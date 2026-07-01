package com.acwindows

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText

/**
 * Widget-only settings, kept separate from the global app/comfort settings
 * ([ConfigActivity]). Two ways in: it's the widget's `android:configure` activity
 * (shown when a widget is added / reconfigured), and the app's gear icon opens it on
 * long-press. Writes the same SharedPreferences [AppConfig.load] reads.
 */
class WidgetSettingsActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // If the user backs out of the add flow, no widget is added.
        setResult(RESULT_CANCELED, resultIntent())

        setContentView(R.layout.activity_widget_settings)
        val cfg = AppConfig.load(this)
        field(R.id.widget_hours).setText(cfg.widgetForecastHours.toString())
        field(R.id.widget_opacity).setText(cfg.widgetOpacity.toString())

        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
    }

    private fun save() {
        val cfg = AppConfig.load(this)
        val hours = field(R.id.widget_hours).text.toString().toIntOrNull()?.coerceIn(6, 96)
            ?: cfg.widgetForecastHours
        val opacity = field(R.id.widget_opacity).text.toString().toIntOrNull()?.coerceIn(0, 100)
            ?: cfg.widgetOpacity

        getSharedPreferences("ac_windows", MODE_PRIVATE).edit()
            .putString("widgetForecastHours", hours.toString())
            .putString("widgetOpacity", opacity.toString())
            .apply()

        // Forecast length changed → cached scores are the wrong span; refetch + redraw.
        WidgetCache.clear(this)
        AcWindowsProvider.refreshNow(this)

        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun field(id: Int): EditText = findViewById(id)
    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}
