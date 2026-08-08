package com.acwindows

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The second home-screen widget: the app's charts, each panel switchable between the
 * timeseries rendering and an hourly-row rendering (see [PanelGraph]). Configuration is
 * PER WIDGET INSTANCE ([GraphsWidgetConfig]), unlike [AcWindowsProvider]'s global
 * settings — several of these can sit on one home screen with different panels showing.
 *
 * It shares [AcWindowsProvider]'s 10-minute alarm tick rather than owning a clock of its
 * own (see [AcWindowsProvider.scheduleTick]) and shares the same cached [Snapshot] and
 * [RefreshWorker] fetch — this file only ever READS the cache and asks for a refresh,
 * never fetches on its own.
 */
class GraphsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val snap = WidgetCache.load(context)
        val stale = snap == null || snap.ageMs() > WidgetCache.TTL_MS
        ids.forEach { id -> drawId(context, mgr, id, refreshing = snap == null) }
        if (stale) AcWindowsProvider.enqueueRefresh(context)
        AcWindowsProvider.scheduleTick(context)
        // The alarm is the primary driver, but it's the one that can be dropped (reboot,
        // Doze, a killed process) — which is exactly why the 15-minute WorkManager backstop
        // exists. Arm it from here too, or a home screen carrying ONLY this widget has a
        // single point of failure that the older widget doesn't.
        AcWindowsProvider.schedulePeriodic(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle,
    ) {
        // Re-fit every visible panel to the new size — same reasoning as AcWindowsProvider's
        // override: this also lands mid-first-fetch when the widget is freshly placed.
        drawId(context, mgr, id, refreshing = WidgetCache.load(context) == null)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        // Otherwise a removed instance's panel/hours settings sit in SharedPreferences
        // forever under a widget id nothing will ever look up again.
        ids.forEach { GraphsWidgetConfig.delete(context, it) }
        AcWindowsProvider.scheduleTick(context)   // may have just become the last widget gone
    }

    override fun onEnabled(context: Context) {
        AcWindowsProvider.scheduleTick(context)
        AcWindowsProvider.schedulePeriodic(context)
    }

    /**
     * Mirror of [AcWindowsProvider.onDisabled]: the alarm and the periodic backstop are
     * shared, so only tear them down once no widget of EITHER type is left. [scheduleTick]
     * already applies that guard for the alarm; the periodic work needs it stated here.
     */
    override fun onDisabled(context: Context) {
        AcWindowsProvider.scheduleTick(context)
        if (AcWindowsProvider.widgetIds(context).isEmpty()) {
            WorkManager.getInstance(context).cancelUniqueWork(AcWindowsProvider.PERIODIC_WORK)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                renderAll(context, refreshing = true)
                AcWindowsProvider.enqueueRefresh(context)
            }
            // Alarms don't survive a reboot; AcWindowsProvider's own boot receiver already
            // re-arms the shared tick, but this registration is defensive in case this
            // provider is ever the only one the OS chooses to wake.
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AcWindowsProvider.scheduleTick(context)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.acwindows.ACTION_GRAPHS_REFRESH"

        /** Tapping the widget body opens the full app (this widget has no separate refresh
         *  button of its own — the shared 10-minute tick is the only refresh trigger). */
        private fun appIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context, 3, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Redraw every instance from cache with the spinner off (keeps last good data). */
        fun refreshDone(context: Context) = renderAll(context, refreshing = false)

        /** Every [GraphsWidgetProvider] instance currently placed. Also read by
         *  [AcWindowsProvider.scheduleTick] — the shared alarm has to stay armed while
         *  either widget type is present. */
        fun widgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, GraphsWidgetProvider::class.java))

        /** Redraw every instance from the cache — the single source of truth for what's shown. */
        fun renderAll(context: Context, refreshing: Boolean) {
            val mgr = AppWidgetManager.getInstance(context)
            widgetIds(context).forEach { drawId(context, mgr, it, refreshing) }
        }

        private fun drawId(context: Context, mgr: AppWidgetManager, id: Int, refreshing: Boolean) {
            val snap = WidgetCache.load(context)
            val views = if (snap == null) loadingView(context, refreshing) else buildViews(context, snap, refreshing, id, sizeOf(mgr, id))
            mgr.updateAppWidget(id, views)
        }

        /** Current widget size in dp (portrait orientation values), with fallbacks. */
        private fun sizeOf(mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 250
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 220
            return w to h
        }

        private fun colorFor(action: Action): Int = when (action) {
            Action.OPEN_WINDOWS -> Color.parseColor("#3E6B4C")
            Action.RUN_AC -> Color.parseColor("#3F5E84")
            Action.KEEP_CLOSED -> Color.parseColor("#58646F")
            Action.COMFORTABLE -> Color.parseColor("#3C6E74")
            Action.UNKNOWN -> Color.parseColor("#8C5350")
        }

        private fun withOpacity(color: Int, opacityPct: Int): Int {
            val a = (opacityPct.coerceIn(0, 100) / 100.0 * 255).roundToInt()
            return (color and 0x00FFFFFF) or (a shl 24)
        }

        private fun footerDim(theme: WidgetTheme): Int = (0xB3 shl 24) or (theme.text and 0x00FFFFFF)

        private val PANEL_SLOTS = listOf(R.id.panel_0, R.id.panel_1, R.id.panel_2)

        private fun loadingView(context: Context, refreshing: Boolean): RemoteViews {
            val theme = WidgetTheme.load(context)
            val cfg = AppConfig.load(context)
            return RemoteViews(context.packageName, R.layout.widget_graphs).apply {
                PANEL_SLOTS.forEach { slot ->
                    setViewVisibility(slot, View.GONE)
                    setImageViewBitmap(slot, null)   // see buildViews for why this is mandatory
                }
                setTextViewText(R.id.graphs_footer, if (refreshing) "Loading…" else "No data yet")
                setTextColor(R.id.graphs_footer, footerDim(theme))
                setInt(
                    R.id.graphs_root, "setBackgroundColor",
                    withOpacity(
                        if (theme.useActionBackground) colorFor(Action.KEEP_CLOSED) else theme.background,
                        cfg.widgetOpacity,
                    ),
                )
                setOnClickPendingIntent(R.id.graphs_root, appIntent(context))
            }
        }

        private fun buildViews(
            context: Context, s: Snapshot, refreshing: Boolean, id: Int, size: Pair<Int, Int>,
        ): RemoteViews {
            val (wDp, hDp) = size
            val density = context.resources.displayMetrics.density
            val cfg = AppConfig.load(context)
            val theme = WidgetTheme.load(context)
            val unit = TempUnit.load(context)
            val gcfg = GraphsWidgetConfig.load(context, id)
            val panels = gcfg.panels.take(PANEL_SLOTS.size)   // the layout only has 3 slots
            val textScale = theme.textScalePct / 100f
            val nowMs = System.currentTimeMillis()

            // Estimate each panel ImageView's true pixel size — same reasoning as
            // AcWindowsProvider's graph sizing: rendering the bitmap at the view's real
            // aspect (with fitCenter) is what keeps text from stretching on resize.
            val padDp = 12 * 2                              // root's 12dp padding, top+bottom / left+right
            val footerDp = Math.round(14 * textScale) + 4    // footer text + its top margin
            val availableHDp = (hDp - padDp - footerDp).coerceAtLeast(40)
            val perPanelHDp = if (panels.isEmpty()) availableHDp else (availableHDp / panels.size).coerceAtLeast(30)
            val ivWdp = (wDp - padDp).coerceAtLeast(60)
            val ivWpx = (ivWdp * density).toInt().coerceIn(120, 1600)
            val ivHpx = (perPanelHDp * density).toInt().coerceIn(40, 900)

            return RemoteViews(context.packageName, R.layout.widget_graphs).apply {
                setInt(
                    R.id.graphs_root, "setBackgroundColor",
                    withOpacity(
                        if (theme.useActionBackground) colorFor(s.action) else theme.background,
                        cfg.widgetOpacity,
                    ),
                )
                PANEL_SLOTS.forEachIndexed { i, slot ->
                    val panel = panels.getOrNull(i)
                    // ALWAYS set both visibility and bitmap, even to GONE/null — the launcher
                    // reapplies this RemoteViews onto the views already on screen rather than
                    // re-inflating, so a property left unset keeps its previous value (see
                    // AcWindowsProvider.buildViews for the same trap with @id/graph).
                    if (panel == null) {
                        setViewVisibility(slot, View.GONE)
                        setImageViewBitmap(slot, null)
                    } else {
                        setViewVisibility(slot, View.VISIBLE)
                        val mode = gcfg.modes[panel] ?: PanelMode.TIMESERIES
                        val bmp = if (s.scores.isEmpty()) null else PanelGraph.render(
                            context, panel, mode, s, ivWpx, ivHpx, density, theme, unit,
                            gcfg.hours, cfg.comfort.openScoreMin, nowMs,
                        )
                        setImageViewBitmap(slot, bmp)
                    }
                }
                setTextViewText(R.id.graphs_footer, footerLabel(s, refreshing, nowMs))
                setTextColor(R.id.graphs_footer, footerDim(theme))
                setTextViewTextSize(R.id.graphs_footer, TypedValue.COMPLEX_UNIT_SP, 10f * textScale)
                setOnClickPendingIntent(R.id.graphs_root, appIntent(context))
            }
        }

        private fun footerLabel(s: Snapshot, refreshing: Boolean, nowMs: Long): String {
            if (refreshing) return "updating…"
            val at = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(s.updatedAtMs))
            val age = s.ageMs(nowMs)
            if (age <= WidgetCache.STALE_MS) return "as of $at"
            val hours = age / 3_600_000
            val ago = if (hours >= 1) "${hours}h" else "${age / 60_000}m"
            return "⚠ as of $at ($ago old)"
        }
    }
}
