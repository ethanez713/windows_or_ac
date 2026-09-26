package com.acwindows

/**
 * The recommendation engine — a faithful port of the Python backend's
 * decision.py. Pure (no I/O), so it's the natural seam for future smart-home
 * control: today it returns an [Action]; later a controller maps actions to
 * devices (thermostat, attic fan, window openers) without changing this logic.
 *
 * Opening windows is worth it when outdoor air is inside the comfort band AND
 * clean AND dry. Dew point (not relative humidity) is the honest mugginess gauge.
 * Below the cold floor the call is keep closed: an open window cools toward the
 * outdoor air and vents solar gain, so it does not warm the room.
 */
object Decision {

    private const val COLD_SUMMARY =
        "Outdoor air is too cold to bring in. Opening would cool the house toward that air and let solar heat out; it won't warm the room."

    fun aqiCategory(aqi: Int?): String = when {
        aqi == null -> "unknown"
        aqi <= 50 -> "good"
        aqi <= 100 -> "moderate"
        aqi <= 150 -> "unhealthy for sensitive groups"
        aqi <= 200 -> "unhealthy"
        aqi <= 300 -> "very unhealthy"
        else -> "hazardous"
    }

    fun decide(c: Consensus, cfg: Comfort, unit: TempUnit = TempUnit.F): Recommendation {
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
        val tooCold = temp <= cfg.tempMinF
        val coolEnough = temp <= cfg.tempIdealF

        if (aqiBad) reasons.add(
            "AQI ${c.aqi} (${aqiCategory(c.aqi)}) is above your limit of ${cfg.aqiMax} " +
                "— outdoor air is unhealthy to let in"
        )
        if (raining) reasons.add("precipitation right now — opening windows risks letting rain in")
        if (muggy) reasons.add(
            "dew point ${unit.degUnit(c.dewPointF!!)} is muggy " +
                "(above ${unit.degUnit(cfg.dewPointMaxF)}) — fresh air would feel clammy"
        )

        val windowsBlocked = aqiBad || raining || muggy

        // 1) Too warm to coast — ventilation can't cool below outdoor temp.
        if (tooWarm) {
            reasons.add(0, "it's ${unit.degUnit(temp)} out, above your ${unit.degUnit(cfg.maxTempF)} max")
            return Recommendation(
                Action.RUN_AC,
                "Turn on the AC",
                "Outdoor air is warmer than your target, so opening up won't cool the house.",
                reasons,
            )
        }

        // 2) Too cold to leave open. Not AC, and not "open to warm": greenhouse
        //    gain stays indoors only while the glass is shut.
        if (tooCold) {
            reasons.add(0, "it's ${unit.degUnit(temp)} out, at or below your ${unit.degUnit(cfg.tempMinF)} cold limit")
            return Recommendation(Action.KEEP_CLOSED, "Keep windows closed", COLD_SUMMARY, reasons)
        }

        // 3) Comfortable temperature, but something keeps the windows shut.
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

        // 4) In the open band, clean and dry — free cooling. Inside the cold
        //    ramp, open only while the score still clears the threshold, so the
        //    yes/no call and the graph don't disagree.
        if (coolEnough && (temp >= cfg.tempMinIdealF || scoreClears(c, cfg))) {
            reasons.add(0, "it's ${unit.degUnit(temp)} out, below your ${unit.degUnit(cfg.maxTempF)} max")
            c.dewPointF?.let { reasons.add("dew point ${unit.degUnit(it)} is comfortable") }
            c.aqi?.let { reasons.add("AQI $it (${aqiCategory(it)})") }
            return Recommendation(
                Action.OPEN_WINDOWS,
                "Open your windows",
                "Cooler, comfortable, clean air outside — let it cool the house for free.",
                reasons,
            )
        }

        if (coolEnough) {
            reasons.add(0, "it's ${unit.degUnit(temp)} out, below your ${unit.degUnit(cfg.tempMinIdealF)} open-down-to")
            return Recommendation(Action.KEEP_CLOSED, "Keep windows closed", COLD_SUMMARY, reasons)
        }

        // 5) Near the cooling setpoint with no blockers — either choice is fine.
        return Recommendation(
            Action.COMFORTABLE,
            "You're comfortable — windows optional",
            "It's ${unit.degUnit(temp)} out, near your ${unit.degUnit(cfg.maxTempF)} target; " +
                "open up if you'd like fresh air.",
            reasons.ifEmpty { listOf("conditions are within your comfort band") },
        )
    }

    private fun scoreClears(c: Consensus, cfg: Comfort): Boolean =
        Score.windowScore(cfg, c.tempF, c.dewPointF, c.aqi, precipMm = c.precipMm) >= cfg.openScoreMin
}
