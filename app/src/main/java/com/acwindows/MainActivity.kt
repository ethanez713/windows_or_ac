package com.acwindows

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val state: AppState? = null,
    val error: String? = null,
    val updatedAtMs: Long = 0L,
)

/**
 * Fetches [AppState] off the main thread, one slot per saved location, so switching back to a
 * place already fetched this session is instant; offline-first (keep last data on error).
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val _locations = MutableStateFlow(Locations.load(app))
    val locations: StateFlow<List<SavedLocation>> = _locations.asStateFlow()

    private val _selectedId = MutableStateFlow(Locations.selected(app).id)
    val selectedId: StateFlow<String> = _selectedId.asStateFlow()

    private val slots = MutableStateFlow<Map<String, AppUiState>>(emptyMap())
    val ui: StateFlow<AppUiState> = combine(slots, _selectedId) { m, id -> m[id] ?: AppUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    init { refresh() }

    /** Show [id], fetching it when this session has nothing for it or what it has is stale. */
    fun select(id: String) {
        if (id == _selectedId.value) return
        Locations.select(getApplication(), id)
        _selectedId.value = id
        val slot = slots.value[id]
        if (slot?.state == null || System.currentTimeMillis() - slot.updatedAtMs > WidgetCache.TTL_MS) refresh()
    }

    /** Step through the list in carousel order, wrapping at both ends. */
    fun selectAdjacent(step: Int) {
        val list = _locations.value
        if (list.size < 2) return
        val i = list.indexOfFirst { it.id == _selectedId.value }.coerceAtLeast(0)
        select(list[(i + step).mod(list.size)].id)
    }

    /** Pick up edits made on the Locations screen: order, nicknames, removals, a new fix. */
    fun reloadLocations() {
        val app = getApplication<Application>()
        val list = Locations.load(app)
        val ids = list.map { it.id }.toSet()
        _locations.value = list
        slots.update { m -> m.filterKeys { it in ids } }
        val selected = Locations.selected(app).id
        if (selected != _selectedId.value) {
            _selectedId.value = selected
            if (slots.value[selected]?.state == null) refresh()
        }
    }

    private fun slot(id: String, f: (AppUiState) -> AppUiState) =
        slots.update { m -> m + (id to f(m[id] ?: AppUiState())) }

    /** Locations whose in-flight fetch is already out of date and must run again when it lands. */
    private val rerun = mutableSetOf<String>()

    /** [force]: a fetch already running is stale (e.g. location permission just landed). */
    fun refresh(force: Boolean = false) {
        val id = _selectedId.value
        if (slots.value[id]?.refreshing == true) {
            if (force) rerun += id
            return
        }
        slot(id) { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                // Progressive: surface the first state as soon as 3+ sources answer, then
                // refresh it (and the "N/X sources" count + graphs) on every later arrival.
                // loadResolved: for the Current location, re-reads the fix before the fetch,
                // so the app and the widget agree on where "here" is.
                val app = getApplication<Application>()
                val cfg = AppConfig.loadResolved(app, Locations.byId(app, id))
                if (id == Locations.CURRENT_ID) _locations.value = Locations.load(app)
                WeatherRepository.fetchAppStateProgressive(cfg, ResponseCache(app)) { partial ->
                    val empty = partial.consensus.confidence == "none" && partial.forecast.points.isEmpty()
                    if (!empty) slot(id) {
                        // Keep refreshing=true across partials: an early partial may carry current
                        // conditions but no hourly forecast yet, and the UI shows skeleton chips
                        // (not "No forecast available") for as long as a fetch is still in flight.
                        it.copy(
                            loading = false, state = partial,
                            updatedAtMs = System.currentTimeMillis(), error = null,
                        )
                    }
                }
            }
            slot(id) { cur ->
                result.fold(
                    onSuccess = { newState ->
                        // Don't clobber good data with an empty result (all sources failed but no
                        // exception) — keep the last state and surface a soft notice instead.
                        val empty = newState.consensus.confidence == "none" && newState.forecast.points.isEmpty()
                        if (empty && cur.state != null) {
                            cur.copy(loading = false, refreshing = false, error = "Refresh failed — showing last data")
                        } else {
                            cur.copy(
                                loading = false, refreshing = false, state = newState,
                                updatedAtMs = System.currentTimeMillis(), error = null,
                            )
                        }
                    },
                    onFailure = { cur.copy(loading = false, refreshing = false, error = it.message ?: "fetch failed") },
                )
            }
            if (rerun.remove(id) && id == _selectedId.value) refresh()
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Read once at launch (not observed live) — same pattern as every other config
        // read in this app; a change made in ConfigActivity takes effect on next launch.
        val textScale = AppConfig.load(this).appTextScalePct / 100f
        setContent { AcWindowsTheme(textScale) { AppRoot() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val locations by vm.locations.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    val context = LocalContext.current
    val selected = locations.firstOrNull { it.id == selectedId } ?: locations.first()

    // Back from the Locations screen (or anywhere): its edits are already saved, so re-read.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reloadLocations() }

    // Re-read per fetch and resume: the permission or the system location switch can change
    // while the app sits open.
    val locationMode = remember(ui.updatedAtMs, locations) { LocationProvider.mode(context, true) }

    // Current location without permission would silently forecast the last saved fix, so
    // ask — once per launch, and only when Current is what's on screen.
    var askedPermission by rememberSaveable { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { if (LocationProvider.hasPermission(context)) vm.refresh(force = true) }
    LaunchedEffect(selected.isCurrent) {
        if (selected.isCurrent && !askedPermission && !LocationProvider.hasPermission(context)) {
            askedPermission = true
            askPermission.launch(LocationProvider.permissions)
        }
    }

    // Pull down anywhere on the list to refresh (replaces the old top-bar button).
    val ptr = rememberPullToRefreshState()
    if (ptr.isRefreshing) LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(ui.refreshing) { if (!ui.refreshing && ptr.isRefreshing) ptr.endRefresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("AC / Windows", fontFamily = Heading, fontSize = 20.sp) },
                actions = { SettingsButton() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (locations.size >= 2) LocationCarousel(locations, selectedId, locationMode, vm::select)
        },
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().nestedScroll(ptr.nestedScrollConnection)
                .locationSwipe(enabled = locations.size >= 2, onSwipe = vm::selectAdjacent),
        ) {
            val s = ui.state
            when {
                // Genuine failure with nothing to show → error + retry.
                s == null && !ui.loading -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(ui.error ?: "No data", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = vm::refresh) { Text("Retry") }
                    }
                }
                // Still loading the first data → skeleton chips, not a spinner or empty message.
                s == null -> SkeletonForecast()
                else -> ForecastScreen(s, selected, locationMode, ui.updatedAtMs, ui.refreshing)
            }
            PullToRefreshContainer(ptr, Modifier.align(Alignment.TopCenter))
        }
    }
}

/**
 * Swipe the forecast sideways to step through locations: left for the next, right for the
 * previous. A chart's scrub consumes its horizontal drag before this sees it, so scrubbing
 * never switches location; anywhere else a horizontal drag past [SWIPE_DISTANCE] does.
 *
 * The content tracks the finger nearly 1:1 and fades as it goes. A committed swipe throws it
 * the rest of the way off-screen, switches, and brings the next location in from the opposite
 * side; a short one springs back.
 */
@Composable
private fun Modifier.locationSwipe(enabled: Boolean, onSwipe: (Int) -> Unit): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    val shift = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val threshold = with(LocalDensity.current) { SWIPE_DISTANCE.toPx() }
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    // pointerInput sits outside the layer so the drag is measured in untranslated space.
    return pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f; width = size.width.toFloat().coerceAtLeast(1f) },
            onDragEnd = {
                val step = when {
                    total <= -threshold -> 1
                    total >= threshold -> -1
                    else -> 0
                }
                scope.launch {
                    if (step == 0) {
                        shift.animateTo(0f, tween(SWIPE_MS))
                        return@launch
                    }
                    shift.animateTo(-step * width, tween(SWIPE_MS))
                    currentOnSwipe(step)
                    shift.snapTo(step * width * SWIPE_ENTER_FROM)
                    shift.animateTo(0f, tween(SWIPE_MS * 3 / 2))
                }
            },
            onDragCancel = { scope.launch { shift.animateTo(0f, tween(SWIPE_MS)) } },
        ) { change, dx ->
            change.consume()
            total += dx
            scope.launch { shift.snapTo(total * SWIPE_TRAIL) }
        }
    }.graphicsLayer {
        translationX = shift.value
        // Gone by the time it has travelled [SWIPE_FADE_BY] of the width.
        alpha = (1f - abs(shift.value) / (width * SWIPE_FADE_BY)).coerceIn(0f, 1f)
    }
}

private val SWIPE_DISTANCE = 72.dp
private const val SWIPE_TRAIL = 0.9f
private const val SWIPE_FADE_BY = 0.6f
private const val SWIPE_ENTER_FROM = 0.35f
private const val SWIPE_MS = 160

/** Tap = app/comfort settings; long-press = widget-only settings. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsButton() {
    val context = LocalContext.current
    Box(
        Modifier.size(48.dp).clip(CircleShape).combinedClickable(
            onClick = { context.startActivity(Intent(context, ConfigActivity::class.java)) },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
    }
}

@Composable
internal fun CenterBox(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
