package com.nanocomm.nanosmart.eventos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale

data class EmergencyLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val capturedAtMillis: Long
) {
    fun mapsUrl(): String = EmergencyMapLink.url(latitude, longitude)
}

object EmergencyLocationPolicy {
    const val MAX_LAST_KNOWN_AGE_MS = 10 * 60 * 1000L

    fun validCoordinates(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun isRecent(nowMillis: Long, capturedAtMillis: Long): Boolean {
        val age = (nowMillis - capturedAtMillis).coerceAtLeast(0L)
        return age <= MAX_LAST_KNOWN_AGE_MS
    }
}

object EmergencyLocationProvider {
    private const val CURRENT_LOCATION_TIMEOUT_MS = 10_000L

    @Volatile
    private var cachedLocation: EmergencyLocation? = null

    @Volatile
    private var refreshInProgress = false

    @SuppressLint("MissingPermission")
    fun bestLastKnown(context: Context, nowMillis: Long = System.currentTimeMillis()): EmergencyLocation? {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val systemLocations = providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { location ->
                EmergencyLocationPolicy.validCoordinates(location.latitude, location.longitude) &&
                    EmergencyLocationPolicy.isRecent(nowMillis, location.time)
            }
            .map { location ->
                EmergencyLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                    capturedAtMillis = location.time
                )
            }
        val cached = cachedLocation?.takeIf {
            EmergencyLocationPolicy.validCoordinates(it.latitude, it.longitude) &&
                EmergencyLocationPolicy.isRecent(nowMillis, it.capturedAtMillis)
        }
        return (systemLocations + listOfNotNull(cached)).maxByOrNull { it.capturedAtMillis }
    }

    @SuppressLint("MissingPermission")
    fun refresh(context: Context) {
        if (refreshInProgress) return
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return
        refreshInProgress = true
        val mainHandler = Handler(Looper.getMainLooper())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancellation = CancellationSignal()
            mainHandler.postDelayed({
                if (refreshInProgress) {
                    cancellation.cancel()
                    refreshInProgress = false
                }
            }, CURRENT_LOCATION_TIMEOUT_MS)
            runCatching {
                manager.getCurrentLocation(
                    provider,
                    cancellation,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    location?.let(::remember)
                }
            }.onFailure { refreshInProgress = false }
            return
        }

        @Suppress("DEPRECATION")
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                remember(location)
                runCatching { manager.removeUpdates(this) }
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        mainHandler.postDelayed({
            runCatching { manager.removeUpdates(listener) }
            refreshInProgress = false
        }, CURRENT_LOCATION_TIMEOUT_MS)
        runCatching {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }.onFailure { refreshInProgress = false }
    }

    private fun remember(location: android.location.Location) {
        if (!EmergencyLocationPolicy.validCoordinates(location.latitude, location.longitude)) return
        cachedLocation = EmergencyLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            capturedAtMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
    }
}

object EmergencyMapLink {
    fun url(latitude: Double, longitude: Double): String {
        val coordinates = String.format(Locale.US, "%.7f,%.7f", latitude, longitude)
        return "https://www.google.com/maps/search/?api=1&query=${Uri.encode(coordinates)}"
    }

    fun intent(latitude: Double, longitude: Double): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url(latitude, longitude)))
}
