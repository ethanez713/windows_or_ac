package com.acwindows

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The forecast widget's single panel (docs/ux-redesign.md §C): one question — what will the
 * weather do in the next N hours — answered by four readings layered into one chart.
 *
 * The layering is the whole design. At 186dp there is no room to stack four panels, so each
 * reading takes a different visual register instead: temperature is a solid line, dew point
 * a dashed line, rain columns, air quality a gradient strip. Nothing competes for the same
 * shape, so nothing has to be identified by a legend.
 *
 * Drawn to a Bitmap because RemoteViews cannot render vectors.
 */
object ForecastPanel {

    /** Chart geometry, in dp, from §C.1's content box. */
    private const val HEADER_H = 40f
    private const val HEADER_GAP = 3f
    private const val PLOT_LEFT = 30f
    /** The gradient strips share the score bar's height; the halftone cloud strip is taller,
     *  since the dot screen needs the height to read. */
    private const val STRIP_H = 5f
    private const val AQI_H = STRIP_H
    private const val SCORE_H = STRIP_H
    private const val UV_H = STRIP_H
    private const val CLOUD_H = 8f

    /** Gap above each strip — from the plot for the first, from the strip above for the rest. */
    private const val STRIP_GAP = 2f

    /** The below-plot strips, top to bottom, each reserving a band of [bandH] at the panel foot. */
    private enum class Strip(val h: Float) { SCORE(SCORE_H), AQI(AQI_H), UV(UV_H), CLOUD(CLOUD_H) }

    private fun stripsOf(widget: ForecastWidgetConfig): List<Strip> = buildList {
        if (widget.showScore) add(Strip.SCORE)
        if (widget.showAqi) add(Strip.AQI)
        if (widget.showUv) add(Strip.UV)
        if (widget.showCloud) add(Strip.CLOUD)
    }

    private fun bandH(strip: Strip, dp: Float) = (strip.h + STRIP_GAP) * dp

    /**
     * Band reserved BELOW the plot for the hour labels. The time axis is a layer of the
     * chart, not something under it, so the plot has to stop short of the panel's own
     * bottom edge — drawing the axis past it just clips the labels away.
     */
    private const val AXIS_H = 14f

    /** A thunder chance worth outlining a column for, short of the user's alert threshold. */
    private const val MARGINAL_THUNDER = 15.0

    fun render(
        context: Context,
        s: Snapshot,
        cfg: AppConfig,
        widget: ForecastWidgetConfig,
        unit: TempUnit,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        dark: Boolean,
        textScale: Float = 1f,
        nowMs: Long = System.currentTimeMillis(),
    ): Bitmap {
        val w = widthPx.coerceAtLeast(160)
        val h = heightPx.coerceAtLeast(90)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = Tokens.data(dark)
        val chrome = Tokens.chrome(dark)
        val dp = density

        // The series always starts at the current hour, so a widget set to 24h shows the
        // next 24 rather than whatever 24 the last fetch happened to begin with.
        val start = firstFutureIndex(s, nowMs).coerceAtLeast(0)
        val slice = start until min(s.scores.size, start + widget.hours)
        if (slice.isEmpty()) return bmp
        val n = slice.count()

        val head = Head(s, cfg, unit, slice)
        drawHeader(context, c, head, d, chrome, dp, w)

        val ts = textScale
        val plotTop = (HEADER_H + HEADER_GAP) * dp
        val axisH = AXIS_H * dp * ts
        // The score, air-quality, UV and cloud strips each take their own reserved band at the
        // very bottom of the panel (below the plot, above the time axis), so they're out of the
        // way of the full-height rain columns and read as the floor of the chart.
        val stripsBand = stripsOf(widget).fold(0f) { acc, strip -> acc + bandH(strip, dp) }
        val g = Geom(PLOT_LEFT * dp, plotTop, w.toFloat(), h - axisH - stripsBand, n)
        drawChart(context, c, s, slice, widget, unit, g, d, chrome, dp, cfg, nowMs, dark, ts)
        drawRefresh(context, c, chrome, dp, w, h)
        return bmp
    }

    // ------------------------------------------------------------------ header

    /**
     * §C.2's four factors. The hero temperature IS the temperature factor — stating it twice
     * (once large, once in the factor line) wastes the panel's scarcest space on a number the
     * reader has already read.
     */
    private class Head(
        s: Snapshot, cfg: AppConfig, unit: TempUnit, slice: IntRange,
    ) {
        val tempF = s.tempF
        val hero = s.tempF?.let { unit.deg(it) } ?: "—°"
        val place = cfg.placeName?.trim().orEmpty()
        val condition: String
        val hi: String
        val lo: String
        val factors: List<Triple<String, String, Factor>>
        // UV and cloud aren't comfort factors — no penalty, no limiting marker — so they ride
        // the tail of the factor line as plain context readouts rather than joining [factors].
        val extras: List<Pair<String, String>>
        val limiting: Factor?
        val storm: Pair<String, Double>?

        init {
            val temps = slice.mapNotNull { s.tempSeries.getOrNull(it) }
            hi = temps.maxOrNull()?.let { unit.deg(it) } ?: "—°"
            lo = temps.minOrNull()?.let { unit.deg(it) } ?: "—°"

            val rainNow = s.precipProb.getOrNull(slice.first) ?: 0.0
            val thunderNow = s.thunderSeries.getOrNull(slice.first)
            val rank = wxRank(rainNow, thunderNow, cfg.thunderAlertPct)
            val label = when (rank) {
                WxRank.THUNDER -> "Storms"
                WxRank.RAIN -> "Rain"
                WxRank.CLOUDY -> "Cloudy"
                WxRank.PARTLY -> "Partly cloudy"
                WxRank.CLEAR -> "Clear"
            }
            condition = if (place.isEmpty()) label else "$label · $place"

            factors = listOfNotNull(
                s.dewPointF?.let { Triple("DEW", unit.deg(it), Factor.DEW) },
                s.aqi?.let { Triple("AIR", it.toString(), Factor.AQI) },
                Triple("RAIN", "${rainNow.roundToInt()}%", Factor.RAIN),
            )

            extras = listOfNotNull(
                s.uvSeries.getOrNull(slice.first)?.let { "UV" to it.roundToInt().toString() },
                s.cloudSeries.getOrNull(slice.first)?.let { "CLD" to "${it.roundToInt()}%" },
            )

            val c = cfg.comfort
            limiting = listOf(
                Factor.TEMP to tempSeverity(s.tempF, c),
                Factor.DEW to severity(s.dewPointF, c.dewPointIdealF, c.dewPointMaxF),
                Factor.AQI to severity(s.aqi?.toDouble(), c.aqiIdeal, c.aqiMax.toDouble()),
                Factor.RAIN to severity(rainNow, c.rainProbIdeal, c.rainProbMax),
            ).filter { it.second > 0 }.maxByOrNull { it.second }?.first

            // The peak storm hour, if any — what the chip names.
            storm = slice.mapNotNull { i ->
                val th = s.thunderSeries.getOrNull(i) ?: return@mapNotNull null
                if (th < cfg.thunderAlertPct) null else s.times.getOrNull(i)?.let { it to th }
            }.maxByOrNull { it.second }?.let { (time, th) -> ForecastBuilder.fmtHour(time) to th }
        }
    }

    private fun drawHeader(
        context: Context, c: Canvas, head: Head, d: Tokens.Data, chrome: Tokens.Chrome,
        dp: Float, w: Int,
    ) {
        val icon = ContextCompat.getDrawable(context, glyphRes(head))
        val iconSize = 26f * dp
        icon?.setTint(chrome.body)
        icon?.setBounds(0, 0, iconSize.toInt(), iconSize.toInt())
        c.save()
        c.translate(0f, 2f * dp)
        icon?.draw(c)
        c.restore()

        val heroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.heading; textSize = 25f * dp
            typeface = WidgetType.mono(context); letterSpacing = -0.03f
        }
        val heroX = iconSize + 8f * dp
        val heroBaseline = 24f * dp
        // When temperature is the limiting factor the HERO wears the marker, and no chip is
        // emitted for it — the number is already the largest thing on the panel.
        if (head.limiting == Factor.TEMP) {
            markerBand(c, chrome, heroX, heroBaseline, heroPaint.measureText(head.hero), heroPaint.textSize)
        }
        c.drawText(head.hero, heroX, heroBaseline, heroPaint)

        val condPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.muted; textSize = 12f * dp; typeface = WidgetType.ui(context)
        }
        val condX = heroX + heroPaint.measureText(head.hero) + 8f * dp

        val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.heading; textSize = 14f * dp
            typeface = WidgetType.mono(context); textAlign = Paint.Align.RIGHT
        }
        val loPaint = Paint(hiPaint).apply { color = chrome.faint }
        val loW = loPaint.measureText(head.lo)
        c.drawText(head.lo, w.toFloat(), 16f * dp, loPaint)
        c.drawText(head.hi, w - loW - 5f * dp, 16f * dp, hiPaint)

        val condRoom = (w - loW - hiPaint.measureText(head.hi) - 12f * dp) - condX
        c.drawText(ellipsize(head.condition, condPaint, condRoom), condX, 16f * dp, condPaint)

        drawFactorLine(context, c, head, d, chrome, dp, heroX, 36f * dp)
        head.storm?.let { (at, pct) -> drawStormChip(context, c, d, chrome, dp, w, at, pct) }
    }

    /**
     * The other three factors, each label in its own hue so the row can be read without a
     * legend, with a flat band behind whichever one is holding the score back.
     */
    private fun drawFactorLine(
        context: Context, c: Canvas, head: Head, d: Tokens.Data, chrome: Tokens.Chrome,
        dp: Float, x0: Float, baseline: Float,
    ) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * dp; typeface = WidgetType.monoBold(context)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.body; textSize = 11f * dp; typeface = WidgetType.mono(context)
        }
        // The separator rides the proportional UI face, not mono: a monospace " · " reserves a
        // full figure cell on each side and, with five readouts now on the line, that padding is
        // exactly the horizontal room the row can least afford.
        val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.faint; textSize = 11f * dp; typeface = WidgetType.ui(context)
        }

        var x = x0
        var drawn = 0
        fun cell(label: String, value: String, labelColor: Int, factor: Factor?, valueColor: Int = chrome.body) {
            if (drawn > 0) {
                c.drawText(" · ", x, baseline, sepPaint)
                x += sepPaint.measureText(" · ")
            }
            val pairW = labelPaint.measureText("$label ") + valuePaint.measureText(value)
            if (factor != null && head.limiting == factor) markerBand(c, chrome, x, baseline, pairW, labelPaint.textSize)
            labelPaint.color = labelColor
            c.drawText("$label ", x, baseline, labelPaint)
            x += labelPaint.measureText("$label ")
            valuePaint.color = valueColor
            c.drawText(value, x, baseline, valuePaint)
            x += valuePaint.measureText(value)
            drawn++
        }

        head.factors.forEach { (label, value, factor) -> cell(label, value, Tokens.factorHue(d, factor), factor) }
        head.extras.forEach { (label, value) ->
            val color = when (label) {
                "UV" -> d.sun
                "CLD" -> d.cloud
                else -> chrome.muted
            }
            cell(label, value, color, null, color)
        }
    }

    /** A flat band over the bottom 42% of the line — a highlighter, not a box. */
    private fun markerBand(c: Canvas, chrome: Tokens.Chrome, x: Float, baseline: Float, width: Float, textSize: Float) {
        val top = baseline - textSize * 0.42f
        c.drawRect(x - 2f, top, x + width + 2f, baseline + textSize * 0.12f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = chrome.marker })
    }

    private fun drawStormChip(
        context: Context, c: Canvas, d: Tokens.Data, chrome: Tokens.Chrome, dp: Float,
        w: Int, at: String, pct: Double,
    ) {
        val text = "${pct.roundToInt()}% $at"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = d.storm; textSize = 11f * dp; typeface = WidgetType.mono(context)
        }
        val glyph = 13f * dp
        val padH = 5f * dp
        val chipW = glyph + 3f * dp + paint.measureText(text) + padH * 2
        val chipH = 17f * dp
        val left = w - chipW
        val top = 23f * dp
        c.drawRoundRect(
            RectF(left, top, left + chipW, top + chipH), chipH / 2, chipH / 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.lerpLinear(chrome.card, d.storm, 0.18f) },
        )
        ContextCompat.getDrawable(context, R.drawable.ic_wx_thunder)?.apply {
            setTint(d.storm)
            setBounds(0, 0, glyph.toInt(), glyph.toInt())
            c.save()
            c.translate(left + padH, top + (chipH - glyph) / 2)
            draw(c)
            c.restore()
        }
        c.drawText(text, left + padH + glyph + 3f * dp, top + chipH / 2 + 4f * dp, paint)
    }

    private fun glyphRes(head: Head): Int {
        val rain = head.factors.firstOrNull { it.third == Factor.RAIN }?.second
            ?.removeSuffix("%")?.toDoubleOrNull() ?: 0.0
        return when {
            head.storm != null -> R.drawable.ic_wx_thunder
            rain >= 20 -> R.drawable.ic_wx_rain
            rain >= 8 -> R.drawable.ic_wx_cloudy
            rain > 0 -> R.drawable.ic_wx_partly_day
            else -> R.drawable.ic_wx_clear_day
        }
    }

    // ------------------------------------------------------------------ chart

    private fun drawChart(
        context: Context, c: Canvas, s: Snapshot, slice: IntRange, widget: ForecastWidgetConfig,
        unit: TempUnit, g: Geom, d: Tokens.Data, chrome: Tokens.Chrome, dp: Float, cfg: AppConfig,
        nowMs: Long, dark: Boolean, ts: Float,
    ) {
        val idx = slice.toList()
        val n = idx.size

        // Plot field: a sunken fill under everything, so the chart area reads as its own
        // surface and its top and bottom edges show where the y-axis bounds are.
        c.drawRect(g.x0, g.top, g.x1, g.bottom, Paint().apply { color = chrome.sunken })

        // 1. day/night shading + a SOLID grey line at each midnight and a DOTTED one at each
        // noon, unlabelled — the shading is what differentiates the days, in place of dates.
        if (widget.showDayNight) {
            val bandPaint = Paint().apply { color = chrome.bandTint }
            val slot = g.plotW / n
            idx.forEachIndexed { k, i ->
                if ((s.isDay.getOrNull(i) ?: true) == chrome.bandOnNight) return@forEachIndexed
                c.drawRect(g.x0 + k * slot, g.top, g.x0 + (k + 1) * slot, g.bottom, bandPaint)
            }
            val midnight = Paint().apply { color = chrome.faint; strokeWidth = 1.4f * dp }
            val noon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = chrome.faint; strokeWidth = 1.4f * dp
                pathEffect = DashPathEffect(floatArrayOf(2f * dp, 2.4f * dp), 0f)
            }
            for (k in 0 until n) {
                val time = s.times.getOrNull(idx[k]) ?: continue
                val x = g.xAt(k)
                when {
                    k > 0 && time.take(10) != s.times.getOrNull(idx[k - 1])?.take(10) ->
                        c.drawLine(x, g.top, x, g.bottom, midnight)
                    ForecastBuilder.hour(time) == 12 -> c.drawLine(x, g.top, x, g.bottom, noon)
                }
            }
        }

        val nowX = g.xAtFrac(nowFraction(s, slice, nowMs))

        // 2. rain columns FIRST, behind the temperature line — height is chance (up to the
        // whole plot), WIDTH is intensity.
        if (widget.showRain) drawRain(context, c, s, idx, widget, g, d, chrome, dp, cfg, ts)

        // 3. temperature/dew axis + gridlines + lines, only when a temperature or dew line is
        // actually shown and has data (so a rain- or AQI-only widget still draws).
        val temps = if (widget.showTemp) idx.mapNotNull { s.tempSeries.getOrNull(it) }.map(unit::fromF) else emptyList()
        val dews = if (widget.showDew) idx.mapNotNull { s.dewSeries.getOrNull(it) }.map(unit::fromF) else emptyList()
        val all = temps + dews
        if (all.isNotEmpty()) {
            val lo = all.min() - 3
            val hi = all.max() + 3
            val gridPaint = Paint().apply { color = chrome.hairline; strokeWidth = 1f }
            val gridText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = chrome.faint; textSize = 9f * dp * ts; typeface = WidgetType.mono(context)
            }
            for (v in gridValues(lo, hi)) {
                val y = g.yAt(v, lo, hi)
                c.drawLine(g.x0, y, g.x1, y, gridPaint)
                c.drawText("${v.roundToInt()}°", 0f, y - 2f * dp, gridText)
            }
            if (widget.showTemp) {
                drawSeries(
                    c, idx.map { s.tempSeries.getOrNull(it)?.let(unit::fromF) }, g, lo, hi, d.temp,
                    2.4f * dp, dp, dashed = false, degF = idx.map { s.tempSeries.getOrNull(it) },
                )
            }
            if (widget.showDew) {
                drawSeries(
                    c, idx.map { s.dewSeries.getOrNull(it)?.let(unit::fromF) }, g, lo, hi, d.dew, 2f * dp, dp,
                    dashed = true, degF = idx.map { s.dewSeries.getOrNull(it) },
                    highF = cfg.comfort.dewPointMaxF, lowF = cfg.comfort.dewPointMinF,
                )
            }
            drawExtremes(context, c, idx, s, unit, g, lo, hi, d, dp, ts, widget.showTemp, widget.showDew)
        }

        // 4. the below-plot strips, in order, each in its own reserved band at the panel foot.
        // Each strip draws its own bar and, when it has data, contributes a gutter tag; the tags
        // are placed together afterwards (see [drawStripLabels]) so the tight bands can't stack
        // one on top of the next.
        val labels = mutableListOf<StripLabel>()
        var bandTop = g.bottom
        stripsOf(widget).forEach { strip ->
            val y = bandTop + STRIP_GAP * dp
            val mid = y + strip.h * dp / 2
            val label = when (strip) {
                Strip.SCORE -> if (drawScoreStrip(c, s, idx, g, d, chrome, dp, cfg, y)) StripLabel("WIN", d.score, mid) else null
                Strip.AQI -> if (drawAqi(c, s, idx, g, d, chrome, dp, cfg, dark, y)) StripLabel("AIR", d.aqi, mid) else null
                Strip.UV -> if (drawUv(c, s, idx, chrome, dp, dark, g.x0, g.x1, y)) StripLabel("UV", chrome.muted, mid) else null
                Strip.CLOUD -> if (drawCloud(c, s, idx, chrome, dp, g.x0, g.x1, y)) StripLabel("cld", chrome.muted, mid) else null
            }
            label?.let { labels += it }
            bandTop += bandH(strip, dp)
        }
        drawStripLabels(context, c, labels, g.x0, dp, ts)

        // 5. the present, at the REAL clock time — the gap to the plot edge is staleness
        c.drawLine(nowX, g.top, nowX, g.bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = d.now; strokeWidth = 1.5f * dp
        })

        // 6. time axis, below every strip band
        drawTimeAxis(context, c, s, idx, g, chrome, dp, nowX, nowMs, ts, bandTop)
    }

    private fun drawRain(
        context: Context, c: Canvas, s: Snapshot, idx: List<Int>, widget: ForecastWidgetConfig,
        g: Geom, d: Tokens.Data, chrome: Tokens.Chrome, dp: Float, cfg: AppConfig, ts: Float,
    ) {
        // A 100%-chance column now fills the WHOLE plot height, so a wet spell is unmissable.
        // The fill is translucent so the temperature line (drawn on top) still reads through
        // a tall column.
        val colH = g.plotH
        val base = g.bottom
        var peak = -1
        idx.forEachIndexed { k, i ->
            val chance = s.precipProb.getOrNull(i) ?: 0.0
            val amount = s.precipIn.getOrNull(i) ?: 0.0
            if (chance <= 0 && amount <= 0) return@forEachIndexed
            if (widget.hideUnlikelyRain && chance < cfg.comfort.rainProbIdeal && amount <= 0) return@forEachIndexed
            if (peak < 0 || chance > (s.precipProb.getOrNull(idx[peak]) ?: 0.0)) peak = k

            val cw = Tokens.rainMarkDp(amount) * dp
            val ch = max((chance / 100.0).toFloat() * colH, 2f * dp)
            // Width carries the intensity, so a column at the plot edge clamps its CENTRE
            // and keeps its width — narrowing it would say "drizzle" about a downpour.
            val cx = g.xAt(k).coerceIn(g.x0, g.x1)
            val th = s.thunderSeries.getOrNull(i) ?: 0.0
            c.drawRect(cx - cw / 2, base - ch, cx + cw / 2, base, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (th >= cfg.thunderAlertPct) d.storm else d.rain
                alpha = 165
            })
            if (th >= MARGINAL_THUNDER && th < cfg.thunderAlertPct) {
                c.drawRect(cx - cw / 2, base - ch, cx + cw / 2, base, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = d.storm; style = Paint.Style.STROKE; strokeWidth = 0.9f * dp
                })
            }
        }
        if (peak < 0) return

        val i = idx[peak]
        val chance = s.precipProb.getOrNull(i) ?: 0.0
        val amount = s.precipIn.getOrNull(i) ?: 0.0
        val storm = (s.thunderSeries.getOrNull(i) ?: 0.0) >= cfg.thunderAlertPct
        val text = buildString {
            append("${chance.roundToInt()}%")
            if (amount > 0) append("  ${fmtInches(amount)}")
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (storm) d.storm else chrome.body
            textSize = 11f * dp * ts; typeface = WidgetType.mono(context)
        }
        val glyph = 11f * dp * ts
        val glyphW = if (storm) glyph + 3f * dp else 0f
        val textW = paint.measureText(text)
        val ch = max((chance / 100.0).toFloat() * colH, 2f * dp)
        // Kept inside the plot: a full-height column would otherwise push the label up into
        // the header.
        val y = (g.bottom - ch - 3f * dp).coerceAtLeast(g.top + 10f * dp * ts)
        val x = (g.xAt(peak) - (textW + glyphW) / 2)
            .coerceIn(g.x0, (g.x1 - textW - glyphW).coerceAtLeast(g.x0))
        if (storm) {
            // Inline to the LEFT of the value, never stacked above it — stacking would let
            // the column's own height decide how much clearance the glyph gets.
            ContextCompat.getDrawable(context, R.drawable.ic_wx_thunder)?.apply {
                setTint(d.storm)
                setBounds(0, 0, glyph.toInt(), glyph.toInt())
                c.save()
                c.translate(x, y - glyph + 1f * dp)
                draw(c)
                c.restore()
            }
        }
        c.drawText(text, x + glyphW, y, paint)
    }

    private fun drawAqi(
        c: Canvas, s: Snapshot, idx: List<Int>, g: Geom, d: Tokens.Data,
        chrome: Tokens.Chrome, dp: Float, cfg: AppConfig, dark: Boolean, y: Float,
    ): Boolean {
        val series = idx.map { s.aqiSeries.getOrNull(it) }
        if (series.all { it == null }) return false
        val (colors, stops) = AqiRamp.sweep(series, cfg.aqiPalette, dark)
        val bar = RectF(g.x0, y, g.x1, y + AQI_H * dp)
        c.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(g.x0, 0f, g.x1, 0f, colors, stops, Shader.TileMode.CLAMP)
        })
        c.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.hairline; style = Paint.Style.STROKE; strokeWidth = 0.75f * dp
        })
        return true
    }

    /** UV index as a gradient strip — the app drawer's UV bar (see [UvRamp]) at panel scale. */
    private fun drawUv(
        c: Canvas, s: Snapshot, idx: List<Int>, chrome: Tokens.Chrome,
        dp: Float, dark: Boolean, x0: Float, x1: Float, y: Float,
    ): Boolean {
        val series = idx.map { s.uvSeries.getOrNull(it) }
        if (series.all { it == null }) return false
        val (colors, stops) = UvRamp.sweep(series, idx.map { s.isDay.getOrNull(it) }, dark)
        val bar = RectF(x0, y, x1, y + UV_H * dp)
        c.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(x0, 0f, x1, 0f, colors, stops, Shader.TileMode.CLAMP)
        })
        c.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.hairline; style = Paint.Style.STROKE; strokeWidth = 0.75f * dp
        })
        return true
    }

    /**
     * Cloud cover as the app drawer's cloud bar: the objective clear-sky colour ([CloudRamp])
     * with cover laid over it as a halftone dot screen, so the strip reads as sky with clouds
     * drawn on it rather than a grey fade.
     */
    private fun drawCloud(
        c: Canvas, s: Snapshot, idx: List<Int>, chrome: Tokens.Chrome,
        dp: Float, x0: Float, x1: Float, y: Float,
    ): Boolean {
        val cover = idx.map { s.cloudSeries.getOrNull(it) }
        if (cover.all { it == null }) return false
        val (colors, stops) = CloudRamp.skySweep(idx.map { s.isDay.getOrNull(it) })
        val bar = RectF(x0, y, x1, y + CLOUD_H * dp)
        c.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(x0, 0f, x1, 0f, colors, stops, Shader.TileMode.CLAMP)
        })
        c.save()
        c.clipRect(bar)
        cloudHalftone(c, x0, x1, bar.top, bar.bottom, cover, dp)
        c.restore()
        c.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.hairline; style = Paint.Style.STROKE; strokeWidth = 0.75f * dp
        })
        return true
    }

    /** One strip's gutter tag: text, hue, and the strip centre it wants to sit on. AIR takes
     *  the AQI text hue rather than the ramp's, whose green collides with the temperature line. */
    private class StripLabel(val text: String, val color: Int, val targetY: Float)

    /**
     * The strips' gutter tags, right-aligned just left of the plot. Each wants to sit on its
     * strip's centre, but the 2dp bands pack the centres closer than the text is tall, so the
     * tags are nudged apart: consecutive baselines are forced [minGap] apart while each moves as
     * little as possible from its target — so the middle tags stay on their strip and the top and
     * bottom ones give way. That least-squares fit under an ordering constraint is isotonic
     * regression, solved exactly by pool-adjacent-violators (see [spreadApart]).
     */
    private fun drawStripLabels(
        context: Context, c: Canvas, labels: List<StripLabel>, x0: Float, dp: Float, ts: Float,
    ) {
        if (labels.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * dp * ts; textAlign = Paint.Align.RIGHT; typeface = WidgetType.monoBold(context)
        }
        val ys = spreadApart(FloatArray(labels.size) { labels[it].targetY }, paint.textSize)
        labels.forEachIndexed { i, lab ->
            paint.color = lab.color
            c.drawText(lab.text, x0 - 3f * dp, ys[i] + 3f * dp, paint)
        }
    }

    /**
     * Least-squares placement of ascending [targets] under the constraint that consecutive
     * outputs are at least [minGap] apart: minimises Σ(out−target)². Substituting z_i = out_i −
     * i·minGap turns the spacing constraint into "non-decreasing", so the answer is the isotonic
     * regression of the shifted targets — pool adjacent violators, each pool taking its members'
     * mean.
     */
    private fun spreadApart(targets: FloatArray, minGap: Float): FloatArray {
        val n = targets.size
        if (n == 0) return targets
        val mean = FloatArray(n)
        val count = IntArray(n)
        val start = IntArray(n)
        var pools = 0
        for (i in 0 until n) {
            mean[pools] = targets[i] - i * minGap
            count[pools] = 1
            start[pools] = i
            pools++
            while (pools > 1 && mean[pools - 1] < mean[pools - 2]) {
                val merged = count[pools - 1] + count[pools - 2]
                mean[pools - 2] = (mean[pools - 1] * count[pools - 1] + mean[pools - 2] * count[pools - 2]) / merged
                count[pools - 2] = merged
                pools--
            }
        }
        val out = FloatArray(n)
        var i = 0
        for (p in 0 until pools) {
            val end = if (p + 1 < pools) start[p + 1] else n
            while (i < end) { out[i] = mean[p] + i * minGap; i++ }
        }
        return out
    }

    /**
     * The Canvas port of the app drawer's `halftone` (Drawer.kt): a hex grid of solid dots
     * whose area equals each hour's cover, so the white-pixel fraction equals the cover. The
     * overlap-corrected radius comes from [CloudRamp.dotRatio] — the one place that curve lives.
     * Cover is interpolated between hours so the screen thickens and thins smoothly.
     */
    private fun cloudHalftone(
        c: Canvas, x0: Float, x1: Float, top: Float, bottom: Float, cover: List<Double?>, dp: Float,
    ) {
        val n = cover.size
        if (n == 0) return
        val f = FloatArray(n)
        var last = 0f
        for (i in 0 until n) {
            cover[i]?.let { last = (it / 100.0).toFloat().coerceIn(0f, 1f) }
            f[i] = last
        }
        val span = (x1 - x0).coerceAtLeast(1f)
        fun coverAt(x: Float): Float {
            if (n == 1) return f[0]
            val t = ((x - x0) / span).coerceIn(0f, 1f) * (n - 1)
            val i0 = t.toInt().coerceIn(0, n - 1)
            val i1 = (i0 + 1).coerceAtMost(n - 1)
            return f[i0] + (f[i1] - f[i0]) * (t - i0)
        }
        val step = 2.4f * dp
        val rowH = step * 0.8660254f                    // hex packing row pitch, √3/2 · s
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CloudRamp.CLOUD }
        var row = 0
        var y = top + rowH * 0.5f
        while (y < bottom + rowH) {
            var x = x0 + if (row % 2 == 0) 0f else step * 0.5f
            while (x <= x1) {
                val cv = coverAt(x)
                if (cv > 0.01f) c.drawCircle(x, y, CloudRamp.dotRatio(cv) * step, dot)
                x += step
            }
            y += rowH
            row++
        }
    }

    /**
     * The Open-Window Score as a smooth horizontal colour strip, on the same footing as the
     * AQI strip: green where the hour clears the open threshold, and below it a MUTED blend of
     * the bad factors' own hues, weighted by how much each is holding the hour back — the exact
     * semantics of the score bars, so a glance says both "not open" and "why". The per-hour
     * colours are laid out as a gradient so the strip reads as a drift, never a set of blocks.
     */
    private fun drawScoreStrip(
        c: Canvas, s: Snapshot, idx: List<Int>,
        g: Geom, d: Tokens.Data, chrome: Tokens.Chrome, dp: Float, cfg: AppConfig, y: Float,
    ): Boolean {
        if (s.scores.isEmpty()) return false
        val threshold = cfg.comfort.openScoreMin
        val colors = IntArray(idx.size.coerceAtLeast(2)) { d.score }
        idx.forEachIndexed { k, i -> colors[k] = scoreColor(s.scores.getOrNull(i) ?: 0.0, threshold, cfg.comfort, s, i, d) }
        if (idx.size == 1) colors[1] = colors[0]

        val bar = RectF(g.x0, y, g.x1, y + SCORE_H * dp)
        val stops = FloatArray(colors.size) { it / (colors.size - 1).toFloat() }
        c.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(g.x0, 0f, g.x1, 0f, colors, stops, Shader.TileMode.CLAMP)
        })
        c.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.hairline; style = Paint.Style.STROKE; strokeWidth = 0.75f * dp
        })
        return true
    }

    /**
     * One hour's score-strip colour (see [drawScoreStrip]): vivid green when open, else the
     * bad factors' hues mixed by severity and muted through [Tokens.blockedBar]. Falls back to
     * the cached dominant factor, then a flat grey, when the per-factor series aren't cached.
     */
    private fun scoreColor(score: Double, threshold: Double, comfort: Comfort, s: Snapshot, i: Int, d: Tokens.Data): Int {
        if (score >= threshold) return d.score
        val hues = intArrayOf(d.temp, d.dew, d.aqi, d.rain)
        val weights = doubleArrayOf(
            tempSeverity(s.tempSeries.getOrNull(i), comfort),
            severity(s.dewSeries.getOrNull(i), comfort.dewPointIdealF, comfort.dewPointMaxF),
            severity(s.aqiSeries.getOrNull(i)?.toDouble(), comfort.aqiIdeal, comfort.aqiMax.toDouble()),
            severity(s.precipProb.getOrNull(i), comfort.rainProbIdeal, comfort.rainProbMax),
        )
        if (weights.sum() <= 0.0) {
            val wf = s.worst.getOrNull(i) ?: -1
            return if (wf in hues.indices) Tokens.blockedBar(d, hues[wf]) else d.blockedBase
        }
        return Tokens.blockedBar(d, Tokens.mixLinear(hues, weights))
    }

    private fun drawSeries(
        c: Canvas, values: List<Double?>, g: Geom, lo: Double, hi: Double,
        color: Int, width: Float, dp: Float, dashed: Boolean,
        /** Raw °F per hour. When given, a segment at/above [highF] is red and at/below [lowF] is blue. */
        degF: List<Double?>? = null,
        highF: Double = HOT_F,
        lowF: Double = COLD_F,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = width
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            if (dashed) pathEffect = DashPathEffect(floatArrayOf(1f * dp, 3.4f * dp), 0f)
        }
        for (k in 0 until values.size - 1) {
            val a = values[k] ?: continue
            val b = values[k + 1] ?: continue
            paint.color = segmentColor(color, degF, k, highF, lowF)
            c.drawLine(g.xAt(k), g.yAt(a, lo, hi), g.xAt(k + 1), g.yAt(b, lo, hi), paint)
        }
    }

    /** [HotRedArgb] at/above [highF], [ColdBlueArgb] at/below [lowF], else [base]. Null [degF] stays [base]. */
    private fun segmentColor(base: Int, degF: List<Double?>?, k: Int, highF: Double, lowF: Double): Int {
        if (degF == null) return base
        val a = degF.getOrNull(k) ?: return base
        val f = (a + (degF.getOrNull(k + 1) ?: a)) / 2
        return segmentTintArgb(f, base, highF, lowF)
    }

    /**
     * Each shown series' high and low, printed over the very hour they fall on, in the series'
     * own colour and with no label — the AQI strip's peak/trough treatment, extended to temp
     * and dew. Replaces the start-of-line TEMP/DEW tags.
     */
    private fun drawExtremes(
        context: Context, c: Canvas, idx: List<Int>, s: Snapshot, unit: TempUnit, g: Geom,
        lo: Double, hi: Double, d: Tokens.Data, dp: Float, ts: Float, showTemp: Boolean, showDew: Boolean,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * dp * ts; typeface = WidgetType.monoBold(context); textAlign = Paint.Align.CENTER
        }
        fun mark(series: List<Double?>, colour: Int) {
            val known = series.indices.filter { series[it] != null }
            if (known.isEmpty()) return
            val hiIdx = known.maxByOrNull { series[it]!! }!!
            val loIdx = known.minByOrNull { series[it]!! }!!
            paint.color = colour
            placeValue(c, unit.deg(series[hiIdx]!!), paint, g, hiIdx, g.yAt(unit.fromF(series[hiIdx]!!), lo, hi), above = true, dp, ts)
            if (loIdx != hiIdx) {
                placeValue(c, unit.deg(series[loIdx]!!), paint, g, loIdx, g.yAt(unit.fromF(series[loIdx]!!), lo, hi), above = false, dp, ts)
            }
        }
        if (showTemp) mark(idx.map { s.tempSeries.getOrNull(it) }, d.temp)
        if (showDew) mark(idx.map { s.dewSeries.getOrNull(it) }, d.dew)
    }

    /** One extreme's value centred over its hour, above or below the point, clamped on-screen. */
    private fun placeValue(
        c: Canvas, text: String, paint: Paint, g: Geom, k: Int, atY: Float, above: Boolean, dp: Float, ts: Float,
    ) {
        val half = paint.measureText(text) / 2
        val cx = g.xAt(k).coerceIn(g.x0 + half, g.x1 - half)
        val y = (if (above) atY - 3f * dp else atY + 10f * dp * ts).coerceIn(g.top + 8f * dp * ts, g.bottom - 1f * dp)
        c.drawText(text, cx, y, paint)
    }

    private fun drawTimeAxis(
        context: Context, c: Canvas, s: Snapshot, idx: List<Int>, g: Geom, chrome: Tokens.Chrome,
        dp: Float, nowX: Float, nowMs: Long, ts: Float, axisTop: Float,
    ) {
        // The time axis is set small — on a par with the score widget's hour ticks (8.5sp)
        // rather than the old 11sp — so the clock and hour marks read as chart furniture.
        val axisText = 8.5f * dp * ts
        val baseline = axisTop + 9f * dp * ts
        val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.heading; textSize = axisText; typeface = WidgetType.monoBold(context)
        }
        val clock = clockLabel(nowMs)
        val clockX = min(nowX + 3f * dp, g.x1 - clockPaint.measureText(clock))
        val clockBox = clockX - 4f * dp to clockX + clockPaint.measureText(clock) + 4f * dp
        c.drawText(clock, clockX, baseline, clockPaint)

        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.faint; textSize = axisText; typeface = WidgetType.mono(context)
        }
        idx.forEachIndexed { k, i ->
            val time = s.times.getOrNull(i) ?: return@forEachIndexed
            if (ForecastBuilder.hour(time) % 6 != 0) return@forEachIndexed
            val label = ForecastBuilder.fmtHour(time)
            val tw = tick.measureText(label)
            val x = (g.xAt(k) - tw / 2).coerceIn(0f, g.x1 - tw)
            // Measured, not assumed: whether the clock leaves room depends on the time of day.
            if (x <= clockBox.second && clockBox.first <= x + tw) return@forEachIndexed
            c.drawText(label, x, baseline, tick)
        }
    }

    private fun drawRefresh(context: Context, c: Canvas, chrome: Tokens.Chrome, dp: Float, w: Int, h: Int) {
        val size = 13f * dp
        ContextCompat.getDrawable(context, R.drawable.ic_refresh)?.apply {
            setTint(chrome.faint)
            setBounds(0, 0, size.toInt(), size.toInt())
            c.save()
            c.translate(w - size, h - size)
            draw(c)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ geometry + helpers

    private class Geom(val x0: Float, val top: Float, val x1: Float, val bottom: Float, val n: Int) {
        val plotW get() = x1 - x0
        val plotH get() = bottom - top
        fun xAt(k: Int) = if (n <= 1) (x0 + x1) / 2 else x0 + k.toFloat() / (n - 1) * plotW
        fun xAtFrac(f: Float) = x0 + f.coerceIn(0f, 1f) * plotW
        fun yAt(v: Double, lo: Double, hi: Double): Float {
            val f = if (hi <= lo) 0.0 else (v - lo) / (hi - lo)
            return bottom - TOP_HEADROOM * f.toFloat().coerceIn(0f, 1f) * plotH
        }
    }

    /** The temperature line stops just short of the top so a peak doesn't touch the header. */
    private const val TOP_HEADROOM = 0.9f

    private fun gridValues(lo: Double, hi: Double): List<Double> {
        val span = hi - lo
        if (span <= 0) return emptyList()
        val raw = span / 4
        val step = listOf(2.0, 5.0, 10.0, 20.0, 25.0).firstOrNull { it >= raw } ?: 50.0
        var v = Math.ceil(lo / step) * step
        val out = mutableListOf<Double>()
        while (v < hi && out.size < 3) { out.add(v); v += step }
        return out
    }

    /** Index of the first row at or after the present, so the panel always starts at now. */
    private fun firstFutureIndex(s: Snapshot, nowMs: Long): Int {
        val pos = nowBarPos(s.firstHourMs, nowMs) ?: return 0
        return pos.toInt().coerceIn(0, (s.scores.size - 1).coerceAtLeast(0))
    }

    private fun nowFraction(s: Snapshot, slice: IntRange, nowMs: Long): Float {
        val pos = nowBarPos(s.firstHourMs, nowMs) ?: return 0f
        val within = pos - slice.first
        val n = slice.count()
        return if (n <= 1) 0f else (within / (n - 1)).toFloat().coerceIn(0f, 1f)
    }

    private fun clockLabel(nowMs: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        val h12 = (h % 12).let { if (it == 0) 12 else it }
        return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
    }

    private fun fmtInches(v: Double): String =
        (if (v >= 0.1) String.format("%.1f", v) else String.format("%.2f", v)) + "″"

    private fun ellipsize(text: String, paint: Paint, room: Float): String {
        if (room <= 0) return ""
        if (paint.measureText(text) <= room) return text
        var s = text
        while (s.isNotEmpty() && paint.measureText("$s…") > room) s = s.dropLast(1)
        return if (s.isEmpty()) "" else "$s…"
    }
}
