package com.acwindows

import kotlin.math.abs

/**
 * Builds a scored [Forecast] from hourly series — a port of the Python backend's
 * forecast.py. Open-Meteo drives the score; NWS (and met.no) cross-check the
 * temperature track.
 *
 * Each hourly map carries: time, tempF, dewPointF, aqi, precipProb, shortwaveWm2.
 */
object ForecastBuilder {

    private const val FORECAST_TOL_F = 8.0
    private const val NIGHT_START = 21
    private const val NIGHT_END = 8

    fun hour(iso: String): Int =
        if (iso.length >= 13) iso.substring(11, 13).toIntOrNull() ?: -1 else -1

    private fun minute(iso: String): Int =
        if (iso.length >= 16) iso.substring(14, 16).toIntOrNull() ?: 0 else 0

    fun fmtHour(iso: String): String {
        val h = hour(iso)
        return if (h < 0) iso else fmtHourOf(h)
    }

    /**
     * A window's CLOSING label. [bestWindow] reports the last hour that is still open,
     * and an hourly row covers the whole hour it names — so a window whose last open row
     * is 6 AM actually runs until 7 AM. Labelling that row with its own hour under-reports
     * every window by an hour and collapses a one-hour window into a meaningless
     * "6 AM–6 AM", which is what the widget headline was showing.
     */
    fun fmtHourEnd(iso: String): String = fmtRowEnd(iso, 60)

    /**
     * Same closing-edge label as [fmtHourEnd], generalized to any row width: adds
     * [stepMinutes] instead of a fixed hour. A whole-hour step (60, 120, ...) keeps the
     * plain "7 AM" form — every row of that width lands back on the hour, so showing
     * ":00" would just be noise. Anything sub-hour shows minutes explicitly ("6:15 AM",
     * "7:00 AM") because the whole point of a 15-minute series is that it DOESN'T always
     * land on the hour.
     */
    fun fmtRowEnd(iso: String, stepMinutes: Int): String {
        val h = hour(iso)
        if (h < 0) return iso
        val total = ((h * 60 + minute(iso) + stepMinutes) % 1440 + 1440) % 1440
        val eh = total / 60
        val em = total % 60
        return if (stepMinutes % 60 == 0) fmtHourOf(eh) else fmtClockOf(eh, em)
    }

    private fun fmtHourOf(h: Int): String {
        val ampm = if (h < 12) "AM" else "PM"
        val h12 = (h % 12).let { if (it == 0) 12 else it }
        return "$h12 $ampm"
    }

    private fun fmtClockOf(h: Int, m: Int): String {
        val ampm = if (h < 12) "AM" else "PM"
        val h12 = (h % 12).let { if (it == 0) 12 else it }
        return "$h12:${m.toString().padStart(2, '0')} $ampm"
    }

    fun build(
        omHourly: List<Map<String, Any?>>, crossHourly: List<Map<String, Any?>>, c: Comfort,
        stepMinutes: Int = 60,
    ): Forecast {
        val crossTemp = crossHourly.mapNotNull { h ->
            val t = h["time"] as? String ?: return@mapNotNull null
            (h["tempF"] as? Double)?.let { t to it }
        }.toMap()

        val points = omHourly.mapNotNull { h ->
            val time = h["time"] as? String ?: return@mapNotNull null
            val tempF = h["tempF"] as? Double
            val dewPointF = h["dewPointF"] as? Double
            val aqi = h["aqi"] as? Int
            val precipProb = h["precipProb"] as? Double
            val precipIn = h["precipIn"] as? Double
            val weatherCode = h["weatherCode"] as? Int
            val isDay = h["isDay"] as? Boolean
            val shortwaveWm2 = h["shortwaveWm2"] as? Double
            val breakdown = Score.windowScoreBreakdown(
                c, tempF = tempF, dewPointF = dewPointF, aqi = aqi, precipProb = precipProb,
            )
            ForecastPoint(
                time = time,
                score = breakdown.score,
                tempF = tempF,
                dewPointF = dewPointF,
                aqi = aqi,
                precipProb = precipProb,
                precipIn = precipIn,
                weatherCode = weatherCode,
                isDay = isDay,
                shortwaveWm2 = shortwaveWm2,
                nwsTempF = crossTemp[time],
                breakdown = breakdown,
            )
        }
        if (points.isEmpty()) return Forecast(stepMinutes = stepMinutes)

        val (bStart, bEnd, bAvg) = bestWindow(points, c.openScoreMin)

        val diffs = points.mapNotNull { p ->
            if (p.tempF != null && p.nwsTempF != null) abs(p.tempF - p.nwsTempF) else null
        }
        val agreement = when {
            crossTemp.isEmpty() -> "degraded"
            diffs.isEmpty() -> "none"
            diffs.average() <= FORECAST_TOL_F -> "agree"
            else -> "conflict"
        }

        return Forecast(
            points = points, bestStart = bStart, bestEnd = bEnd, bestAvgScore = bAvg,
            tempAgreement = agreement,
            notes = notes(points, bStart, bEnd, bAvg, c, stepMinutes),
            stepMinutes = stepMinutes,
        )
    }

    private data class Best(val start: String?, val end: String?, val avg: Double?)

    private fun bestWindow(points: List<ForecastPoint>, threshold: Double): Best {
        var best: Triple<Double, Int, Int>? = null  // area, startIdx, endIdx
        var i = 0
        while (i < points.size) {
            if (points[i].score >= threshold) {
                var j = i
                var area = 0.0
                while (j < points.size && points[j].score >= threshold) {
                    area += points[j].score - threshold; j++
                }
                if (best == null || area > best!!.first) best = Triple(area, i, j - 1)
                i = j
            } else i++
        }
        val b = best ?: return Best(null, null, null)
        val slice = points.subList(b.second, b.third + 1)
        val avg = (slice.sumOf { it.score } / slice.size * 10).toInt() / 10.0
        return Best(points[b.second].time, points[b.third].time, avg)
    }

    private fun notes(
        points: List<ForecastPoint>, bStart: String?, bEnd: String?, bAvg: Double?, c: Comfort,
        stepMinutes: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        val thr = c.openScoreMin
        val nowOpen = points[0].score >= thr

        if (bStart == null) {
            // A count of points is only a duration once scaled by the row width — at 15-minute
            // resolution "the next 96" would otherwise misreport a day as 96 hours.
            val spanHours = Math.round(points.size * stepMinutes / 60.0)
            out.add("Outdoor air won't beat the AC in the next ${spanHours}h — keep closed / run AC.")
            return out
        }
        if (bStart == points[0].time) out.add("Good to open now (through ${fmtRowEnd(bEnd!!, stepMinutes)}).")
        else out.add("Best window to open: ${fmtHour(bStart)}–${fmtRowEnd(bEnd!!, stepMinutes)} (avg ${bAvg!!.toInt()}).")

        val sh = hour(bStart)
        if (sh >= NIGHT_START || sh < NIGHT_END)
            out.add("Overnight is the strong flush window — let the house coast on cool air.")

        if (nowOpen) {
            val drop = points.drop(1).firstOrNull { it.score < thr }
            if (drop != null)
                out.add("Open now, then close up before ~${fmtHour(drop.time)} when it warms.")
        }
        return out.take(3)
    }
}
