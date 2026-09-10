package com.acwindows

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

/**
 * Per-widget-instance settings for the forecast widget (docs/ux-redesign.md §C.5): horizon,
 * which series to draw, theme and text size, over a live preview. The preview renders through
 * [ForecastPanel] at the real 4x2 size, so it is the widget rather than a picture of one —
 * which is what makes each toggle a decision you can make by looking instead of by reading.
 *
 * This is also the widget's `android:configure` activity, so it runs once as part of adding
 * the widget: [RESULT_OK] is set up front and Save commits and finishes, but leaving by Back
 * commits too, so no route out of here loses the widget.
 */
class ForecastWidgetSettingsActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var saved: ForecastWidgetConfig = ForecastWidgetConfig()

    private val poll = Handler(Looper.getMainLooper())
    private var pollsLeft = 0

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

        // How far ahead the app fetches is one global setting shared with the windows
        // widget, so a horizon past it silently shows fewer hours. Name the cap here
        // rather than let the preview be the only place it shows up.
        findViewById<TextView>(R.id.hours_hint).text =
            "${ForecastWidgetConfig.MIN_HOURS}–${ForecastWidgetConfig.MAX_HOURS}, always starting at " +
                "the current hour. The app currently fetches ${AppConfig.load(this).widgetForecastHours} " +
                "hours ahead — past that this widget just shows every hour it has."

        val discard = findViewById<Button>(R.id.discard)
        val changed = {
            discard.visibility = if (current() == saved) View.GONE else View.VISIBLE
            renderPreview()
        }
        val onToggle = CompoundButton.OnCheckedChangeListener { _, _ -> changed() }
        allToggles().forEach { it.setOnCheckedChangeListener(onToggle) }

        val textScale = findViewById<SeekBar>(R.id.text_scale)
        val textScaleValue = findViewById<TextView>(R.id.text_scale_value)
        textScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                textScaleValue.text = "${ForecastWidgetConfig.clampTextScale(progress)}%"
                changed()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        hoursField().addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = changed()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        discard.setOnClickListener {
            apply(saved)
            discard.visibility = View.GONE
            renderPreview()
        }
        findViewById<Button>(R.id.save).apply {
            // The add flow allocates the id but has not placed the widget yet, so the
            // provider doesn't list it — which is what tells the two entry points apart.
            // No id at all means this was opened from the app, not from a widget.
            val placing = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
                widgetId !in ForecastWidgetProvider.widgetIds(this@ForecastWidgetSettingsActivity).toList()
            text = if (placing) "Add widget" else "Save"
            setOnClickListener { commit(); finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        renderPreview()
        // Nothing has ever been fetched (fresh install, or the widget is being added before
        // the app has run): ask for a refresh and watch for it, so the preview fills in
        // instead of sitting empty for the whole visit.
        if (WidgetCache.load(this) == null) {
            AcWindowsProvider.enqueueRefresh(this)
            pollsLeft = PREVIEW_POLLS
            poll.postDelayed(::pollForSnapshot, PREVIEW_POLL_MS)
        }
    }

    override fun onPause() {
        super.onPause()
        poll.removeCallbacksAndMessages(null)
        if (isFinishing) commit()
    }

    private fun pollForSnapshot() {
        if (WidgetCache.load(this) != null) {
            renderPreview()
            return
        }
        if (--pollsLeft > 0) poll.postDelayed(::pollForSnapshot, PREVIEW_POLL_MS)
    }

    private fun commit() {
        val cfg = current()
        if (cfg != saved) {
            ForecastWidgetConfig.save(this, widgetId, cfg)
            saved = cfg
        }
        ForecastWidgetProvider.renderAll(this, refreshing = false)
    }

    private fun allToggles(): List<CompoundButton> = listOf(
        R.id.theme_auto, R.id.theme_light, R.id.theme_dark,
    ).map { findViewById<RadioButton>(it) } +
        SERIES_IDS.map { findViewById<CheckBox>(it) } +
        listOf(
            findViewById<CheckBox>(R.id.show_daynight),
            findViewById<CheckBox>(R.id.hide_rain),
            findViewById<CheckBox>(R.id.tap_opens),
        )

    private fun apply(cfg: ForecastWidgetConfig) {
        hoursField().setText(cfg.hours.toString())
        checkBox(R.id.show_temp).isChecked = cfg.showTemp
        checkBox(R.id.show_dew).isChecked = cfg.showDew
        checkBox(R.id.show_aqi).isChecked = cfg.showAqi
        checkBox(R.id.show_rain).isChecked = cfg.showRain
        checkBox(R.id.show_daynight).isChecked = cfg.showDayNight
        check(
            when (cfg.theme) {
                ForecastTheme.LIGHT -> R.id.theme_light
                ForecastTheme.DARK -> R.id.theme_dark
                ForecastTheme.AUTO -> R.id.theme_auto
            },
        )
        checkBox(R.id.hide_rain).isChecked = cfg.hideUnlikelyRain
        checkBox(R.id.tap_opens).isChecked = cfg.tapOpensApp
        val textScale = findViewById<SeekBar>(R.id.text_scale)
        textScale.progress = ForecastWidgetConfig.clampTextScale(cfg.textScalePct)
        findViewById<TextView>(R.id.text_scale_value).text = "${textScale.progress}%"
    }

    private fun check(id: Int) {
        findViewById<RadioButton>(id).isChecked = true
    }

    private fun checkBox(id: Int): CheckBox = findViewById(id)

    private fun hoursField(): EditText = findViewById(R.id.hours)

    /** A half-typed or out-of-range field keeps the saved value rather than snapping to it. */
    private fun current() = ForecastWidgetConfig(
        hours = ForecastWidgetConfig.clampHours(hoursField().text.toString().toIntOrNull() ?: saved.hours),
        showTemp = checkBox(R.id.show_temp).isChecked,
        showDew = checkBox(R.id.show_dew).isChecked,
        showAqi = checkBox(R.id.show_aqi).isChecked,
        showRain = checkBox(R.id.show_rain).isChecked,
        showDayNight = checkBox(R.id.show_daynight).isChecked,
        theme = when (findViewById<RadioGroup>(R.id.theme_group).checkedRadioButtonId) {
            R.id.theme_light -> ForecastTheme.LIGHT
            R.id.theme_dark -> ForecastTheme.DARK
            else -> ForecastTheme.AUTO
        },
        hideUnlikelyRain = checkBox(R.id.hide_rain).isChecked,
        tapOpensApp = checkBox(R.id.tap_opens).isChecked,
        textScalePct = ForecastWidgetConfig.clampTextScale(findViewById<SeekBar>(R.id.text_scale).progress),
    )

    /** The real renderer at the real frame size, so the preview IS the widget. */
    private fun renderPreview() {
        val view = findViewById<ImageView>(R.id.preview)
        val empty = findViewById<TextView>(R.id.preview_empty)
        val snap = WidgetCache.load(this)
        if (snap == null || snap.scores.isEmpty()) {
            view.setImageBitmap(null)
            empty.visibility = View.VISIBLE
            empty.text = if (snap == null) {
                "Fetching the forecast — the preview appears when it lands."
            } else {
                "No hourly forecast in the last fetch; the preview appears once one arrives."
            }
            return
        }
        empty.visibility = View.GONE
        val cfg = current()
        val density = resources.displayMetrics.density
        view.setImageBitmap(
            ForecastPanel.render(
                this, snap, AppConfig.load(this), cfg, TempUnit.load(this),
                widthPx = (372 * density).toInt(),
                heightPx = (166 * density).toInt(),
                density = density,
                dark = cfg.isDark(this),
                textScale = cfg.textScalePct / 100f,
            ),
        )
    }

    private fun resultIntent() =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private companion object {
        const val PREVIEW_POLL_MS = 1500L
        const val PREVIEW_POLLS = 20
        val SERIES_IDS = listOf(R.id.show_temp, R.id.show_dew, R.id.show_aqi, R.id.show_rain)
    }
}
