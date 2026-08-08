package com.acwindows

import android.content.Context
import kotlin.math.roundToInt

/**
 * The app's temperature DISPLAY unit.
 *
 * Everything inside the app stays in °F: the [Comfort] setpoints, [ForecastPoint.tempF]
 * and `dewPointF`, [Score], [Decision], and the widget's cached [Snapshot]. That is the
 * unit every provider is already asked for and the unit the Python brain speaks, so one
 * canonical internal unit means a stored setpoint can't drift a fraction of a degree
 * every time this toggle is flipped.
 *
 * This type therefore lives purely at the display boundary — convert on the way out to a
 * label, convert back on the way in from a settings field. Nothing downstream of a
 * conversion should be fed back into the model.
 *
 * It reads its own preference key out of the same `ac_windows` SharedPreferences the rest
 * of the config uses, so the widget's render path can ask for the unit without loading a
 * whole [AppConfig].
 */
enum class TempUnit(val key: String, val label: String, val suffix: String) {
    F("F", "Fahrenheit (°F)", "°F"),
    C("C", "Celsius (°C)", "°C");

    /** An absolute °F reading expressed in this unit. */
    fun fromF(f: Double): Double = if (this == F) f else (f - 32.0) * 5.0 / 9.0

    /** A value typed by the user in this unit, back to the canonical °F. */
    fun toF(v: Double): Double = if (this == F) v else v * 9.0 / 5.0 + 32.0

    /**
     * A DIFFERENCE between two °F values, in this unit — scaled but NOT offset. Use this
     * for spans, axis ranges and tolerances; using [fromF] on a delta adds a spurious 32°.
     */
    fun deltaFromF(dF: Double): Double = if (this == F) dF else dF * 5.0 / 9.0

    /** "78°" — the compact in-chart form, with no unit letter. */
    fun deg(f: Double): String = "${fromF(f).roundToInt()}°"

    /** "78°F" — for the places that have to name the unit. */
    fun degUnit(f: Double): String = "${fromF(f).roundToInt()}$suffix"

    companion object {
        const val PREF_KEY = "tempUnit"
        private const val PREFS = "ac_windows"

        fun of(key: String?): TempUnit = entries.firstOrNull { it.key == key } ?: F

        fun load(context: Context): TempUnit =
            of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_KEY, null))

        fun save(context: Context, unit: TempUnit) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, unit.key).apply()
        }
    }
}
