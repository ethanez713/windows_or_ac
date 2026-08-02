package com.acwindows

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Does the actual fetch+decide off the main thread, caches the result, then
 * pushes it to every widget instance. Enqueued by [AcWindowsProvider] on add, on
 * tap, on cache expiry, and on the 10-minute auto-refresh tick.
 *
 * Every run — however it was triggered — re-arms the next tick, so the auto-refresh
 * chain heals itself and the clock always restarts from the last actual refresh.
 *
 * Refreshes are strictly single-file (see [running]). Two runs overlapping was the root
 * of the widget's "half-updated" look: both publish progressive frames into the same
 * cache key, so a slower run's earlier, thinner frame could land *after* a faster run's
 * complete one and leave the widget showing a mix of the two.
 */
class RefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    /**
     * Always finishes successfully, even when the fetch came back empty — the failure is
     * logged and the widget says so, but WorkManager is told the run is over.
     *
     * [Result.retry] is actively harmful here. A retrying unique work stays ENQUEUED for
     * the whole of its backoff (30s, doubling towards hours), and every refresh trigger
     * goes through [AcWindowsProvider.enqueueRefresh], which uses KEEP — so while that
     * backoff ran, each 10-minute tick was silently dropped instead of retrying. One
     * transient DNS failure could therefore strand a widget with no cached snapshot on
     * "fetching weather…" for hours. The tick is the retry mechanism (re-armed at the top
     * of [refresh] on every run); reaching a terminal state is what lets the next one in.
     */
    override suspend fun doWork(): Result = try {
        running.withLock { refresh() }
    } catch (e: Exception) {
        Log.w(TAG, "refresh failed; next tick will retry", e)
        AcWindowsProvider.refreshDone(applicationContext)
        Result.success()
    }

    private suspend fun refresh(): Result {
        AcWindowsProvider.scheduleTick(applicationContext)
        // loadResolved (not load): when the user has "use my phone's location" on, this is
        // where the current fix is picked up, so the widget follows you without any
        // separate location plumbing.
        val cfg = AppConfig.loadResolved(applicationContext)
        // Render progressively: paint as soon as 3+ sources answer, then redraw on every
        // later source/forecast arrival (each call updates the widget's "N/X sources" count).
        val result = WeatherRepository.fetchProgressive(cfg) { partial ->
            publish(applicationContext, cfg, partial, final = false)
        }
        val con = result.consensus
        // A final paint covers the case where no progressive frame ever published (e.g. the
        // soft deadline passed with exactly 3 sources and they all failed). publish() applies
        // the same empty-data guard, so a dead refresh never clobbers the last good snapshot.
        if (!publish(applicationContext, cfg, result, final = true)) {
            Log.w(TAG, "refresh returned no data; keeping last cached snapshot; failed=${con.sourcesFailed}")
            AcWindowsProvider.refreshDone(applicationContext)
        }
        return Result.success()   // see [doWork] — never retry(), the tick drives retries
    }

    companion object {
        private const val TAG = "AcWindows"

        /**
         * Serialises refreshes across the whole process. WorkManager will happily run the
         * one-time chain and the 15-minute periodic backstop at the same time, and both
         * write the same snapshot — so uniqueness at the enqueue site isn't enough on its own.
         */
        private val running = Mutex()

        /**
         * Build a snapshot from a (partial or final) result, cache it, and redraw every
         * widget. Returns false — drawing nothing — when the result carries no usable data
         * (no sources AND no forecast), so the last good snapshot stays on screen.
         *
         * [final] keeps the spinner up across the progressive frames and drops it only on
         * the last one, so "updating…" means the refresh is genuinely still going.
         */
        private fun publish(
            context: Context, cfg: AppConfig, result: WeatherResult, final: Boolean,
        ): Boolean {
            val con = result.consensus
            val fc = result.forecast
            if (con.confidence == "none" && fc.points.isEmpty()) return false

            val rec = Decision.decide(con, cfg.comfort)
            val nowScore = Score.windowScore(
                cfg.comfort, tempF = con.tempF, dewPointF = con.dewPointF,
                aqi = con.aqi, precipMm = con.precipMm,
            )
            // The hourly feeds are slower than the reading sources (two round trips each),
            // so early progressive frames — and a run whose feeds miss the soft deadline —
            // routinely arrive with no forecast at all. Writing that through would blank a
            // graph we already have, so keep the cached series instead. It stays honest
            // because the series carries its own wall-clock anchor: the graph draws "now"
            // wherever it really falls in those bars, however old they are.
            val series = seriesOf(fc, cfg) ?: seriesOf(WidgetCache.load(context)) ?: Series()
            val snapshot = Snapshot(
                action = rec.action,
                headline = windowHeadline(rec, series),
                summary = rec.summary,
                tempF = con.tempF,
                dewPointF = con.dewPointF,
                aqi = con.aqi,
                nowScore = nowScore,
                confidence = con.confidence,
                sourcesOk = con.sourcesOk,
                sourcesTotal = con.sourcesAttempted,
                scores = series.scores,
                times = series.times,
                worst = series.worst,
                isDay = series.isDay,
                precipProb = series.precipProb,
                precipIn = series.precipIn,
                bestStart = series.bestStart,
                bestEnd = series.bestEnd,
                note = series.note,
                updatedAtMs = System.currentTimeMillis(),
                firstHourMs = series.firstHourMs,
            )
            WidgetCache.save(context, snapshot)
            Log.i(
                TAG,
                "fetch ok=${con.sourcesOk}/${con.sourcesAttempted} failed=${con.sourcesFailed} " +
                    "confidence=${con.confidence} now=${con.tempF} score=$nowScore -> ${rec.action}; " +
                    "best=${series.bestStart}..${series.bestEnd} hours=${series.scores.size}",
            )
            AcWindowsProvider.renderAll(context, refreshing = !final)
            return true
        }

        /**
         * The forecast half of a [Snapshot]: the graph's series plus the window it
         * describes. Split out so a frame that arrived without a forecast can reuse the
         * cached one wholesale — every field here has to move together or the graph and
         * its underline/headline stop agreeing.
         */
        private data class Series(
            val scores: List<Double> = emptyList(),
            val times: List<String> = emptyList(),
            val worst: List<Int> = emptyList(),
            val isDay: List<Boolean> = emptyList(),
            val precipProb: List<Double> = emptyList(),
            val precipIn: List<Double> = emptyList(),
            val bestStart: String? = null,
            val bestEnd: String? = null,
            val note: String = "",
            val firstHourMs: Long = 0L,
        )

        /** The series a freshly-built forecast carries, or null when it has no hours. */
        private fun seriesOf(fc: Forecast, cfg: AppConfig): Series? {
            if (fc.points.isEmpty()) return null
            return Series(
                scores = fc.points.map { it.score },
                times = fc.points.map { it.time },
                // Per-hour dominant detracting factor (argmin of the four multipliers), so the
                // widget can colour the baseline of a 0-score bar by *why* it's closed.
                worst = fc.points.map { p ->
                    val b = p.breakdown ?: return@map -1
                    val m = listOf(b.fTemp, b.fHumid, b.fAqi, b.fRain)
                    m.indices.minByOrNull { m[it] } ?: -1
                },
                isDay = fc.points.map { it.isDay ?: true },
                precipProb = fc.points.map { it.precipProb ?: 0.0 },
                precipIn = fc.points.map { it.precipIn ?: 0.0 },
                bestStart = fc.bestStart,
                bestEnd = fc.bestEnd,
                note = fc.notes.firstOrNull() ?: "",
                firstHourMs = epochMs(fc.points.first().time, cfg.zone()) ?: 0L,
            )
        }

        /** The series already on screen, or null when there isn't one to keep. */
        private fun seriesOf(prev: Snapshot?): Series? {
            if (prev == null || prev.scores.isEmpty()) return null
            return Series(
                scores = prev.scores, times = prev.times, worst = prev.worst, isDay = prev.isDay,
                precipProb = prev.precipProb, precipIn = prev.precipIn,
                bestStart = prev.bestStart, bestEnd = prev.bestEnd, note = prev.note,
                firstHourMs = prev.firstHourMs,
            )
        }

        /**
         * An hourly row's local time ("2026-08-02T14:00") as an instant. The rows come back
         * in whatever zone the config asked the provider for, which is not necessarily the
         * phone's — so the config's zone, not the default one, is what makes them absolute.
         */
        private fun epochMs(iso: String, zone: ZoneId): Long? = runCatching {
            LocalDateTime.parse(iso.take(16)).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()

        /**
         * The headline tells you WHEN to open up — the best window's span becomes
         * the main message, replacing the action labels ("Open your windows",
         * "Turn on the AC"). Falls back to the decision's headline only when the
         * look-ahead finds no openable window at all.
         */
        private fun windowHeadline(rec: Recommendation, series: Series): String {
            val start = series.bestStart ?: return rec.headline
            val end = series.bestEnd ?: return rec.headline
            val startIdx = series.times.indexOf(start)
            val endIdx = series.times.indexOf(end)
            // Say "now" only once the window has really started, and stop advertising one
            // that has already finished. Comparing against the series' FIRST hour instead
            // (as this used to) keeps claiming "now" for a series that has since aged past
            // it — that's the headline drifting away from what the bars show.
            val now = nowBarPos(series.firstHourMs) ?: 0.0
            if (endIdx >= 0 && endIdx + 1 < now) return rec.headline   // window is in the past
            val to = ForecastBuilder.fmtHourEnd(end)
            return if (startIdx in 0..now.toInt()) "Open your windows now–$to"
                else "Open your windows from ${ForecastBuilder.fmtHour(start)}–$to"
        }
    }
}
