package com.acwindows

import android.content.Context

/** How much of the forecast panel is drawn. See [ForecastPanel]. */
enum class ForecastDetail { COMPACT, STANDARD, EVERYTHING }

/** Which palette step the panel draws, independent of the phone's own setting. */
enum class ForecastTheme { AUTO, LIGHT, DARK }

/**
 * Per-widget-instance settings for the forecast widget (docs/ux-redesign.md §C.5). Several
 * can be placed at once showing different horizons, so every key carries the widget id it
 * belongs to. The key namespace is deliberately its own — the windows widget keeps its
 * settings, and the two must not read each other's.
 */
data class ForecastWidgetConfig(
    /** Look-ahead, always starting at the current hour. */
    val hours: Int = 24,
    val detail: ForecastDetail = ForecastDetail.STANDARD,
    val theme: ForecastTheme = ForecastTheme.AUTO,
    /** Drop rain columns below the comfort threshold rather than drawing near-zero stubs. */
    val hideUnlikelyRain: Boolean = true,
    /** Off means the widget refreshes where it stands instead of opening the app. */
    val tapOpensApp: Boolean = false,
) {
    /** Compact drops the dew line and the AQI strip — the two layers a small panel can lose. */
    val showDew: Boolean get() = detail != ForecastDetail.COMPACT
    val showAqi: Boolean get() = detail != ForecastDetail.COMPACT

    /** Everything adds the marks that are informative but not load-bearing. */
    val showDateFlags: Boolean get() = detail == ForecastDetail.EVERYTHING

    fun isDark(context: Context): Boolean = when (theme) {
        ForecastTheme.AUTO -> Tokens.isDarkTheme(context)
        ForecastTheme.LIGHT -> false
        ForecastTheme.DARK -> true
    }

    companion object {
        private const val PREFS = "ac_windows"
        val HOUR_CHOICES = listOf(12, 24, 36)

        fun load(context: Context, id: Int): ForecastWidgetConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            fun <T : Enum<T>> enum(key: String, values: Array<T>, fallback: T): T =
                p.getString("${key}_$id", null)
                    ?.let { raw -> values.firstOrNull { it.name == raw } } ?: fallback
            return ForecastWidgetConfig(
                hours = p.getString("forecastHours_$id", null)?.toIntOrNull()
                    ?.takeIf { it in HOUR_CHOICES } ?: 24,
                detail = enum("forecastDetail", ForecastDetail.entries.toTypedArray(), ForecastDetail.STANDARD),
                theme = enum("forecastTheme", ForecastTheme.entries.toTypedArray(), ForecastTheme.AUTO),
                hideUnlikelyRain = p.getBoolean("forecastHideRain_$id", true),
                tapOpensApp = p.getBoolean("forecastTapOpens_$id", false),
            )
        }

        fun save(context: Context, id: Int, cfg: ForecastWidgetConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("forecastHours_$id", cfg.hours.toString())
                .putString("forecastDetail_$id", cfg.detail.name)
                .putString("forecastTheme_$id", cfg.theme.name)
                .putBoolean("forecastHideRain_$id", cfg.hideUnlikelyRain)
                .putBoolean("forecastTapOpens_$id", cfg.tapOpensApp)
                .apply()
        }

        /** Drop this widget id's keys, so a removed widget doesn't leak settings forever. */
        fun delete(context: Context, id: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("forecastHours_$id")
                .remove("forecastDetail_$id")
                .remove("forecastTheme_$id")
                .remove("forecastHideRain_$id")
                .remove("forecastTapOpens_$id")
                .apply()
        }
    }
}
