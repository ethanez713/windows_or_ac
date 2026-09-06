package com.acwindows

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView

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
        check(R.id.show_bars).isChecked = cfg.widgetShowBars
        check(R.id.show_temp).isChecked = cfg.widgetShowTemp
        check(R.id.show_dew).isChecked = cfg.widgetShowDew
        check(R.id.show_rain).isChecked = cfg.widgetShowRain
        check(R.id.show_threshold).isChecked = cfg.widgetShowThreshold

        val theme = WidgetTheme.load(this)
        check(R.id.use_action_background).isChecked = theme.useActionBackground
        COLOR_IDS.forEach { (fieldId, get) -> field(fieldId).setText(WidgetTheme.hex(get(theme))) }
        SWATCH_IDS.forEach { (swatchId, colorFieldId) ->
            findViewById<View>(swatchId).setBackgroundColor(WidgetTheme.parseHex(field(colorFieldId).text.toString(), 0))
            bindSwatch(field(colorFieldId), findViewById(swatchId))
        }
        // Greyed out from the start when the background follows the recommendation colour —
        // matches what the checkbox already says.
        field(R.id.color_background).isEnabled = !theme.useActionBackground
        field(R.id.color_background).alpha = if (theme.useActionBackground) 0.4f else 1f

        val textScale = findViewById<SeekBar>(R.id.text_scale)
        val textScaleValue = findViewById<TextView>(R.id.text_scale_value)
        textScale.progress = theme.textScalePct.coerceIn(50, 200)
        textScaleValue.text = "${textScale.progress}%"

        val discard = findViewById<Button>(R.id.discard)
        staged = StagedEdits(
            listOf(field(R.id.widget_hours), field(R.id.widget_opacity)) + COLOR_IDS.map { field(it.first) },
            checks = LAYERS.map { check(it) } + check(R.id.use_action_background),
            sliders = listOf(textScale),
            onToggle = { btn, on ->
                // Only the background follows/doesn't-follow toggle drives anything else on
                // screen; the layer checkboxes are otherwise inert. Also runs on discard(),
                // which is what puts the hex field's enabled state back too.
                if (btn.id == R.id.use_action_background) {
                    field(R.id.color_background).isEnabled = !on
                    field(R.id.color_background).alpha = if (on) 0.4f else 1f
                }
            },
            onSlide = { _, progress -> textScaleValue.text = "$progress%" },
        ) { dirty ->
            discard.visibility = if (dirty) View.VISIBLE else View.GONE
        }
        discard.setOnClickListener { staged.discard() }

        // Goes through the fields (not straight to prefs) so StagedEdits — and the
        // Discard button — see this as just another staged edit.
        findViewById<Button>(R.id.reset_colors).setOnClickListener {
            COLOR_IDS.forEach { (fieldId, get) -> field(fieldId).setText(WidgetTheme.hex(get(WidgetTheme.DEFAULT))) }
            check(R.id.use_action_background).isChecked = WidgetTheme.DEFAULT.useActionBackground
        }
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
            .putBoolean("widgetShowBars", check(R.id.show_bars).isChecked)
            .putBoolean("widgetShowTemp", check(R.id.show_temp).isChecked)
            .putBoolean("widgetShowDew", check(R.id.show_dew).isChecked)
            .putBoolean("widgetShowRain", check(R.id.show_rain).isChecked)
            .putBoolean("widgetShowThreshold", check(R.id.show_threshold).isChecked)
            .apply()

        // A half-typed hex field falls back to whatever's already saved (not the global
        // default) — same idea as the numeric fields above, just via WidgetTheme.parseHex.
        val current = WidgetTheme.load(this)
        WidgetTheme.save(
            this,
            WidgetTheme(
                useActionBackground = check(R.id.use_action_background).isChecked,
                background = WidgetTheme.parseHex(field(R.id.color_background).text.toString(), current.background),
                barOpen = WidgetTheme.parseHex(field(R.id.color_bar_open).text.toString(), current.barOpen),
                barClosed = WidgetTheme.parseHex(field(R.id.color_bar_closed).text.toString(), current.barClosed),
                temp = WidgetTheme.parseHex(field(R.id.color_temp).text.toString(), current.temp),
                dew = WidgetTheme.parseHex(field(R.id.color_dew).text.toString(), current.dew),
                rain = WidgetTheme.parseHex(field(R.id.color_rain).text.toString(), current.rain),
                text = WidgetTheme.parseHex(field(R.id.color_text).text.toString(), current.text),
                textScalePct = findViewById<SeekBar>(R.id.text_scale).progress.coerceIn(50, 200),
            ),
        )

        // A changed forecast length makes the cached scores the wrong span, so they have
        // to be thrown away and refetched. The layer toggles only change what's drawn from
        // the same data — but a snapshot cached before the temp/dew series existed hasn't
        // got them, so still ask for a refresh; it redraws immediately either way and
        // coalesces with any fetch already running. Colours and text size only change how
        // the cached snapshot is DRAWN, so they don't need a cache clear either.
        if (hours != cfg.widgetForecastHours) WidgetCache.clear(this)
        AcWindowsProvider.refreshNow(this)

        staged.commit()
    }

    private fun field(id: Int): EditText = findViewById(id)
    private fun check(id: Int): CheckBox = findViewById(id)
    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    /** Live-updates [swatch] from [hexField] as the user types — only when it parses. */
    private fun bindSwatch(hexField: EditText, swatch: View) {
        hexField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (isValidHex(text)) swatch.setBackgroundColor(WidgetTheme.parseHex(text, 0))
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    private fun isValidHex(s: String): Boolean {
        val h = s.removePrefix("#")
        return h.length == 6 && h.toIntOrNull(16) != null
    }

    private companion object {
        /** The graph's optional layers, in the order they're listed on screen. */
        val LAYERS = listOf(
            R.id.show_bars, R.id.show_temp, R.id.show_dew, R.id.show_rain, R.id.show_threshold,
        )

        /** Each hex field, paired with the [WidgetTheme] property it edits. */
        val COLOR_IDS: List<Pair<Int, (WidgetTheme) -> Int>> = listOf(
            R.id.color_background to WidgetTheme::background,
            R.id.color_bar_open to WidgetTheme::barOpen,
            R.id.color_bar_closed to WidgetTheme::barClosed,
            R.id.color_temp to WidgetTheme::temp,
            R.id.color_dew to WidgetTheme::dew,
            R.id.color_rain to WidgetTheme::rain,
            R.id.color_text to WidgetTheme::text,
        )

        /** Each swatch View, paired with the hex EditText it previews. */
        val SWATCH_IDS = listOf(
            R.id.swatch_background to R.id.color_background,
            R.id.swatch_bar_open to R.id.color_bar_open,
            R.id.swatch_bar_closed to R.id.color_bar_closed,
            R.id.swatch_temp to R.id.color_temp,
            R.id.swatch_dew to R.id.color_dew,
            R.id.swatch_rain to R.id.color_rain,
            R.id.swatch_text to R.id.color_text,
        )
    }
}
