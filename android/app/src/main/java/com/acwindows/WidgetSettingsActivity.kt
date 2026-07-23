package com.acwindows

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText

/**
 * Widget-only settings, kept separate from the global app/comfort settings
 * ([ConfigActivity]). Two ways in: it's the widget's `android:configure` activity
 * (shown when a widget is added / reconfigured), and the app's gear icon opens it on
 * long-press. Writes the same SharedPreferences [AppConfig.load] reads.
 *
 * Like [ConfigActivity] there is no Save button: edits commit when you leave the screen,
 * and the header's "Discard changes" button backs them out.
 */
class WidgetSettingsActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var staged: StagedEdits

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // There's no Cancel path to report, and the result has to be set before the
        // activity finishes — so accept the add up front. Leaving by Back adds the
        // widget with whatever settings are on screen.
        setResult(RESULT_OK, resultIntent())

        setContentView(R.layout.activity_widget_settings)
        val cfg = AppConfig.load(this)
        field(R.id.widget_hours).setText(cfg.widgetForecastHours.toString())
        field(R.id.widget_opacity).setText(cfg.widgetOpacity.toString())

        val discard = findViewById<Button>(R.id.discard)
        staged = StagedEdits(listOf(field(R.id.widget_hours), field(R.id.widget_opacity))) { dirty ->
            discard.visibility = if (dirty) View.VISIBLE else View.GONE
        }
        discard.setOnClickListener { staged.discard() }
    }

    /** Back press: commit before the screen behind us resumes. */
    override fun onPause() {
        super.onPause()
        if (isFinishing) saveIfDirty()
    }

    /** Home / app switcher is leaving too; a rotation isn't. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) saveIfDirty()
    }

    private fun saveIfDirty() {
        if (staged.isDirty()) save()
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

        staged.commit()
    }

    private fun field(id: Int): EditText = findViewById(id)
    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}
