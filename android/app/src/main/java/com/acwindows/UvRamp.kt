package com.acwindows

/**
 * The WHO UV-index colour ramp — green (low) through yellow, orange and red to violet
 * (extreme) — drawn as a continuous gradient across the hour axis, the same treatment
 * [AqiRamp] gives air quality. Interpolated in plain sRGB between category breakpoints.
 *
 * Night hours are not on this ramp: they take [CloudRamp.CLEAR_NIGHT], the same near-black
 * as the cloud bar, so dusk is one sky rather than a leftover green "UV 0".
 *
 * The light variant darkens the green and yellow steps, which are near-invisible at full
 * chroma on an off-white card, the same trade [AqiRamp] makes.
 */
object UvRamp {

    private val BREAKS = doubleArrayOf(0.0, 3.0, 6.0, 8.0, 11.0)

    private val DARK = intArrayOf(
        0xFF3EC65B.toInt(), 0xFFF4D400.toInt(), 0xFFFF8A00.toInt(),
        0xFFFF3B30.toInt(), 0xFFB05CE6.toInt(),
    )
    private val LIGHT = intArrayOf(
        0xFF2E9E4F.toInt(), 0xFFB89400.toInt(), 0xFFE06A00.toInt(),
        0xFFD02418.toInt(), 0xFF8E3FD0.toInt(),
    )

    private fun stops(dark: Boolean) = if (dark) DARK else LIGHT

    fun colorAt(uv: Double, dark: Boolean = true): Int {
        val s = stops(dark)
        if (uv <= BREAKS.first()) return s.first()
        if (uv >= BREAKS.last()) return s.last()
        for (i in 0 until BREAKS.size - 1) {
            val lo = BREAKS[i]
            val hi = BREAKS[i + 1]
            if (uv <= hi) return Tokens.lerpSrgb(s[i], s[i + 1], ((uv - lo) / (hi - lo)).toFloat())
        }
        return s.last()
    }

    /**
     * Per-hour colour across the axis. Daylight follows the WHO ramp; night hours
     * ([isDay] == false) are [CloudRamp.CLEAR_NIGHT], matching the cloud bar. A missing
     * [isDay] is daylight, the same default [CloudRamp.skySweep] uses. Hours with no UV
     * reading hold the last known ramp colour rather than dropping to green — but a night
     * hour still goes black, even if the held reading is a daytime index.
     */
    fun sweep(
        series: List<Double?>,
        isDay: List<Boolean?>,
        dark: Boolean = true,
    ): Pair<IntArray, FloatArray> {
        if (series.isEmpty()) {
            val flat = colorAt(0.0, dark)
            return intArrayOf(flat, flat) to floatArrayOf(0f, 1f)
        }
        var last = series.firstOrNull { it != null } ?: 0.0
        val colors = IntArray(series.size)
        series.forEachIndexed { i, v ->
            if (v != null) last = v
            colors[i] = if (isDay.getOrNull(i) ?: true) colorAt(last, dark) else CloudRamp.CLEAR_NIGHT
        }
        val denom = (series.size - 1).coerceAtLeast(1).toFloat()
        return colors to FloatArray(series.size) { it / denom }
    }
}
