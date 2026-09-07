package com.acwindows

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
import androidx.work.WorkManager

/**
 * The forecast widget (docs/ux-redesign.md §C): one panel answering "what will the weather
 * do in the next N hours", as opposed to [AcWindowsProvider]'s "when do I open the windows".
 * The two questions are deliberately kept apart, so this is a separate widget rather than a
 * mode of that one.
 *
 * Configuration is PER WIDGET INSTANCE ([ForecastWidgetConfig]) — several can sit on one
 * home screen at different horizons. Like [GraphsWidgetProvider] it shares
 * [AcWindowsProvider]'s alarm tick, the cached [Snapshot] and the [RefreshWorker] fetch:
 * this file only READS the cache and asks for a refresh, never fetches.
 */
class ForecastWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val snap = WidgetCache.load(context)
        ids.forEach { id -> drawId(context, mgr, id, refreshing = snap == null) }
        if (snap == null || snap.ageMs() > WidgetCache.TTL_MS) AcWindowsProvider.enqueueRefresh(context)
        AcWindowsProvider.scheduleTick(context)
        AcWindowsProvider.schedulePeriodic(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle,
    ) {
        drawId(context, mgr, id, refreshing = WidgetCache.load(context) == null)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { ForecastWidgetConfig.delete(context, it) }
        AcWindowsProvider.scheduleTick(context)
    }

    override fun onEnabled(context: Context) {
        AcWindowsProvider.scheduleTick(context)
        AcWindowsProvider.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        AcWindowsProvider.scheduleTick(context)
        if (AcWindowsProvider.widgetIds(context).isEmpty() &&
            GraphsWidgetProvider.widgetIds(context).isEmpty()
        ) {
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
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                AcWindowsProvider.scheduleTick(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.acwindows.ACTION_FORECAST_REFRESH"

        fun widgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, ForecastWidgetProvider::class.java))

        fun refreshDone(context: Context) = renderAll(context, refreshing = false)

        fun renderAll(context: Context, refreshing: Boolean) {
            val mgr = AppWidgetManager.getInstance(context)
            widgetIds(context).forEach { drawId(context, mgr, it, refreshing) }
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

        private fun buildViews(
            context: Context, id: Int, refreshing: Boolean, size: Pair<Int, Int>,
        ): RemoteViews {
            val (wDp, hDp) = size
            val widget = ForecastWidgetConfig.load(context, id)
            val dark = widget.isDark(context)
            val chrome = Tokens.chrome(dark)
            val cfg = AppConfig.load(context)
            val snap = WidgetCache.load(context)
            val density = context.resources.displayMetrics.density

            return RemoteViews(context.packageName, R.layout.widget_forecast).apply {
                setImageViewBitmap(R.id.forecast_card, cardBitmap(chrome.card, cfg.widgetOpacity, wDp, hDp, density))
                // ALWAYS set the panel, even with nothing to draw: the launcher reapplies a
                // RemoteViews onto the views already on screen rather than re-inflating, so
                // a panel we don't set keeps the previous one under freshly updated text.
                val panel = snap?.takeIf { it.scores.isNotEmpty() }?.let {
                    ForecastPanel.render(
                        context, it, cfg, widget, TempUnit.load(context),
                        widthPx = ((wDp - 20) * density).toInt().coerceIn(160, 1400),
                        heightPx = ((hDp - 20) * density).toInt().coerceIn(90, 900),
                        density = density, dark = dark,
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
         * The rounded card ground. Drawn rather than styled for the same reason as
         * [AcWindowsProvider.cardBitmap]: RemoteViews cannot tint a shape drawable before
         * API 31, and the ground carries the user's opacity.
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
