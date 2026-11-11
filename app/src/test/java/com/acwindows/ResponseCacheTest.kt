package com.acwindows

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResponseCacheTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val hour = 60 * 60_000L
    private val a = "https://api.example.org/forecast?lat=1&lon=2"
    private val b = "https://api.example.org/current?lat=1&lon=2"
    private val other = "https://other.example.net/forecast?lat=1&lon=2"
    private var calls = 0

    private fun ok(body: String): () -> String = { calls++; body }
    private val limited: () -> String = { calls++; throw WeatherRepository.HttpStatusException(429) }

    /** Backdate every cached body, as if written [ms] ago. */
    private fun ageAll(ms: Long) {
        File(ctx.cacheDir, "responses").listFiles()!!
            .forEach { it.setLastModified(System.currentTimeMillis() - ms) }
    }

    @Test fun reusesABodyUntilItExpires() {
        val c = ResponseCache(ctx)
        assertEquals("1", c.get(a, call = ok("1")))
        assertEquals("1", c.get(a, call = ok("2")))
        assertEquals(1, calls)
        ageAll(ResponseCache.FRESH_MS + 1)
        assertEquals("2", c.get(a, call = ok("2")))
        assertEquals(2, calls)
    }

    @Test fun theDefaultAgeRefreshesOnEveryWidgetTick() {
        // Responses land seconds after the tick is armed, so by the next tick they are just
        // short of the interval — they must still count as due.
        val c = ResponseCache(ctx)
        c.get(a, call = ok("1"))
        ageAll(WidgetCache.TTL_MS - 15_000)
        assertEquals("2", c.get(a, call = ok("2")))
    }

    @Test fun urlsAreCachedSeparately() {
        val c = ResponseCache(ctx)
        c.get(a, call = ok("a"))
        assertEquals("b", c.get(b, call = ok("b")))
        assertEquals(2, calls)
    }

    @Test fun a429PausesThatHostOnlyAndServesRecentCache() {
        val c = ResponseCache(ctx)
        c.get(a, call = ok("cached"))
        ageAll(2 * hour)   // expired, but inside the stale allowance
        assertEquals("cached", c.get(a, call = limited))
        // Paused: nothing more reaches that host...
        assertEquals("cached", c.get(a, call = ok("fresh")))
        assertThrows(ResponseCache.RateLimited::class.java) { c.get(b, call = ok("fresh")) }
        assertEquals(2, calls)
        // ...while other hosts are untouched.
        assertEquals("x", c.get(other, call = ok("x")))
    }

    @Test fun otherHttpErrorsPassThroughWithoutPausing() {
        val c = ResponseCache(ctx)
        assertThrows(WeatherRepository.HttpStatusException::class.java) {
            c.get(a) { throw WeatherRepository.HttpStatusException(500) }
        }
        assertEquals("ok", c.get(a, call = ok("ok")))
    }
}
