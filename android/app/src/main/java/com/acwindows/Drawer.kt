package com.acwindows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
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

/** Gradient-strip height; the on-bar numbers are sized from it so their digits fill it. */
private val BAR_H = 7.dp

@Composable
fun DetailDrawer(
    points: List<ForecastPoint>,
    unit: TempUnit,
    palette: AqiPalette,
    thunderAlertPct: Double,
    comfort: Comfort,
    isToday: Boolean,
    // Selected hour is hoisted to the card, so the score bars above can mark it too; null
    // follows the present, so an untouched today card still opens on "now".
    sel: Int?,
    onSel: (Int?) -> Unit,
) {
    if (points.isEmpty()) return
    val tm = rememberTextMeasurer()
    val n = points.size
    val nowFrac = remember(points, isToday) { if (isToday) nowFractionOf(points) else null }
    val nowIdx = remember(points, isToday) { if (isToday) nowIndexOf(points) else -1 }

    val activeIdx = sel ?: nowIdx
    // The present is drawn on every today chart as a persistent grey reference; the picked
    // hour, when the finger has chosen one, rides above it as the darker marker.
    val selFrac = sel?.let { if (n <= 1) 0.5f else it.toFloat() / (n - 1) }

    Column(Modifier.fillMaxWidth()) {
        ReadingsLine(points, unit, activeIdx)
        Spacer(Modifier.height(8.dp))
        // The scrub gesture lives on the Box itself rather than a matchParentSize() overlay:
        // the overlay's pointer node wasn't laid out on the first frame a card opened, so a
        // hold-and-drag on the default-expanded today card did nothing until a re-layout (a
        // day toggle) woke it. The Canvas children draw only — none consume pointer events —
        // so a gesture on the Box reaches the whole stack. A horizontal drag is consumed to
        // scrub; a vertical one falls through to scroll the list.
        Box(Modifier.fillMaxWidth().scrub(n, onSel)) {
            Column {
                val dark = Skin.chrome.dark
                TempDewBlock(points, unit, tm, nowFrac, selFrac, comfort)
                Spacer(Modifier.height(4.dp))
                GradientBarBlock(
                    "AQI", points.map { it.aqi?.toDouble() },
                    AqiRamp.sweep(points.map { it.aqi }, palette, dark),
                    fmt = { it.roundToInt().toString() }, show = BarValues.HIGH_LOW, sel = sel, tm = tm,
                )
                Spacer(Modifier.height(3.dp))
                GradientBarBlock(
                    "UV", points.map { it.uvIndex },
                    UvRamp.sweep(points.map { it.uvIndex }, points.map { it.isDay }, dark),
                    fmt = { it.roundToInt().toString() }, show = BarValues.PEAK, sel = sel, tm = tm,
                )
                Spacer(Modifier.height(3.dp))
                GradientBarBlock(
                    "cld", points.map { it.cloudPct },
                    CloudRamp.skySweep(points.map { it.isDay }),
                    fmt = { "${it.roundToInt()}%" }, show = BarValues.NONE, sel = sel, tm = tm,
                    overlay = { g -> halftone(g, points.map { it.cloudPct }) },
                )
                Spacer(Modifier.height(4.dp))
                RainBlock(points, thunderAlertPct, tm, nowFrac, selFrac)
                TimeAxis(points, tm, nowFrac)
            }
        }
    }
}

// ---------------------------------------------------------------- 1. readings line

/**
 * One running line of the hour's actual numbers, each in its own series colour.
 *
 * Rain is dropped at zero chance: `rain 0%` says nothing the rain chart below doesn't.
 * Cloud cover is omitted entirely — the halftone strip is the reading. UV drops at zero
 * (night), for the same reason. A non-zero rain chance always shows.
 */
@OptIn(ExperimentalLayoutApi::class)
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
        p.uvIndex?.takeIf { it > 0 }?.let { parts += "UV ${it.roundToInt()}" to Color(UvRamp.colorAt(it, chrome.dark)) }
        p.precipProb?.takeIf { it > 0 }?.let { prob ->
            val rain = buildString {
                append("rain ${prob.roundToInt()}%")
                (p.precipIn ?: 0.0).takeIf { it > 0 }?.let { append(" ${fmtIn(it)}") }
            }
            parts += rain to d.rain
        }
    } else {
        // A future day has no "now" to read, so it reports its own extremes instead.
        val temps = points.mapNotNull { it.tempF }
        val dews = points.mapNotNull { it.dewPointF }
        val aqis = points.mapNotNull { it.aqi }
        val uvs = points.mapNotNull { it.uvIndex }
        val rain = points.mapNotNull { it.precipProb }.maxOrNull()
        parts += dayLabelOf(points.first().time) to chrome.heading
        temps.maxOrNull()?.let { parts += "peak ${unit.degUnit(it)}" to d.temp }
        dews.maxOrNull()?.let { parts += "dew ${unit.deg(it)}" to d.dew }
        aqis.maxOrNull()?.let { parts += "AQI $it" to d.aqi }
        uvs.maxOrNull()?.takeIf { it > 0 }?.let { parts += "UV ${it.roundToInt()}" to Color(UvRamp.colorAt(it, chrome.dark)) }
        rain?.takeIf { it > 0 }?.let { parts += "rain ${it.roundToInt()}%" to d.rain }
    }

    // A flow, not a Row: overflow wraps rather than clipping a reading off the right edge.
    // Each value carries its own trailing separator as one atomic unit, so a wrap never
    // strands a leading " · " at the start of the next line.
    FlowRow(Modifier.fillMaxWidth()) {
        parts.forEachIndexed { i, (text, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReadoutText(text, color)
                if (i < parts.lastIndex) ReadoutText(" · ", chrome.faint)
            }
        }
    }
}

// The default (proportional) app font, not Mono: this is one running line, not a column of
// figures to align, and mono's fixed-width digits and padded separators ran it off-screen.
@Composable
private fun ReadoutText(text: String, color: Color) = androidx.compose.material3.Text(
    text, color = color, fontSize = AXIS_TEXT, fontFamily = Ui, maxLines = 1, softWrap = false,
)

// ---------------------------------------------------------------- 2. temp + dew

/**
 * Temperature and dew point on one chart, with NO series labels: they share a unit, and the
 * dew point is always the lower of the two, so position and the two established hues are
 * enough to tell them apart. Exactly three gridlines — a fourth is too dense at this height.
 */
@Composable
private fun TempDewBlock(
    points: List<ForecastPoint>, unit: TempUnit, tm: TextMeasurer, nowFrac: Float?, selFrac: Float?,
    comfort: Comfort,
) {
    val d = Skin.data
    val chrome = Skin.chrome
    val temps = points.mapNotNull { it.tempF?.let(unit::fromF) }
    val dews = points.mapNotNull { it.dewPointF?.let(unit::fromF) }
    if (temps.isEmpty() && dews.isEmpty()) return
    val all = temps + dews
    val lo = all.min() - 3
    val hi = all.max() + 3

    Canvas(Modifier.fillMaxWidth().height(104.dp)) {
        val g = plot(points.size)
        fun y(v: Double) = g.yAt(v, lo, hi)

        fieldBg(g, chrome)
        bands(points, g, chrome)

        for (v in gridValues(lo, hi, 3)) {
            val gy = y(v)
            drawLine(chrome.hairline, Offset(g.left, gy), Offset(g.right, gy), strokeWidth = 1f)
            label(tm, "${v.roundToInt()}°", chrome.faint, AXIS_TEXT, 0f, gy - 6.dp.toPx())
        }

        series(
            points, g, { it.dewPointF?.let(unit::fromF) }, ::y, d.dew, 2.dp.toPx(), dashed = true,
            degF = { it.dewPointF }, highF = comfort.dewPointIdealF, lowF = comfort.dewPointMinIdealF,
        )
        tempSeries(points, g, unit, ::y, d.temp, 2.4.dp.toPx(), comfort, coldMarkArgb(chrome.dark))

        // Value labels are inked one notch toward the heading and haloed in the ground colour,
        // so a number sitting on its own line stays readable rather than dissolving into it.
        val halo = if (chrome.dark) Color(0xCC000000) else Color(0xCCFFFFFF)
        val tempInk = lerp(d.temp, chrome.heading, 0.3f)
        val dewInk = lerp(d.dew, chrome.heading, 0.3f)

        // Each series' own high and low over the day, printed on the hour they land on.
        extremes(points) { it.tempF }?.let { (hiIdx, loIdx) ->
            pointValue(tm, unit.deg(points[hiIdx].tempF!!), tempInk, halo, g, g.xAt(hiIdx), y(unit.fromF(points[hiIdx].tempF!!)), above = true)
            if (loIdx != hiIdx) pointValue(tm, unit.deg(points[loIdx].tempF!!), tempInk, halo, g, g.xAt(loIdx), y(unit.fromF(points[loIdx].tempF!!)), above = false)
        }
        extremes(points) { it.dewPointF }?.let { (hiIdx, loIdx) ->
            pointValue(tm, unit.deg(points[hiIdx].dewPointF!!), dewInk, halo, g, g.xAt(hiIdx), y(unit.fromF(points[hiIdx].dewPointF!!)), above = true)
            if (loIdx != hiIdx) pointValue(tm, unit.deg(points[loIdx].dewPointF!!), dewInk, halo, g, g.xAt(loIdx), y(unit.fromF(points[loIdx].dewPointF!!)), above = false)
        }

        markers(g, nowFrac, selFrac, chrome, g.top, g.bottom)
    }
}

// ---------------------------------------------------------------- 3. air quality

/** A bar's DEFAULT (un-scrubbed) number: both extremes (AQI), just the day peak, or none
 *  (cloud — the halftone speaks for itself). The scrubbed-hour value always shows regardless. */
private enum class BarValues { PEAK, HIGH_LOW, NONE }

/**
 * A running scalar — air quality, UV index, cloud cover — as ONE gradient strip across the
 * hour axis rather than a column per hour: the value has no per-hour shape worth plotting,
 * only a level and where it drifts. The strip is exactly tall enough to seat its own numbers,
 * which are printed ON the bar over the hours they fall on — white on a dark halo, so they
 * clear whatever colour the gradient runs under them and cost the block no extra height. The
 * left gutter names the strip, since a gradient carries no axis to label it.
 *
 * While an hour is actively scrubbed ([sel] non-null) the strip drops its day extremes and
 * shows just that hour's reading over its own column — in line with the selection marker on
 * the charts above and below — so a value can be read at a chosen hour, not interpolated.
 */
@Composable
private fun GradientBarBlock(
    tag: String,
    series: List<Double?>,
    sweep: Pair<IntArray, FloatArray>,
    fmt: (Double) -> String,
    show: BarValues,
    sel: Int?,
    tm: TextMeasurer,
    overlay: (DrawScope.(Plot) -> Unit)? = null,
) {
    val chrome = Skin.chrome
    val known = series.withIndex().mapNotNull { (i, v) -> v?.let { i to it } }
    if (known.isEmpty()) return
    val (maxIdx, maxV) = known.maxByOrNull { it.second }!!
    val (minIdx, minV) = known.minByOrNull { it.second }!!

    Canvas(Modifier.fillMaxWidth().height(BAR_H)) {
        val g = plot(series.size)
        val rect = Size(g.right - g.left, size.height)
        val r = CornerRadius(size.height / 3f)
        val (colors, stops) = sweep
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colorStops = Array(colors.size) { stops[it] to Color(colors[it]) },
                startX = g.left, endX = g.right,
            ),
            topLeft = Offset(g.left, 0f), size = rect, cornerRadius = r,
        )
        // A textured surface (the cloud halftone) drawn over the base, clipped to the strip.
        overlay?.let { paint ->
            val clip = Path().apply { addRoundRect(RoundRect(g.left, 0f, g.right, size.height, r)) }
            clipPath(clip) { paint(g) }
        }
        drawRoundRect(
            color = chrome.hairline, topLeft = Offset(g.left, 0f), size = rect, cornerRadius = r,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.75.dp.toPx()),
        )
        // Font sized so a digit's cap height fills the bar (cap ≈ 0.72·em); the taller line box
        // overhangs top and bottom and is clipped to the strip, leaving the numbers edge-to-edge.
        val digit = (size.height / 0.72f).toSp()
        val name = tm.measure(tag, TextStyle(color = chrome.faint, fontSize = digit * 0.82f, fontFamily = Mono))
        drawText(name, topLeft = Offset(0f, (size.height - name.size.height) / 2f))

        val cy = size.height / 2f
        // White over a strong opaque-black halo, drawn twice to build the halo up, so a number
        // reads over any colour under it — the pale overcast/UV/yellow ends included.
        val style = TextStyle(
            color = Color.White, fontSize = digit, fontFamily = Mono, fontWeight = FontWeight.Bold,
            shadow = Shadow(Color(0xFF000000), Offset.Zero, blurRadius = 2.5.dp.toPx()),
        )
        fun blit(l: TextLayoutResult, x: Float) {
            val at = Offset(x, cy - l.size.height / 2f)
            drawText(l, topLeft = at); drawText(l, topLeft = at)
        }
        fun place(value: Double, atIdx: Int): ClosedFloatingPointRange<Float> {
            val l = tm.measure(fmt(value), style)
            val x = (g.xAt(atIdx) - l.size.width / 2f).coerceIn(g.left, g.right - l.size.width)
            blit(l, x)
            return x..(x + l.size.width)
        }

        if (sel != null) {
            // Active scrub always shows this hour's reading over its column, aligned with the
            // marker — for every strip, cloud included; nothing only when the hour has no value.
            series.getOrNull(sel)?.let { place(it, sel) }
        } else when (show) {
            BarValues.NONE -> Unit          // self-explanatory strip (cloud): no default number
            BarValues.PEAK -> place(maxV, maxIdx)
            BarValues.HIGH_LOW -> {
                val hi = place(maxV, maxIdx)
                // Peak wins any collision; the trough is drawn only when it clears the peak's box.
                if (minIdx != maxIdx) {
                    val loL = tm.measure(fmt(minV), style)
                    val loX = (g.xAt(minIdx) - loL.size.width / 2f).coerceIn(g.left, g.right - loL.size.width)
                    val gap = 3.dp.toPx()
                    if (loX + loL.size.width < hi.start - gap || loX > hi.endInclusive + gap) blit(loL, loX)
                }
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
    points: List<ForecastPoint>, thunderAlertPct: Double,
    tm: TextMeasurer, nowFrac: Float?, selFrac: Float?,
) {
    val d = Skin.data
    val chrome = Skin.chrome
    val anyRain = points.any { (it.precipProb ?: 0.0) > 0 || (it.precipIn ?: 0.0) > 0 }

    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val g = plot(points.size)
        // Columns rise from the floor over the full field; the peak annotation drops inside
        // the column when a tall one would push it off the top, so no top band is reserved.
        val base = g.bottom
        val colTop = 0f
        val colH = base - colTop

        fieldBg(g, chrome)
        bands(points, g, chrome)

        if (!anyRain) {
            val l = tm.measure(
                "no rain in 24 h",
                TextStyle(color = chrome.faint, fontSize = AXIS_TEXT, fontFamily = Mono),
            )
            drawText(l, topLeft = Offset((size.width - l.size.width) / 2, colTop + colH / 2 - l.size.height / 2))
            return@Canvas
        }

        // Top label top-aligned to the 100% line (the plot top) so it sits inside the field;
        // the "%" rides the shorter bottom label to keep the top one narrow in the gutter.
        label(tm, "100", chrome.faint, AXIS_TEXT, 0f, colTop)
        label(tm, "0%", chrome.faint, AXIS_TEXT, 0f, base - 12.dp.toPx())

        // Quarter gridlines across the 0–100% chance axis, so a column's height is a value.
        for (pct in intArrayOf(25, 50, 75)) {
            val gy = base - (pct / 100f) * colH
            drawLine(chrome.hairline, Offset(g.left, gy), Offset(g.right, gy), strokeWidth = 1f)
        }

        var peak = -1
        points.forEachIndexed { i, p ->
            val chance = p.precipProb ?: 0.0
            if (chance <= 0 && (p.precipIn ?: 0.0) <= 0) return@forEachIndexed
            if (peak < 0 || chance > (points[peak].precipProb ?: 0.0)) peak = i
            val w = Tokens.rainMarkDp(p.precipIn ?: 0.0).dp.toPx()
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
            val halo = if (chrome.dark) Color(0xCC000000) else Color(0xCCFFFFFF)
            val l = tm.measure(text, TextStyle(
                color = if (storm) d.storm else chrome.body, fontSize = AXIS_TEXT, fontFamily = Mono,
                shadow = Shadow(halo, Offset.Zero, blurRadius = 2.dp.toPx()),
            ))
            val h = max(((p.precipProb ?: 0.0) / 100.0).toFloat() * colH, 2.dp.toPx())
            val x = (g.xAt(peak) - l.size.width / 2).coerceIn(g.left, g.right - l.size.width)
            // Above the column, but tucked just inside its top when a tall one would clip it.
            val above = base - h - l.size.height - 2.dp.toPx()
            drawText(l, topLeft = Offset(x, if (above < 0f) base - h + 2.dp.toPx() else above))
        }

        markers(g, nowFrac, selFrac, chrome, colTop, base)
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
 *
 * [onSel] is read at event time, not captured when the handler starts: `pointerInput`'s
 * block keeps the first lambda, and the default-open today card replaces its `sel` state
 * on the first data refresh (same [n], new list) — so the handler would keep writing to
 * the discarded state, looking dead until the drawer is disposed and recreated. Size is
 * a key too: the first layout of that card is often empty, and `pointerInput` does not
 * restart on size alone.
 */
private fun Modifier.scrub(n: Int, onSel: (Int?) -> Unit): Modifier = composed {
    val currentOnSel by rememberUpdatedState(onSel)
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    onSizeChanged { boxSize = it }
        .pointerInput(n, boxSize) {
            if (boxSize.width <= 0 || boxSize.height <= 0) return@pointerInput
            detectTapGestures { o -> currentOnSel(idxAt(o.x, size.width.toFloat(), n)) }
        }
        .pointerInput(n, boxSize) {
            if (boxSize.width <= 0 || boxSize.height <= 0) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = { o -> currentOnSel(idxAt(o.x, size.width.toFloat(), n)) },
            ) { change, _ ->
                currentOnSel(idxAt(change.position.x, size.width.toFloat(), n))
                change.consume()
            }
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
 * A slightly sunken fill over the plotting rectangle, so the chart field reads as its own
 * surface and the y-axis top and bottom are visible edges rather than blending into the card.
 * Drawn first, under the day/night bands and gridlines.
 */
private fun DrawScope.fieldBg(g: Plot, chrome: ChromeColors) {
    drawRect(chrome.sunken, topLeft = Offset(g.left, g.top), size = Size(g.right - g.left, g.bottom - g.top))
}

/**
 * Cloud cover as an amplitude-modulated halftone over the sky base: a hex grid of solid dots
 * whose AREA equals the hour's cover, so the fraction of white (cloud) pixels equals the cover.
 * Radius comes from [cloudDotRatio] (overlap-corrected), giving proportional cover up to a solid
 * fill at 100%. Dots are solid — anti-aliasing is the only soft edge, since a faded dot paints
 * fewer white pixels than its area and would break the proportion. Cover is interpolated between
 * hours so the screen thickens and thins smoothly rather than stepping at each column.
 */
private fun DrawScope.halftone(g: Plot, cover: List<Double?>) {
    val n = cover.size
    if (n == 0) return
    val f = FloatArray(n)
    var last = 0f
    for (i in 0 until n) {
        cover[i]?.let { last = (it / 100.0).toFloat().coerceIn(0f, 1f) }
        f[i] = last
    }
    val span = (g.right - g.left).coerceAtLeast(1f)
    fun coverAt(x: Float): Float {
        if (n == 1) return f[0]
        val t = ((x - g.left) / span).coerceIn(0f, 1f) * (n - 1)
        val i0 = t.toInt().coerceIn(0, n - 1)
        val i1 = (i0 + 1).coerceAtMost(n - 1)
        return f[i0] + (f[i1] - f[i0]) * (t - i0)
    }
    val s = 2.4.dp.toPx()
    val rowH = s * 0.8660254f                     // hex packing row pitch, √3/2 · s
    val dot = Color(CloudRamp.CLOUD)
    var row = 0
    var y = rowH * 0.5f
    while (y < size.height + rowH) {
        var x = g.left + if (row % 2 == 0) 0f else s * 0.5f
        while (x <= g.right) {
            val c = coverAt(x)
            if (c > 0.01f) drawCircle(dot, radius = CloudRamp.dotRatio(c) * s, center = Offset(x, y))
            x += s
        }
        y += rowH
        row++
    }
}

/**
 * One series' extreme reading printed over the hour it falls on, with no label. Above the
 * point for a high, below it for a low, clamped inside the canvas. [halo] rings the glyphs in
 * the ground colour so the number reads even where it lands on its own line.
 */
private fun DrawScope.pointValue(
    tm: TextMeasurer, text: String, color: Color, halo: Color, g: Plot, atX: Float, atY: Float, above: Boolean,
) {
    val l = tm.measure(
        text,
        TextStyle(
            color = color, fontSize = ANNOTATION_TEXT, fontFamily = Mono, fontWeight = FontWeight.Bold,
            shadow = Shadow(halo, Offset.Zero, blurRadius = 2.dp.toPx()),
        ),
    )
    val x = (atX - l.size.width / 2f).coerceIn(g.left, g.right - l.size.width)
    val y = (if (above) atY - l.size.height - 2.dp.toPx() else atY + 2.dp.toPx())
        .coerceIn(0f, size.height - l.size.height)
    drawText(l, topLeft = Offset(x, y))
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

/**
 * Temperature line. Red at/above [Comfort.tempLineHotF], white (the cold mark) at/below
 * [Comfort.tempLineColdF]. Those two are line colors, separate from the score cutoffs.
 * The test is on raw °F; the segment takes its endpoints' midpoint.
 */
private fun DrawScope.tempSeries(
    points: List<ForecastPoint>, g: Plot, unit: TempUnit, y: (Double) -> Float, base: Color, width: Float,
    comfort: Comfort, coldArgb: Int,
) {
    series(
        points, g, { it.tempF?.let(unit::fromF) }, y, base, width, dashed = false,
        degF = { it.tempF }, highF = comfort.tempLineHotF, lowF = comfort.tempLineColdF,
        highColor = HotRedArgb, lowColor = coldArgb,
    )
}

/** The (highest, lowest) point indices of a series, or null when it has no readings. */
private fun extremes(points: List<ForecastPoint>, sel: (ForecastPoint) -> Double?): Pair<Int, Int>? {
    val idx = points.indices.filter { sel(points[it]) != null }
    if (idx.isEmpty()) return null
    return idx.maxByOrNull { sel(points[it])!! }!! to idx.minByOrNull { sel(points[it])!! }!!
}

/**
 * Two vertical reference lines over a chart: a persistent light-grey "now" (today only, so a
 * scrubbed hour can be read against the present) and, above it, the darker selection marker
 * for whichever hour the finger has picked.
 */
private fun DrawScope.markers(
    g: Plot, nowFrac: Float?, selFrac: Float?, chrome: ChromeColors, top: Float, bottom: Float,
) {
    nowFrac?.let {
        drawLine(chrome.faint.copy(alpha = 0.6f), Offset(g.xAtFrac(it), top), Offset(g.xAtFrac(it), bottom), strokeWidth = 1.2.dp.toPx())
    }
    selFrac?.let {
        drawLine(chrome.heading, Offset(g.xAtFrac(it), top), Offset(g.xAtFrac(it), bottom), strokeWidth = 1.2.dp.toPx())
    }
}

/**
 * One series as a line, breaking rather than plotting a gap where an hour has no reading.
 * A dashed series goes solid where the °F midpoint is outside [lowF, highF] — the dew line,
 * outside the full-score band. Passing [highColor] and [lowColor] tints instead, which is
 * the temperature line.
 */
private fun DrawScope.series(
    points: List<ForecastPoint>, g: Plot, value: (ForecastPoint) -> Double?, y: (Double) -> Float,
    color: Color, width: Float, dashed: Boolean,
    degF: ((ForecastPoint) -> Double?)? = null,
    highF: Double = Double.POSITIVE_INFINITY,
    lowF: Double = Double.NEGATIVE_INFINITY,
    highColor: Int? = null,
    lowColor: Int? = null,
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.4.dp.toPx()))
    val base = color.toArgb()
    for (i in 0 until points.size - 1) {
        val a = value(points[i]) ?: continue
        val b = value(points[i + 1]) ?: continue
        val mid = if (degF == null) null else {
            val af = degF(points[i]) ?: continue
            val bf = degF(points[i + 1]) ?: af
            (af + bf) / 2
        }
        val outside = mid != null && (mid > highF || mid < lowF)
        val ink = if (mid == null || highColor == null || lowColor == null) color
            else Color(segmentTintArgb(mid, base, highF, lowF, highColor, lowColor))
        drawLine(
            ink, Offset(g.xAt(i), y(a)), Offset(g.xAt(i + 1), y(b)),
            strokeWidth = width, pathEffect = if (dashed && !outside) dash else null,
        )
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
