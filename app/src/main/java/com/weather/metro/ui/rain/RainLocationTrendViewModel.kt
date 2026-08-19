package com.weather.metro.ui.rain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weather.metro.data.rain.RainSwirlsPointClient
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainLocationTrendSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val LOCATION_TREND_FRAME_COUNT = 16
private const val MAX_CONSECUTIVE_LOCATION_TREND_FAILURES = 2

/**
 * Progressive Current-only SWIRLS state.
 *
 * This state is deliberately independent from [RainHostViewModel]. In particular it does not own,
 * start, cancel, or wait for the two-hour Forecast timeline/prefetch jobs. A partially populated
 * [samples] list is valid and immediately usable by a future Current UI enhancement.
 */
data class RainLocationTrendState(
    val location: LocationInfo? = null,
    val samples: List<RainLocationTrendSample> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasUsableSamples: Boolean
        get() = samples.isNotEmpty()
}

internal fun interface RainLocationTrendSampleSource {
    suspend fun load(
        frameIndex: Int,
        latitude: Double,
        longitude: Double,
    ): RainLocationTrendSample
}

/**
 * Loads native SWIRLS frames one at a time and publishes each valid point sample as soon as it is
 * available. Completion of all 16 frames is never a prerequisite for usable output.
 *
 * One isolated frame failure is skipped so later samples can still arrive. Two consecutive failures
 * stop the enhancement early to avoid hammering an unhealthy upstream. If the HKO forecast run
 * changes midway, samples from the old run are discarded and collection restarts once from frame 0.
 */
internal suspend fun loadLocationTrendProgressively(
    source: RainLocationTrendSampleSource,
    latitude: Double,
    longitude: Double,
    onReset: () -> Unit,
    onSample: (RainLocationTrendSample) -> Unit,
): String? {
    var expectedRunTime: String? = null
    var frameIndex = 0
    var restartCount = 0
    var consecutiveFailures = 0
    var firstFailureMessage: String? = null

    while (frameIndex < LOCATION_TREND_FRAME_COUNT) {
        val sample = try {
            source.load(frameIndex, latitude, longitude)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (firstFailureMessage == null) {
                firstFailureMessage = error.message ?: "Location rain trend sample unavailable"
            }
            consecutiveFailures += 1
            if (consecutiveFailures >= MAX_CONSECUTIVE_LOCATION_TREND_FAILURES) {
                return firstFailureMessage
            }
            frameIndex += 1
            continue
        }

        require(sample.frameIndex == frameIndex) {
            "Location rain trend returned frame ${sample.frameIndex} while requesting $frameIndex"
        }
        require(abs(sample.latitude - latitude) <= 0.000001) {
            "Location rain trend latitude mismatch"
        }
        require(abs(sample.longitude - longitude) <= 0.000001) {
            "Location rain trend longitude mismatch"
        }

        val sampleRunTime = sample.runTime
        if (expectedRunTime != null && sampleRunTime != null && sampleRunTime != expectedRunTime) {
            if (restartCount >= 1) {
                return "SWIRLS forecast run changed repeatedly during location trend loading"
            }
            restartCount += 1
            expectedRunTime = null
            consecutiveFailures = 0
            firstFailureMessage = null
            onReset()
            frameIndex = 0
            continue
        }

        if (expectedRunTime == null && sampleRunTime != null) expectedRunTime = sampleRunTime
        consecutiveFailures = 0
        onSample(sample)
        frameIndex += 1
    }

    return firstFailureMessage
}

class RainLocationTrendViewModel : ViewModel() {
    private val client = RainSwirlsPointClient()
    private val source = RainLocationTrendSampleSource { frameIndex, latitude, longitude ->
        client.loadSample(frameIndex, latitude, longitude).value
    }

    private val _state = MutableStateFlow(RainLocationTrendState())
    val state: StateFlow<RainLocationTrendState> = _state.asStateFlow()

    private var locationTrendJob: Job? = null
    private var generation = 0L

    fun bindHostLocation(location: LocationInfo) {
        val current = _state.value.location
        if (current != null && sameTrendLocation(current, location)) {
            if (current != location) _state.update { it.copy(location = location) }
            return
        }

        generation += 1
        locationTrendJob?.cancel()
        locationTrendJob = null
        _state.value = RainLocationTrendState(location = location)
    }

    fun refreshIfNeeded() {
        val current = _state.value
        if (current.location == null || current.isRefreshing) return
        if (current.samples.size >= LOCATION_TREND_FRAME_COUNT) return
        refresh()
    }

    fun refresh() {
        val location = _state.value.location ?: return
        val requestGeneration = ++generation
        locationTrendJob?.cancel()
        _state.update {
            it.copy(
                isRefreshing = true,
                errorMessage = null,
            )
        }

        locationTrendJob = viewModelScope.launch {
            try {
                val warning = loadLocationTrendProgressively(
                    source = source,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    onReset = {
                        if (requestGeneration == generation) {
                            _state.update { state -> state.copy(samples = emptyList()) }
                        }
                    },
                    onSample = { sample ->
                        if (requestGeneration == generation) {
                            _state.update { state ->
                                state.copy(samples = mergeTrendSample(state.samples, sample))
                            }
                        }
                    },
                )
                if (requestGeneration != generation) return@launch
                _state.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = warning,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestGeneration != generation) return@launch
                _state.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = error.message ?: "Location rain trend unavailable",
                    )
                }
            }
        }
    }

    fun cancelRefresh() {
        generation += 1
        locationTrendJob?.cancel()
        locationTrendJob = null
        _state.update { it.copy(isRefreshing = false) }
    }

    private fun sameTrendLocation(left: LocationInfo, right: LocationInfo): Boolean =
        abs(left.latitude - right.latitude) <= 0.000001 &&
            abs(left.longitude - right.longitude) <= 0.000001
}

internal fun mergeTrendSample(
    samples: List<RainLocationTrendSample>,
    sample: RainLocationTrendSample,
): List<RainLocationTrendSample> =
    (samples.filterNot { it.frameIndex == sample.frameIndex } + sample)
        .sortedBy { it.frameIndex }
