package com.weather.metro.data.rain

import com.weather.metro.domain.rain.RainCapabilities
import com.weather.metro.domain.rain.RainLoadResult
import com.weather.metro.domain.rain.RainPointForecast

class RainRepository(
    private val client: RainTrackClient,
    private val cache: RainCache,
) {
    suspend fun loadCapabilities(): RainLoadResult<RainCapabilities> = try {
        val network = client.loadCapabilities()
        runCatching { cache.writeCapabilities(network.rawPayload) }
        RainLoadResult(network.value, isStale = false)
    } catch (error: Throwable) {
        val cached = cache.readCapabilities()?.let {
            runCatching { client.parseCapabilities(it) }.getOrNull()
        } ?: throw error
        RainLoadResult(
            value = cached,
            isStale = true,
            networkError = error.message ?: "Rain capabilities unavailable",
        )
    }

    suspend fun loadPointForecast(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
    ): RainLoadResult<RainPointForecast> = try {
        val network = client.loadPointForecast(latitude, longitude, radiusKm)
        runCatching { cache.writePoint(latitude, longitude, radiusKm, network.rawPayload) }
        RainLoadResult(network.value, isStale = false)
    } catch (error: Throwable) {
        val cached = cache.readPoint(latitude, longitude, radiusKm)?.let {
            runCatching {
                client.parsePointForecast(
                    payload = it,
                    expectedLatitude = latitude,
                    expectedLongitude = longitude,
                    expectedRadiusKm = radiusKm,
                )
            }.getOrNull()
        } ?: throw error
        RainLoadResult(
            value = cached,
            isStale = true,
            networkError = error.message ?: "Rain point forecast unavailable",
        )
    }

    suspend fun clearCache() = cache.clear()
}
