package com.acwindows

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Properties

/**
 * On-device store for user-pasted API keys (hyperlocal weather / air-quality
 * providers). Kept in a dedicated file in the app's private storage — separate from
 * the SharedPreferences config, never bundled in the APK, and preserved across app
 * updates (only a clear-data/uninstall wipes it).
 *
 * Security: the file lives in [Context.getFilesDir] (app-sandboxed, not world-
 * readable) and is re-chmod'd to 0600 on every write per the project's secret-handling
 * baseline. Keys are never logged.
 */
object SecretsStore {

    /** Known provider key slots. [id] is the storage key + the SourceReading source name. */
    enum class Key(val id: String, val label: String, val signupUrl: String) {
        TOMORROW_IO("tomorrowio", "Tomorrow.io", "https://app.tomorrow.io/development/keys"),
        PIRATE_WEATHER("pirateweather", "Pirate Weather", "https://pirateweather.net/"),
        AIRNOW("airnow", "AirNow (EPA AQI)", "https://docs.airnowapi.org/account/request/"),
    }

    private const val FILE = "api_keys.properties"

    fun load(context: Context): Map<String, String> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyMap()
        return try {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.entries.associate { it.key.toString() to it.value.toString().trim() }
                .filterValues { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w("AcWindows", "secrets load failed")  // never log the values
            emptyMap()
        }
    }

    /** A key's value, or null when unset/blank. */
    fun get(context: Context, key: Key): String? = load(context)[key.id]?.takeIf { it.isNotBlank() }

    /** Persist the given slots; a blank/null value clears that slot. Re-applies 0600. */
    fun save(context: Context, values: Map<Key, String?>) {
        val merged = load(context).toMutableMap()
        values.forEach { (k, v) ->
            val t = v?.trim().orEmpty()
            if (t.isEmpty()) merged.remove(k.id) else merged[k.id] = t
        }
        val f = File(context.filesDir, FILE)
        try {
            val props = Properties().apply { merged.forEach { (k, v) -> setProperty(k, v) } }
            f.outputStream().use { props.store(it, "AC / Windows API keys — do not share") }
            // Lock down: owner read/write only (the dir is already app-private 0700).
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        } catch (e: Exception) {
            Log.w("AcWindows", "secrets save failed")
        }
    }
}

/** Resolved API keys for one fetch (null = not configured). */
data class Secrets(
    val tomorrowIo: String? = null,
    val pirateWeather: String? = null,
    val airNow: String? = null,
) {
    companion object {
        fun load(context: Context) = Secrets(
            tomorrowIo = SecretsStore.get(context, SecretsStore.Key.TOMORROW_IO),
            pirateWeather = SecretsStore.get(context, SecretsStore.Key.PIRATE_WEATHER),
            airNow = SecretsStore.get(context, SecretsStore.Key.AIRNOW),
        )
    }
}
