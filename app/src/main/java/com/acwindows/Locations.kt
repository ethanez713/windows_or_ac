package com.acwindows

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class LocationKind { CURRENT, FIXED }

/**
 * One place the forecast can be for. The CURRENT entry follows the phone; its [lat]/[lon]/
 * [placeName] are the last good fix, kept as the fallback when no fix can be had.
 */
data class SavedLocation(
    val id: String,
    val kind: LocationKind,
    val lat: Double,
    val lon: Double,
    val placeName: String? = null,
    val nickname: String? = null,
) {
    val isCurrent: Boolean get() = kind == LocationKind.CURRENT

    /** What the carousel and pickers show. */
    val label: String
        get() = nickname ?: if (isCurrent) "Current location" else placeLabel

    /** The place itself, ignoring any nickname — the header and the list's second line. */
    val placeLabel: String get() = placeName ?: "$lat, $lon"
}

/**
 * The ordered list of saved locations and which one the app shows. Always holds exactly one
 * CURRENT entry, which cannot be removed.
 */
object Locations {
    const val MAX = 20
    const val NICKNAME_MAX = 32
    const val CURRENT_ID = "current"

    private const val PREFS = "ac_windows"
    private const val KEY_LIST = "locations"
    private const val KEY_SELECTED = "selectedLocationId"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<SavedLocation> {
        val p = prefs(context)
        val raw = p.getString(KEY_LIST, null) ?: return migrate(context, p)
        val parsed = runCatching { decode(raw) }.getOrDefault(emptyList())
        return withSingleCurrent(parsed)
    }

    fun save(context: Context, list: List<SavedLocation>) {
        prefs(context).edit().putString(KEY_LIST, encode(withSingleCurrent(list))).apply()
    }

    fun byId(context: Context, id: String?): SavedLocation {
        val list = load(context)
        return list.firstOrNull { it.id == id } ?: list.first { it.isCurrent }
    }

    fun selected(context: Context): SavedLocation =
        byId(context, prefs(context).getString(KEY_SELECTED, null))

    fun select(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SELECTED, id).apply()
    }

    /** Appends a fixed place; null when the list is already at [MAX]. */
    fun add(context: Context, lat: Double, lon: Double, placeName: String?): SavedLocation? {
        val list = load(context)
        if (list.size >= MAX) return null
        val loc = SavedLocation(UUID.randomUUID().toString(), LocationKind.FIXED, lat, lon, placeName)
        save(context, list + loc)
        return loc
    }

    /** False for the CURRENT entry or an unknown id. Drops the location's cached snapshot. */
    fun remove(context: Context, id: String): Boolean {
        val list = load(context)
        val target = list.firstOrNull { it.id == id } ?: return false
        if (target.isCurrent) return false
        save(context, list - target)
        WidgetCache.clear(context, id)
        if (prefs(context).getString(KEY_SELECTED, null) == id) select(context, CURRENT_ID)
        return true
    }

    /** A blank nickname clears it; longer than [NICKNAME_MAX] is cut. */
    fun rename(context: Context, id: String, nickname: String?) {
        val nick = nickname?.trim()?.take(NICKNAME_MAX)?.ifEmpty { null }
        save(context, load(context).map { if (it.id == id) it.copy(nickname = nick) else it })
    }

    fun move(context: Context, from: Int, to: Int) {
        save(context, moved(load(context), from, to))
    }

    fun moved(list: List<SavedLocation>, from: Int, to: Int): List<SavedLocation> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun updateCurrentFix(context: Context, lat: Double, lon: Double, placeName: String?) {
        save(context, load(context).map {
            if (it.isCurrent) it.copy(lat = lat, lon = lon, placeName = placeName) else it
        })
    }

    /**
     * First read on an install that predates the list. Its single saved place becomes the
     * CURRENT entry's fallback fix, and — when it was a pinned place rather than the phone's
     * position — also a FIXED entry that stays selected, so nothing visibly moves.
     */
    private fun migrate(context: Context, p: SharedPreferences): List<SavedLocation> {
        val lat = p.getString(LEGACY_LAT, null)?.toDoubleOrNull()
        val lon = p.getString(LEGACY_LON, null)?.toDoubleOrNull()
        val place = p.getString(LEGACY_PLACE, null)?.takeIf { it.isNotBlank() }
        val followed = p.getBoolean(LEGACY_FOLLOW, false)
        val current = SavedLocation(
            CURRENT_ID, LocationKind.CURRENT,
            lat ?: AppConfig.DEFAULT_LAT, lon ?: AppConfig.DEFAULT_LON, place,
        )
        val pinned = if (lat != null && lon != null && !followed)
            SavedLocation(UUID.randomUUID().toString(), LocationKind.FIXED, lat, lon, place) else null
        val list = listOfNotNull(current, pinned)
        val selectedId = pinned?.id ?: CURRENT_ID

        val e = p.edit()
            .putString(KEY_LIST, encode(list))
            .putString(KEY_SELECTED, selectedId)
            .remove(LEGACY_LAT).remove(LEGACY_LON).remove(LEGACY_PLACE).remove(LEGACY_FOLLOW)
        ForecastWidgetProvider.widgetIds(context).forEach { id ->
            e.putString(ForecastWidgetConfig.locationKey(id), selectedId)
        }
        p.getString(WidgetCache.LEGACY_KEY, null)?.let { snap ->
            e.putString(WidgetCache.keyFor(selectedId), snap).remove(WidgetCache.LEGACY_KEY)
        }
        e.apply()
        return list
    }

    private fun withSingleCurrent(list: List<SavedLocation>): List<SavedLocation> {
        val currents = list.filter { it.isCurrent }
        if (currents.size == 1) return list.take(MAX)
        val current = currents.firstOrNull()
            ?: SavedLocation(CURRENT_ID, LocationKind.CURRENT, AppConfig.DEFAULT_LAT, AppConfig.DEFAULT_LON)
        return (listOf(current) + list.filterNot { it.isCurrent }).take(MAX)
    }

    private fun encode(list: List<SavedLocation>): String = JSONArray().apply {
        list.forEach { l ->
            put(
                JSONObject()
                    .put("id", l.id)
                    .put("kind", l.kind.name)
                    .put("lat", l.lat)
                    .put("lon", l.lon)
                    .put("placeName", l.placeName ?: JSONObject.NULL)
                    .put("nickname", l.nickname ?: JSONObject.NULL),
            )
        }
    }.toString()

    private fun decode(raw: String): List<SavedLocation> {
        val a = JSONArray(raw)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val kind = runCatching { LocationKind.valueOf(o.getString("kind")) }.getOrNull()
                ?: return@mapNotNull null
            SavedLocation(
                id = if (kind == LocationKind.CURRENT) CURRENT_ID else o.getString("id"),
                kind = kind,
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                placeName = o.optString("placeName").takeIf { !o.isNull("placeName") && it.isNotBlank() },
                nickname = o.optString("nickname").takeIf { !o.isNull("nickname") && it.isNotBlank() },
            )
        }
    }

    private const val LEGACY_LAT = "lat"
    private const val LEGACY_LON = "lon"
    private const val LEGACY_PLACE = "placeName"
    private const val LEGACY_FOLLOW = "useDeviceLocation"
}
