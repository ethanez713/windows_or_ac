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

    /**
     * The dot radius (as a ratio of grid spacing) that makes a hex halftone's white-pixel
     * fraction equal [cover] — the numerically inverted coverage curve, sampled every 0.05 from
     * cover 0 to 1 and linearly interpolated. A plain r = √(cover·cellArea/π) is exact only until
     * the dots start to overlap (~0.9) and then under-covers; these values correct the overlap
     * and reach a solid fill (dots merging) at 1.0. Validated by rasterisation: white-fraction
     * error < ~1% throughout. Shared by both halftone renderers (the app drawer's DrawScope one
     * and the widget panel's Canvas one) so the coverage curve can't drift between them.
     */
    private val COVER_Q = floatArrayOf(
        0.0000f, 0.1174f, 0.1658f, 0.2034f, 0.2347f, 0.2625f, 0.2876f, 0.3105f,
        0.3321f, 0.3522f, 0.3713f, 0.3893f, 0.4070f, 0.4233f, 0.4392f, 0.4547f,
        0.4697f, 0.4839f, 0.4981f, 0.5175f, 0.5690f,
    )

    fun dotRatio(cover: Float): Float {
        val t = cover.coerceIn(0f, 1f) * 20f
        val i = t.toInt().coerceIn(0, 19)
        return COVER_Q[i] + (COVER_Q[i + 1] - COVER_Q[i]) * (t - i)
    }
}
