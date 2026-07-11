package com.acwindows

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * On-device location using only the platform [LocationManager] + [Geocoder] — no
 * Google Play Services dependency, so the app stays buildable/installable on any
 * device and free of proprietary libs. Coarse (≈city-level) accuracy is all the
 * weather lookup needs, so we request only ACCESS_COARSE_LOCATION; users who don't
 * want to share location at all can still type a lat/long by hand in settings.
 */
object LocationProvider {

    /** What ConfigActivity requests. Coarse only — fine is accepted if already granted. */
    val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(context: Context, perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    /**
     * Best-effort current location: the freshest last-known fix if one exists, else a
     * single fresh fix (bounded by [timeoutMs]). Returns null when location is
     * off/denied or no fix arrives in time — the caller falls back to manual entry.
     * Safe to call from the main thread (the fresh-fix listener uses the main looper).
     */
    @SuppressLint("MissingPermission")   // gated on hasPermission()
    suspend fun currentLocation(context: Context, timeoutMs: Long = 12_000): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        lastKnown(lm)?.let { return it }

        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(timeoutMs) { singleUpdate(lm, provider) }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        lm.allProviders
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    private suspend fun singleUpdate(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }
                // Required abstract methods pre-API-30 (no-ops here).
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Required by LocationListener pre-API-30", ReplaceWith(""))
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
            // Explicit main looper → deliverable even when called off the main thread.
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }

    /**
     * Reverse-geocode a coordinate to a short "City, ST" (or county) label, or null if
     * no geocoder backend is present / nothing resolves. Runs off the main thread.
     */
    suspend fun placeName(context: Context, lat: Double, lon: Double): String? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            val geo = Geocoder(context, Locale.getDefault())
            val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocodeAsync(geo, lat, lon)
            } else {
                @Suppress("DEPRECATION")
                runCatching { geo.getFromLocation(lat, lon, 1) }.getOrNull()?.firstOrNull()
            }
            addr?.let { format(it) }
        }

    private suspend fun geocodeAsync(geo: Geocoder, lat: Double, lon: Double): Address? =
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                geo.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.firstOrNull())
                    }
                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        }

    /** "City, ST" — city (or county) plus state, dropping either if missing/duplicated. */
    private fun format(a: Address): String? {
        val city = a.locality ?: a.subAdminArea ?: a.subLocality
        val region = a.adminArea
        return listOfNotNull(city, region?.takeIf { it != city })
            .joinToString(", ")
            .ifBlank { null }
    }
}
