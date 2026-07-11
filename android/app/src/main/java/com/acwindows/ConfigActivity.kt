package com.acwindows

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * On-device widget settings. Shown when the widget is added, and re-openable
 * later (long-press the widget → reconfigure, Android 12+). Writes to the same
 * SharedPreferences that [AppConfig.load] reads, so changes take effect on the
 * next refresh. Settings are global (shared by all widget instances).
 */
class ConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val scope = MainScope()

    // The label + coordinates last resolved via "Use my location". We only persist the
    // label if the saved lat/lon still match these (hand-editing them clears it).
    private var pendingPlaceName: String? = null
    private var pendingLat: Double? = null
    private var pendingLon: Double? = null

    // Weight EditText id ↔ source name (ids can't contain hyphens; the source name can).
    private val weightFields = listOf(
        R.id.weight_openmeteo to "open-meteo",
        R.id.weight_nws to "nws",
        R.id.weight_metno to "metno",
        R.id.weight_tomorrowio to "tomorrowio",
        R.id.weight_pirateweather to "pirateweather",
        R.id.weight_airnow to "airnow",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default result: if the user backs out during the add flow, no widget is added.
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, resultIntent())

        setContentView(R.layout.activity_config)

        // Pre-fill with the current effective config (prefs merged with defaults).
        val cfg = AppConfig.load(this)
        field(R.id.open_score).setText(cfg.comfort.openScoreMin.toInt().toString())
        field(R.id.temp_ideal).setText(trimNum(cfg.comfort.tempIdealF))
        field(R.id.max_temp).setText(trimNum(cfg.comfort.maxTempF))
        field(R.id.dew_ideal).setText(trimNum(cfg.comfort.dewPointIdealF))
        field(R.id.dew_max).setText(trimNum(cfg.comfort.dewPointMaxF))
        field(R.id.aqi_ideal).setText(trimNum(cfg.comfort.aqiIdeal))
        field(R.id.aqi_max).setText(cfg.comfort.aqiMax.toString())
        field(R.id.rain_ideal).setText(trimNum(cfg.comfort.rainProbIdeal))
        field(R.id.rain_max).setText(trimNum(cfg.comfort.rainProbMax))
        field(R.id.lat).setText(cfg.lat.toString())
        field(R.id.lon).setText(cfg.lon.toString())

        // Pre-fill any saved API keys and wire the "get a key" links to each signup page.
        field(R.id.key_tomorrowio).setText(SecretsStore.get(this, SecretsStore.Key.TOMORROW_IO) ?: "")
        field(R.id.key_pirateweather).setText(SecretsStore.get(this, SecretsStore.Key.PIRATE_WEATHER) ?: "")
        field(R.id.key_airnow).setText(SecretsStore.get(this, SecretsStore.Key.AIRNOW) ?: "")
        link(R.id.link_tomorrowio, SecretsStore.Key.TOMORROW_IO.signupUrl)
        link(R.id.link_pirateweather, SecretsStore.Key.PIRATE_WEATHER.signupUrl)
        link(R.id.link_airnow, SecretsStore.Key.AIRNOW.signupUrl)

        // Per-source trust weights (default 1.0).
        weightFields.forEach { (id, src) -> field(id).setText(trimNum(cfg.sourceWeights[src] ?: 1.0)) }

        // Show the saved location label (if the coordinates came from device location).
        cfg.placeName?.let { setLocStatus("Using $it") }

        findViewById<Button>(R.id.use_location).setOnClickListener { onUseLocation() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ───────── on-device location ─────────

    private fun onUseLocation() {
        if (LocationProvider.hasPermission(this)) fetchLocation()
        else requestPermissions(LocationProvider.permissions, REQ_LOCATION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQ_LOCATION) return
        if (results.any { it == PackageManager.PERMISSION_GRANTED }) fetchLocation()
        else setLocStatus("Location permission denied — enter a lat/long by hand.")
    }

    private fun fetchLocation() {
        setLocStatus("Locating…")
        scope.launch {
            val loc = LocationProvider.currentLocation(this@ConfigActivity)
            if (loc == null) {
                setLocStatus("Couldn't get a location — check location is on, or enter it by hand.")
                return@launch
            }
            // Round to ~11 m: enough for weather, and avoids storing a needlessly exact fix.
            val lat = round4(loc.latitude)
            val lon = round4(loc.longitude)
            field(R.id.lat).setText(lat.toString())
            field(R.id.lon).setText(lon.toString())
            pendingLat = lat
            pendingLon = lon
            pendingPlaceName = null
            val name = LocationProvider.placeName(this@ConfigActivity, lat, lon)
            pendingPlaceName = name
            setLocStatus(name?.let { "Using $it" } ?: "Location set")
        }
    }

    private fun setLocStatus(msg: String) = findViewById<TextView>(R.id.loc_status).setText(msg)

    private fun round4(d: Double) = Math.round(d * 10000.0) / 10000.0

    /** The label to persist for the saved coordinates, or null if they're hand-entered. */
    private fun placeNameFor(cfg: AppConfig, lat: Double, lon: Double): String? = when {
        pendingPlaceName != null && lat == pendingLat && lon == pendingLon -> pendingPlaceName
        lat == cfg.lat && lon == cfg.lon -> cfg.placeName   // unchanged → keep the stored label
        else -> null                                        // changed by hand → no known label
    }

    private fun link(id: Int, url: String) = findViewById<TextView>(id).setOnClickListener {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun save() {
        val cfg = AppConfig.load(this)
        // Parse each field, falling back to the current value if it's blank/invalid.
        val tempIdeal = field(R.id.temp_ideal).text.toString().toDoubleOrNull() ?: cfg.comfort.tempIdealF
        val maxTemp = field(R.id.max_temp).text.toString().toDoubleOrNull() ?: cfg.comfort.maxTempF
        val openScore = field(R.id.open_score).text.toString().toDoubleOrNull()
            ?.coerceIn(0.0, 100.0) ?: cfg.comfort.openScoreMin
        val dewIdeal = field(R.id.dew_ideal).text.toString().toDoubleOrNull() ?: cfg.comfort.dewPointIdealF
        val dewMax = field(R.id.dew_max).text.toString().toDoubleOrNull() ?: cfg.comfort.dewPointMaxF
        val aqiIdeal = field(R.id.aqi_ideal).text.toString().toDoubleOrNull() ?: cfg.comfort.aqiIdeal
        val aqiMax = field(R.id.aqi_max).text.toString().toIntOrNull() ?: cfg.comfort.aqiMax
        val rainIdeal = field(R.id.rain_ideal).text.toString().toDoubleOrNull() ?: cfg.comfort.rainProbIdeal
        val rainMax = field(R.id.rain_max).text.toString().toDoubleOrNull() ?: cfg.comfort.rainProbMax
        val lat = field(R.id.lat).text.toString().toDoubleOrNull() ?: cfg.lat
        val lon = field(R.id.lon).text.toString().toDoubleOrNull() ?: cfg.lon
        val placeName = placeNameFor(cfg, lat, lon)

        val ed = getSharedPreferences("ac_windows", MODE_PRIVATE).edit()
            .putString("tempIdealF", tempIdeal.toString())
            .putString("maxTempF", maxTemp.toString())
            .putString("openScoreMin", openScore.toString())
            .putString("dewPointIdealF", dewIdeal.toString())
            .putString("dewPointMaxF", dewMax.toString())
            .putString("aqiIdeal", aqiIdeal.toString())
            .putString("aqiMax", aqiMax.toString())
            .putString("rainProbIdeal", rainIdeal.toString())
            .putString("rainProbMax", rainMax.toString())
            .putString("lat", lat.toString())
            .putString("lon", lon.toString())
            .putString("placeName", placeName ?: "")
        weightFields.forEach { (id, src) ->
            val w = field(id).text.toString().toDoubleOrNull()?.coerceIn(0.0, 10.0) ?: 1.0
            ed.putString("weight_$src", trimNum(w))
        }
        ed.apply()

        // API keys live in their own private file (not SharedPreferences); blank clears a slot.
        SecretsStore.save(
            this,
            mapOf(
                SecretsStore.Key.TOMORROW_IO to field(R.id.key_tomorrowio).text.toString(),
                SecretsStore.Key.PIRATE_WEATHER to field(R.id.key_pirateweather).text.toString(),
                SecretsStore.Key.AIRNOW to field(R.id.key_airnow).text.toString(),
            ),
        )

        // New comfort/location/keys → cached scores are stale; refetch + redraw.
        WidgetCache.clear(this)
        AcWindowsProvider.refreshNow(this)

        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun field(id: Int): EditText = findViewById(id)
    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
    private fun trimNum(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    private companion object {
        const val REQ_LOCATION = 42
    }
}
