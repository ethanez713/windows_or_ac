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
 *
 * [valueOf] takes the display [TempUnit] so the two temperature-based factors can render
 * in the user's unit here, in the one place that owns their formatting. Converting them
 * at the call site instead would mean matching on [label] to spot which factors are
 * temperatures — exactly the drift this type exists to prevent. The other two factors
 * carry no unit and ignore it.
 */
data class FactorSpec(
    val label: String,
    val color: Color,
    val multiplierOf: (ScoreBreakdown) -> Double,
    val valueOf: (ForecastPoint, TempUnit) -> String,
)

val FACTORS: List<FactorSpec> = listOf(
    FactorSpec("Temp", FactorTemp, { it.fTemp }, { p, u -> p.tempF?.let { u.degUnit(it) } ?: "—" }),
    FactorSpec("Humidity", FactorHumid, { it.fHumid }, { p, u -> p.dewPointF?.let { "dew ${u.degUnit(it)}" } ?: "—" }),
    FactorSpec("Air quality", FactorAqi, { it.fAqi }, { p, _ -> p.aqi?.let { "AQI $it" } ?: "—" }),
    FactorSpec("Rain", FactorRain, { it.fRain }, { p, _ -> p.precipProb?.let { "${it.roundToInt()}% rain" } ?: "—" }),
)
