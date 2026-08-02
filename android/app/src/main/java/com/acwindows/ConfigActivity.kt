package com.acwindows

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Global app / comfort settings (thresholds, location, API keys), opened from the app's
 * gear icon. Writes to the same SharedPreferences that [AppConfig.load] reads, so changes
 * take effect on the next refresh. Settings are global (shared by all widget instances).
 *
 * There is no Save button: edits are staged in the fields and committed when you leave
 * the screen (see [onStop]). The header's "Discard changes" button — which appears only
 * once something has been edited — is the way to back out.
 */
class ConfigActivity : Activity() {

    private val scope = MainScope()

    private lateinit var staged: StagedEdits

    /** The location label stored in prefs when the screen opened; restored on discard. */
    private var savedPlaceName: String? = null

    // The label + coordinates last resolved from the device or an address lookup. We only
    // persist the label if the saved lat/lon still match these (hand-editing them clears it).
    private var pendingPlaceName: String? = null
    private var pendingLat: Double? = null
    private var pendingLon: Double? = null

    // Weight EditText id ↔ source name (ids can't contain hyphens; the source name can).
    private val weightFields = listOf(
        R.id.weight_openmeteo to "open-meteo",
        R.id.weight_nws to "nws",
        R.id.weight_metno to "metno",
        R.id.weight_tomorrowio to "tomorrowio",
        R.id.weight_pirateweather to "pirateweather",
        R.id.weight_airnow to "airnow",
    )

    /** Every editable field on the screen, for staged-edit tracking. */
    private val valueFields = listOf(
        R.id.open_score, R.id.temp_ideal, R.id.max_temp, R.id.dew_ideal, R.id.dew_max,
        R.id.aqi_ideal, R.id.aqi_max, R.id.rain_ideal, R.id.rain_max, R.id.lat, R.id.lon,
        R.id.key_tomorrowio, R.id.key_pirateweather, R.id.key_airnow,
    ) + weightFields.map { it.first }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        // Pre-fill with the current effective config (prefs merged with defaults).
        val cfg = AppConfig.load(this)
        field(R.id.open_score).setText(cfg.comfort.openScoreMin.toInt().toString())
        field(R.id.temp_ideal).setText(trimNum(cfg.comfort.tempIdealF))
        field(R.id.max_temp).setText(trimNum(cfg.comfort.maxTempF))
        field(R.id.dew_ideal).setText(trimNum(cfg.comfort.dewPointIdealF))
        field(R.id.dew_max).setText(trimNum(cfg.comfort.dewPointMaxF))
        field(R.id.aqi_ideal).setText(trimNum(cfg.comfort.aqiIdeal))
        field(R.id.aqi_max).setText(cfg.comfort.aqiMax.toString())
        field(R.id.rain_ideal).setText(trimNum(cfg.comfort.rainProbIdeal))
        field(R.id.rain_max).setText(trimNum(cfg.comfort.rainProbMax))
        field(R.id.lat).setText(cfg.lat.toString())
        field(R.id.lon).setText(cfg.lon.toString())

        // Pre-fill any saved API keys and wire the "get a key" links to each signup page.
        field(R.id.key_tomorrowio).setText(SecretsStore.get(this, SecretsStore.Key.TOMORROW_IO) ?: "")
        field(R.id.key_pirateweather).setText(SecretsStore.get(this, SecretsStore.Key.PIRATE_WEATHER) ?: "")
        field(R.id.key_airnow).setText(SecretsStore.get(this, SecretsStore.Key.AIRNOW) ?: "")
        link(R.id.link_tomorrowio, SecretsStore.Key.TOMORROW_IO.signupUrl)
        link(R.id.link_pirateweather, SecretsStore.Key.PIRATE_WEATHER.signupUrl)
        link(R.id.link_airnow, SecretsStore.Key.AIRNOW.signupUrl)

        // Per-source trust weights (default 1.0).
        weightFields.forEach { (id, src) -> field(id).setText(trimNum(cfg.sourceWeights[src] ?: 1.0)) }

        // Show the saved location label (from a device fix or an address lookup).
        savedPlaceName = cfg.placeName
        setLocStatus(cfg.placeName?.let { "Using $it" } ?: "")
        follow().isChecked = cfg.useDeviceLocation

        // Track edits from here on: whatever is in the fields now is the baseline.
        val discard = findViewById<Button>(R.id.discard)
        staged = StagedEdits(
            fields = valueFields.map { field(it) },
            checks = listOf(follow()),
            onToggle = { _, on -> onFollowToggled(on) },
        ) { dirty ->
            discard.visibility = if (dirty) View.VISIBLE else View.GONE
        }
        discard.setOnClickListener { discardChanges() }

        findViewById<Button>(R.id.lookup_address).setOnClickListener { onLookupAddress() }
        // Reflect the loaded state without going through the toggle listener (which would
        // prompt for permission just for opening the screen).
        syncLocationFields(cfg.useDeviceLocation)
    }

    /** Back press: commit before the screen behind us resumes, so it sees the new config. */
    override fun onPause() {
        super.onPause()
        if (isFinishing) saveIfDirty()
    }

    /**
     * Home / app switcher: still leaving the screen. Guarded so the two paths that
     * merely pause or recreate us — the location permission dialog (onPause only) and a
     * rotation (onStop with isChangingConfigurations) — don't save behind the user's back.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) saveIfDirty()
    }

    private fun saveIfDirty() {
        if (staged.isDirty()) save()
    }

    private fun discardChanges() {
        staged.discard()   // restores the fields and the follow checkbox (re-syncing enablement)
        pendingPlaceName = null
        pendingLat = null
        pendingLon = null
        setLocStatus(savedPlaceName?.let { "Using $it" } ?: "")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ───────── location: follow the phone, look up an address, or type coordinates ─────────

    private fun follow(): CheckBox = findViewById(R.id.use_device_location)

    /**
     * The checkbox changed. Turning it on needs location permission, so prompt when we
     * don't hold it yet and leave the box on only if the grant comes back — a denial
     * puts it straight back to manual coordinates rather than leaving a checkbox that
     * silently does nothing.
     */
    private fun onFollowToggled(on: Boolean) {
        syncLocationFields(on)
        if (!on) {
            setLocStatus(savedPlaceName?.let { "Using $it" } ?: "")
            return
        }
        // Gate on precise: if we don't yet have FINE, prompt — this also lets an
        // existing Approximate-only grant upgrade to Precise via the Android 12+ toggle.
        if (LocationProvider.hasPrecise(this)) fetchLocation()
        else requestPermissions(LocationProvider.permissions, REQ_LOCATION)
    }

    /** While the phone's location is in charge, the coordinates aren't yours to edit. */
    private fun syncLocationFields(following: Boolean) {
        listOf(R.id.lat, R.id.lon, R.id.address).forEach {
            field(it).isEnabled = !following
            field(it).alpha = if (following) 0.45f else 1f
        }
        findViewById<Button>(R.id.lookup_address).isEnabled = !following
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQ_LOCATION) return
        // Fetch if we ended up with any grant (fetch uses the finest available); only a
        // full denial falls back to manual entry. Re-check live state rather than the
        // results array, since an already-held COARSE reads as granted here too.
        if (LocationProvider.hasPermission(this)) {
            fetchLocation()
        } else {
            follow().isChecked = false   // fires onFollowToggled → re-enables the fields
            setLocStatus("Location permission denied — enter a lat/long or an address instead.")
        }
    }

    private fun fetchLocation() {
        setLocStatus("Locating…")
        scope.launch {
            val loc = LocationProvider.currentLocation(this@ConfigActivity)
            if (loc == null) {
                setLocStatus("Couldn't get a location — check location is on, or enter it by hand.")
                return@launch
            }
            val lat = AppConfig.round4(loc.latitude)
            val lon = AppConfig.round4(loc.longitude)
            applyResolved(lat, lon, label = null)
            val name = LocationProvider.placeName(this@ConfigActivity, lat, lon)
            applyResolved(lat, lon, name)
            setLocStatus(name?.let { "Using $it" } ?: "Location set")
        }
    }

    /** Geocode whatever is in the address box into the lat/long fields. */
    private fun onLookupAddress() {
        val query = field(R.id.address).text.toString().trim()
        if (query.isEmpty()) {
            setLocStatus("Type an address, city, or ZIP first.")
            return
        }
        setLocStatus("Looking up “$query”…")
        scope.launch {
            val hit = LocationProvider.geocodeAddress(this@ConfigActivity, query)
            if (hit == null) {
                setLocStatus("Couldn't find “$query” — try a city and state, or a ZIP.")
                return@launch
            }
            val lat = AppConfig.round4(hit.lat)
            val lon = AppConfig.round4(hit.lon)
            applyResolved(lat, lon, hit.label)
            setLocStatus("Using ${hit.label}  ($lat, $lon)")
        }
    }

    /** Put a resolved coordinate into the fields and remember its label for [save]. */
    private fun applyResolved(lat: Double, lon: Double, label: String?) {
        field(R.id.lat).setText(lat.toString())
        field(R.id.lon).setText(lon.toString())
        pendingLat = lat
        pendingLon = lon
        pendingPlaceName = label
    }

    private fun setLocStatus(msg: String) = findViewById<TextView>(R.id.loc_status).setText(msg)

    /** The label to persist for the saved coordinates, or null if they're hand-entered. */
    private fun placeNameFor(cfg: AppConfig, lat: Double, lon: Double): String? = when {
        pendingPlaceName != null && lat == pendingLat && lon == pendingLon -> pendingPlaceName
        lat == cfg.lat && lon == cfg.lon -> cfg.placeName   // unchanged → keep the stored label
        else -> null                                        // changed by hand → no known label
    }

    private fun link(id: Int, url: String) = findViewById<TextView>(id).setOnClickListener {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun save() {
        val cfg = AppConfig.load(this)
        // Parse each field, falling back to the current value if it's blank/invalid.
        val tempIdeal = field(R.id.temp_ideal).text.toString().toDoubleOrNull() ?: cfg.comfort.tempIdealF
        val maxTemp = field(R.id.max_temp).text.toString().toDoubleOrNull() ?: cfg.comfort.maxTempF
        val openScore = field(R.id.open_score).text.toString().toDoubleOrNull()
            ?.coerceIn(0.0, 100.0) ?: cfg.comfort.openScoreMin
        val dewIdeal = field(R.id.dew_ideal).text.toString().toDoubleOrNull() ?: cfg.comfort.dewPointIdealF
        val dewMax = field(R.id.dew_max).text.toString().toDoubleOrNull() ?: cfg.comfort.dewPointMaxF
        val aqiIdeal = field(R.id.aqi_ideal).text.toString().toDoubleOrNull() ?: cfg.comfort.aqiIdeal
        val aqiMax = field(R.id.aqi_max).text.toString().toIntOrNull() ?: cfg.comfort.aqiMax
        val rainIdeal = field(R.id.rain_ideal).text.toString().toDoubleOrNull() ?: cfg.comfort.rainProbIdeal
        val rainMax = field(R.id.rain_max).text.toString().toDoubleOrNull() ?: cfg.comfort.rainProbMax
        val lat = field(R.id.lat).text.toString().toDoubleOrNull() ?: cfg.lat
        val lon = field(R.id.lon).text.toString().toDoubleOrNull() ?: cfg.lon
        val placeName = placeNameFor(cfg, lat, lon)
        // Only honour the checkbox if the permission actually landed; the saved lat/lon
        // stay written either way, as the fallback when a fix can't be had later.
        val followDevice = follow().isChecked && LocationProvider.hasPermission(this)

        val ed = getSharedPreferences("ac_windows", MODE_PRIVATE).edit()
            .putBoolean("useDeviceLocation", followDevice)
            .putString("tempIdealF", tempIdeal.toString())
            .putString("maxTempF", maxTemp.toString())
            .putString("openScoreMin", openScore.toString())
            .putString("dewPointIdealF", dewIdeal.toString())
            .putString("dewPointMaxF", dewMax.toString())
            .putString("aqiIdeal", aqiIdeal.toString())
            .putString("aqiMax", aqiMax.toString())
            .putString("rainProbIdeal", rainIdeal.toString())
            .putString("rainProbMax", rainMax.toString())
            .putString("lat", lat.toString())
            .putString("lon", lon.toString())
            .putString("placeName", placeName ?: "")
        weightFields.forEach { (id, src) ->
            val w = field(id).text.toString().toDoubleOrNull()?.coerceIn(0.0, 10.0) ?: 1.0
            ed.putString("weight_$src", trimNum(w))
        }
        ed.apply()

        // API keys live in their own private file (not SharedPreferences); blank clears a slot.
        SecretsStore.save(
            this,
            mapOf(
                SecretsStore.Key.TOMORROW_IO to field(R.id.key_tomorrowio).text.toString(),
                SecretsStore.Key.PIRATE_WEATHER to field(R.id.key_pirateweather).text.toString(),
                SecretsStore.Key.AIRNOW to field(R.id.key_airnow).text.toString(),
            ),
        )

        // New comfort/location/keys → cached scores are stale; refetch + redraw.
        WidgetCache.clear(this)
        AcWindowsProvider.refreshNow(this)

        savedPlaceName = placeName
        staged.commit()
    }

    private fun field(id: Int): EditText = findViewById(id)
    private fun trimNum(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    private companion object {
        const val REQ_LOCATION = 42
    }
}
