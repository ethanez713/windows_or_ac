package com.acwindows

import kotlin.math.roundToInt

/**
 * The recommendation engine — a faithful port of the Python backend's
 * decision.py. Pure (no I/O), so it's the natural seam for future smart-home
 * control: today it returns an [Action]; later a controller maps actions to
 * devices (thermostat, attic fan, window openers) without changing this logic.
 *
 * Opening windows is "free cooling": worth it only when outdoor air is cooler
 * than your target AND comfortable AND clean AND dry. Dew point (not relative
 * humidity) is the honest mugginess gauge.
 */
object Decision {

    fun aqiCategory(aqi: Int?): String = when {
        aqi == null -> "unknown"
        aqi <= 50 -> "good"
        aqi <= 100 -> "moderate"
        aqi <= 150 -> "unhealthy for sensitive groups"
        aqi <= 200 -> "unhealthy"
        aqi <= 300 -> "very unhealthy"
        else -> "hazardous"
    }

    fun decide(c: Consensus, cfg: Comfort): Recommendation {
        val temp = c.tempF ?: return Recommendation(
            Action.UNKNOWN,
            "No data",
            "Couldn't read the outdoor temperature from any source.",
            c.sourcesFailed.ifEmpty { listOf("all sources unavailable") },
        )

        // PHASE-2 SEAM: decide() is purely outdoor-driven today. When a [HomeState]
        // (DeviceSource) supplies indoor temp / thermostat setpoint / attic-fan
        // state, refine here — e.g. only recommend OPEN_WINDOWS when outdoor is
        // cooler than *indoor* (free cooling), and surface "run the attic fan" as
        // an action. Kept out of the signature until that data exists.
        val reasons = mutableListOf<String>()
        val aqiBad = c.aqi != null && c.aqi > cfg.aqiMax
        val raining = c.precipMm != null && c.precipMm > 0
        val muggy = c.dewPointF != null && c.dewPointF > cfg.dewPointMaxF
        val tooWarm = temp > cfg.maxTempF
        val coolEnough = temp <= cfg.tempIdealF

        if (aqiBad) reasons.add(
            "AQI ${c.aqi} (${aqiCategory(c.aqi)}) is above your limit of ${cfg.aqiMax} " +
                "— outdoor air is unhealthy to let in"
        )
        if (raining) reasons.add("precipitation right now — opening windows risks letting rain in")
        if (muggy) reasons.add(
            "dew point ${c.dewPointF!!.roundToInt()}°F is muggy " +
                "(above ${cfg.dewPointMaxF.roundToInt()}°F) — fresh air would feel clammy"
        )

        val windowsBlocked = aqiBad || raining || muggy

        // 1) Too warm to coast — ventilation can't cool below outdoor temp.
        if (tooWarm) {
            reasons.add(0, "it's ${temp.roundToInt()}°F out, above your ${cfg.maxTempF.roundToInt()}°F max")
            return Recommendation(
                Action.RUN_AC,
                "Turn on the AC",
                "Outdoor air is warmer than your target, so opening up won't cool the house.",
                reasons,
            )
        }

        // 2) Comfortable temperature, but something keeps the windows shut.
        if (windowsBlocked) {
            return if (muggy) Recommendation(
                Action.RUN_AC,
                "Keep windows shut — run AC to dehumidify",
                "It isn't hot, but the air is muggy; AC will dry it out and stay comfortable.",
                reasons,
            ) else Recommendation(
                Action.KEEP_CLOSED,
                "Keep windows closed",
                "Indoor temperature is fine — just don't open up right now.",
                reasons,
            )
        }

        // 3) Cooler than your target, clean and dry — free cooling.
        if (coolEnough) {
            reasons.add(0, "it's ${temp.roundToInt()}°F out, below your ${cfg.maxTempF.roundToInt()}°F max")
            c.dewPointF?.let { reasons.add("dew point ${it.roundToInt()}°F is comfortable") }
            c.aqi?.let { reasons.add("AQI $it (${aqiCategory(it)})") }
            return Recommendation(
                Action.OPEN_WINDOWS,
                "Open your windows",
                "Cooler, comfortable, clean air outside — let it cool the house for free.",
                reasons,
            )
        }

        // 4) Near the setpoint with no blockers — either choice is fine.
        return Recommendation(
            Action.COMFORTABLE,
            "You're comfortable — windows optional",
            "It's ${temp.roundToInt()}°F out, near your ${cfg.maxTempF.roundToInt()}°F target; " +
                "open up if you'd like fresh air.",
            reasons.ifEmpty { listOf("conditions are within your comfort band") },
        )
    }
}
