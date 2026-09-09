package com.acwindows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The expanded day's detail drawer (docs/ux-redesign.md §A.4): four blocks stacked on one
 * shared time axis, in place of the single chart that used to carry AQI, rain chance and
 * rain amount together on a left 0–100 axis and a right inches axis, identified by legend.
 *
 * Each block gets the encoding its own quantity deserves — temperature and dew point share
 * a chart because they share a unit, air quality is a gradient strip because a single
 * running value has no shape worth plotting, and rain is columns because chance and
 * intensity are two numbers per hour, not one.
 */

/** Every chart in the drawer is authored to this width and shares this gutter. */
private val PLOT_LEFT = 30.dp

/**
 * The right inset exists so a stroked mark at the last hour clears the card border; the
 * drawer's TEXT rows deliberately span the full content box instead.
 */
private val PLOT_RIGHT_INSET = 4.dp

private val AXIS_TEXT = 11.sp
private val ANNOTATION_TEXT = 9.sp

@Composable
fun DetailDrawer(
    points: List<ForecastPoint>,
    comfort: Comfort,
    unit: TempUnit,
    palette: AqiPalette,
    rainScaleInPerHr: Double,
    thunderAlertPct: Double,
    isToday: Boolean,
) {
    if (points.isEmpty()) return
    val tm = rememberTextMeasurer()
    val n = points.size
    val nowFrac = remember(points, isToday) { if (isToday) nowFractionOf(points) else null }
    val nowIdx = remember(points, isToday) { if (isToday) nowIndexOf(points) else -1 }

    // Drag or tap across the chart to read a specific hour; null follows the present, so an
    // untouched today card still opens on "now". Reset whenever the day's rows change.
    var sel by remember(points) { mutableStateOf<Int?>(null) }
    val activeIdx = sel ?: nowIdx
    val markerFrac = sel?.let { if (n <= 1) 0.5f else it.toFloat() / (n - 1) } ?: nowFrac

    Column(Modifier.fillMaxWidth()) {
        ReadingsLine(points, unit, activeIdx)
        Spacer(Modifier.height(8.dp))
        Box {
            Column {
                TempDewBlock(points, comfort, unit, tm, markerFrac)
                Spacer(Modifier.height(4.dp))
                AqiBlock(points, palette, tm)
                Spacer(Modifier.height(14.dp))
                RainBlock(points, comfort, rainScaleInPerHr, thunderAlertPct, tm, markerFrac)
                TimeAxis(points, tm, nowFrac)
            }
            // One overlay across the whole chart stack drives the selection; a horizontal
            // drag is consumed here so it scrubs instead of collapsing the card or scrolling
            // the list (a vertical drag falls through to the list).
            Spacer(Modifier.matchParentSize().scrub(n) { sel = it })
        }
    }
}

// ---------------------------------------------------------------- 1. readings line

/**
 * One mono line of the hour's actual numbers, each in its own series colour.
 *
 * The rain item is dropped at zero chance: six items measure past the content box and wrap,
 * orphaning the last one behind a dangling separator, and `rain 0%` is the one reading that
 * says nothing the rain chart below doesn't already say more plainly. A non-zero chance
 * always shows.
 */
@Composable
private fun ReadingsLine(points: List<ForecastPoint>, unit: TempUnit, idx: Int) {
    val d = Skin.data
    val chrome = Skin.chrome
    val parts = mutableListOf<Pair<String, Color>>()

    if (idx in points.indices) {
        val p = points[idx]
        parts += ForecastBuilder.fmtHour(p.time) to chrome.heading
        parts += "score ${p.score.roundToInt()}" to d.score
        p.tempF?.let { parts += unit.degUnit(it) to d.temp }
        p.dewPointF?.let { parts += "dew ${unit.deg(it)}" to d.dew }
        p.aqi?.let { parts += "AQI $it" to d.aqi }
        p.precipProb?.takeIf { it > 0 }?.let { parts += "rain ${it.roundToInt()}%" to d.rain }
    } else {
        // A future day has no "now" to read, so it reports its own extremes instead.
        val temps = points.mapNotNull { it.tempF }
        val dews = points.mapNotNull { it.dewPointF }
        val aqis = points.mapNotNull { it.aqi }
        val rain = points.mapNotNull { it.precipProb }.maxOrNull()
        parts += dayLabelOf(points.first().time) to chrome.heading
        temps.maxOrNull()?.let { parts += "peak ${unit.degUnit(it)}" to d.temp }
        dews.maxOrNull()?.let { parts += "dew ${unit.deg(it)}" to d.dew }
        aqis.maxOrNull()?.let { parts += "AQI $it" to d.aqi }
        rain?.takeIf { it > 0 }?.let { parts += "rain ${it.roundToInt()}%" to d.rain }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, (text, color) ->
            if (i > 0) MonoText(" · ", chrome.faint)
            MonoText(text, color)
        }
    }
}

@Composable
private fun MonoText(text: String, color: Color) = androidx.compose.material3.Text(
    text, color = color, fontSize = AXIS_TEXT, fontFamily = Mono, maxLines = 1, softWrap = false,
)

// ---------------------------------------------------------------- 2. temp + dew

/**
 * Temperature and dew point on one chart, with NO series labels: they share a unit, and the
 * dew point is always the lower of the two, so position and the two established hues are
 * enough to tell them apart. Exactly three gridlines — a fourth is too dense at this height.
 */
@Composable
private fun TempDewBlock(
    points: List<ForecastPoint>, comfort: Comfort, unit: TempUnit, tm: TextMeasurer, markerFrac: Float?,
) {
    val d = Skin.data
    val chrome = Skin.chrome
    val cutoff = unit.fromF(comfort.maxTempF)
    val temps = points.mapNotNull { it.tempF?.let(unit::fromF) }
    val dews = points.mapNotNull { it.dewPointF?.let(unit::fromF) }
    if (temps.isEmpty() && dews.isEmpty()) return
    val all = temps + dews + listOf(cutoff)
    val lo = all.min() - 3
    val hi = all.max() + 3

    Canvas(Modifier.fillMaxWidth().height(104.dp)) {
        val g = plot(points.size)
        fun y(v: Double) = g.yAt(v, lo, hi)

        bands(points, g, chrome)

        for (v in gridValues(lo, hi, 3)) {
            val gy = y(v)
            drawLine(chrome.hairline, Offset(g.left, gy), Offset(g.right, gy), strokeWidth = 1f)
            label(tm, "${v.roundToInt()}°", chrome.faint, AXIS_TEXT, 0f, gy - 6.dp.toPx())
        }

        // The comfort cutoff, named at the right edge — the line the temperature curve is
        // read against, and the reason a warm-looking day can still be an open-window one.
        val cy = y(cutoff)
        drawLine(
            d.cutoffRule, Offset(g.left, cy), Offset(g.right, cy), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
        )
        val cutoffLabel = tm.measure(
            "cutoff ${cutoff.roundToInt()}°",
            TextStyle(color = chrome.faint, fontSize = ANNOTATION_TEXT, fontFamily = Mono),
        )
        drawText(cutoffLabel, topLeft = Offset(g.right - cutoffLabel.size.width, cy - cutoffLabel.size.height - 1.dp.toPx()))

        series(points, g, { it.dewPointF?.let(unit::fromF) }, ::y, d.dew, 2.dp.toPx(), dashed = true)
        series(points, g, { it.tempF?.let(unit::fromF) }, ::y, d.temp, 2.4.dp.toPx(), dashed = false)

        markerFrac?.let { drawLine(chrome.heading, Offset(g.xAtFrac(it), g.top), Offset(g.xAtFrac(it), g.bottom), strokeWidth = 1.2.dp.toPx()) }
    }
}

// ---------------------------------------------------------------- 3. air quality

/**
 * Air quality as ONE thin gradient bar rather than 24 columns. A running AQI has no per-hour
 * shape worth reading — what matters is the level and where it drifts — so the day's highest
 * and lowest readings are printed over the very hours they fall on, just above the strip,
 * and the label/value row the block used to carry is gone.
 */
@Composable
private fun AqiBlock(points: List<ForecastPoint>, palette: AqiPalette, tm: TextMeasurer) {
    val chrome = Skin.chrome
    val dark = chrome.dark
    val series = points.map { it.aqi }
    val vals = series.filterNotNull()
    if (vals.isEmpty()) return
    val maxV = vals.max()
    val minV = vals.min()
    val maxIdx = series.indexOfFirst { it == maxV }
    val minIdx = series.indexOfLast { it == minV }

    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val g = plot(points.size)
        val barH = 4.dp.toPx()
        val barTop = size.height - barH
        val r = CornerRadius(barH / 2)
        val (colors, stops) = AqiRamp.sweep(series, palette, dark)
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colorStops = Array(colors.size) { stops[it] to Color(colors[it]) },
                startX = g.left, endX = g.right,
            ),
            topLeft = Offset(g.left, barTop),
            size = Size(g.right - g.left, barH),
            cornerRadius = r,
        )
        // The outline is what gives the strip an edge under any palette.
        drawRoundRect(
            color = chrome.hairline,
            topLeft = Offset(g.left, barTop),
            size = Size(g.right - g.left, barH),
            cornerRadius = r,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.75.dp.toPx()),
        )
        // Peak wins any collision: draw it first, and drop the trough only if the two labels
        // would touch (a flat day with one AQI draws a single number).
        fun labelFor(v: Int) = tm.measure(
            "$v",
            TextStyle(
                color = Color(AqiRamp.colorAt(v.toDouble(), palette, dark)),
                fontSize = ANNOTATION_TEXT, fontFamily = Mono, fontWeight = FontWeight.Bold,
            ),
        )
        val maxL = labelFor(maxV)
        val labelY = barTop - maxL.size.height - 1.dp.toPx()
        val maxX = (g.xAt(maxIdx) - maxL.size.width / 2f).coerceIn(g.left, g.right - maxL.size.width)
        drawText(maxL, topLeft = Offset(maxX, labelY))
        if (minV != maxV) {
            val minL = labelFor(minV)
            val minX = (g.xAt(minIdx) - minL.size.width / 2f).coerceIn(g.left, g.right - minL.size.width)
            val gap = 3.dp.toPx()
            if (minX + minL.size.width < maxX - gap || minX > maxX + maxL.size.width + gap) {
                drawText(minL, topLeft = Offset(minX, labelY))
            }
        }
    }
}

// ---------------------------------------------------------------- 4. rain

/**
 * Rain gets its own chart and its own axis, because chance and intensity are two different
 * numbers and the old shared axis could only show one of them honestly.
 *
 * Height is the chance; WIDTH is the intensity. A tall thin column is likely drizzle, a
 * tall fat one a downpour — which is the distinction that decides whether an open window
 * is a problem. Because width carries meaning, a column at the plot edge clamps its CENTRE
 * and keeps its width.
 */
@Composable
private fun RainBlock(
    points: List<ForecastPoint>, comfort: Comfort, rainScaleInPerHr: Double, thunderAlertPct: Double,
    tm: TextMeasurer, markerFrac: Float?,
) {
    val d = Skin.data
    val chrome = Skin.chrome
    val anyRain = points.any { (it.precipProb ?: 0.0) > 0 || (it.precipIn ?: 0.0) > 0 }

    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val g = plot(points.size)
        // Columns sit on the canvas floor and rise into a reserved top band, so the peak
        // annotation always has room above the tallest column instead of spilling up into
        // the AQI strip above the block.
        val topBand = 15.dp.toPx()
        val base = g.bottom
        val colTop = topBand
        val colH = base - colTop

        if (!anyRain) {
            val l = tm.measure(
                "no rain in 24 h",
                TextStyle(color = chrome.faint, fontSize = AXIS_TEXT, fontFamily = Mono),
            )
            drawText(l, topLeft = Offset((size.width - l.size.width) / 2, colTop + colH / 2 - l.size.height / 2))
            return@Canvas
        }

        label(tm, "100%", chrome.faint, AXIS_TEXT, 0f, 1.dp.toPx())
        label(tm, "0", chrome.faint, AXIS_TEXT, 0f, base - 12.dp.toPx())

        val cutoffY = base - (comfort.rainProbIdeal / 100.0).toFloat() * colH
        drawLine(
            d.cutoffRule, Offset(g.left, cutoffY), Offset(g.right, cutoffY), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
        )

        val slot = (g.right - g.left) / points.size.coerceAtLeast(1)
        var peak = -1
        points.forEachIndexed { i, p ->
            val chance = p.precipProb ?: 0.0
            if (chance <= 0 && (p.precipIn ?: 0.0) <= 0) return@forEachIndexed
            if (peak < 0 || chance > (points[peak].precipProb ?: 0.0)) peak = i
            val intensity = min(1.0, (p.precipIn ?: 0.0) / rainScaleInPerHr.coerceAtLeast(0.01))
            val w = slot * (0.26 + 0.7 * intensity).toFloat()
            val h = max((chance / 100.0).toFloat() * colH, 2.dp.toPx())
            val cx = g.xAt(i).coerceIn(g.left, g.right)
            val storm = (p.thunderProb ?: 0.0) >= thunderAlertPct
            drawRect(
                color = if (storm) d.storm else d.rain,
                topLeft = Offset(cx - w / 2, base - h),
                size = Size(w, h),
            )
        }

        if (peak >= 0) {
            val p = points[peak]
            val text = buildString {
                append("${(p.precipProb ?: 0.0).roundToInt()}%")
                (p.precipIn ?: 0.0).takeIf { it > 0 }?.let { append("  ${fmtIn(it)}") }
            }
            val storm = (p.thunderProb ?: 0.0) >= thunderAlertPct
            val l = tm.measure(text, TextStyle(color = if (storm) d.storm else chrome.body, fontSize = AXIS_TEXT, fontFamily = Mono))
            val h = max(((p.precipProb ?: 0.0) / 100.0).toFloat() * colH, 2.dp.toPx())
            val x = (g.xAt(peak) - l.size.width / 2).coerceIn(g.left, g.right - l.size.width)
            drawText(l, topLeft = Offset(x, base - h - l.size.height - 2.dp.toPx()))
        }

        markerFrac?.let { drawLine(chrome.heading, Offset(g.xAtFrac(it), colTop), Offset(g.xAtFrac(it), base), strokeWidth = 1.2.dp.toPx()) }
    }
}

// ---------------------------------------------------------------- 5. shared time axis

/**
 * One axis under all four blocks. The clock sits beside the now-line; an hour tick whose
 * measured box would collide with it is suppressed rather than allowed to overprint —
 * measured, not assumed, because the gap is only wide enough at some times of day.
 */
@Composable
private fun TimeAxis(points: List<ForecastPoint>, tm: TextMeasurer, nowFrac: Float?) {
    val chrome = Skin.chrome
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val g = plot(points.size)
        val style = TextStyle(color = chrome.faint, fontSize = AXIS_TEXT, fontFamily = Mono)

        var clockBox: ClosedFloatingPointRange<Float>? = null
        if (nowFrac != null) {
            val l = tm.measure(
                nowClock(),
                TextStyle(color = chrome.heading, fontSize = AXIS_TEXT, fontFamily = Mono, fontWeight = FontWeight.SemiBold),
            )
            val x = (g.xAtFrac(nowFrac) + 3.dp.toPx()).coerceAtMost(size.width - l.size.width)
            drawText(l, topLeft = Offset(x, 0f))
            clockBox = (x - 4.dp.toPx())..(x + l.size.width + 4.dp.toPx())
        }

        points.forEachIndexed { i, p ->
            if (ForecastBuilder.hour(p.time) % 6 != 0) return@forEachIndexed
            val l = tm.measure(ForecastBuilder.fmtHour(p.time), style)
            val x = (g.xAt(i) - l.size.width / 2).coerceIn(0f, size.width - l.size.width)
            val box = x..(x + l.size.width)
            if (clockBox != null && box.start <= clockBox.endInclusive && clockBox.start <= box.endInclusive) return@forEachIndexed
            drawText(l, topLeft = Offset(x, 0f))
        }
    }
}

// ---------------------------------------------------------------- shared geometry

private class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float, val n: Int) {
    /**
     * A POINT scale: hour 0 and hour n-1 land exactly on the plot edges. Anything centred
     * on one of them therefore loses half its width unless the caller clamps the centre.
     */
    fun xAt(i: Int) = if (n <= 1) (left + right) / 2 else left + i.toFloat() / (n - 1) * (right - left)

    fun xAtFrac(f: Float) = left + f.coerceIn(0f, 1f) * (right - left)

    fun yAt(v: Double, lo: Double, hi: Double): Float {
        val f = if (hi <= lo) 0.0 else (v - lo) / (hi - lo)
        return bottom - f.toFloat().coerceIn(0f, 1f) * (bottom - top)
    }
}

private fun DrawScope.plot(n: Int) =
    Plot(PLOT_LEFT.toPx(), 0f, size.width - PLOT_RIGHT_INSET.toPx(), size.height, n)

/**
 * Tap or horizontal-drag anywhere over the chart stack to pick the hour under the finger.
 * The horizontal drag is consumed so it scrubs rather than scrolls; a vertical drag isn't
 * touched, so the day list still scrolls through the overlay.
 */
private fun Modifier.scrub(n: Int, onSel: (Int?) -> Unit): Modifier = this
    .pointerInput(n) { detectTapGestures { o -> onSel(idxAt(o.x, size.width.toFloat(), n)) } }
    .pointerInput(n) {
        detectHorizontalDragGestures { change, _ ->
            onSel(idxAt(change.position.x, size.width.toFloat(), n)); change.consume()
        }
    }

/** Which point index an x maps to, measured within the same [PLOT_LEFT]/inset gutters the charts use. */
private fun Density.idxAt(x: Float, width: Float, n: Int): Int {
    if (n <= 1) return 0
    val left = PLOT_LEFT.toPx()
    val right = width - PLOT_RIGHT_INSET.toPx()
    val frac = ((x - left) / (right - left)).coerceIn(0f, 1f)
    return (frac * (n - 1)).roundToInt().coerceIn(0, n - 1)
}

private fun DrawScope.label(
    tm: TextMeasurer, text: String, color: Color, size: androidx.compose.ui.unit.TextUnit, x: Float, y: Float,
) {
    drawText(tm.measure(text, TextStyle(color = color, fontSize = size, fontFamily = Mono)), topLeft = Offset(x, y))
}

/** Round values inside [lo]..[hi], for gridlines that land on numbers worth reading. */
private fun gridValues(lo: Double, hi: Double, count: Int): List<Double> {
    val span = hi - lo
    if (span <= 0) return emptyList()
    val raw = span / (count + 1)
    val step = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0).firstOrNull { it >= raw } ?: ceil(raw / 50) * 50
    val first = ceil(lo / step) * step
    return generateSequence(first) { it + step }.takeWhile { it < hi }.take(count).toList()
}

/**
 * The 6% day/night tint. Which half wears it flips with the theme — the same tint darkens
 * paper and lightens dark ground, so a fixed half would read brighter at night in one of them.
 */
private fun DrawScope.bands(points: List<ForecastPoint>, g: Plot, chrome: ChromeColors) {
    val n = points.size
    if (n == 0) return
    val slot = (g.right - g.left) / n
    points.forEachIndexed { i, p ->
        if ((p.isDay ?: true) == chrome.bandOnNight) return@forEachIndexed
        drawRect(chrome.bandTint, topLeft = Offset(g.left + i * slot, g.top), size = Size(slot, g.bottom - g.top))
    }
}

/** One series as a line, breaking rather than plotting zero where an hour has no reading. */
private fun DrawScope.series(
    points: List<ForecastPoint>, g: Plot, value: (ForecastPoint) -> Double?, y: (Double) -> Float,
    color: Color, width: Float, dashed: Boolean,
) {
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.4.dp.toPx())) else null
    for (i in 0 until points.size - 1) {
        val a = value(points[i]) ?: continue
        val b = value(points[i + 1]) ?: continue
        drawLine(color, Offset(g.xAt(i), y(a)), Offset(g.xAt(i + 1), y(b)), strokeWidth = width, pathEffect = effect)
    }
}

private fun fmtIn(v: Double): String =
    (if (v >= 0.1) String.format("%.1f", v) else String.format("%.2f", v)) + "″"

private fun nowClock(): String {
    val now = java.time.LocalTime.now()
    val h12 = (now.hour % 12).let { if (it == 0) 12 else it }
    return "%d:%02d %s".format(h12, now.minute, if (now.hour < 12) "AM" else "PM")
}

/** Index of the row covering the present, or -1 when this series doesn't contain now. */
private fun nowIndexOf(points: List<ForecastPoint>): Int {
    val prefix = java.time.LocalDateTime.now().toString().take(16)
    val i = points.indexOfLast { it.time.take(16) <= prefix }
    return if (i >= 0 && points[i].time.take(10) == java.time.LocalDate.now().toString()) i else -1
}

/**
 * Where the present falls across the plot, 0..1 — the REAL clock time, not the first
 * forecast hour, so the gap between the rule and the edge is visible staleness.
 */
private fun nowFractionOf(points: List<ForecastPoint>): Float? {
    val i = nowIndexOf(points)
    if (i < 0 || points.size <= 1) return null
    val within = java.time.LocalTime.now().minute / 60f
    return ((i + within) / (points.size - 1)).coerceIn(0f, 1f)
}

private fun dayLabelOf(iso: String): String = try {
    val d = java.time.LocalDate.parse(iso.take(10))
    when (d) {
        java.time.LocalDate.now() -> "today"
        java.time.LocalDate.now().plusDays(1) -> "tomorrow"
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE M/d")).lowercase()
    }
} catch (e: Exception) {
    ""
}
