package com.acwindows

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Maps WMO weather codes (Open-Meteo's `weather_code`) onto the bundled
 * Material Symbols weather glyphs (Apache-2.0, vector drawables under res/drawable),
 * with a sky-appropriate tint. Used by the collapsed day summary.
 *
 * Codes: 0 clear · 1-2 partly · 3 overcast · 45/48 fog · 51-67/80-82 rain ·
 * 71-77/85-86 snow · 95-99 thunder. See README "Methodology".
 */
data class WxGlyph(val res: Int, val tint: Color)

private val ClearDay = Color(0xFFFBBF24)    // amber-400
private val ClearNight = Color(0xFFC7D2FE)  // indigo-200
private val CloudGray = Color(0xFF94A3B8)   // slate-400
private val RainCyan = Color(0xFF38BDF8)    // sky-400
private val SnowBlue = Color(0xFFBAE6FD)    // sky-200
private val StormViolet = Color(0xFFA78BFA) // violet-400

fun wxGlyph(code: Int?, isDay: Boolean): WxGlyph = when (code) {
    null -> WxGlyph(R.drawable.ic_wx_cloudy, CloudGray)
    0, 1 -> if (isDay) WxGlyph(R.drawable.ic_wx_clear_day, ClearDay)
            else WxGlyph(R.drawable.ic_wx_clear_night, ClearNight)
    2 -> if (isDay) WxGlyph(R.drawable.ic_wx_partly_day, ClearDay)
         else WxGlyph(R.drawable.ic_wx_partly_night, ClearNight)
    3 -> WxGlyph(R.drawable.ic_wx_cloudy, CloudGray)
    45, 48 -> WxGlyph(R.drawable.ic_wx_fog, CloudGray)
    in 71..77, 85, 86 -> WxGlyph(R.drawable.ic_wx_snow, SnowBlue)
    95, 96, 99 -> WxGlyph(R.drawable.ic_wx_thunder, StormViolet)
    else -> WxGlyph(R.drawable.ic_wx_rain, RainCyan)  // 51-67, 80-82 + any unmapped wet code
}

@Composable
fun WeatherGlyph(code: Int?, isDay: Boolean, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val g = wxGlyph(code, isDay)
    Image(
        painter = painterResource(g.res),
        contentDescription = null,
        colorFilter = ColorFilter.tint(g.tint),
        modifier = modifier.size(size),
    )
}

// ───────────────────────── Per-day weather summary ─────────────────────────

/**
 * The compact "weather report" a collapsed day card shows on the right: morning and
 * night conditions, the day's high/low, and rain. Hours are local; "morning" is the
 * point nearest 9 AM (daytime icon), "night" the point nearest 9 PM (night icon).
 */
data class DaySummary(
    val morningCode: Int?,
    val nightCode: Int?,
    val highF: Int?,
    val lowF: Int?,
    val maxRainProb: Int?,
    val totalPrecipIn: Double,
)

fun summarize(points: List<ForecastPoint>): DaySummary {
    val temps = points.mapNotNull { it.tempF }
    val nearestCodeTo = { target: Int ->
        points.filter { it.weatherCode != null }
            .minByOrNull { kotlin.math.abs(ForecastBuilder.hour(it.time) - target) }
            ?.weatherCode
    }
    return DaySummary(
        morningCode = nearestCodeTo(9),
        nightCode = nearestCodeTo(21),
        highF = temps.maxOrNull()?.roundToInt(),
        lowF = temps.minOrNull()?.roundToInt(),
        maxRainProb = points.mapNotNull { it.precipProb }.maxOrNull()?.roundToInt(),
        totalPrecipIn = points.sumOf { it.precipIn ?: 0.0 },
    )
}
