package com.acwindows

import android.content.Context

/** One panel of the graphs widget. See [GraphsWidgetConfig.panels]. */
enum class Panel { TEMP_DEW, AQI_RAIN, SCORE }

/** How a panel draws its data. See [PanelGraph]. */
enum class PanelMode { TIMESERIES, HOURLY_ROW }

/**
 * Per-[android.appwidget.AppWidgetManager] widget-instance settings for the graphs
 * widget — unlike [WidgetSettingsActivity]'s global config, several of these can be
 * placed at once with different panels showing, so every key is suffixed with the
 * widget id it belongs to (see [load]/[save]).
 */
data class GraphsWidgetConfig(
    val panels: List<Panel> = listOf(Panel.TEMP_DEW, Panel.AQI_RAIN),
    val modes: Map<Panel, PanelMode> = Panel.entries.associateWith { PanelMode.TIMESERIES },
    /** Look-ahead hours shown per panel; 6..48. */
    val hours: Int = 24,
) {
    companion object {
        private const val PREFS = "ac_windows"
        private const val MIN_HOURS = 6
        private const val MAX_HOURS = 48

        fun load(context: Context, id: Int): GraphsWidgetConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val panels = p.getString("graphsPanels_$id", null)
                ?.split(",")
                ?.mapNotNull { name -> runCatching { Panel.valueOf(name) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(Panel.TEMP_DEW, Panel.AQI_RAIN)
            val modes = Panel.entries.associateWith { panel ->
                p.getString("graphsMode_${panel}_$id", null)
                    ?.let { raw -> runCatching { PanelMode.valueOf(raw) }.getOrNull() }
                    ?: PanelMode.TIMESERIES
            }
            val hours = p.getString("graphsHours_$id", null)?.toIntOrNull()
                ?.coerceIn(MIN_HOURS, MAX_HOURS) ?: 24
            return GraphsWidgetConfig(panels, modes, hours)
        }

        fun save(context: Context, id: Int, cfg: GraphsWidgetConfig) {
            val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("graphsPanels_$id", cfg.panels.joinToString(",") { it.name })
                .putString("graphsHours_$id", cfg.hours.coerceIn(MIN_HOURS, MAX_HOURS).toString())
            Panel.entries.forEach { panel ->
                e.putString("graphsMode_${panel}_$id", (cfg.modes[panel] ?: PanelMode.TIMESERIES).name)
            }
            e.apply()
        }

        /** Drop this widget id's staged keys — called from [GraphsWidgetProvider.onDeleted] so
         *  removing a widget doesn't leak its settings in SharedPreferences forever. */
        fun delete(context: Context, id: Int) {
            val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("graphsPanels_$id")
                .remove("graphsHours_$id")
            Panel.entries.forEach { panel -> e.remove("graphsMode_${panel}_$id") }
            e.apply()
        }
    }
}
