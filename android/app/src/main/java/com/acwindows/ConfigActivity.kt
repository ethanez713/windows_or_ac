package com.acwindows

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Global app / comfort settings (thresholds, location, API keys), opened from the app's
 * gear icon. Writes to the same SharedPreferences that [AppConfig.load] reads, so changes
 * take effect on the next refresh. Settings are global (shared by all widget instances).
 *
 * There is no Save button: edits are staged in the fields and committed when you leave
 * the screen (see [onStop]). The header's "Discard changes" button — which appears only
 * once something has been edited — is the way to back out.
 */
class ConfigActivity : Activity() {

    private lateinit var staged: StagedEdits

    /** The unit the four setpoint fields are CURRENTLY rendered in. */
    private lateinit var currentUnit: TempUnit

    /** The unit + values the screen opened with, for an exact (not reparsed) discard. */
    private lateinit var initialUnit: TempUnit
    private val initialTempF = mutableMapOf<Int, Double>()

    /** Every setpoint field that needs unit conversion (as opposed to aqi/rain, which don't). */
    private val tempFieldIds = listOf(R.id.temp_ideal, R.id.max_temp, R.id.dew_ideal, R.id.dew_max)

    // The °F value and exact text each setpoint field was last RENDERED from — see
    // [fahrenheitOf] for why this is what stops the toggle from drifting a stored setpoint
    // a fraction of a degree every time it's flipped.
    private val renderedF = mutableMapOf<Int, Double>()
    private val renderedText = mutableMapOf<Int, String>()

    // Weight EditText id ↔ source name (ids can't contain hyphens; the source name can).
    private val weightFields = listOf(
        R.id.weight_openmeteo to "open-meteo",
        R.id.weight_nws to "nws",
        R.id.weight_metno to "metno",
        R.id.weight_tomorrowio to "tomorrowio",
        R.id.weight_pirateweather to "pirateweather",
        R.id.weight_airnow to "airnow",
    )

    /** Every editable field on the screen, for staged-edit tracking. */
    private val valueFields = listOf(
        R.id.open_score, R.id.temp_ideal, R.id.max_temp, R.id.dew_ideal, R.id.dew_max,
        R.id.aqi_ideal, R.id.aqi_max, R.id.rain_ideal, R.id.rain_max,
        R.id.key_tomorrowio, R.id.key_pirateweather, R.id.key_airnow,
    ) + weightFields.map { it.first }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        // Pre-fill with the current effective config (prefs merged with defaults).
        val cfg = AppConfig.load(this)
        currentUnit = TempUnit.load(this)
        initialUnit = currentUnit
        initialTempF[R.id.temp_ideal] = cfg.comfort.tempIdealF
        initialTempF[R.id.max_temp] = cfg.comfort.maxTempF
        initialTempF[R.id.dew_ideal] = cfg.comfort.dewPointIdealF
        initialTempF[R.id.dew_max] = cfg.comfort.dewPointMaxF
        tempFieldIds.forEach { renderTempField(it, initialTempF.getValue(it)) }
        updateTempLabels(currentUnit)
        celsiusCheck().isChecked = currentUnit == TempUnit.C
        nowViewCheck().isChecked = cfg.dayViewMode == DayViewMode.NOW
        hourlyRowsCheck().isChecked = cfg.appShowHourlyRows
        field(R.id.open_score).setText(cfg.comfort.openScoreMin.toInt().toString())
        field(R.id.aqi_ideal).setText(trimNum(cfg.comfort.aqiIdeal))
        field(R.id.aqi_max).setText(cfg.comfort.aqiMax.toString())
        field(R.id.rain_ideal).setText(trimNum(cfg.comfort.rainProbIdeal))
        field(R.id.rain_max).setText(trimNum(cfg.comfort.rainProbMax))

        // Pre-fill any saved API keys and wire the "get a key" links to each signup page.
        field(R.id.key_tomorrowio).setText(SecretsStore.get(this, SecretsStore.Key.TOMORROW_IO) ?: "")
        field(R.id.key_pirateweather).setText(SecretsStore.get(this, SecretsStore.Key.PIRATE_WEATHER) ?: "")
        field(R.id.key_airnow).setText(SecretsStore.get(this, SecretsStore.Key.AIRNOW) ?: "")
        link(R.id.link_tomorrowio, SecretsStore.Key.TOMORROW_IO.signupUrl)
        link(R.id.link_pirateweather, SecretsStore.Key.PIRATE_WEATHER.signupUrl)
        link(R.id.link_airnow, SecretsStore.Key.AIRNOW.signupUrl)

        // Per-source trust weights (default 1.0).
        weightFields.forEach { (id, src) -> field(id).setText(trimNum(cfg.sourceWeights[src] ?: 1.0)) }

        // App text size (60–200%; the widget has its own, separate setting).
        textScaleSlider().progress = cfg.appTextScalePct
        updateTextScaleLabel(cfg.appTextScalePct)

        // Track edits from here on: whatever is in the fields now is the baseline.
        val discard = findViewById<Button>(R.id.discard)
        staged = StagedEdits(
            fields = valueFields.map { field(it) },
            checks = listOf(celsiusCheck(), nowViewCheck(), hourlyRowsCheck()),
            sliders = listOf(textScaleSlider()),
            onToggle = { btn, on -> if (btn.id == R.id.use_celsius) onUnitToggled(on) },
            onSlide = { _, progress -> updateTextScaleLabel(progress) },
        ) { dirty ->
            discard.visibility = if (dirty) View.VISIBLE else View.GONE
        }
        discard.setOnClickListener { discardChanges() }

        findViewById<Button>(R.id.open_locations).setOnClickListener {
            startActivity(Intent(this, LocationsActivity::class.java))
        }
    }

    /** Back press: commit before the screen behind us resumes, so it sees the new config. */
    override fun onPause() {
        super.onPause()
        if (isFinishing) saveIfDirty()
    }

    /**
     * Home / app switcher: still leaving the screen. Guarded so the two paths that
     * merely pause or recreate us — the location permission dialog (onPause only) and a
     * rotation (onStop with isChangingConfigurations) — don't save behind the user's back.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) saveIfDirty()
    }

    private fun saveIfDirty() {
        if (staged.isDirty()) save()
    }

    private fun discardChanges() {
        staged.discard()   // restores the fields, the unit checkbox and the slider
        // Belt-and-suspenders for the unit toggle: StagedEdits.discard() resets the fields'
        // TEXT to the baseline *before* it re-fires the unit checkbox's listener, so
        // onUnitToggled — which reparses whatever text is currently sitting in the fields —
        // can't be trusted to land on the exact original °F values from that ordering.
        // Recompute straight from initialTempF so a discard is always byte-exact.
        currentUnit = initialUnit
        tempFieldIds.forEach { renderTempField(it, initialTempF.getValue(it)) }
        updateTempLabels(currentUnit)
    }

    private fun celsiusCheck(): CheckBox = findViewById(R.id.use_celsius)
    private fun nowViewCheck(): CheckBox = findViewById(R.id.start_at_now)
    private fun hourlyRowsCheck(): CheckBox = findViewById(R.id.show_hourly_rows)
    private fun textScaleSlider(): SeekBar = findViewById(R.id.text_scale_slider)

    /**
     * Renders one setpoint field from its canonical °F value into [currentUnit], and
     * remembers both the °F value and the exact text produced — that's what lets
     * [fahrenheitOf] tell an untouched field from a genuinely edited one later.
     */
    private fun renderTempField(id: Int, fahrenheit: Double) {
        val text = fmtTemp(currentUnit.fromF(fahrenheit))
        field(id).setText(text)
        renderedF[id] = fahrenheit
        renderedText[id] = text
    }

    /**
     * The °F value a setpoint field currently represents. If its text is still exactly
     * what it was last rendered from, hand back the remembered °F value UNCHANGED — that's
     * the fix for 78°F → 25.5555…°C → displayed "25.6" → reparsed as 78.08°F, which would
     * otherwise walk a stored setpoint a fraction of a degree on every toggle. Only text
     * that's genuinely different gets reparsed, as a value typed in [fromUnit].
     */
    private fun fahrenheitOf(id: Int, fromUnit: TempUnit): Double {
        val known = renderedF[id] ?: 0.0
        val text = field(id).text.toString()
        if (text == renderedText[id]) return known
        return text.toDoubleOrNull()?.let { fromUnit.toF(it) } ?: known
    }

    /** Setpoint display text, rounded to one decimal — the raw converted value
     *  (78°F → 25.555555…°C) isn't something you'd want to see in a field you can retype. */
    private fun fmtTemp(v: Double): String = trimNum((v * 10).roundToInt() / 10.0)

    private fun updateTempLabels(unit: TempUnit) {
        findViewById<TextView>(R.id.temp_unit_label).text = "Temperature (${unit.suffix})"
        findViewById<TextView>(R.id.dew_unit_label).text = "Dew point (${unit.suffix})"
    }

    /** The unit checkbox flipped: re-render the four setpoint fields (from their current
     *  °F basis, not a reparse of the OLD unit's live text) and swap the field labels. */
    private fun onUnitToggled(on: Boolean) {
        val newUnit = if (on) TempUnit.C else TempUnit.F
        val oldUnit = currentUnit
        val basis = tempFieldIds.associateWith { fahrenheitOf(it, oldUnit) }
        currentUnit = newUnit
        tempFieldIds.forEach { renderTempField(it, basis.getValue(it)) }
        updateTempLabels(newUnit)
    }

    private fun updateTextScaleLabel(pct: Int) {
        findViewById<TextView>(R.id.text_scale_pct).text = "$pct%"
    }

    private fun link(id: Int, url: String) = findViewById<TextView>(id).setOnClickListener {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun save() {
        val cfg = AppConfig.load(this)
        // The four setpoints go through the drift-guard reparse (see [fahrenheitOf]); the
        // rest are just parsed directly, falling back to the current value if invalid.
        val tempIdeal = fahrenheitOf(R.id.temp_ideal, currentUnit)
        val maxTemp = fahrenheitOf(R.id.max_temp, currentUnit)
        val openScore = field(R.id.open_score).text.toString().toDoubleOrNull()
            ?.coerceIn(0.0, 100.0) ?: cfg.comfort.openScoreMin
        val dewIdeal = fahrenheitOf(R.id.dew_ideal, currentUnit)
        val dewMax = fahrenheitOf(R.id.dew_max, currentUnit)
        // Re-render each field from its just-parsed °F value: normalizes anything the user
        // typed (e.g. "78.00") to the canonical form, and refreshes the drift-guard's
        // remembered value/text so a further toggle (without leaving the screen) has a
        // fresh basis instead of the one from screen-open.
        renderTempField(R.id.temp_ideal, tempIdeal)
        renderTempField(R.id.max_temp, maxTemp)
        renderTempField(R.id.dew_ideal, dewIdeal)
        renderTempField(R.id.dew_max, dewMax)
        val aqiIdeal = field(R.id.aqi_ideal).text.toString().toDoubleOrNull() ?: cfg.comfort.aqiIdeal
        val aqiMax = field(R.id.aqi_max).text.toString().toIntOrNull() ?: cfg.comfort.aqiMax
        val rainIdeal = field(R.id.rain_ideal).text.toString().toDoubleOrNull() ?: cfg.comfort.rainProbIdeal
        val rainMax = field(R.id.rain_max).text.toString().toDoubleOrNull() ?: cfg.comfort.rainProbMax
        val textScalePct = textScaleSlider().progress.coerceIn(60, 200)

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
            .putString("appTextScalePct", textScalePct.toString())
            .putString("dayViewMode", (if (nowViewCheck().isChecked) DayViewMode.NOW else DayViewMode.TODAY).name)
            .putBoolean("appShowHourlyRows", hourlyRowsCheck().isChecked)
        weightFields.forEach { (id, src) ->
            val w = field(id).text.toString().toDoubleOrNull()?.coerceIn(0.0, 10.0) ?: 1.0
            ed.putString("weight_$src", trimNum(w))
        }
        ed.apply()
        // Its own key in the same prefs file, written through Units.kt so both readers
        // (this screen and the widget/app render paths) agree on where it lives.
        TempUnit.save(this, currentUnit)

        // API keys live in their own private file (not SharedPreferences); blank clears a slot.
        SecretsStore.save(
            this,
            mapOf(
                SecretsStore.Key.TOMORROW_IO to field(R.id.key_tomorrowio).text.toString(),
                SecretsStore.Key.PIRATE_WEATHER to field(R.id.key_pirateweather).text.toString(),
                SecretsStore.Key.AIRNOW to field(R.id.key_airnow).text.toString(),
            ),
        )

        // New comfort/keys → cached scores are stale; refetch + redraw.
        WidgetCache.clearAll(this)
        ForecastWidgetProvider.refreshNow(this)

        staged.commit()
    }

    private fun field(id: Int): EditText = findViewById(id)
    private fun trimNum(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
}
