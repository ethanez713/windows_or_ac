package com.acwindows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The limiting-factor scan the forecast surfaces read (docs/ux-redesign.md §D). Pure
 * logic — runs on plain JVM JUnit.
 */
class SeverityTest {

    private val c = Comfort()  // temp 76/78, dew 60/65, aqi 100/150, rain 20/70

    private fun hour(
        h: Int, temp: Double? = 70.0, dew: Double? = 50.0, aqi: Int? = 20,
        rain: Double? = 0.0, thunder: Double? = null,
    ) = ForecastPoint(
        time = "2026-06-15T%02d:00".format(h),
        score = Score.windowScore(c, temp, dew, aqi, rain),
        tempF = temp, dewPointF = dew, aqi = aqi, precipProb = rain, thunderProb = thunder,
    )

    @Test fun idealIsZeroAndCutoffIsOne() {
        assertEquals(0.0, severity(70.0, 76.0, 78.0), 1e-9)
        assertEquals(0.0, severity(76.0, 76.0, 78.0), 1e-9)
        assertEquals(0.5, severity(77.0, 76.0, 78.0), 1e-9)
        assertEquals(1.0, severity(78.0, 76.0, 78.0), 1e-9)
    }

    @Test fun coldFloorRisesAsItGetsColder() {
        assertEquals(0.0, severityLow(55.0, 55.0, 40.0), 1e-9)
        assertEquals(0.5, severityLow(47.5, 55.0, 40.0), 1e-9)
        assertEquals(1.0, severityLow(40.0, 55.0, 40.0), 1e-9)
        assertEquals(2.0, severityLow(25.0, 55.0, 40.0), 1e-9)
    }

    @Test fun freezingHourOutranksAMildlyHotOne() {
        val hot = hour(15, temp = 79.0)
        val cold = hour(6, temp = 20.0)
        val worst = limitingFactor(c, listOf(hot, cold))!!
        assertEquals(Factor.TEMP, worst.factor)
        assertEquals("2026-06-15T06:00", worst.point.time)
    }

    @Test fun severityKeepsRisingPastTheCutoff() {
        // The property the whole scan depends on: past the cutoff the score has already
        // saturated at 0, so only severity can still tell two bad hours apart.
        assertEquals(2.0, severity(80.0, 76.0, 78.0), 1e-9)
        assertEquals(3.0, severity(82.0, 76.0, 78.0), 1e-9)
    }

    @Test fun aMissingReadingIsNotABlocker() {
        assertEquals(0.0, severity(null, 76.0, 78.0), 1e-9)
    }

    @Test fun comfortableDayHasNoLimitingFactor() {
        assertNull(limitingFactor(c, listOf(hour(9), hour(10), hour(11))))
    }

    @Test fun picksTheWorstHourNotTheFirstBlockedOne() {
        // Both hours score exactly 0 — the score is a product of ramps, so it bottoms out
        // as soon as one factor does and every blocked hour ties. Reading the limiter at
        // the lowest-scoring hour would return hour 9; the worst hour is 15.
        val mild = hour(9, temp = 79.0)
        val brutal = hour(15, temp = 95.0)
        assertEquals(0.0, mild.score, 1e-9)
        assertEquals(0.0, brutal.score, 1e-9)

        val worst = limitingFactor(c, listOf(mild, brutal))!!
        assertEquals(Factor.TEMP, worst.factor)
        assertEquals("2026-06-15T15:00", worst.point.time)
    }

    @Test fun picksTheWorstFactorNotTheFirstOne() {
        // Temp is 1 step past its cutoff; AQI is far further past its own.
        val h = hour(14, temp = 80.0, aqi = 260)
        assertEquals(Factor.AQI, limitingFactor(c, listOf(h))!!.factor)
    }

    @Test fun glyphRankFollowsTheNumbersBesideIt() {
        assertEquals(WxRank.CLEAR, wxRank(0.0, null, 50.0))
        assertEquals(WxRank.PARTLY, wxRank(4.0, null, 50.0))
        assertEquals(WxRank.CLOUDY, wxRank(8.0, null, 50.0))
        assertEquals(WxRank.RAIN, wxRank(20.0, null, 50.0))
        assertEquals(WxRank.THUNDER, wxRank(20.0, 50.0, 50.0))
        // Below the alert threshold a storm chance is not a storm glyph.
        assertEquals(WxRank.RAIN, wxRank(20.0, 49.0, 50.0))
    }

    @Test fun dayAndNightGlyphsTakeTheirOwnHoursWorst() {
        val points = listOf(
            hour(3, rain = 90.0),    // night: rain
            hour(12, rain = 0.0),    // day: clear
            hour(15, rain = 10.0),   // day: cloudy
            hour(22, rain = 0.0),
        )
        val (day, night) = dayNightRanks(points, thunderAlert = 50.0) {
            ForecastBuilder.hour(it.time)
        }
        assertEquals(WxRank.CLOUDY, day)
        assertEquals(WxRank.RAIN, night)
    }

    @Test fun aFactorHiddenBehindAWorseOneIsStillReported() {
        val bad = badConditions(c, listOf(hour(15, dew = 70.0, rain = 90.0)), threshold = 50.0)
        assertEquals(setOf(Factor.DEW, Factor.RAIN), bad.map { it.factor }.toSet())
    }

    @Test fun aMildFactorThatCouldNotSinkTheHourIsLeftOut() {
        val bad = badConditions(c, listOf(hour(15, temp = 79.0, rain = 30.0)), threshold = 50.0)
        assertEquals(listOf(Factor.TEMP), bad.map { it.factor })
    }

    @Test fun hoursAtOrAboveThresholdContributeNothing() {
        assertEquals(emptyList<Limiting>(), badConditions(c, listOf(hour(12), hour(13)), threshold = 50.0))
    }

    @Test fun eachFactorReportsItsWorstHour() {
        val bad = badConditions(c, listOf(hour(9, rain = 80.0), hour(14, rain = 95.0)), threshold = 50.0)
        assertEquals("2026-06-15T14:00", bad.single().point.time)
    }

    @Test fun worstFactorComesFirst() {
        val bad = badConditions(c, listOf(hour(15, dew = 70.0, rain = 80.0)), threshold = 50.0)
        assertEquals(listOf(Factor.DEW, Factor.RAIN), bad.map { it.factor })
    }
}
