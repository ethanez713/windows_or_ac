package com.acwindows

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.unit.dp

/**
 * The design system's colour and geometry tokens (docs/ux-redesign.md §0.1–§0.3), for both
 * the Compose screens and the widget canvases. Everything is an ARGB `Int` because the
 * widgets draw with [android.graphics.Paint]; Compose call sites wrap with `Color(token)`.
 *
 * The split between [Data] and [Chrome] is load-bearing. Chrome is the theme and may be
 * restyled freely. Data colours carry meanings the reader already knows — this hue is the
 * dew point, that one is rain — so they are NOT theme accents and must never be swapped for
 * one. Only their lightness step changes between light and dark, which is why the light
 * values are 700-level rather than the dark theme's 400s: every graphical mark has to clear
 * 3:1 against its own card ground, and the 400s do not on paper.
 */
object Tokens {

    /** Series hues (§0.1). One value per theme; the *meaning* never changes. */
    class Data(
        val temp: Int,
        val dew: Int,
        val aqi: Int,
        val rain: Int,
        val score: Int,
        val now: Int,
        val storm: Int,
        val blockedBase: Int,
        val cutoffRule: Int,
        /** UV readout identity — sunlight, not the WHO ramp (a low index would paint green). */
        val sun: Int,
        /** Cloud-cover readout identity — the halftone's white, darkened on paper. */
        val cloud: Int,
    )

    /** Everything that isn't data (§0.3). This *is* the theme. */
    class Chrome(
        val card: Int,
        val ground: Int,
        val sunken: Int,
        val heading: Int,
        val body: Int,
        val muted: Int,
        val faint: Int,
        val hairline: Int,
        /** Marker band behind the limiting factor in the forecast header (§C.2). */
        val marker: Int,
        /**
         * The day/night band fill, and which half wears it. A small ink lift that must lift
         * AWAY from the ground — a dark tint on light paper, a light tint on dark ground — so
         * the tinted half reads as brighter daylight in either theme; a dark tint on dark
         * ground is invisible.
         */
        val bandTint: Int,
        val bandOnNight: Boolean,
        /**
         * Selection controls, straight from the Ethan design system: the brand orange fill
         * (`--accent`, identical in both themes) under its warm-white label (`--on-accent`),
         * and the warm-grey resting control (`--ink-050` fill, `--ink-200` border, `--ink-600`
         * label). Warm white on orange is 2.8:1 — the system's one sanctioned exception, for
         * control and badge labels only.
         */
        val accent: Int,
        val onAccent: Int,
        val control: Int,
        val controlBorder: Int,
        val controlText: Int,
    )

    private val LIGHT_DATA = Data(
        temp = 0xFFB45309.toInt(),
        dew = 0xFF555299.toInt(),
        aqi = 0xFF15803D.toInt(),
        rain = 0xFF0369A1.toInt(),
        score = 0xFF047857.toInt(),
        now = 0xFF92400E.toInt(),
        storm = 0xFF6D28D9.toInt(),
        blockedBase = 0xFF8893A1.toInt(),
        cutoffRule = argb(115, 62, 56, 48),
        sun = 0xFFA16207.toInt(),
        cloud = 0xFF64748B.toInt(),
    )

    private val DARK_DATA = Data(
        temp = 0xFFFB923C.toInt(),
        dew = 0xFFA9B0DA.toInt(),
        aqi = 0xFF4ADE80.toInt(),
        rain = 0xFF38BDF8.toInt(),
        score = 0xFF34D399.toInt(),
        now = 0xFFFBBF24.toInt(),
        storm = 0xFFA78BFA.toInt(),
        blockedBase = 0xFF606B7A.toInt(),
        cutoffRule = argb(128, 214, 208, 197),
        sun = 0xFFFBBF24.toInt(),
        cloud = CloudRamp.CLOUD,
    )

    private val LIGHT_CHROME = Chrome(
        card = 0xFFFDFCF9.toInt(),
        ground = 0xFFF8F6F1.toInt(),
        sunken = 0xFFF1EDE5.toInt(),
        heading = 0xFF211D18.toInt(),
        body = 0xFF3E3830.toInt(),
        muted = 0xFF5F7080.toInt(),
        faint = 0xFF6B7A8A.toInt(),
        hairline = 0xFFE8E2D8.toInt(),
        marker = 0xFFFED7AA.toInt(),
        bandTint = argb(15, 33, 29, 24),
        bandOnNight = true,
        accent = 0xFFDD7A32.toInt(),
        onAccent = 0xFFFFF8F1.toInt(),
        control = 0xFFF2EFE8.toInt(),
        controlBorder = 0xFFD8D1C5.toInt(),
        controlText = 0xFF574F45.toInt(),
    )

    private val DARK_CHROME = Chrome(
        card = 0xFF232019.toInt(),
        ground = 0xFF1A1712.toInt(),
        sunken = 0xFF12100C.toInt(),
        heading = 0xFFF7F4ED.toInt(),
        body = 0xFFD6D0C5.toInt(),
        muted = 0xFFB4AEA2.toInt(),
        faint = 0xFF9A9385.toInt(),
        hairline = argb(26, 247, 244, 237),
        marker = 0xFF493B31.toInt(),
        bandTint = argb(22, 247, 244, 237),
        bandOnNight = false,
        accent = 0xFFDD7A32.toInt(),
        onAccent = 0xFFFFF8F1.toInt(),
        control = 0xFF2A2620.toInt(),
        controlBorder = 0xFF423D34.toInt(),
        controlText = 0xFFB4AEA2.toInt(),
    )

    fun data(dark: Boolean): Data = if (dark) DARK_DATA else LIGHT_DATA

    fun chrome(dark: Boolean): Chrome = if (dark) DARK_CHROME else LIGHT_CHROME

    fun isDarkTheme(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * A blocked score bar: the neutral [Data.blockedBase] carrying just enough of the
     * limiting factor's hue to say *why* the hour is closed, without ever being mistaken
     * for the vivid open colour. Mixed in linear sRGB so the result doesn't muddy.
     */
    fun blockedBar(d: Data, limitingFactorHue: Int): Int = lerpLinear(d.blockedBase, limitingFactorHue, 0.38f)

    /** Per-factor hue for the blocked-bar tint and the limiting-factor labels. */
    fun factorHue(d: Data, factor: Factor): Int = when (factor) {
        Factor.TEMP -> d.temp
        Factor.DEW -> d.dew
        Factor.AQI -> d.aqi
        Factor.RAIN -> d.rain
    }

    object Radius {
        val card = 10.dp
        val control = 7.dp
        val row = 5.dp
        val widgetCard = 24.dp
    }

    /**
     * Width in dp of a rain mark for an hour's rate (inches that hour). The score widget's
     * rain line stroke, the forecast widget's rain column and the app's rain column all size
     * from this, so one rate reads at one thickness whatever shape draws it. Absolute in/hr
     * thresholds, stepped wide enough to tell apart.
     */
    fun rainMarkDp(inchesPerHour: Double): Float = when {
        inchesPerHour >= 0.3 -> 9f
        inchesPerHour >= 0.1 -> 6f
        inchesPerHour >= 0.05 -> 3f
        else -> 1f
    }

    /** Every graphical mark must clear this against its own card ground (§0.1). */
    const val MIN_MARK_CONTRAST = 3.0

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)

    fun alpha(c: Int) = c ushr 24 and 0xFF
    fun red(c: Int) = c shr 16 and 0xFF
    fun green(c: Int) = c shr 8 and 0xFF
    fun blue(c: Int) = c and 0xFF

    private fun toLinear(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    private fun fromLinear(v: Double): Int {
        val s = if (v <= 0.0031308) v * 12.92 else 1.055 * Math.pow(v, 1 / 2.4) - 0.055
        return (s.coerceIn(0.0, 1.0) * 255).toInt()
    }

    fun lerpLinear(a: Int, b: Int, t: Float): Int {
        fun ch(ca: Int, cb: Int): Int =
            fromLinear(toLinear(ca) + (toLinear(cb) - toLinear(ca)) * t)
        return argb(
            Math.round(alpha(a) + (alpha(b) - alpha(a)) * t),
            ch(red(a), red(b)), ch(green(a), green(b)), ch(blue(a), blue(b)),
        )
    }

    /**
     * A [weights]-proportioned blend of several opaque colours, mixed in linear sRGB so the
     * result doesn't muddy — the forecast score strip mixes each bad factor's hue by how much
     * it's holding the hour back. A zero weight-sum returns the first colour unchanged.
     */
    fun mixLinear(colors: IntArray, weights: DoubleArray): Int {
        val total = weights.sum()
        if (total <= 0.0 || colors.isEmpty()) return colors.firstOrNull() ?: 0
        var r = 0.0; var g = 0.0; var b = 0.0
        for (i in colors.indices) {
            val w = weights[i] / total
            r += toLinear(red(colors[i])) * w
            g += toLinear(green(colors[i])) * w
            b += toLinear(blue(colors[i])) * w
        }
        return argb(255, fromLinear(r), fromLinear(g), fromLinear(b))
    }

    /** Plain per-channel sRGB mix — what the AQI ramp interpolates in (§0.2). */
    fun lerpSrgb(a: Int, b: Int, t: Float): Int = argb(
        Math.round(alpha(a) + (alpha(b) - alpha(a)) * t),
        Math.round(red(a) + (red(b) - red(a)) * t),
        Math.round(green(a) + (green(b) - green(a)) * t),
        Math.round(blue(a) + (blue(b) - blue(a)) * t),
    )

    private fun relLuminance(c: Int): Double =
        0.2126 * toLinear(red(c)) + 0.7152 * toLinear(green(c)) + 0.0722 * toLinear(blue(c))

    /** WCAG contrast ratio, for asserting §0.1's 3:1 floor in tests. */
    fun contrastRatio(fg: Int, bg: Int): Double {
        val a = relLuminance(fg)
        val b = relLuminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
