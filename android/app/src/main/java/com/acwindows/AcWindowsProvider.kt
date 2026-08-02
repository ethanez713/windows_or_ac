package com.acwindows

import android.app.AlarmManager
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
import androidx.work.ExistingWorkPolicy
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
 * Auto-refresh runs on its own every [TICK_INTERVAL_MS] (10 min), driven by an
 * AlarmManager tick with a WorkManager periodic job as a backstop — see
 * [scheduleTick] for why it takes both.
 *
 * Rendering is per-widget and size-aware: it shows as many forecast hours as fit
 * the widget's width and tiers the text rows by height, so the same widget works
 * from 2x2 up to a full-width banner.
 */
class AcWindowsProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val snap = WidgetCache.load(context)
        val stale = snap == null || snap.ageMs() > WidgetCache.TTL_MS
        // Only spin when there's nothing to show yet; with a cached snapshot the old data
        // stays readable while the refresh runs.
        ids.forEach { id -> drawId(context, mgr, id, refreshing = snap == null) }
        if (stale) enqueueRefresh(context)   // only fetch when actually due
        scheduleTick(context)
        schedulePeriodic(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle,
    ) {
        // Re-fit bars to the new size. The launcher also sends this right after onUpdate when
        // the widget is first placed, so with no snapshot yet it lands mid-first-fetch —
        // report that as still loading rather than flashing the "couldn't fetch" state.
        drawId(context, mgr, id, refreshing = WidgetCache.load(context) == null)
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
            // The 10-minute auto-refresh tick. Re-arm first (so the chain survives even
            // if the fetch never runs), then refresh quietly — no spinner, because this
            // one isn't user-initiated and the old data stays readable meanwhile.
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
        const val ACTION_REFRESH = "com.acwindows.ACTION_REFRESH"
        private const val ACTION_TICK = "com.acwindows.ACTION_TICK"
        private const val PERIODIC_WORK = "ac_windows_periodic"
        private const val REFRESH_WORK = "ac_windows_refresh"

        /**
         * How often the widget refreshes itself, unprompted — and, by the same token, how
         * long a cached snapshot is considered current (see [WidgetCache.TTL_MS]).
         */
        const val TICK_INTERVAL_MS = WidgetCache.TTL_MS

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

        /**
         * Queue a refresh, coalescing with one already queued or running (KEEP). Every
         * trigger funnels through here — a tap, the 10-minute tick, a settings save, and
         * onUpdate, which the system can fire several times in a row on its own — so
         * without a unique name a single moment could kick off four overlapping fetches
         * that then fight over the snapshot. An in-flight refresh publishes within seconds
         * anyway, so joining it is what the user wanted regardless.
         */
        fun enqueueRefresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                REFRESH_WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>().build(),
            )
        }

        private fun alarms(context: Context) =
            context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        /** The self-rescheduling 10-minute tick broadcast (see [scheduleTick]). */
        private fun tickIntent(context: Context): PendingIntent {
            val intent = Intent(context, AcWindowsProvider::class.java).setAction(ACTION_TICK)
            return PendingIntent.getBroadcast(
                context, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Arm the next auto-refresh, [TICK_INTERVAL_MS] from now. Called on every widget
         * update, on boot, and by [RefreshWorker] after each run, so the clock restarts
         * from the last actual refresh and a dropped tick heals on the next one.
         *
         * WorkManager can't do this alone: its periodic floor is 15 min, which is why the
         * widget only ever moved when you tapped it. setAndAllowWhileIdle is inexact and
         * needs no permission, but still fires in Doze (the OS throttles it to roughly
         * every 9 min at worst), so 10 min is honoured whenever the phone is awake.
         */
        fun scheduleTick(context: Context) {
            val am = alarms(context) ?: return
            // Same PendingIntent every time, so re-arming reschedules rather than stacking.
            val pi = tickIntent(context)
            // With no widget on a home screen there's nothing to refresh — don't leave a
            // self-perpetuating alarm running (settings screens refresh via the same worker).
            if (widgetIds(context).isEmpty()) {
                am.cancel(pi)
                return
            }
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + TICK_INTERVAL_MS, pi)
            }
        }

        private fun schedulePeriodic(context: Context) {
            // Backstop only — the 10-min alarm tick is the primary driver. 15 min is
            // WorkManager's floor. UPDATE (not KEEP) so a reinstall/version bump re-applies
            // the interval instead of keeping a stale longer one.
            val work = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, work,
            )
        }

        /** Draw immediately (spinner over current/loading view) and kick off a fetch. */
        fun refreshNow(context: Context) {
            renderAll(context, refreshing = true)
            enqueueRefresh(context)
        }

        private fun widgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, AcWindowsProvider::class.java))

        /** Redraw every instance from the cache — the single source of truth for what's shown. */
        fun renderAll(context: Context, refreshing: Boolean) {
            val mgr = AppWidgetManager.getInstance(context)
            widgetIds(context).forEach { drawId(context, mgr, it, refreshing) }
        }

        private fun drawId(context: Context, mgr: AppWidgetManager, id: Int, refreshing: Boolean) {
            val snap = WidgetCache.load(context)
            val views = if (snap == null) loadingView(context, refreshing)
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

        /**
         * The pre-first-data view. [refreshing] false means a fetch has already been tried
         * and came back with nothing, so this says so and offers the refresh button — the
         * only way back. It used to hard-code the spinner and hide that button, which left a
         * widget whose first fetch failed sitting on "fetching weather…" with no tap target
         * but the body, and that only opens the app (which has its own fetch and never
         * writes the widget's cache).
         */
        private fun loadingView(context: Context, refreshing: Boolean): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_ac_windows).apply {
                setTextViewText(R.id.headline, if (refreshing) "Loading…" else "No data yet")
                setTextViewText(R.id.readings, "")
                setViewVisibility(R.id.readings, View.GONE)
                setTextViewCompoundDrawablesRelative(R.id.sources, 0, 0, 0, 0)
                setTextViewText(
                    R.id.sources,
                    if (refreshing) "fetching weather…" else "couldn't reach the weather sources",
                )
                setTextViewText(R.id.updated, if (refreshing) "" else "tap ⟳ to retry")
                setTextColor(R.id.updated, if (refreshing) FOOTER_DIM else STALE_AMBER)
                setViewVisibility(R.id.refresh_spinner, if (refreshing) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.refresh_button, if (refreshing) View.GONE else View.VISIBLE)
                // Must clear the graph and set the background explicitly — see [buildViews].
                // Neutral slate while loading: the first paint shouldn't imply a verdict.
                setImageViewBitmap(R.id.graph, null)
                setInt(R.id.widget_root, "setBackgroundColor", colorFor(Action.KEEP_CLOSED))
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

            val readings = buildString {
                s.tempF?.let { append("${it.roundToInt()}°F") }
                s.dewPointF?.let { append("   ·   dew ${it.roundToInt()}°F") }
                s.aqi?.let { append("   ·   AQI $it") }
                append("   ·   score ${s.nowScore.roundToInt()}")
            }
            // With a location set, condense to fold the place into this one footer line
            // ("📍 City, ST · 6/6 · high"); without one, keep the verbose form.
            val sources = when {
                s.sourcesOk.isEmpty() -> if (showLocation) "$place · no sources" else "no sources"
                showLocation -> "$place · ${s.sourcesOk.size}/${s.sourcesTotal} · ${s.confidence}"
                else -> "${s.sourcesOk.size}/${s.sourcesTotal} sources · ${s.confidence}"
            }

            val ivWpx = (ivWdp * density).toInt().coerceIn(120, 1600)
            val ivHpx = (ivHdp * density).toInt().coerceIn(56, 900)
            val nowMs = System.currentTimeMillis()

            return RemoteViews(context.packageName, R.layout.widget_ac_windows).apply {
                setTextViewText(R.id.headline, s.headline)
                setTextViewText(R.id.readings, readings)
                setViewVisibility(R.id.readings, if (showReadings) View.VISIBLE else View.GONE)
                // Location pin sits as the footer line's leading compound drawable when set.
                setTextViewCompoundDrawablesRelative(
                    R.id.sources, if (showLocation) R.drawable.ic_location else 0, 0, 0, 0,
                )
                setTextViewText(R.id.sources, sources)
                setTextViewText(R.id.updated, asOfLabel(s, refreshing, nowMs))
                setTextColor(R.id.updated, if (s.ageMs(nowMs) > WidgetCache.STALE_MS) STALE_AMBER else FOOTER_DIM)
                setViewVisibility(R.id.refresh_spinner, if (refreshing) View.VISIBLE else View.GONE)
                // Hide the manual refresh icon while a refresh is in flight (spinner shows instead).
                setViewVisibility(R.id.refresh_button, if (refreshing) View.GONE else View.VISIBLE)
                setInt(R.id.widget_root, "setBackgroundColor", withOpacity(colorFor(s.action), cfg.widgetOpacity))
                // ALWAYS set the graph, even when there is nothing to draw. The launcher
                // reapplies a RemoteViews onto the views already on screen rather than
                // re-inflating, so any property we DON'T set keeps its previous value —
                // an omitted setImageViewBitmap left the old chart sitting under freshly
                // updated text, which is exactly the "bars and text disagree" symptom.
                setImageViewBitmap(
                    R.id.graph,
                    if (s.scores.isEmpty()) null else Graph.render(
                        s.scores, s.times, s.worst, s.isDay, s.precipProb, s.precipIn,
                        s.bestStart, s.bestEnd, threshold, ivWpx, ivHpx, density,
                        nowPos = nowBarPos(s.firstHourMs, nowMs),
                    ),
                )
                setOnClickPendingIntent(R.id.widget_root, appIntent(context))
                setOnClickPendingIntent(R.id.refresh_button, refreshIntent(context))
            }
        }

        private val FOOTER_DIM = Color.parseColor("#B3FFFFFF")
        private val STALE_AMBER = Color.parseColor("#FFFBBF24")

        /**
         * The footer's right-hand label: what time the widget's data is FROM. Says so
         * explicitly ("as of 3:45 PM") rather than leaving a bare clock time that reads
         * like the current time, and calls out its age once the data is properly stale, so
         * a widget the system has stopped refreshing is obvious at a glance instead of
         * quietly showing this morning's numbers.
         */
        private fun asOfLabel(s: Snapshot, refreshing: Boolean, nowMs: Long): String {
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
