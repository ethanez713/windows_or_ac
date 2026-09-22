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
class TomorrowIoBudgetTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val hour = 60 * 60_000L
    private var calls = 0

    private fun ok(body: String): () -> String = { calls++; body }
    private val limited: () -> String = { calls++; throw WeatherRepository.HttpStatusException(429) }

    /** Backdate the cached body, as if it had been written [ms] ago. */
    private fun age(kind: String, ms: Long) {
        File(File(ctx.cacheDir, "tomorrowio"), "${kind}_1.0_2.0.json")
            .setLastModified(System.currentTimeMillis() - ms)
    }

    @Test fun reusesABodyUntilItExpires() {
        val b = TomorrowIoBudget(ctx)
        assertEquals("a", b.get("hourly", 1.0, 2.0, hour, ok("a")))
        assertEquals("a", b.get("hourly", 1.0, 2.0, hour, ok("b")))
        assertEquals(1, calls)
        age("hourly", hour + 1)
        assertEquals("b", b.get("hourly", 1.0, 2.0, hour, ok("b")))
        assertEquals(2, calls)
    }

    @Test fun a429PausesEveryCallAndServesRecentCache() {
        val b = TomorrowIoBudget(ctx)
        b.get("hourly", 1.0, 2.0, hour, ok("cached"))
        age("hourly", 2 * hour)   // expired, but inside the stale allowance
        assertEquals("cached", b.get("hourly", 1.0, 2.0, hour, limited))
        assertEquals(2, calls)
        // Paused: no further calls reach the API, for this kind or any other.
        assertEquals("cached", b.get("hourly", 1.0, 2.0, hour, ok("fresh")))
        assertThrows(TomorrowIoBudget.RateLimited::class.java) {
            b.get("realtime", 1.0, 2.0, hour, ok("fresh"))
        }
        assertEquals(2, calls)
    }

    @Test fun a429WithNothingRecentCachedIsRateLimited() {
        assertThrows(TomorrowIoBudget.RateLimited::class.java) {
            TomorrowIoBudget(ctx).get("realtime", 1.0, 2.0, hour, limited)
        }
    }

    @Test fun otherHttpErrorsPassThroughWithoutPausing() {
        val b = TomorrowIoBudget(ctx)
        assertThrows(WeatherRepository.HttpStatusException::class.java) {
            b.get("realtime", 1.0, 2.0, hour) { throw WeatherRepository.HttpStatusException(500) }
        }
        assertEquals("ok", b.get("realtime", 1.0, 2.0, hour, ok("ok")))
    }
}
