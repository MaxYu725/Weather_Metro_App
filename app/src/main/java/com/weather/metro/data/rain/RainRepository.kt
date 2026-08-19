package com.weather.metro.data.rain

import com.weather.metro.data.tools.RainRadarMode
import com.weather.metro.domain.rain.RainCapabilities
import com.weather.metro.domain.rain.RainForecastFrame
import com.weather.metro.domain.rain.RainForecastRunChangedException
import com.weather.metro.domain.rain.RainForecastSource
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.domain.rain.RainLoadResult
import com.weather.metro.domain.rain.RainPointForecast
import com.weather.metro.domain.rain.RainRadarContract
import com.weather.metro.domain.rain.RainRadarTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class RainRepository(
    private val client: RainTrackClient,
    private val cache: RainCache,
    private val forecastClient: RainForecastClient = RainForecastClient(),
    private val radarClient: RainRadarClient = RainRadarClient(),
) {
    private val forecastLock = Any()
    private var activeSwirlsRun: String? = null
    private val activeSwirlsFrames = mutableMapOf<Int, RainForecastFrame>()

    suspend fun loadCapabilities(): RainLoadResult<RainCapabilities> = try {
        val network = client.loadCapabilities()
        runCatching { cache.writeCapabilities(network.rawPayload) }
        RainLoadResult(network.value, isStale = false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        val cached = cache.readCapabilities()?.let {
            runCatching { client.parseCapabilities(it) }.getOrNull()
        } ?: throw userFacingRainException(error)
        RainLoadResult(
            value = cached,
            isStale = true,
            networkError = rainUserFacingError(error.message),
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
    } catch (error: CancellationException) {
        throw error
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
        } ?: throw userFacingRainException(error)
        RainLoadResult(
            value = cached,
            isStale = true,
            networkError = rainUserFacingError(error.message),
        )
    }

    suspend fun loadRadarContract(): RainLoadResult<RainRadarContract> = try {
        val network = radarClient.loadContract()
        RainLoadResult(network.value, isStale = false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw userFacingRainException(error)
    }

    suspend fun loadRadarTimeline(
        rangeKm: Int,
        heightKm: Int,
        mode: RainRadarMode = RainRadarMode.LIVE,
    ): RainLoadResult<RainRadarTimeline> = try {
        val network = radarClient.loadFrames(rangeKm, heightKm, mode)
        RainLoadResult(network.value, isStale = false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw userFacingRainException(error)
    }

    suspend fun loadRadarImage(relativePath: String): ByteArray = radarClient.loadImage(relativePath)

    suspend fun loadForecastTimeline(): RainLoadResult<RainForecastTimeline> {
        try {
            val network = loadInitialSwirlsWithRetry {
                forecastClient.loadSwirlsFrame(0)
            }
            val timeline = forecastClient.buildSwirlsTimeline(network.value)
            synchronized(forecastLock) {
                if (activeSwirlsRun != timeline.issueTime) activeSwirlsFrames.clear()
                activeSwirlsRun = timeline.issueTime
                activeSwirlsFrames[0] = network.value
            }
            return RainLoadResult(timeline, isStale = false)
        } catch (error: CancellationException) {
            throw error
        } catch (swirlsError: Throwable) {
            return loadNowcastFallback(swirlsError)
        }
    }

    suspend fun loadForecastFrame(
        timeline: RainForecastTimeline,
        frameIndex: Int,
    ): RainLoadResult<RainForecastFrame> {
        require(frameIndex in timeline.frames.indices) { "Forecast frame index outside active timeline" }
        timeline.frame(frameIndex)?.let { return RainLoadResult(it, isStale = false) }
        require(timeline.source == RainForecastSource.SWIRLS) {
            "Only SWIRLS timelines support lazy frame loading"
        }

        synchronized(forecastLock) {
            if (activeSwirlsRun == timeline.issueTime) {
                activeSwirlsFrames[frameIndex]?.let { return RainLoadResult(it, isStale = false) }
            }
        }

        try {
            val network = forecastClient.loadSwirlsFrame(frameIndex)
            forecastClient.assertSwirlsFrameCompatible(timeline, network.value)
            synchronized(forecastLock) {
                if (activeSwirlsRun != timeline.issueTime) {
                    throw RainForecastRunChangedException(
                        "SWIRLS active run changed while frame $frameIndex was loading",
                    )
                }
                activeSwirlsFrames[frameIndex] = network.value
            }
            return RainLoadResult(network.value, isStale = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RainForecastRunChangedException) {
            throw error
        } catch (error: Throwable) {
            throw userFacingRainException(error)
        }
    }

    fun clearForecastMemory() {
        synchronized(forecastLock) {
            activeSwirlsRun = null
            activeSwirlsFrames.clear()
        }
    }

    suspend fun clearCache() {
        clearForecastMemory()
        cache.clear()
    }

    private suspend fun loadNowcastFallback(swirlsError: Throwable): RainLoadResult<RainForecastTimeline> {
        val swirlsMessage = swirlsError.message ?: "SWIRLS unavailable"
        try {
            val network = forecastClient.loadNowcast(fallbackReason = swirlsMessage)
            runCatching { cache.writeNowcast(network.rawPayload) }
            return RainLoadResult(network.value, isStale = false)
        } catch (error: CancellationException) {
            throw error
        } catch (nowcastError: Throwable) {
            val cached = cache.readNowcast()?.let {
                runCatching {
                    forecastClient.parseNowcast(
                        payload = it,
                        fallbackReason = "$swirlsMessage; nowcast refresh failed: ${nowcastError.message ?: "unknown error"}",
                    )
                }.getOrNull()
            }
            if (cached != null) {
                return RainLoadResult(
                    value = cached,
                    isStale = true,
                    networkError = rainUserFacingError(nowcastError.message),
                )
            }
            throw userFacingRainException(nowcastError)
        }
    }
}

internal suspend fun <T> loadInitialSwirlsWithRetry(
    maxAttempts: Int = 2,
    retryDelayMs: Long = 750L,
    load: suspend () -> T,
): T {
    require(maxAttempts > 0) { "SWIRLS initial load attempts must be positive" }
    require(retryDelayMs >= 0L) { "SWIRLS initial retry delay must be non-negative" }

    var lastError: Throwable? = null
    repeat(maxAttempts) { attempt ->
        try {
            return load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastError = error
            if (attempt < maxAttempts - 1 && retryDelayMs > 0L) delay(retryDelayMs)
        }
    }
    throw lastError ?: IllegalStateException("SWIRLS initial frame unavailable")
}

private fun userFacingRainException(error: Throwable): IllegalStateException =
    IllegalStateException(rainUserFacingError(error.message), error)
