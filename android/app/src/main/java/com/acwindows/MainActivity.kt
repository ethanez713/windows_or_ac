package com.acwindows

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// ───────────────────────── State + ViewModel ─────────────────────────

data class AppUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val state: AppState? = null,
    val error: String? = null,
    val updatedAtMs: Long = 0L,
)

/** Fetches [AppState] off the main thread; offline-first (keep last data on error). */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(AppUiState())
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_ui.value.refreshing) return
        _ui.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                // Progressive: surface the first state as soon as 3+ sources answer, then
                // refresh it (and the "N/X sources" count + graphs) on every later arrival.
                WeatherRepository.fetchAppStateProgressive(AppConfig.load(getApplication())) { partial ->
                    val empty = partial.consensus.confidence == "none" && partial.forecast.points.isEmpty()
                    if (!empty) _ui.update {
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
            _ui.update { cur ->
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
        }
    }
}

// ───────────────────────── Activity + root ─────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AcWindowsTheme { AppRoot() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    var mode by rememberSaveable { mutableStateOf(ChartMode.VALUES) }

    // Pull down anywhere on the list to refresh (replaces the old top-bar button).
    val ptr = rememberPullToRefreshState()
    if (ptr.isRefreshing) LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(ui.refreshing) { if (!ui.refreshing && ptr.isRefreshing) ptr.endRefresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("AC / Windows", fontWeight = FontWeight.Bold) },
                actions = {
                    DetailModeToggle(mode) { mode = it }
                    SettingsButton()
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().nestedScroll(ptr.nestedScrollConnection)) {
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
                else -> ForecastScreen(s, mode, ui.updatedAtMs, ui.refreshing)
            }
            PullToRefreshContainer(ptr, Modifier.align(Alignment.TopCenter))
        }
    }
}

/** The global Score/Values toggle, sized to sit inside the top app bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailModeToggle(mode: ChartMode, onChange: (ChartMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.padding(end = 4.dp)) {
        SegmentedButton(
            selected = mode == ChartMode.SCORE, onClick = { onChange(ChartMode.SCORE) },
            shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.height(34.dp),
        ) { Text("Score", fontSize = 12.sp) }
        SegmentedButton(
            selected = mode == ChartMode.VALUES, onClick = { onChange(ChartMode.VALUES) },
            shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.height(34.dp),
        ) { Text("Values", fontSize = 12.sp) }
    }
}

/** Tap = app/comfort settings; long-press = widget-only settings. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsButton() {
    val context = LocalContext.current
    Box(
        Modifier.size(48.dp).clip(CircleShape).combinedClickable(
            onClick = { context.startActivity(Intent(context, ConfigActivity::class.java)) },
            onLongClick = { context.startActivity(Intent(context, WidgetSettingsActivity::class.java)) },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings (long-press for widget settings)")
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

/**
 * Placeholder chips shown while the first forecast is still loading. Mirrors the real
 * layout (sources banner + day rows) so data swaps in without a jump, and replaces the
 * brief "No forecast available" flash that showed before the hourly forecast arrived.
 */
@Composable
private fun SkeletonForecast() {
    val chip = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkeletonChip(chip, Modifier.fillMaxWidth().height(40.dp))   // sources banner
        repeat(5) {
            ElevatedCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceElevated),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    SkeletonChip(chip, Modifier.width(64.dp).height(16.dp))   // date
                    Spacer(Modifier.width(12.dp))
                    SkeletonChip(chip, Modifier.weight(1f).height(40.dp))     // sparkline
                    Spacer(Modifier.width(12.dp))
                    SkeletonChip(chip, Modifier.width(72.dp).height(28.dp))   // summary
                }
            }
        }
    }
}

@Composable
private fun SkeletonChip(color: Color, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(color))
}

// ───────────────────────── 14-Day forecast screen ─────────────────────────

private data class DayForecast(val date: String, val points: List<ForecastPoint>)

/** What the expanded detail chart plots: the 0-100 score breakdown, or raw values. */
private enum class ChartMode { SCORE, VALUES }

@Composable
private fun ForecastScreen(state: AppState, mode: ChartMode, updatedAtMs: Long, refreshing: Boolean) {
    val context = LocalContext.current
    val cfg = remember { AppConfig.load(context) }
    val comfort = cfg.comfort
    val threshold = comfort.openScoreMin
    val days = remember(state) {
        state.forecast.points.groupBy { it.time.take(10) }
            .map { (d, pts) -> DayForecast(d, pts) }
            .sortedBy { it.date }
    }
    // Open spans computed across the WHOLE forecast (not per-day), so a window that
    // crosses midnight is one continuous span — its boundary flags land on the real
    // start/end day, never split at midnight.
    val spans = remember(state, threshold) { openSpansTimes(state.forecast.points, threshold) }
    var expanded by rememberSaveable { mutableStateOf(days.firstOrNull()?.date) }
    var showSources by rememberSaveable { mutableStateOf(false) }

    if (days.isEmpty()) {
        // A fetch in flight may have current conditions but no hourly forecast yet — show
        // skeletons rather than flashing "No forecast available" before the data lands.
        if (refreshing) SkeletonForecast() else CenterBox { Text("No forecast available") }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SourceStatusHeader(state, updatedAtMs, cfg.placeName) { showSources = true } }
        items(days, key = { it.date }) { day ->
            DayCard(
                day = day,
                comfort = comfort,
                threshold = threshold,
                mode = mode,
                globalSpans = spans,
                expanded = expanded == day.date,
                onToggle = { expanded = if (expanded == day.date) null else day.date },
            )
        }
    }
    if (showSources) SourceDetailDialog(state.sources, state.consensus) { showSources = false }
}

@Composable
private fun DayCard(
    day: DayForecast,
    comfort: Comfort,
    threshold: Double,
    mode: ChartMode,
    globalSpans: List<Pair<String, String>>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val openHours = day.points.count { it.score >= threshold }
    ElevatedCard(
        Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceElevated),
    ) {
        Column(Modifier.padding(14.dp)) {
            // Selected hour, shared between the collapsed sparkline cursor and the
            // expanded detail charts so a pick in either reads across both.
            var sel by rememberSaveable(day.date) { mutableStateOf<Int?>(null) }
            // Fixed gutters keep every day's bar graph aligned with the others, with a
            // balanced buffer on each side of the bars (left to the date, right to the
            // weather summary).
            val dateW = 84.dp
            val summaryW = 96.dp
            val barPad = 8.dp
            // Open-window time flags ride ABOVE the bars (so they never compress them),
            // in a strip aligned to the sparkline column. Only shown on days a flag lands on.
            val flagTimes = remember(day) { day.points.map { it.time }.toSet() }
            val hasFlags = remember(globalSpans, flagTimes) {
                globalSpans.any { (s, e) -> s in flagTimes || e in flagTimes }
            }
            // Index of the current hour — only matches on the day containing "now" (the
            // date is part of the prefix), so the marker lands on today's graphs only.
            val nowIdx = remember(day) {
                val pre = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH"))
                day.points.indexOfFirst { it.time.startsWith(pre) }
            }
            if (hasFlags) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(dateW))
                    OpenWindowFlags(day.points, globalSpans, Modifier.weight(1f).padding(horizontal = barPad).height(26.dp))
                    Spacer(Modifier.width(summaryW))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayLabel(day.date), Modifier.width(dateW), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                DaySparkline(day.points, threshold, sel, nowIdx, Modifier.weight(1f).padding(horizontal = barPad).height(40.dp))
                DaySummaryView(day.points, Modifier.width(summaryW))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (openHours > 0) "$openHours ${if (openHours == 1) "hour" else "hours"} to open windows"
                    else "No open-window hours",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (openHours > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Spacer(Modifier.height(14.dp))
                when (mode) {
                    ChartMode.SCORE -> FactorChart(day.points, threshold, sel, nowIdx) { sel = it }
                    ChartMode.VALUES -> AbsoluteCharts(day.points, comfort, sel, nowIdx) { sel = it }
                }
            }
        }
    }
}

// ───────────────────────── Collapsed weather summary (right side) ─────────────────────────

@Composable
private fun DaySummaryView(points: List<ForecastPoint>, modifier: Modifier = Modifier) {
    val s = remember(points) { summarize(points) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.width(96.dp), horizontalAlignment = Alignment.End) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(s.morningCode, isDay = true, size = 22.dp)
            WeatherGlyph(s.nightCode, isDay = false, size = 22.dp)
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${s.highF?.toString() ?: "—"}°", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = onSurface)
            Text(" / ${s.lowF?.toString() ?: "—"}°", fontSize = 13.sp, color = onVar)
        }
        rainSummary(s)?.let { Text(it, fontSize = 11.sp, color = onVar, maxLines = 1, softWrap = false) }
    }
}

/** "30% · 0.05″" when there's meaningful rain, else null (omit the line). */
private fun rainSummary(s: DaySummary): String? {
    val prob = s.maxRainProb
    val wet = s.totalPrecipIn >= 0.01
    if ((prob ?: 0) < 5 && !wet) return null
    val parts = mutableListOf<String>()
    if (prob != null && prob >= 5) parts.add("$prob%")
    if (wet) parts.add(fmtInches(s.totalPrecipIn))
    return parts.joinToString(" · ")
}

/** Inches with sensible precision: 0.2″, 0.05″. */
private fun fmtInches(inches: Double): String {
    val s = if (inches >= 0.1) String.format("%.1f", inches) else String.format("%.2f", inches)
    return "$s″"
}

// ───────────────────────── Data-source counter + detail dialog ─────────────────────────

/**
 * Prominent header for the current data view: how many sources were successfully polled
 * (X/N), the consensus confidence colour, and when this data was fetched. Tap to open the
 * per-source breakdown — handy when the reported numbers look off versus a local reading.
 */
@Composable
private fun SourceStatusHeader(state: AppState, updatedAtMs: Long, place: String?, onClick: () -> Unit) {
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
    val placeLabel = place?.trim()?.takeIf { it.isNotEmpty() }
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onClick() },
        color = SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (placeLabel != null) {
                // Condensed single line: "📍 City, ST · ● 6/6 · 3:45 PM  ›". The city name
                // ellipsizes inside the weighted inner row so the count/time never get pushed
                // off; the chevron stays pinned to the right edge.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_location),
                        contentDescription = null,
                        tint = onVar,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        placeLabel,
                        Modifier.weight(1f, fill = false),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(" · ", fontSize = 13.sp, color = onVar)
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text("$ok/$total", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                    updated?.let { Text(" · $it", fontSize = 12.sp, color = onVar, maxLines = 1, softWrap = false) }
                }
                Text(" ›", fontSize = 13.sp, color = primary)
            } else {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text("$ok/$total sources", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                updated?.let { Text(" · updated $it", fontSize = 12.sp, color = onVar) }
                Spacer(Modifier.weight(1f))
                Text("details ›", fontSize = 12.sp, color = primary)
            }
        }
    }
}

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
private fun sourceValues(r: SourceReading): String {
    val parts = mutableListOf<String>()
    r.tempF?.let { parts.add("${it.roundToInt()}°F") }
    r.dewPointF?.let { parts.add("dew ${it.roundToInt()}°") }
    r.humidityPct?.let { parts.add("${it.roundToInt()}% RH") }
    r.windMph?.let { parts.add("${it.roundToInt()} mph") }
    r.aqi?.let { parts.add("AQI $it") }
    return if (parts.isEmpty()) "no values" else parts.joinToString(" · ")
}

/** The merged numbers the app actually reports (the average across the OK sources). */
private fun consensusValues(c: Consensus): String {
    val parts = mutableListOf<String>()
    c.tempF?.let { parts.add("${it.roundToInt()}°F") }
    c.dewPointF?.let { parts.add("dew ${it.roundToInt()}°") }
    c.humidityPct?.let { parts.add("${it.roundToInt()}% RH") }
    c.windMph?.let { parts.add("${it.roundToInt()} mph") }
    c.aqi?.let { parts.add("AQI $it") }
    return if (parts.isEmpty()) "no values" else parts.joinToString(" · ")
}

@Composable
private fun SourceDetailDialog(sources: List<SourceReading>, consensus: Consensus, onDismiss: () -> Unit) {
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
                Text(consensusValues(consensus), fontSize = 12.sp, color = onVar)
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
                        if (r.ok) Text(sourceValues(r), fontSize = 12.sp, color = onVar)
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

// ───────────────────────── Collapsed sparkline + open-window flags ─────────────────────────

/**
 * Score bars for one day. Bars use the full canvas height (the open-window flags now
 * live in their own strip ABOVE — see [OpenWindowFlags] — so they no longer steal bar
 * height). A non-null [sel] mirrors the detail chart's selected hour as a cursor.
 */
@Composable
private fun DaySparkline(
    points: List<ForecastPoint>,
    threshold: Double,
    sel: Int?,
    nowIdx: Int,
    modifier: Modifier,
) {
    val open = BarOpen
    val closed = BarClosed
    val onSurface = MaterialTheme.colorScheme.onSurface
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
                val b = p.breakdown
                val factor = b?.let { FACTORS.minByOrNull { f -> f.multiplierOf(it) }?.color }
                if (factor != null) detractorGrey(factor) else closed
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
                else (FACTORS.minByOrNull { it.multiplierOf(b) } ?: return@forEachIndexed).color
            drawRect(color, topLeft = Offset(i * bw, plotBot - markH), size = Size(bw * 0.7f, markH))
        }

        // Static "now" marker (today only — nowIdx is -1 on other days), centred on the bar.
        if (nowIdx in 0 until n) nowMarker(nowIdx * bw + bw * 0.35f, 0f, plotBot)

        // Selection cursor, mirrored from the expanded detail chart (centred on the bar).
        sel?.let { s ->
            if (s in 0 until n) {
                val cx = s * bw + bw * 0.35f
                drawLine(onSurface.copy(alpha = 0.55f), Offset(cx, 0f), Offset(cx, plotBot), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

/**
 * The open-window time pills, drawn in a strip ABOVE the day's bars and aligned to the
 * sparkline column (so a pill never overlaps a bar). Flags come from the GLOBAL spans: a
 * span's start flag shows on the day it begins, its end flag on the day it ends — so a
 * window across midnight is one continuous span with no boundary flag. The stagger is
 * bottom-anchored so the pills sit just over the bars.
 */
@Composable
private fun OpenWindowFlags(
    points: List<ForecastPoint>,
    globalSpans: List<Pair<String, String>>,
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
            if (si >= 0) { centers.add(si * bw); texts.add(hourLabelOf(ForecastBuilder.hour(startIso))) }
            val ei = timeOf.indexOf(endIso)
            if (ei >= 0) { centers.add((ei + 1) * bw); texts.add(hourLabelOf(ForecastBuilder.hour(endIso) + 1)) }
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

// ───────────────────────── Expanded factor chart (lines + scrub tooltip) ─────────────────────────

@Composable
private fun FactorChart(points: List<ForecastPoint>, threshold: Double, sel: Int?, nowIdx: Int, onSel: (Int?) -> Unit) {
    if (points.isEmpty()) return
    val n = points.size
    val tm = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant

    Column {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxWidth().height(190.dp)
                    .pointerInput(n) { detectTapGestures { o -> onSel(idxAt(o.x, size.width, n)) } }
                    .pointerInput(n) {
                        detectHorizontalDragGestures { change, _ ->
                            onSel(idxAt(change.position.x, size.width, n)); change.consume()
                        }
                    },
            ) {
                val topPad = 8.dp.toPx()
                val botPad = 16.dp.toPx()
                val plotBot = size.height - botPad
                val plotH = plotBot - topPad
                fun xAt(i: Int) = if (n == 1) size.width / 2 else i.toFloat() / (n - 1) * size.width
                fun yAt(v: Double) = plotBot - (v / 100.0).toFloat().coerceIn(0f, 1f) * plotH

                // Day/night shading behind everything (sun-up lighter, overnight darker).
                dayNightBands(points, xAt(0), xAt(n - 1), topPad, plotBot)

                // Faint score area + line (the product the factors explain).
                val area = Path().apply {
                    moveTo(xAt(0), plotBot)
                    points.forEachIndexed { i, p -> lineTo(xAt(i), yAt(p.score)) }
                    lineTo(xAt(n - 1), plotBot); close()
                }
                drawPath(area, ScoreInk.copy(alpha = 0.12f))

                // Open threshold (dashed).
                val thrY = yAt(threshold)
                drawLine(
                    onVar.copy(alpha = 0.5f), Offset(0f, thrY), Offset(size.width, thrY),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 9f)),
                )

                // One coloured line per factor.
                FACTORS.forEach { f ->
                    val path = Path()
                    points.forEachIndexed { i, p ->
                        val m = p.breakdown?.let(f.multiplierOf) ?: 1.0
                        val x = xAt(i)
                        val y = yAt(m * 100)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, f.color, style = Stroke(width = 2.dp.toPx()))
                }
                // Score line on top (thin, neutral).
                val sPath = Path()
                points.forEachIndexed { i, p ->
                    val x = xAt(i); val y = yAt(p.score)
                    if (i == 0) sPath.moveTo(x, y) else sPath.lineTo(x, y)
                }
                drawPath(sPath, ScoreInk.copy(alpha = 0.6f), style = Stroke(width = 1.5f.dp.toPx()))

                // Static "now" marker (today only — nowIdx is -1 on other days).
                if (nowIdx in 0 until n) nowMarker(xAt(nowIdx), topPad, plotBot, tm)

                // Selection cursor (only when an hour is tapped).
                sel?.let { s ->
                    val cx = xAt(s)
                    drawLine(onSurface.copy(alpha = 0.6f), Offset(cx, topPad), Offset(cx, plotBot), strokeWidth = 1.dp.toPx())
                }

                // Sparse hour labels.
                val step = (n / 4).coerceAtLeast(1)
                var i = 0
                while (i < n) {
                    val layout = tm.measure(shortHour(points[i].time), TextStyle(color = onVar, fontSize = 9.sp))
                    drawText(
                        layout,
                        topLeft = Offset(
                            (xAt(i) - layout.size.width / 2).coerceIn(0f, size.width - layout.size.width),
                            plotBot + 3.dp.toPx(),
                        ),
                    )
                    i += step
                }
            }

            // Tooltip — hidden until you tap a point; offset to whichever side has room
            // so it never covers the selected hour. Tap it to dismiss.
            sel?.let { s ->
                val p = points[s]
                val frac = if (n == 1) 0.5f else s.toFloat() / (n - 1)
                val ttW = 188.dp
                val gap = 8.dp
                val cursorX = maxWidth * frac
                val left = when {
                    maxWidth - cursorX >= ttW + gap -> cursorX + gap
                    cursorX >= ttW + gap -> cursorX - gap - ttW
                    else -> (maxWidth - ttW) / 2
                }.coerceIn(0.dp, (maxWidth - ttW).coerceAtLeast(0.dp))
                Tooltip(p, Modifier.width(ttW).offset(x = left, y = 6.dp).clickable { onSel(null) })
            }
        }
        Spacer(Modifier.height(10.dp))
        Legend()
    }
}

@Composable
private fun Tooltip(p: ForecastPoint, modifier: Modifier) {
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("${fmt(p.time)}  ·  score ${p.score.roundToInt()}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            FACTORS.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(f.color))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        f.label, fontSize = 11.sp, maxLines = 1, softWrap = false,
                        modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(f.valueOf(p), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FACTORS.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(f.color))
                Spacer(Modifier.width(5.dp))
                Text(f.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ───────────────────────── Absolute-value charts (stacked, shared scrub) ─────────────────────────

/**
 * The "Values" detail view: the raw weather behind the score, as small multiples
 * sharing one hour axis. Both charts use identical left/right gutters so their plot
 * areas line up — the scrub cursor hits the same hour in both. Chart 1: temp + dew on
 * a °F axis (temp turns red ≥90°F). Chart 2: AQI and rain-chance share the 0-100 left
 * axis, precipitation amount rides a right ″ axis as bars. Tapping scrubs both and
 * fills the readout row.
 */
@Composable
private fun AbsoluteCharts(points: List<ForecastPoint>, comfort: Comfort, sel: Int?, nowIdx: Int, onSel: (Int?) -> Unit) {
    if (points.isEmpty()) return
    Column {
        AbsReadout(points, sel)
        Spacer(Modifier.height(10.dp))
        TempChart(points, comfort, sel, nowIdx, onSel)
        Spacer(Modifier.height(12.dp))
        CombinedChart(points, sel, nowIdx, onSel)
    }
}

/** Geometry for one stacked mini-chart's plot area. */
private class ChartGeom(
    val plotLeft: Float, val plotTop: Float, val plotRight: Float, val plotBot: Float, val n: Int,
) {
    val plotH get() = plotBot - plotTop
    val plotW get() = plotRight - plotLeft
    fun xAt(i: Int) = if (n <= 1) (plotLeft + plotRight) / 2 else plotLeft + i.toFloat() / (n - 1) * plotW
    fun yAt(v: Double, lo: Double, hi: Double): Float {
        val f = if (hi <= lo) 0.0 else (v - lo) / (hi - lo)
        return plotBot - f.toFloat().coerceIn(0f, 1f) * plotH
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AbsReadout(points: List<ForecastPoint>, sel: Int?) {
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    if (sel == null) {
        Text("Tap a chart for the hour-by-hour readings", fontSize = 11.sp, color = onVar)
        return
    }
    val p = points[sel]
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Chip(fmt(p.time), FontWeight.Bold, MaterialTheme.colorScheme.onSurface)
        Chip("score ${p.score.roundToInt()}", FontWeight.Bold, ScoreInk)
        p.tempF?.let { Chip("${it.roundToInt()}°F", color = FactorTemp) }
        p.dewPointF?.let { Chip("dew ${it.roundToInt()}°", color = FactorHumid) }
        p.aqi?.let { Chip("AQI $it", color = FactorAqi) }
        p.precipProb?.let { Chip("${it.roundToInt()}% rain", color = AcBlue) }
        p.precipIn?.takeIf { it > 0 }?.let { Chip(fmtInches(it), color = FactorRain) }
    }
}

@Composable
private fun Chip(text: String, weight: FontWeight = FontWeight.Medium, color: Color) {
    Text(text, fontSize = 11.sp, fontWeight = weight, color = color, maxLines = 1, softWrap = false)
}

@Composable
private fun TempChart(points: List<ForecastPoint>, comfort: Comfort, sel: Int?, nowIdx: Int, onSel: (Int?) -> Unit) {
    val temps = remember(points) { points.mapNotNull { it.tempF } }
    if (temps.isEmpty()) { NoDataChart("Temp · Dew point (°F)", "No temperature data"); return }
    val dews = points.mapNotNull { it.dewPointF }
    val (lo, hi) = niceBounds(
        minOf(temps.min(), dews.minOrNull() ?: temps.min()),
        maxOf(temps.max(), dews.maxOrNull() ?: temps.max(), comfort.maxTempF),
    )
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    ChartFrame("Temp · Dew point (°F)", 124.dp, points, sel, showHourAxis = false, onSel = onSel) { g, tm ->
        axisLabel(tm, "${hi.roundToInt()}°", onVar, 2f.dp.toPx(), g.plotTop)
        axisLabel(tm, "${lo.roundToInt()}°", onVar, 2f.dp.toPx(), g.plotBot - 10.dp.toPx())
        dashed(g.plotLeft, g.plotRight, g.yAt(comfort.maxTempF, lo, hi), onVar.copy(alpha = 0.4f))
        polyline(points.mapIndexedNotNull { i, p -> p.dewPointF?.let { g.xAt(i) to g.yAt(it, lo, hi) } }, FactorHumid, 2.dp.toPx())
        // Temp drawn segment-by-segment so any stretch touching ≥90°F renders red.
        val w = 2.dp.toPx()
        for (k in 0 until points.size - 1) {
            val a = points[k].tempF ?: continue
            val b = points[k + 1].tempF ?: continue
            val color = if (a >= HOT_F || b >= HOT_F) HotRed else FactorTemp
            drawLine(color, Offset(g.xAt(k), g.yAt(a, lo, hi)), Offset(g.xAt(k + 1), g.yAt(b, lo, hi)), strokeWidth = w)
        }
        // Static "now" marker (today only).
        if (nowIdx in points.indices) nowMarker(g.xAt(nowIdx), g.plotTop, g.plotBot, tm)
        // At the selected hour, label each series' value right where the cursor meets its line.
        sel?.let { s ->
            points[s].dewPointF?.let { d ->
                valueAtLine(tm, "${d.roundToInt()}°", FactorHumid, g.xAt(s), g.yAt(d, lo, hi), g.plotLeft, g.plotRight)
            }
            points[s].tempF?.let { t ->
                valueAtLine(tm, "${t.roundToInt()}°", if (t >= HOT_F) HotRed else FactorTemp, g.xAt(s), g.yAt(t, lo, hi), g.plotLeft, g.plotRight)
            }
        }
    }
}

@Composable
private fun CombinedChart(points: List<ForecastPoint>, sel: Int?, nowIdx: Int, onSel: (Int?) -> Unit) {
    val maxIn = remember(points) { points.mapNotNull { it.precipIn }.maxOrNull() ?: 0.0 }
    val inHi = niceCeilIn(maxIn)
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    ChartFrame(
        "US AQI · Rain Chance (%) · Rain amount (in)", 124.dp, points, sel, showHourAxis = true, onSel = onSel,
    ) { g, tm ->
        // Precip amount as bars on the right (″) scale, behind the lines.
        val bw = (g.plotW / g.n * 0.55f).coerceAtLeast(1.5f.dp.toPx())
        points.forEachIndexed { i, p ->
            val v = p.precipIn ?: 0.0
            if (v <= 0) return@forEachIndexed
            val top = g.yAt(v, 0.0, inHi)
            drawRect(FactorRain.copy(alpha = 0.4f), topLeft = Offset(g.xAt(i) - bw / 2, top), size = Size(bw, g.plotBot - top))
        }
        // AQI + rain chance share the 0-100 left axis (AQI clipped at 100; exact value in the readout).
        polyline(points.mapIndexedNotNull { i, p -> p.aqi?.let { g.xAt(i) to g.yAt(it.toDouble().coerceAtMost(100.0), 0.0, 100.0) } }, FactorAqi, 2.dp.toPx())
        polyline(points.mapIndexedNotNull { i, p -> p.precipProb?.let { g.xAt(i) to g.yAt(it, 0.0, 100.0) } }, AcBlue, 2.dp.toPx())
        axisLabel(tm, "100", onVar, 2f.dp.toPx(), g.plotTop)
        axisLabel(tm, "0", onVar, 2f.dp.toPx(), g.plotBot - 10.dp.toPx())
        axisLabel(tm, fmtInches(inHi), FactorRain, g.plotRight + 2.dp.toPx(), g.plotTop)
        axisLabel(tm, "0″", FactorRain, g.plotRight + 2.dp.toPx(), g.plotBot - 10.dp.toPx())
        // Static "now" marker (today only).
        if (nowIdx in points.indices) nowMarker(g.xAt(nowIdx), g.plotTop, g.plotBot, tm)
        // At the selected hour, label each series' value where the cursor meets it: AQI is
        // unitless, rain chance is %, rain amount uses ′ (inches), each in its line's colour.
        sel?.let { s ->
            val p = points[s]
            p.aqi?.let {
                valueAtLine(tm, "$it AQI", FactorAqi, g.xAt(s), g.yAt(it.toDouble().coerceAtMost(100.0), 0.0, 100.0), g.plotLeft, g.plotRight)
            }
            p.precipProb?.let {
                valueAtLine(tm, "${it.roundToInt()}%", AcBlue, g.xAt(s), g.yAt(it, 0.0, 100.0), g.plotLeft, g.plotRight)
            }
            p.precipIn?.takeIf { it > 0 }?.let { v ->
                val txt = (if (v >= 0.1) String.format("%.1f", v) else String.format("%.2f", v)) + "′"
                valueAtLine(tm, txt, FactorRain, g.xAt(s), g.yAt(v, 0.0, inHi), g.plotLeft, g.plotRight)
            }
        }
    }
}

/** Shared mini-chart scaffold: panel background, 1/3-2/3 gridlines, title, scrub
 *  gesture, selection cursor, optional hour axis. All charts use the same gutters so
 *  their plot areas — and therefore the scrub cursor — line up. */
@Composable
private fun ChartFrame(
    title: String,
    height: Dp,
    points: List<ForecastPoint>,
    sel: Int?,
    showHourAxis: Boolean,
    leftGutter: Dp = 30.dp,
    rightGutter: Dp = 36.dp,
    onSel: (Int?) -> Unit,
    draw: DrawScope.(ChartGeom, TextMeasurer) -> Unit,
) {
    val n = points.size
    val tm = rememberTextMeasurer()
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(height).scrub(n, onSel)) {
        val titleLayout = tm.measure(title, TextStyle(color = onVar, fontSize = 10.sp, fontWeight = FontWeight.Medium))
        drawText(titleLayout, topLeft = Offset(leftGutter.toPx(), 0f))
        val topPad = titleLayout.size.height + 6.dp.toPx()
        val botPad = if (showHourAxis) 16.dp.toPx() else 4.dp.toPx()
        val geom = ChartGeom(leftGutter.toPx(), topPad, size.width - rightGutter.toPx(), size.height - botPad, n)
        // Panel background + reference gridlines at 1/3 and 2/3 height (glance aid).
        drawRoundRect(
            ChartPanel, topLeft = Offset(0f, geom.plotTop),
            size = Size(size.width, geom.plotH), cornerRadius = CornerRadius(6.dp.toPx()),
        )
        // Day/night shading behind the gridlines + data (sun-up lighter, overnight darker).
        dayNightBands(points, geom.plotLeft, geom.plotRight, geom.plotTop, geom.plotBot)
        for (frac in listOf(1f / 3f, 2f / 3f)) {
            val gy = geom.plotTop + geom.plotH * frac
            drawLine(onVar.copy(alpha = 0.16f), Offset(geom.plotLeft, gy), Offset(geom.plotRight, gy), strokeWidth = 1f)
        }
        draw(geom, tm)
        sel?.let { s ->
            val cx = geom.xAt(s)
            drawLine(onSurface.copy(alpha = 0.55f), Offset(cx, geom.plotTop), Offset(cx, geom.plotBot), strokeWidth = 1.dp.toPx())
        }
        if (showHourAxis) {
            val step = (n / 4).coerceAtLeast(1)
            var i = 0
            while (i < n) {
                val l = tm.measure(shortHour(points[i].time), TextStyle(color = onVar, fontSize = 9.sp))
                drawText(l, topLeft = Offset(
                    (geom.xAt(i) - l.size.width / 2).coerceIn(0f, size.width - l.size.width),
                    geom.plotBot + 3.dp.toPx(),
                ))
                i += step
            }
        }
    }
}

@Composable
private fun NoDataChart(title: String, msg: String) {
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = onVar)
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
            Text(msg, fontSize = 11.sp, color = onVar)
        }
    }
}

private fun Modifier.scrub(n: Int, onSel: (Int?) -> Unit): Modifier =
    this.pointerInput(n) { detectTapGestures { o -> onSel(idxAt(o.x, size.width, n)) } }
        .pointerInput(n) {
            detectHorizontalDragGestures { change, _ -> onSel(idxAt(change.position.x, size.width, n)); change.consume() }
        }

/**
 * Shades the plot background by time of day: a light wash for sun-up hours
 * (sunrise→sunset), a darker one overnight — mirroring the widget's graph so the
 * time-of-day behind each point reads at a glance. Each hour owns a cell whose
 * boundaries sit halfway to its neighbours; runs of the same state merge into one
 * rect so there are no hairline seams. [left]/[right] are the x of the first/last
 * point (point i sits at left + (right-left)·i/(n-1)).
 */
private fun DrawScope.dayNightBands(
    points: List<ForecastPoint>, left: Float, right: Float, top: Float, bottom: Float,
) {
    val n = points.size
    if (n == 0 || right <= left || bottom <= top) return
    val span = (n - 1).coerceAtLeast(1)
    fun edge(i: Int) = (left + (right - left) * ((i - 0.5f) / span)).coerceIn(left, right)
    val dayColor = Color.White.copy(alpha = 0.05f)
    val nightColor = Color.Black.copy(alpha = 0.16f)
    var i = 0
    while (i < n) {
        val up = points[i].isDay ?: true
        var j = i + 1
        while (j < n && (points[j].isDay ?: true) == up) j++
        val x0 = edge(i)
        val x1 = edge(j)
        drawRect(if (up) dayColor else nightColor, topLeft = Offset(x0, top), size = Size(x1 - x0, bottom - top))
        i = j
    }
}

private fun DrawScope.polyline(pts: List<Pair<Float, Float>>, color: Color, width: Float) {
    if (pts.isEmpty()) return
    val path = Path()
    pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
    drawPath(path, color, style = Stroke(width = width))
}

private fun DrawScope.dashed(x0: Float, x1: Float, y: Float, color: Color) =
    drawLine(color, Offset(x0, y), Offset(x1, y), strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))

private fun DrawScope.axisLabel(tm: TextMeasurer, text: String, color: Color, x: Float, y: Float) =
    drawText(tm.measure(text, TextStyle(color = color, fontSize = 9.sp)), topLeft = Offset(x, y))

/** Draws a bold value label at the height [y] where the scrub cursor (at [cx]) meets a
 *  line, offset to the side so it clears the cursor and stays within the plot. */
private fun DrawScope.valueAtLine(
    tm: TextMeasurer, text: String, color: Color, cx: Float, y: Float, plotLeft: Float, plotRight: Float,
) {
    val layout = tm.measure(text, TextStyle(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold))
    val gap = 4.dp.toPx()
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    // Prefer the right of the cursor; flip left when it would overflow the plot.
    var x = cx + gap
    if (x + w > plotRight) x = cx - gap - w
    x = x.coerceIn(plotLeft, (plotRight - w).coerceAtLeast(plotLeft))
    val top = (y - h / 2f).coerceIn(0f, (size.height - h).coerceAtLeast(0f))
    drawText(layout, topLeft = Offset(x, top))
}

/** Static dashed amber "now" marker at the current hour (today's graphs only). When [tm]
 *  is given, also draws a small "now" label clamped on-screen. */
private fun DrawScope.nowMarker(cx: Float, top: Float, bottom: Float, tm: TextMeasurer? = null) {
    drawLine(
        NowInk.copy(alpha = 0.85f), Offset(cx, top), Offset(cx, bottom),
        strokeWidth = 1.5f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
    )
    tm?.let {
        val l = it.measure("now", TextStyle(color = NowInk, fontSize = 9.sp, fontWeight = FontWeight.Bold))
        val x = (cx + 2.dp.toPx()).coerceAtMost(size.width - l.size.width)
        drawText(l, topLeft = Offset(x.coerceAtLeast(0f), top))
    }
}

/** Pad a value range out to tidy 5° gridlines for the temp axis. */
private fun niceBounds(lo: Double, hi: Double): Pair<Double, Double> {
    if (hi <= lo) return lo - 2 to hi + 2
    val pad = (hi - lo) * 0.12
    return floor((lo - pad) / 5) * 5 to ceil((hi + pad) / 5) * 5
}

private fun niceCeilIn(v: Double): Double = when {
    v <= 0.1 -> 0.1
    v <= 0.25 -> 0.25
    v <= 0.5 -> 0.5
    v <= 1.0 -> 1.0
    else -> ceil(v * 2) / 2
}

// ───────────────────────── helpers ─────────────────────────

private fun idxAt(x: Float, w: Int, n: Int): Int =
    ((x / w.coerceAtLeast(1).toFloat()) * n).toInt().coerceIn(0, n - 1)

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

/** Compact axis hour: "12a", "6a", "12p", "6p". */
private fun shortHour(iso: String): String {
    val h = ForecastBuilder.hour(iso)
    if (h < 0) return ""
    val h12 = (h % 12).let { if (it == 0) 12 else it }
    return "$h12${if (h < 12) "a" else "p"}"
}

private fun dayLabel(date: String): String = try {
    val d = LocalDate.parse(date)
    val today = LocalDate.now()
    when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("EEE M/d"))
    }
} catch (e: Exception) {
    date
}
