package com.acwindows

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything needed to re-draw the widget without touching the network. Persisted
 * so the widget renders instantly from cache on every load and only hits the
 * network when the cache is missing or older than [TTL_MS] (10 min).
 */
data class Snapshot(
    val action: Action,
    val headline: String,
    val summary: String,
    val tempF: Double?,
    val dewPointF: Double?,
    val aqi: Int?,
    val nowScore: Double,
    val confidence: String,
    val sourcesOk: List<String>,
    val sourcesTotal: Int,   // sources attempted (ok + failed), for the widget's X/N count
    val scores: List<Double>,
    val times: List<String>,
    val worst: List<Int>,   // per-hour dominant detracting factor (0=temp,1=humid,2=aqi,3=rain; -1=none)
    val isDay: List<Boolean>,   // per-hour sun-up flag (true sunrise→sunset) for the graph's day/night shading
    val precipProb: List<Double>,   // per-hour % chance of rain (0 when a source didn't supply it)
    val precipIn: List<Double>,     // per-hour rain amount, inches (that hour's rate; 0 when unknown)
    val bestStart: String?,
    val bestEnd: String?,
    val note: String,
    /** When the readings in this snapshot were fetched. */
    val updatedAtMs: Long,
    /**
     * Wall-clock time of the FIRST graph bar. The hourly series is a fixed set of absolute
     * clock hours, so without this anchor there is no way to tell where "now" falls once
     * the snapshot ages — bar 0 stops being now the moment a refresh is late, which is how
     * the graph and the text ended up describing different times. 0 = unknown (a snapshot
     * cached by an older build); the widget then falls back to labelling bar 0 "now".
     */
    val firstHourMs: Long = 0L,
) {
    /** How far past the readings' fetch time we are, in ms. */
    fun ageMs(nowMs: Long = System.currentTimeMillis()): Long = nowMs - updatedAtMs
}

/**
 * Where the current moment falls in an hourly series whose first bar starts at
 * [firstHourMs], as a fractional bar index (1.5 = halfway through the second bar).
 * Null when the anchor is unknown, and may fall outside 0..n when the series has aged
 * out — callers decide whether to clamp or to flag it.
 */
fun nowBarPos(firstHourMs: Long, nowMs: Long = System.currentTimeMillis()): Double? =
    if (firstHourMs <= 0L) null else (nowMs - firstHourMs) / 3_600_000.0

object WidgetCache {

    /** Matches the widget's 10-minute auto-refresh tick (see AcWindowsProvider). */
    const val TTL_MS = 10 * 60 * 1000L

    /**
     * Past this age the widget is visibly out of date, not just due a refresh — three
     * missed ticks means something is actually wrong (no network, aggressive Doze,
     * background restrictions). The footer and the graph both say so at this point.
     */
    const val STALE_MS = 3 * TTL_MS
    private const val PREFS = "ac_windows"
    private const val KEY = "snapshot"

    fun save(context: Context, s: Snapshot) {
        val o = JSONObject()
            .put("action", s.action.name)
            .put("headline", s.headline)
            .put("summary", s.summary)
            .put("tempF", s.tempF ?: JSONObject.NULL)
            .put("dewPointF", s.dewPointF ?: JSONObject.NULL)
            .put("aqi", s.aqi ?: JSONObject.NULL)
            .put("nowScore", s.nowScore)
            .put("confidence", s.confidence)
            .put("sourcesOk", JSONArray(s.sourcesOk))
            .put("sourcesTotal", s.sourcesTotal)
            .put("scores", JSONArray(s.scores))
            .put("times", JSONArray(s.times))
            .put("worst", JSONArray(s.worst))
            .put("isDay", JSONArray(s.isDay))
            .put("precipProb", JSONArray(s.precipProb))
            .put("precipIn", JSONArray(s.precipIn))
            .put("bestStart", s.bestStart ?: JSONObject.NULL)
            .put("bestEnd", s.bestEnd ?: JSONObject.NULL)
            .put("note", s.note)
            .put("updatedAtMs", s.updatedAtMs)
            .put("firstHourMs", s.firstHourMs)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }

    fun load(context: Context): Snapshot? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return null
        return try {
            val o = JSONObject(raw)
            fun dbl(k: String) = if (o.isNull(k)) null else o.getDouble(k)
            fun strList(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
            fun dblList(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getDouble(it) } }
            // Optional (added after the first release): a snapshot cached by an older
            // build simply has no rain series, and the graph draws no rain line.
            fun optDblList(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optDouble(it, 0.0) } } ?: emptyList()
            fun intList(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
            fun boolList(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getBoolean(it) } } ?: emptyList()
            Snapshot(
                action = runCatching { Action.valueOf(o.getString("action")) }.getOrDefault(Action.UNKNOWN),
                headline = o.getString("headline"),
                summary = o.getString("summary"),
                tempF = dbl("tempF"),
                dewPointF = dbl("dewPointF"),
                aqi = if (o.isNull("aqi")) null else o.getInt("aqi"),
                nowScore = o.optDouble("nowScore", 0.0),
                confidence = o.getString("confidence"),
                sourcesOk = strList("sourcesOk"),
                sourcesTotal = o.optInt("sourcesTotal", strList("sourcesOk").size),
                scores = dblList("scores"),
                times = strList("times"),
                worst = intList("worst"),
                isDay = boolList("isDay"),
                precipProb = optDblList("precipProb"),
                precipIn = optDblList("precipIn"),
                bestStart = if (o.isNull("bestStart")) null else o.getString("bestStart"),
                bestEnd = if (o.isNull("bestEnd")) null else o.getString("bestEnd"),
                note = o.optString("note", ""),
                updatedAtMs = o.getLong("updatedAtMs"),
                firstHourMs = o.optLong("firstHourMs", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Drop the cache so the next render refetches (e.g. after a settings change). */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
