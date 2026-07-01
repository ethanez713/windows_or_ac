package com.acwindows

import android.content.Context

/**
 * App configuration: your home location and comfort target.
 *
 * MVP: edit the DEFAULT_* constants below and rebuild. (Values are also read
 * from SharedPreferences first, so a future in-app settings screen can override
 * them without a rebuild.)
 */
data class AppConfig(
    val lat: Double,
    val lon: Double,
    val timezone: String,
    val userAgent: String,
    val comfort: Comfort,
    val secrets: Secrets = Secrets(),     // user-pasted provider API keys (see SecretsStore)
    val sourceWeights: Map<String, Double> = emptyMap(),  // per-source trust weight (default 1.0)
    val widgetOpacity: Int = 100,         // widget background opacity, 0–100%
    val widgetForecastHours: Int = 36,    // how many hours the widget graph spans
) {
    companion object {
        // Every source the consensus can see — keyless first, then the optional keyed ones.
        // Used for the per-source trust weights (weighted-median consensus).
        val KNOWN_SOURCES = listOf("open-meteo", "nws", "metno", "tomorrowio", "pirateweather", "airnow")
        // ───────── EDIT THESE FOR YOUR HOME ─────────
        const val DEFAULT_LAT = 40.7128            // your latitude (look it up at latlong.net)
        const val DEFAULT_LON = -74.0060           // your longitude
        const val DEFAULT_TZ = "America/New_York"  // "auto" or an IANA tz name
        const val DEFAULT_UA = "ac-widget/0.1 (your-email@example.com)" // NWS asks for a contact
        // Comfort / decision setpoints (also editable here, then rebuild):
        const val DEFAULT_MAX_TEMP_F = 78.0        // don't let the house get warmer than this
        const val DEFAULT_OPEN_SCORE_MIN = 60.0    // open windows when the score is ≥ this (0-100)
        const val DEFAULT_WIDGET_HOURS = 36        // widget graph look-ahead (configurable)
        // Other comfort defaults live in Comfort() (dew point max 60°F, AQI max 100).
        // ────────────────────────────────────────────

        fun load(context: Context): AppConfig {
            val p = context.getSharedPreferences("ac_windows", Context.MODE_PRIVATE)
            val base = Comfort()
            return AppConfig(
                lat = p.getString("lat", null)?.toDoubleOrNull() ?: DEFAULT_LAT,
                lon = p.getString("lon", null)?.toDoubleOrNull() ?: DEFAULT_LON,
                timezone = p.getString("tz", null) ?: DEFAULT_TZ,
                userAgent = p.getString("ua", null) ?: DEFAULT_UA,
                comfort = Comfort(
                    tempIdealF = p.getString("tempIdealF", null)?.toDoubleOrNull() ?: base.tempIdealF,
                    maxTempF = p.getString("maxTempF", null)?.toDoubleOrNull() ?: DEFAULT_MAX_TEMP_F,
                    dewPointIdealF = p.getString("dewPointIdealF", null)?.toDoubleOrNull() ?: base.dewPointIdealF,
                    dewPointMaxF = p.getString("dewPointMaxF", null)?.toDoubleOrNull() ?: base.dewPointMaxF,
                    aqiIdeal = p.getString("aqiIdeal", null)?.toDoubleOrNull() ?: base.aqiIdeal,
                    aqiMax = p.getString("aqiMax", null)?.toIntOrNull() ?: base.aqiMax,
                    rainProbIdeal = p.getString("rainProbIdeal", null)?.toDoubleOrNull() ?: base.rainProbIdeal,
                    rainProbMax = p.getString("rainProbMax", null)?.toDoubleOrNull() ?: base.rainProbMax,
                    openScoreMin = p.getString("openScoreMin", null)?.toDoubleOrNull() ?: DEFAULT_OPEN_SCORE_MIN,
                ),
                secrets = Secrets.load(context),
                sourceWeights = KNOWN_SOURCES.associateWith { s ->
                    p.getString("weight_$s", null)?.toDoubleOrNull()?.coerceIn(0.0, 10.0) ?: 1.0
                },
                widgetOpacity = p.getString("widgetOpacity", null)?.toIntOrNull()?.coerceIn(0, 100) ?: 100,
                widgetForecastHours = p.getString("widgetForecastHours", null)?.toIntOrNull()
                    ?.coerceIn(6, 96) ?: DEFAULT_WIDGET_HOURS,
            )
        }
    }
}
