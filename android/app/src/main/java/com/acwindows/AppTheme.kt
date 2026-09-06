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
import androidx.compose.ui.graphics.toArgb
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

class ChromeColors(t: Tokens.Chrome) {
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
private val LocalChrome = staticCompositionLocalOf { ChromeColors(Tokens.chrome(dark = true)) }

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
    val chrome = ChromeColors(Tokens.chrome(dark))
    CompositionLocalProvider(
        LocalDensity provides Density(density = d.density, fontScale = d.fontScale * textScale),
        LocalData provides data,
        LocalChrome provides chrome,
    ) {
        MaterialTheme(colorScheme = schemeOf(chrome.raw, data.raw, dark), content = content)
    }
}

// Recommendation/brand colours — these name an ACTION, not a data series, so they are
// chrome and follow the theme's accent rather than §0.1.
val OpenGreen = Color(0xFF34D399)
val AcBlue = Color(0xFF60A5FA)
val ClosedSlate = Color(0xFF64748B)
val ComfortTeal = Color(0xFF2DD4BF)
val UnknownRed = Color(0xFFF87171)

/** The brand colour for a recommendation (shared with the widget palette). */
fun actionColor(action: Action): Color = when (action) {
    Action.OPEN_WINDOWS -> OpenGreen
    Action.RUN_AC -> AcBlue
    Action.KEEP_CLOSED -> ClosedSlate
    Action.COMFORTABLE -> ComfortTeal
    Action.UNKNOWN -> UnknownRed
}

/** Extreme-heat flag: any hour at/above this °F is drawn red (sparkline bar + temp line). */
const val HOT_F = 90.0
val HotRed = Color(0xFFEF4444)

// Pre-token colour names, still used by MainActivity, Breakdown and the widget providers.
// Each is the dark-theme step of its §0.1 token; call sites move to `Skin.data` / `Tokens`
// as they are redesigned, and these go with the last of them.
private val darkData = Tokens.data(dark = true)
val FactorTemp = Color(darkData.temp)
val FactorHumid = Color(darkData.dew)
val FactorAqi = Color(darkData.aqi)
val FactorRain = Color(darkData.rain)
val ScoreInk = Color(0xFFCBD5E1)
val NowInk = Color(darkData.now)
val ChartPanel = Color(0xFF11161D)
val BarOpen = Color(darkData.score)
val BarClosed = Color(darkData.blockedBase)
val SurfaceElevated = Color(0xFF1E242D)

fun detractorGrey(factor: Color): Color =
    Color(Tokens.blockedBar(darkData, factor.toArgb()))
