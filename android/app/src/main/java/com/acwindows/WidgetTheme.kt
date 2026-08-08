package com.acwindows

import android.content.Context
import android.graphics.Color

/**
 * Per-element widget colours + text scale, staged separately from [AppConfig] so this
 * file can be edited without touching it. Reads/writes the same `ac_windows`
 * SharedPreferences everything else uses — just its own key namespace.
 *
 * Colours are stored as `#RRGGBB` strings (no alpha; opacity is handled elsewhere via
 * [AppConfig.widgetOpacity]) so they round-trip through a plain hex EditText.
 */
data class WidgetTheme(
    val useActionBackground: Boolean = true,  // background follows the recommendation colour
    val background: Int = DEFAULT_BACKGROUND, // used only when useActionBackground is false
    val barOpen: Int = DEFAULT_BAR_OPEN,
    val barClosed: Int = DEFAULT_BAR_CLOSED,
    val temp: Int = DEFAULT_TEMP,
    val dew: Int = DEFAULT_DEW,
    val rain: Int = DEFAULT_RAIN,
    val text: Int = DEFAULT_TEXT,
    val textScalePct: Int = 100,
) {
    companion object {
        private const val PREFS = "ac_windows"

        private const val DEFAULT_BACKGROUND = 0xFF3E6B4C.toInt()  // OPEN_WINDOWS colour, only a fallback
        private const val DEFAULT_BAR_OPEN = 0xFFE8F5E9.toInt()
        private const val DEFAULT_BAR_CLOSED = 0xFF3A434F.toInt()
        private const val DEFAULT_TEMP = 0xFFFB923C.toInt()
        private const val DEFAULT_DEW = 0xFFA5B4FC.toInt()
        private const val DEFAULT_RAIN = 0xFF7DD3FC.toInt()
        private const val DEFAULT_TEXT = 0xFFFFFFFF.toInt()

        val DEFAULT = WidgetTheme()

        /**
         * Accepts `#RRGGBB` or `RRGGBB` (case-insensitive); anything else — unset, too
         * short, non-hex — falls back rather than throwing, so a half-typed settings
         * field never crashes the widget.
         */
        fun parseHex(s: String?, fallback: Int): Int {
            val hex = s?.removePrefix("#") ?: return fallback
            if (hex.length != 6) return fallback
            val rgb = hex.toIntOrNull(16) ?: return fallback
            return Color.rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        }

        /** Opaque `#RRGGBB` — alpha is dropped, colours here never carry their own opacity. */
        fun hex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

        fun load(context: Context): WidgetTheme {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return WidgetTheme(
                useActionBackground = p.getBoolean("widgetUseActionBackground", true),
                background = parseHex(p.getString("widgetColorBackground", null), DEFAULT_BACKGROUND),
                barOpen = parseHex(p.getString("widgetColorBarOpen", null), DEFAULT_BAR_OPEN),
                barClosed = parseHex(p.getString("widgetColorBarClosed", null), DEFAULT_BAR_CLOSED),
                temp = parseHex(p.getString("widgetColorTemp", null), DEFAULT_TEMP),
                dew = parseHex(p.getString("widgetColorDew", null), DEFAULT_DEW),
                rain = parseHex(p.getString("widgetColorRain", null), DEFAULT_RAIN),
                text = parseHex(p.getString("widgetColorText", null), DEFAULT_TEXT),
                textScalePct = p.getString("widgetTextScalePct", null)?.toIntOrNull()
                    ?.coerceIn(50, 200) ?: 100,
            )
        }

        fun save(context: Context, theme: WidgetTheme) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("widgetUseActionBackground", theme.useActionBackground)
                .putString("widgetColorBackground", hex(theme.background))
                .putString("widgetColorBarOpen", hex(theme.barOpen))
                .putString("widgetColorBarClosed", hex(theme.barClosed))
                .putString("widgetColorTemp", hex(theme.temp))
                .putString("widgetColorDew", hex(theme.dew))
                .putString("widgetColorRain", hex(theme.rain))
                .putString("widgetColorText", hex(theme.text))
                .putString("widgetTextScalePct", theme.textScalePct.toString())
                .apply()
        }
    }
}
