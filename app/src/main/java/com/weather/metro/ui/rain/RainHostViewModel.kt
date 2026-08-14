package com.weather.metro.ui.rain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weather.metro.data.rain.RainCache
import com.weather.metro.data.rain.RainRepository
import com.weather.metro.data.rain.RainTrackClient
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainCapabilities
import com.weather.metro.domain.rain.RainForecastFrame
import com.weather.metro.domain.rain.RainForecastRunChangedException
import com.weather.metro.domain.rain.RainForecastSource
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.domain.rain.RainPointForecast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class RainResourceStatus {
    IDLE,
    LOADING,
    READY,
    ERROR,
}

data class RainResourceState<T>(
    val status: RainResourceStatus = RainResourceStatus.IDLE,
    val value: T? = null,
    val isStale: Boolean = false,
    val errorMessage: String? = null,
)

data class RainPointRequestKey(
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Int,
) {
    companion object {
        fun from(location: LocationInfo, radiusKm: Int): RainPointRequestKey = RainPointRequestKey(
            latitude = location.latitude,
            longitude = location.longitude,
            radiusKm = radiusKm,
        )
    }
}

data class RainHostState(
    val location: LocationInfo? = null,
    val capabilities: RainResourceState<RainCapabilities> = RainResourceState(),
    val pointForecast: RainResourceState<RainPointForecast> = RainResourceState(),
    val pointRequest: RainPointRequestKey? = null,
    val forecast: RainResourceState<RainForecastTimeline> = RainResourceState(),
    val forecastFrame: RainResourceState<RainForecastFrame> = RainResourceState(),
    val forecastFrameIndex: Int? = null,
)

/**
 * Independent state owner for Rain features hosted by Weather Metro.
 *
 * This ViewModel deliberately does not own a LocationRepository. The normal Weather pipeline resolves
 * location once, then [bindHostLocation] supplies that LocationInfo here so Current, Point, Radar and
 * Forecast surfaces can share the same host location without creating a second location subsystem.
 */
class RainHostViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RainRepository(
        client = RainTrackClient(),
        cache = RainCache(application),
    )

    private val _state = MutableStateFlow(RainHostState())
    val state: StateFlow<RainHostState> = _state.asStateFlow()

    private var capabilitiesJob: Job? = null
    private var pointJob: Job? = null
    private var forecastJob: Job? = null
    private var forecastFrameJob: Job? = null
    private var forecastPrefetchJob: Job? = null
    private var capabilitiesGeneration = 0L
    private var pointGeneration = 0L
    private var forecastGeneration = 0L
    private var forecastFrameGeneration = 0L
    private var forecastPrefetchGeneration = 0L
    private var pointAcceptedAtEpochMs: Long? = null
    private var pointLastAttemptEpochMs: Long? = null
    private var forecastAcceptedAtEpochMs: Long? = null
    private var forecastLastAttemptEpochMs: Long? = null

    fun bindHostLocation(location: LocationInfo) {
        val current = _state.value.location
        if (current != null && sameRainLocation(current, location)) {
            if (current != location) _state.update { it.copy(location = location) }
            return
        }

        pointGeneration += 1
        pointJob?.cancel()
        pointJob = null
        pointAcceptedAtEpochMs = null
        pointLastAttemptEpochMs = null
        _state.update {
            it.copy(
                location = location,
                pointForecast = RainResourceState(),
                pointRequest = null,
            )
        }
    }

    fun refreshCapabilities() {
        val generation = ++capabilitiesGeneration
        capabilitiesJob?.cancel()
        val previous = _state.value.capabilities
        _state.update {
            it.copy(
                capabilities = previous.copy(
                    status = RainResourceStatus.LOADING,
                    errorMessage = null,
                ),
            )
        }

        capabilitiesJob = viewModelScope.launch {
            try {
                val result = repository.loadCapabilities()
                if (generation != capabilitiesGeneration) return@launch
                _state.update {
                    it.copy(
                        capabilities = RainResourceState(
                            status = RainResourceStatus.READY,
                            value = result.value,
                            isStale = result.isStale,
                            errorMessage = result.networkError,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation != capabilitiesGeneration) return@launch
                _state.update {
                    val existing = it.capabilities.value
                    it.copy(
                        capabilities = if (existing != null) {
                            it.capabilities.copy(
                                status = RainResourceStatus.READY,
                                isStale = true,
                                errorMessage = error.message ?: "Rain capabilities unavailable",
                            )
                        } else {
                            RainResourceState(
                                status = RainResourceStatus.ERROR,
                                errorMessage = error.message ?: "Rain capabilities unavailable",
                            )
                        },
                    )
                }
            }
        }
    }

    fun refreshPointForecastIfStale(radiusKm: Int = DEFAULT_POINT_RADIUS_KM) {
        require(radiusKm in SUPPORTED_POINT_RADII_KM) { "Unsupported nearby radius: $radiusKm km" }
        val current = _state.value
        val location = current.location ?: return
        val request = RainPointRequestKey.from(location, radiusKm)
        if (current.pointRequest != request) {
            refreshPointForecast(radiusKm)
            return
        }
        if (
            shouldRefreshRainPoint(
                status = current.pointForecast.status,
                isStale = current.pointForecast.isStale,
                forecast = current.pointForecast.value,
                acceptedAtEpochMs = pointAcceptedAtEpochMs,
                lastAttemptEpochMs = pointLastAttemptEpochMs,
                nowEpochMs = System.currentTimeMillis(),
            )
        ) {
            refreshPointForecast(radiusKm)
        }
    }

    fun refreshPointForecast(radiusKm: Int = DEFAULT_POINT_RADIUS_KM) {
        require(radiusKm in SUPPORTED_POINT_RADII_KM) { "Unsupported nearby radius: $radiusKm km" }
        val location = _state.value.location
        if (location == null) {
            _state.update {
                it.copy(
                    pointForecast = RainResourceState(
                        status = RainResourceStatus.ERROR,
                        errorMessage = "尚未有可用位置",
                    ),
                    pointRequest = null,
                )
            }
            return
        }

        val request = RainPointRequestKey.from(location, radiusKm)
        val generation = ++pointGeneration
        pointJob?.cancel()
        pointLastAttemptEpochMs = System.currentTimeMillis()
        val currentState = _state.value
        val previous = currentState.pointForecast.takeIf { currentState.pointRequest == request }
            ?: RainResourceState()

        _state.update {
            it.copy(
                pointForecast = previous.copy(
                    status = RainResourceStatus.LOADING,
                    errorMessage = null,
                ),
                pointRequest = request,
            )
        }

        pointJob = viewModelScope.launch {
            try {
                val result = repository.loadPointForecast(
                    latitude = request.latitude,
                    longitude = request.longitude,
                    radiusKm = request.radiusKm,
                )
                if (!isCurrentPointRequest(generation, request)) return@launch
                pointAcceptedAtEpochMs = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        pointForecast = RainResourceState(
                            status = RainResourceStatus.READY,
                            value = result.value,
                            isStale = result.isStale,
                            errorMessage = result.networkError,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentPointRequest(generation, request)) return@launch
                _state.update {
                    val existing = it.pointForecast.value
                    it.copy(
                        pointForecast = if (existing != null) {
                            it.pointForecast.copy(
                                status = RainResourceStatus.READY,
                                isStale = true,
                                errorMessage = error.message ?: "Rain point forecast unavailable",
                            )
                        } else {
                            RainResourceState(
                                status = RainResourceStatus.ERROR,
                                errorMessage = error.message ?: "Rain point forecast unavailable",
                            )
                        },
                    )
                }
            }
        }
    }

    fun refreshForecastIfStale() {
        val current = _state.value.forecast
        if (
            shouldRefreshRainForecast(
                status = current.status,
                isStale = current.isStale,
                timeline = current.value,
                acceptedAtEpochMs = forecastAcceptedAtEpochMs,
                lastAttemptEpochMs = forecastLastAttemptEpochMs,
                nowEpochMs = System.currentTimeMillis(),
            )
        ) {
            refreshForecast()
        }
    }

    fun refreshForecast() {
        val generation = ++forecastGeneration
        forecastFrameGeneration += 1
        forecastPrefetchGeneration += 1
        forecastJob?.cancel()
        forecastFrameJob?.cancel()
        forecastPrefetchJob?.cancel()
        forecastFrameJob = null
        forecastPrefetchJob = null
        forecastLastAttemptEpochMs = System.currentTimeMillis()
        val previous = _state.value.forecast
        _state.update {
            it.copy(
                forecast = previous.copy(
                    status = RainResourceStatus.LOADING,
                    errorMessage = null,
                ),
                forecastFrame = settleCancelled(it.forecastFrame),
            )
        }

        forecastJob = viewModelScope.launch {
            try {
                val result = repository.loadForecastTimeline()
                if (generation != forecastGeneration) return@launch
                val firstFrame = result.value.frame(0)
                    ?: error("Rain forecast timeline did not provide an initial frame")
                forecastAcceptedAtEpochMs = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        forecast = RainResourceState(
                            status = RainResourceStatus.READY,
                            value = result.value,
                            isStale = result.isStale,
                            errorMessage = result.networkError,
                        ),
                        forecastFrame = RainResourceState(
                            status = RainResourceStatus.READY,
                            value = firstFrame,
                            isStale = result.isStale,
                            errorMessage = result.networkError,
                        ),
                        forecastFrameIndex = 0,
                    )
                }
                startForecastPrefetch(result.value, selectedIndex = 0)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation != forecastGeneration) return@launch
                _state.update {
                    val existing = it.forecast.value
                    it.copy(
                        forecast = if (existing != null) {
                            it.forecast.copy(
                                status = RainResourceStatus.READY,
                                isStale = true,
                                errorMessage = error.message ?: "Rain forecast unavailable",
                            )
                        } else {
                            RainResourceState(
                                status = RainResourceStatus.ERROR,
                                errorMessage = error.message ?: "Rain forecast unavailable",
                            )
                        },
                    )
                }
            }
        }
    }

    fun loadForecastFrame(frameIndex: Int) {
        val timeline = _state.value.forecast.value
        if (timeline == null) {
            _state.update {
                it.copy(
                    forecastFrame = RainResourceState(
                        status = RainResourceStatus.ERROR,
                        errorMessage = "尚未載入兩小時預報",
                    ),
                    forecastFrameIndex = frameIndex,
                )
            }
            return
        }
        require(frameIndex in timeline.frames.indices) { "Forecast frame index outside active timeline" }

        timeline.frame(frameIndex)?.let { frame ->
            forecastFrameGeneration += 1
            forecastFrameJob?.cancel()
            forecastFrameJob = null
            _state.update {
                it.copy(
                    forecastFrame = RainResourceState(
                        status = RainResourceStatus.READY,
                        value = frame,
                        isStale = it.forecast.isStale,
                        errorMessage = it.forecast.errorMessage,
                    ),
                    forecastFrameIndex = frameIndex,
                )
            }
            startForecastPrefetch(_state.value.forecast.value ?: timeline, frameIndex)
            return
        }

        forecastPrefetchGeneration += 1
        forecastPrefetchJob?.cancel()
        forecastPrefetchJob = null
        val generation = ++forecastFrameGeneration
        forecastFrameJob?.cancel()
        val previous = _state.value.forecastFrame
        _state.update {
            it.copy(
                forecastFrame = previous.copy(
                    status = RainResourceStatus.LOADING,
                    errorMessage = null,
                ),
                forecastFrameIndex = frameIndex,
            )
        }

        forecastFrameJob = viewModelScope.launch {
            try {
                val result = repository.loadForecastFrame(timeline, frameIndex)
                if (!isCurrentForecastFrameRequest(generation, timeline, frameIndex)) return@launch
                _state.update {
                    val currentTimeline = it.forecast.value ?: return@update it
                    it.copy(
                        forecast = it.forecast.copy(
                            value = currentTimeline.withLoadedFrame(result.value),
                        ),
                        forecastFrame = RainResourceState(
                            status = RainResourceStatus.READY,
                            value = result.value,
                            isStale = result.isStale,
                            errorMessage = result.networkError,
                        ),
                    )
                }
                _state.value.forecast.value?.let { updatedTimeline ->
                    startForecastPrefetch(updatedTimeline, frameIndex)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RainForecastRunChangedException) {
                if (!isCurrentForecastFrameRequest(generation, timeline, frameIndex)) return@launch
                refreshForecast()
            } catch (error: Throwable) {
                if (!isCurrentForecastFrameRequest(generation, timeline, frameIndex)) return@launch
                _state.update {
                    val existing = it.forecastFrame.value
                    it.copy(
                        forecastFrame = if (existing != null) {
                            it.forecastFrame.copy(
                                status = RainResourceStatus.READY,
                                isStale = true,
                                errorMessage = error.message ?: "Rain forecast frame unavailable",
                            )
                        } else {
                            RainResourceState(
                                status = RainResourceStatus.ERROR,
                                errorMessage = error.message ?: "Rain forecast frame unavailable",
                            )
                        },
                        forecastFrameIndex = existing?.frameIndex ?: frameIndex,
                    )
                }
            }
        }
    }

    fun cancelPointRefresh() {
        val cancelledActiveRefresh = pointJob?.isActive == true
        pointGeneration += 1
        pointJob?.cancel()
        pointJob = null
        if (cancelledActiveRefresh) pointLastAttemptEpochMs = null
        _state.update {
            if (it.pointForecast.status != RainResourceStatus.LOADING) return@update it
            val retained = it.pointForecast.value
            it.copy(
                pointForecast = if (retained != null) {
                    it.pointForecast.copy(
                        status = RainResourceStatus.READY,
                        errorMessage = null,
                    )
                } else {
                    RainResourceState()
                },
            )
        }
    }

    fun cancelForecastRequests() {
        val cancelledTimelineRefresh = forecastJob?.isActive == true
        forecastGeneration += 1
        forecastFrameGeneration += 1
        forecastPrefetchGeneration += 1
        forecastJob?.cancel()
        forecastFrameJob?.cancel()
        forecastPrefetchJob?.cancel()
        forecastJob = null
        forecastFrameJob = null
        forecastPrefetchJob = null
        if (cancelledTimelineRefresh) forecastLastAttemptEpochMs = null
        _state.update {
            val settledFrame = settleCancelled(it.forecastFrame)
            it.copy(
                forecast = settleCancelled(it.forecast),
                forecastFrame = settledFrame,
                forecastFrameIndex = settledFrame.value?.frameIndex ?: it.forecastFrameIndex,
            )
        }
    }

    fun cancelTransientRequests() {
        val cancelledPointRefresh = pointJob?.isActive == true
        val cancelledForecastRefresh = forecastJob?.isActive == true
        capabilitiesGeneration += 1
        pointGeneration += 1
        forecastGeneration += 1
        forecastFrameGeneration += 1
        forecastPrefetchGeneration += 1
        capabilitiesJob?.cancel()
        pointJob?.cancel()
        forecastJob?.cancel()
        forecastFrameJob?.cancel()
        forecastPrefetchJob?.cancel()
        capabilitiesJob = null
        pointJob = null
        forecastJob = null
        forecastFrameJob = null
        forecastPrefetchJob = null
        if (cancelledPointRefresh) pointLastAttemptEpochMs = null
        if (cancelledForecastRefresh) forecastLastAttemptEpochMs = null
        _state.update {
            val settledFrame = settleCancelled(it.forecastFrame)
            it.copy(
                capabilities = settleCancelled(it.capabilities),
                pointForecast = settleCancelled(it.pointForecast),
                forecast = settleCancelled(it.forecast),
                forecastFrame = settledFrame,
                forecastFrameIndex = settledFrame.value?.frameIndex ?: it.forecastFrameIndex,
            )
        }
    }

    fun clearCache() {
        capabilitiesGeneration += 1
        pointGeneration += 1
        forecastGeneration += 1
        forecastFrameGeneration += 1
        forecastPrefetchGeneration += 1
        capabilitiesJob?.cancel()
        pointJob?.cancel()
        forecastJob?.cancel()
        forecastFrameJob?.cancel()
        forecastPrefetchJob?.cancel()
        capabilitiesJob = null
        pointJob = null
        forecastJob = null
        forecastFrameJob = null
        forecastPrefetchJob = null
        pointAcceptedAtEpochMs = null
        pointLastAttemptEpochMs = null
        forecastAcceptedAtEpochMs = null
        forecastLastAttemptEpochMs = null
        _state.update {
            it.copy(
                capabilities = RainResourceState(),
                pointForecast = RainResourceState(),
                pointRequest = null,
                forecast = RainResourceState(),
                forecastFrame = RainResourceState(),
                forecastFrameIndex = null,
            )
        }
        viewModelScope.launch { repository.clearCache() }
    }

    private fun startForecastPrefetch(timeline: RainForecastTimeline, selectedIndex: Int) {
        if (timeline.source != RainForecastSource.SWIRLS) return
        if (timeline.loadedFrameCount >= timeline.frames.size) return

        forecastPrefetchJob?.cancel()
        val generation = ++forecastPrefetchGeneration
        val candidateIndexes = forecastPrefetchIndexes(
            frameCount = timeline.frames.size,
            selectedIndex = selectedIndex,
        )
        if (candidateIndexes.isEmpty()) {
            forecastPrefetchJob = null
            return
        }

        forecastPrefetchJob = viewModelScope.launch {
            try {
                coroutineScope {
                    candidateIndexes.map { frameIndex ->
                        async {
                            prefetchForecastFrame(
                                timeline = timeline,
                                frameIndex = frameIndex,
                                generation = generation,
                            )
                        }
                    }.awaitAll()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RainForecastRunChangedException) {
                if (generation == forecastPrefetchGeneration) refreshForecast()
            }
        }
    }

    private suspend fun prefetchForecastFrame(
        timeline: RainForecastTimeline,
        frameIndex: Int,
        generation: Long,
    ) {
        if (generation != forecastPrefetchGeneration) return
        val activeTimeline = _state.value.forecast.value ?: return
        if (!sameForecastTimeline(activeTimeline, timeline)) return
        if (activeTimeline.frame(frameIndex) != null) return

        try {
            val result = repository.loadForecastFrame(activeTimeline, frameIndex)
            if (generation != forecastPrefetchGeneration) return
            _state.update { currentState ->
                val currentTimeline = currentState.forecast.value ?: return@update currentState
                if (!sameForecastTimeline(currentTimeline, timeline)) return@update currentState
                val updatedTimeline = currentTimeline.withLoadedFrame(result.value)
                val selectedFrameWasWaiting =
                    currentState.forecastFrameIndex == frameIndex &&
                        currentState.forecastFrame.status == RainResourceStatus.LOADING
                currentState.copy(
                    forecast = currentState.forecast.copy(value = updatedTimeline),
                    forecastFrame = if (selectedFrameWasWaiting) {
                        RainResourceState(
                            status = RainResourceStatus.READY,
                            value = result.value,
                            isStale = result.isStale,
                            errorMessage = result.networkError,
                        )
                    } else {
                        currentState.forecastFrame
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RainForecastRunChangedException) {
            throw error
        } catch (_: Throwable) {
            // Prefetch is opportunistic. A foreground selection can retry this frame later.
        }
    }

    private fun isCurrentPointRequest(generation: Long, request: RainPointRequestKey): Boolean =
        generation == pointGeneration && _state.value.pointRequest == request

    private fun isCurrentForecastFrameRequest(
        generation: Long,
        timeline: RainForecastTimeline,
        frameIndex: Int,
    ): Boolean {
        val activeTimeline = _state.value.forecast.value ?: return false
        return generation == forecastFrameGeneration &&
            _state.value.forecastFrameIndex == frameIndex &&
            sameForecastTimeline(activeTimeline, timeline)
    }

    private fun <T> settleCancelled(resource: RainResourceState<T>): RainResourceState<T> {
        if (resource.status != RainResourceStatus.LOADING) return resource
        return if (resource.value != null) {
            resource.copy(status = RainResourceStatus.READY, errorMessage = null)
        } else {
            RainResourceState()
        }
    }

    companion object {
        val SUPPORTED_POINT_RADII_KM: Set<Int> = setOf(1, 2, 3, 5)
        const val DEFAULT_POINT_RADIUS_KM = 2
    }
}

internal fun sameRainLocation(
    first: LocationInfo,
    second: LocationInfo,
    epsilon: Double = 0.000001,
): Boolean =
    abs(first.latitude - second.latitude) <= epsilon &&
        abs(first.longitude - second.longitude) <= epsilon

internal fun sameForecastTimeline(
    first: RainForecastTimeline,
    second: RainForecastTimeline,
): Boolean = first.source == second.source && first.issueTime == second.issueTime
