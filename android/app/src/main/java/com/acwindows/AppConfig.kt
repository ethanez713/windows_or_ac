package com.acwindows

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.time.ZoneId
import kotlin.math.abs

/**
 * What today's card plots: the full day (hourly, same as every other card), or a
 * "now → end of day" view built from the 15-minute [AppState.nearTerm] series. Persisted
 * under the "dayViewMode" pref key as [DayViewMode.name] — see [AppConfig.saveDayViewMode].
 */
enum class DayViewMode { TODAY, NOW }

/**
 * App configuration: your home location and comfort target.
 *
 * The DEFAULT_* constants and [Comfort]'s defaults are a first-run *seed*, not a live
 * source — see [Companion.freezeDefaults]. Once an install has been seeded its settings
 * live on the device, so shipping a new APK can't move numbers out from under someone.
 */
data class AppConfig(
    val lat: Double,
    val lon: Double,
    val timezone: String,
    val userAgent: String,
    val comfort: Comfort,
    val secrets: Secrets = Secrets(),     // user-pasted provider API keys (see SecretsStore)
    val sourceWeights: Map<String, Double> = emptyMap(),  // per-source trust weight (default 1.0)
    val placeName: String? = null,        // label for lat/lon (device fix or address lookup), or null
    val useDeviceLocation: Boolean = false,  // follow the phone: re-read lat/lon before every fetch
    val widgetOpacity: Int = 100,         // widget background opacity, 0–100%
    val widgetForecastHours: Int = 36,    // how many hours the widget graph spans
    // What the widget graph draws. Turning a layer off doesn't stop it being fetched or
    // cached — it's purely how busy you want the chart (see Graph.render).
    val widgetShowBars: Boolean = true,   // the Open-Window Score bars
    val widgetShowTemp: Boolean = true,   // temperature line (°F axis)
    val widgetShowDew: Boolean = true,    // dew point line (same °F axis)
    val widgetShowRain: Boolean = true,   // rain-chance line, thickness = rain amount
    val widgetShowThreshold: Boolean = true,  // dashed rule + label at the open-score cutoff
    val appTextScalePct: Int = 100,       // app-wide text scale, 60–200% (widget has its own)
    val dayViewMode: DayViewMode = DayViewMode.TODAY,  // today's card: full day, or now→EOD
    val appShowHourlyRows: Boolean = false,  // hour-by-hour value strip above the Values charts
    // Shared by every surface that draws the forecast — the widget, its config preview and
    // the app's drawer — so a reading means the same thing wherever it is drawn.
    val aqiPalette: AqiPalette = AqiPalette.EPA,
    val rainScaleInPerHr: Double = 0.50,   // in/hr that draws a full-width rain column
    val thunderAlertPct: Double = 50.0,    // storm treatment at/above this thunder chance
) {
    /**
     * The zone the hourly forecast timestamps are expressed in — the same one sent to
     * Open-Meteo, so a row's "2026-08-02T14:00" can be turned back into an instant.
     * Falls back to the device zone for "auto" or an unparseable name.
     */
    fun zone(): ZoneId = runCatching {
        if (timezone.isBlank() || timezone == "auto") ZoneId.systemDefault() else ZoneId.of(timezone)
    }.getOrDefault(ZoneId.systemDefault())

    companion object {
        // Every source the consensus can see — keyless first, then the optional keyed ones.
        // Used for the per-source trust weights (weighted-median consensus).
        val KNOWN_SOURCES = listOf("open-meteo", "nws", "metno", "tomorrowio", "pirateweather", "airnow")
        const val DEFAULT_LAT = 40.7128            // your latitude (look it up at latlong.net)
        const val DEFAULT_LON = -74.0060           // your longitude
        const val DEFAULT_TZ = "America/New_York"  // "auto" or an IANA tz name
        const val DEFAULT_UA = "ac-widget/0.1 (your-email@example.com)" // NWS asks for a contact
        // Comfort / decision setpoints seeded on first run:
        const val DEFAULT_MAX_TEMP_F = 78.0        // don't let the house get warmer than this
        const val DEFAULT_OPEN_SCORE_MIN = 60.0    // open windows when the score is ≥ this (0-100)
        const val DEFAULT_WIDGET_HOURS = 36        // widget graph look-ahead (configurable)
        const val DEFAULT_RAIN_SCALE = 0.50        // in/hr drawing a full-width rain column
        const val DEFAULT_THUNDER_ALERT = 50.0     // % thunder chance that earns storm treatment
        // The rest live in Comfort() (dew point 60/65°F, AQI 100/150).

        fun load(context: Context): AppConfig {
            val p = context.getSharedPreferences("ac_windows", Context.MODE_PRIVATE)
            freezeDefaults(p)
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
                placeName = p.getString("placeName", null)?.takeIf { it.isNotBlank() },
                useDeviceLocation = p.getBoolean("useDeviceLocation", false),
                widgetOpacity = p.getString("widgetOpacity", null)?.toIntOrNull()?.coerceIn(0, 100) ?: 100,
                widgetForecastHours = p.getString("widgetForecastHours", null)?.toIntOrNull()
                    ?.coerceIn(6, 96) ?: DEFAULT_WIDGET_HOURS,
                widgetShowBars = p.getBoolean("widgetShowBars", true),
                widgetShowTemp = p.getBoolean("widgetShowTemp", true),
                widgetShowDew = p.getBoolean("widgetShowDew", true),
                widgetShowRain = p.getBoolean("widgetShowRain", true),
                widgetShowThreshold = p.getBoolean("widgetShowThreshold", true),
                appTextScalePct = p.getString("appTextScalePct", null)?.toIntOrNull()?.coerceIn(60, 200) ?: 100,
                dayViewMode = p.getString("dayViewMode", null)
                    ?.let { name -> runCatching { DayViewMode.valueOf(name) }.getOrNull() }
                    ?: DayViewMode.TODAY,
                appShowHourlyRows = p.getBoolean("appShowHourlyRows", false),
                aqiPalette = p.getString("aqiPalette", null)
                    ?.let { name -> runCatching { AqiPalette.valueOf(name) }.getOrNull() }
                    ?: AqiPalette.EPA,
                rainScaleInPerHr = p.getString("rainScaleInPerHr", null)?.toDoubleOrNull()
                    ?.coerceIn(0.15, 0.60) ?: DEFAULT_RAIN_SCALE,
                thunderAlertPct = p.getString("thunderAlertPct", null)?.toDoubleOrNull()
                    ?.coerceIn(10.0, 70.0) ?: DEFAULT_THUNDER_ALERT,
            )
        }

        /**
         * A tunable's first-run value, plus — when a release changes what we ship —
         * [supersedes], the previously-shipped number this one is allowed to replace.
         */
        private class Seed(val key: String, val value: String, val supersedes: Double? = null)

        private fun seeds(): List<Seed> {
            val base = Comfort()
            return listOf(
                Seed("tempIdealF", base.tempIdealF.toString()),
                Seed("maxTempF", DEFAULT_MAX_TEMP_F.toString()),
                Seed("openScoreMin", DEFAULT_OPEN_SCORE_MIN.toString()),
                Seed("dewPointIdealF", base.dewPointIdealF.toString(), supersedes = 55.0),
                Seed("dewPointMaxF", base.dewPointMaxF.toString(), supersedes = 60.0),
                Seed("aqiIdeal", base.aqiIdeal.toString(), supersedes = 50.0),
                Seed("aqiMax", base.aqiMax.toString(), supersedes = 100.0),
                Seed("rainProbIdeal", base.rainProbIdeal.toString()),
                Seed("rainProbMax", base.rainProbMax.toString()),
                Seed("appTextScalePct", "100"),
                Seed("widgetOpacity", "100"),
                Seed("widgetForecastHours", DEFAULT_WIDGET_HOURS.toString()),
                Seed("rainScaleInPerHr", DEFAULT_RAIN_SCALE.toString()),
                Seed("thunderAlertPct", DEFAULT_THUNDER_ALERT.toString()),
                Seed("lat", DEFAULT_LAT.toString()),
                Seed("lon", DEFAULT_LON.toString()),
            )
        }

        /**
         * Write the shipped defaults into the prefs the first time an install reads its
         * config, so from then on every tunable is answered from the device rather than
         * from whatever the current build happens to be compiled with. Without this a new
         * APK silently moves the settings of anyone who never opened the settings screen.
         *
         * [SETTINGS_VERSION] is the deliberate escape hatch for actually changing a shipped
         * default: bumping it re-offers each seed, but only overwrites a value still sitting
         * on the number that seed [Seed.supersedes] — anything the user chose is left alone.
         * Adding a seed for a brand-new key needs no bump; an unset key is always seeded.
         */
        private fun freezeDefaults(p: SharedPreferences) {
            if (p.getInt("settingsVersion", 0) >= SETTINGS_VERSION) return
            val e = p.edit()
            seeds().forEach { seed ->
                val stored = p.getString(seed.key, null)?.toDoubleOrNull()
                if (stored == null || stored == seed.supersedes) e.putString(seed.key, seed.value)
            }
            e.putInt("settingsVersion", SETTINGS_VERSION).apply()
        }

        /** 1: comfort window widened — dew point 55/60 → 60/65 °F, AQI 50/100 → 100/150. */
        private const val SETTINGS_VERSION = 1

        /**
         * The card's own tap toggle writes this directly (no trip through the settings
         * screen), so a switch made from the forecast list takes effect immediately without
         * waiting on a settings save. [ConfigActivity]'s checkbox writes the same key.
         */
        fun saveDayViewMode(context: Context, mode: DayViewMode) {
            context.getSharedPreferences("ac_windows", Context.MODE_PRIVATE).edit()
                .putString("dayViewMode", mode.name)
                .apply()
        }

        /**
         * The header's location chip writes this directly, the same way [saveDayViewMode]
         * does, so a choice made from the forecast list takes effect on the next refresh
         * without a trip through the settings screen. [ConfigActivity]'s checkbox writes
         * the same key.
         */
        fun saveUseDeviceLocation(context: Context, on: Boolean) {
            context.getSharedPreferences("ac_windows", Context.MODE_PRIVATE).edit()
                .putBoolean("useDeviceLocation", on)
                .apply()
        }

        /**
         * [load], plus — when "use my phone's location" is on — a fresh device fix before
         * the weather fetch, so the forecast follows you instead of being pinned to
         * wherever you last opened settings. Every refresh path (widget worker and app)
         * goes through here, so there is one place that decides what "here" means.
         *
         * Degrades to the last saved coordinates whenever the fix can't be had — the widget
         * keeps working rather than going blank.
         *
         * Note that "before every refresh" is best-effort from the widget's background
         * worker. Without ACCESS_BACKGROUND_LOCATION, Android hands a backgrounded app no
         * fix at all, so out there this usually falls through to the saved coordinates; the
         * position really moves when the app is open (or its settings screen is used). That
         * permission is deliberately NOT requested: Play gates it behind a separate review
         * with a video demonstration, which is a steep price for a widget that only needs
         * to know which town you're in. The saved pair is written on every successful
         * resolve, so the widget still tracks you — one app launch behind.
         */
        suspend fun loadResolved(context: Context): AppConfig {
            val cfg = load(context)
            if (!cfg.useDeviceLocation || !LocationProvider.hasPermission(context)) return cfg
            // Short leash: this runs ahead of every weather fetch, and in the background
            // no fix is coming at all, so the settings screen's patient 12s budget would
            // just stall each refresh for 12s before falling back to the saved coordinates.
            val loc = LocationProvider.currentLocation(context, timeoutMs = 5_000) ?: run {
                Log.i(TAG, "device location unavailable; using last saved coordinates")
                return cfg
            }
            val lat = round4(loc.latitude)
            val lon = round4(loc.longitude)
            // Re-geocoding on every 10-minute tick is wasted work (and battery) when you
            // haven't gone anywhere, so only relabel once the fix has actually moved.
            val moved = abs(lat - cfg.lat) > RELABEL_DEG || abs(lon - cfg.lon) > RELABEL_DEG
            val place = if (moved || cfg.placeName == null)
                LocationProvider.placeName(context, lat, lon) else cfg.placeName
            saveLocation(context, lat, lon, place)
            return cfg.copy(lat = lat, lon = lon, placeName = place)
        }

        /**
         * Persist a resolved location. Shared by [loadResolved] and the settings screen so
         * both write the same three keys — the saved pair doubles as the fallback when
         * device location later becomes unavailable.
         */
        fun saveLocation(context: Context, lat: Double, lon: Double, placeName: String?) {
            context.getSharedPreferences("ac_windows", Context.MODE_PRIVATE).edit()
                .putString("lat", lat.toString())
                .putString("lon", lon.toString())
                .putString("placeName", placeName ?: "")
                .apply()
        }

        /** ~11 m — enough precision for weather, and no more of your position than needed. */
        fun round4(d: Double): Double = Math.round(d * 10000.0) / 10000.0

        private const val TAG = "AcWindows"

        /** ~1.1 km: how far the fix must move before the place label is looked up again. */
        private const val RELABEL_DEG = 0.01
    }
}
