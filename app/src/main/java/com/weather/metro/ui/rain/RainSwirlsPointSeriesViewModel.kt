package com.weather.metro.ui.rain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weather.metro.data.rain.RainSwirlsPointSeriesClient
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainSwirlsPointSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

data class RainSwirlsPointSeriesState(
    val location: LocationInfo? = null,
    val resource: RainResourceState<RainSwirlsPointSeries> = RainResourceState(),
)

class RainSwirlsPointSeriesViewModel : ViewModel() {
    private val client = RainSwirlsPointSeriesClient()
    private val _state = MutableStateFlow(RainSwirlsPointSeriesState())
    val state: StateFlow<RainSwirlsPointSeriesState> = _state.asStateFlow()

    private var job: Job? = null
    private var generation = 0L
    private var acceptedAtEpochMs: Long? = null
    private var lastAttemptEpochMs: Long? = null

    fun bindLocation(location: LocationInfo) {
        val current = _state.value.location
        if (current != null && samePointSeriesLocation(current, location)) {
            if (current != location) _state.value = _state.value.copy(location = location)
            return
        }
        generation += 1
        job?.cancel()
        job = null
        acceptedAtEpochMs = null
        lastAttemptEpochMs = null
        _state.value = RainSwirlsPointSeriesState(location = location)
    }

    fun refreshIfStale(nowEpochMs: Long = System.currentTimeMillis()) {
        val current = _state.value
        if (current.location == null || current.resource.status == RainResourceStatus.LOADING) return
        val acceptedAt = acceptedAtEpochMs
        val staleByAge = acceptedAt == null || nowEpochMs - acceptedAt >= REFRESH_INTERVAL_MS
        val retryAllowed = lastAttemptEpochMs?.let { nowEpochMs - it >= RETRY_COOLDOWN_MS } ?: true
        if (
            current.resource.status == RainResourceStatus.IDLE ||
            current.resource.status == RainResourceStatus.ERROR ||
            current.resource.isStale ||
            staleByAge
        ) {
            if (retryAllowed) refresh()
        }
    }

    fun refresh() {
        val location = _state.value.location ?: return
        val requestGeneration = ++generation
        job?.cancel()
        lastAttemptEpochMs = System.currentTimeMillis()
        val previous = _state.value.resource
        _state.value = _state.value.copy(
            resource = previous.copy(status = RainResourceStatus.LOADING, errorMessage = null),
        )
        job = viewModelScope.launch {
            try {
                val result = client.load(location.latitude, location.longitude)
                if (requestGeneration != generation) return@launch
                acceptedAtEpochMs = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    resource = RainResourceState(
                        status = RainResourceStatus.READY,
                        value = result.value,
                        isStale = false,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestGeneration != generation) return@launch
                val retained = _state.value.resource.value
                _state.value = _state.value.copy(
                    resource = if (retained != null) {
                        RainResourceState(
                            status = RainResourceStatus.READY,
                            value = retained,
                            isStale = true,
                            errorMessage = error.message ?: "精細降雨趨勢暫時無法更新",
                        )
                    } else {
                        RainResourceState(
                            status = RainResourceStatus.ERROR,
                            errorMessage = error.message ?: "精細降雨趨勢暫時無法取得",
                        )
                    },
                )
            }
        }
    }

    fun cancel() {
        generation += 1
        job?.cancel()
        job = null
        if (_state.value.resource.status == RainResourceStatus.LOADING) {
            val retained = _state.value.resource.value
            _state.value = _state.value.copy(
                resource = if (retained != null) {
                    _state.value.resource.copy(status = RainResourceStatus.READY, errorMessage = null)
                } else {
                    RainResourceState()
                },
            )
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        const val RETRY_COOLDOWN_MS = 60 * 1000L
    }
}

internal fun samePointSeriesLocation(
    first: LocationInfo,
    second: LocationInfo,
    epsilon: Double = 0.00002,
): Boolean =
    abs(first.latitude - second.latitude) <= epsilon &&
        abs(first.longitude - second.longitude) <= epsilon
