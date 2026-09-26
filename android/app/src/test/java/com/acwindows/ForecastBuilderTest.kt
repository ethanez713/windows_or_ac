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

    @Test fun noWindowWhenAlwaysCold() {
        val cold = (0..5).map { h("2026-06-15T0$it:00", 30.0, dew = 20.0) }
        val fc = ForecastBuilder.build(cold, emptyList(), c)
        assertNull(fc.bestStart)
        assertTrue(fc.notes.any { it.contains("Too cold") })
        assertTrue(fc.notes.none { it.contains("won't beat the AC") })
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

    // Rain consensus (see ForecastBuilder.rainConsensus). The real disagreement this
    // exists for: Open-Meteo said 32% / 0.00" for an hour NWS called 72% and met.no put
    // 0.7 mm in.
    private fun rain(time: String, pop: Double? = null, inches: Double? = null) =
        mapOf<String, Any?>("time" to time, "precipProb" to pop, "precipIn" to inches)

    private val oneWetHour = listOf(h("2026-06-15T09:00", 70.0, pop = 32.0) + mapOf("precipIn" to 0.0))

    @Test fun rainChanceAveragesTwoSources() {
        val fc = ForecastBuilder.build(oneWetHour, listOf(rain("2026-06-15T09:00", pop = 72.0)), c)
        assertEquals(52.0, fc.points[0].precipProb!!, 0.001)
    }

    @Test fun rainAmountAveragesTwoSources() {
        val fc = ForecastBuilder.build(
            oneWetHour, emptyList(), c,
            extraHourly = listOf(listOf(rain("2026-06-15T09:00", inches = 0.0276))),
        )
        assertEquals(0.0138, fc.points[0].precipIn!!, 0.0001)
    }

    @Test fun rainTakesTheMedianOfThreeSources() {
        val fc = ForecastBuilder.build(
            oneWetHour, listOf(rain("2026-06-15T09:00", pop = 72.0)), c,
            extraHourly = listOf(listOf(rain("2026-06-15T09:00", pop = 50.0))),
        )
        assertEquals(50.0, fc.points[0].precipProb!!, 0.001)
    }

    @Test fun aSourceMissingTheHourDoesNotVote() {
        val fc = ForecastBuilder.build(oneWetHour, listOf(rain("2026-06-15T10:00", pop = 72.0)), c)
        assertEquals(32.0, fc.points[0].precipProb!!, 0.001)
    }

    @Test fun aNullFieldDoesNotVote() {
        // met.no supplies an amount but never a chance outside the Nordics — its null
        // must not drag the consensus toward zero.
        val fc = ForecastBuilder.build(
            oneWetHour, listOf(rain("2026-06-15T09:00", pop = 72.0)), c,
            extraHourly = listOf(listOf(rain("2026-06-15T09:00", inches = 0.0276))),
        )
        assertEquals(52.0, fc.points[0].precipProb!!, 0.001)
    }

    @Test fun rainStaysNullWhenNoSourceHasIt() {
        val fc = ForecastBuilder.build(listOf(h("2026-06-15T09:00", 70.0) - "precipProb"), emptyList(), c)
        assertNull(fc.points[0].precipProb)
    }

    @Test fun consensusChanceDrivesTheScore() {
        // 32% alone barely dents the score; the 52% consensus with NWS is a real penalty.
        val alone = ForecastBuilder.build(oneWetHour, emptyList(), c).points[0].score
        val merged = ForecastBuilder.build(oneWetHour, listOf(rain("2026-06-15T09:00", pop = 72.0)), c).points[0].score
        assertTrue("consensus should score lower than Open-Meteo alone", merged < alone)
    }

    @Test fun fmtHourFormats() {
        assertEquals("9 PM", ForecastBuilder.fmtHour("2026-06-15T21:00"))
        assertEquals("12 AM", ForecastBuilder.fmtHour("2026-06-15T00:00"))
        assertEquals("8 AM", ForecastBuilder.fmtHour("2026-06-15T08:00"))
        assertEquals("12 PM", ForecastBuilder.fmtHour("2026-06-15T12:00"))
    }
}
