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
 * flag). The best contiguous window is underlined.
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

    fun render(
        scores: List<Double>,
        times: List<String>,
        worst: List<Int>,
        isDay: List<Boolean>,
        bestStart: String?,
        bestEnd: String?,
        threshold: Double,
        widthPx: Int,
        heightPx: Int,
        density: Float,
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
            val r = (2f * sp).coerceAtMost(barW / 2)
            c.drawRoundRect(x, base - bh, x + barW, base, r, r, bar)
            // When the score bottoms out (~0), mark the baseline with the dominant
            // detracting factor's colour — same idea as the in-app sparkline.
            if (s < 0.5) {
                val wf = worst.getOrElse(i) { -1 }
                if (wf in FACTOR_COLORS.indices) {
                    bar.color = FACTOR_COLORS[wf]
                    c.drawRect(x, base - 2.5f * sp, x + barW, base, bar)
                }
            }
            if (i == 0 || i % labelEvery == 0) {
                val label = if (i == 0) "now" else ForecastBuilder.fmtHour(times.getOrElse(i) { "" })
                c.drawText(label, x + barW / 2, h - 2f * sp, tick)
            }
        }

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
        return bmp
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
