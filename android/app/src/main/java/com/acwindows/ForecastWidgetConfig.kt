package com.acwindows

import android.content.Context

/** Which palette step the panel draws, independent of the phone's own setting. */
enum class ForecastTheme { AUTO, LIGHT, DARK }

/**
 * Per-widget-instance settings for the forecast widget (docs/ux-redesign.md §C.5). Several
 * can be placed at once showing different horizons, so every key carries the widget id it
 * belongs to. The key namespace is deliberately its own — the windows widget keeps its
 * settings, and the two must not read each other's.
 *
 * Each of the four series is toggled on its own rather than picked from a preset ladder, so
 * the panel can be a thermometer, an air-quality strip, a rain chart, or any mix.
 */
data class ForecastWidgetConfig(
    /** Look-ahead, always starting at the current hour. */
    val hours: Int = DEFAULT_HOURS,
    val showTemp: Boolean = true,
    val showDew: Boolean = true,
    val showAqi: Boolean = true,
    val showRain: Boolean = true,
    /** The Open-Window Score as a colour strip along the bottom; opt-in, so off by default. */
    val showScore: Boolean = false,
    /** UV index and cloud-cover strips (the app drawer's bars); opt-in, so off by default. */
    val showUv: Boolean = false,
    val showCloud: Boolean = false,
    /**
     * Day/night shading from local sunrise/sunset, plus a solid grey line at each midnight
     * and a dotted grey one at each noon (unlabelled). This is what differentiates the days,
     * in place of date ticks, so it's on by default.
     */
    val showDayNight: Boolean = true,
    val theme: ForecastTheme = ForecastTheme.AUTO,
    /** Drop rain columns below the comfort threshold rather than drawing near-zero stubs. */
    val hideUnlikelyRain: Boolean = true,
    /** Off means the widget refreshes where it stands instead of opening the app. */
    val tapOpensApp: Boolean = false,
    /** Scales every label the panel draws (see [ForecastPanel]); the chart itself is unchanged. */
    val textScalePct: Int = DEFAULT_TEXT_SCALE,
) {
    fun isDark(context: Context): Boolean = when (theme) {
        ForecastTheme.AUTO -> Tokens.isDarkTheme(context)
        ForecastTheme.LIGHT -> false
        ForecastTheme.DARK -> true
    }

    companion object {
        private const val PREFS = "ac_windows"

        /** Below 3 the chart has nothing to shape; above 96 no source forecasts hourly. */
        const val MIN_HOURS = 3
        const val MAX_HOURS = 96
        const val DEFAULT_HOURS = 24

        const val MIN_TEXT_SCALE = 80
        const val MAX_TEXT_SCALE = 200
        const val DEFAULT_TEXT_SCALE = 130

        fun clampHours(h: Int) = h.coerceIn(MIN_HOURS, MAX_HOURS)
        fun clampTextScale(p: Int) = p.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)

        fun load(context: Context, id: Int): ForecastWidgetConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            fun bool(key: String, fallback: Boolean) = p.getBoolean("${key}_$id", fallback)
            return ForecastWidgetConfig(
                hours = p.getString("forecastHours_$id", null)?.toIntOrNull()
                    ?.let(::clampHours) ?: DEFAULT_HOURS,
                showTemp = bool("forecastShowTemp", true),
                showDew = bool("forecastShowDew", true),
                showAqi = bool("forecastShowAqi", true),
                showRain = bool("forecastShowRain", true),
                showScore = bool("forecastShowScore", false),
                showUv = bool("forecastShowUv", false),
                showCloud = bool("forecastShowCloud", false),
                showDayNight = bool("forecastDayNight", true),
                theme = p.getString("forecastTheme_$id", null)
                    ?.let { raw -> ForecastTheme.entries.firstOrNull { it.name == raw } } ?: ForecastTheme.AUTO,
                hideUnlikelyRain = bool("forecastHideRain", true),
                tapOpensApp = bool("forecastTapOpens", false),
                textScalePct = p.getString("forecastTextScale_$id", null)?.toIntOrNull()
                    ?.let(::clampTextScale) ?: DEFAULT_TEXT_SCALE,
            )
        }

        fun save(context: Context, id: Int, cfg: ForecastWidgetConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("forecastHours_$id", cfg.hours.toString())
                .putBoolean("forecastShowTemp_$id", cfg.showTemp)
                .putBoolean("forecastShowDew_$id", cfg.showDew)
                .putBoolean("forecastShowAqi_$id", cfg.showAqi)
                .putBoolean("forecastShowRain_$id", cfg.showRain)
                .putBoolean("forecastShowScore_$id", cfg.showScore)
                .putBoolean("forecastShowUv_$id", cfg.showUv)
                .putBoolean("forecastShowCloud_$id", cfg.showCloud)
                .putBoolean("forecastDayNight_$id", cfg.showDayNight)
                .putString("forecastTheme_$id", cfg.theme.name)
                .putBoolean("forecastHideRain_$id", cfg.hideUnlikelyRain)
                .putBoolean("forecastTapOpens_$id", cfg.tapOpensApp)
                .putString("forecastTextScale_$id", cfg.textScalePct.toString())
                .apply()
        }

        /** Drop this widget id's keys, so a removed widget doesn't leak settings forever. */
        fun delete(context: Context, id: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("forecastHours_$id")
                .remove("forecastShowTemp_$id")
                .remove("forecastShowDew_$id")
                .remove("forecastShowAqi_$id")
                .remove("forecastShowRain_$id")
                .remove("forecastShowScore_$id")
                .remove("forecastShowUv_$id")
                .remove("forecastShowCloud_$id")
                .remove("forecastDayNight_$id")
                .remove("forecastTheme_$id")
                .remove("forecastHideRain_$id")
                .remove("forecastTapOpens_$id")
                .remove("forecastTextScale_$id")
                .apply()
        }
    }
}
