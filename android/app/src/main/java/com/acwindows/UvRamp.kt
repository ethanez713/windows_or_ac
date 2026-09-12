package com.acwindows

/**
 * The WHO UV-index colour ramp — green (low) through yellow, orange and red to violet
 * (extreme) — drawn as a continuous gradient across the hour axis, the same treatment
 * [AqiRamp] gives air quality. Interpolated in plain sRGB between category breakpoints.
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

    /** Hours with no reading hold the last known colour rather than dropping to green. */
    fun sweep(series: List<Double?>, dark: Boolean = true): Pair<IntArray, FloatArray> {
        val known = series.filterNotNull()
        if (known.isEmpty()) {
            val flat = colorAt(0.0, dark)
            return intArrayOf(flat, flat) to floatArrayOf(0f, 1f)
        }
        var last = known.first()
        val colors = IntArray(series.size)
        series.forEachIndexed { i, v ->
            if (v != null) last = v
            colors[i] = colorAt(last, dark)
        }
        val denom = (series.size - 1).coerceAtLeast(1).toFloat()
        return colors to FloatArray(series.size) { it / denom }
    }
}
