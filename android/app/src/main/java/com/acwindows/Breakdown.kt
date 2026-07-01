package com.acwindows

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * One score factor, described once and reused everywhere: the factor-contribution
 * line on the chart, the legend chip, and the tooltip's absolute value all read
 * from the same spec, so they can never drift apart.
 *
 *  - [multiplierOf] → the 0..1 contribution (what the line plots, ×100).
 *  - [valueOf]      → the human-readable raw value (what the tooltip shows).
 */
data class FactorSpec(
    val label: String,
    val color: Color,
    val multiplierOf: (ScoreBreakdown) -> Double,
    val valueOf: (ForecastPoint) -> String,
)

val FACTORS: List<FactorSpec> = listOf(
    FactorSpec("Temp", FactorTemp, { it.fTemp }, { p -> p.tempF?.let { "${it.roundToInt()}°F" } ?: "—" }),
    FactorSpec("Humidity", FactorHumid, { it.fHumid }, { p -> p.dewPointF?.let { "dew ${it.roundToInt()}°F" } ?: "—" }),
    FactorSpec("Air quality", FactorAqi, { it.fAqi }, { p -> p.aqi?.let { "AQI $it" } ?: "—" }),
    FactorSpec("Rain", FactorRain, { it.fRain }, { p -> p.precipProb?.let { "${it.roundToInt()}% rain" } ?: "—" }),
)
