package com.acwindows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Fetches the weather sources in parallel and merges them — a port of the Python
 * backend's providers/ + aggregate.py + forecast.py. Each fetch swallows its own
 * errors and returns ok=false, so one dead source can't take down the others.
 *
 * Sources (all free, keyless, simple HTTP GETs, all with forecasting):
 *   open-meteo — model; supplies AQI + the hourly forecast that drives the score
 *   nws        — US measured obs + hourly forecast (temperature cross-check)
 *   metno      — Norwegian MET model; independent global cross-check
 *
 * Optional keyed hyperlocal sources (added to the now-consensus only when the user
 * pastes a key in settings — see [SecretsStore]):
 *   tomorrowio    — Tomorrow.io hyperlocal model (temp/dew + AQI when available)
 *   pirateweather — HRRR-based hyperlocal model (temp/dew)
 *   airnow        — EPA measured AQI from the nearest monitor (authoritative AQI)
 */
object WeatherRepository {

    private const val TOLERANCE_F = 8.0

    /** Don't render until at least this many sources have answered (ok or failed). */
    private const val MIN_SOURCES = 3

    /**
     * Once [MIN_SOURCES] have answered, wait at most this long for the stragglers
     * before drawing with whatever's in. Until 3 have answered we keep waiting
     * (bounded only by each fetch's own HTTP timeout).
     */
    private const val SOURCE_SOFT_DEADLINE_MS = 5_000L

    /** One-shot fetch: returns the final result with no progressive callbacks. */
    suspend fun fetch(cfg: AppConfig): WeatherResult = fetchProgressive(cfg) {}

    /**
     * Progressive fetch for the widget. Kicks off every current-reading source plus
     * the hourly-forecast feeds in parallel and invokes [onUpdate] each time a fresh
     * arrival improves the picture — but only after [MIN_SOURCES] sources have
     * answered. So the widget paints the instant the 3rd source lands, redraws on
     * every later source/forecast arrival, and stops waiting [SOURCE_SOFT_DEADLINE_MS]
     * after the 3rd answer. Returns the final assembled result.
     */
    suspend fun fetchProgressive(
        cfg: AppConfig, onUpdate: suspend (WeatherResult) -> Unit,
    ): WeatherResult = coroutineScope {
        val hrs = cfg.widgetForecastHours
        // Current readings (keyless + any keyed hyperlocal sources) + hourly forecast.
        val readingTasks = currentReadingTasks(cfg)
        val omH = async(Dispatchers.IO) { fetchOpenMeteoHourly(cfg, hrs) }
        val nwsH = async(Dispatchers.IO) { fetchNwsHourly(cfg, hrs) }
        driveProgressive(
            readingTasks, listOf(omH, nwsH),
            assemble = { readings, feeds ->
                WeatherResult(
                    aggregate(readings, cfg.sourceWeights, readingTasks.size),
                    ForecastBuilder.build(hourlyOf(feeds, omH), hourlyOf(feeds, nwsH), cfg.comfort),
                )
            },
            onUpdate = onUpdate,
        )
    }

    /**
     * The three keyless sources plus any keyed hyperlocal sources the user configured,
     * each kicked off in parallel. A keyed source is only attempted when its key is set.
     */
    private fun CoroutineScope.currentReadingTasks(cfg: AppConfig): List<Deferred<SourceReading>> {
        val tasks = mutableListOf(
            async(Dispatchers.IO) { fetchOpenMeteo(cfg) },
            async(Dispatchers.IO) { fetchNws(cfg) },
            async(Dispatchers.IO) { fetchMetno(cfg) },
        )
        cfg.secrets.tomorrowIo?.let { k -> tasks += async(Dispatchers.IO) { fetchTomorrowIo(cfg, k) } }
        cfg.secrets.pirateWeather?.let { k -> tasks += async(Dispatchers.IO) { fetchPirateWeather(cfg, k) } }
        cfg.secrets.airNow?.let { k -> tasks += async(Dispatchers.IO) { fetchAirNow(cfg, k) } }
        return tasks
    }

    /**
     * The full-app fetch: current consensus + a multi-day hourly forecast (default
     * 14 days) + home/device state. Same keyless sources as [fetch]; the device
     * source is the no-op MVP stub. Used by the launcher app's Now / 14-Day tabs.
     */
    suspend fun fetchAppState(
        cfg: AppConfig,
        days: Int = cfg.comfort.appForecastDays,
        deviceSource: DeviceSource = NoopDeviceSource,
    ): AppState = fetchAppStateProgressive(cfg, days, deviceSource) {}

    /**
     * Progressive variant of [fetchAppState] for the launcher app: same 3-source /
     * 5-second policy as [fetchProgressive], emitting an [AppState] on each fresh
     * arrival so the app's source counter and graphs fill in live.
     */
    suspend fun fetchAppStateProgressive(
        cfg: AppConfig,
        days: Int = cfg.comfort.appForecastDays,
        deviceSource: DeviceSource = NoopDeviceSource,
        onUpdate: suspend (AppState) -> Unit,
    ): AppState = coroutineScope {
        val readingTasks = currentReadingTasks(cfg)
        val omH = async(Dispatchers.IO) { fetchOpenMeteoHourlyDays(cfg, days) }
        // NWS hourly tops out ~6.5 days; ask for what it has, ForecastBuilder marks
        // the rest "degraded". Open-Meteo alone still drives the score.
        val nwsH = async(Dispatchers.IO) { fetchNwsHourly(cfg, days * 24) }
        val home = async(Dispatchers.IO) { deviceSource.read(cfg) }
        // The near-term 15-minute series for the app's "now → end of day" view. One more
        // HTTP round trip per refresh, but it's just another feed in the progressive
        // engine: a slow or failed fetch degrades to nearTerm = null in assemble() below
        // rather than delaying the screen past SOURCE_SOFT_DEADLINE_MS.
        val min15 = async(Dispatchers.IO) { fetchOpenMeteoMinutely15(cfg, 96) }
        driveProgressive(
            readingTasks, listOf(omH, nwsH, home, min15),
            assemble = { readings, feeds ->
                val min15Rows = hourlyOf(feeds, min15)
                AppState(
                    aggregate(readings, cfg.sourceWeights, readingTasks.size),
                    ForecastBuilder.build(hourlyOf(feeds, omH), hourlyOf(feeds, nwsH), cfg.comfort),
                    feeds[home] as? HomeState ?: HomeState(),
                    readings.toList(),
                    nearTerm = if (min15Rows.isEmpty()) null else
                        ForecastBuilder.build(min15Rows, emptyList(), cfg.comfort, stepMinutes = 15),
                )
            },
            onUpdate = onUpdate,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun hourlyOf(feeds: Map<Deferred<*>, Any?>, key: Deferred<*>): List<Map<String, Any?>> =
        feeds[key] as? List<Map<String, Any?>> ?: emptyList()

    /**
     * The shared progressive engine. Awaits [readingTasks] and [feeds] as they finish,
     * accumulating each into [readings] / a per-feed value map, and calls [onUpdate]
     * with a freshly [assemble]d result on every arrival once [MIN_SOURCES] sources
     * have answered. Caps the wait at [SOURCE_SOFT_DEADLINE_MS] (measured from start)
     * once that floor is met, then cancels any stragglers and returns the final result.
     */
    private suspend fun <R> driveProgressive(
        readingTasks: List<Deferred<SourceReading>>,
        feeds: List<Deferred<*>>,
        assemble: (readings: List<SourceReading>, feeds: Map<Deferred<*>, Any?>) -> R,
        onUpdate: suspend (R) -> Unit,
    ): R {
        val readings = ArrayList<SourceReading>(readingTasks.size)
        val feedValues = HashMap<Deferred<*>, Any?>()
        val readingSet = readingTasks.toHashSet()
        val pending = HashSet<Deferred<*>>(readingTasks.size + feeds.size).apply {
            addAll(readingTasks); addAll(feeds)
        }
        val startNs = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startNs) / 1_000_000

        while (pending.isNotEmpty()) {
            val haveEnough = readings.size >= MIN_SOURCES
            if (haveEnough && elapsedMs() >= SOURCE_SOFT_DEADLINE_MS) break
            // Wait indefinitely for the 3rd answer; once we have it, only for the deadline remainder.
            val waitMs = if (haveEnough) SOURCE_SOFT_DEADLINE_MS - elapsedMs() else null
            val done = awaitNext(pending, waitMs) { d, v ->
                if (d in readingSet) readings.add(v as SourceReading) else feedValues[d] = v
            } ?: break   // deadline hit while waiting on stragglers
            pending.remove(done)
            if (readings.size >= MIN_SOURCES) onUpdate(assemble(readings, feedValues))
        }
        pending.forEach { it.cancel() }
        return assemble(readings, feedValues)
    }

    /**
     * Suspends until the next deferred in [pending] completes, hands its (deferred,
     * value) to [capture], and returns that deferred. With [waitMs] non-null it gives
     * up after that many ms (returning null); null means wait without a timeout.
     */
    private suspend fun awaitNext(
        pending: Set<Deferred<*>>,
        waitMs: Long?,
        capture: (Deferred<*>, Any?) -> Unit,
    ): Deferred<*>? {
        suspend fun pick(): Deferred<*> = select {
            pending.forEach { d -> d.onAwait { v -> capture(d, v); d } }
        }
        return when {
            waitMs == null -> pick()
            waitMs <= 0L -> null
            else -> withTimeoutOrNull(waitMs) { pick() }
        }
    }

    // ---- HTTP helpers (HttpURLConnection + org.json, both built into Android) ----

    private fun httpGet(urlStr: String, userAgent: String, timeoutMs: Int = 15000): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        try {
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(urlStr: String, userAgent: String, timeoutMs: Int = 15000): JSONObject =
        JSONObject(httpGet(urlStr, userAgent, timeoutMs))

    private fun getJsonArray(urlStr: String, userAgent: String, timeoutMs: Int = 15000): JSONArray =
        JSONArray(httpGet(urlStr, userAgent, timeoutMs))

    private fun JSONObject.dblOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun JSONArray.dblAt(i: Int): Double? =
        if (i < length() && !isNull(i)) optDouble(i) else null

    // ---- Current-reading providers ----

    private fun fetchOpenMeteo(cfg: AppConfig): SourceReading = try {
        val base = "https://api.open-meteo.com/v1/forecast?latitude=${cfg.lat}&longitude=${cfg.lon}" +
            "&current=temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature," +
            "precipitation,weather_code,wind_speed_10m" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=mm" +
            "&timezone=${cfg.timezone}"
        val cur = getJson(base, cfg.userAgent).getJSONObject("current")
        var aqi: Int? = null
        try {
            val aqUrl = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                "?latitude=${cfg.lat}&longitude=${cfg.lon}&current=us_aqi&timezone=${cfg.timezone}"
            getJson(aqUrl, cfg.userAgent).getJSONObject("current").dblOrNull("us_aqi")?.let {
                aqi = it.roundToInt()
            }
        } catch (_: Exception) { /* AQI optional */ }
        SourceReading(
            source = "open-meteo",
            tempF = cur.dblOrNull("temperature_2m"),
            humidityPct = cur.dblOrNull("relative_humidity_2m"),
            dewPointF = cur.dblOrNull("dew_point_2m"),
            apparentTempF = cur.dblOrNull("apparent_temperature"),
            precipMm = cur.dblOrNull("precipitation"),
            windMph = cur.dblOrNull("wind_speed_10m"),
            aqi = aqi,
        )
    } catch (e: Exception) {
        SourceReading(source = "open-meteo", ok = false, error = e.message)
    }

    private fun fetchNws(cfg: AppConfig): SourceReading = try {
        val pts = getJson("https://api.weather.gov/points/${cfg.lat},${cfg.lon}", cfg.userAgent)
        val stationsUrl = pts.getJSONObject("properties").getString("observationStations")
        val stations = getJson(stationsUrl, cfg.userAgent).getJSONArray("features")
        if (stations.length() == 0) throw RuntimeException("no NWS stations near location")
        val stationId = stations.getJSONObject(0).getJSONObject("properties").getString("stationIdentifier")
        val obs = getJson(
            "https://api.weather.gov/stations/$stationId/observations/latest", cfg.userAgent,
        ).getJSONObject("properties")
        fun valOf(key: String): Double? =
            obs.optJSONObject(key)?.let { if (it.isNull("value")) null else it.optDouble("value") }
        SourceReading(
            source = "nws",
            tempF = valOf("temperature")?.let { it * 9 / 5 + 32 },
            dewPointF = valOf("dewpoint")?.let { it * 9 / 5 + 32 },
            humidityPct = valOf("relativeHumidity"),
            windMph = valOf("windSpeed")?.let { it * 0.621371 },
        )
    } catch (e: Exception) {
        SourceReading(source = "nws", ok = false, error = e.message)
    }

    private fun fetchMetno(cfg: AppConfig): SourceReading = try {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${cfg.lat}&lon=${cfg.lon}"
        val ts = getJson(url, cfg.userAgent).getJSONObject("properties").getJSONArray("timeseries")
        if (ts.length() == 0) throw RuntimeException("met.no empty timeseries")
        val inst = ts.getJSONObject(0).getJSONObject("data").getJSONObject("instant")
            .getJSONObject("details")
        val tempC = inst.dblOrNull("air_temperature")
        val rh = inst.dblOrNull("relative_humidity")
        SourceReading(
            source = "metno",
            tempF = cToF(tempC),
            humidityPct = rh,
            dewPointF = cToF(dewPointC(tempC, rh)),
            windMph = inst.dblOrNull("wind_speed")?.let { it * 2.236936 },
        )
    } catch (e: Exception) {
        SourceReading(source = "metno", ok = false, error = e.message)
    }

    // ---- Optional keyed hyperlocal providers (current reading only) ----

    /** Tomorrow.io realtime: hyperlocal model temp/dew/humidity/wind (+ EPA AQI if present). */
    private fun fetchTomorrowIo(cfg: AppConfig, key: String): SourceReading = try {
        val url = "https://api.tomorrow.io/v4/weather/realtime?location=${cfg.lat},${cfg.lon}" +
            "&units=imperial&apikey=$key"
        val v = getJson(url, cfg.userAgent).getJSONObject("data").getJSONObject("values")
        SourceReading(
            source = "tomorrowio",
            tempF = v.dblOrNull("temperature"),
            dewPointF = v.dblOrNull("dewPoint"),
            humidityPct = v.dblOrNull("humidity"),
            windMph = v.dblOrNull("windSpeed"),
            aqi = if (v.has("epaIndex") && !v.isNull("epaIndex")) v.optInt("epaIndex") else null,
        )
    } catch (e: Exception) {
        SourceReading(source = "tomorrowio", ok = false, error = e.message)
    }

    /** Pirate Weather (HRRR-based, hyperlocal): current temp/dew/humidity/wind. */
    private fun fetchPirateWeather(cfg: AppConfig, key: String): SourceReading = try {
        val url = "https://api.pirateweather.net/forecast/$key/${cfg.lat},${cfg.lon}" +
            "?units=us&exclude=minutely,hourly,daily,alerts"
        val cur = getJson(url, cfg.userAgent).getJSONObject("currently")
        SourceReading(
            source = "pirateweather",
            tempF = cur.dblOrNull("temperature"),
            dewPointF = cur.dblOrNull("dewPoint"),
            humidityPct = cur.dblOrNull("humidity")?.let { it * 100 },   // API returns 0–1
            windMph = cur.dblOrNull("windSpeed"),
        )
    } catch (e: Exception) {
        SourceReading(source = "pirateweather", ok = false, error = e.message)
    }

    /** AirNow: EPA measured AQI from the nearest monitor (overall = max sub-index). */
    private fun fetchAirNow(cfg: AppConfig, key: String): SourceReading = try {
        val url = "https://www.airnowapi.org/aq/observation/latLong/current/?format=application/json" +
            "&latitude=${cfg.lat}&longitude=${cfg.lon}&distance=50&API_KEY=$key"
        val arr = getJsonArray(url, cfg.userAgent)
        var maxAqi: Int? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val a = if (o.isNull("AQI")) null else o.optInt("AQI", -1).takeIf { it >= 0 }
            if (a != null && (maxAqi == null || a > maxAqi)) maxAqi = a
        }
        if (maxAqi == null) throw RuntimeException("AirNow: no AQI in range")
        SourceReading(source = "airnow", aqi = maxAqi)
    } catch (e: Exception) {
        SourceReading(source = "airnow", ok = false, error = e.message)
    }

    // ---- Hourly forecast providers (return rows keyed by local ISO time) ----

    // Widget look-ahead: the next `hours` hours (from now).
    private fun fetchOpenMeteoHourly(cfg: AppConfig, hours: Int): List<Map<String, Any?>> =
        fetchOpenMeteoHourlyRange(cfg, "forecast_hours=$hours", "forecast_hours=$hours")

    // App look-ahead: the next `days` calendar days. AQI only forecasts ~5 days,
    // so cap its request there; the missing hours stay null → fAqi is non-blocking.
    private fun fetchOpenMeteoHourlyDays(cfg: AppConfig, days: Int): List<Map<String, Any?>> =
        fetchOpenMeteoHourlyRange(cfg, "forecast_days=$days", "forecast_days=${minOf(days, 5)}")

    private fun fetchOpenMeteoHourlyRange(
        cfg: AppConfig, forecastRange: String, aqiRange: String,
    ): List<Map<String, Any?>> = try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${cfg.lat}&longitude=${cfg.lon}" +
            "&hourly=temperature_2m,dew_point_2m,relative_humidity_2m,precipitation_probability," +
            "precipitation,weather_code,cloud_cover,shortwave_radiation,wind_speed_10m,is_day&$forecastRange" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" +
            "&timezone=${cfg.timezone}"
        val h = getJson(url, cfg.userAgent).getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val temp = h.getJSONArray("temperature_2m")
        val dew = h.getJSONArray("dew_point_2m")
        val pop = h.optJSONArray("precipitation_probability")
        val precip = h.optJSONArray("precipitation")
        val wcode = h.optJSONArray("weather_code")
        val isDay = h.optJSONArray("is_day")
        val sw = h.optJSONArray("shortwave_radiation")

        // AQI from the separate air-quality endpoint, merged by timestamp.
        val aqiByTime = HashMap<String, Int>()
        try {
            val aqUrl = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                "?latitude=${cfg.lat}&longitude=${cfg.lon}&hourly=us_aqi&$aqiRange" +
                "&timezone=${cfg.timezone}"
            val ah = getJson(aqUrl, cfg.userAgent).getJSONObject("hourly")
            val at = ah.getJSONArray("time"); val av = ah.getJSONArray("us_aqi")
            for (i in 0 until at.length()) av.dblAt(i)?.let { aqiByTime[at.getString(i)] = it.roundToInt() }
        } catch (_: Exception) { /* AQI optional */ }

        (0 until times.length()).map { i ->
            val t = times.getString(i)
            mapOf(
                "time" to t, "tempF" to temp.dblAt(i), "dewPointF" to dew.dblAt(i),
                "precipProb" to pop?.dblAt(i), "precipIn" to precip?.dblAt(i),
                "weatherCode" to wcode?.dblAt(i)?.let { it.roundToInt() },
                "isDay" to isDay?.dblAt(i)?.let { it >= 0.5 },
                "shortwaveWm2" to sw?.dblAt(i), "aqi" to aqiByTime[t],
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * 15-minute-resolution near-term series for [AppState.nearTerm]. Unlike the hourly
     * endpoint (which starts at local midnight), Open-Meteo's minutely_15 block starts at
     * the CURRENT quarter-hour, so `forecast_minutely_15=steps` yields exactly `steps *
     * 15` minutes from now — [steps]=96 is 24h. Same row shape as [fetchOpenMeteoHourlyRange]
     * except AQI, which this endpoint doesn't offer at 15-minute resolution (backfilled
     * below). NOTE: unlike the hourly `precipitation` field (an hourly rate), this
     * endpoint's `precipitation` is the accumulation over just that 15-minute step — a
     * caller summing it into an hourly-equivalent total must scale by [Forecast.stepMinutes],
     * not treat it as already-hourly.
     */
    private fun fetchOpenMeteoMinutely15(cfg: AppConfig, steps: Int): List<Map<String, Any?>> = try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${cfg.lat}&longitude=${cfg.lon}" +
            "&minutely_15=temperature_2m,dew_point_2m,precipitation,precipitation_probability," +
            "weather_code,is_day,shortwave_radiation&forecast_minutely_15=$steps" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" +
            "&timezone=${cfg.timezone}"
        val m = getJson(url, cfg.userAgent).getJSONObject("minutely_15")
        val times = m.getJSONArray("time")
        val temp = m.getJSONArray("temperature_2m")
        val dew = m.getJSONArray("dew_point_2m")
        val pop = m.optJSONArray("precipitation_probability")
        val precip = m.optJSONArray("precipitation")
        val wcode = m.optJSONArray("weather_code")
        val isDay = m.optJSONArray("is_day")
        val sw = m.optJSONArray("shortwave_radiation")

        // AQI is hourly-only. Fetch it hourly and assign each 15-minute row the AQI of its
        // containing hour (timestamp truncated to "yyyy-MM-ddTHH", same keying the rest of
        // this file uses) — AQI moves slowly enough that carrying one hour's value across
        // its four quarter-hour slots is a fine approximation, and there's no finer source.
        val aqiByHour = HashMap<String, Int>()
        try {
            val aqUrl = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                // +2 hours of slack, not just steps/4: the AQI series starts at the top of the
                // current hour while the 15-minute one starts at the current QUARTER hour, so an
                // exactly-sized request runs out before the last few rows and leaves them with a
                // null AQI (which scores as non-blocking, i.e. quietly optimistic).
                "?latitude=${cfg.lat}&longitude=${cfg.lon}&hourly=us_aqi&forecast_hours=${steps / 4 + 2}" +
                "&timezone=${cfg.timezone}"
            val ah = getJson(aqUrl, cfg.userAgent).getJSONObject("hourly")
            val at = ah.getJSONArray("time"); val av = ah.getJSONArray("us_aqi")
            for (i in 0 until at.length()) av.dblAt(i)?.let { aqiByHour[at.getString(i).take(13)] = it.roundToInt() }
        } catch (_: Exception) { /* AQI optional */ }

        (0 until times.length()).map { i ->
            val t = times.getString(i)
            mapOf(
                "time" to t, "tempF" to temp.dblAt(i), "dewPointF" to dew.dblAt(i),
                "precipProb" to pop?.dblAt(i), "precipIn" to precip?.dblAt(i),
                "weatherCode" to wcode?.dblAt(i)?.let { it.roundToInt() },
                "isDay" to isDay?.dblAt(i)?.let { it >= 0.5 },
                "shortwaveWm2" to sw?.dblAt(i), "aqi" to aqiByHour[t.take(13)],
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun fetchNwsHourly(cfg: AppConfig, hours: Int): List<Map<String, Any?>> = try {
        val pts = getJson("https://api.weather.gov/points/${cfg.lat},${cfg.lon}", cfg.userAgent)
        val hourlyUrl = pts.getJSONObject("properties").getString("forecastHourly")
        val periods = getJson(hourlyUrl, cfg.userAgent).getJSONObject("properties").getJSONArray("periods")
        (0 until minOf(hours, periods.length())).map { i ->
            val p = periods.getJSONObject(i)
            var temp = p.dblOrNull("temperature")
            if (temp != null && p.optString("temperatureUnit") == "C") temp = cToF(temp)
            val dewC = p.optJSONObject("dewpoint")?.let { if (it.isNull("value")) null else it.optDouble("value") }
            mapOf(
                "time" to p.optString("startTime").take(16),
                "tempF" to temp, "dewPointF" to cToF(dewC),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    // ---- shared math ----

    private fun cToF(c: Double?): Double? = c?.let { it * 9 / 5 + 32 }

    /** Magnus-formula dew point (°C) from temperature (°C) + RH (%). */
    private fun dewPointC(tempC: Double?, rh: Double?): Double? {
        if (tempC == null || rh == null || rh <= 0) return null
        val a = 17.625; val b = 243.04
        val gamma = ln(rh / 100.0) + a * tempC / (b + tempC)
        return b * gamma / (a - gamma)
    }

    // ---- Aggregation (current consensus) ----

    /**
     * Merges per-source readings into one consensus using a WEIGHTED MEDIAN per field
     * (not a mean): the median is robust — a single off source (e.g. a station obs sitting
     * on sun-baked tarmac) can't drag the result — and per-source weights let the user
     * favour the sources they trust. Weight 0 drops a source from the consensus entirely.
     * The temp spread (max−min) is still reported for the confidence flag.
     */
    private fun aggregate(
        readings: List<SourceReading>, weights: Map<String, Double>, attempted: Int,
    ): Consensus {
        val ok = readings.filter { it.ok }
        val failed = readings.filter { !it.ok }.map { "${it.source}: ${it.error ?: "error"}" }

        fun weightOf(r: SourceReading) = (weights[r.source] ?: 1.0).coerceAtLeast(0.0)
        // Weighted median over the (value, weight) pairs of the sources that supplied a field.
        fun wmed(sel: (SourceReading) -> Double?): Double? {
            val pts = ok.mapNotNull { r -> sel(r)?.let { it to weightOf(r) } }
                .filter { it.second > 0 }
                .sortedBy { it.first }
            if (pts.isEmpty()) return null
            val half = pts.sumOf { it.second } / 2.0
            var cum = 0.0
            for ((v, w) in pts) { cum += w; if (cum >= half) return v }
            return pts.last().first
        }

        // Spread/confidence consider only sources that still carry weight.
        val temps = ok.filter { weightOf(it) > 0 }.mapNotNull { it.tempF }
        val spread = if (temps.size >= 2) temps.max() - temps.min() else 0.0
        val weighted = ok.filter { weightOf(it) > 0 }

        val confidence = when {
            weighted.isEmpty() -> "none"
            weighted.size == 1 -> "degraded"
            spread > TOLERANCE_F -> "conflict"
            else -> "high"
        }

        return Consensus(
            tempF = wmed { it.tempF },
            dewPointF = wmed { it.dewPointF },
            humidityPct = wmed { it.humidityPct },
            apparentTempF = wmed { it.apparentTempF },
            precipMm = wmed { it.precipMm },
            windMph = wmed { it.windMph },
            aqi = wmed { it.aqi?.toDouble() }?.roundToInt(),
            confidence = confidence,
            tempSpreadF = spread,
            sourcesOk = ok.map { it.source },
            sourcesFailed = failed,
            sourcesAttempted = attempted,
        )
    }
}
