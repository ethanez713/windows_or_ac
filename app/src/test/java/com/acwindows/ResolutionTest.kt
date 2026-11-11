package com.acwindows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for making row resolution explicit (Forecast.stepMinutes) and the
 * places that used to silently assume one row = one hour. These are the things that
 * would actually break once a 15-minute series exists: point count vs. duration,
 * closing-edge labels, and the now-marker's bar math.
 *
 * Pure logic — plain JVM JUnit.
 */
class ResolutionTest {

    private val c = Comfort()

    /** One Open-Meteo-shaped row (same shape ForecastBuilderTest uses for hourly rows). */
    private fun row(time: String, temp: Double, dew: Double = 50.0, aqi: Int = 20, pop: Double = 0.0) =
        mapOf<String, Any?>(
            "time" to time, "tempF" to temp, "dewPointF" to dew,
            "aqi" to aqi, "precipProb" to pop, "shortwaveWm2" to 0.0,
        )

    /** ISO time for row [index] of a series stepping by [stepMinutes], starting at midnight. */
    private fun rowTime(index: Int, stepMinutes: Int): String {
        val totalMin = index * stepMinutes
        val hh = (totalMin / 60).toString().padStart(2, '0')
        val mm = (totalMin % 60).toString().padStart(2, '0')
        return "2026-06-15T$hh:$mm"
    }

    @Test fun buildAtFifteenMinuteResolutionIsOnePointPerRow() {
        val rows = (0 until 10).map { row(rowTime(it, 15), 70.0) }
        val fc = ForecastBuilder.build(rows, emptyList(), c, stepMinutes = 15)
        assertEquals(15, fc.stepMinutes)
        assertEquals(rows.size, fc.points.size)
    }

    @Test fun fmtRowEndAddsTheStepNotAFixedHour() {
        assertEquals("6:15 AM", ForecastBuilder.fmtRowEnd("2026-08-07T06:00", 15))
        assertEquals("7:00 AM", ForecastBuilder.fmtRowEnd("2026-08-07T06:45", 15))
        assertEquals("12:00 AM", ForecastBuilder.fmtRowEnd("2026-08-07T23:45", 15))   // wraps past midnight
    }

    @Test fun fmtHourEndStaysHourlyOnly() {
        // Existing hourly behaviour must be untouched even though it now delegates to fmtRowEnd.
        assertEquals("7 AM", ForecastBuilder.fmtHourEnd("2026-08-07T06:00"))
    }

    @Test fun nowBarPosAtFifteenMinuteResolution() {
        val firstMs = 1_700_000_000_000L
        // 30 minutes in is two 15-minute steps.
        assertEquals(2.0, nowBarPos(firstMs, firstMs + 30 * 60_000L, 15)!!, 0.001)
        // The two-arg (hourly) overload must still treat the same 30 minutes as half a bar.
        assertEquals(0.5, nowBarPos(firstMs, firstMs + 30 * 60_000L)!!, 0.001)
    }

    @Test fun notesDurationScalesByStepMinutesNotPointCount() {
        // 96 rows of 15 minutes = 24 hours, always too warm to open — the "won't beat the
        // AC in the next Nh" note has to say 24, not the raw point count of 96.
        val rows = (0 until 96).map { row(rowTime(it, 15), 85.0) }
        val fc = ForecastBuilder.build(rows, emptyList(), c, stepMinutes = 15)
        assertTrue(fc.notes.any { it.contains("next 24h") })
        assertTrue(fc.notes.none { it.contains("96") })
    }
}
