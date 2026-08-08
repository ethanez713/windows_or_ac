package com.acwindows

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// ── Modern dark palette (slate base + emerald accent, Tailwind-ish) ──
private val Bg = Color(0xFF0E1217)
private val Surface = Color(0xFF161B22)
private val SurfaceHi = Color(0xFF1E242D)
private val SurfaceVariant = Color(0xFF252D38)
private val OnSurface = Color(0xFFE6EAF0)
private val OnSurfaceVariant = Color(0xFF93A1B0)
private val Accent = Color(0xFF34D399)        // emerald-400
private val Outline = Color(0xFF313A45)

// Recommendation/brand colours (kept for the widget palette + future Home tab).
val OpenGreen = Color(0xFF34D399)
val AcBlue = Color(0xFF60A5FA)
val ClosedSlate = Color(0xFF64748B)
val ComfortTeal = Color(0xFF2DD4BF)
val UnknownRed = Color(0xFFF87171)

// Score-bar colours (open vs. below-threshold).
val BarOpen = Accent
val BarClosed = Color(0xFF3A434F)

/**
 * Colour for a below-threshold ("don't open the windows") score bar: its dominant detractor's
 * hue pulled hard toward the neutral [BarClosed] grey. The result reads as unmistakably grey at
 * a glance — clearly NOT the vivid green of an open-window bar — while a faint tint still hints
 * at *why* the hour is closed. [BarClosed] itself is the fallback when no detractor is known.
 */
fun detractorGrey(factor: Color): Color = lerp(BarClosed, factor, 0.28f)

// Extreme-heat flag: any hour at/above this °F is drawn red (sparkline bar + temp line).
const val HOT_F = 90.0
val HotRed = Color(0xFFEF4444)   // red-500

// Per-factor line colours — distinct, semantically apt, modern.
val FactorTemp = Color(0xFFFB923C)   // orange-400     (warmth)
val FactorHumid = Color(0xFFA5B4FC)  // periwinkle     (moisture)
val FactorAqi = Color(0xFF4ADE80)    // green-400      (clean air)
val FactorRain = Color(0xFF22D3EE)   // cyan-400       (water)

// Neutral colour for the resulting score line/area (kept distinct from the green AQI line).
val ScoreInk = Color(0xFFCBD5E1)     // slate-300

// "Now" marker on today's graphs — a warm amber, distinct from the grey scrub cursor.
val NowInk = Color(0xFFFBBF24)       // amber-400

// Inset panel behind each detail chart's plot area (a touch darker than the card).
val ChartPanel = Color(0xFF11161D)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF06281C),
    secondary = ComfortTeal,
    background = Bg,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outlineVariant = Outline,
    error = UnknownRed,
)

/**
 * Always-dark, modern theme for the app. [textScale] is the app text-size setting
 * (60%–200%, default 1f) folded into the ambient font scale rather than threaded through
 * every `fontSize` call site — it rides on top of the system's own accessibility font
 * scale (multiplied, not replaced) and only touches `sp`, so `dp` layout is unaffected.
 * That does mean fixed-`Dp` chart heights and [SkeletonForecast]'s hand-measured scaffold
 * don't grow with it — see that composable's doc comment.
 */
@Composable
fun AcWindowsTheme(textScale: Float = 1f, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = d.density, fontScale = d.fontScale * textScale),
    ) {
        MaterialTheme(colorScheme = DarkColors, content = content)
    }
}

/** Card surface a touch lighter than the background. */
val SurfaceElevated = SurfaceHi

/** The brand colour for a recommendation (shared with the widget palette). */
fun actionColor(action: Action): Color = when (action) {
    Action.OPEN_WINDOWS -> OpenGreen
    Action.RUN_AC -> AcBlue
    Action.KEEP_CLOSED -> ClosedSlate
    Action.COMFORTABLE -> ComfortTeal
    Action.UNKNOWN -> UnknownRed
}
