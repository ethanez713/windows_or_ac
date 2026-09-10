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
    private const val AQI_H = 4f

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

        val head = Head(s, cfg, unit, slice, widget)
        drawHeader(context, c, head, d, chrome, dp, w)

        val ts = textScale
        val plotTop = (HEADER_H + HEADER_GAP) * dp
        val axisH = AXIS_H * dp * ts
        // Air quality moves to a thin band at the very BOTTOM of the panel (its own reserved
        // strip below the plot, above the time axis), so it's out of the way of the
        // full-height rain columns and reads as the floor of the chart.
        val aqiBand = if (widget.showAqi) (AQI_H + 5f) * dp else 0f
        val g = Geom(PLOT_LEFT * dp, plotTop, w.toFloat(), h - axisH - aqiBand, n)
        drawChart(context, c, s, slice, widget, unit, g, d, chrome, dp, cfg, nowMs, dark, ts, aqiBand)
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
        s: Snapshot, cfg: AppConfig, unit: TempUnit, slice: IntRange, widget: ForecastWidgetConfig,
    ) {
        val tempF = s.tempF
        val hero = s.tempF?.let { unit.deg(it) } ?: "—°"
        val place = cfg.placeName?.trim().orEmpty()
        val condition: String
        val hi: String
        val lo: String
        val factors: List<Triple<String, String, Factor>>
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

            val c = cfg.comfort
            limiting = listOf(
                Factor.TEMP to severity(s.tempF, c.tempIdealF, c.maxTempF),
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
        val sepPaint = Paint(valuePaint).apply { color = chrome.faint }

        var x = x0
        head.factors.forEachIndexed { i, (label, value, factor) ->
            if (i > 0) {
                c.drawText(" · ", x, baseline, sepPaint)
                x += sepPaint.measureText(" · ")
            }
            val pairW = labelPaint.measureText("$label ") + valuePaint.measureText(value)
            if (head.limiting == factor) markerBand(c, chrome, x, baseline, pairW, labelPaint.textSize)
            labelPaint.color = Tokens.factorHue(d, factor)
            c.drawText("$label ", x, baseline, labelPaint)
            x += labelPaint.measureText("$label ")
            c.drawText(value, x, baseline, valuePaint)
            x += valuePaint.measureText(value)
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
        nowMs: Long, dark: Boolean, ts: Float, aqiBand: Float,
    ) {
        val idx = slice.toList()
        val n = idx.size

        // 1. day/night bands + midnight dividers
        val bandPaint = Paint().apply { color = chrome.bandTint }
        val slot = g.plotW / n
        idx.forEachIndexed { k, i ->
            if ((s.isDay.getOrNull(i) ?: true) == chrome.bandOnNight) return@forEachIndexed
            c.drawRect(g.x0 + k * slot, g.top, g.x0 + (k + 1) * slot, g.bottom, bandPaint)
        }
        val dividerPaint = Paint().apply { color = chrome.hairline; strokeWidth = 1f * dp }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.faint; textSize = 9f * dp * ts; typeface = WidgetType.mono(context)
        }
        for (k in 1 until n) {
            if (s.times.getOrNull(idx[k])?.take(10) == s.times.getOrNull(idx[k - 1])?.take(10)) continue
            val x = g.xAt(k)
            c.drawLine(x, g.top, x, g.bottom, dividerPaint)
            if (widget.showDateFlags) c.drawText(dateLabel(s.times[idx[k]]), x + 4f * dp, g.top + 9f * dp * ts, datePaint)
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
                drawSeries(c, idx.map { s.tempSeries.getOrNull(it)?.let(unit::fromF) }, g, lo, hi, d.temp, 2.4f * dp, dp, dashed = false)
            }
            if (widget.showDew) {
                drawSeries(c, idx.map { s.dewSeries.getOrNull(it)?.let(unit::fromF) }, g, lo, hi, d.dew, 2f * dp, dp, dashed = true)
            }
            drawTags(context, c, idx, s, unit, g, lo, hi, d, dp, nowX, ts, widget.showTemp, widget.showDew)
        }

        // 4. air quality strip, in its reserved band at the foot of the panel
        if (widget.showAqi) drawAqi(context, c, s, idx, g, d, chrome, dp, cfg, dark, ts)

        // 5. the present, at the REAL clock time — the gap to the plot edge is staleness
        c.drawLine(nowX, g.top, nowX, g.bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = d.now; strokeWidth = 1.5f * dp
        })

        // 6. time axis, below the AQI band
        drawTimeAxis(context, c, s, idx, g, chrome, dp, nowX, nowMs, ts, g.bottom + aqiBand)
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
        val slot = g.plotW / idx.size
        var peak = -1
        idx.forEachIndexed { k, i ->
            val chance = s.precipProb.getOrNull(i) ?: 0.0
            val amount = s.precipIn.getOrNull(i) ?: 0.0
            if (chance <= 0 && amount <= 0) return@forEachIndexed
            if (widget.hideUnlikelyRain && chance < cfg.comfort.rainProbIdeal && amount <= 0) return@forEachIndexed
            if (peak < 0 || chance > (s.precipProb.getOrNull(idx[peak]) ?: 0.0)) peak = k

            val intensity = min(1.0, amount / cfg.rainScaleInPerHr.coerceAtLeast(0.01))
            // Widen the intensity range so a drizzle column is a sliver and a downpour nearly
            // fills its slot — the width difference is the whole point of the encoding.
            val cw = slot * (0.12 + 0.85 * intensity).toFloat()
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
        context: Context, c: Canvas, s: Snapshot, idx: List<Int>, g: Geom, d: Tokens.Data,
        chrome: Tokens.Chrome, dp: Float, cfg: AppConfig, dark: Boolean, ts: Float,
    ) {
        val series = idx.map { s.aqiSeries.getOrNull(it) }
        if (series.all { it == null }) return
        val (colors, stops) = AqiRamp.sweep(series, cfg.aqiPalette, dark)
        val y = g.bottom + 3f * dp
        val bar = RectF(g.x0, y, g.x1, y + AQI_H * dp)
        c.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(g.x0, 0f, g.x1, 0f, colors, stops, Shader.TileMode.CLAMP)
        })
        c.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.hairline; style = Paint.Style.STROKE; strokeWidth = 0.75f * dp
        })
        // Named in the AQI text colour rather than left to the ramp: under the EPA palette
        // the ramp's own hue collides with the temperature line.
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = d.aqi; textSize = 9f * dp * ts; textAlign = Paint.Align.RIGHT
            typeface = WidgetType.monoBold(context)
        }
        c.drawText("AIR", g.x0 - 3f * dp, y + AQI_H * dp / 2 + 3f * dp, label)
    }

    private fun drawSeries(
        c: Canvas, values: List<Double?>, g: Geom, lo: Double, hi: Double,
        color: Int, width: Float, dp: Float, dashed: Boolean,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = width
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            if (dashed) pathEffect = DashPathEffect(floatArrayOf(1f * dp, 3.4f * dp), 0f)
        }
        for (k in 0 until values.size - 1) {
            val a = values[k] ?: continue
            val b = values[k + 1] ?: continue
            c.drawLine(g.xAt(k), g.yAt(a, lo, hi), g.xAt(k + 1), g.yAt(b, lo, hi), paint)
        }
    }

    /**
     * `TEMP`/`DEW` plus each series' high/low at that line's left end. The start is pushed
     * clear of the now-rule rather than pinned to the gutter, so the rule cannot strike
     * through the label.
     */
    private fun drawTags(
        context: Context, c: Canvas, idx: List<Int>, s: Snapshot, unit: TempUnit, g: Geom,
        lo: Double, hi: Double, d: Tokens.Data, dp: Float, nowX: Float, ts: Float,
        showTemp: Boolean, showDew: Boolean,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * dp * ts; letterSpacing = 0.1f; typeface = WidgetType.monoBold(context)
        }
        val x = max(g.x0 + 2f * dp, nowX + 5f * dp)
        // Each tag carries the series' own high/low over the shown window and sits at that
        // line's left end, so both the reading and the hour it starts at are at a glance —
        // and DEW lands correctly whether or not the temperature line is drawn above it.
        if (showTemp) {
            val vals = idx.mapNotNull { s.tempSeries.getOrNull(it) }.map(unit::fromF)
            val first = idx.firstNotNullOfOrNull { s.tempSeries.getOrNull(it) }?.let(unit::fromF)
            if (first != null && vals.isNotEmpty()) {
                paint.color = d.temp
                c.drawText("TEMP ${vals.max().roundToInt()}°/${vals.min().roundToInt()}°", x, g.yAt(first, lo, hi) - 3f * dp, paint)
            }
        }
        if (showDew) {
            val vals = idx.mapNotNull { s.dewSeries.getOrNull(it) }.map(unit::fromF)
            val first = idx.firstNotNullOfOrNull { s.dewSeries.getOrNull(it) }?.let(unit::fromF)
            if (first != null && vals.isNotEmpty()) {
                paint.color = d.dew
                c.drawText("DEW ${vals.max().roundToInt()}°/${vals.min().roundToInt()}°", x, g.yAt(first, lo, hi) + 11f * dp * ts, paint)
            }
        }
    }

    private fun drawTimeAxis(
        context: Context, c: Canvas, s: Snapshot, idx: List<Int>, g: Geom, chrome: Tokens.Chrome,
        dp: Float, nowX: Float, nowMs: Long, ts: Float, axisTop: Float,
    ) {
        val baseline = axisTop + 11f * dp * ts
        val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.heading; textSize = 11f * dp * ts; typeface = WidgetType.monoBold(context)
        }
        val clock = clockLabel(nowMs)
        val clockX = min(nowX + 3f * dp, g.x1 - clockPaint.measureText(clock))
        val clockBox = clockX - 4f * dp to clockX + clockPaint.measureText(clock) + 4f * dp
        c.drawText(clock, clockX, baseline, clockPaint)

        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chrome.faint; textSize = 11f * dp * ts; typeface = WidgetType.mono(context)
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

    private fun dateLabel(iso: String): String = runCatching {
        java.time.LocalDate.parse(iso.take(10))
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE M/d")).uppercase()
    }.getOrDefault("")

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
