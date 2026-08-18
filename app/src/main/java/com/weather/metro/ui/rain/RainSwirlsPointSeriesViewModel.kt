package com.weather.metro.ui.rain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weather.metro.data.rain.RainCache
import com.weather.metro.data.rain.RainSwirlsPointSeriesClient
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainSwirlsPointSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

data class RainSwirlsPointSeriesState(
    val location: LocationInfo? = null,
    val resource: RainResourceState<RainSwirlsPointSeries> = RainResourceState(),
)

class RainSwirlsPointSeriesViewModel(application: Application) : AndroidViewModel(application) {
    private val client = RainSwirlsPointSeriesClient()
    private val cache = RainCache(application)
    private val _state = MutableStateFlow(RainSwirlsPointSeriesState())
    val state: StateFlow<RainSwirlsPointSeriesState> = _state.asStateFlow()

    private var restoreJob: Job? = null
    private var job: Job? = null
    private var retryJob: Job? = null
    private var generation = 0L
    private var acceptedAtEpochMs: Long? = null
    private var lastAttemptEpochMs: Long? = null
    private var consecutiveFailures = 0

    fun bindLocation(location: LocationInfo) {
        val current = _state.value.location
        if (current != null && samePointSeriesLocation(current, location)) {
            if (current != location) _state.value = _state.value.copy(location = location)
            return
        }

        val restoreGeneration = ++generation
        restoreJob?.cancel()
        job?.cancel()
        retryJob?.cancel()
        restoreJob = null
        job = null
        retryJob = null
        acceptedAtEpochMs = null
        lastAttemptEpochMs = null
        consecutiveFailures = 0
        _state.value = RainSwirlsPointSeriesState(
            location = location,
            resource = RainResourceState(status = RainResourceStatus.LOADING),
        )

        restoreJob = viewModelScope.launch {
            val cached = runCatching {
                cache.readSwirlsPoint(location.latitude, location.longitude)
                    ?.let { client.parse(it) }
            }.getOrNull()
            if (restoreGeneration != generation) return@launch

            _state.value = _state.value.copy(
                resource = if (cached != null) {
                    RainResourceState(
                        status = RainResourceStatus.READY,
                        value = cached,
                        isStale = true,
                    )
                } else {
                    RainResourceState()
                },
            )
            restoreJob = null
            refresh()
        }
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
        retryJob?.cancel()
        retryJob = null
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
                consecutiveFailures = 0
                runCatching {
                    cache.writeSwirlsPoint(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        payload = result.rawPayload,
                    )
                }
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
                consecutiveFailures += 1
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
                scheduleRetry(requestGeneration)
            } finally {
                if (requestGeneration == generation) job = null
            }
        }
    }

    fun cancel() {
        generation += 1
        restoreJob?.cancel()
        job?.cancel()
        retryJob?.cancel()
        restoreJob = null
        job = null
        retryJob = null
        consecutiveFailures = 0
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

    private fun scheduleRetry(requestGeneration: Long) {
        retryJob?.cancel()
        val delayMs = pointSeriesRetryDelayMs(consecutiveFailures)
        retryJob = viewModelScope.launch {
            delay(delayMs)
            if (requestGeneration != generation) return@launch
            val resource = _state.value.resource
            if (resource.status == RainResourceStatus.ERROR || resource.isStale) {
                retryJob = null
                refresh()
            }
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        const val RETRY_COOLDOWN_MS = 15 * 1000L
    }
}

internal fun samePointSeriesLocation(
    first: LocationInfo,
    second: LocationInfo,
    epsilon: Double = 0.0005,
): Boolean =
    abs(first.latitude - second.latitude) <= epsilon &&
        abs(first.longitude - second.longitude) <= epsilon

internal fun pointSeriesRetryDelayMs(consecutiveFailures: Int): Long = when {
    consecutiveFailures <= 1 -> 15_000L
    consecutiveFailures == 2 -> 30_000L
    else -> 60_000L
}
