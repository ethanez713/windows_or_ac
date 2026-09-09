package com.acwindows

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Renders the next-N-hours Open-Window Score as a bar-chart bitmap for the widget
 * (RemoteViews can't draw vector graphics, so we hand it a bitmap).
 *
 * Left axis: tall = "win" (open windows), short = "ac". Dark vertical dividers mark
 * day changes (with a date flag); grey ones mark each day's noon (with a "noon"
 * flag). The best contiguous window is underlined. When rain is in the forecast,
 * a blue line traces the chance of it on the same 0–100 axis (see [drawRain]), and
 * temperature / dew point ride on their own shared axis (see [drawDegLine]).
 *
 * Which of those layers is drawn is up to the user — every one of bars, temp, dew
 * and rain can be switched off in the widget's settings, so the same chart can be a
 * score chart, a thermometer, or both. The layers that stay are the ones that place
 * an hour in time: day/night shading, the date and noon flags, and "now".
 *
 * A bright vertical line marks the present (see [drawNow]) and hours already gone
 * are dimmed. The bars are fixed clock hours, so the marker is what keeps the chart
 * honest as the snapshot ages — it moves right between refreshes, and if a refresh
 * is badly overdue you can see the present has walked off into (or past) the bars.
 *
 * Colours come from the design system's §0.1/§0.3 tokens for the active light/dark
 * theme; a per-element colour stored in [WidgetTheme] overrides its token, which is how
 * a user keeps a palette they chose themselves. The temp/dew/rain axis is drawn in
 * [TempUnit]'s display unit, but the data handed in is always raw °F — see the `unit` param.
 *
 * The bitmap is rendered at the ImageView's true pixel aspect (see
 * AcWindowsProvider) and shown with fitCenter, so text never stretches on resize.
 */
object Graph {

    /**
     * Per-hour detractor hues, from the design system (§0.1) rather than a local copy —
     * a closed bar's tint has to name the same factor the app's drawer does.
     */
    private fun factorColors(d: Tokens.Data) = intArrayOf(d.temp, d.dew, d.aqi, d.rain)

    /** Below this chance, with no accumulation, there's no rain worth drawing. */
    private const val RAIN_MIN_PCT = 5.0

    /**
     * Narrowest the shared temp/dew axis is allowed to get, in °F — converted to the
     * display unit via [TempUnit.deltaFromF] before use (see [degBounds]).
     */
    private const val MIN_DEG_SPAN_F = 10.0

    /** How far elapsed hours fade behind the now-marker (see [drawNow]). */
    private const val PAST_ALPHA = 90

    /**
     * Line thickness in dp for an hour's rain rate (inches in that hour): a hairline
     * for a trace, up to 4dp for an inch-plus downpour.
     */
    private fun rainStrokeDp(inchesPerHour: Double): Float = when {
        inchesPerHour >= 1.0 -> 4f
        inchesPerHour >= 0.33 -> 3f
        inchesPerHour >= 0.05 -> 2f
        else -> 1f
    }

    /** Inches with the app's precision: 0.2″, 0.05″. */
    private fun fmtInches(v: Double): String =
        (if (v >= 0.1) String.format("%.1f", v) else String.format("%.2f", v)) + "″"

    fun render(
        scores: List<Double>,
        times: List<String>,
        worst: List<Int>,
        isDay: List<Boolean>,
        precipProb: List<Double>,
        precipIn: List<Double>,
        bestStart: String?,
        bestEnd: String?,
        threshold: Double,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        /** Per-hour °F for the overlay lines; a null hour breaks the line rather than plotting 0. */
        tempF: List<Double?> = emptyList(),
        dewF: List<Double?> = emptyList(),
        // Which layers the user wants drawn (widget settings).
        showBars: Boolean = true,
        showTemp: Boolean = true,
        showDew: Boolean = true,
        showRain: Boolean = true,
        showThreshold: Boolean = true,
        /**
         * Where the present falls in [scores], as a fractional bar index (see
         * `nowBarPos`). Null for a snapshot with no wall-clock anchor — an older cache —
         * in which case bar 0 is labelled "now" the way it always was.
         */
        nowPos: Double? = null,
        /** Widget colours + text scale (see [WidgetTheme]). */
        theme: WidgetTheme = WidgetTheme.DEFAULT,
        /** Display unit for the temp/dew axis; the data handed in is always raw °F. */
        unit: TempUnit = TempUnit.F,
        /** Which step of the §0.1 palette to draw — the host widget follows the system. */
        dark: Boolean = true,
        /** IBM Plex Mono, for every number and axis label on this chart (§0.4). */
        mono: Typeface? = null,
    ): Bitmap {
        val d = Tokens.data(dark)
        val chrome = Tokens.chrome(dark)
        // [WidgetTheme] arrives resolved: each element is the user's colour if they chose
        // one, otherwise the §0.1/§0.3 token for the theme the phone is in.
        val barOpenColor = theme.barOpen
        val barClosedColor = theme.barClosed
        val tempColor = theme.temp
        val dewColor = theme.dew
        val rainColor = theme.rain
        val inkColor = theme.text
        val headingInk = chrome.heading
        // A contrasting outline drawn under the rain line and the noon divider so they read
        // over the vivid open bars (a thin blue line and a grey line both vanish into the
        // green). Matched to the bars' own luminance — dark in dark theme, light in light —
        // so the casing separates the mark from the bar in either theme.
        val casing = if (dark) 0x99000000.toInt() else 0x99FFFFFF.toInt()

        val w = widthPx.coerceAtLeast(80)
        val h = heightPx.coerceAtLeast(60)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (scores.isEmpty()) return bmp

        val sp = density
        // Text-scaled unit: every text size and every reserved band whose size follows
        // text (axisW, labelH, dateBandH, pill heights) is built from this instead of
        // sp, so cranking the text-size slider doesn't overflow its own band. Anything
        // NOT text-derived — bar corners, stroke widths, gaps — stays on plain sp.
        val scale = theme.textScalePct / 100f
        val tsp = sp * scale
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor; textSize = 9.5f * tsp; typeface = mono; textAlign = Paint.Align.LEFT
        }
        // Gutter sized to exactly fit the widest axis label, so the plot sits as
        // far left as it can (just clears "win"/"ac") — no wasted left margin. With the
        // bars off there is no "win"/"ac" to clear, so the plot gets the whole width.
        val axisW = if (showBars) axis.measureText("win") + 1.5f * tsp else 1.5f * tsp
        val labelH = 13f * tsp           // bottom strip for hour labels
        val topPad = 2f * sp
        val n = scores.size
        // Reserve a top band for the date + noon flags so they sit ABOVE the bars.
        val hasDayChange = (1 until n).any { day(times, it) != day(times, it - 1) }
        val hasNoon = (0 until n).any { ForecastBuilder.hour(times.getOrElse(it) { "" }) == 12 }
        val dateBandH = if (hasDayChange || hasNoon) 13f * tsp else 0f
        val base = h - labelH
        val plotTop = topPad + dateBandH
        val plotH = (base - plotTop).coerceAtLeast(1f)
        val gap = (1.5f * sp).coerceAtLeast(2f)
        val plotW = w - axisW
        val barW = ((plotW - gap * (n + 1)) / n).coerceAtLeast(1f)
        fun barLeft(i: Int) = axisW + gap + i * (barW + gap)

        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.faint; textSize = 8.5f * tsp; typeface = mono; textAlign = Paint.Align.CENTER
        }

        // Day/night background: one 6% ink lift, on the night half in light and the day
        // half in dark (§0.3). The same tint darkens paper and lightens dark ground, so
        // tinting a fixed half would make night read brighter than day in one of the two
        // themes. Adjacent same-state cells tile seamlessly.
        val bandShade = Paint().apply { color = chrome.bandTint }
        val cellStep = barW + gap
        for (i in 0 until n) {
            val up = isDay.getOrElse(i) { true }
            if (up == chrome.bandOnNight) continue
            val x0 = axisW + i * cellStep
            c.drawRect(x0, plotTop, x0 + cellStep, base, bandShade)
        }

        // Left axis labels (within the plot band) — they name the bars' 0–100 axis, so
        // they go when the bars do.
        if (showBars) {
            c.drawText("win", 0f, plotTop + 9f * sp, axis)
            c.drawText("ac", 0f, base - 1f * sp, axis)
        }

        // The line the whole chart is read against: above it the windows beat the AC.
        // Dashed and labelled in the gutter so a bar's height is a value, not a guess.
        if (showBars && showThreshold) {
            val ty = base - (threshold.coerceIn(0.0, 100.0) / 100f).toFloat() * plotH
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = d.cutoffRule; strokeWidth = 1f * sp
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * sp, 4f * sp), 0f)
            }
            c.drawLine(axisW, ty, w.toFloat(), ty, rule)
            val thresholdLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = chrome.faint; textSize = 8f * tsp; typeface = mono; textAlign = Paint.Align.LEFT
            }
            c.drawText(threshold.roundToInt().toString(), 0f, ty - 1.5f * sp, thresholdLabel)
        }

        val labelEvery = maxOf(1, Math.round(n / 6f))

        for (i in 0 until n) {
            val x = barLeft(i)
            if (showBars) {
                val s = scores[i].coerceIn(0.0, 100.0)
                val bh = (s / 100f).toFloat() * plotH
                // Open hours stay the (themeable) bright colour; closed hours become a
                // desaturated grey tinted by their dominant detractor, so it's obvious at a
                // glance which bars aren't "open".
                val hues = factorColors(d)
                bar.color = if (s >= threshold) {
                    barOpenColor
                } else {
                    val wf = worst.getOrElse(i) { -1 }
                    if (wf in hues.indices) Tokens.lerpLinear(barClosedColor, hues[wf], 0.38f) else barClosedColor
                }
                // An hour that has already elapsed is history, not forecast — fade it so the
                // eye starts at the present rather than at the left edge.
                bar.alpha = pastAlpha(i, nowPos)
                val r = (2f * sp).coerceAtMost(barW / 2)
                c.drawRoundRect(x, base - bh, x + barW, base, r, r, bar)
                // When the score bottoms out (~0), mark the baseline with the dominant
                // detracting factor's colour — same idea as the in-app sparkline.
                if (s < 0.5) {
                    val wf = worst.getOrElse(i) { -1 }
                    if (wf in hues.indices) {
                        bar.color = hues[wf]            // resets alpha, so re-apply the fade
                        bar.alpha = pastAlpha(i, nowPos)
                        c.drawRect(x, base - 2.5f * sp, x + barW, base, bar)
                    }
                }
                bar.alpha = 255
            }
            if (i % labelEvery == 0) {
                // Without an anchor there's nothing better to go on than the old assumption
                // that the series starts at the present; with one, [drawNow] labels it.
                val label = if (i == 0 && nowPos == null) "now"
                    else ForecastBuilder.fmtHour(times.getOrElse(i) { "" })
                c.drawText(label, x + barW / 2, h - 2f * sp, tick)
            }
        }

        // Temperature and dew point share ONE auto-scaled display-unit axis (they're the
        // same quantity, and the gap between them IS the muggyness) — which is a
        // different axis from the bars' 0–100. Only the visible ones set the range, so
        // hiding the dew line lets the temp line use the full height. Converted to the
        // display unit BEFORE computing bounds, so the axis snaps to round numbers in
        // whatever unit is showing rather than round °F numbers turned into ugly °C.
        val tempDisp = tempF.map { it?.let(unit::fromF) }
        val dewDisp = dewF.map { it?.let(unit::fromF) }
        val degVals = (if (showTemp) tempDisp.take(n).filterNotNull() else emptyList()) +
            (if (showDew) dewDisp.take(n).filterNotNull() else emptyList())
        if (degVals.isNotEmpty()) {
            val (lo, hi) = degBounds(degVals, unit.deltaFromF(MIN_DEG_SPAN_F))
            val yDeg = { v: Double -> base - (((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)) * plotH }
            // Dew first: it sits under the temp line as often as not, and temp is the
            // headline series.
            if (showDew) drawDegLine(c, dewDisp, dewColor, n, { i -> barLeft(i) + barW / 2 }, yDeg, barW, sp, nowPos)
            if (showTemp) drawDegLine(c, tempDisp, tempColor, n, { i -> barLeft(i) + barW / 2 }, yDeg, barW, sp, nowPos)
            // Range labels on the right edge, tinted to whichever line owns the axis, so
            // the numbers can't be read as belonging to the bars' scale.
            val degLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (showTemp) tempColor else dewColor
                textSize = 8f * tsp; typeface = mono; textAlign = Paint.Align.RIGHT
            }
            c.drawText("${hi.roundToInt()}°", w - 1f * sp, plotTop + 8f * sp, degLabel)
            c.drawText("${lo.roundToInt()}°", w - 1f * sp, base - 1.5f * sp, degLabel)
        }

        // Rain rides on top of the bars, under the day/noon markers.
        if (showRain) {
            drawRain(
                c, precipProb, precipIn, n, { i -> barLeft(i) },
                barW, base, plotTop, plotH, axisW, w, sp, tsp, rainColor, casing, chrome.card, mono,
            )
        }

        // Shared label paint for the date + noon flags (white-on-theme text on a coloured pill).
        val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.card; textSize = 8.5f * tsp; typeface = mono; textAlign = Paint.Align.CENTER
        }

        // Noon markers: a solid grey divider through the bars + a "noon" flag in
        // the top band, mirroring the date flags so midday is easy to spot. The divider
        // and pill background are structural, not themed.
        val noonCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = casing; strokeWidth = 3.4f * sp }
        val noonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = chrome.muted; strokeWidth = 1.7f * sp }
        // The pill takes the dark heading fill the date pills use, so "noon" reads on a
        // bright bar; the label tells it apart from a date flag.
        val noonPill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = headingInk }
        for (i in 0 until n) {
            if (ForecastBuilder.hour(times.getOrElse(i) { "" }) != 12) continue
            val nx = barLeft(i) - gap / 2
            c.drawLine(nx, plotTop, nx, base, noonCasing)
            c.drawLine(nx, plotTop, nx, base, noonPaint)
            flag(c, "noon", nx, noonPill, pillText, topPad, axisW, w, tsp)
        }

        // Day changes: a dark divider through the bars (a light line vanishes
        // behind a 100-score bar), with the date tag in the reserved band ABOVE.
        val divider = Paint().apply { color = headingInk; strokeWidth = 1.6f * sp }
        val datePill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = headingInk }
        for (i in 1 until n) {
            if (day(times, i) == day(times, i - 1)) continue
            val dx = barLeft(i) - gap / 2
            var yy = plotTop
            while (yy < base) { c.drawLine(dx, yy, dx, (yy + 4f * sp).coerceAtMost(base), divider); yy += 7f * sp }
            flag(c, dayLabel(times[i]), dx, datePill, pillText, topPad, axisW, w, tsp)
        }

        // Underline the best window (if it falls within the shown range). It annotates the
        // score series, so it follows the bars off the chart.
        val bi = times.indexOf(bestStart)
        val bj = times.indexOf(bestEnd)
        if (showBars && bi in 0 until n && bj in 0 until n && bj >= bi) {
            val u = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = barOpenColor; strokeWidth = 2.5f * sp
            }
            c.drawLine(barLeft(bi), base + 1.5f * sp, barLeft(bj) + barW, base + 1.5f * sp, u)
        }

        // Last, so it sits above the bars, the rain line and every flag: this is the one
        // mark that says which of these hours you're actually in.
        if (nowPos != null) {
            drawNow(c, nowPos, n, axisW, gap, barW, plotTop, base, h, sp, tsp, d, chrome, mono)
        }
        return bmp
    }

    /** Hour [i] is history once "now" has passed its right edge — draw it faded. */
    private fun pastAlpha(i: Int, nowPos: Double?): Int =
        if (nowPos != null && i + 1 <= nowPos) PAST_ALPHA else 255

    /**
     * The display-unit axis the temp and dew lines share: the data's range snapped out
     * to whole 5-unit steps, widened to at least [minSpan] so a flat overnight doesn't
     * get magnified into a mountain range by an axis two degrees tall. [values] and
     * [minSpan] are both already in the display unit — see the `unit.fromF` conversion
     * at the call site.
     */
    private fun degBounds(values: List<Double>, minSpan: Double): Pair<Double, Double> {
        val lo = floor(values.min() / 5.0) * 5.0
        val hi = ceil(values.max() / 5.0) * 5.0
        if (hi - lo >= minSpan) return lo to hi
        val mid = (lo + hi) / 2.0
        return (mid - minSpan / 2) to (mid + minSpan / 2)
    }

    /**
     * Traces an hourly display-unit series on the shared temperature axis. Hours the
     * forecast didn't supply break the line instead of dropping it to the floor, and an
     * hour left stranded between two gaps gets a flat tick so it still shows up. Elapsed
     * hours fade exactly as the bars do, so the whole chart ages together.
     */
    private fun drawDegLine(
        c: Canvas, values: List<Double?>, color: Int, n: Int,
        x: (Int) -> Float, y: (Double) -> Float, barW: Float, sp: Float, nowPos: Double?,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.8f * sp
        }
        fun v(i: Int) = values.getOrNull(i)
        for (i in 0 until n) {
            val a = v(i) ?: continue
            val b = if (i + 1 < n) v(i + 1) else null
            p.color = color                     // setting alpha alone survives; colour resets it
            p.alpha = pastAlpha(i, nowPos)
            if (b != null) {
                c.drawLine(x(i), y(a), x(i + 1), y(b), p)
            } else if (v(i - 1) == null) {
                c.drawLine(x(i) - barW / 3, y(a), x(i) + barW / 3, y(a), p)
            }
        }
    }

    /**
     * The "now" marker: a vertical line at the present, tagged in the hour-label strip
     * below. When the present has fallen outside the series — the snapshot is old enough
     * that these bars no longer cover it — the marker pins to the nearest edge and takes
     * the now-marker hue, so a stale widget looks wrong rather than merely being wrong.
     */
    private fun drawNow(
        c: Canvas, nowPos: Double, n: Int, axisW: Float, gap: Float, barW: Float,
        plotTop: Float, base: Float, h: Int, sp: Float, tsp: Float,
        d: Tokens.Data, chrome: Tokens.Chrome, mono: Typeface?,
    ) {
        val outside = nowPos < 0.0 || nowPos > n.toDouble()
        val clamped = nowPos.coerceIn(0.0, n.toDouble())
        val x = axisW + gap + (clamped * (barW + gap)).toFloat()
        val color = if (outside) d.now else chrome.heading

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = 1.8f * sp
        }
        c.drawLine(x, plotTop, x, base, line)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = chrome.card; textSize = 8.5f * tsp
            typeface = mono; textAlign = Paint.Align.CENTER
        }
        val label = if (outside) "stale" else "now"
        val tw = text.measureText(label)
        // Same on-screen clamp as flag(): never coerceIn with min > max on a narrow bitmap.
        val hi = c.width - tw / 2 - 2f * tsp
        val cx = x.coerceAtMost(hi).coerceAtLeast(minOf(axisW + tw / 2 + 3f * tsp, hi))
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRoundRect(
            cx - tw / 2 - 3f * tsp, h - 11.5f * tsp, cx + tw / 2 + 3f * tsp, h - 0.5f * tsp,
            3f * tsp, 3f * tsp, pill,
        )
        c.drawText(label, cx, h - 3f * tsp, text)
    }

    /**
     * Traces the chance of rain as a line on the SAME 0–100 axis as the bars (100%
     * chance sits where a 100-score bar tops out), so rain reads at a glance without a
     * second chart. Only hours that actually carry a chance are drawn, so a dry forecast
     * leaves the graph untouched. Thickness follows the hourly rain RATE
     * ([rainStrokeDp], not text-scaled — it's a data line, not a label), and each run of
     * wet hours is labelled with the total inches it adds up to.
     */
    private fun drawRain(
        c: Canvas, prob: List<Double>, inches: List<Double>, n: Int,
        barLeft: (Int) -> Float, barW: Float, base: Float, plotTop: Float, plotH: Float,
        axisW: Float, w: Int, sp: Float, tsp: Float, rainColor: Int, casing: Int, pillInk: Int, mono: Typeface?,
    ) {
        fun p(i: Int) = prob.getOrElse(i) { 0.0 }
        fun rate(i: Int) = inches.getOrElse(i) { 0.0 }
        // Accumulation counts as "wet" even at a low stated chance — if the forecast
        // says inches will fall, the line should be there.
        val wet = (0 until n).map { p(it) >= RAIN_MIN_PCT || rate(it) > 0.0 }
        if (wet.none { it }) return

        fun x(i: Int) = barLeft(i) + barW / 2
        fun y(i: Int) = base - (p(i).coerceIn(0.0, 100.0) / 100.0).toFloat() * plotH

        val rain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = rainColor; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        // Outline drawn under the line at the same points but wider, so the blue reads over
        // the open bars instead of blending into the green.
        val rainCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = casing; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, width: Float) {
            rainCasing.strokeWidth = width + 1.6f * sp
            c.drawLine(x0, y0, x1, y1, rainCasing)
            rain.strokeWidth = width
            c.drawLine(x0, y0, x1, y1, rain)
        }
        for (i in 0 until n) {
            if (!wet[i]) continue
            if (i + 1 < n && wet[i + 1]) {
                // A segment spans two hours: take the wetter one, so a downpour never
                // hides behind the drizzle beside it.
                stroke(x(i), y(i), x(i + 1), y(i + 1), rainStrokeDp(maxOf(rate(i), rate(i + 1))) * sp)
            } else if (i == 0 || !wet[i - 1]) {
                // A lone wet hour has no segment to sit on — give it a flat tick. (The
                // last hour of a longer run is already covered by the segment into it.)
                stroke(x(i) - barW / 3, y(i), x(i) + barW / 3, y(i), rainStrokeDp(rate(i)) * sp)
            }
        }

        // Label each run of wet hours with its total, on a dark pill so it stays
        // readable over a bright bar. Runs too close together drop their label rather
        // than overprinting.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = rainColor; textSize = 8.5f * tsp; typeface = mono; textAlign = Paint.Align.CENTER
        }
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pillInk }
        var lastRight = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < n) {
            if (!wet[i]) { i++; continue }
            var j = i
            var total = 0.0
            var peak = i
            while (j < n && wet[j]) {
                total += rate(j)
                if (p(j) > p(peak)) peak = j
                j++
            }
            if (total >= 0.01) {
                val label = fmtInches(total)
                val tw = text.measureText(label)
                // Clamp on-screen the same way flag() does — never coerceIn(min>max).
                val hi = w - tw / 2 - 2f * tsp
                val cx = x(peak).coerceAtMost(hi).coerceAtLeast(minOf(axisW + tw / 2, hi))
                // Sits above the line, or below it when the run peaks near the top.
                var ly = y(peak) - 4.5f * tsp
                if (ly < plotTop + 9.5f * tsp) ly = y(peak) + 12f * tsp
                ly = ly.coerceAtMost(base - 1.5f * tsp)
                if (cx - tw / 2 > lastRight) {
                    c.drawRoundRect(
                        cx - tw / 2 - 3f * tsp, ly - 8.5f * tsp, cx + tw / 2 + 3f * tsp, ly + 2.5f * tsp,
                        3f * tsp, 3f * tsp, pill,
                    )
                    c.drawText(label, cx, ly, text)
                    lastRight = cx + tw / 2 + 3f * tsp
                }
            }
            i = j
        }
    }

    /** Draws a small rounded label "flag" in the top band, just right of [atX], clamped on-screen. */
    private fun flag(
        c: Canvas, label: String, atX: Float, pill: Paint, text: Paint,
        topPad: Float, axisW: Float, w: Int, tsp: Float,
    ) {
        val tw = text.measureText(label)
        // Keep the pill on-screen without risking coerceIn(min>max) on a narrow bitmap.
        val hi = w - tw / 2 - 2f * tsp
        val cx = (atX + tw / 2 + 3f * tsp).coerceAtMost(hi).coerceAtLeast(minOf(axisW + tw / 2, hi))
        c.drawRoundRect(cx - tw / 2 - 3f * tsp, topPad, cx + tw / 2 + 3f * tsp, topPad + 12f * tsp, 3f * tsp, 3f * tsp, pill)
        c.drawText(label, cx, topPad + 9f * tsp, text)
    }

    private fun day(times: List<String>, i: Int): String = times.getOrElse(i) { "" }.take(10)

    /** "2026-06-16T00:00" -> "6/16". */
    private fun dayLabel(iso: String): String {
        val d = iso.take(10).split("-")
        return if (d.size == 3) "${d[1].toIntOrNull() ?: d[1]}/${d[2].toIntOrNull() ?: d[2]}" else ""
    }
}
