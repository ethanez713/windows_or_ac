package com.acwindows

import org.junit.Assert.assertEquals
import org.junit.Test

class UvRampTest {

    @Test fun nightHoursMatchTheCloudBarNightSky() {
        val night = listOf(false, false, false)
        val (cloud, _) = CloudRamp.skySweep(night)
        val (uv, _) = UvRamp.sweep(listOf(7.0, 0.0, null), night, dark = true)
        assertEquals(cloud.toList(), uv.toList())
    }

    @Test fun dayHoursStayOnTheWhoRamp() {
        val day = listOf(true, true, true)
        val (colors, _) = UvRamp.sweep(listOf(0.0, 6.0, 11.0), day, dark = true)
        assertEquals(UvRamp.colorAt(0.0), colors[0])
        assertEquals(UvRamp.colorAt(6.0), colors[1])
        assertEquals(UvRamp.colorAt(11.0), colors[2])
    }

    @Test fun nightOverridesAHeldDaytimeReading() {
        val (colors, _) = UvRamp.sweep(listOf(8.0, null), listOf(true, false), dark = true)
        assertEquals(UvRamp.colorAt(8.0), colors[0])
        assertEquals(CloudRamp.CLEAR_NIGHT, colors[1])
    }
}
