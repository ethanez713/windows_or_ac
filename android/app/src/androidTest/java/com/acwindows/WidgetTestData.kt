package com.acwindows

import android.graphics.Bitmap

/** Distinct pixel colours over a sampled grid — a cheap "did the renderer draw anything" probe. */
internal fun distinctColors(bmp: Bitmap): Int {
    val seen = HashSet<Int>()
    val stepX = (bmp.width / 40).coerceAtLeast(1)
    val stepY = (bmp.height / 40).coerceAtLeast(1)
    var x = 0
    while (x < bmp.width) {
        var y = 0
        while (y < bmp.height) { seen.add(bmp.getPixel(x, y)); y += stepY }
        x += stepX
    }
    return seen.size
}

/**
 * A representative 30-hour snapshot for the render/inflation tests: crosses a midnight and a
 * noon (so the day/night dividers draw), pushes temperature past 90°F and down near freezing
 * (so the hot/cold line branches fire), and carries a wet spell with one thunder hour, varied
 * AQI and a mix of open and closed hours. Kept in sync with the copy in androidTest.
 */
internal fun sampleSnapshot(): Snapshot {
    val zone = java.time.ZoneId.systemDefault()
    val start = java.time.LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).minusHours(2)
    val n = 30
    val times = ArrayList<String>(n)
    val scores = ArrayList<Double>(n)
    val worst = ArrayList<Int>(n)
    val isDay = ArrayList<Boolean>(n)
    val precipProb = ArrayList<Double>(n)
    val precipIn = ArrayList<Double>(n)
    val temp = ArrayList<Double?>(n)
    val dew = ArrayList<Double?>(n)
    val aqi = ArrayList<Int?>(n)
    val thunder = ArrayList<Double?>(n)
    for (i in 0 until n) {
        val t = start.plusHours(i.toLong())
        times.add(t.toString().take(16))
        val hour = t.hour
        isDay.add(hour in 6..19)
        val tf = 62.0 + 33 * Math.sin((hour - 4) / 24.0 * 2 * Math.PI) - (if (i > 22) 40 else 0)
        temp.add(tf)
        dew.add(tf - 15)
        val a = 30 + (i * 7) % 140
        aqi.add(a)
        val rain = if (i in 8..12) 40.0 + (i - 8) * 12 else 0.0
        precipProb.add(rain)
        precipIn.add(if (i in 9..11) 0.15 * (i - 8) else 0.0)
        thunder.add(if (i == 11) 55.0 else 0.0)
        val sc = Score.windowScore(Comfort(), tf, tf - 15, a, rain, null)
        scores.add(sc)
        worst.add(if (sc >= 60) -1 else i % 4)
    }
    val firstMs = start.atZone(zone).toInstant().toEpochMilli()
    return Snapshot(
        action = Action.OPEN_WINDOWS, headline = "Open the windows", summary = "Comfortable out",
        tempF = temp[0], dewPointF = dew[0], aqi = aqi[0], nowScore = scores[0],
        confidence = "high", sourcesOk = listOf("a", "b"), sourcesTotal = 2,
        scores = scores, times = times, worst = worst, isDay = isDay,
        precipProb = precipProb, precipIn = precipIn,
        tempSeries = temp, dewSeries = dew, aqiSeries = aqi, thunderSeries = thunder,
        bestStart = times[2], bestEnd = times[5], note = "",
        updatedAtMs = firstMs, firstHourMs = firstMs,
    )
}
