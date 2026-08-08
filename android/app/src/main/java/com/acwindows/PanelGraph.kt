package com.acwindows

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Renders ONE panel of the graphs widget to a bitmap (RemoteViews can't draw vectors —
 * same reason [Graph] exists for the original widget). Two renderings per [Panel]:
 * [PanelMode.TIMESERIES] ports the app's TempChart / CombinedChart / FactorChart-style
 * score bars from MainActivity.kt onto a Canvas, and [PanelMode.HOURLY_ROW] is a fresh
 * horizontal strip modelled on a phone weather app's hourly forecast row.
 *
 * Every panel shows the next `hours` hours FROM NOW: the cached [Snapshot]'s series are
 * fixed clock hours (same as the original widget's), so as the snapshot ages "now" walks
 * forward through them — [render] slices out the window starting at the bar containing
 * the present (via [nowBarPos]) rather than always drawing from index 0.
 */
object PanelGraph {

    // Structural chrome shared with [Graph] — not themed, has to read against any colours.
    private val NOW_WHITE = Color.rgb(0xFF, 0xFF, 0xFF)
    private val STALE_AMBER = Color.rgb(0xFB, 0xBF, 0x24)
    private const val PAST_ALPHA = 90

    // Matches the app's AppTheme.kt constants (HOT_F / HotRed) — the widget can't import
    // those (Compose-only file), so the value and colour are pinned here to stay in sync.
    private const val HOT_F = 90.0
    private val HOT_RED = Color.rgb(0xEF, 0x44, 0x44)
    private val AQI_GREEN = Color.rgb(0x4A, 0xDE, 0x80)
    private val RAIN_CHANCE_BLUE = Color.rgb(0x60, 0xA5, 0xFA)

    private const val MIN_DEG_SPAN_F = 10.0

    // Per-factor detractor tint hues, matching [Graph]'s FACTOR_COLORS (temp/humid/aqi/rain).
    private val FACTOR_COLORS = intArrayOf(
        Color.rgb(0xFB, 0x92, 0x3C),
        Color.rgb(0xA5, 0xB4, 0xFC),
        Color.rgb(0x4A, 0xDE, 0x80),
        Color.rgb(0x22, 0xD3, 0xEE),
    )
    private const val DETRACTOR_TINT = 0.28f

    fun render(
        context: Context,
        panel: Panel,
        mode: PanelMode,
        s: Snapshot,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        theme: WidgetTheme,
        unit: TempUnit,
        hours: Int,
        threshold: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): Bitmap {
        val w = widthPx.coerceAtLeast(80)
        val h = heightPx.coerceAtLeast(60)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val total = s.scores.size
        if (total == 0) return bmp

        val nowPosFull = nowBarPos(s.firstHourMs, nowMs)
        // The bar containing the present, clamped into range — a badly stale snapshot
        // just shows its tail instead of drawing nothing.
        val startIdx = (nowPosFull?.let(::floor)?.toInt() ?: 0).coerceIn(0, total - 1)
        val n = (total - startIdx).coerceAtMost(hours.coerceIn(1, total))
        if (n <= 0) return bmp
        val endIdx = startIdx + n
        // The present's fractional position WITHIN the visible slice (row 0 = the row
        // it's in). Null propagates through so an anchor-less old cache still falls back
        // to labelling row 0 "now" instead of drawing a marker in the wrong place.
        val nowPos = nowPosFull?.minus(startIdx)

        fun <T> slice(list: List<T>): List<T> =
            if (list.isEmpty()) emptyList() else list.subList(startIdx.coerceAtMost(list.size), endIdx.coerceAtMost(list.size))

        val rows = Rows(
            n = n,
            times = slice(s.times),
            scores = slice(s.scores),
            worst = slice(s.worst),
            isDay = slice(s.isDay),
            precipProb = slice(s.precipProb),
            precipIn = slice(s.precipIn),
            tempF = slice(s.tempSeries),
            dewF = slice(s.dewSeries),
            aqi = slice(s.aqiSeries),
            code = slice(s.codeSeries),
        )

        when (mode) {
            PanelMode.TIMESERIES -> renderTimeseries(c, w, h, density, theme, unit, threshold, panel, rows, nowPos)
            PanelMode.HOURLY_ROW -> renderHourlyRow(context, c, w, h, density, theme, unit, panel, rows, nowPos)
        }
        return bmp
    }

    /** The panel's visible slice — every series re-indexed so row 0 is [startIdx]. */
    private class Rows(
        val n: Int,
        val times: List<String>,
        val scores: List<Double>,
        val worst: List<Int>,
        val isDay: List<Boolean>,
        val precipProb: List<Double>,
        val precipIn: List<Double>,
        val tempF: List<Double?>,
        val dewF: List<Double?>,
        val aqi: List<Int?>,
        val code: List<Int?>,
    )

    // ───────────────────────── shared chrome ─────────────────────────

    /** Row [i] is history once "now" has passed its right edge — draw it faded. */
    private fun pastAlpha(i: Int, nowPos: Double?): Int =
        if (nowPos != null && i + 1 <= nowPos) PAST_ALPHA else 255

    private fun dayNightShade(c: Canvas, n: Int, isDay: List<Boolean>, left: Float, cellStep: Float, top: Float, bottom: Float) {
        val dayShade = Paint().apply { color = Color.argb(22, 255, 255, 255) }
        val nightShade = Paint().apply { color = Color.argb(55, 0, 0, 0) }
        for (i in 0 until n) {
            val up = isDay.getOrElse(i) { true }
            val x0 = left + i * cellStep
            c.drawRect(x0, top, x0 + cellStep, bottom, if (up) dayShade else nightShade)
        }
    }

    /** Same "now" marker as [Graph.render]'s — bright line + pill, amber "stale" once the
     *  present has fallen outside the visible slice. */
    private fun drawNow(
        c: Canvas, nowPos: Double, n: Int, axisW: Float, gap: Float, cellW: Float,
        plotTop: Float, base: Float, h: Int, sp: Float, tsp: Float,
    ) {
        val outside = nowPos < 0.0 || nowPos > n.toDouble()
        val clamped = nowPos.coerceIn(0.0, n.toDouble())
        val x = axisW + gap + (clamped * (cellW + gap)).toFloat()
        val color = if (outside) STALE_AMBER else NOW_WHITE
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 1.8f * sp }
        c.drawLine(x, plotTop, x, base, line)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.rgb(17, 24, 39); textSize = 8.5f * tsp
            isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        val label = if (outside) "stale" else "now"
        val tw = text.measureText(label)
        val hi = c.width - tw / 2 - 2f * tsp
        val cx = x.coerceAtMost(hi).coerceAtLeast(minOf(axisW + tw / 2 + 3f * tsp, hi))
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRoundRect(cx - tw / 2 - 3f * tsp, h - 11.5f * tsp, cx + tw / 2 + 3f * tsp, h - 0.5f * tsp, 3f * tsp, 3f * tsp, pill)
        c.drawText(label, cx, h - 3f * tsp, text)
    }

    /** Trimmed-down "now" marker for the hourly strip: a plain accent line, no pill — the
     *  first column is already labelled "Now" in text, so a second floating label there
     *  would just duplicate it. Still turns amber when the present has aged past the
     *  visible columns, same "this data is stale" signal as the timeseries pill. */
    private fun drawNowLine(c: Canvas, nowPos: Double, cols: Int, colW: Float, h: Int, sp: Float) {
        val outside = nowPos < 0.0 || nowPos > cols.toDouble()
        val x = (nowPos.coerceIn(0.0, cols.toDouble()) * colW).toFloat()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (outside) STALE_AMBER else NOW_WHITE; strokeWidth = 1.8f * sp; alpha = 210
        }
        c.drawLine(x, 0f, x, h.toFloat(), line)
    }

    /**
     * The display-unit axis a series shares: its range snapped to whole 5-unit steps,
     * widened to at least [minSpan] so a flat run doesn't get magnified into a mountain
     * range. Duplicate of [Graph]'s private degBounds — same 5-line pure function, not
     * worth exposing across files for.
     */
    private fun degBounds(values: List<Double>, minSpan: Double): Pair<Double, Double> {
        val lo = floor(values.min() / 5.0) * 5.0
        val hi = ceil(values.max() / 5.0) * 5.0
        if (hi - lo >= minSpan) return lo to hi
        val mid = (lo + hi) / 2.0
        return (mid - minSpan / 2) to (mid + minSpan / 2)
    }

    /** Nicely-rounded ceiling for a rain-amount axis (matches MainActivity's niceCeilIn). */
    private fun niceCeilIn(v: Double): Double = when {
        v <= 0.1 -> 0.1
        v <= 0.25 -> 0.25
        v <= 0.5 -> 0.5
        v <= 1.0 -> 1.0
        else -> ceil(v * 2) / 2
    }

    /** Inches with the app's precision: 0.2″, 0.05″. */
    private fun fmtInches(v: Double): String =
        (if (v >= 0.1) String.format("%.1f", v) else String.format("%.2f", v)) + "″"

    /** [factor]'s hue pulled hard toward [base] (the theme's closed-bar grey). */
    private fun detractorGrey(factor: Int, base: Int): Int {
        fun mix(g: Int, cch: Int) = Math.round(g * (1 - DETRACTOR_TINT) + cch * DETRACTOR_TINT)
        return Color.rgb(mix(Color.red(base), Color.red(factor)), mix(Color.green(base), Color.green(factor)), mix(Color.blue(base), Color.blue(factor)))
    }

    /**
     * Traces a series, breaking the line at a gap; a value stranded between two gaps
     * still gets a flat tick. Generalized version of [Graph]'s private drawDegLine — used
     * here for dew, AQI and rain-chance, not just the temp/dew axis.
     */
    private fun tracePoly(
        c: Canvas, values: List<Double?>, color: Int, n: Int,
        x: (Int) -> Float, y: (Double) -> Float, cellW: Float, sp: Float, nowPos: Double?,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.8f * sp
        }
        fun v(i: Int) = values.getOrNull(i)
        for (i in 0 until n) {
            val a = v(i) ?: continue
            val b = if (i + 1 < n) v(i + 1) else null
            p.color = color
            p.alpha = pastAlpha(i, nowPos)
            if (b != null) c.drawLine(x(i), y(a), x(i + 1), y(b), p)
            else if (v(i - 1) == null) c.drawLine(x(i) - cellW / 3, y(a), x(i) + cellW / 3, y(a), p)
        }
    }

    // ───────────────────────── TIMESERIES ─────────────────────────

    private fun renderTimeseries(
        c: Canvas, w: Int, h: Int, density: Float, theme: WidgetTheme, unit: TempUnit,
        threshold: Double, panel: Panel, r: Rows, nowPos: Double?,
    ) {
        val n = r.n
        if (n <= 0) return
        val sp = density
        val scale = theme.textScalePct / 100f
        val tsp = sp * scale

        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.text; textSize = 9.5f * tsp; isFakeBoldText = true; textAlign = Paint.Align.LEFT
        }
        // Only the score bars carry a left-axis label ("win"/"ac"); the line charts get a
        // slim margin instead, just enough for the "now" line to have somewhere to sit.
        val axisW = if (panel == Panel.SCORE) axis.measureText("win") + 1.5f * tsp
            else if (panel == Panel.AQI_RAIN) axis.measureText("100") + 1.5f * tsp
            else 1.5f * tsp
        val labelH = 13f * tsp
        val topPad = 2f * sp
        val base = h - labelH
        val plotTop = topPad
        val plotH = (base - plotTop).coerceAtLeast(1f)
        val gap = (1.5f * sp).coerceAtLeast(2f)
        val plotW = w - axisW
        val barW = ((plotW - gap * (n + 1)) / n).coerceAtLeast(1f)
        fun barLeft(i: Int) = axisW + gap + i * (barW + gap)
        val cellStep = barW + gap

        dayNightShade(c, n, r.isDay, axisW, cellStep, plotTop, base)

        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, Color.red(theme.text), Color.green(theme.text), Color.blue(theme.text))
            textSize = 8.5f * tsp; textAlign = Paint.Align.CENTER
        }
        val labelEvery = maxOf(1, Math.round(n / 6f))
        for (i in 0 until n) {
            if (i % labelEvery != 0) continue
            val label = if (i == 0 && nowPos == null) "now" else ForecastBuilder.fmtHour(r.times.getOrElse(i) { "" })
            c.drawText(label, barLeft(i) + barW / 2, h - 2f * sp, tick)
        }

        when (panel) {
            Panel.TEMP_DEW -> drawTempDew(c, theme, unit, r, n, ::barLeft, barW, base, plotTop, plotH, w, sp, tsp, nowPos)
            Panel.AQI_RAIN -> drawAqiRain(c, theme, r, n, ::barLeft, barW, base, plotTop, plotH, w, sp, tsp, nowPos)
            Panel.SCORE -> drawScoreBars(c, theme, threshold, r, n, ::barLeft, barW, base, plotTop, plotH, axisW, cellStep, sp, tsp, nowPos, axis)
        }

        if (nowPos != null) drawNow(c, nowPos, n, axisW, gap, barW, plotTop, base, h, sp, tsp)
    }

    /**
     * Temp + dew on one shared display-unit axis, converted to the display unit BEFORE
     * computing bounds so a Celsius chart snaps to round Celsius gridlines. Temp is drawn
     * segment-by-segment so any stretch touching ≥[HOT_F] renders red — the HOT_F
     * comparison stays in °F even though the plotted y is in the display unit, matching
     * the app's TempChart.
     */
    private fun drawTempDew(
        c: Canvas, theme: WidgetTheme, unit: TempUnit, r: Rows, n: Int,
        barLeft: (Int) -> Float, barW: Float, base: Float, plotTop: Float, plotH: Float,
        w: Int, sp: Float, tsp: Float, nowPos: Double?,
    ) {
        val tempDisp = r.tempF.map { it?.let(unit::fromF) }
        val dewDisp = r.dewF.map { it?.let(unit::fromF) }
        val vals = tempDisp.filterNotNull() + dewDisp.filterNotNull()
        if (vals.isEmpty()) return
        val (lo, hi) = degBounds(vals, unit.deltaFromF(MIN_DEG_SPAN_F))
        fun yDeg(v: Double) = base - ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) * plotH
        fun x(i: Int) = barLeft(i) + barW / 2

        // Dew first — it sits under the temp line as often as not.
        tracePoly(c, dewDisp, theme.dew, n, ::x, ::yDeg, barW, sp, nowPos)

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.8f * sp
        }
        for (i in 0 until n - 1) {
            val a = r.tempF.getOrNull(i) ?: continue
            val b = r.tempF.getOrNull(i + 1) ?: continue
            p.color = if (a >= HOT_F || b >= HOT_F) HOT_RED else theme.temp
            p.alpha = pastAlpha(i, nowPos)
            c.drawLine(x(i), yDeg(unit.fromF(a)), x(i + 1), yDeg(unit.fromF(b)), p)
        }
        // A temp reading with no neighbour on either side still gets a flat tick.
        for (i in 0 until n) {
            val t = r.tempF.getOrNull(i) ?: continue
            if (r.tempF.getOrNull(i - 1) != null || r.tempF.getOrNull(i + 1) != null) continue
            p.color = if (t >= HOT_F) HOT_RED else theme.temp
            p.alpha = pastAlpha(i, nowPos)
            c.drawLine(x(i) - barW / 3, yDeg(unit.fromF(t)), x(i) + barW / 3, yDeg(unit.fromF(t)), p)
        }

        val degLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.temp; textSize = 8f * tsp; isFakeBoldText = true; textAlign = Paint.Align.RIGHT
        }
        c.drawText("${hi.roundToInt()}°", w - 1f * sp, plotTop + 8f * sp, degLabel)
        c.drawText("${lo.roundToInt()}°", w - 1f * sp, base - 1.5f * sp, degLabel)
    }

    /**
     * AQI + rain chance share the 0-100 left axis (AQI clipped at 100), precip amount
     * rides a right ″ axis as bars behind the lines — same layout as the app's
     * CombinedChart.
     */
    private fun drawAqiRain(
        c: Canvas, theme: WidgetTheme, r: Rows, n: Int,
        barLeft: (Int) -> Float, barW: Float, base: Float, plotTop: Float, plotH: Float,
        w: Int, sp: Float, tsp: Float, nowPos: Double?,
    ) {
        fun x(i: Int) = barLeft(i) + barW / 2
        fun y(v: Double, lo: Double, hi: Double) = base - ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) * plotH

        val maxIn = r.precipIn.maxOrNull() ?: 0.0
        if (maxIn > 0) {
            val inHi = niceCeilIn(maxIn)
            val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.rain }
            for (i in 0 until n) {
                val v = r.precipIn.getOrElse(i) { 0.0 }
                if (v <= 0) continue
                val top = y(v, 0.0, inHi)
                // 40% alpha, dimmed further for an elapsed hour (102 = 0.4 * 255).
                bar.alpha = 102 * pastAlpha(i, nowPos) / 255
                c.drawRect(barLeft(i) + barW * 0.2f, top, barLeft(i) + barW * 0.8f, base, bar)
            }
            val rainLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.rain; textSize = 8f * tsp; isFakeBoldText = true; textAlign = Paint.Align.RIGHT
            }
            c.drawText(fmtInches(inHi), w - 1f * sp, plotTop + 8f * sp, rainLabel)
            c.drawText("0″", w - 1f * sp, base - 1.5f * sp, rainLabel)
        }

        tracePoly(c, r.aqi.map { it?.toDouble()?.coerceAtMost(100.0) }, AQI_GREEN, n, ::x, { v -> y(v, 0.0, 100.0) }, barW, sp, nowPos)
        tracePoly(c, r.precipProb.map { it as Double? }, RAIN_CHANCE_BLUE, n, ::x, { v -> y(v, 0.0, 100.0) }, barW, sp, nowPos)

        val leftAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.text; textSize = 9.5f * tsp; isFakeBoldText = true; textAlign = Paint.Align.LEFT
        }
        c.drawText("100", 0f, plotTop + 9f * sp, leftAxis)
        c.drawText("0", 0f, base - 1f * sp, leftAxis)
    }

    /** The score bars with open/closed colouring, exactly as [Graph.render] does it, plus
     *  the dashed open threshold from the app's FactorChart. */
    private fun drawScoreBars(
        c: Canvas, theme: WidgetTheme, threshold: Double, r: Rows, n: Int,
        barLeft: (Int) -> Float, barW: Float, base: Float, plotTop: Float, plotH: Float,
        axisW: Float, cellStep: Float, sp: Float, tsp: Float, nowPos: Double?, axis: Paint,
    ) {
        c.drawText("win", 0f, plotTop + 9f * sp, axis)
        c.drawText("ac", 0f, base - 1f * sp, axis)

        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until n) {
            val x = barLeft(i)
            val score = r.scores.getOrElse(i) { 0.0 }.coerceIn(0.0, 100.0)
            val bh = (score / 100f).toFloat() * plotH
            bar.color = if (score >= threshold) {
                theme.barOpen
            } else {
                val wf = r.worst.getOrElse(i) { -1 }
                if (wf in FACTOR_COLORS.indices) detractorGrey(FACTOR_COLORS[wf], theme.barClosed) else theme.barClosed
            }
            bar.alpha = pastAlpha(i, nowPos)
            val rad = (2f * sp).coerceAtMost(barW / 2)
            c.drawRoundRect(x, base - bh, x + barW, base, rad, rad, bar)
            if (score < 0.5) {
                val wf = r.worst.getOrElse(i) { -1 }
                if (wf in FACTOR_COLORS.indices) {
                    bar.color = FACTOR_COLORS[wf]; bar.alpha = pastAlpha(i, nowPos)
                    c.drawRect(x, base - 2.5f * sp, x + barW, base, bar)
                }
            }
            bar.alpha = 255
        }

        val thrY = base - (threshold / 100.0).toFloat().coerceIn(0f, 1f) * plotH
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, Color.red(theme.text), Color.green(theme.text), Color.blue(theme.text))
            strokeWidth = 1f * sp
            pathEffect = DashPathEffect(floatArrayOf(9f * sp, 9f * sp), 0f)
        }
        c.drawLine(axisW, thrY, axisW + n * cellStep, thrY, dash)
    }

    // ───────────────────────── HOURLY_ROW ─────────────────────────

    /**
     * A horizontal strip of per-hour columns: value, weather icon, an optional secondary
     * value, then the hour label — top to bottom, modelled on a phone weather app's
     * hourly forecast row. Columns never shrink below [minColPx]; if [Rows.n] would need
     * more than fit at that width, the extras just aren't shown (see the report for the
     * exact figure chosen).
     */
    private fun renderHourlyRow(
        context: Context, c: Canvas, w: Int, h: Int, density: Float, theme: WidgetTheme,
        unit: TempUnit, panel: Panel, r: Rows, nowPos: Double?,
    ) {
        if (r.n <= 0) return
        val sp = density
        val scale = theme.textScalePct / 100f
        val tsp = sp * scale

        // 34dp at 100% text scale, growing with the text-size slider so a cranked-up font
        // can't start overlapping neighbouring columns.
        val minColPx = 34f * tsp
        val cols = minOf(r.n, (w / minColPx).toInt().coerceAtLeast(1))
        val colW = w.toFloat() / cols

        val dayShade = Paint().apply { color = Color.argb(22, 255, 255, 255) }
        val nightShade = Paint().apply { color = Color.argb(55, 0, 0, 0) }
        for (i in 0 until cols) {
            val up = r.isDay.getOrElse(i) { true }
            c.drawRect(i * colW, 0f, (i + 1) * colW, h.toFloat(), if (up) dayShade else nightShade)
        }

        // SCORE has no secondary value (per spec), so its rows get 3 bands instead of 4 —
        // more even spacing rather than a blank row reserved for nothing.
        val hasSecondary = panel != Panel.SCORE
        val bands = if (hasSecondary) 4 else 3
        val bandH = h / bands.toFloat()
        val iconSize = minOf(bandH * 0.85f, colW * 0.75f).coerceAtLeast(12f * sp)

        val primary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * tsp; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        val secondary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.dew; textSize = 8.5f * tsp; textAlign = Paint.Align.CENTER
        }
        val hourLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, Color.red(theme.text), Color.green(theme.text), Color.blue(theme.text))
            textSize = 8.5f * tsp; textAlign = Paint.Align.CENTER
        }

        for (i in 0 until cols) {
            val cx = i * colW + colW / 2
            val alpha = pastAlpha(i, nowPos)

            val tF = r.tempF.getOrNull(i)
            val primaryText = when (panel) {
                Panel.TEMP_DEW -> tF?.let(unit::deg) ?: "–"
                Panel.AQI_RAIN -> "${r.precipProb.getOrElse(i) { 0.0 }.roundToInt()}%"
                Panel.SCORE -> r.scores.getOrElse(i) { 0.0 }.roundToInt().toString()
            }
            primary.color = if (panel == Panel.TEMP_DEW && (tF ?: 0.0) >= HOT_F) HOT_RED else theme.text
            primary.alpha = alpha
            c.drawText(primaryText, cx, bandH * 0.72f, primary)

            val glyph = wxGlyph(r.code.getOrNull(i), r.isDay.getOrElse(i) { true })
            ContextCompat.getDrawable(context, glyph.res)?.mutate()?.let { d ->
                d.setTint(glyph.tint.toArgb())
                d.alpha = alpha
                val top = (bandH + (bandH - iconSize) / 2).toInt()
                val left = (cx - iconSize / 2).toInt()
                d.setBounds(left, top, left + iconSize.toInt(), top + iconSize.toInt())
                d.draw(c)
            }

            if (hasSecondary) {
                val secText = when (panel) {
                    Panel.TEMP_DEW -> r.dewF.getOrNull(i)?.let { "dew ${unit.deg(it)}" }
                    Panel.AQI_RAIN -> r.precipIn.getOrElse(i) { 0.0 }.takeIf { it > 0 }?.let(::fmtInches)
                    Panel.SCORE -> null
                }
                secondary.alpha = alpha
                secText?.let { c.drawText(it, cx, bandH * 2 + bandH * 0.72f, secondary) }
            }

            hourLabel.alpha = alpha
            val label = if (i == 0) "Now" else ForecastBuilder.fmtHour(r.times.getOrElse(i) { "" })
            c.drawText(label, cx, h - 3f * sp, hourLabel)
        }

        if (nowPos != null) drawNowLine(c, nowPos, cols, colW, h, sp)
    }
}
