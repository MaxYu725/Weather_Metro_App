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
import androidx.annotation.RequiresApi
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

private fun traditionalAddressText(value: String?): String? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        IcuTraditionalAddressConverter.convert(text)
    } else {
        buildString(text.length) {
            text.forEach { character ->
                append(LEGACY_TRADITIONAL_ADDRESS_MAP[character] ?: character)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private object IcuTraditionalAddressConverter {
    private val converter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Transliterator.getInstance("Simplified-Traditional")
    }

    fun convert(text: String): String = synchronized(converter) {
        converter.transliterate(text)
    }
}

/**
 * API 26-28 fallback for common Simplified Chinese characters returned by Android geocoders in
 * Hong Kong address/place names. API 29+ uses ICU for complete Simplified-to-Traditional conversion.
 */
private val LEGACY_TRADITIONAL_ADDRESS_MAP = mapOf(
    '号' to '號',
    '区' to '區',
    '湾' to '灣',
    '门' to '門',
    '东' to '東',
    '华' to '華',
    '马' to '馬',
    '龙' to '龍',
    '风' to '風',
    '长' to '長',
    '广' to '廣',
    '场' to '場',
    '观' to '觀',
    '码' to '碼',
    '头' to '頭',
    '岭' to '嶺',
    '岗' to '崗',
    '桥' to '橋',
    '线' to '線',
    '铁' to '鐵',
    '车' to '車',
    '乡' to '鄉',
    '镇' to '鎮',
    '楼' to '樓',
    '层' to '層',
    '厦' to '廈',
    '发' to '發',
    '兴' to '興',
    '国' to '國',
    '园' to '園',
    '体' to '體',
    '医' to '醫',
    '学' to '學',
    '会' to '會',
    '馆' to '館',
    '义' to '義',
    '乐' to '樂',
    '云' to '雲',
    '维' to '維',
    '经' to '經',
    '济' to '濟',
    '贸' to '貿',
    '业' to '業',
    '办' to '辦',
    '厅' to '廳',
    '卫' to '衛',
    '环' to '環',
    '务' to '務',
    '树' to '樹',
    '边' to '邊',
)
