package com.acwindows

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors the Python backend's tests/test_decision.py. Keeps the Kotlin decision
 * engine in lockstep with the canonical brain. Pure logic — plain JVM JUnit.
 */
class DecisionTest {

    private val c = Comfort()  // temp 76/78, dew max 65, aqi max 150

    /** A consensus with sensible, non-blocking defaults (cf. Python's reading()). */
    private fun consensus(
        tempF: Double? = 70.0,
        dewPointF: Double? = 50.0,
        precipMm: Double? = 0.0,
        aqi: Int? = 20,
    ) = Consensus(
        tempF = tempF, dewPointF = dewPointF, humidityPct = 45.0, apparentTempF = 70.0,
        precipMm = precipMm, windMph = 4.0, aqi = aqi, confidence = "high",
    )

    @Test fun coolCleanDryOpensWindows() {
        assertEquals(Action.OPEN_WINDOWS, Decision.decide(consensus(tempF = 70.0), c).action)
    }

    @Test fun hotRunsAc() {
        assertEquals(Action.RUN_AC, Decision.decide(consensus(tempF = 85.0), c).action)
    }

    @Test fun badAqiWhenCoolKeepsClosed() {
        assertEquals(Action.KEEP_CLOSED, Decision.decide(consensus(tempF = 70.0, aqi = 160), c).action)
    }

    @Test fun badAqiWhenHotRunsAc() {
        assertEquals(Action.RUN_AC, Decision.decide(consensus(tempF = 88.0, aqi = 160), c).action)
    }

    @Test fun muggyWhenCoolPrefersAcToDehumidify() {
        assertEquals(Action.RUN_AC, Decision.decide(consensus(tempF = 72.0, dewPointF = 68.0), c).action)
    }

    @Test fun rainWhenCoolKeepsClosed() {
        assertEquals(Action.KEEP_CLOSED, Decision.decide(consensus(tempF = 68.0, precipMm = 0.5), c).action)
    }

    @Test fun nearSetpointIsComfortable() {
        // 77°F: not cool enough (>76 ideal) but nothing blocks and not too warm (<78).
        assertEquals(Action.COMFORTABLE, Decision.decide(consensus(tempF = 77.0), c).action)
    }

    @Test fun missingTempIsUnknown() {
        assertEquals(Action.UNKNOWN, Decision.decide(consensus(tempF = null), c).action)
    }

    @Test fun customSetpointRespected() {
        val warm = Comfort(maxTempF = 72.0)
        assertEquals(Action.RUN_AC, Decision.decide(consensus(tempF = 74.0), warm).action)
    }

    @Test fun missingAqiDoesNotBlockOpening() {
        assertEquals(Action.OPEN_WINDOWS, Decision.decide(consensus(tempF = 68.0, aqi = null), c).action)
    }

    @Test fun reasonsArePopulated() {
        assert(Decision.decide(consensus(tempF = 85.0), c).reasons.isNotEmpty())
    }

    @Test fun freezingKeepsClosedRatherThanHeatingByWindow() {
        val rec = Decision.decide(consensus(tempF = 32.0, dewPointF = 20.0), c)
        assertEquals(Action.KEEP_CLOSED, rec.action)
        assert(rec.summary.contains("won't warm"))
    }

    @Test fun coldAndSmokyDoesNotRunAc() {
        val rec = Decision.decide(consensus(tempF = 32.0, aqi = 160), c)
        assertEquals(Action.KEEP_CLOSED, rec.action)
        assert(!rec.headline.contains("AC"))
    }

    @Test fun fiftyDegreesStillOpens() {
        assertEquals(Action.OPEN_WINDOWS, Decision.decide(consensus(tempF = 50.0, dewPointF = 50.0), c).action)
    }

    @Test fun midRampColdKeepsClosed() {
        assertEquals(Action.KEEP_CLOSED, Decision.decide(consensus(tempF = 45.0, dewPointF = 35.0), c).action)
    }

    @Test fun veryDryAirKeepsClosed() {
        val rec = Decision.decide(consensus(tempF = 70.0, dewPointF = 30.0), c)
        assertEquals(Action.KEEP_CLOSED, rec.action)
        assert(rec.summary.contains("very dry"))
    }

    @Test fun smokyCoolEveningIsNotTheColdMessage() {
        val rec = Decision.decide(consensus(tempF = 50.0, aqi = 160), c)
        assertEquals(Action.KEEP_CLOSED, rec.action)
        assert(!rec.summary.contains("won't warm"))
    }

    @Test fun aqiCategoryBoundaries() {
        assertEquals("good", Decision.aqiCategory(50))
        assertEquals("moderate", Decision.aqiCategory(100))
        assertEquals("unhealthy for sensitive groups", Decision.aqiCategory(150))
        assertEquals("unknown", Decision.aqiCategory(null))
    }
}
