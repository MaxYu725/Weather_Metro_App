package com.weather.metro.data

import com.weather.metro.data.cache.WeatherCache
import com.weather.metro.data.hko.HkoClient
import com.weather.metro.data.location.LocationRepository
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.WeatherSnapshot

data class RefreshResult(
    val snapshot: WeatherSnapshot,
    val networkError: String? = null,
)

class WeatherRepository(
    private val hkoClient: HkoClient,
    private val locationRepository: LocationRepository,
    private val cache: WeatherCache,
) {
    fun hasLocationPermission(): Boolean = locationRepository.hasLocationPermission()

    suspend fun resolveLocation(usePreciseLocation: Boolean): LocationInfo =
        if (usePreciseLocation) {
            runCatching { locationRepository.currentLocation() }.getOrElse { locationRepository.defaultLocation() }
        } else {
            locationRepository.defaultLocation()
        }

    suspend fun refresh(usePreciseLocation: Boolean): RefreshResult =
        refreshAt(resolveLocation(usePreciseLocation))

    suspend fun refreshAt(location: LocationInfo): RefreshResult {
        return try {
            val result = hkoClient.load(location)
            runCatching { cache.write(result.cachePayload) }
            RefreshResult(result.snapshot)
        } catch (error: Throwable) {
            val cached = cache.read()?.let { runCatching { hkoClient.parseCached(it) }.getOrNull() }
                ?: throw error
            RefreshResult(cached, error.message ?: "network unavailable")
        }
    }

    suspend fun cached(): WeatherSnapshot? =
        cache.read()?.let { runCatching { hkoClient.parseCached(it) }.getOrNull() }

    suspend fun clearCache() = cache.clear()
}
