package com.acwindows

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Does the actual fetch+decide off the main thread, caches the result, then
 * pushes it to every widget instance. Enqueued by [AcWindowsProvider] on add, on
 * tap, on cache expiry, and on a periodic schedule.
 */
class RefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val cfg = AppConfig.load(applicationContext)
        // Render progressively: paint as soon as 3+ sources answer, then redraw on every
        // later source/forecast arrival (each call updates the widget's "N/X sources" count).
        val result = WeatherRepository.fetchProgressive(cfg) { partial ->
            publish(applicationContext, cfg, partial)
        }
        val con = result.consensus
        // A final paint covers the case where no progressive frame ever published (e.g. the
        // soft deadline passed with exactly 3 sources and they all failed). publish() applies
        // the same empty-data guard, so a dead refresh never clobbers the last good snapshot.
        if (publish(applicationContext, cfg, result)) {
            if (con.confidence == "none") Result.retry() else Result.success()
        } else {
            Log.w(TAG, "refresh returned no data; keeping last cached snapshot; failed=${con.sourcesFailed}")
            AcWindowsProvider.refreshDone(applicationContext)
            Result.retry()
        }
    } catch (e: Exception) {
        Log.w(TAG, "refresh failed, will retry", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "AcWindows"

        /**
         * Build a snapshot from a (partial or final) result, cache it, and redraw every
         * widget. Returns false — drawing nothing — when the result carries no usable data
         * (no sources AND no forecast), so the last good snapshot stays on screen.
         */
        private fun publish(context: Context, cfg: AppConfig, result: WeatherResult): Boolean {
            val con = result.consensus
            val fc = result.forecast
            if (con.confidence == "none" && fc.points.isEmpty()) return false

            val rec = Decision.decide(con, cfg.comfort)
            val nowScore = Score.windowScore(
                cfg.comfort, tempF = con.tempF, dewPointF = con.dewPointF,
                aqi = con.aqi, precipMm = con.precipMm,
            )
            // Per-hour dominant detracting factor (argmin of the four multipliers), so the
            // widget can colour the baseline of a 0-score bar by *why* it's closed.
            val worst = fc.points.map { p ->
                val b = p.breakdown ?: return@map -1
                val m = listOf(b.fTemp, b.fHumid, b.fAqi, b.fRain)
                m.indices.minByOrNull { m[it] } ?: -1
            }
            val snapshot = Snapshot(
                action = rec.action,
                headline = windowHeadline(rec, fc),
                summary = rec.summary,
                tempF = con.tempF,
                dewPointF = con.dewPointF,
                aqi = con.aqi,
                nowScore = nowScore,
                confidence = con.confidence,
                sourcesOk = con.sourcesOk,
                sourcesTotal = con.sourcesAttempted,
                scores = fc.points.map { it.score },
                times = fc.points.map { it.time },
                worst = worst,
                isDay = fc.points.map { it.isDay ?: true },
                bestStart = fc.bestStart,
                bestEnd = fc.bestEnd,
                note = fc.notes.firstOrNull() ?: "",
                updatedAtMs = System.currentTimeMillis(),
            )
            WidgetCache.save(context, snapshot)
            Log.i(
                TAG,
                "fetch ok=${con.sourcesOk}/${con.sourcesAttempted} failed=${con.sourcesFailed} " +
                    "confidence=${con.confidence} now=${con.tempF} score=$nowScore -> ${rec.action}; " +
                    "best=${fc.bestStart}..${fc.bestEnd}",
            )
            AcWindowsProvider.render(context, snapshot)
            return true
        }

        /**
         * The headline tells you WHEN to open up — the best window's span becomes
         * the main message, replacing the action labels ("Open your windows",
         * "Turn on the AC"). Falls back to the decision's headline only when the
         * look-ahead finds no openable window at all.
         */
        private fun windowHeadline(rec: Recommendation, fc: Forecast): String {
            val start = fc.bestStart ?: return rec.headline
            val end = fc.bestEnd ?: return rec.headline
            val to = ForecastBuilder.fmtHour(end)
            return if (start == fc.points.firstOrNull()?.time) "Open your windows now–$to"
                else "Open your windows from ${ForecastBuilder.fmtHour(start)}–$to"
        }
    }
}
