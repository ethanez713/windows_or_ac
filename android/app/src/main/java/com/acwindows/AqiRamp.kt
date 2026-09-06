package com.acwindows

/**
 * The AQI colour ramp (docs/ux-redesign.md §0.2): a continuous gradient across the hour
 * axis rather than banded columns, so a day that drifts from 40 to 90 reads as a drift
 * instead of a step. Interpolated in plain sRGB between adjacent breakpoints.
 */
enum class AqiPalette { EPA, ETHAN, INK }

object AqiRamp {

    /** The EPA category breakpoints every palette is anchored on. */
    private val BREAKS = intArrayOf(0, 50, 100, 150, 200, 300)

    /**
     * The published EPA colours fix hue but not lightness: #00E400 and #FFFF00 measure
     * 1.05:1 on off-white, so the light ramp darkens the green and yellow steps to clear
     * the 3:1 floor while keeping the categories recognisable.
     */
    private val EPA_LIGHT = intArrayOf(
        0xFF00A400.toInt(), 0xFFA68A00.toInt(), 0xFFE86A00.toInt(),
        0xFFE00000.toInt(), 0xFF8F3F97.toInt(), 0xFF7E0023.toInt(),
    )
    private val EPA_DARK = intArrayOf(
        0xFF00E400.toInt(), 0xFFFFFF00.toInt(), 0xFFFF7E00.toInt(),
        0xFFFF0000.toInt(), 0xFFB060BC.toInt(), 0xFFC2335B.toInt(),
    )

    /**
     * An OkLCH ramp from jade through amber to hazard. The hue path is shorter than the
     * EPA one and passes through orange and red on its own, so the ramp stays perceptually
     * even instead of jumping at each category edge. Sampled from OkLCH at the same
     * breakpoints so the runtime only ever mixes sRGB.
     */
    private val ETHAN_LIGHT = intArrayOf(
        0xFF00855B.toInt(), 0xFF788B00.toInt(), 0xFFD07C00.toInt(),
        0xFFCA5D00.toInt(), 0xFFC13900.toInt(), 0xFFA50000.toInt(),
    )
    private val ETHAN_DARK = intArrayOf(
        0xFF37C695.toInt(), 0xFFB3C948.toInt(), 0xFFFFB92F.toInt(),
        0xFFFF9A11.toInt(), 0xFFFF7A10.toInt(), 0xFFEE343B.toInt(),
    )

    /**
     * Density only, no hue — the ramp to pick when colour can't be relied on. It starts at
     * a mid-tone rather than near the card, because the clean end of the scale still has to
     * clear the 3:1 floor: the ramp trades some of its range for being visible at all.
     */
    private val INK_LIGHT = intArrayOf(
        0xFF919191.toInt(), 0xFF787878.toInt(), 0xFF5F5F5F.toInt(),
        0xFF464646.toInt(), 0xFF2D2D2D.toInt(), 0xFF141414.toInt(),
    )
    private val INK_DARK = intArrayOf(
        0xFF6C6C6C.toInt(), 0xFF888888.toInt(), 0xFFA4A4A4.toInt(),
        0xFFBFBFBF.toInt(), 0xFFDBDBDB.toInt(), 0xFFF7F7F7.toInt(),
    )

    private fun stops(palette: AqiPalette, dark: Boolean): IntArray = when (palette) {
        AqiPalette.EPA -> if (dark) EPA_DARK else EPA_LIGHT
        AqiPalette.ETHAN -> if (dark) ETHAN_DARK else ETHAN_LIGHT
        AqiPalette.INK -> if (dark) INK_DARK else INK_LIGHT
    }

    /** The ramp colour for one AQI reading. Below 50 stays visually flat, as the EPA scale intends. */
    fun colorAt(aqi: Double, palette: AqiPalette = AqiPalette.EPA, dark: Boolean = true): Int {
        val s = stops(palette, dark)
        if (aqi <= BREAKS.first()) return s.first()
        if (aqi >= BREAKS.last()) return s.last()
        for (i in 0 until BREAKS.size - 1) {
            val lo = BREAKS[i]
            val hi = BREAKS[i + 1]
            if (aqi <= hi) {
                val t = ((aqi - lo) / (hi - lo)).toFloat()
                return Tokens.lerpSrgb(s[i], s[i + 1], t)
            }
        }
        return s.last()
    }

    /** EPA category name, for the strip's right-hand eyebrow ("peak 71 moderate"). */
    fun category(aqi: Double): String = when {
        aqi <= 50 -> "good"
        aqi <= 100 -> "moderate"
        aqi <= 150 -> "unhealthy for some"
        aqi <= 200 -> "unhealthy"
        aqi <= 300 -> "very unhealthy"
        else -> "hazardous"
    }

    /**
     * The gradient across an hour series, as the `colors` + `positions` pair a
     * [android.graphics.LinearGradient] or a Compose brush wants. Hours with no reading
     * hold the last known colour rather than dropping to green, which would read as clean
     * air that was never measured.
     */
    fun sweep(series: List<Int?>, palette: AqiPalette = AqiPalette.EPA, dark: Boolean = true): Pair<IntArray, FloatArray> {
        val known = series.filterNotNull()
        if (known.isEmpty()) {
            val flat = colorAt(0.0, palette, dark)
            return intArrayOf(flat, flat) to floatArrayOf(0f, 1f)
        }
        var last = known.first().toDouble()
        val colors = IntArray(series.size)
        series.forEachIndexed { i, v ->
            if (v != null) last = v.toDouble()
            colors[i] = colorAt(last, palette, dark)
        }
        val denom = (series.size - 1).coerceAtLeast(1).toFloat()
        val positions = FloatArray(series.size) { it / denom }
        return colors to positions
    }
}
