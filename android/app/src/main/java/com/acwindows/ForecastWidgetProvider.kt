package com.acwindows

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * The forecast widget (docs/ux-redesign.md §C): one panel answering "what will the weather do
 * in the next N hours". Configuration is PER WIDGET INSTANCE ([ForecastWidgetConfig]) — several
 * can sit on one home screen at different horizons.
 *
 * This is the app's only home-screen widget, so it also owns the shared refresh machinery:
 * the 10-minute AlarmManager tick (with a WorkManager periodic job as a backstop — see
 * [scheduleTick] for why it takes both), the coalesced [RefreshWorker] fetch, and the cached
 * [Snapshot]s — one per location a widget is bound to. It only READS the cache and asks for a
 * refresh; the fetch itself lives in [RefreshWorker].
 */
class ForecastWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        var due = false
        ids.forEach { id ->
            val snap = WidgetCache.load(context, locationOf(context, id).id)
            drawId(context, mgr, id, refreshing = snap == null)
            if (snap == null || snap.ageMs() > WidgetCache.TTL_MS) due = true
        }
        if (due) enqueueRefresh(context)
        scheduleTick(context)
        schedulePeriodic(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle,
    ) {
        drawId(context, mgr, id, refreshing = WidgetCache.load(context, locationOf(context, id).id) == null)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { ForecastWidgetConfig.delete(context, it) }
        scheduleTick(context)
    }

    override fun onEnabled(context: Context) {
        scheduleTick(context)
        schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        alarms(context)?.cancel(tickIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                renderAll(context, refreshing = true)   // immediate "pulling data" feedback
                enqueueRefresh(context)
            }
            // The 10-minute auto-refresh tick. Re-arm first (so the chain survives even if the
            // fetch never runs), then refresh quietly — the old data stays readable meanwhile.
            ACTION_TICK -> {
                scheduleTick(context)
                enqueueRefresh(context)
            }
            // Alarms don't survive a reboot (WorkManager's own jobs do), so re-arm.
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                scheduleTick(context)
                schedulePeriodic(context)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.acwindows.ACTION_FORECAST_REFRESH"
        private const val ACTION_TICK = "com.acwindows.ACTION_TICK"
        const val PERIODIC_WORK = "ac_windows_periodic"
        private const val REFRESH_WORK = "ac_windows_refresh"

        /**
         * How often the widget refreshes itself, unprompted — and, by the same token, how long
         * a cached snapshot is considered current (see [WidgetCache.TTL_MS]).
         */
        const val TICK_INTERVAL_MS = WidgetCache.TTL_MS

        fun widgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, ForecastWidgetProvider::class.java))

        /** Redraw every instance from cache with the spinner off (keeps last good data). */
        fun refreshDone(context: Context) = renderAll(context, refreshing = false)

        /** Draw immediately (spinner over current/loading view) and kick off a fetch. */
        fun refreshNow(context: Context) {
            renderAll(context, refreshing = true)
            enqueueRefresh(context)
        }

        /** Redraw every instance, or only those showing [locationId] when it is given. */
        fun renderAll(context: Context, refreshing: Boolean, locationId: String? = null) {
            val mgr = AppWidgetManager.getInstance(context)
            widgetIds(context)
                .filter { locationId == null || locationOf(context, it).id == locationId }
                .forEach { drawId(context, mgr, it, refreshing) }
        }

        fun locationOf(context: Context, widgetId: Int): SavedLocation {
            // Load the list first: on an upgraded install that read is what binds existing
            // widgets to their place, and it has to land before the widget's config is read.
            val list = Locations.load(context)
            val id = ForecastWidgetConfig.load(context, widgetId).locationId
            return list.firstOrNull { it.id == id } ?: list.first { it.isCurrent }
        }

        /** The locations background refresh fetches: those shown by a placed widget, and no others. */
        fun boundLocations(context: Context): List<SavedLocation> =
            widgetIds(context).map { locationOf(context, it) }.distinctBy { it.id }

        /**
         * Queue a refresh, coalescing with one already queued or running (KEEP). Every trigger
         * funnels through here — a tap, the 10-minute tick, a settings save, and onUpdate, which
         * the system can fire several times in a row — so without a unique name a single moment
         * could kick off overlapping fetches that fight over the snapshot.
         */
        fun enqueueRefresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                REFRESH_WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>().build(),
            )
        }

        /**
         * Fetch one location that no placed widget shows yet — the settings preview of a widget
         * still being added. Its own unique name, so a routine refresh already queued can't
         * swallow it.
         */
        fun enqueueRefresh(context: Context, locationId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$REFRESH_WORK:$locationId", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setInputData(workDataOf(RefreshWorker.KEY_LOCATION to locationId))
                    .build(),
            )
        }

        private fun alarms(context: Context) =
            context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        /** The self-rescheduling 10-minute tick broadcast (see [scheduleTick]). */
        private fun tickIntent(context: Context): PendingIntent {
            val intent = Intent(context, ForecastWidgetProvider::class.java).setAction(ACTION_TICK)
            return PendingIntent.getBroadcast(
                context, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Arm the next auto-refresh, [TICK_INTERVAL_MS] from now. Called on every widget update,
         * on boot, and by [RefreshWorker] after each run, so the clock restarts from the last
         * actual refresh and a dropped tick heals on the next one.
         *
         * WorkManager can't do this alone: its periodic floor is 15 min. setAndAllowWhileIdle is
         * inexact and needs no permission, but still fires in Doze (throttled to ~9 min at
         * worst), so 10 min is honoured whenever the phone is awake. With no widget placed there
         * is nothing to refresh, so the alarm is cancelled rather than left self-perpetuating.
         */
        fun scheduleTick(context: Context) {
            val am = alarms(context) ?: return
            val pi = tickIntent(context)
            if (widgetIds(context).isEmpty()) {
                am.cancel(pi)
                return
            }
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + TICK_INTERVAL_MS, pi)
            }
        }

        fun schedulePeriodic(context: Context) {
            // Backstop only — the 10-min alarm tick is the primary driver. 15 min is
            // WorkManager's floor. UPDATE (not KEEP) so a reinstall/version bump re-applies the
            // interval instead of keeping a stale longer one.
            val work = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, work,
            )
        }

        private fun drawId(context: Context, mgr: AppWidgetManager, id: Int, refreshing: Boolean) {
            mgr.updateAppWidget(id, buildViews(context, id, refreshing, sizeOf(mgr, id)))
        }

        /** Current widget size in dp (portrait values), defaulting to §C.1's 4x2 frame. */
        private fun sizeOf(mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 392
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 186
            return w to h
        }

        // internal, not private, so WidgetInflationTest can build the exact RemoteViews the
        // launcher receives and run apply() on it.
        @VisibleForTesting
        internal fun buildViews(
            context: Context, id: Int, refreshing: Boolean, size: Pair<Int, Int>,
        ): RemoteViews {
            val (wDp, hDp) = size
            val widget = ForecastWidgetConfig.load(context, id)
            val dark = widget.isDark(context)
            val chrome = Tokens.chrome(dark)
            val loc = Locations.byId(context, widget.locationId)
            val cfg = AppConfig.load(context, loc)
            val snap = WidgetCache.load(context, loc.id)
            val density = context.resources.displayMetrics.density

            return RemoteViews(context.packageName, R.layout.widget_forecast).apply {
                setImageViewBitmap(R.id.forecast_card, cardBitmap(chrome.card, widget.opacityPct, wDp, hDp, density))
                // ALWAYS set the panel, even with nothing to draw: the launcher reapplies a
                // RemoteViews onto the views already on screen rather than re-inflating, so
                // a panel we don't set keeps the previous one under freshly updated text.
                val panel = snap?.takeIf { it.scores.isNotEmpty() }?.let {
                    ForecastPanel.render(
                        context, it, cfg, widget, TempUnit.load(context),
                        widthPx = ((wDp - 20) * density).toInt().coerceIn(160, 1400),
                        heightPx = ((hDp - 20) * density).toInt().coerceIn(90, 900),
                        density = density, dark = dark,
                        textScale = widget.textScalePct / 100f,
                    )
                }
                setImageViewBitmap(R.id.forecast_panel, panel)
                setViewVisibility(R.id.forecast_empty, if (panel == null) View.VISIBLE else View.GONE)
                setTextViewText(
                    R.id.forecast_empty,
                    if (refreshing || snap == null) "Loading…" else "No forecast yet",
                )
                setTextColor(R.id.forecast_empty, chrome.muted)
                setOnClickPendingIntent(
                    R.id.forecast_root,
                    if (widget.tapOpensApp) appIntent(context) else refreshIntent(context),
                )
                // The refresh corner is always a refresh, whatever the body tap is bound to.
                setOnClickPendingIntent(R.id.forecast_refresh, refreshIntent(context))
            }
        }

        /**
         * The rounded card ground. Drawn rather than styled because RemoteViews cannot tint a
         * shape drawable before API 31, and the ground carries the user's opacity.
         */
        private fun cardBitmap(color: Int, opacityPct: Int, wDp: Int, hDp: Int, density: Float): Bitmap {
            val w = (wDp * density).toInt().coerceIn(80, 1400)
            val h = (hDp * density).toInt().coerceIn(60, 900)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val a = (opacityPct.coerceIn(0, 100) / 100.0 * 255).toInt()
            val r = 24f * density
            Canvas(bmp).drawRoundRect(
                0f, 0f, w.toFloat(), h.toFloat(), r, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = (color and 0x00FFFFFF) or (a shl 24) },
            )
            return bmp
        }

        private fun appIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context, 5, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun refreshIntent(context: Context): PendingIntent {
            val intent = Intent(context, ForecastWidgetProvider::class.java).setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(
                context, 6, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
