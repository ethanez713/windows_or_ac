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
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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

    private const val MM_PER_INCH = 25.4

    /**
     * Progressive fetch for the widget. Kicks off every current-reading source plus
     * the hourly-forecast feeds in parallel and invokes [onUpdate] each time a fresh
     * arrival improves the picture — but only after [MIN_SOURCES] sources have
     * answered. So the widget paints the instant the 3rd source lands, redraws on
     * every later source/forecast arrival, and stops waiting [SOURCE_SOFT_DEADLINE_MS]
     * after the 3rd answer. Returns the final assembled result.
     */
    suspend fun fetchProgressive(
        cfg: AppConfig, cache: ResponseCache, onUpdate: suspend (WeatherResult) -> Unit,
    ): WeatherResult = coroutineScope {
        this@WeatherRepository.cache = cache
        val hrs = cfg.widgetForecastHours
        // Current readings (keyless + any keyed hyperlocal sources) + hourly forecast.
        val readingTasks = currentReadingTasks(cfg)
        val omH = async(Dispatchers.IO) { fetchOpenMeteoHourly(cfg, hrs) }
        val nwsH = async(Dispatchers.IO) { fetchNwsHourly(cfg, hrs) }
        val metnoH = async(Dispatchers.IO) { fetchMetnoHourly(cfg, hrs) }
        val ecmwfH = async(Dispatchers.IO) { fetchOpenMeteoEcmwfRain(cfg, "forecast_hours=$hrs") }
        val tioH = cfg.secrets.tomorrowIo?.let { k -> async(Dispatchers.IO) { fetchTomorrowIoHourly(cfg, k) } }
        driveProgressive(
            readingTasks, listOfNotNull(omH, nwsH, metnoH, ecmwfH, tioH),
            assemble = { readings, feeds ->
                WeatherResult(
                    aggregate(readings, cfg.sourceWeights, readingTasks.size),
                    ForecastBuilder.build(
                        hourlyOf(feeds, omH), hourlyOf(feeds, nwsH), cfg.comfort,
                        extraHourly = listOfNotNull(
                            hourlyOf(feeds, metnoH), hourlyOf(feeds, ecmwfH),
                            tioH?.let { hourlyOf(feeds, it) },
                        ),
                    ),
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
     * The full-app fetch: current consensus + a multi-day hourly forecast (default 14
     * days) + home/device state. Same 3-source / 5-second policy as [fetchProgressive],
     * emitting an [AppState] on each fresh arrival so the app's source counter and
     * graphs fill in live.
     */
    suspend fun fetchAppStateProgressive(
        cfg: AppConfig,
        cache: ResponseCache,
        days: Int = cfg.comfort.appForecastDays,
        deviceSource: DeviceSource = NoopDeviceSource,
        onUpdate: suspend (AppState) -> Unit,
    ): AppState = coroutineScope {
        this@WeatherRepository.cache = cache
        val readingTasks = currentReadingTasks(cfg)
        val omH = async(Dispatchers.IO) { fetchOpenMeteoHourlyDays(cfg, days) }
        // NWS hourly tops out ~6.5 days and met.no's hourly rain ~2.5; ask each for what
        // it has, and the days past it fall back to Open-Meteo alone (ForecastBuilder
        // marks the missing temperature cross-check "degraded").
        val nwsH = async(Dispatchers.IO) { fetchNwsHourly(cfg, days * 24) }
        val metnoH = async(Dispatchers.IO) { fetchMetnoHourly(cfg, days * 24) }
        val ecmwfH = async(Dispatchers.IO) { fetchOpenMeteoEcmwfRain(cfg, "forecast_days=$days") }
        val tioH = cfg.secrets.tomorrowIo?.let { k -> async(Dispatchers.IO) { fetchTomorrowIoHourly(cfg, k) } }
        val home = async(Dispatchers.IO) { deviceSource.read(cfg) }
        // The near-term 15-minute series for the app's "now → end of day" view. One more
        // HTTP round trip per refresh, but it's just another feed in the progressive
        // engine: a slow or failed fetch degrades to nearTerm = null in assemble() below
        // rather than delaying the screen past SOURCE_SOFT_DEADLINE_MS.
        val min15 = async(Dispatchers.IO) { fetchOpenMeteoMinutely15(cfg, 96) }
        driveProgressive(
            readingTasks, listOfNotNull(omH, nwsH, metnoH, ecmwfH, tioH, home, min15),
            assemble = { readings, feeds ->
                val min15Rows = hourlyOf(feeds, min15)
                AppState(
                    aggregate(readings, cfg.sourceWeights, readingTasks.size),
                    ForecastBuilder.build(
                        hourlyOf(feeds, omH), hourlyOf(feeds, nwsH), cfg.comfort,
                        extraHourly = listOfNotNull(
                            hourlyOf(feeds, metnoH), hourlyOf(feeds, ecmwfH),
                            tioH?.let { hourlyOf(feeds, it) },
                        ),
                    ),
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

    // HTTP helpers: HttpURLConnection + org.json, both built into Android.

    class HttpStatusException(val code: Int) : RuntimeException("HTTP $code")

    private fun httpGet(urlStr: String, userAgent: String, timeoutMs: Int = 15000): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        try {
            if (conn.responseCode !in 200..299) throw HttpStatusException(conn.responseCode)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Set by each fetch entry point; every request below goes through it. */
    @Volatile private var cache: ResponseCache? = null

    private fun cachedGet(urlStr: String, userAgent: String, maxAgeMs: Long): String =
        checkNotNull(cache) { "fetch entry point didn't set the response cache" }
            .get(urlStr, maxAgeMs) { httpGet(urlStr, userAgent) }

    private fun getJson(urlStr: String, userAgent: String, maxAgeMs: Long = ResponseCache.FRESH_MS): JSONObject =
        JSONObject(cachedGet(urlStr, userAgent, maxAgeMs))

    private fun getJsonArray(urlStr: String, userAgent: String, maxAgeMs: Long = ResponseCache.FRESH_MS): JSONArray =
        JSONArray(cachedGet(urlStr, userAgent, maxAgeMs))

    private fun JSONObject.dblOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun JSONArray.dblAt(i: Int): Double? =
        if (i < length() && !isNull(i)) optDouble(i) else null

    private fun fetchOpenMeteo(cfg: AppConfig): SourceReading = try {
        val base = "https://api.open-meteo.com/v1/forecast?latitude=${cfg.lat}&longitude=${cfg.lon}" +
            "&current=temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature," +
            "precipitation,weather_code,wind_speed_10m" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=mm" +
            "&timezone=${cfg.timezoneParam}"
        val cur = getJson(base, cfg.userAgent).getJSONObject("current")
        var aqi: Int? = null
        try {
            val aqUrl = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                "?latitude=${cfg.lat}&longitude=${cfg.lon}&current=us_aqi&timezone=${cfg.timezoneParam}"
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
        val pts = getJson("https://api.weather.gov/points/${cfg.lat},${cfg.lon}", cfg.userAgent, ResponseCache.PLACE_MS)
        val stationsUrl = pts.getJSONObject("properties").getString("observationStations")
        val stations = getJson(stationsUrl, cfg.userAgent, ResponseCache.PLACE_MS).getJSONArray("features")
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

    /** Tomorrow.io realtime: hyperlocal model temp/dew/humidity/wind (+ EPA AQI if present). */
    private fun fetchTomorrowIo(cfg: AppConfig, key: String): SourceReading = try {
        val url = "https://api.tomorrow.io/v4/weather/realtime?location=${cfg.lat},${cfg.lon}" +
            "&units=imperial&apikey=$key"
        val v = getJson(url, cfg.userAgent, maxAgeMs = ResponseCache.TOMORROW_REALTIME_MS)
            .getJSONObject("data").getJSONObject("values")
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

    // Hourly forecast providers return rows keyed by local ISO time.

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
            "precipitation,weather_code,cloud_cover,uv_index,shortwave_radiation,wind_speed_10m,is_day&$forecastRange" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" +
            "&timezone=${cfg.timezoneParam}"
        val h = getJson(url, cfg.userAgent).getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val temp = h.getJSONArray("temperature_2m")
        val dew = h.getJSONArray("dew_point_2m")
        val pop = h.optJSONArray("precipitation_probability")
        val precip = h.optJSONArray("precipitation")
        val wcode = h.optJSONArray("weather_code")
        val isDay = h.optJSONArray("is_day")
        val sw = h.optJSONArray("shortwave_radiation")
        val cloud = h.optJSONArray("cloud_cover")
        val uv = h.optJSONArray("uv_index")

        // AQI from the separate air-quality endpoint, merged by timestamp.
        val aqiByTime = HashMap<String, Int>()
        try {
            val aqUrl = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                "?latitude=${cfg.lat}&longitude=${cfg.lon}&hourly=us_aqi&$aqiRange" +
                "&timezone=${cfg.timezoneParam}"
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
                "cloudPct" to cloud?.dblAt(i), "uvIndex" to uv?.dblAt(i),
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
            "weather_code,is_day,shortwave_radiation,cloud_cover,uv_index&forecast_minutely_15=$steps" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" +
            "&timezone=${cfg.timezoneParam}"
        val m = getJson(url, cfg.userAgent).getJSONObject("minutely_15")
        val times = m.getJSONArray("time")
        val temp = m.getJSONArray("temperature_2m")
        val dew = m.getJSONArray("dew_point_2m")
        val pop = m.optJSONArray("precipitation_probability")
        val precip = m.optJSONArray("precipitation")
        val wcode = m.optJSONArray("weather_code")
        val isDay = m.optJSONArray("is_day")
        val sw = m.optJSONArray("shortwave_radiation")
        val cloud = m.optJSONArray("cloud_cover")
        val uv = m.optJSONArray("uv_index")

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
                "&timezone=${cfg.timezoneParam}"
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
                "cloudPct" to cloud?.dblAt(i), "uvIndex" to uv?.dblAt(i),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun fetchNwsHourly(cfg: AppConfig, hours: Int): List<Map<String, Any?>> = try {
        val pts = getJson("https://api.weather.gov/points/${cfg.lat},${cfg.lon}", cfg.userAgent, ResponseCache.PLACE_MS)
        val hourlyUrl = pts.getJSONObject("properties").getString("forecastHourly")
        val periods = getJson(hourlyUrl, cfg.userAgent).getJSONObject("properties").getJSONArray("periods")
        (0 until minOf(hours, periods.length())).mapNotNull { i ->
            val p = periods.getJSONObject(i)
            // startTime carries the LOCATION's offset; dropping it would label the row in
            // the location's clock instead of the phone's.
            val t = offsetHourKey(p.optString("startTime"), cfg.zone()) ?: return@mapNotNull null
            var temp = p.dblOrNull("temperature")
            if (temp != null && p.optString("temperatureUnit") == "C") temp = cToF(temp)
            val dewC = p.optJSONObject("dewpoint")?.let { if (it.isNull("value")) null else it.optDouble("value") }
            val pop = p.optJSONObject("probabilityOfPrecipitation")
                ?.let { if (it.isNull("value")) null else it.optDouble("value") }
            mapOf(
                "time" to t,
                "tempF" to temp, "dewPointF" to cToF(dewC), "precipProb" to pop,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * A second Open-Meteo hourly call pinned to ECMWF, purely as an extra rain vote.
     * Open-Meteo derives precipitation_probability from the GFS ensemble for every US
     * model it serves — best_match, ncep_hrrr_conus and gfs_seamless return byte-identical
     * probabilities — so naming ECMWF is the only way to get a second opinion out of this
     * API, and it tracks NWS closely on hours the default model calls dry. Rain fields
     * only: temperature, dew point and AQI already come from the best_match call, and
     * asking for them again would just be a bigger payload.
     */
    private fun fetchOpenMeteoEcmwfRain(cfg: AppConfig, forecastRange: String): List<Map<String, Any?>> = try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${cfg.lat}&longitude=${cfg.lon}" +
            "&hourly=precipitation_probability,precipitation&$forecastRange" +
            "&models=ecmwf_ifs025&precipitation_unit=inch&timezone=${cfg.timezoneParam}"
        val h = getJson(url, cfg.userAgent).getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val pop = h.optJSONArray("precipitation_probability")
        val precip = h.optJSONArray("precipitation")
        (0 until times.length()).map { i ->
            mapOf<String, Any?>(
                "time" to times.getString(i),
                "precipProb" to pop?.dblAt(i), "precipIn" to precip?.dblAt(i),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * met.no's hourly precipitation AMOUNT, as a second vote on precipIn (Open-Meteo
     * regularly reports 0.00" for hours met.no puts real millimetres in). Amount only:
     * `probability_of_precipitation` is a Nordic-area field and comes back null
     * everywhere else, so there is nothing here to vote on the chance with.
     *
     * Two shape differences from the Open-Meteo rows this merges into, both handled
     * here so ForecastBuilder sees one format: times are UTC and have to be keyed with
     * [utcHourKey], and amounts are millimetres. Rows past
     * met.no's ~2.5-day hourly horizon carry only a `next_6_hours` block; those are
     * skipped rather than smeared across six hours it never forecast.
     */
    /**
     * The key the hourly feeds are joined on: the hour containing [instant], as wall-clock
     * time in [zone]. Truncated to the hour because Open-Meteo labels its rows on whole
     * local hours even in a half-hour-offset zone (+5:30 gives "02:00", not "02:30"), so an
     * exact conversion of another source's UTC hour would land between its rows.
     */
    internal fun hourKey(instant: Instant, zone: ZoneId): String =
        instant.atZone(zone).truncatedTo(ChronoUnit.HOURS).format(HOUR_KEY)

    /** [hourKey] of a UTC timestamp ("2026-08-02T14:00:00Z"), or null if unparseable. */
    internal fun utcHourKey(utcIso: String, zone: ZoneId): String? =
        runCatching { hourKey(Instant.parse(utcIso), zone) }.getOrNull()

    /** [hourKey] of a timestamp carrying its own offset ("2026-08-02T14:00:00-04:00"), as NWS sends. */
    internal fun offsetHourKey(iso: String, zone: ZoneId): String? =
        runCatching { hourKey(OffsetDateTime.parse(iso).toInstant(), zone) }.getOrNull()

    private val HOUR_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    private fun fetchMetnoHourly(cfg: AppConfig, hours: Int): List<Map<String, Any?>> = try {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${cfg.lat}&lon=${cfg.lon}"
        val ts = getJson(url, cfg.userAgent).getJSONObject("properties").getJSONArray("timeseries")
        (0 until minOf(hours, ts.length())).mapNotNull { i ->
            val e = ts.getJSONObject(i)
            val mm = e.optJSONObject("data")?.optJSONObject("next_1_hours")
                ?.optJSONObject("details")?.dblOrNull("precipitation_amount")
                ?: return@mapNotNull null
            val t = utcHourKey(e.optString("time"), cfg.zone()) ?: return@mapNotNull null
            mapOf<String, Any?>("time" to t, "precipIn" to mm / MM_PER_INCH)
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Tomorrow.io's hourly rain, as another vote in the forecast consensus.
     *
     * It forecasts for a POINT rather than an area, so under scattered convection it
     * reads well below NWS's areal PoP (which is confidence x areal coverage — the
     * chance of rain ANYWHERE in the zone). That is a difference in the question being
     * answered, not an error: "will rain reach this house" is the one that decides
     * whether the windows can stay open, so it votes like any other source.
     *
     * It is also the only source here that publishes a per-hour thunderstorm probability,
     * which is what the storm treatment in the forecast surfaces keys off; without a key
     * for it, ForecastBuilder falls back to the WMO code.
     *
     * Amount comes from `rainIntensity` (in/hr over a 1-hour step) rather than
     * `rainAccumulation`, which is unreliable here — it reports 0 for hours whose own
     * intensity is the highest of the day.
     *
     * Both Tomorrow.io calls are held in [ResponseCache] longer than the other sources, to
     * stay inside the free tier; when it has nothing usable this returns empty and simply
     * doesn't vote.
     */
    private fun fetchTomorrowIoHourly(cfg: AppConfig, key: String): List<Map<String, Any?>> = try {
        val url = "https://api.tomorrow.io/v4/weather/forecast?location=${cfg.lat},${cfg.lon}" +
            "&timesteps=1h&units=imperial&apikey=$key"
        val hourly = getJson(url, cfg.userAgent, maxAgeMs = ResponseCache.TOMORROW_HOURLY_MS)
            .getJSONObject("timelines").getJSONArray("hourly")
        (0 until hourly.length()).mapNotNull { i ->
            val row = hourly.getJSONObject(i)
            val t = utcHourKey(row.optString("time"), cfg.zone()) ?: return@mapNotNull null
            val v = row.optJSONObject("values") ?: return@mapNotNull null
            mapOf<String, Any?>(
                "time" to t,
                "precipProb" to v.dblOrNull("precipitationProbability"),
                "precipIn" to v.dblOrNull("rainIntensity"),
                "thunderProb" to v.dblOrNull("thunderstormProbability"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun cToF(c: Double?): Double? = c?.let { it * 9 / 5 + 32 }

    /** Magnus-formula dew point (°C) from temperature (°C) + RH (%). */
    private fun dewPointC(tempC: Double?, rh: Double?): Double? {
        if (tempC == null || rh == null || rh <= 0) return null
        val a = 17.625; val b = 243.04
        val gamma = ln(rh / 100.0) + a * tempC / (b + tempC)
        return b * gamma / (a - gamma)
    }

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
