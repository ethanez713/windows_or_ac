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
    val sun = Color(t.sun)
    val cloud = Color(t.cloud)
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
    val accent = Color(t.accent)
    val onAccent = Color(t.onAccent)
    val control = Color(t.control)
    val controlBorder = Color(t.controlBorder)
    val controlText = Color(t.controlText)
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

/** Temperature line and hot score bars. Clears 3:1 on both the light paper and the dark card. */
val HotRed = Color(0xFFEF4444)
val HotRedArgb = 0xFFEF4444.toInt()

/**
 * Cold mark for the temperature line. White on a dark card. (Cold score bars use the ice
 * blue [Tokens.Data.coldBar] instead.)
 * Pure white on the light paper (#FDFCF9) is invisible, so that theme uses the heading ink.
 */
fun coldMarkArgb(dark: Boolean): Int = if (dark) 0xFFFFFFFF.toInt() else 0xFF211D18.toInt()

/** [highColor] at/above [highF], [lowColor] at/below [lowF], else [base]. */
fun segmentTintArgb(
    midF: Double, base: Int, highF: Double, lowF: Double, highColor: Int, lowColor: Int,
): Int = when {
    midF >= highF -> highColor
    midF <= lowF -> lowColor
    else -> base
}

/**
 * The temperature line's color for one reading. A heat-limited score bar uses this same
 * result, so the bar is red only when the line is red. (A cold-limited bar is ice blue.)
 */
fun tempLineArgb(tempF: Double, c: Comfort, baseArgb: Int, coldArgb: Int): Int =
    segmentTintArgb(tempF, baseArgb, c.tempLineHotF, c.tempLineColdF, HotRedArgb, coldArgb)
