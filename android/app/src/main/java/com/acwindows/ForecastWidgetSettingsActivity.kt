package com.acwindows

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup

/**
 * Per-widget-instance settings for the forecast widget (docs/ux-redesign.md §C.5): three
 * questions and a live preview: horizon, detail and theme.
 * matrix. The preview renders through [ForecastPanel] at the real 4x2 size, so it is the
 * widget rather than a picture of one — which is what makes "Compact" a decision you can
 * make by looking instead of by reading.
 *
 * Same idiom as the other settings screens: no Save button, edits commit on leave.
 */
class ForecastWidgetSettingsActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var saved: ForecastWidgetConfig = ForecastWidgetConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Has to be set before the activity can finish — leaving by Back adds the widget
        // with whatever is on screen.
        setResult(RESULT_OK, resultIntent())

        setContentView(R.layout.activity_forecast_widget_settings)
        saved = ForecastWidgetConfig.load(this, widgetId)
        apply(saved)

        val discard = findViewById<Button>(R.id.discard)
        val onChange = CompoundButton.OnCheckedChangeListener { _, _ ->
            discard.visibility = if (current() == saved) View.GONE else View.VISIBLE
            renderPreview()
        }
        allToggles().forEach { it.setOnCheckedChangeListener(onChange) }
        discard.setOnClickListener {
            apply(saved)
            discard.visibility = View.GONE
            renderPreview()
        }
        renderPreview()
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) {
            val cfg = current()
            if (cfg != saved) {
                ForecastWidgetConfig.save(this, widgetId, cfg)
                saved = cfg
            }
            ForecastWidgetProvider.renderAll(this, refreshing = false)
        }
    }

    private fun allToggles(): List<CompoundButton> = listOf(
        R.id.hours_12, R.id.hours_24, R.id.hours_36,
        R.id.detail_compact, R.id.detail_standard, R.id.detail_everything,
        R.id.theme_auto, R.id.theme_light, R.id.theme_dark,
    ).map { findViewById<RadioButton>(it) } +
        listOf(findViewById<CheckBox>(R.id.hide_rain), findViewById<CheckBox>(R.id.tap_opens))

    private fun apply(cfg: ForecastWidgetConfig) {
        check(
            when (cfg.hours) {
                12 -> R.id.hours_12
                36 -> R.id.hours_36
                else -> R.id.hours_24
            },
        )
        check(
            when (cfg.detail) {
                ForecastDetail.COMPACT -> R.id.detail_compact
                ForecastDetail.EVERYTHING -> R.id.detail_everything
                ForecastDetail.STANDARD -> R.id.detail_standard
            },
        )
        check(
            when (cfg.theme) {
                ForecastTheme.LIGHT -> R.id.theme_light
                ForecastTheme.DARK -> R.id.theme_dark
                ForecastTheme.AUTO -> R.id.theme_auto
            },
        )
        findViewById<CheckBox>(R.id.hide_rain).isChecked = cfg.hideUnlikelyRain
        findViewById<CheckBox>(R.id.tap_opens).isChecked = cfg.tapOpensApp
    }

    private fun check(id: Int) {
        findViewById<RadioButton>(id).isChecked = true
    }

    private fun current() = ForecastWidgetConfig(
        hours = when (findViewById<RadioGroup>(R.id.hours_group).checkedRadioButtonId) {
            R.id.hours_12 -> 12
            R.id.hours_36 -> 36
            else -> 24
        },
        detail = when (findViewById<RadioGroup>(R.id.detail_group).checkedRadioButtonId) {
            R.id.detail_compact -> ForecastDetail.COMPACT
            R.id.detail_everything -> ForecastDetail.EVERYTHING
            else -> ForecastDetail.STANDARD
        },
        theme = when (findViewById<RadioGroup>(R.id.theme_group).checkedRadioButtonId) {
            R.id.theme_light -> ForecastTheme.LIGHT
            R.id.theme_dark -> ForecastTheme.DARK
            else -> ForecastTheme.AUTO
        },
        hideUnlikelyRain = findViewById<CheckBox>(R.id.hide_rain).isChecked,
        tapOpensApp = findViewById<CheckBox>(R.id.tap_opens).isChecked,
    )

    /** The real renderer at the real frame size; empty cache simply draws nothing. */
    private fun renderPreview() {
        val view = findViewById<ImageView>(R.id.preview)
        val snap = WidgetCache.load(this)
        if (snap == null || snap.scores.isEmpty()) {
            view.setImageBitmap(null)
            return
        }
        val cfg = current()
        val density = resources.displayMetrics.density
        view.setImageBitmap(
            ForecastPanel.render(
                this, snap, AppConfig.load(this), cfg, TempUnit.load(this),
                widthPx = (372 * density).toInt(),
                heightPx = (166 * density).toInt(),
                density = density,
                dark = cfg.isDark(this),
            ),
        )
    }

    private fun resultIntent() =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}
