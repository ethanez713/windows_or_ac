package com.acwindows

/**
 * Data containers, mirroring the Python backend's models.py so the two stay
 * conceptually in sync. The Python project remains the canonical "brain"; this
 * is a faithful on-device port for the widget.
 */

/**
 * Comfort target. Each factor has a configurable penalty window: it scores 1.0
 * at/below its "ideal" and 0.0 at/above its cutoff, ramping linearly between.
 */
data class Comfort(
    val tempIdealF: Double = 76.0,        // temp: comfortable up to here
    val maxTempF: Double = 78.0,          // temp: ventilation can't win at/above
    val dewPointIdealF: Double = 60.0,    // humidity: not muggy up to here
    val dewPointMaxF: Double = 65.0,      // humidity: too muggy at/above
    val aqiIdeal: Double = 100.0,         // air: clean up to here
    val aqiMax: Int = 150,                // air: unhealthy at/above
    val rainProbIdeal: Double = 20.0,     // rain: fine up to this % chance
    val rainProbMax: Double = 70.0,       // rain: likely at/above
    // Forecast / scoring (see Score.kt + README "Methodology").
    val forecastHours: Int = 48,          // widget look-ahead
    val appForecastDays: Int = 14,        // full-app look-ahead
    val openScoreMin: Double = 60.0,
)

/** A scored hour with every factor exposed, so the reasoning is auditable. */
data class ScoreBreakdown(
    val score: Double,    // 0..100, higher = better to have windows open
    val fTemp: Double,    // each factor is a 0..1 multiplier (1 = not limiting)
    val fHumid: Double,
    val fAqi: Double,
    val fRain: Double,
)

/** One source's reading. A null field means that source didn't supply it. */
data class SourceReading(
    val source: String,
    val tempF: Double? = null,
    val dewPointF: Double? = null,
    val humidityPct: Double? = null,
    val apparentTempF: Double? = null,
    val precipMm: Double? = null,
    val windMph: Double? = null,
    val aqi: Int? = null,
    val ok: Boolean = true,
    val error: String? = null,
)

/** Cross-source consensus that the decision engine consumes. */
data class Consensus(
    val tempF: Double? = null,
    val dewPointF: Double? = null,
    val humidityPct: Double? = null,
    val apparentTempF: Double? = null,
    val precipMm: Double? = null,
    val windMph: Double? = null,
    val aqi: Int? = null,
    val confidence: String = "none",   // high | degraded | conflict | none
    val tempSpreadF: Double = 0.0,
    val sourcesOk: List<String> = emptyList(),
    val sourcesFailed: List<String> = emptyList(),
    val sourcesAttempted: Int = 0,   // total sources kicked off (the X in the "N/X sources" count),
                                     // stable across progressive frames while ok/failed grow toward it
)

enum class Action { OPEN_WINDOWS, RUN_AC, KEEP_CLOSED, COMFORTABLE, UNKNOWN }

data class Recommendation(
    val action: Action,
    val headline: String,
    val summary: String,
    val reasons: List<String>,
)

/** One scored hour of the look-ahead window. */
data class ForecastPoint(
    val time: String,          // local ISO "2026-06-15T21:00"
    val score: Double,         // Open-Window Score 0..100
    val tempF: Double? = null,
    val dewPointF: Double? = null,
    val aqi: Int? = null,
    val precipProb: Double? = null,          // % chance of precipitation
    val precipIn: Double? = null,            // forecast precipitation amount, inches
    val thunderProb: Double? = null,         // % chance of thunder this hour, when a source supplies one
    val weatherCode: Int? = null,            // WMO weather code (for the summary icons)
    val isDay: Boolean? = null,              // true sunrise→sunset, false overnight (for graph shading)
    val shortwaveWm2: Double? = null,
    val nwsTempF: Double? = null,
    val breakdown: ScoreBreakdown? = null,   // per-factor detail for the app UI
)

/** Next N hours of scores plus the best time to open up. */
data class Forecast(
    val points: List<ForecastPoint> = emptyList(),
    val bestStart: String? = null,
    val bestEnd: String? = null,
    val bestAvgScore: Double? = null,
    val tempAgreement: String = "none",
    val notes: List<String> = emptyList(),
    /**
     * Wall-clock span, in minutes, that each [ForecastPoint] covers — 60 for the
     * hourly series, 15 for [AppState.nearTerm]. Any code that turns a *count* of
     * points into a *duration*, or sums a per-step accumulation (e.g. precipIn),
     * must go through this rather than assuming one point = one hour.
     */
    val stepMinutes: Int = 60,
)

/** Everything one refresh produces: the now-consensus and the look-ahead. */
data class WeatherResult(val consensus: Consensus, val forecast: Forecast)

/**
 * Home / device state — indoor conditions and controllable equipment.
 *
 * SEAM FOR PHASE 2: today every field is null/unknown (weather-only). A future
 * Google Home thermostat source fills indoor temp + setpoint, and an attic-fan
 * source fills [atticFanOn]. See [DeviceSource]. The app already reserves a
 * "Home / Devices" UI slot that stays hidden while this is empty.
 */
data class HomeState(
    val indoorTempF: Double? = null,
    val thermostatSetpointF: Double? = null,
    val thermostatMode: String? = null,   // e.g. "heat" | "cool" | "off"
    val atticFanOn: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = indoorTempF == null && thermostatSetpointF == null &&
            thermostatMode == null && atticFanOn == null
}

/** Full app state: outdoor weather/forecast plus (future) home/device data. */
data class AppState(
    val consensus: Consensus,
    val forecast: Forecast,
    val home: HomeState = HomeState(),
    val sources: List<SourceReading> = emptyList(),   // per-source readings, for the sources detail page
    /**
     * 15-minute-resolution look-ahead from now, covering the next ~24h; null when
     * unavailable (fetch failed or hasn't landed yet). This is a SEPARATE series from
     * [forecast], not a replacement for it or a splice into [Forecast.points] — the
     * two have different resolutions (15 min vs 60 min) and different start times
     * (now vs the top of the current hour), and mixing them into one list is exactly
     * what this field exists to avoid. The hourly [forecast] remains the source of
     * truth for anything spanning more than a day or two out.
     */
    val nearTerm: Forecast? = null,
)
