package com.acwindows

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * On-device widget settings. Shown when the widget is added, and re-openable
 * later (long-press the widget → reconfigure, Android 12+). Writes to the same
 * SharedPreferences that [AppConfig.load] reads, so changes take effect on the
 * next refresh. Settings are global (shared by all widget instances).
 */
class ConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

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

        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
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
}
