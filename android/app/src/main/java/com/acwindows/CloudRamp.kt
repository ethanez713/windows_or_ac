package com.acwindows

/**
 * Cloud cover as the colour of the sky itself: a clear hour is sky-blue by day and a near-black
 * violet by night, and cloud fills that in toward overcast white-grey as cover rises — so the
 * strip reads as "what the sky looks like this hour", with white always meaning cloud. A single
 * linear sRGB interpolation between the hour's clear-sky colour and overcast; the day/night end
 * is chosen per hour from [isDay], not from the app theme, because the sky's colour is objective.
 */
object CloudRamp {

    private const val CLEAR_DAY = 0xFF4AA8E0.toInt()      // sky blue
    private const val CLEAR_NIGHT = 0xFF150A24.toInt()    // near-black violet — a clear night sky
    private const val OVERCAST = 0xFFCED4DA.toInt()        // white-grey cloud

    fun colorAt(pct: Double, isDay: Boolean): Int =
        Tokens.lerpSrgb(if (isDay) CLEAR_DAY else CLEAR_NIGHT, OVERCAST, (pct / 100.0).toFloat().coerceIn(0f, 1f))

    /** Hours with no cover reading hold the last known cover; each hour's own day/night sets the clear end. */
    fun sweep(cover: List<Double?>, isDay: List<Boolean?>): Pair<IntArray, FloatArray> {
        if (cover.all { it == null }) {
            val flat = colorAt(0.0, true)
            return intArrayOf(flat, flat) to floatArrayOf(0f, 1f)
        }
        var last = cover.firstOrNull { it != null } ?: 0.0
        val colors = IntArray(cover.size)
        cover.forEachIndexed { i, v ->
            if (v != null) last = v
            colors[i] = colorAt(last, isDay.getOrNull(i) ?: true)
        }
        val denom = (cover.size - 1).coerceAtLeast(1).toFloat()
        return colors to FloatArray(cover.size) { it / denom }
    }
}
