package com.acwindows

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Every weather response the app downloads, kept on disk and reused until it is [maxAgeMs]
 * old — so each server sees at most one request per URL per [FRESH_MS], however often the
 * app is opened, locations are switched or the widget's tick and backstop fire. Weather does
 * not change faster than that, and the providers are shared, mostly free services.
 *
 * A 429 pauses that host for [PAUSE_MS]; meanwhile a cached body up to [STALE_OK_MS] old
 * keeps the source voting. Bodies are keyed by a hash of the URL, never the URL itself,
 * since some URLs carry an API key.
 */
class ResponseCache(context: Context) {
    private val dir = File(context.cacheDir, "responses").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("response_cache", Context.MODE_PRIVATE)

    class RateLimited(host: String) : RuntimeException("rate-limited by $host (HTTP 429) — paused, no recent cached data")

    /**
     * The body for [url]: the cached one while younger than [maxAgeMs], else [call]'s.
     * Throws [RateLimited] when its host is paused and nothing recent enough can stand in.
     * One caller per URL at a time, so the app and the widget refreshing the same place
     * together make one request, not two.
     */
    fun get(url: String, maxAgeMs: Long = FRESH_MS, call: () -> String): String {
        val file = File(dir, sha256(url))
        val host = runCatching { URL(url).host }.getOrDefault("")
        val pauseKey = "pausedUntil_$host"
        synchronized(locks.getOrPut(file.name) { Any() }) {
            val now = System.currentTimeMillis()
            val age = if (file.exists()) now - file.lastModified() else Long.MAX_VALUE
            if (age < maxAgeMs) return file.readText()
            if (now < prefs.getLong(pauseKey, 0L)) {
                if (age < STALE_OK_MS) return file.readText()
                throw RateLimited(host)
            }
            val body = try {
                call()
            } catch (e: WeatherRepository.HttpStatusException) {
                if (e.code != 429) throw e
                prefs.edit().putLong(pauseKey, now + PAUSE_MS).apply()
                val cached = if (file.exists()) "${age / 60_000}min" else "none"
                Log.i(TAG, "429 from $host; pausing ${PAUSE_MS / 60_000} min; cached=$cached")
                if (age < STALE_OK_MS) return file.readText()
                throw RateLimited(host)
            }
            file.writeText(body)
            prune(now)
            return body
        }
    }

    /** Drop bodies nothing will reuse — places since removed, fixes since moved from. */
    private fun prune(now: Long) {
        dir.listFiles()?.filter { now - it.lastModified() > PRUNE_AFTER_MS }?.forEach { it.delete() }
    }

    companion object {
        /**
         * The refresh interval less a minute. The widget's next tick is armed as a refresh
         * STARTS, and its responses land seconds later, so at the next tick they are a few
         * seconds short of [WidgetCache.TTL_MS] — a full-interval age would serve them again
         * and halve the real refresh rate.
         */
        val FRESH_MS = WidgetCache.TTL_MS - 60_000L

        /** Place metadata (NWS grid point, station list) that only changes with the location. */
        const val PLACE_MS = 24 * 60 * 60_000L

        /** Tomorrow.io's free tier is 25 calls/hour, 500/day: 3/hour per location stays well inside. */
        const val TOMORROW_REALTIME_MS = 30 * 60_000L
        const val TOMORROW_HOURLY_MS = 60 * 60_000L

        const val PAUSE_MS = 60 * 60_000L
        const val STALE_OK_MS = 3 * 60 * 60_000L
        private const val PRUNE_AFTER_MS = PLACE_MS + 60 * 60_000L
        private const val TAG = "AcWindows"

        /** Process-wide, so separate instances over the same directory still take turns. */
        private val locks = ConcurrentHashMap<String, Any>()

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
