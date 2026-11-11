package com.acwindows

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Placeholder chips shown while the first forecast is still loading, sized to land on
 * the real layout rather than near it: same 84dp/96dp gutters, same one-row 17dp open-window
 * flag strip, same 40dp sparkline, same stacked summary, and text rows that reserve the
 * real line box via [SkeletonTextChip]. A loaded collapsed [DayCard] measures 114dp
 * (10 + 17 + 53 + 2 + 22 + 10) and the header 48dp (10 + 28 + 10).
 *
 * The FIRST card is drawn EXPANDED, because today's card opens by default — so the
 * placeholder has to reserve the open card's height or the list jumps the moment data
 * lands. Its extra chrome is the Values-mode layout appended below the collapsed body,
 * built from the same dp constants [DayCard]/[AbsoluteCharts] use: Spacer(14) + readout
 * line (a ~28dp themed line box) + Spacer(10) + 124dp temp chart + Spacer(12) + a 140dp
 * combined chart (the 124dp plot plus the 16dp [ICON_BAND] reserved below it for
 * [CombinedChart]'s precip-type icons) = 328dp. That puts the expanded skeleton at
 * 114 + 328 = 442dp (not reverified against a live device: no screenshots without being
 * asked).
 *
 * Two deliberate assumptions for both the collapsed body and that 442dp target, both
 * the common case: the day has an open-window flag (one row, 17dp) and no rain line in its summary
 * (a rain line would add another 28dp); the expanded readout also assumes one line.
 *
 * None of this dp scaffold grows with the app text-size setting (see [AcWindowsTheme]) —
 * that setting only scales `sp`, not `dp`. At 200% the skeleton's placeholder heights will
 * drift a bit from the real, now-taller cards; not worth chasing for a loading placeholder.
 */
@Composable
internal fun SkeletonForecast() {
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
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // The open-window flag strip. Most days carry a span boundary, so
                    // reserving its 17dp one-row height is what keeps the card at a real card's height.
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.width(dateW))
                        Box(Modifier.weight(1f).padding(horizontal = barPad).height(17.dp)) {
                            SkeletonChip(chip, Modifier.align(Alignment.TopStart).width(58.dp).height(15.dp))
                            SkeletonChip(chip, Modifier.align(Alignment.TopEnd).width(58.dp).height(15.dp))
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
                    Spacer(Modifier.height(2.dp))
                    // The notes row: the Today chip's 22dp is the tallest thing in it.
                    Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterEnd) {
                        SkeletonChip(chip, Modifier.width(150.dp).height(14.dp))
                    }

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
internal data class DayForecast(val date: String, val points: List<ForecastPoint>, val stepMinutes: Int = 60)

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
internal fun ForecastScreen(
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
