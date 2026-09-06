package com.acwindows

import android.content.Context
import android.graphics.Color

/**
 * Per-element widget colours + text scale, staged separately from [AppConfig] so this
 * file can be edited without touching it. Reads/writes the same `ac_windows`
 * SharedPreferences everything else uses — just its own key namespace.
 *
 * Every colour here is already RESOLVED: unset elements come back carrying the design
 * system's value (§0.1/§0.3) for whichever theme the phone is in, so a widget nobody has
 * customised follows light/dark on its own. Only a colour the user actually chose is
 * stored, which is what [save] enforces — writing back a value that merely matches the
 * current theme's token would silently pin the widget to that theme.
 *
 * Colours are `#RRGGBB` on disk (no alpha; opacity is handled elsewhere via
 * [AppConfig.widgetOpacity]) so they round-trip through a plain hex EditText.
 */
data class WidgetTheme(
    val useActionBackground: Boolean = false, // opt in to a card tinted by the recommendation
    val background: Int = DEFAULTS.background, // used only when useActionBackground is false
    val barOpen: Int = DEFAULTS.barOpen,
    val barClosed: Int = DEFAULTS.barClosed,
    val temp: Int = DEFAULTS.temp,
    val dew: Int = DEFAULTS.dew,
    val rain: Int = DEFAULTS.rain,
    val text: Int = DEFAULTS.text,
    val textScalePct: Int = 100,
) {
    /** The colour every element takes when the user hasn't chosen one. */
    class Defaults(dark: Boolean) {
        private val d = Tokens.data(dark)
        private val c = Tokens.chrome(dark)
        val background = c.card
        val barOpen = d.score
        val barClosed = d.blockedBase
        val temp = d.temp
        val dew = d.dew
        val rain = d.rain
        val text = c.body
    }

    companion object {
        private const val PREFS = "ac_windows"

        /** Compile-time stand-in for [defaults]; the real one needs a Context to know the theme. */
        private val DEFAULTS = Defaults(dark = true)

        val DEFAULT = WidgetTheme()

        fun defaults(context: Context) = Defaults(Tokens.isDarkTheme(context))

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
            val def = defaults(context)
            fun color(key: String, fallback: Int) = parseHex(p.getString(key, null), fallback)
            return WidgetTheme(
                useActionBackground = p.getBoolean("widgetUseActionBackground", false),
                background = color("widgetColorBackground", def.background),
                barOpen = color("widgetColorBarOpen", def.barOpen),
                barClosed = color("widgetColorBarClosed", def.barClosed),
                temp = color("widgetColorTemp", def.temp),
                dew = color("widgetColorDew", def.dew),
                rain = color("widgetColorRain", def.rain),
                text = color("widgetColorText", def.text),
                textScalePct = p.getString("widgetTextScalePct", null)?.toIntOrNull()
                    ?.coerceIn(50, 200) ?: 100,
            )
        }

        fun save(context: Context, theme: WidgetTheme) {
            val def = defaults(context)
            val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            // Storing a colour that equals the current theme's token would freeze the widget
            // to this theme; leaving the key unset is what lets it keep following the system.
            fun color(key: String, value: Int, default: Int) =
                if (value == default) e.remove(key) else e.putString(key, hex(value))
            e.putBoolean("widgetUseActionBackground", theme.useActionBackground)
            color("widgetColorBackground", theme.background, def.background)
            color("widgetColorBarOpen", theme.barOpen, def.barOpen)
            color("widgetColorBarClosed", theme.barClosed, def.barClosed)
            color("widgetColorTemp", theme.temp, def.temp)
            color("widgetColorDew", theme.dew, def.dew)
            color("widgetColorRain", theme.rain, def.rain)
            color("widgetColorText", theme.text, def.text)
            e.putString("widgetTextScalePct", theme.textScalePct.toString())
            e.apply()
        }
    }
}
