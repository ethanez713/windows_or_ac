package com.acwindows

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors the Python backend's tests/test_score.py so the on-device port can't
 * silently drift from the canonical scoring. Pure logic — runs on plain JVM JUnit.
 */
class ScoreTest {

    private val c = Comfort()  // defaults: temp 76/78, dew 60/65, aqi 100/150, rain 20/70

    @Test fun coolDryCleanIsPerfect() {
        assertEquals(100.0, Score.windowScore(c, tempF = 70.0, dewPointF = 50.0, aqi = 20), 0.001)
    }

    @Test fun tooWarmZeroesIt() {
        // At/above the setpoint, ventilation can't cool -> windows score 0.
        assertEquals(0.0, Score.windowScore(c, tempF = 80.0, dewPointF = 50.0, aqi = 20), 0.001)
    }

    @Test fun muggyZeroesIt() {
        // Dew point at the muggy cap kills favorability even when cool.
        assertEquals(0.0, Score.windowScore(c, tempF = 70.0, dewPointF = 65.0, aqi = 20), 0.001)
    }

    @Test fun badAirZeroesIt() {
        assertEquals(0.0, Score.windowScore(c, tempF = 70.0, dewPointF = 50.0, aqi = 150), 0.001)
    }

    @Test fun rainProbabilityRamps() {
        assertEquals(100.0, Score.windowScore(c, tempF = 70.0, dewPointF = 50.0, aqi = 20, precipProb = 10.0), 0.001)
        assertEquals(0.0, Score.windowScore(c, tempF = 70.0, dewPointF = 50.0, aqi = 20, precipProb = 70.0), 0.001)
    }

    @Test fun tempRampsOverMargin() {
        // 77°F is halfway through the 76->78 ramp.
        val b = Score.windowScoreBreakdown(c, tempF = 77.0, dewPointF = 50.0, aqi = 20)
        assertEquals(0.5, b.fTemp, 0.01)
        assertEquals(50.0, b.score, 0.1)
    }

    @Test fun missingTempIsZero() {
        assertEquals(0.0, Score.windowScore(c, tempF = null), 0.001)
    }

    @Test fun unknownAqiDoesNotBlock() {
        assertEquals(100.0, Score.windowScore(c, tempF = 70.0, dewPointF = 50.0, aqi = null), 0.001)
    }

    @Test fun coldLockoutZeroesIt() {
        val b = Score.windowScoreBreakdown(c, tempF = 40.0, dewPointF = 30.0, aqi = 20)
        assertEquals(0.0, b.fTemp, 0.001)
        assertEquals(0.0, b.score, 0.001)
    }

    @Test fun nightFlushTemperatureStaysOpen() {
        assertEquals(100.0, Score.windowScore(c, tempF = 55.0, dewPointF = 50.0, aqi = 20), 0.001)
        val b = Score.windowScoreBreakdown(c, tempF = 50.0, dewPointF = 50.0, aqi = 20)
        assertEquals(0.667, b.fTemp, 0.001)
        assertEquals(66.7, b.score, 0.1)
    }

    @Test fun coldRampMidpoint() {
        val b = Score.windowScoreBreakdown(c, tempF = 47.5, dewPointF = 50.0, aqi = 20)
        assertEquals(0.5, b.fTemp, 0.01)
        assertEquals(50.0, b.score, 0.1)
    }

    @Test fun dryDewPointZeroesIt() {
        val b = Score.windowScoreBreakdown(c, tempF = 70.0, dewPointF = 40.0, aqi = 20)
        assertEquals(0.0, b.fHumid, 0.001)
        assertEquals(0.0, b.score, 0.001)
    }

    @Test fun dewRampMidpoint() {
        val b = Score.windowScoreBreakdown(c, tempF = 70.0, dewPointF = 42.5, aqi = 20)
        assertEquals(0.5, b.fHumid, 0.01)
        assertEquals(50.0, b.score, 0.1)
    }

    @Test fun pleasantDewStaysOpen() {
        assertEquals(100.0, Score.windowScore(c, tempF = 70.0, dewPointF = 50.0, aqi = 20), 0.001)
    }

    @Test fun tempLimitedColorFollowsTheLineThresholds() {
        val base = 0xFFFB923C.toInt()
        val cold = 0xFFFFFFFF.toInt()
        // Above the score cutoff (76) but below the line (90): still the temperature hue.
        assertEquals(base, tempLineArgb(80.0, c, base, cold))
        assertEquals(HotRedArgb, tempLineArgb(90.0, c, base, cold))
        // Below the open-down-to line (55) but above the line's white mark (45): still the hue.
        assertEquals(base, tempLineArgb(50.0, c, base, cold))
        assertEquals(cold, tempLineArgb(45.0, c, base, cold))
    }
}
