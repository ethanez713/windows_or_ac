package com.acwindows

/**
 * Cloud cover as a gradient from clear sky-blue at 0% to overcast white-grey at 100%, so a
 * bar's colour reads as "how much sky is left" at a glance. A single linear sRGB interpolation
 * between the two ends; the strip's own outline keeps the pale overcast end visible on either
 * theme's card. The endpoints are nudged per theme only enough to stay off the card colour.
 */
object CloudRamp {

    private val CLEAR = intArrayOf(0xFF4AA8E0.toInt(), 0xFF52ABE0.toInt())   // light, dark
    private val OVERCAST = intArrayOf(0xFFCED4DA.toInt(), 0xFFBCC3CB.toInt())

    fun colorAt(pct: Double, dark: Boolean = true): Int {
        val i = if (dark) 1 else 0
        return Tokens.lerpSrgb(CLEAR[i], OVERCAST[i], (pct / 100.0).toFloat().coerceIn(0f, 1f))
    }

    /** Hours with no reading hold the last known colour rather than dropping to clear. */
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
