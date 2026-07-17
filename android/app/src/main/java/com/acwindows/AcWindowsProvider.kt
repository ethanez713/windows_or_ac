package com.acwindows

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The home-screen widget. Renders instantly from the cached [Snapshot] on every
 * update, and only kicks off a network refresh when the cache is missing or
 * older than [WidgetCache.TTL_MS]. Tapping forces an immediate refresh.
 *
 * Rendering is per-widget and size-aware: it shows as many forecast hours as fit
 * the widget's width and tiers the text rows by height, so the same widget works
 * from 2x2 up to a full-width banner.
 */
class AcWindowsProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val stale = WidgetCache.isStale(context)
        ids.forEach { id -> drawId(context, mgr, id, refreshing = stale && WidgetCache.load(context) == null) }
        if (stale) enqueueRefresh(context)   // only fetch when actually due
        schedulePeriodic(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle,
    ) {
        drawId(context, mgr, id, refreshing = false)   // re-fit bars to the new size
    }

    override fun onEnabled(context: Context) = schedulePeriodic(context)

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            renderAll(context, refreshing = true)   // immediate "pulling data" feedback
            enqueueRefresh(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.acwindows.ACTION_REFRESH"
        private const val PERIODIC_WORK = "ac_windows_periodic"

        /** Tapping the widget body opens the full app. */
        private fun appIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Tapping the refresh icon broadcasts ACTION_REFRESH (manual refetch, no app open). */
        private fun refreshIntent(context: Context): PendingIntent {
            val intent = Intent(context, AcWindowsProvider::class.java).setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(
                context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Redraw every instance from cache with the spinner off (keeps last good data). */
        fun refreshDone(context: Context) = renderAll(context, refreshing = false)

        fun enqueueRefresh(context: Context) {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<RefreshWorker>().build())
        }

        private fun schedulePeriodic(context: Context) {
            // 15 min is WorkManager's floor and matches the cache TTL. UPDATE (not KEEP)
            // so a reinstall/version bump re-applies the interval instead of keeping a
            // stale longer one — the bug where the widget appeared to stop refreshing.
            val work = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, work,
            )
        }

        /** Called by the worker (and on tap) — redraw every instance at its own size. */
        fun render(context: Context, snap: Snapshot) = renderAll(context, refreshing = false)

        /** Draw immediately (spinner over current/loading view) and kick off a fetch. */
        fun refreshNow(context: Context) {
            renderAll(context, refreshing = true)
            enqueueRefresh(context)
        }

        private fun renderAll(context: Context, refreshing: Boolean) {
            val mgr = AppWidgetManager.getInstance(context)
            mgr.getAppWidgetIds(ComponentName(context, AcWindowsProvider::class.java))
                .forEach { drawId(context, mgr, it, refreshing) }
        }

        private fun drawId(context: Context, mgr: AppWidgetManager, id: Int, refreshing: Boolean) {
            val snap = WidgetCache.load(context)
            val views = if (snap == null) loadingView(context)
                else buildViews(context, snap, refreshing, sizeOf(mgr, id))
            mgr.updateAppWidget(id, views)
        }

        /** Current widget size in dp (portrait orientation values), with fallbacks. */
        private fun sizeOf(mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 250
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 180
            return w to h
        }

        private fun colorFor(action: Action): Int = when (action) {
            Action.OPEN_WINDOWS -> Color.parseColor("#3E6B4C") // muted green
            Action.RUN_AC -> Color.parseColor("#3F5E84")       // muted blue
            Action.KEEP_CLOSED -> Color.parseColor("#58646F")  // muted slate
            Action.COMFORTABLE -> Color.parseColor("#3C6E74")  // muted teal
            Action.UNKNOWN -> Color.parseColor("#8C5350")      // muted red
        }

        /** Apply a 0–100% opacity to an opaque colour (transparency shows wallpaper). */
        private fun withOpacity(color: Int, opacityPct: Int): Int {
            val a = (opacityPct.coerceIn(0, 100) / 100.0 * 255).roundToInt()
            return (color and 0x00FFFFFF) or (a shl 24)
        }

        private fun loadingView(context: Context): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_ac_windows).apply {
                setTextViewText(R.id.headline, "Loading…")
                setTextViewText(R.id.readings, "")
                setTextViewCompoundDrawablesRelative(R.id.sources, 0, 0, 0, 0)
                setTextViewText(R.id.sources, "fetching weather…")
                setTextViewText(R.id.updated, "")
                setViewVisibility(R.id.refresh_spinner, View.VISIBLE)
                setViewVisibility(R.id.refresh_button, View.GONE)
                setOnClickPendingIntent(R.id.widget_root, appIntent(context))
                setOnClickPendingIntent(R.id.refresh_button, refreshIntent(context))
            }

        private fun buildViews(
            context: Context, s: Snapshot, refreshing: Boolean, size: Pair<Int, Int>,
        ): RemoteViews {
            val (wDp, hDp) = size
            val density = context.resources.displayMetrics.density
            val cfg = AppConfig.load(context)
            val comfort = cfg.comfort
            val threshold = comfort.openScoreMin

            // Tier the text rows by height so a 2x2 still looks intentional.
            val showReadings = hDp >= 130
            val place = cfg.placeName?.trim().orEmpty()
            val showLocation = place.isNotEmpty()

            // Estimate the graph ImageView's true size so its bitmap matches the
            // view aspect (with fitCenter) — this is what keeps text from
            // stretching/compressing when the widget is resized.
            var reservedDp = 24 + 22 + 6 + 21          // padding + headline + graph margin + footer
            if (showReadings) reservedDp += 19
            val ivWdp = (wDp - 24).coerceAtLeast(60)
            val ivHdp = (hDp - reservedDp).coerceAtLeast(40)

            // Show the whole configured forecast window, stretched to fill the width
            // (bars get thinner on a small widget rather than the window getting shorter).
            val hours = s.scores.size.coerceAtLeast(1)
            val scores = s.scores.take(hours)
            val times = s.times.take(hours)
            val worst = s.worst.take(hours)
            val isDay = s.isDay.take(hours)

            val readings = buildString {
                s.tempF?.let { append("${it.roundToInt()}°F") }
                s.dewPointF?.let { append("   ·   dew ${it.roundToInt()}°F") }
                s.aqi?.let { append("   ·   AQI $it") }
                append("   ·   score ${s.nowScore.roundToInt()}")
            }
            // With a location set, condense to fold the place into this one footer line
            // ("📍 City, ST · 6/6 · high  3:45 PM"); without one, keep the verbose form.
            val sources = when {
                s.sourcesOk.isEmpty() -> if (showLocation) "$place · no sources" else "no sources"
                showLocation -> "$place · ${s.sourcesOk.size}/${s.sourcesTotal} · ${s.confidence}"
                else -> "${s.sourcesOk.size}/${s.sourcesTotal} sources · ${s.confidence}"
            }
            val updated = if (refreshing) "updating…"
                else (if (showLocation) "" else "updated ") +
                    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(s.updatedAtMs))

            val ivWpx = (ivWdp * density).toInt().coerceIn(120, 1600)
            val ivHpx = (ivHdp * density).toInt().coerceIn(56, 900)

            return RemoteViews(context.packageName, R.layout.widget_ac_windows).apply {
                setTextViewText(R.id.headline, s.headline)
                setTextViewText(R.id.readings, readings)
                setViewVisibility(R.id.readings, if (showReadings) View.VISIBLE else View.GONE)
                // Location pin sits as the footer line's leading compound drawable when set.
                setTextViewCompoundDrawablesRelative(
                    R.id.sources, if (showLocation) R.drawable.ic_location else 0, 0, 0, 0,
                )
                setTextViewText(R.id.sources, sources)
                setTextViewText(R.id.updated, updated)
                setViewVisibility(R.id.refresh_spinner, if (refreshing) View.VISIBLE else View.GONE)
                // Hide the manual refresh icon while a refresh is in flight (spinner shows instead).
                setViewVisibility(R.id.refresh_button, if (refreshing) View.GONE else View.VISIBLE)
                setInt(R.id.widget_root, "setBackgroundColor", withOpacity(colorFor(s.action), cfg.widgetOpacity))
                if (scores.isNotEmpty()) {
                    setImageViewBitmap(
                        R.id.graph,
                        Graph.render(scores, times, worst, isDay, s.bestStart, s.bestEnd, threshold, ivWpx, ivHpx, density),
                    )
                }
                setOnClickPendingIntent(R.id.widget_root, appIntent(context))
                setOnClickPendingIntent(R.id.refresh_button, refreshIntent(context))
            }
        }
    }
}
