package com.acwindows

/** The four things that can keep the windows shut. */
enum class Factor { TEMP, DEW, AQI, RAIN }

/** Short label for the limiting factor, as it appears beside its reading. */
fun Factor.label(): String = when (this) {
    Factor.TEMP -> "TEMP"
    Factor.DEW -> "DEW"
    Factor.AQI -> "AIR"
    Factor.RAIN -> "RAIN"
}

/**
 * How far past comfortable a reading is, on a scale where 0 is the ideal, 1 is the cutoff,
 * and above 1 keeps growing.
 *
 * This exists because the score cannot answer "which factor is worst". [Score] is a PRODUCT
 * of ramps, so it hits 0 the moment any single factor bottoms out — every blocked hour of a
 * bad day ties at zero, and picking the limiting factor at the lowest-scoring hour just
 * returns whichever hour came first. Severity keeps rising past the cutoff, so a maximum
 * over it finds the genuinely worst hour and the genuinely worst factor.
 */
fun severity(x: Double?, full: Double, zero: Double): Double {
    if (x == null) return 0.0
    if (zero <= full) return if (x <= full) 0.0 else 1.0
    val span = zero - full
    return when {
        x <= full -> 0.0
        x < zero -> (x - full) / span
        else -> 1.0 + (x - zero) / span
    }
}

/** One hour's severity in every factor, against the user's [Comfort] thresholds. */
fun severities(c: Comfort, p: ForecastPoint): Map<Factor, Double> = mapOf(
    Factor.TEMP to severity(p.tempF, c.tempIdealF, c.maxTempF),
    Factor.DEW to severity(p.dewPointF, c.dewPointIdealF, c.dewPointMaxF),
    Factor.AQI to severity(p.aqi?.toDouble(), c.aqiIdeal, c.aqiMax.toDouble()),
    Factor.RAIN to severity(p.precipProb, c.rainProbIdeal, c.rainProbMax),
)

/** The reading behind [factor] at one hour, formatted the way its label shows it. */
fun readingOf(factor: Factor, p: ForecastPoint): String? = when (factor) {
    Factor.TEMP -> p.tempF?.let { "${Math.round(it)}°" }
    Factor.DEW -> p.dewPointF?.let { "${Math.round(it)}°" }
    Factor.AQI -> p.aqi?.toString()
    Factor.RAIN -> p.precipProb?.let { "${Math.round(it)}%" }
}

/** The worst hour and factor across a span — see [severity] for why it isn't the min score. */
data class Limiting(val factor: Factor, val point: ForecastPoint, val severity: Double)

fun limitingFactor(c: Comfort, points: List<ForecastPoint>): Limiting? =
    points.flatMap { p -> severities(c, p).map { (f, s) -> Limiting(f, p, s) } }
        .filter { it.severity > 0 }
        .maxByOrNull { it.severity }

/**
 * Which weather glyph an hour earns, derived from the numbers rather than read off a
 * per-day forecast code. Deriving it is what guarantees the icon can never contradict the
 * rain percentage printed beside it, which a daily code routinely does.
 */
enum class WxRank { CLEAR, PARTLY, CLOUDY, RAIN, THUNDER }

fun wxRank(precipProb: Double?, thunderProb: Double?, thunderAlert: Double): WxRank {
    val rain = precipProb ?: 0.0
    return when {
        (thunderProb ?: 0.0) >= thunderAlert -> WxRank.THUNDER
        rain >= 20 -> WxRank.RAIN
        rain >= 8 -> WxRank.CLOUDY
        rain > 0 -> WxRank.PARTLY
        else -> WxRank.CLEAR
    }
}

/** Hours counted as daytime for the day glyph; the rest make up the night glyph (§0.5). */
private val DAY_HOURS = 6..19

/**
 * A day's pair of glyphs: the worst rank over its daylight hours, and over its night hours.
 * [hourOf] pulls the local hour out of a point's ISO time.
 */
fun dayNightRanks(
    points: List<ForecastPoint>, thunderAlert: Double, hourOf: (ForecastPoint) -> Int?,
): Pair<WxRank, WxRank> {
    var day = WxRank.CLEAR
    var night = WxRank.CLEAR
    for (p in points) {
        val h = hourOf(p) ?: continue
        val r = wxRank(p.precipProb, p.thunderProb, thunderAlert)
        if (h in DAY_HOURS) {
            if (r > day) day = r
        } else if (r > night) night = r
    }
    return day to night
}

/**
 * The factor holding an hour's score down: the smallest of the four multipliers. This is
 * the *within-hour* question, and it is well-posed even where [limitingFactor]'s
 * across-hours one is not — a single hour's multipliers only tie when the readings
 * genuinely do.
 */
fun ScoreBreakdown.limiting(): Factor? {
    val m = listOf(Factor.TEMP to fTemp, Factor.DEW to fHumid, Factor.AQI to fAqi, Factor.RAIN to fRain)
    return m.minByOrNull { it.second }?.first
}
