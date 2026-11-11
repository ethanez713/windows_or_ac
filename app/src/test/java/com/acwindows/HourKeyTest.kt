package com.acwindows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * Every source's hourly rows must key on the phone's clock, whatever zone the location is in,
 * or they stop merging into the same row as Open-Meteo's (which is asked for the phone's zone).
 */
class HourKeyTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test fun nwsLocationOffsetAndMetnoUtcLandOnTheSameRow() {
        // 14:00 in New York (EDT) is 18:00Z is 03:00 next day in Tokyo.
        val nws = WeatherRepository.offsetHourKey("2026-08-02T14:00:00-04:00", tokyo)
        val metno = WeatherRepository.utcHourKey("2026-08-02T18:00:00Z", tokyo)
        assertEquals("2026-08-03T03:00", nws)
        assertEquals(nws, metno)
    }

    @Test fun halfHourZonesSnapToOpenMeteosWholeHourRows() {
        // Open-Meteo labels rows on whole local hours even at +5:30, so 20:00Z (01:30 IST)
        // has to key on the 01:00 row rather than fall between rows.
        assertEquals("2026-08-03T01:00", WeatherRepository.utcHourKey("2026-08-02T20:00:00Z", ZoneId.of("Asia/Kolkata")))
    }

    @Test fun unparseableTimesAreDropped() {
        assertNull(WeatherRepository.utcHourKey("", tokyo))
        assertNull(WeatherRepository.offsetHourKey("2026-08-02T14:00", tokyo))
    }
}
