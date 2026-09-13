package com.acwindows

/**
 * The clear-sky backdrop the cloud bar is painted on: cyan by day, near-black violet by
 * night, chosen per hour from `isDay` (the sky's colour is objective, not theme-dependent).
 * Cloud cover is NOT mixed into this — it is laid over the top as a halftone dot screen (see
 * `halftone` in Drawer.kt), so the strip reads as sky with clouds drawn on it, [CLOUD] being
 * the dots' white-grey.
 */
object CloudRamp {

    private const val CLEAR_DAY = 0xFF12B6DC.toInt()      // cyan sky
    private const val CLEAR_NIGHT = 0xFF150A24.toInt()    // near-black violet — a clear night sky
    const val CLOUD = 0xFFDCE1E6.toInt()                  // halftone dot colour (white-grey cloud)

    private fun sky(isDay: Boolean?) = if (isDay ?: true) CLEAR_DAY else CLEAR_NIGHT

    /** Per-hour clear-sky colour as a horizontal-gradient sweep; adjacent hours blend at dusk/dawn. */
    fun skySweep(isDay: List<Boolean?>): Pair<IntArray, FloatArray> {
        if (isDay.isEmpty()) {
            val c = sky(true)
            return intArrayOf(c, c) to floatArrayOf(0f, 1f)
        }
        val colors = IntArray(isDay.size) { sky(isDay[it]) }
        val denom = (isDay.size - 1).coerceAtLeast(1).toFloat()
        return colors to FloatArray(isDay.size) { it / denom }
    }
}
