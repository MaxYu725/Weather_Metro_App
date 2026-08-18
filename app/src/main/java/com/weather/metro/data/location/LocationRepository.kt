package com.weather.metro.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.icu.text.Transliterator
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.weather.metro.domain.LocationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

class LocationRepository(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): LocationInfo {
        if (!hasLocationPermission()) return defaultLocation()
        val cancellation = CancellationTokenSource()
        val location = suspendCancellableCoroutine<Location?> { continuation ->
            continuation.invokeOnCancellation { cancellation.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .addOnSuccessListener { result ->
                    if (result == null) continuation.resume(null)
                    else continuation.resume(result)
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
        } ?: return defaultLocation()

        val address = reverseGeocode(location.latitude, location.longitude)
        val street = listOfNotNull(
            traditionalAddressText(address?.subThoroughfare),
            traditionalAddressText(address?.thoroughfare),
        )
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        val districtHints = listOfNotNull(
            traditionalAddressText(address?.subAdminArea),
            traditionalAddressText(address?.adminArea),
            traditionalAddressText(address?.subLocality),
            traditionalAddressText(address?.locality),
        ).joinToString(" ")
        val label = preferredLocationLabel(
            candidates = listOf(
                traditionalAddressText(address?.premises),
                street,
                traditionalAddressText(address?.thoroughfare),
                traditionalAddressText(address?.featureName),
                traditionalAddressText(address?.subLocality),
                traditionalAddressText(address?.locality),
            ),
            geocodedDistrict = districtHints,
        )
        return HongKongStations.enrich(
            latitude = location.latitude,
            longitude = location.longitude,
            label = label,
            geocodedDistrict = districtHints,
            accuracyMetres = location.accuracy.takeIf { it > 0 }?.roundToInt(),
        )
    }

    fun defaultLocation(): LocationInfo = HongKongStations.enrich(
        latitude = 22.3019,
        longitude = 114.1742,
        label = "香港天文台",
        geocodedDistrict = "油尖旺",
        accuracyMetres = null,
    )

    private suspend fun reverseGeocode(latitude: Double, longitude: Double): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.forLanguageTag("zh-HK"))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                    if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) {
                runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }
    }
}

private val simplifiedToTraditional by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    Transliterator.getInstance("Simplified-Traditional")
}

private fun traditionalAddressText(value: String?): String? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return synchronized(simplifiedToTraditional) {
        simplifiedToTraditional.transliterate(text)
    }
}
