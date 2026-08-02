package com.acwindows

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Renders the next-N-hours Open-Window Score as a bar-chart bitmap for the widget
 * (RemoteViews can't draw vector graphics, so we hand it a bitmap).
 *
 * Left axis: tall = "win" (open windows), short = "ac". A dashed line marks the
 * open threshold (labelled with its value). Dark vertical dividers mark day
 * changes (with a date flag); grey ones mark each day's noon (with a "noon"
 * flag). The best contiguous window is underlined. When rain is in the forecast,
 * a blue line traces the chance of it on the same 0–100 axis (see [drawRain]).
 *
 * A bright vertical line marks the present (see [drawNow]) and hours already gone
 * are dimmed. The bars are fixed clock hours, so the marker is what keeps the chart
 * honest as the snapshot ages — it moves right between refreshes, and if a refresh
 * is badly overdue you can see the present has walked off into (or past) the bars.
 *
 * The bitmap is rendered at the ImageView's true pixel aspect (see
 * AcWindowsProvider) and shown with fitCenter, so text never stretches on resize.
 */
object Graph {

    // Per-factor baseline colours (match the app's FactorTemp/Humid/Aqi/Rain).
    private val FACTOR_COLORS = intArrayOf(
        Color.rgb(0xFB, 0x92, 0x3C),  // temp
        Color.rgb(0xA5, 0xB4, 0xFC),  // humidity
        Color.rgb(0x4A, 0xDE, 0x80),  // air quality
        Color.rgb(0x22, 0xD3, 0xEE),  // rain
    )

    // Neutral grey a below-threshold bar is pulled toward (matches the app's BarClosed), plus the
    // fraction of the detractor hue kept on top of it. Strongly grey, faintly tinted by "why closed".
    private val CLOSED_GREY = Color.rgb(0x3A, 0x43, 0x4F)
    private const val DETRACTOR_TINT = 0.28f

    /** [factor]'s hue pulled hard toward [CLOSED_GREY] — the bar reads grey but hints at its cause. */
    private fun detractorGrey(factor: Int): Int {
        fun mix(g: Int, c: Int) = Math.round(g * (1 - DETRACTOR_TINT) + c * DETRACTOR_TINT)
        return Color.rgb(
            mix(Color.red(CLOSED_GREY), Color.red(factor)),
            mix(Color.green(CLOSED_GREY), Color.green(factor)),
            mix(Color.blue(CLOSED_GREY), Color.blue(factor)),
        )
    }

    // Rain overlay: a bright sky blue (kin to the app's cyan rain factor) that stays
    // legible crossing both the mint "open" bars and the grey closed ones.
    private val RAIN_BLUE = Color.rgb(0x7D, 0xD3, 0xFC)

    /** Below this chance, with no accumulation, there's no rain worth drawing. */
    private const val RAIN_MIN_PCT = 5.0

    // The "now" marker (see [drawNow]) and how far elapsed hours fade behind it.
    private val NOW_WHITE = Color.rgb(0xFF, 0xFF, 0xFF)
    private val STALE_AMBER = Color.rgb(0xFB, 0xBF, 0x24)
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
        /**
         * Where the present falls in [scores], as a fractional bar index (see
         * `nowBarPos`). Null for a snapshot with no wall-clock anchor — an older cache —
         * in which case bar 0 is labelled "now" the way it always was.
         */
        nowPos: Double? = null,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(80)
        val h = heightPx.coerceAtLeast(60)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (scores.isEmpty()) return bmp

        val sp = density
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 9.5f * sp; isFakeBoldText = true; textAlign = Paint.Align.LEFT
        }
        // Gutter sized to exactly fit the widest axis label, so the plot sits as
        // far left as it can (just clears "win"/"ac") — no wasted left margin.
        val axisW = axis.measureText("win") + 1.5f * sp
        val labelH = 13f * sp           // bottom strip for hour labels
        val topPad = 2f * sp
        val n = scores.size
        // Reserve a top band for the date + noon flags so they sit ABOVE the bars.
        val hasDayChange = (1 until n).any { day(times, it) != day(times, it - 1) }
        val hasNoon = (0 until n).any { ForecastBuilder.hour(times.getOrElse(it) { "" }) == 12 }
        val dateBandH = if (hasDayChange || hasNoon) 13f * sp else 0f
        val base = h - labelH
        val plotTop = topPad + dateBandH
        val plotH = (base - plotTop).coerceAtLeast(1f)
        val gap = (1.5f * sp).coerceAtLeast(2f)
        val plotW = w - axisW
        val barW = ((plotW - gap * (n + 1)) / n).coerceAtLeast(1f)
        fun barLeft(i: Int) = axisW + gap + i * (barW + gap)

        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255); textSize = 8.5f * sp; textAlign = Paint.Align.CENTER
        }
        val line = Paint().apply { color = Color.argb(110, 255, 255, 255); strokeWidth = 1f * sp }

        // Day/night background: shade each hour by whether the sun is up. Daytime
        // (sunrise→sunset) gets a light wash; overnight gets a darker one, so a bar's
        // time-of-day reads at a glance. Adjacent same-state cells tile seamlessly.
        val dayShade = Paint().apply { color = Color.argb(22, 255, 255, 255) }
        val nightShade = Paint().apply { color = Color.argb(55, 0, 0, 0) }
        val cellStep = barW + gap
        for (i in 0 until n) {
            val up = isDay.getOrElse(i) { true }
            val x0 = axisW + i * cellStep
            c.drawRect(x0, plotTop, x0 + cellStep, base, if (up) dayShade else nightShade)
        }

        // Left axis labels (within the plot band).
        c.drawText("win", 0f, plotTop + 9f * sp, axis)
        c.drawText("ac", 0f, base - 1f * sp, axis)

        // Dashed threshold line + its value.
        val thY = base - (threshold / 100f).toFloat() * plotH
        var gx = axisW
        while (gx < w) { c.drawLine(gx, thY, gx + 5f * sp, thY, line); gx += 11f * sp }
        val thLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 255, 255, 255); textSize = 8f * sp; textAlign = Paint.Align.RIGHT
        }
        c.drawText(threshold.toInt().toString(), w - 1f * sp, thY - 1.5f * sp, thLabel)

        val labelEvery = maxOf(1, Math.round(n / 6f))

        for (i in 0 until n) {
            val x = barLeft(i)
            val s = scores[i].coerceIn(0.0, 100.0)
            val bh = (s / 100f).toFloat() * plotH
            // Open hours stay the bright mint; closed hours become a desaturated grey tinted by
            // their dominant detractor, so it's obvious at a glance which bars aren't "open".
            bar.color = if (s >= threshold) {
                Color.rgb(232, 245, 233)
            } else {
                val wf = worst.getOrElse(i) { -1 }
                if (wf in FACTOR_COLORS.indices) detractorGrey(FACTOR_COLORS[wf]) else CLOSED_GREY
            }
            // An hour that has already elapsed is history, not forecast — fade it so the
            // eye starts at the present rather than at the left edge.
            bar.alpha = if (nowPos != null && i + 1 <= nowPos) PAST_ALPHA else 255
            val r = (2f * sp).coerceAtMost(barW / 2)
            c.drawRoundRect(x, base - bh, x + barW, base, r, r, bar)
            // When the score bottoms out (~0), mark the baseline with the dominant
            // detracting factor's colour — same idea as the in-app sparkline.
            if (s < 0.5) {
                val wf = worst.getOrElse(i) { -1 }
                if (wf in FACTOR_COLORS.indices) {
                    bar.color = FACTOR_COLORS[wf]   // resets alpha, so re-apply the fade
                    bar.alpha = if (nowPos != null && i + 1 <= nowPos) PAST_ALPHA else 255
                    c.drawRect(x, base - 2.5f * sp, x + barW, base, bar)
                }
            }
            bar.alpha = 255
            if (i % labelEvery == 0) {
                // Without an anchor there's nothing better to go on than the old assumption
                // that the series starts at the present; with one, [drawNow] labels it.
                val label = if (i == 0 && nowPos == null) "now"
                    else ForecastBuilder.fmtHour(times.getOrElse(i) { "" })
                c.drawText(label, x + barW / 2, h - 2f * sp, tick)
            }
        }

        // Rain rides on top of the bars, under the day/noon markers.
        drawRain(
            c, precipProb, precipIn, n, { i -> barLeft(i) },
            barW, base, plotTop, plotH, axisW, w, sp,
        )

        // Shared label paint for the date + noon flags (white on a coloured pill).
        val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 8.5f * sp; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }

        // Noon markers: a solid grey divider through the bars + a "noon" flag in
        // the top band, mirroring the date flags so midday is easy to spot.
        val noonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 124, 135); strokeWidth = 1.7f * sp }
        for (i in 0 until n) {
            if (ForecastBuilder.hour(times.getOrElse(i) { "" }) != 12) continue
            val nx = barLeft(i) - gap / 2
            c.drawLine(nx, plotTop, nx, base, noonPaint)
            flag(c, "noon", nx, noonPaint, pillText, topPad, axisW, w, sp)
        }

        // Day changes: a dark divider through the bars (a light line vanishes
        // behind a 100-score bar), with the date tag in the reserved band ABOVE.
        val divider = Paint().apply { color = Color.argb(225, 17, 24, 39); strokeWidth = 1.6f * sp }
        val datePill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 17, 24, 39) }
        for (i in 1 until n) {
            if (day(times, i) == day(times, i - 1)) continue
            val dx = barLeft(i) - gap / 2
            var yy = plotTop
            while (yy < base) { c.drawLine(dx, yy, dx, (yy + 4f * sp).coerceAtMost(base), divider); yy += 7f * sp }
            flag(c, dayLabel(times[i]), dx, datePill, pillText, topPad, axisW, w, sp)
        }

        // Underline the best window (if it falls within the shown range).
        val bi = times.indexOf(bestStart)
        val bj = times.indexOf(bestEnd)
        if (bi in 0 until n && bj in 0 until n && bj >= bi) {
            val u = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(232, 245, 233); strokeWidth = 2.5f * sp
            }
            c.drawLine(barLeft(bi), base + 1.5f * sp, barLeft(bj) + barW, base + 1.5f * sp, u)
        }

        // Last, so it sits above the bars, the rain line and every flag: this is the one
        // mark that says which of these hours you're actually in.
        if (nowPos != null) {
            drawNow(c, nowPos, n, axisW, gap, barW, plotTop, base, h, sp)
        }
        return bmp
    }

    /**
     * The "now" marker: a bright vertical line at the present, tagged in the hour-label
     * strip below. When the present has fallen outside the series — the snapshot is old
     * enough that these bars no longer cover it — the marker pins to the nearest edge and
     * turns amber, so a stale widget looks wrong rather than merely being wrong.
     */
    private fun drawNow(
        c: Canvas, nowPos: Double, n: Int, axisW: Float, gap: Float, barW: Float,
        plotTop: Float, base: Float, h: Int, sp: Float,
    ) {
        val outside = nowPos < 0.0 || nowPos > n.toDouble()
        val clamped = nowPos.coerceIn(0.0, n.toDouble())
        val x = axisW + gap + (clamped * (barW + gap)).toFloat()
        val color = if (outside) STALE_AMBER else NOW_WHITE

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = 1.8f * sp
        }
        c.drawLine(x, plotTop, x, base, line)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.rgb(17, 24, 39); textSize = 8.5f * sp
            isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        val label = if (outside) "stale" else "now"
        val tw = text.measureText(label)
        // Same on-screen clamp as flag(): never coerceIn with min > max on a narrow bitmap.
        val hi = c.width - tw / 2 - 2f * sp
        val cx = x.coerceAtMost(hi).coerceAtLeast(minOf(axisW + tw / 2 + 3f * sp, hi))
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRoundRect(
            cx - tw / 2 - 3f * sp, h - 11.5f * sp, cx + tw / 2 + 3f * sp, h - 0.5f * sp,
            3f * sp, 3f * sp, pill,
        )
        c.drawText(label, cx, h - 3f * sp, text)
    }

    /**
     * Traces the chance of rain as a blue line on the SAME 0–100 axis as the bars
     * (100% chance sits where a 100-score bar tops out), so rain reads at a glance
     * without a second chart. Only hours that actually carry a chance are drawn, so
     * a dry forecast leaves the graph untouched. Thickness follows the hourly rain
     * RATE ([rainStrokeDp]), and each run of wet hours is labelled with the total
     * inches it adds up to.
     */
    private fun drawRain(
        c: Canvas, prob: List<Double>, inches: List<Double>, n: Int,
        barLeft: (Int) -> Float, barW: Float, base: Float, plotTop: Float, plotH: Float,
        axisW: Float, w: Int, sp: Float,
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
            color = RAIN_BLUE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        for (i in 0 until n) {
            if (!wet[i]) continue
            if (i + 1 < n && wet[i + 1]) {
                // A segment spans two hours: take the wetter one, so a downpour never
                // hides behind the drizzle beside it.
                rain.strokeWidth = rainStrokeDp(maxOf(rate(i), rate(i + 1))) * sp
                c.drawLine(x(i), y(i), x(i + 1), y(i + 1), rain)
            } else if (i == 0 || !wet[i - 1]) {
                // A lone wet hour has no segment to sit on — give it a flat tick. (The
                // last hour of a longer run is already covered by the segment into it.)
                rain.strokeWidth = rainStrokeDp(rate(i)) * sp
                c.drawLine(x(i) - barW / 3, y(i), x(i) + barW / 3, y(i), rain)
            }
        }

        // Label each run of wet hours with its total, on a dark pill so it stays
        // readable over a bright bar. Runs too close together drop their label rather
        // than overprinting.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RAIN_BLUE; textSize = 8.5f * sp; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 17, 24, 39) }
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
                val hi = w - tw / 2 - 2f * sp
                val cx = x(peak).coerceAtMost(hi).coerceAtLeast(minOf(axisW + tw / 2, hi))
                // Sits above the line, or below it when the run peaks near the top.
                var ly = y(peak) - 4.5f * sp
                if (ly < plotTop + 9.5f * sp) ly = y(peak) + 12f * sp
                ly = ly.coerceAtMost(base - 1.5f * sp)
                if (cx - tw / 2 > lastRight) {
                    c.drawRoundRect(
                        cx - tw / 2 - 3f * sp, ly - 8.5f * sp, cx + tw / 2 + 3f * sp, ly + 2.5f * sp,
                        3f * sp, 3f * sp, pill,
                    )
                    c.drawText(label, cx, ly, text)
                    lastRight = cx + tw / 2 + 3f * sp
                }
            }
            i = j
        }
    }

    /** Draws a small rounded label "flag" in the top band, just right of [atX], clamped on-screen. */
    private fun flag(
        c: Canvas, label: String, atX: Float, pill: Paint, text: Paint,
        topPad: Float, axisW: Float, w: Int, sp: Float,
    ) {
        val tw = text.measureText(label)
        // Keep the pill on-screen without risking coerceIn(min>max) on a narrow bitmap.
        val hi = w - tw / 2 - 2f * sp
        val cx = (atX + tw / 2 + 3f * sp).coerceAtMost(hi).coerceAtLeast(minOf(axisW + tw / 2, hi))
        c.drawRoundRect(cx - tw / 2 - 3f * sp, topPad, cx + tw / 2 + 3f * sp, topPad + 12f * sp, 3f * sp, 3f * sp, pill)
        c.drawText(label, cx, topPad + 9f * sp, text)
    }

    private fun day(times: List<String>, i: Int): String = times.getOrElse(i) { "" }.take(10)

    /** "2026-06-16T00:00" -> "6/16". */
    private fun dayLabel(iso: String): String {
        val d = iso.take(10).split("-")
        return if (d.size == 3) "${d[1].toIntOrNull() ?: d[1]}/${d[2].toIntOrNull() ?: d[2]}" else ""
    }
}
