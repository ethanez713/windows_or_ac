package com.acwindows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The wall-clock anchoring that keeps the widget's text and its graph describing the same
 * moment. The bars are fixed clock hours, so "which bar is now" is a function of the
 * current time, not of the array index — getting that wrong is what let a stale snapshot
 * keep labelling its first bar "now" while the readings had moved on.
 *
 * Pure logic — plain JVM JUnit.
 */
class SnapshotTimeTest {

    private val hour = 3_600_000L

    /** A snapshot whose first bar starts at [firstHourMs] and whose readings are [ageMs] old. */
    private fun snap(firstHourMs: Long, ageMs: Long, now: Long) = Snapshot(
        action = Action.OPEN_WINDOWS, headline = "", summary = "", tempF = 70.0,
        dewPointF = 50.0, aqi = 20, nowScore = 80.0, confidence = "high",
        sourcesOk = listOf("open-meteo"), sourcesTotal = 3,
        scores = List(12) { 50.0 }, times = List(12) { "" }, worst = emptyList(),
        isDay = emptyList(), precipProb = emptyList(), precipIn = emptyList(),
        bestStart = null, bestEnd = null, note = "",
        updatedAtMs = now - ageMs, firstHourMs = firstHourMs,
    )

    @Test fun nowSitsInTheFirstBarRightAfterAFetch() {
        val now = 1_700_000_000_000L
        // Fetched 10 minutes into the current clock hour.
        val pos = nowBarPos(firstHourMs = now - 10 * 60_000L, nowMs = now)!!
        assertEquals(0.167, pos, 0.01)
    }

    @Test fun nowAdvancesThroughTheBarsAsTheSnapshotAges() {
        val first = 1_700_000_000_000L
        // Three and a half hours later the present is well inside the fourth bar — the
        // whole point: the graph must not still be calling bar 0 "now".
        assertEquals(3.5, nowBarPos(first, first + 3 * hour + hour / 2)!!, 0.001)
    }

    @Test fun nowFallsOffTheEndOnceTheSeriesIsOutrun() {
        val first = 1_700_000_000_000L
        val pos = nowBarPos(first, first + 20 * hour)!!
        // 12 bars of forecast, 20 hours elapsed → the marker is outside and reads "stale".
        assertTrue("expected the present past the last bar, was $pos", pos > 12)
    }

    @Test fun nowIsNegativeWhenTheSeriesStartsInTheFuture() {
        val first = 1_700_000_000_000L
        assertTrue(nowBarPos(first, first - 2 * hour)!! < 0)
    }

    @Test fun noAnchorMeansNoMarker() {
        // Snapshots cached by a build that predates the anchor: the graph falls back to
        // labelling bar 0 "now" rather than drawing a marker in the wrong place.
        assertNull(nowBarPos(0L))
    }

    @Test fun ageDrivesTheStaleFlag() {
        val now = 1_700_000_000_000L
        assertTrue(snap(now, WidgetCache.TTL_MS, now).ageMs(now) <= WidgetCache.STALE_MS)
        assertTrue(snap(now, 4 * WidgetCache.TTL_MS, now).ageMs(now) > WidgetCache.STALE_MS)
    }

    // The config zone is what makes an hourly row's local time absolute.

    private fun cfg(tz: String) = AppConfig(
        lat = 0.0, lon = 0.0, timezone = tz, userAgent = "test", comfort = Comfort(),
    )

    @Test fun rowsAreAnchoredInTheZoneTheProviderWasAskedFor() {
        // The rows come back in the CONFIGURED zone, which need not be the device's — a
        // phone travelling with a fixed home timezone would otherwise shift every bar.
        val ny = cfg("America/New_York").zone()
        val la = cfg("America/Los_Angeles").zone()
        val local = LocalDateTime.parse("2026-08-02T14:00")
        val delta = local.atZone(la).toInstant().toEpochMilli() -
            local.atZone(ny).toInstant().toEpochMilli()
        assertEquals(3 * hour, delta)
    }

    @Test fun autoAndGarbageZonesFallBackToTheDevice() {
        assertEquals(ZoneId.systemDefault(), cfg("auto").zone())
        assertEquals(ZoneId.systemDefault(), cfg("").zone())
        assertEquals(ZoneId.systemDefault(), cfg("Not/AZone").zone())
    }
}
