package com.acwindows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the Python backend's tests/test_forecast.py for the on-device port.
 * Pure logic — plain JVM JUnit.
 */
class ForecastBuilderTest {

    private val c = Comfort()

    /** One Open-Meteo-shaped hourly row (types must match ForecastBuilder's casts). */
    private fun h(time: String, temp: Double, dew: Double = 50.0, aqi: Int = 20, pop: Double = 0.0) =
        mapOf<String, Any?>(
            "time" to time, "tempF" to temp, "dewPointF" to dew,
            "aqi" to aqi, "precipProb" to pop, "shortwaveWm2" to 0.0,
        )

    private fun cross(time: String, temp: Double) = mapOf<String, Any?>("time" to time, "tempF" to temp)

    // A warm afternoon cooling into a comfortable evening.
    private val warmThenCool = listOf(
        h("2026-06-15T16:00", 80.0), h("2026-06-15T17:00", 78.0),
        h("2026-06-15T18:00", 75.0), h("2026-06-15T19:00", 72.0),
        h("2026-06-15T20:00", 70.0), h("2026-06-15T21:00", 69.0),
    )

    @Test fun scoresTrackTemperature() {
        val fc = ForecastBuilder.build(warmThenCool, emptyList(), c)
        assertEquals(6, fc.points.size)
        assertEquals(0.0, fc.points.first().score, 0.001)    // 80°F, can't cool
        assertEquals(100.0, fc.points.last().score, 0.001)   // 69°F, dry, clean
    }

    @Test fun bestWindowIsTheCoolStretch() {
        val fc = ForecastBuilder.build(warmThenCool, emptyList(), c)
        assertEquals("2026-06-15T18:00", fc.bestStart)
        assertEquals("2026-06-15T21:00", fc.bestEnd)
        assertTrue(fc.bestAvgScore!! >= c.openScoreMin)
    }

    @Test fun noWindowWhenAlwaysWarm() {
        val warm = (16..21).map { h("2026-06-15T${it}:00", 85.0) }
        val fc = ForecastBuilder.build(warm, emptyList(), c)
        assertNull(fc.bestStart)
        assertTrue(fc.notes.any { it.contains("won't beat the AC") })
    }

    @Test fun tempAgreementAgree() {
        val nws = warmThenCool.map { cross(it["time"] as String, (it["tempF"] as Double) + 1) }
        assertEquals("agree", ForecastBuilder.build(warmThenCool, nws, c).tempAgreement)
    }

    @Test fun tempAgreementConflict() {
        val nws = warmThenCool.map { cross(it["time"] as String, (it["tempF"] as Double) + 15) }
        assertEquals("conflict", ForecastBuilder.build(warmThenCool, nws, c).tempAgreement)
    }

    @Test fun tempAgreementDegradedWithoutCross() {
        assertEquals("degraded", ForecastBuilder.build(warmThenCool, emptyList(), c).tempAgreement)
    }

    @Test fun emptyForecast() {
        val fc = ForecastBuilder.build(emptyList(), emptyList(), c)
        assertTrue(fc.points.isEmpty())
        assertNull(fc.bestStart)
    }

    @Test fun openNowNoteWhenCurrentlyGood() {
        val fc = ForecastBuilder.build(warmThenCool.reversed(), emptyList(), c)  // cool now -> warm
        assertTrue(fc.notes.any { it.contains("Good to open now") })
    }

    @Test fun fmtHourFormats() {
        assertEquals("9 PM", ForecastBuilder.fmtHour("2026-06-15T21:00"))
        assertEquals("12 AM", ForecastBuilder.fmtHour("2026-06-15T00:00"))
        assertEquals("8 AM", ForecastBuilder.fmtHour("2026-06-15T08:00"))
        assertEquals("12 PM", ForecastBuilder.fmtHour("2026-06-15T12:00"))
    }
}
