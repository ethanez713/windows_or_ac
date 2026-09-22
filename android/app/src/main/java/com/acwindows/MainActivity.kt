package com.acwindows

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

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
private fun CenterBox(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

/**
 * Placeholder chips shown while the first forecast is still loading, sized to land on
 * the real layout rather than near it: same 84dp/96dp gutters, same 26dp open-window
 * flag strip, same 40dp sparkline, same stacked summary, and text rows that reserve the
 * real line box via [SkeletonTextChip]. A loaded collapsed [DayCard] measures 141dp
 * (14 + 26 + 53 + 6 + 28 + 14) and the header 48dp (10 + 28 + 10).
 *
 * The FIRST card is drawn EXPANDED, because today's card opens by default — so the
 * placeholder has to reserve the open card's height or the list jumps the moment data
 * lands. Its extra chrome is the Values-mode layout appended below the collapsed body,
 * built from the same dp constants [DayCard]/[AbsoluteCharts] use: Spacer(14) + readout
 * line (a ~28dp themed line box) + Spacer(10) + 124dp temp chart + Spacer(12) + a 140dp
 * combined chart (the 124dp plot plus the 16dp [ICON_BAND] reserved below it for
 * [CombinedChart]'s precip-type icons) = 328dp. That puts the expanded skeleton at
 * 141 + 328 = 469dp — up 16dp from the 453dp (141 + 312) figure measured on device
 * before the icon band existed; not reverified against a live device since this
 * project's rule is no screenshots/emulator runs without being asked.
 *
 * Two deliberate assumptions for both the collapsed body and that 452.6dp target, both
 * the common case: the day has an open-window flag (26dp) and no rain line in its summary
 * (a rain line would add another 28dp); the expanded readout also assumes one line.
 *
 * None of this dp scaffold grows with the app text-size setting (see [AcWindowsTheme]) —
 * that setting only scales `sp`, not `dp`. At 200% the skeleton's placeholder heights will
 * drift a bit from the real, now-taller cards; not worth chasing for a loading placeholder.
 */
@Composable
private fun SkeletonForecast() {
    val chip = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Sources banner — same surface, padding and single 13sp line as SourceStatusHeader.
        Surface(Modifier.fillMaxWidth(), color = Skin.chrome.card, shape = RoundedCornerShape(10.dp)) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonTextChip(chip, width = 170.dp, fontSize = 13.sp, chipHeight = 14.dp)
            }
        }
        repeat(5) { i ->
            // Gutters copied from DayCard so the swap is a fill, not a re-layout.
            val dateW = 84.dp
            val summaryW = 96.dp
            val barPad = 8.dp
            ElevatedCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Skin.chrome.card),
            ) {
                Column(Modifier.padding(14.dp)) {
                    // The open-window flag strip. Most days carry a span boundary, so
                    // reserving its 26dp is what keeps the card at a real card's height.
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.width(dateW))
                        Box(Modifier.weight(1f).padding(horizontal = barPad).height(26.dp)) {
                            SkeletonChip(chip, Modifier.align(Alignment.BottomStart).width(58.dp).height(15.dp))
                            SkeletonChip(chip, Modifier.align(Alignment.BottomEnd).width(58.dp).height(15.dp))
                        }
                        Spacer(Modifier.width(summaryW))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(dateW)) {
                            SkeletonTextChip(chip, width = 64.dp, fontSize = 15.sp, chipHeight = 16.dp)
                        }
                        SkeletonChip(
                            chip,
                            Modifier.weight(1f).padding(horizontal = barPad).height(40.dp),   // sparkline
                        )
                        // Weather glyphs + high/low, stacked and end-aligned like
                        // DaySummaryView (this column, not the sparkline, sets the row height).
                        Column(Modifier.width(summaryW), horizontalAlignment = Alignment.End) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SkeletonChip(chip, Modifier.size(22.dp))
                                SkeletonChip(chip, Modifier.size(22.dp))
                            }
                            Spacer(Modifier.height(3.dp))
                            SkeletonTextChip(chip, width = 64.dp, fontSize = 15.sp, chipHeight = 16.dp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // "N hours to open windows"
                    SkeletonTextChip(chip, width = 150.dp, fontSize = 12.sp, chipHeight = 14.dp)

                    // First card = expanded today. Mirror AbsoluteCharts' exact dp scaffold
                    // (see the doc comment) so data fills it instead of growing the list.
                    if (i == 0) {
                        Spacer(Modifier.height(14.dp))
                        SkeletonTextChip(chip, width = 240.dp, fontSize = 11.sp, chipHeight = 12.dp)  // readout
                        Spacer(Modifier.height(10.dp))
                        SkeletonChip(chip, Modifier.fillMaxWidth().height(124.dp))                   // temp chart
                        Spacer(Modifier.height(12.dp))
                        SkeletonChip(chip, Modifier.fillMaxWidth().height(140.dp))                   // combined chart (124 plot + 16 icon band)
                    }
                }
            }
        }
    }
}

/**
 * A chip standing in for a line of text. The invisible glyph behind it reserves exactly
 * the line box the real Text occupies, so the skeleton inherits the app's text metrics
 * instead of hardcoding them — worth doing because that box is 28dp here whatever the
 * font size (lineHeight comes from the theme, not from fontSize), which is what made the
 * hand-sized chips come up ~8dp short per text row. The chip itself is drawn at roughly
 * the glyphs' ink height and centred, landing where the real text's ink lands.
 */
@Composable
private fun SkeletonTextChip(color: Color, width: Dp, fontSize: TextUnit, chipHeight: Dp) {
    Box(contentAlignment = Alignment.Center) {
        Text("0", fontSize = fontSize, color = Color.Transparent)
        SkeletonChip(color, Modifier.width(width).height(chipHeight))
    }
}

@Composable
private fun SkeletonChip(color: Color, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(color))
}

// stepMinutes defaults to 60 (the hourly case) so every existing call site that doesn't
// know about the now-view still compiles unchanged.
private data class DayForecast(val date: String, val points: List<ForecastPoint>, val stepMinutes: Int = 60)

// DayViewMode (AppConfig.kt) isn't Parcelable, so rememberSaveable needs an explicit
// Saver — round-trip it through its enum name, same trick as everywhere else in Android
// that saves an enum across a config change.
private val DayViewModeSaver = Saver<DayViewMode, String>(
    save = { it.name },
    restore = { DayViewMode.valueOf(it) },
)

/**
 * Today's now-view series: from the current moment to the end of the local day, built
 * from the highest-resolution data available (see the GOAL doc for the priority order).
 * [todayHourly] is today's ordinary hourly [DayForecast] — used both as the fallback
 * source and as the final fallback value, so a now-view card is never left with nothing
 * to draw (e.g. the last hourly row of the day, right before midnight).
 */
private fun nowSeriesFor(state: AppState, todayHourly: DayForecast): DayForecast {
    val date = todayHourly.date
    val now = java.time.LocalDateTime.now()
    val nowQuarterIso = now.withMinute((now.minute / 15) * 15).withSecond(0).withNano(0)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    state.nearTerm?.let { nt ->
        val pts = nt.points.filter { it.time.take(10) == date && it.time.take(16) >= nowQuarterIso }
        if (pts.isNotEmpty()) return DayForecast(date, pts, nt.stepMinutes)
    }
    val nowHourIso = now.withMinute(0).withSecond(0).withNano(0)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    val hourlyPts = todayHourly.points.filter { it.time.take(16) >= nowHourIso }
    return if (hourlyPts.isNotEmpty()) DayForecast(date, hourlyPts, 60) else todayHourly
}

@Composable
private fun ForecastScreen(
    state: AppState,
    location: SavedLocation,
    locationMode: LocationProvider.LocationMode,
    updatedAtMs: Long,
    refreshing: Boolean,
) {
    val context = LocalContext.current
    // Keyed on the refresh time too: for the Current location a fetch can move the
    // coordinates and the place label, and the header has to follow.
    val cfg = remember(location, updatedAtMs) { AppConfig.load(context, location) }
    val unit = remember(updatedAtMs) { TempUnit.load(context) }
    val comfort = cfg.comfort
    val threshold = comfort.openScoreMin
    val days = remember(state) {
        state.forecast.points.groupBy { it.time.take(10) }
            .map { (d, pts) -> DayForecast(d, pts, state.forecast.stepMinutes) }
            .sortedBy { it.date }
    }
    // Open spans computed across the WHOLE forecast (not per-day), so a window that
    // crosses midnight is one continuous span — its boundary flags land on the real
    // start/end day, never split at midnight.
    val spans = remember(state, threshold) { openSpansTimes(state.forecast.points, threshold) }
    // Which card is expanded. The default is today (falling back to the first day),
    // computed synchronously so the very first frame with data already draws today open —
    // seeding it from a post-composition LaunchedEffect made the card render collapsed for
    // one frame and then expand, a visible jump. `userPicked` records that the user has
    // taken over; until then we show the default. A real pick can be null (they collapsed
    // the open card), so a separate flag — not a nullable sentinel — is what distinguishes
    // "user chose nothing open" from "untouched". Both are saveable, so a config change
    // keeps the user's choice.
    val todayDate = remember { LocalDate.now().toString() }
    val defaultExpanded = remember(days) {
        if (days.isEmpty()) null else (days.firstOrNull { it.date == todayDate } ?: days.first()).date
    }
    var userPick by rememberSaveable { mutableStateOf<String?>(null) }
    var userPicked by rememberSaveable { mutableStateOf(false) }
    val expanded = if (userPicked) userPick else defaultExpanded
    var showSources by rememberSaveable { mutableStateOf(false) }

    // Today's card only: whole-day (hourly, like every other card) vs. now-to-EOD. Seeded
    // from the persisted default and re-persisted on every toggle — see AppConfig.DayViewMode.
    var viewMode by rememberSaveable(stateSaver = DayViewModeSaver) { mutableStateOf(cfg.dayViewMode) }
    val onToggleView: () -> Unit = {
        viewMode = if (viewMode == DayViewMode.NOW) DayViewMode.TODAY else DayViewMode.NOW
        AppConfig.saveDayViewMode(context, viewMode)
    }
    val todayHourly = remember(days, todayDate) { days.firstOrNull { it.date == todayDate } }
    // The now-view series is a SEPARATE series from the hourly one (see AppState.nearTerm's
    // doc comment) — only swapped into the displayed list for today's card, never spliced
    // into `days` itself, so every other day keeps reading the plain hourly data.
    val nowDay = remember(state, viewMode, todayHourly) {
        if (viewMode == DayViewMode.NOW && todayHourly != null) nowSeriesFor(state, todayHourly) else null
    }
    val displayDays = remember(days, nowDay) {
        if (nowDay == null) days else days.map { if (it.date == nowDay.date) nowDay else it }
    }
    // Spans for the now-view series are computed over THAT series, not the hourly one —
    // otherwise a flag time from the hourly spans wouldn't exist in the now-view's rows
    // and would just silently fail to draw (or land on the wrong row).
    val nowSpans = remember(nowDay, threshold) { nowDay?.let { openSpansTimes(it.points, threshold) } ?: emptyList() }

    if (days.isEmpty()) {
        // A fetch in flight may have current conditions but no hourly forecast yet — show
        // skeletons rather than flashing "No forecast available" before the data lands.
        if (refreshing) SkeletonForecast() else CenterBox { Text("No forecast available") }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            SourceStatusHeader(
                state, updatedAtMs, location.placeLabel,
                pin = locationMode.takeIf { location.isCurrent },
                onLocationClick = { context.startActivity(Intent(context, LocationsActivity::class.java)) },
                onClick = { showSources = true },
            )
        }
        items(displayDays, key = { it.date }) { day ->
            val isToday = day.date == todayDate
            DayCard(
                day = day,
                comfort = comfort,
                threshold = threshold,
                cfg = cfg,
                globalSpans = if (isToday && viewMode == DayViewMode.NOW) nowSpans else spans,
                expanded = expanded == day.date,
                unit = unit,
                isToday = isToday,
                viewMode = viewMode,
                onToggleView = onToggleView,
                onToggle = { userPicked = true; userPick = if (expanded == day.date) null else day.date },
            )
        }
    }
    if (showSources) SourceDetailDialog(state.sources, state.consensus, unit) { showSources = false }
}

@Composable
private fun DayCard(
    day: DayForecast,
    comfort: Comfort,
    threshold: Double,
    cfg: AppConfig,
    globalSpans: List<Pair<String, String>>,
    expanded: Boolean,
    unit: TempUnit,
    isToday: Boolean,
    viewMode: DayViewMode,
    onToggleView: () -> Unit,
    onToggle: () -> Unit,
) {
    val openRows = day.points.count { it.score >= threshold }
    ElevatedCard(
        Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.elevatedCardColors(containerColor = Skin.chrome.card),
    ) {
        // 14dp, not the design's stated 12: that 12 is paired with a 1dp border on both the
        // frame and the card, and these cards are borderless, so 14 keeps the same content box.
        Column(Modifier.padding(14.dp)) {
            // The label and the trailing glyphs+hi/lo group are FIXED width; the sparkline
            // takes the slack between them with weight(1f). That keeps its width identical on
            // every card — so the bars start and end at the same x down the list — while
            // giving the score bars the most horizontal room the row can spare.
            val labelW = 64.dp
            val colGap = 6.dp
            val trailingW = 104.dp
            // Open-window time flags ride ABOVE the bars (so they never compress them),
            // in a strip aligned to the sparkline column. Only shown on days a flag lands on.
            val flagTimes = remember(day) { day.points.map { it.time }.toSet() }
            val hasFlags = remember(globalSpans, flagTimes) {
                globalSpans.any { (s, e) -> s in flagTimes || e in flagTimes }
            }
            // Index of the LAST row at/before now, resolution-aware (see [nowIdxOf]) — only
            // matches on the day/series containing "now", so the marker lands on today's
            // graphs only (and, in NOW mode, on the right 15-minute row rather than the
            // hour-prefix match four rows could tie on).
            val nowIdx = remember(day) { nowIdxOf(day.points) }
            // Hour picked by scrubbing the expanded charts; it also drives the marker on the
            // score bars above. Cleared when the card collapses or the day's rows change
            // (length / first timestamp), not on every new list instance — a refresh of the
            // same series would otherwise discard the state the in-flight pointerInput still
            // writes to, and the default-open today card would look unscubbable.
            var sel by remember(expanded, day.date, day.points.size, day.points.firstOrNull()?.time) {
                mutableStateOf<Int?>(null)
            }
            if (hasFlags) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(labelW + colGap))
                    OpenWindowFlags(day.points, globalSpans, day.stepMinutes, Modifier.weight(1f).height(26.dp))
                    Spacer(Modifier.width(colGap + trailingW))
                }
            }
            // "Now" only on today's card in NOW mode — every other card (and today's card
            // in TODAY mode) keeps the ordinary date label.
            val label = if (isToday && viewMode == DayViewMode.NOW) "Now" else dayLabel(day.date)
            val summary = remember(day.points) { summarize(day.points) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label, Modifier.width(labelW), fontFamily = Ui, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = Skin.chrome.heading,
                )
                Spacer(Modifier.width(colGap))
                DaySparkline(day.points, threshold, nowIdx, sel, Modifier.weight(1f).height(34.dp))
                Spacer(Modifier.width(colGap))
                // A 2x2 block pinned to the right edge: hi/lo temperature on top, the two
                // day/night glyphs below. Both a step larger than the row text, since this
                // fixed column has the room and the info earns it.
                Column(
                    Modifier.width(trailingW),
                    horizontalAlignment = Alignment.End,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            summary.highF?.let { unit.deg(it.toDouble()) } ?: "—°",
                            fontFamily = Mono, fontSize = 18.sp, color = Skin.chrome.heading,
                        )
                        Text(
                            " " + (summary.lowF?.let { unit.deg(it.toDouble()) } ?: "—°"),
                            fontFamily = Mono, fontSize = 15.sp, color = Skin.chrome.faint,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WeatherGlyph(summary.morningCode, isDay = true, size = 20.dp)
                        Spacer(Modifier.width(4.dp))
                        WeatherGlyph(summary.nightCode, isDay = false, size = 20.dp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // The view-switch hint rides in the SAME row as the open-hours line (weighted
            // 1f so the hint doesn't steal its width) rather than a row of its own, so the
            // card's height doesn't grow by adding it — which is the whole requirement here,
            // and is fiddlier than it looks: a smaller fontSize does NOT mean a shorter Text.
            // Compose merges only fontSize onto the ambient LocalTextStyle, so the 11sp hint
            // inherits bodyLarge's 24sp lineHeight and lays out exactly as tall as the 12sp
            // line beside it (the same quirk SkeletonTextChip's comment documents). The hint
            // therefore carries NO vertical padding — 2dp of it would grow the card by 4dp
            // and put SkeletonForecast's hand-measured scaffold out by the same amount. Its
            // touch target is already the full ~28dp line box; only horizontal padding is
            // needed, to widen the tap area and inset the rounded highlight.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    openWindowLabel(openRows, day.stepMinutes),
                    fontFamily = Ui,
                    fontSize = 12.sp,
                    color = if (openRows > 0) Skin.data.score else Skin.chrome.faint,
                )
                if (isToday) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            // An inner clickable consumes the tap before it reaches the
                            // card's own clickable (the expand/collapse toggle) — nesting
                            // is enough, no explicit consume()/interaction-source needed.
                            .clickable { onToggleView() }
                            .padding(horizontal = 10.dp),
                    ) {
                        Text(
                            if (viewMode == DayViewMode.NOW) "Now ›" else "Today ›",
                            fontFamily = Mono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Skin.data.score,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // A day with open hours already shows their timing in the flag strip above
                // the bars, so this slot only carries the closed-day reason.
                if (openRows == 0) {
                    limitingFactor(comfort, day.points)?.let { lim ->
                        Text(
                            "${lim.factor.label()} ${readingOf(lim.factor, lim.point) ?: ""}",
                            fontFamily = Mono, fontSize = 11.sp,
                            color = Color(Tokens.factorHue(Skin.data.raw, lim.factor)),
                            maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Skin.chrome.hairline)
                Spacer(Modifier.height(10.dp))
                DetailDrawer(
                    day.points, unit,
                    palette = cfg.aqiPalette,
                    thunderAlertPct = cfg.thunderAlertPct,
                    isToday = isToday,
                    sel = sel,
                    onSel = { sel = it },
                )
            }
        }
    }
}

/**
 * Prominent header for the current data view: where the forecast is for, how many sources
 * were successfully polled (X/N), the consensus confidence colour, and when this data was
 * fetched. Tap the place to manage locations; tap anywhere else for the per-source
 * breakdown — handy when the reported numbers look off versus a local reading.
 */
@Composable
private fun SourceStatusHeader(
    state: AppState,
    updatedAtMs: Long,
    place: String,
    pin: LocationProvider.LocationMode?,
    onLocationClick: () -> Unit,
    onClick: () -> Unit,
) {
    val c = state.consensus
    val ok = c.sourcesOk.size
    val total = maxOf(c.sourcesAttempted, ok + c.sourcesFailed.size)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val dot = confidenceColor(c.confidence)
    val updated = remember(updatedAtMs) {
        if (updatedAtMs > 0)
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(updatedAtMs))
        else null
    }
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onClick() },
        color = Skin.chrome.card,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Condensed single line: "📍 City, ST ▾ · ● 6/6 · 3:45 PM  ›". The city name
            // ellipsizes inside the weighted inner row so the count/time never get pushed
            // off; the chevron stays pinned to the right edge. The place is its own
            // clickable — the tap that opens the Locations screen — and the rest of the
            // row still opens the source breakdown.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                LocationChip(Modifier.weight(1f, fill = false), onLocationClick) {
                    pin?.let {
                        LocationPin(it, onVar)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        place,
                        Modifier.weight(1f, fill = false),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(" ▾", fontSize = 12.sp, color = primary)
                }
                Text(" · ", fontSize = 13.sp, color = onVar)
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                Text("$ok/$total", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                updated?.let { Text(" · $it", fontSize = 12.sp, color = onVar, maxLines = 1, softWrap = false) }
            }
            Text(" ›", fontSize = 13.sp, color = primary)
        }
    }
}

@Composable
private fun LocationPin(mode: LocationProvider.LocationMode, tint: Color) {
    Icon(
        painterResource(mode.iconRes),
        contentDescription = mode.label,
        tint = tint,
        modifier = Modifier.size(14.dp),
    )
}

/** The header's tappable location area — its own click target inside the wider banner. */
@Composable
private fun LocationChip(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) = Row(
    modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }.padding(vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
)

private fun confidenceColor(confidence: String): Color = when (confidence) {
    "high" -> OpenGreen
    "degraded" -> AcBlue
    "conflict" -> UnknownRed
    else -> ClosedSlate
}

private data class SourceMeta(val name: String, val url: String?)

private fun sourceMeta(source: String): SourceMeta = when (source) {
    "open-meteo" -> SourceMeta("Open-Meteo", "https://open-meteo.com/")
    "nws" -> SourceMeta("NWS · weather.gov", "https://www.weather.gov/")
    "metno" -> SourceMeta("MET Norway", "https://www.met.no/en")
    else -> SourceMeta(source, null)
}

/** One readable line of whatever a source actually returned. */
private fun sourceValues(r: SourceReading, unit: TempUnit): String {
    val parts = mutableListOf<String>()
    r.tempF?.let { parts.add(unit.degUnit(it)) }
    r.dewPointF?.let { parts.add("dew ${unit.deg(it)}") }
    r.humidityPct?.let { parts.add("${it.roundToInt()}% RH") }
    r.windMph?.let { parts.add("${it.roundToInt()} mph") }
    r.aqi?.let { parts.add("AQI $it") }
    return if (parts.isEmpty()) "no values" else parts.joinToString(" · ")
}

/** The merged numbers the app actually reports (the average across the OK sources). */
private fun consensusValues(c: Consensus, unit: TempUnit): String {
    val parts = mutableListOf<String>()
    c.tempF?.let { parts.add(unit.degUnit(it)) }
    c.dewPointF?.let { parts.add("dew ${unit.deg(it)}") }
    c.humidityPct?.let { parts.add("${it.roundToInt()}% RH") }
    c.windMph?.let { parts.add("${it.roundToInt()} mph") }
    c.aqi?.let { parts.add("AQI $it") }
    return if (parts.isEmpty()) "no values" else parts.joinToString(" · ")
}

@Composable
private fun SourceDetailDialog(sources: List<SourceReading>, consensus: Consensus, unit: TempUnit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Data sources") },
        text = {
            Column {
                // What the app reports = the average of the OK sources below. Surfaced up top
                // so a value that looks off can be traced to which source is pulling it.
                Text("Reported (averaged)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(consensusValues(consensus, unit), fontSize = 12.sp, color = onVar)
                Spacer(Modifier.height(8.dp))
                Text("Per source", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (sources.isEmpty()) Text("No source detail available.", color = onVar)
                sources.forEach { r ->
                    val meta = sourceMeta(r.source)
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (r.ok) OpenGreen else UnknownRed))
                            Spacer(Modifier.width(8.dp))
                            Text(meta.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (meta.url != null) {
                                Text(
                                    "open ↗", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(meta.url))) }
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        if (r.ok) Text(sourceValues(r, unit), fontSize = 12.sp, color = onVar)
                        else Text(r.error ?: "unavailable", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Consensus: ${consensus.confidence}" +
                        (if (consensus.tempSpreadF > 0) " · temp spread ${consensus.tempSpreadF.roundToInt()}°F" else ""),
                    fontSize = 11.sp, color = onVar,
                )
            }
        },
    )
}

/**
 * Score bars for one day. Bars use the full canvas height (the open-window flags now
 * live in their own strip ABOVE — see [OpenWindowFlags] — so they no longer steal bar
 * height).
 */
@Composable
private fun DaySparkline(
    points: List<ForecastPoint>,
    threshold: Double,
    nowIdx: Int,
    selectedIdx: Int?,
    modifier: Modifier,
) {
    val d = Skin.data
    val open = d.score
    val closed = d.blockedBase
    val selColor = Skin.chrome.heading
    Canvas(modifier) {
        val n = points.size.coerceAtLeast(1)
        val bw = size.width / n
        val plotBot = size.height
        val plotH = plotBot

        points.forEachIndexed { i, p ->
            val h = (p.score / 100.0).toFloat() * plotH
            // Open hours are vivid green; closed hours are a desaturated grey tinted by their
            // dominant detractor (falling back to a flat grey when the breakdown is unknown).
            val barColor = if (p.score >= threshold) open else {
                val factor = p.breakdown?.limiting()
                if (factor != null) Color(Tokens.blockedBar(d.raw, Tokens.factorHue(d.raw, factor))) else closed
            }
            drawRect(
                color = barColor,
                topLeft = Offset(i * bw, plotBot - h),
                size = Size(bw * 0.7f, h),
            )
        }

        // Where the score bottoms out (≈0), colour the baseline by the most-penalizing
        // factor, so a closed stretch still shows *why* it's closed. Extreme heat (≥90°F)
        // overrides that mark with red.
        val markH = 2.5f.dp.toPx()
        points.forEachIndexed { i, p ->
            if (p.score > 0.5) return@forEachIndexed
            val b = p.breakdown ?: return@forEachIndexed
            val color = if ((p.tempF ?: 0.0) >= HOT_F) HotRed
                else Color(Tokens.factorHue(d.raw, b.limiting() ?: return@forEachIndexed))
            drawRect(color, topLeft = Offset(i * bw, plotBot - markH), size = Size(bw * 0.7f, markH))
        }

        // Static "now" marker (today only — nowIdx is -1 on other days), centred on the bar.
        if (nowIdx in 0 until n) nowMarker(nowIdx * bw + bw * 0.35f, 0f, plotBot, d.now)

        // The hour picked in the expanded charts below, as a solid line through its bar, so
        // the selected score is easy to read off these bars.
        if (selectedIdx in 0 until n) {
            val x = selectedIdx!! * bw + bw * 0.35f
            drawLine(selColor, Offset(x, 0f), Offset(x, plotBot), strokeWidth = 1.5f.dp.toPx())
        }
    }
}

/**
 * The open-window time pills, drawn in a strip ABOVE the day's bars and aligned to the
 * sparkline column (so a pill never overlaps a bar). Flags come from the GLOBAL spans: a
 * span's start flag shows on the day it begins, its end flag on the day it ends — so a
 * window across midnight is one continuous span with no boundary flag. The stagger is
 * bottom-anchored so the pills sit just over the bars.
 *
 * [stepMinutes] is [points]' own row width, needed for the end flag: [ForecastBuilder.fmtRowEnd]
 * closes a row at its start + stepMinutes, not always +1 hour, so a 15-minute now-view row
 * doesn't get labelled 45 minutes later than it actually ends.
 */
@Composable
private fun OpenWindowFlags(
    points: List<ForecastPoint>,
    globalSpans: List<Pair<String, String>>,
    stepMinutes: Int,
    modifier: Modifier,
) {
    val tm = rememberTextMeasurer()
    val pillBg = Color(0xE6121821)
    val pillFg = Color(0xFFE6EAF0)
    Canvas(modifier) {
        val n = points.size.coerceAtLeast(1)
        val bw = size.width / n
        val padX = 4.dp.toPx()
        val padY = 1.5f.dp.toPx()
        val rowH = 12.dp.toPx()
        val pillStyle = TextStyle(color = pillFg, fontSize = 9.sp, fontWeight = FontWeight.Bold)

        val timeOf = points.map { it.time }
        val centers = mutableListOf<Float>()
        val texts = mutableListOf<String>()
        globalSpans.forEach { (startIso, endIso) ->
            val si = timeOf.indexOf(startIso)
            // Start flag keeps naming the row's own hour (unaffected by resolution — see
            // the GOAL doc: only the end flag over-reports).
            if (si >= 0) { centers.add(si * bw); texts.add(hourLabelOf(ForecastBuilder.hour(startIso))) }
            val ei = timeOf.indexOf(endIso)
            if (ei >= 0) { centers.add((ei + 1) * bw); texts.add(ForecastBuilder.fmtRowEnd(endIso, stepMinutes)) }
        }
        if (centers.isEmpty()) return@Canvas
        val layouts = texts.map { tm.measure(it, pillStyle) }
        val lefts = FloatArray(centers.size)
        val rows = IntArray(centers.size)
        val rowRight = floatArrayOf(-1e9f, -1e9f)
        var maxRow = 0
        centers.indices.sortedBy { centers[it] }.forEach { idx ->
            val w = layouts[idx].size.width + 2 * padX
            val left = (centers[idx] - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
            val row = if (left >= rowRight[0] + 2.dp.toPx()) 0 else 1
            lefts[idx] = left; rows[idx] = row; rowRight[row] = left + w
            if (row > maxRow) maxRow = row
        }
        // Bottom-anchor the stagger so the lowest pill row sits right above the bars.
        val bandH = (maxRow + 1) * rowH
        val offsetY = (size.height - bandH).coerceAtLeast(0f)
        for (idx in centers.indices) {
            val l = layouts[idx]
            val w = l.size.width + 2 * padX
            val h = l.size.height + 2 * padY
            val top = offsetY + rows[idx] * rowH
            drawRoundRect(pillBg, topLeft = Offset(lefts[idx], top), size = Size(w, h), cornerRadius = CornerRadius(4.dp.toPx()))
            drawText(l, topLeft = Offset(lefts[idx] + padX, top + padY))
        }
    }
}


/** Static dashed "now" marker at the current hour (today's graphs only). When [tm] is
 *  given, also draws a small "now" label clamped on-screen. */
private fun DrawScope.nowMarker(
    cx: Float, top: Float, bottom: Float, ink: Color, tm: TextMeasurer? = null,
) {
    drawLine(
        ink.copy(alpha = 0.85f), Offset(cx, top), Offset(cx, bottom),
        strokeWidth = 1.5f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
    )
    tm?.let {
        val l = it.measure("now", TextStyle(color = ink, fontSize = 9.sp, fontWeight = FontWeight.Bold))
        val x = (cx + 2.dp.toPx()).coerceAtMost(size.width - l.size.width)
        drawText(l, topLeft = Offset(x.coerceAtLeast(0f), top))
    }
}


/**
 * Index of the LAST row whose start is <= now, resolution-aware: compares the full
 * "yyyy-MM-ddTHH:mm" local prefix as a string rather than just the hour, since these are
 * local ISO timestamps and lexicographic order matches chronological order. The old
 * hour-prefix match tied on all four rows of a 15-minute hour and landed on the first
 * (wrong) one; this lands on the row actually covering "now" at any resolution. -1 (no
 * marker) whenever every row is in the future — which is every day but the one containing
 * "now", exactly as before.
 */
private fun nowIdxOf(points: List<ForecastPoint>): Int {
    val nowPrefix = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    val i = points.indexOfLast { it.time.take(16) <= nowPrefix }
    // Every row being in the past doesn't mean one of them is "now" — for a day that has
    // already finished, indexOfLast happily returns its final row. Only a row on today's
    // date can be the current one, which is the invariant the old hour-prefix match had
    // for free and this comparison has to restore explicitly.
    return if (i >= 0 && points[i].time.take(10) == LocalDate.now().toString()) i else -1
}

/**
 * "N hours to open windows" at any row width. A row COUNT isn't a duration once rows are
 * shorter than an hour (see [Forecast.stepMinutes]), so this multiplies out to minutes
 * first: a whole number of hours stays "N hours", a clean half becomes "N.5 hours", and
 * anything under an hour is "N min". A remainder that's neither whole nor half (only
 * reachable at 15-minute resolution, e.g. 7 open 15-minute rows = 1h45m) spells out both
 * parts rather than inventing a quarter-hour decimal the rest of the app doesn't use.
 */
private fun openWindowLabel(rowCount: Int, stepMinutes: Int): String {
    if (rowCount <= 0) return "No open-window hours"
    val totalMin = rowCount * stepMinutes
    if (totalMin < 60) return "$totalMin min to open windows"
    val hours = totalMin / 60
    val rem = totalMin % 60
    val hourWord = if (hours == 1) "hour" else "hours"
    return when (rem) {
        0 -> "$hours $hourWord to open windows"
        30 -> "$hours.5 hours to open windows"
        else -> "$hours $hourWord $rem min to open windows"
    }
}

/** Contiguous open-worthy runs across the whole forecast: (startIso, lastOpenIso). */
private fun openSpansTimes(points: List<ForecastPoint>, thr: Double): List<Pair<String, String>> {
    val spans = mutableListOf<Pair<String, String>>()
    var i = 0
    while (i < points.size) {
        if (points[i].score >= thr) {
            var j = i
            while (j < points.size && points[j].score >= thr) j++
            spans.add(points[i].time to points[j - 1].time)
            i = j
        } else {
            i++
        }
    }
    return spans
}

/** Hour-of-day (wraps; e.g. 24 → 0) to a label like "4 AM" / "12 PM". */
private fun hourLabelOf(hour: Int): String {
    val h = ((hour % 24) + 24) % 24
    val ampm = if (h < 12) "AM" else "PM"
    val h12 = (h % 12).let { if (it == 0) 12 else it }
    return "$h12 $ampm"
}

/** "2026-06-16T21:00" -> "9 PM". */
private fun fmt(iso: String): String = ForecastBuilder.fmtHour(iso)

// Every day but today is a weekday + date ("Wed 9/10"), a fixed-width label — "Tomorrow"
// was the one variable-width name and it overran the day column.
private fun dayLabel(date: String): String = try {
    val d = LocalDate.parse(date)
    if (d == LocalDate.now()) "Today" else d.format(DateTimeFormatter.ofPattern("EEE M/d"))
} catch (e: Exception) {
    date
}
