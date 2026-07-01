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

    fun fmtHour(iso: String): String {
        val h = hour(iso)
        if (h < 0) return iso
        val ampm = if (h < 12) "AM" else "PM"
        val h12 = (h % 12).let { if (it == 0) 12 else it }
        return "$h12 $ampm"
    }

    fun build(omHourly: List<Map<String, Any?>>, crossHourly: List<Map<String, Any?>>, c: Comfort): Forecast {
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
        if (points.isEmpty()) return Forecast()

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
            notes = notes(points, bStart, bEnd, bAvg, c),
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
    ): List<String> {
        val out = mutableListOf<String>()
        val thr = c.openScoreMin
        val nowOpen = points[0].score >= thr

        if (bStart == null) {
            out.add("Outdoor air won't beat the AC in the next ${points.size}h — keep closed / run AC.")
            return out
        }
        if (bStart == points[0].time) out.add("Good to open now (through ${fmtHour(bEnd!!)}).")
        else out.add("Best window to open: ${fmtHour(bStart)}–${fmtHour(bEnd!!)} (avg ${bAvg!!.toInt()}).")

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
