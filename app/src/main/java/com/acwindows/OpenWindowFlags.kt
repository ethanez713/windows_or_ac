package com.acwindows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
internal fun OpenWindowFlags(
    points: List<ForecastPoint>,
    globalSpans: List<Pair<String, String>>,
    stepMinutes: Int,
    modifier: Modifier,
) {
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    val pillBg = Color(0xE6121821)
    val pillFg = Color(0xFFE6EAF0)
    BoxWithConstraints(modifier) {
        val widthPx = with(density) { maxWidth.toPx() }
        val flags = remember(points, globalSpans, stepMinutes, widthPx, density) {
            with(density) { layoutFlags(points, globalSpans, stepMinutes, widthPx, tm, pillFg) }
        }
        Canvas(Modifier.fillMaxWidth().height(flags.height)) {
            val padX = FLAG_PAD_X.toPx()
            val padY = FLAG_PAD_Y.toPx()
            flags.layouts.forEachIndexed { i, l ->
                val top = flags.rows[i] * FLAG_ROW_H.toPx()
                drawRoundRect(
                    pillBg, topLeft = Offset(flags.lefts[i], top),
                    size = Size(l.size.width + 2 * padX, l.size.height + 2 * padY),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                drawText(l, topLeft = Offset(flags.lefts[i] + padX, top + padY))
            }
        }
    }
}

private val FLAG_PAD_X = 4.dp
private val FLAG_PAD_Y = 1.5f.dp
private val FLAG_ROW_H = 12.dp

private class FlagLayout(
    val layouts: List<TextLayoutResult>, val lefts: FloatArray, val rows: IntArray, val height: Dp,
)

internal data class FlagSlot(val index: Int, val left: Float, val row: Int)

/**
 * Each pill, left to right, takes the first of up to [maxRows] rows whose last pill
 * ends at least [gapPx] before it. A pill that fits none is dropped. [left] is clamped
 * so the pill stays inside the strip when it fits, and pins to 0 when it is wider.
 */
internal fun assignFlagRows(
    centers: List<Float>,
    widths: List<Float>,
    widthPx: Float,
    gapPx: Float,
    maxRows: Int = 3,
): List<FlagSlot> {
    val rowEnd = FloatArray(maxRows) { Float.NEGATIVE_INFINITY }
    val placed = mutableListOf<FlagSlot>()
    for (idx in centers.indices.sortedBy { centers[it] }) {
        val width = widths[idx]
        val left = (centers[idx] - width / 2).coerceIn(0f, (widthPx - width).coerceAtLeast(0f))
        val row = (0 until maxRows).firstOrNull { left >= rowEnd[it] + gapPx } ?: continue
        rowEnd[row] = left + width
        placed.add(FlagSlot(idx, left, row))
    }
    return placed
}

/**
 * Where each time pill sits, and the strip height they need. [assignFlagRows] uses one
 * row when nothing collides and only adds a row (up to three) when a pill would overlap,
 * so a single span doesn't reserve an empty band.
 */
private fun Density.layoutFlags(
    points: List<ForecastPoint>,
    globalSpans: List<Pair<String, String>>,
    stepMinutes: Int,
    widthPx: Float,
    tm: TextMeasurer,
    ink: Color,
): FlagLayout {
    val n = points.size.coerceAtLeast(1)
    val bw = widthPx / n
    val padX = FLAG_PAD_X.toPx()
    val padY = FLAG_PAD_Y.toPx()
    val rowH = FLAG_ROW_H.toPx()
    val pillStyle = TextStyle(color = ink, fontSize = 9.sp, fontWeight = FontWeight.Bold)

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
    if (centers.isEmpty()) return FlagLayout(emptyList(), FloatArray(0), IntArray(0), 0.dp)
    val layouts = texts.map { tm.measure(it, pillStyle) }
    val widths = layouts.map { it.size.width + 2 * padX }
    val placed = assignFlagRows(centers, widths, widthPx, 2.dp.toPx())
    if (placed.isEmpty()) return FlagLayout(emptyList(), FloatArray(0), IntArray(0), 0.dp)
    val kept = placed.map { layouts[it.index] }
    val lefts = FloatArray(placed.size) { placed[it].left }
    val rows = IntArray(placed.size) { placed[it].row }
    val maxRow = placed.maxOf { it.row }
    val pillH = kept.maxOf { it.size.height } + 2 * padY
    return FlagLayout(kept, lefts, rows, (maxRow * rowH + pillH).toDp() + FLAG_GAP)
}

private val FLAG_GAP = 2.dp

/** Hour-of-day (wraps; e.g. 24 → 0) to a label like "4 AM" / "12 PM". */
private fun hourLabelOf(hour: Int): String {
    val h = ((hour % 24) + 24) % 24
    val ampm = if (h < 12) "AM" else "PM"
    val h12 = (h % 12).let { if (it == 0) 12 else it }
    return "$h12 $ampm"
}

/** "2026-06-16T21:00" -> "9 PM". */
private fun fmt(iso: String): String = ForecastBuilder.fmtHour(iso)
