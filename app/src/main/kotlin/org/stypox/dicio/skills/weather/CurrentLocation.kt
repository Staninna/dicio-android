package org.stypox.dicio.skills.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object CurrentLocation {
    private const val MAX_AGE_MILLIS = 30 * 60 * 1000L
    private const val FIX_TIMEOUT_MILLIS = 10_000L

    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns the approximate location of the phone, or null if there is no permission, location
     * is off, or no fix arrives in time. A location from the last half hour is reused, otherwise
     * a fresh one is requested.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    suspend fun get(context: Context): Location? {
        if (!hasPermission(context)) {
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER
            else null,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).filter { provider ->
            // before Android 12 the GPS provider needs the fine location permission
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }

        val recent = providers
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { ageMillis(it) < MAX_AGE_MILLIS }
            .minByOrNull { ageMillis(it) }
        if (recent != null) {
            return recent
        }

        // ask all providers at once: with only the coarse permission the fused provider relies on
        // network location, which is often off (e.g. on GrapheneOS), while GPS fixes from
        // Android 12 on are made approximate by the system
        return withTimeoutOrNull(FIX_TIMEOUT_MILLIS) {
            channelFlow {
                providers.filter { it != LocationManager.PASSIVE_PROVIDER }.forEach { provider ->
                    launch {
                        runCatching { requestFix(context, manager, provider) }.getOrNull()
                            ?.let { send(it) }
                    }
                }
            }.firstOrNull()
        }
    }

    private fun ageMillis(location: Location) =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    @SuppressLint("MissingPermission")
    private suspend fun requestFix(
        context: Context,
        manager: LocationManager,
        provider: String,
    ): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            manager.getCurrentLocation(provider, cancel, context.mainExecutor) { location ->
                cont.resume(location)
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    cont.resume(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            cont.invokeOnCancellation { manager.removeUpdates(listener) }
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }
}
