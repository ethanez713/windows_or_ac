package com.acwindows

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The app's Compose face of [Tokens]. The token values live there because the widget
 * canvases need them as plain ARGB ints; this file only wraps them and decides which of
 * the two sets is in force.
 */
class DataColors(t: Tokens.Data) {
    val temp = Color(t.temp)
    val dew = Color(t.dew)
    val aqi = Color(t.aqi)
    val rain = Color(t.rain)
    val score = Color(t.score)
    val now = Color(t.now)
    val storm = Color(t.storm)
    val blockedBase = Color(t.blockedBase)
    val cutoffRule = Color(t.cutoffRule)
    val raw = t
}

class ChromeColors(t: Tokens.Chrome, val dark: Boolean) {
    val card = Color(t.card)
    val ground = Color(t.ground)
    val sunken = Color(t.sunken)
    val heading = Color(t.heading)
    val body = Color(t.body)
    val muted = Color(t.muted)
    val faint = Color(t.faint)
    val hairline = Color(t.hairline)
    val marker = Color(t.marker)
    val bandTint = Color(t.bandTint)
    val bandOnNight = t.bandOnNight
    val raw = t
}

private val LocalData = staticCompositionLocalOf { DataColors(Tokens.data(dark = true)) }
private val LocalChrome = staticCompositionLocalOf { ChromeColors(Tokens.chrome(dark = true), dark = true) }

/** `Skin.data.dew`, `Skin.chrome.card` — the two token sets, resolved for the active theme. */
object Skin {
    val data: DataColors
        @Composable @ReadOnlyComposable get() = LocalData.current
    val chrome: ChromeColors
        @Composable @ReadOnlyComposable get() = LocalChrome.current
}

private fun schemeOf(c: Tokens.Chrome, d: Tokens.Data, dark: Boolean) =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = Color(d.score),
        onPrimary = Color(c.card),
        background = Color(c.ground),
        onBackground = Color(c.heading),
        surface = Color(c.card),
        onSurface = Color(c.heading),
        surfaceVariant = Color(c.sunken),
        onSurfaceVariant = Color(c.muted),
        outlineVariant = Color(c.hairline),
        error = Color(d.storm),
    )

/**
 * [textScale] is the app text-size setting (60%–200%, default 1f) folded into the ambient
 * font scale rather than threaded through every `fontSize` call site — it rides on top of
 * the system's own accessibility font scale (multiplied, not replaced) and only touches
 * `sp`, so `dp` layout is unaffected. That does mean fixed-`Dp` chart heights and
 * [SkeletonForecast]'s hand-measured scaffold don't grow with it — see that composable's
 * doc comment.
 */
@Composable
fun AcWindowsTheme(
    textScale: Float = 1f,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val d = LocalDensity.current
    val data = DataColors(Tokens.data(dark))
    val chrome = ChromeColors(Tokens.chrome(dark), dark)
    CompositionLocalProvider(
        LocalDensity provides Density(density = d.density, fontScale = d.fontScale * textScale),
        LocalData provides data,
        LocalChrome provides chrome,
    ) {
        MaterialTheme(colorScheme = schemeOf(chrome.raw, data.raw, dark), content = content)
    }
}

// Source-confidence colours. These name a STATUS, not a data series, so §0.1 doesn't
// govern them — see confidenceColor in MainActivity.
val OpenGreen = Color(0xFF34D399)
val AcBlue = Color(0xFF60A5FA)
val ClosedSlate = Color(0xFF64748B)
val UnknownRed = Color(0xFFF87171)

/**
 * Extreme-temperature flags for the temperature line and sparkline: an hour at/above [HOT_F]
 * is drawn red, at/below [COLD_F] blue. Both hues clear the §0.1 3:1 mark-contrast floor on
 * the light paper and the dark card; the cold hue is a cyan kept clear of the rain blue.
 */
const val HOT_F = 90.0
const val COLD_F = 32.0
val HotRed = Color(0xFFEF4444)
val ColdBlue = Color(0xFF0891B2)

// The same two, as plain ARGB ints for the widget canvases (android.graphics, no Compose).
val HotRedArgb = 0xFFEF4444.toInt()
val ColdBlueArgb = 0xFF0891B2.toInt()
