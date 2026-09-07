package com.acwindows

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The forecast screen (docs/ux-redesign.md §D): the forecast widget's encoding at phone
 * scale. It is a companion to the windows screen, NOT a replacement — that screen answers
 * "when do I open the windows", this one answers "what will conditions be".
 */
class ForecastActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textScale = AppConfig.load(this).appTextScalePct / 100f
        setContent { AcWindowsTheme(textScale) { ForecastRoot() } }
    }
}

@Composable
private fun ForecastRoot(vm: AppViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val chrome = Skin.chrome
    val cfg = remember(ui.updatedAtMs) { AppConfig.load(context) }
    val unit = remember(ui.updatedAtMs) { TempUnit.load(context) }
    val state = ui.state

    Box(Modifier.fillMaxSize().background(chrome.ground)) {
        if (state == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (ui.loading) "Loading…" else (ui.error ?: "No forecast yet"),
                    fontFamily = Ui, fontSize = 14.sp, color = chrome.muted,
                )
            }
            return@Box
        }
        val points = state.forecast.points
        val today = remember(points) { points.filter { it.time.take(10) == LocalDate.now().toString() } }
        val horizon = remember(points) { points.take(24) }
        val laterDays = remember(points) {
            points.groupBy { it.time.take(10) }
                .filterKeys { it != LocalDate.now().toString() }
                .toSortedMap()
                .map { (date, pts) -> date to pts }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item { ForecastHeader(state, cfg, ui.updatedAtMs, onRefresh = vm::refresh) }
            item { HeroCard(state, horizon, cfg, unit) }
            item { ForecastChartCard(horizon, cfg, unit) }
            items(laterDays, key = { it.first }) { (date, pts) ->
                DayRowCard(date, pts, cfg, unit)
            }
        }
    }
}

@Composable
private fun ForecastHeader(state: AppState, cfg: AppConfig, updatedAtMs: Long, onRefresh: () -> Unit) {
    val chrome = Skin.chrome
    val con = state.consensus
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                cfg.placeName?.trim().orEmpty().ifEmpty { "Forecast" },
                fontFamily = Heading, fontSize = 22.sp, color = chrome.heading,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${con.sourcesOk.size} of ${con.sourcesAttempted} sources · ${clockOf(updatedAtMs)}",
                fontFamily = Mono, fontSize = 12.sp, color = chrome.faint,
            )
        }
        IconTarget(R.drawable.ic_refresh, chrome.muted, onRefresh)
        val context = LocalContext.current
        IconTarget(R.drawable.ic_settings, chrome.muted) {
            context.startActivity(Intent(context, ConfigActivity::class.java))
        }
    }
}

@Composable
private fun IconTarget(res: Int, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(Tokens.Radius.row)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/**
 * §C.2's header at phone scale. The hero temperature IS the temperature factor, so the
 * factor line below carries only the other three — and whichever one is limiting wears the
 * marker band, the hero included.
 */
@Composable
private fun HeroCard(state: AppState, horizon: List<ForecastPoint>, cfg: AppConfig, unit: TempUnit) {
    val chrome = Skin.chrome
    val d = Skin.data
    val con = state.consensus
    val now = horizon.firstOrNull()
    val temps = horizon.mapNotNull { it.tempF }
    val rainNow = now?.precipProb ?: 0.0
    val rank = wxRank(rainNow, now?.thunderProb, cfg.thunderAlertPct)
    val c = cfg.comfort
    val limiting = remember(con, rainNow) {
        listOf(
            Factor.TEMP to severity(con.tempF, c.tempIdealF, c.maxTempF),
            Factor.DEW to severity(con.dewPointF, c.dewPointIdealF, c.dewPointMaxF),
            Factor.AQI to severity(con.aqi?.toDouble(), c.aqiIdeal, c.aqiMax.toDouble()),
            Factor.RAIN to severity(rainNow, c.rainProbIdeal, c.rainProbMax),
        ).filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
    val storm = remember(horizon) {
        horizon.filter { (it.thunderProb ?: 0.0) >= cfg.thunderAlertPct }
            .maxByOrNull { it.thunderProb ?: 0.0 }
    }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(glyphFor(rank, isDay = now?.isDay ?: true)),
                contentDescription = null, tint = chrome.body, modifier = Modifier.size(42.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Marked(limiting == Factor.TEMP) {
                        Text(
                            con.tempF?.let { unit.deg(it) } ?: "—°",
                            fontFamily = Mono, fontSize = 46.sp, color = chrome.heading,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        conditionLabel(rank), fontFamily = Ui, fontSize = 13.sp, color = chrome.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FactorChip("DEW", con.dewPointF?.let { unit.deg(it) }, d.dew, limiting == Factor.DEW)
                    FactorChip("AIR", con.aqi?.toString(), d.aqi, limiting == Factor.AQI)
                    FactorChip("RAIN", "${rainNow.roundToInt()}%", d.rain, limiting == Factor.RAIN)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    temps.maxOrNull()?.let { unit.deg(it) } ?: "—°",
                    fontFamily = Mono, fontSize = 20.sp, color = chrome.heading,
                )
                Text(
                    temps.minOrNull()?.let { unit.deg(it) } ?: "—°",
                    fontFamily = Mono, fontSize = 20.sp, color = chrome.faint,
                )
            }
        }
        storm?.let {
            Spacer(Modifier.height(8.dp))
            StormChip(it, cfg)
        }
    }
}

@Composable
private fun FactorChip(label: String, value: String?, hue: Color, limiting: Boolean) {
    if (value == null) return
    val chrome = Skin.chrome
    Marked(limiting) {
        Row {
            Text("$label ", fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = hue)
            Text(value, fontFamily = Mono, fontSize = 12.sp, color = chrome.body)
        }
    }
    Text(" · ", fontFamily = Mono, fontSize = 12.sp, color = chrome.faint)
}

/** The limiting factor's marker: a flat band behind the pair, not a box around it. */
@Composable
private fun Marked(on: Boolean, content: @Composable () -> Unit) {
    if (!on) { content(); return }
    Box(Modifier.background(Skin.chrome.marker, RoundedCornerShape(3.dp)).padding(horizontal = 2.dp)) {
        content()
    }
}

@Composable
private fun StormChip(p: ForecastPoint, cfg: AppConfig) {
    val d = Skin.data
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Tokens.lerpLinear(Skin.chrome.raw.card, d.raw.storm, 0.18f).let { Color(it) })
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_wx_thunder), contentDescription = null,
            tint = d.storm, modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "${(p.thunderProb ?: 0.0).roundToInt()}% ${ForecastBuilder.fmtHour(p.time)}",
            fontFamily = Mono, fontSize = 12.sp, color = d.storm,
        )
    }
}

/**
 * §C.3's four layers at phone scale. Because the panel is taller, `AIR` gets its own
 * baseline under the strip instead of sharing the gutter with the temperature labels.
 */
@Composable
private fun ForecastChartCard(points: List<ForecastPoint>, cfg: AppConfig, unit: TempUnit) {
    if (points.isEmpty()) return
    val chrome = Skin.chrome
    val d = Skin.data
    val tm = rememberTextMeasurer()
    val dark = chrome.dark

    Card {
        Canvas(Modifier.fillMaxWidth().height(214.dp)) {
            val x0 = 36.dp.toPx()
            val x1 = size.width
            val plotW = x1 - x0
            val n = points.size
            // Reserved below the plot for the AQI strip AND the baseline that names it —
            // the label sits under the strip here rather than in the gutter, so it needs
            // room of its own or it runs off the canvas.
            val bottom = size.height - 34.dp.toPx()
            val rainH = 44.dp.toPx()
            fun xAt(i: Int) = if (n <= 1) (x0 + x1) / 2 else x0 + i.toFloat() / (n - 1) * plotW

            val temps = points.mapNotNull { it.tempF?.let(unit::fromF) }
            val dews = points.mapNotNull { it.dewPointF?.let(unit::fromF) }
            if (temps.isEmpty()) return@Canvas
            val all = temps + dews
            val lo = all.min() - 3
            val hi = all.max() + 3
            val plotTop = 8.dp.toPx()
            val curveBottom = bottom - rainH
            fun yAt(v: Double): Float {
                val f = ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
                return curveBottom - f * (curveBottom - plotTop)
            }

            // 1. day/night bands + midnight dividers
            val slot = plotW / n
            points.forEachIndexed { i, p ->
                if ((p.isDay ?: true) == chrome.bandOnNight) return@forEachIndexed
                drawRect(chrome.bandTint, Offset(x0 + i * slot, plotTop), Size(slot, bottom - plotTop))
            }
            for (i in 1 until n) {
                if (points[i].time.take(10) == points[i - 1].time.take(10)) continue
                drawLine(chrome.hairline, Offset(xAt(i), plotTop), Offset(xAt(i), bottom), strokeWidth = 1f)
            }

            // 2. exactly three gridlines
            for (v in gridSteps(lo, hi)) {
                val y = yAt(v)
                drawLine(chrome.hairline, Offset(x0, y), Offset(x1, y), strokeWidth = 1f)
                drawText(
                    tm.measure("${v.roundToInt()}°", TextStyle(color = chrome.faint, fontSize = 9.sp, fontFamily = Mono)),
                    topLeft = Offset(0f, y - 6.dp.toPx()),
                )
            }

            // 3. rain columns — height is chance, width is intensity
            var peak = -1
            points.forEachIndexed { i, p ->
                val chance = p.precipProb ?: 0.0
                val amount = p.precipIn ?: 0.0
                if (chance <= 0 && amount <= 0) return@forEachIndexed
                if (peak < 0 || chance > (points[peak].precipProb ?: 0.0)) peak = i
                val intensity = min(1.0, amount / cfg.rainScaleInPerHr.coerceAtLeast(0.01))
                val w = slot * (0.26 + 0.7 * intensity).toFloat()
                val h = max((chance / 100.0).toFloat() * rainH, 2.dp.toPx())
                val cx = xAt(i).coerceIn(x0, x1)
                val storm = (p.thunderProb ?: 0.0) >= cfg.thunderAlertPct
                drawRect(if (storm) d.storm else d.rain, Offset(cx - w / 2, bottom - h), Size(w, h))
            }

            // 4. temperature area + line, dew dashed
            val area = androidx.compose.ui.graphics.Path()
            points.forEachIndexed { i, p ->
                val v = p.tempF?.let(unit::fromF) ?: return@forEachIndexed
                if (area.isEmpty) { area.moveTo(xAt(i), curveBottom); area.lineTo(xAt(i), yAt(v)) }
                else area.lineTo(xAt(i), yAt(v))
            }
            if (!area.isEmpty) {
                area.lineTo(xAt(n - 1), curveBottom)
                area.close()
                drawPath(area, d.temp.copy(alpha = 0.14f))
            }
            line(points, { it.tempF?.let(unit::fromF) }, ::xAt, ::yAt, d.temp, 2.4.dp.toPx(), null)
            line(
                points, { it.dewPointF?.let(unit::fromF) }, ::xAt, ::yAt, d.dew, 2.dp.toPx(),
                PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.4.dp.toPx())),
            )

            // 5. air quality strip, named on its own baseline below
            val aqiSeries = points.map { it.aqi }
            if (aqiSeries.any { it != null }) {
                val (colors, stops) = AqiRamp.sweep(aqiSeries, cfg.aqiPalette, dark)
                val stripY = bottom + 3.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(
                        colorStops = Array(colors.size) { stops[it] to Color(colors[it]) },
                        startX = x0, endX = x1,
                    ),
                    topLeft = Offset(x0, stripY), size = Size(plotW, 5.dp.toPx()),
                )
                drawRect(
                    chrome.hairline, topLeft = Offset(x0, stripY), size = Size(plotW, 5.dp.toPx()),
                    style = Stroke(width = 0.75.dp.toPx()),
                )
                val peakAqi = aqiSeries.filterNotNull().max()
                val label = tm.measure(
                    "AIR $peakAqi",
                    TextStyle(color = d.aqi, fontSize = 11.sp, fontFamily = Mono, fontWeight = FontWeight.SemiBold),
                )
                // Inset from the right so it clears the refresh corner.
                drawText(label, topLeft = Offset(x1 - label.size.width - 30.dp.toPx(), stripY + 7.dp.toPx()))
            }

            // 6. the present, at the real clock time
            val nowFrac = nowFractionOfDay(points)
            if (nowFrac != null) {
                val nx = x0 + nowFrac * plotW
                drawLine(d.now, Offset(nx, plotTop), Offset(nx, bottom), strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}

/** One card per following day (§D). */
@Composable
private fun DayRowCard(date: String, points: List<ForecastPoint>, cfg: AppConfig, unit: TempUnit) {
    val chrome = Skin.chrome
    val d = Skin.data
    val summary = remember(points) { summarize(points) }
    val limiting = remember(points) { limitingFactor(cfg.comfort, points) }
    val ranks = remember(points) {
        dayNightRanks(points, cfg.thunderAlertPct) { ForecastBuilder.hour(it.time) }
    }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dayName(date), Modifier.width(78.dp), fontFamily = Ui, fontSize = 15.sp,
                color = chrome.heading, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(
                painterResource(glyphFor(ranks.first, isDay = true)), contentDescription = null,
                tint = chrome.body, modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                painterResource(glyphFor(ranks.second, isDay = false)), contentDescription = null,
                tint = chrome.body, modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            DayTempSpark(points, cfg, unit, Modifier.weight(1f).height(26.dp))
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    summary.highF?.let { unit.deg(it.toDouble()) } ?: "—°",
                    fontFamily = Mono, fontSize = 15.sp, color = chrome.heading,
                )
                Text(
                    summary.lowF?.let { unit.deg(it.toDouble()) } ?: "—°",
                    fontFamily = Mono, fontSize = 13.sp, color = chrome.faint,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            limiting?.let {
                Text(
                    "${it.factor.label()} ${readingOf(it.factor, it.point) ?: ""}",
                    fontFamily = Mono, fontSize = 11.sp,
                    color = Color(Tokens.factorHue(d.raw, it.factor)),
                )
            }
            Spacer(Modifier.weight(1f))
            summary.maxRainProb?.takeIf { it > 0 }?.let {
                Text("$it% rain", fontFamily = Mono, fontSize = 11.sp, color = d.rain)
            }
        }
    }
}

/** The day's temperature shape, with the hours the windows can be open marked beneath. */
@Composable
private fun DayTempSpark(points: List<ForecastPoint>, cfg: AppConfig, unit: TempUnit, modifier: Modifier) {
    val d = Skin.data
    val threshold = cfg.comfort.openScoreMin
    Canvas(modifier) {
        val temps = points.map { it.tempF?.let(unit::fromF) }
        val known = temps.filterNotNull()
        if (known.isEmpty()) return@Canvas
        val lo = known.min()
        val hi = known.max()
        val n = points.size
        val runH = 3.dp.toPx()
        val bottom = size.height - runH - 1.dp.toPx()
        fun xAt(i: Int) = if (n <= 1) size.width / 2 else i.toFloat() / (n - 1) * size.width
        fun yAt(v: Double) = bottom - (if (hi <= lo) 0.5f else ((v - lo) / (hi - lo)).toFloat()) * bottom

        line(points, { it.tempF?.let(unit::fromF) }, ::xAt, ::yAt, d.temp, 2.dp.toPx(), null)
        val slot = size.width / n
        points.forEachIndexed { i, p ->
            if (p.score < threshold) return@forEachIndexed
            drawRect(d.score, Offset(i * slot, size.height - runH), Size(slot, runH))
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        color = Skin.chrome.card,
        shape = RoundedCornerShape(Tokens.Radius.card),
    ) {
        Column(Modifier.padding(12.dp), content = content)
    }
}

// ---------------------------------------------------------------- helpers

private fun androidx.compose.ui.graphics.drawscope.DrawScope.line(
    points: List<ForecastPoint>, value: (ForecastPoint) -> Double?,
    xAt: (Int) -> Float, yAt: (Double) -> Float,
    color: Color, width: Float, effect: PathEffect?,
) {
    for (i in 0 until points.size - 1) {
        val a = value(points[i]) ?: continue
        val b = value(points[i + 1]) ?: continue
        drawLine(color, Offset(xAt(i), yAt(a)), Offset(xAt(i + 1), yAt(b)), strokeWidth = width, pathEffect = effect)
    }
}

private fun gridSteps(lo: Double, hi: Double): List<Double> {
    val span = hi - lo
    if (span <= 0) return emptyList()
    val raw = span / 4
    val step = listOf(2.0, 5.0, 10.0, 20.0, 25.0).firstOrNull { it >= raw } ?: 50.0
    var v = Math.ceil(lo / step) * step
    val out = mutableListOf<Double>()
    while (v < hi && out.size < 3) { out.add(v); v += step }
    return out
}

private fun glyphFor(rank: WxRank, isDay: Boolean): Int = when (rank) {
    WxRank.THUNDER -> R.drawable.ic_wx_thunder
    WxRank.RAIN -> R.drawable.ic_wx_rain
    WxRank.CLOUDY -> R.drawable.ic_wx_cloudy
    WxRank.PARTLY -> if (isDay) R.drawable.ic_wx_partly_day else R.drawable.ic_wx_partly_night
    WxRank.CLEAR -> if (isDay) R.drawable.ic_wx_clear_day else R.drawable.ic_wx_clear_night
}

private fun conditionLabel(rank: WxRank): String = when (rank) {
    WxRank.THUNDER -> "Storms"
    WxRank.RAIN -> "Rain"
    WxRank.CLOUDY -> "Cloudy"
    WxRank.PARTLY -> "Partly cloudy"
    WxRank.CLEAR -> "Clear"
}

private fun dayName(date: String): String = runCatching {
    val d = LocalDate.parse(date)
    when (d) {
        LocalDate.now().plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("EEE M/d"))
    }
}.getOrDefault(date)

private fun clockOf(ms: Long): String {
    if (ms <= 0) return "—"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    val h12 = (h % 12).let { if (it == 0) 12 else it }
    return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
}

/** Where the present falls across [points], 0..1 — null when the series doesn't contain now. */
private fun nowFractionOfDay(points: List<ForecastPoint>): Float? {
    val prefix = java.time.LocalDateTime.now().toString().take(16)
    val i = points.indexOfLast { it.time.take(16) <= prefix }
    if (i < 0 || points.size <= 1) return null
    val within = java.time.LocalTime.now().minute / 60f
    return ((i + within) / (points.size - 1)).coerceIn(0f, 1f)
}
