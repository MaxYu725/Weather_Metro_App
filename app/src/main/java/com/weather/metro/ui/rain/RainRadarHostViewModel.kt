package com.weather.metro.ui.rain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weather.metro.data.rain.RainCache
import com.weather.metro.data.rain.RainRepository
import com.weather.metro.data.rain.RainTrackClient
import com.weather.metro.data.tools.RainRadarMode
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainRadarContract
import com.weather.metro.domain.rain.RainRadarFrame
import com.weather.metro.domain.rain.RainRadarTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class RainRadarHostState(
    val location: LocationInfo? = null,
    val contract: RainResourceState<RainRadarContract> = RainResourceState(),
    val timeline: RainResourceState<RainRadarTimeline> = RainResourceState(),
    val selectedFrameIndex: Int? = null,
) {
    val selectedFrame: RainRadarFrame?
        get() = timeline.value?.frames?.getOrNull(selectedFrameIndex ?: -1)
}

class RainRadarHostViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RainRepository(
        client = RainTrackClient(),
        cache = RainCache(application),
    )

    private val _state = MutableStateFlow(RainRadarHostState())
    val state: StateFlow<RainRadarHostState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshGeneration = 0L

    fun bindHostLocation(location: LocationInfo) {
        val current = _state.value.location
        if (current != null && sameRadarLocation(current, location)) {
            if (current != location) _state.update { it.copy(location = location) }
            return
        }
        _state.update { it.copy(location = location) }
    }

    fun refreshRadar(
        rangeKm: Int = DEFAULT_RANGE_KM,
        heightKm: Int = DEFAULT_HEIGHT_KM,
        mode: RainRadarMode = RainRadarMode.LIVE,
    ) {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        val previousContract = _state.value.contract
        val previousTimeline = _state.value.timeline
        _state.update {
            it.copy(
                contract = previousContract.copy(
                    status = if (previousContract.value == null) RainResourceStatus.LOADING else previousContract.status,
                    errorMessage = null,
                ),
                timeline = previousTimeline.copy(
                    status = RainResourceStatus.LOADING,
                    errorMessage = null,
                ),
            )
        }

        refreshJob = viewModelScope.launch {
            try {
                val contract = repository.loadRadarContract().value
                require(rangeKm in contract.rangesKm) { "Radar range $rangeKm km is unavailable" }
                require(contract.supports(rangeKm, heightKm)) {
                    "Radar product $rangeKm km / $heightKm km is unavailable"
                }
                require(mode.wireValue in contract.modes) { "Radar mode ${mode.wireValue} is unavailable" }

                val timeline = repository.loadRadarTimeline(rangeKm, heightKm, mode).value
                if (generation != refreshGeneration) return@launch
                _state.update {
                    it.copy(
                        contract = RainResourceState(
                            status = RainResourceStatus.READY,
                            value = contract,
                        ),
                        timeline = RainResourceState(
                            status = RainResourceStatus.READY,
                            value = timeline,
                        ),
                        selectedFrameIndex = timeline.frames.lastIndex,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation != refreshGeneration) return@launch
                _state.update { current ->
                    val retainedContract = current.contract.value ?: previousContract.value
                    val retainedTimeline = current.timeline.value ?: previousTimeline.value
                    current.copy(
                        contract = if (retainedContract != null) {
                            RainResourceState(
                                status = RainResourceStatus.READY,
                                value = retainedContract,
                                isStale = true,
                                errorMessage = error.message ?: "Radar contract unavailable",
                            )
                        } else {
                            RainResourceState(
                                status = RainResourceStatus.ERROR,
                                errorMessage = error.message ?: "Radar contract unavailable",
                            )
                        },
                        timeline = if (retainedTimeline != null) {
                            RainResourceState(
                                status = RainResourceStatus.READY,
                                value = retainedTimeline,
                                isStale = true,
                                errorMessage = error.message ?: "Radar unavailable",
                            )
                        } else {
                            RainResourceState(
                                status = RainResourceStatus.ERROR,
                                errorMessage = error.message ?: "Radar unavailable",
                            )
                        },
                        selectedFrameIndex = retainedTimeline?.frames?.lastIndex,
                    )
                }
            }
        }
    }

    fun selectFrame(index: Int) {
        val timeline = _state.value.timeline.value ?: return
        require(index in timeline.frames.indices) { "Radar frame index outside active timeline" }
        _state.update { it.copy(selectedFrameIndex = index) }
    }

    fun cancelRequests() {
        refreshGeneration += 1
        refreshJob?.cancel()
        refreshJob = null
        _state.update {
            it.copy(
                contract = settleRadarCancelled(it.contract),
                timeline = settleRadarCancelled(it.timeline),
            )
        }
    }

    companion object {
        const val DEFAULT_RANGE_KM = 64
        const val DEFAULT_HEIGHT_KM = 3
    }
}

private fun sameRadarLocation(left: LocationInfo, right: LocationInfo): Boolean =
    abs(left.latitude - right.latitude) <= 0.000001 &&
        abs(left.longitude - right.longitude) <= 0.000001

private fun <T> settleRadarCancelled(resource: RainResourceState<T>): RainResourceState<T> {
    if (resource.status != RainResourceStatus.LOADING) return resource
    return if (resource.value != null) {
        resource.copy(status = RainResourceStatus.READY, errorMessage = null)
    } else {
        RainResourceState()
    }
}
