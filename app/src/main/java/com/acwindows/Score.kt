package com.acwindows

import kotlin.math.roundToInt

/**
 * The Open-Window Score — a faithful port of the Python backend's score.py.
 * A continuous 0..100 generalisation of Decision.decide(): the product of
 * factors that each ramp between the thresholds configured in [Comfort].
 *
 *     score = 100 · f_temp · f_humid · f_aqi · f_rain
 *
 * See README "Methodology" for the full write-up.
 */
object Score {

    /** 1.0 at x<=full, 0.0 at x>=zero, linear between. Null = non-blocking (1.0). */
    private fun rampDown(x: Double?, full: Double, zero: Double): Double {
        if (x == null) return 1.0
        if (zero <= full) return if (x <= full) 1.0 else 0.0
        return ((zero - x) / (zero - full)).coerceIn(0.0, 1.0)
    }

    /** 1.0 at x>=full, 0.0 at x<=zero, linear between. Null = non-blocking (1.0). */
    private fun rampUp(x: Double?, zero: Double, full: Double): Double {
        if (x == null) return 1.0
        if (full <= zero) return if (x >= full) 1.0 else 0.0
        return ((x - zero) / (full - zero)).coerceIn(0.0, 1.0)
    }

    /**
     * Full breakdown: the score plus each factor multiplier (0..1), so the UI can
     * show *which* factor is holding the score back. Mirrors score.py's
     * ScoreBreakdown. A missing input leaves its factor at 1.0 (non-blocking).
     */
    fun windowScoreBreakdown(
        c: Comfort,
        tempF: Double?,
        dewPointF: Double? = null,
        aqi: Int? = null,
        precipProb: Double? = null,
        precipMm: Double? = null,
    ): ScoreBreakdown {
        if (tempF == null) return ScoreBreakdown(0.0, 0.0, 1.0, 1.0, 1.0)

        // Product of the hot ramp and the cold lockout. The cold side is not
        // "open to warm": an open window cannot greenhouse-heat a room.
        val fTemp = rampDown(tempF, c.tempIdealF, c.maxTempF) *
            rampUp(tempF, c.tempMinF, c.tempMinIdealF)
        // Same shape as temperature: a low dew point imports very dry air.
        val fHumid = rampDown(dewPointF, c.dewPointIdealF, c.dewPointMaxF) *
            rampUp(dewPointF, c.dewPointMinF, c.dewPointMinIdealF)
        val fAqi = rampDown(aqi?.toDouble(), c.aqiIdeal, c.aqiMax.toDouble())
        val fRain = when {
            precipProb != null -> rampDown(precipProb, c.rainProbIdeal, c.rainProbMax)
            precipMm != null -> if (precipMm > 0) 0.0 else 1.0
            else -> 1.0
        }

        val score = 100.0 * fTemp * fHumid * fAqi * fRain
        fun r3(v: Double) = (v * 1000).roundToInt() / 1000.0
        return ScoreBreakdown(
            score = (score * 10).roundToInt() / 10.0,   // round to 1 dp
            fTemp = r3(fTemp), fHumid = r3(fHumid), fAqi = r3(fAqi), fRain = r3(fRain),
        )
    }

    /** Convenience: just the 0..100 score (what the widget + best-window search use). */
    fun windowScore(
        c: Comfort,
        tempF: Double?,
        dewPointF: Double? = null,
        aqi: Int? = null,
        precipProb: Double? = null,
        precipMm: Double? = null,
    ): Double = windowScoreBreakdown(
        c, tempF, dewPointF, aqi, precipProb, precipMm,
    ).score
}
