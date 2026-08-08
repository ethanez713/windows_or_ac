package com.acwindows

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText

/**
 * Per-widget-instance settings for the graphs widget — unlike [WidgetSettingsActivity]'s
 * global config, several of these can be on screen at once with different panels showing,
 * so everything here is keyed by [widgetId] through [GraphsWidgetConfig]. Same idiom as
 * [WidgetSettingsActivity]: no Save button, edits commit on leave via [StagedEdits], the
 * header's "Discard changes" button backs them out.
 */
class GraphsWidgetSettingsActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var staged: StagedEdits

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // No Cancel path to report, and the result has to be set before the activity
        // finishes — leaving by Back adds the widget with whatever's on screen.
        setResult(RESULT_OK, resultIntent())

        setContentView(R.layout.activity_graphs_widget_settings)
        val cfg = GraphsWidgetConfig.load(this, widgetId)
        PANEL_IDS.forEach { (panel, showId, hourlyId) ->
            val shown = panel in cfg.panels
            check(showId).isChecked = shown
            check(hourlyId).isChecked = cfg.modes[panel] == PanelMode.HOURLY_ROW
            setHourlyEnabled(hourlyId, shown)
        }
        field(R.id.graphs_hours).setText(cfg.hours.toString())

        val discard = findViewById<Button>(R.id.discard)
        staged = StagedEdits(
            listOf(field(R.id.graphs_hours)),
            checks = PANEL_IDS.flatMap { listOf(check(it.second), check(it.third)) },
            onToggle = { btn, on ->
                // A panel's own show/hide box enables or disables its "hourly list" box —
                // you can't pick a rendering for a panel that isn't shown. Also runs on
                // discard(), which is what restores the enabled state too.
                PANEL_IDS.firstOrNull { it.second == btn.id }?.let { setHourlyEnabled(it.third, on) }
            },
        ) { dirty -> discard.visibility = if (dirty) View.VISIBLE else View.GONE }
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
        val panels = PANEL_IDS.filter { check(it.second).isChecked }.map { it.first }
        val modes = PANEL_IDS.associate { (panel, _, hourlyId) ->
            panel to if (check(hourlyId).isChecked) PanelMode.HOURLY_ROW else PanelMode.TIMESERIES
        }
        val hours = field(R.id.graphs_hours).text.toString().toIntOrNull()?.coerceIn(6, 48)
            ?: GraphsWidgetConfig().hours

        GraphsWidgetConfig.save(this, widgetId, GraphsWidgetConfig(panels, modes, hours))

        // A changed look-ahead doesn't need a refetch — a panel just draws whatever the
        // cached series already has (see PanelGraph slicing "next N hours from now"). No
        // WidgetCache.clear here: that would throw away the OTHER widget's data too.
        GraphsWidgetProvider.renderAll(this, refreshing = false)

        staged.commit()
    }

    private fun setHourlyEnabled(hourlyId: Int, enabled: Boolean) {
        val box = check(hourlyId)
        box.isEnabled = enabled
        box.alpha = if (enabled) 1f else 0.4f
    }

    private fun field(id: Int): EditText = findViewById(id)
    private fun check(id: Int): CheckBox = findViewById(id)
    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private companion object {
        /** (panel, its "show" checkbox id, its "show as hourly list" checkbox id) — in the
         *  order they appear on screen, which is also the panel display order. */
        val PANEL_IDS = listOf(
            Triple(Panel.TEMP_DEW, R.id.show_temp_dew, R.id.hourly_temp_dew),
            Triple(Panel.AQI_RAIN, R.id.show_aqi_rain, R.id.hourly_aqi_rain),
            Triple(Panel.SCORE, R.id.show_score, R.id.hourly_score),
        )
    }
}
