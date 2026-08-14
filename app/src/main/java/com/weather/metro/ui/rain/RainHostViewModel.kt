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
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.domain.rain.RainPointForecast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private var capabilitiesGeneration = 0L
    private var pointGeneration = 0L
    private var forecastGeneration = 0L
    private var forecastFrameGeneration = 0L

    fun bindHostLocation(location: LocationInfo) {
        val current = _state.value.location
        if (current != null && sameRainLocation(current, location)) {
            if (current != location) _state.update { it.copy(location = location) }
            return
        }

        pointGeneration += 1
        pointJob?.cancel()
        pointJob = null
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

    fun refreshForecast() {
        val generation = ++forecastGeneration
        forecastFrameGeneration += 1
        forecastJob?.cancel()
        forecastFrameJob?.cancel()
        forecastFrameJob = null
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
            return
        }

        val generation = ++forecastFrameGeneration
        forecastFrameJob?.cancel()
        val previous = _state.value.forecastFrame.takeIf { _state.value.forecastFrameIndex == frameIndex }
            ?: RainResourceState()
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
                    )
                }
            }
        }
    }

    fun cancelPointRefresh() {
        pointGeneration += 1
        pointJob?.cancel()
        pointJob = null
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
        forecastGeneration += 1
        forecastFrameGeneration += 1
        forecastJob?.cancel()
        forecastFrameJob?.cancel()
        forecastJob = null
        forecastFrameJob = null
        _state.update {
            it.copy(
                forecast = settleCancelled(it.forecast),
                forecastFrame = settleCancelled(it.forecastFrame),
            )
        }
    }

    fun cancelTransientRequests() {
        capabilitiesGeneration += 1
        pointGeneration += 1
        forecastGeneration += 1
        forecastFrameGeneration += 1
        capabilitiesJob?.cancel()
        pointJob?.cancel()
        forecastJob?.cancel()
        forecastFrameJob?.cancel()
        capabilitiesJob = null
        pointJob = null
        forecastJob = null
        forecastFrameJob = null
        _state.update {
            it.copy(
                capabilities = settleCancelled(it.capabilities),
                pointForecast = settleCancelled(it.pointForecast),
                forecast = settleCancelled(it.forecast),
                forecastFrame = settleCancelled(it.forecastFrame),
            )
        }
    }

    fun clearCache() {
        capabilitiesGeneration += 1
        pointGeneration += 1
        forecastGeneration += 1
        forecastFrameGeneration += 1
        capabilitiesJob?.cancel()
        pointJob?.cancel()
        forecastJob?.cancel()
        forecastFrameJob?.cancel()
        capabilitiesJob = null
        pointJob = null
        forecastJob = null
        forecastFrameJob = null
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
