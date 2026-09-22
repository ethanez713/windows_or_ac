package com.acwindows

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps Tomorrow.io inside its free tier — 25 calls/hour and 500/day per key — however often
 * the widget and the app refresh. Without it each refresh spends two calls, and the widget's
 * 10-minute tick plus its 15-minute WorkManager backstop alone come to ~480/day.
 *
 * Each response body is kept on disk per location and reused until it is [maxAgeMs] old, so
 * the spend is set by those ages rather than by the refresh rate: 30 min for realtime and 60
 * for the hourly forecast is 3 calls/hour per location. A 429 pauses every call for
 * [PAUSE_MS] (the response doesn't say which window ran out); meanwhile a cached body up to
 * [STALE_OK_MS] old keeps the source voting.
 */
class TomorrowIoBudget(context: Context) {
    private val dir = File(context.cacheDir, "tomorrowio").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("tomorrowio", Context.MODE_PRIVATE)

    class RateLimited : RuntimeException("rate-limited (HTTP 429) — paused, no recent cached data")

    /**
     * The body for [kind] at [lat],[lon]: the cached one while younger than [maxAgeMs], else
     * [call]'s. Throws [RateLimited] when paused with nothing recent enough to stand in.
     * One caller per key at a time, so the app and the widget refreshing the same place
     * together spend one call, not two.
     */
    fun get(kind: String, lat: Double, lon: Double, maxAgeMs: Long, call: () -> String): String {
        val file = File(dir, "${kind}_${lat}_$lon.json")
        synchronized(locks.getOrPut(file.name) { Any() }) {
            val now = System.currentTimeMillis()
            val age = if (file.exists()) now - file.lastModified() else Long.MAX_VALUE
            if (age < maxAgeMs) return file.readText()
            if (now < prefs.getLong(KEY_PAUSED_UNTIL, 0L)) {
                if (age < STALE_OK_MS) return file.readText()
                throw RateLimited()
            }
            val body = try {
                call()
            } catch (e: WeatherRepository.HttpStatusException) {
                if (e.code != 429) throw e
                prefs.edit().putLong(KEY_PAUSED_UNTIL, now + PAUSE_MS).apply()
                val cached = if (file.exists()) "${age / 60_000}min" else "none"
                Log.i(TAG, "tomorrowio 429 on $kind; pausing ${PAUSE_MS / 60_000} min; cached=$cached")
                if (age < STALE_OK_MS) return file.readText()
                throw RateLimited()
            }
            file.writeText(body)
            prune(now)
            return body
        }
    }

    /** Drop bodies no refresh will reuse — places since removed, or fixes since moved from. */
    private fun prune(now: Long) {
        dir.listFiles()?.filter { now - it.lastModified() > STALE_OK_MS }?.forEach { it.delete() }
    }

    companion object {
        const val REALTIME_MAX_AGE_MS = 30 * 60_000L
        const val HOURLY_MAX_AGE_MS = 60 * 60_000L
        const val PAUSE_MS = 60 * 60_000L
        const val STALE_OK_MS = 3 * 60 * 60_000L
        private const val KEY_PAUSED_UNTIL = "pausedUntilMs"
        private const val TAG = "AcWindows"
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
