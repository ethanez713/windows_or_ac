package com.acwindows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun DayCard(
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
    ElevatedCard(
        Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.elevatedCardColors(containerColor = Skin.chrome.card),
    ) {
        // Horizontal 14dp, not the design's stated 12: that 12 is paired with a 1dp border on
        // both the frame and the card, and these cards are borderless, so 14 keeps the same
        // content box. Vertical is tighter so the list shows more days per screen.
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
                    OpenWindowFlags(day.points, globalSpans, day.stepMinutes, Modifier.weight(1f))
                    Spacer(Modifier.width(colGap + trailingW))
                }
            } else {
                Spacer(Modifier.height(4.dp))
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
                DaySparkline(day.points, threshold, comfort, nowIdx, sel, Modifier.weight(1f).height(34.dp))
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
            val bad = remember(day.points, comfort, threshold) { badConditions(comfort, day.points, threshold) }
            if (isToday || bad.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isToday) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                // An inner clickable consumes the tap before it reaches the
                                // card's own clickable (the expand/collapse toggle).
                                .clickable { onToggleView() }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                if (viewMode == DayViewMode.NOW) "Now ›" else "Today ›",
                                fontFamily = Mono,
                                fontSize = 11.sp,
                                lineHeight = NOTE_LINE_HEIGHT,
                                fontWeight = FontWeight.SemiBold,
                                color = Skin.data.score,
                            )
                        }
                    }
                    ConditionNotes(bad, day.points, unit, Modifier.weight(1f))
                }
            } else {
                Spacer(Modifier.height(10.dp))
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Skin.chrome.hairline)
                Spacer(Modifier.height(10.dp))
                DetailDrawer(
                    day.points, unit,
                    palette = cfg.aqiPalette,
                    thunderAlertPct = cfg.thunderAlertPct,
                    comfort = comfort,
                    isToday = isToday,
                    sel = sel,
                    onSel = { sel = it },
                )
            }
        }
    }
}

/** The theme's 24sp line height would make the 11sp notes row 28dp tall. */
private val NOTE_LINE_HEIGHT = 14.sp

/**
 * The conditions that keep a day's under-threshold hours closed, each at its worst hour and in
 * its own hue. Always composes a line, even an empty one, so every card keeps the same height.
 */
@Composable
private fun ConditionNotes(bad: List<Limiting>, points: List<ForecastPoint>, unit: TempUnit, modifier: Modifier) {
    val sep = Skin.chrome.faint
    val rainIn = points.sumOf { it.precipIn ?: 0.0 }
    val text = buildAnnotatedString {
        bad.forEachIndexed { i, lim ->
            val p = lim.point
            val reading = when (lim.factor) {
                Factor.TEMP -> p.tempF?.let { unit.deg(it) }
                Factor.DEW -> p.dewPointF?.let { unit.deg(it) }
                Factor.AQI -> p.aqi?.toString()
                Factor.RAIN -> p.precipProb?.let {
                    "${it.roundToInt()}%" + if (rainIn > 0) " ${fmtIn(rainIn)}" else ""
                }
            } ?: return@forEachIndexed
            if (length > 0) withStyle(SpanStyle(color = sep)) { append(" · ") }
            withStyle(SpanStyle(color = Color(Tokens.factorHue(Skin.data.raw, lim.factor)))) {
                append("${lim.factor.label()} $reading")
            }
        }
    }
    Text(text, modifier, fontFamily = Mono, fontSize = 11.sp, lineHeight = NOTE_LINE_HEIGHT, textAlign = TextAlign.End)
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

// Weekday plus the day of month ("Thu 23"). A variable-width name overruns the
// column, and "EEE M/d" does too once the month and the day are both two digits ("Thu 10…").
private fun dayLabel(date: String): String = try {
    val d = LocalDate.parse(date)
    if (d == LocalDate.now()) "Today" else d.format(DateTimeFormatter.ofPattern("EEE d"))
} catch (e: Exception) {
    date
}
