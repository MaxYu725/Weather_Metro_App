package com.weather.metro.ui.rain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import java.time.Instant
import kotlin.math.abs

data class RainSwirlsPointSeriesState(
    val location: LocationInfo? = null,
    val resource: RainResourceState<RainSwirlsPointSeries> = RainResourceState(),
)

/**
 * Optional 6-minute-cadence enhancement for Current.
 *
 * The startup-critical forecast remains RainHostViewModel's fast `/api/rain/point`
 * request. This owner only asks the Worker for an already-complete prebuilt
 * SWIRLS snapshot. The endpoint never waits for sixteen live HKO frames: a
 * missing/stale snapshot fails quickly and Current simply keeps the point
 * forecast without exposing a loading/error state.
 */
class RainSwirlsPointSeriesViewModel : ViewModel() {
    private val client = RainSwirlsPointSeriesClient()
    private val _state = MutableStateFlow(RainSwirlsPointSeriesState())
    val state: StateFlow<RainSwirlsPointSeriesState> = _state.asStateFlow()

    private var job: Job? = null
    private var retryJob: Job? = null
    private var generation = 0L
    private var acceptedAtEpochMs: Long? = null
    private var lastAttemptEpochMs: Long? = null

    fun bindLocation(location: LocationInfo) {
        val previous = _state.value.location
        if (previous != null && samePointSeriesLocation(previous, location)) {
            if (previous != location) _state.value = _state.value.copy(location = location)
            return
        }

        generation += 1
        job?.cancel()
        retryJob?.cancel()
        job = null
        retryJob = null
        acceptedAtEpochMs = null
        lastAttemptEpochMs = null
        _state.value = RainSwirlsPointSeriesState(location = location)
    }

    fun refreshIfStale(nowEpochMs: Long = System.currentTimeMillis()) {
        if (_state.value.location == null || job?.isActive == true) return
        expireFineSeriesIfTooOld(nowEpochMs)
        val acceptedAt = acceptedAtEpochMs
        if (acceptedAt != null && nowEpochMs - acceptedAt < REFRESH_INTERVAL_MS) return
        val lastAttempt = lastAttemptEpochMs
        if (lastAttempt != null && nowEpochMs - lastAttempt < RETRY_INTERVAL_MS) return
        refreshSilently()
    }

    /** Explicit hook retained for lifecycle/tests; it is intentionally silent. */
    fun refresh() = refreshSilently()

    fun cancel() {
        generation += 1
        job?.cancel()
        retryJob?.cancel()
        job = null
        retryJob = null
    }

    private fun refreshSilently() {
        val location = _state.value.location ?: return
        if (job?.isActive == true) return
        retryJob?.cancel()
        retryJob = null
        val requestGeneration = ++generation
        lastAttemptEpochMs = System.currentTimeMillis()

        job = viewModelScope.launch {
            try {
                val result = client.load(location.latitude, location.longitude).value
                if (requestGeneration != generation) return@launch
                val latestLocation = _state.value.location ?: return@launch
                if (!samePointSeriesLocation(latestLocation, location)) return@launch

                acceptedAtEpochMs = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    resource = RainResourceState(
                        status = RainResourceStatus.READY,
                        value = result,
                        isStale = false,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (requestGeneration != generation) return@launch
                expireFineSeriesIfTooOld()
                scheduleSilentRetry(requestGeneration)
            } finally {
                if (requestGeneration == generation) job = null
            }
        }
    }

    private fun expireFineSeriesIfTooOld(nowEpochMs: Long = System.currentTimeMillis()) {
        val series = _state.value.resource.value ?: return
        if (!fineSeriesSourceExpired(series.runTime, nowEpochMs)) return
        acceptedAtEpochMs = null
        _state.value = _state.value.copy(resource = RainResourceState())
    }

    private fun scheduleSilentRetry(requestGeneration: Long) {
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            delay(RETRY_INTERVAL_MS)
            if (requestGeneration != generation) return@launch
            retryJob = null
            refreshIfStale()
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        const val RETRY_INTERVAL_MS = 60 * 1000L
    }
}

internal fun samePointSeriesLocation(
    first: LocationInfo,
    second: LocationInfo,
    epsilon: Double = 0.0005,
): Boolean =
    abs(first.latitude - second.latitude) <= epsilon &&
        abs(first.longitude - second.longitude) <= epsilon

internal fun fineSeriesSourceExpired(
    runTime: String,
    nowEpochMs: Long,
    maxAgeMs: Long = 18 * 60 * 1000L,
): Boolean {
    val sourceEpochMs = runCatching { Instant.parse(runTime).toEpochMilli() }.getOrNull() ?: return true
    return nowEpochMs - sourceEpochMs > maxAgeMs
}

internal fun pointSeriesRetryDelayMs(consecutiveFailures: Int): Long {
    @Suppress("UNUSED_VARIABLE")
    val ignoredFailures = consecutiveFailures
    return 60_000L
}
