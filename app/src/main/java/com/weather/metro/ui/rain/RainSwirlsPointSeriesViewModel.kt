package com.weather.metro.ui.rain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weather.metro.data.rain.RainForecastClient
import com.weather.metro.data.rain.RainHttpTransport
import com.weather.metro.data.rain.UrlConnectionRainTransport
import com.weather.metro.data.tools.ToolEndpoints
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainForecastFrame
import com.weather.metro.domain.rain.RainForecastRunChangedException
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.domain.rain.RainPointLocation
import com.weather.metro.domain.rain.RainSwirlsPointSample
import com.weather.metro.domain.rain.RainSwirlsPointSeries
import com.weather.metro.domain.rain.buildFinePointSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import kotlin.math.abs

data class RainSwirlsPointSeriesState(
    val location: LocationInfo? = null,
    val resource: RainResourceState<RainSwirlsPointSeries> = RainResourceState(),
    val loadedFrameCount: Int = 0,
    val totalFrameCount: Int = EXPECTED_FRAME_COUNT,
    val downloadedPayloadBytes: Long = 0L,
)

/**
 * Current-page 6-minute-cadence rainfall trend.
 *
 * Phase 3C originally asked the Rain Worker to synchronously aggregate all 16
 * SWIRLS grids into one compact response. Production testing showed that a
 * single slow HKO MDL could make that all-or-nothing Worker request fail even
 * though the normal per-frame endpoints remained usable.
 *
 * This owner therefore uses the same proven per-frame API as the detailed
 * forecast. Successful frames are retained in memory across retries; one slow
 * frame no longer discards the other 15. Nothing is persisted to disk, so a
 * failed state cannot survive an app reinstall/restart as stale application
 * state. The published values remain 30-minute rolling accumulations sampled
 * at six-minute cadence.
 */
class RainSwirlsPointSeriesViewModel : ViewModel() {
    private val transport: RainHttpTransport = UrlConnectionRainTransport()
    private val forecastClient = RainForecastClient()
    private val _state = MutableStateFlow(RainSwirlsPointSeriesState())
    val state: StateFlow<RainSwirlsPointSeriesState> = _state.asStateFlow()

    private var timeline: RainForecastTimeline? = null
    private var job: Job? = null
    private var retryJob: Job? = null
    private var generation = 0L
    private var acceptedAtEpochMs: Long? = null
    private var lastAttemptEpochMs: Long? = null
    private var consecutiveFailures = 0

    fun bindLocation(location: LocationInfo) {
        val previousLocation = _state.value.location
        if (previousLocation != null && samePointSeriesLocation(previousLocation, location)) {
            if (previousLocation != location) _state.value = _state.value.copy(location = location)
            return
        }

        _state.value = _state.value.copy(location = location)
        val activeTimeline = timeline
        if (activeTimeline != null && activeTimeline.loadedFrameCount == activeTimeline.frames.size) {
            val rebuilt = buildLocalSwirlsPointSeries(activeTimeline, location)
            if (rebuilt != null) {
                _state.value = _state.value.copy(
                    resource = RainResourceState(
                        status = RainResourceStatus.READY,
                        value = rebuilt,
                        isStale = acceptedAtEpochMs == null,
                    ),
                    loadedFrameCount = activeTimeline.loadedFrameCount,
                    totalFrameCount = activeTimeline.frames.size,
                )
                return
            }
        }

        // A materially different point must not show a series calculated for
        // the old point. The already downloaded grids remain reusable.
        if (previousLocation != null) {
            _state.value = _state.value.copy(resource = RainResourceState())
            acceptedAtEpochMs = null
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
            var downloadedBytes = 0L
            try {
                var workingTimeline = refreshFrameZero(requestGeneration)
                if (requestGeneration != generation) return@launch
                timeline = workingTimeline
                publishProgress(workingTimeline, downloadedBytes)

                val missingIndexes = workingTimeline.frames.indices.filter { workingTimeline.frame(it) == null }
                for (chunk in missingIndexes.chunked(MAX_CONCURRENT_FRAME_LOADS)) {
                    val attempts = coroutineScope {
                        chunk.map { frameIndex ->
                            async { loadFrameAttempt(frameIndex) }
                        }.awaitAll()
                    }
                    if (requestGeneration != generation) return@launch

                    var runChanged: RainForecastRunChangedException? = null
                    attempts.forEach { attempt ->
                        when (attempt) {
                            is FrameAttempt.Success -> {
                                try {
                                    forecastClient.assertSwirlsFrameCompatible(workingTimeline, attempt.frame)
                                    workingTimeline = workingTimeline.withLoadedFrame(attempt.frame)
                                    downloadedBytes += attempt.payloadBytes
                                } catch (error: RainForecastRunChangedException) {
                                    runChanged = error
                                }
                            }
                            is FrameAttempt.Failure -> {
                                if (attempt.error is RainForecastRunChangedException) {
                                    runChanged = attempt.error
                                }
                            }
                        }
                    }
                    runChanged?.let { throw it }
                    timeline = workingTimeline
                    publishProgress(workingTimeline, downloadedBytes)
                }

                if (requestGeneration != generation) return@launch
                val latestLocation = _state.value.location ?: location
                val series = buildLocalSwirlsPointSeries(workingTimeline, latestLocation)
                if (series == null) {
                    throw IncompleteFineSeriesException(
                        loaded = workingTimeline.loadedFrameCount,
                        total = workingTimeline.frames.size,
                    )
                }

                timeline = workingTimeline
                acceptedAtEpochMs = System.currentTimeMillis()
                consecutiveFailures = 0
                _state.value = _state.value.copy(
                    resource = RainResourceState(
                        status = RainResourceStatus.READY,
                        value = series,
                        isStale = false,
                    ),
                    loadedFrameCount = workingTimeline.loadedFrameCount,
                    totalFrameCount = workingTimeline.frames.size,
                    downloadedPayloadBytes = downloadedBytes,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RainForecastRunChangedException) {
                if (requestGeneration != generation) return@launch
                timeline = null
                consecutiveFailures += 1
                settleFailure(
                    message = "SWIRLS 預報剛更新，正在重新同步精細時段",
                    loaded = 0,
                    total = EXPECTED_FRAME_COUNT,
                    downloadedBytes = downloadedBytes,
                )
                scheduleRetry(requestGeneration)
            } catch (error: Throwable) {
                if (requestGeneration != generation) return@launch
                consecutiveFailures += 1
                val activeTimeline = timeline
                settleFailure(
                    message = error.message ?: "精細降雨趨勢暫時無法完成",
                    loaded = activeTimeline?.loadedFrameCount ?: 0,
                    total = activeTimeline?.frames?.size ?: EXPECTED_FRAME_COUNT,
                    downloadedBytes = downloadedBytes,
                )
                scheduleRetry(requestGeneration)
            } finally {
                if (requestGeneration == generation) job = null
            }
        }
    }

    fun cancel() {
        generation += 1
        job?.cancel()
        retryJob?.cancel()
        job = null
        retryJob = null
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

    private suspend fun refreshFrameZero(requestGeneration: Long): RainForecastTimeline {
        val first = loadFrameAttempt(0)
        if (requestGeneration != generation) throw CancellationException("fine trend superseded")
        val frame = when (first) {
            is FrameAttempt.Success -> first.frame
            is FrameAttempt.Failure -> throw first.error
        }
        val existing = timeline
        return if (existing == null || existing.issueTime != frame.runTime) {
            forecastClient.buildSwirlsTimeline(frame)
        } else {
            forecastClient.assertSwirlsFrameCompatible(existing, frame)
            existing.withLoadedFrame(frame)
        }
    }

    private suspend fun loadFrameAttempt(frameIndex: Int): FrameAttempt {
        return try {
            val payload = transport.get(
                ToolEndpoints.rainSwirlsFrame(frameIndex),
                connectTimeoutMs = FRAME_CONNECT_TIMEOUT_MS,
                readTimeoutMs = FRAME_READ_TIMEOUT_MS,
            )
            val frame = forecastClient.parseSwirlsFrame(payload)
            FrameAttempt.Success(
                frame = frame,
                payloadBytes = payload.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            FrameAttempt.Failure(frameIndex, error)
        }
    }

    private fun publishProgress(activeTimeline: RainForecastTimeline, downloadedBytes: Long) {
        _state.value = _state.value.copy(
            loadedFrameCount = activeTimeline.loadedFrameCount,
            totalFrameCount = activeTimeline.frames.size,
            downloadedPayloadBytes = downloadedBytes,
        )
    }

    private fun settleFailure(
        message: String,
        loaded: Int,
        total: Int,
        downloadedBytes: Long,
    ) {
        val retained = _state.value.resource.value
        _state.value = _state.value.copy(
            resource = if (retained != null) {
                RainResourceState(
                    status = RainResourceStatus.READY,
                    value = retained,
                    isStale = true,
                    errorMessage = message,
                )
            } else {
                RainResourceState(
                    status = RainResourceStatus.ERROR,
                    errorMessage = message,
                )
            },
            loadedFrameCount = loaded,
            totalFrameCount = total,
            downloadedPayloadBytes = downloadedBytes,
        )
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

    private sealed interface FrameAttempt {
        data class Success(
            val frame: RainForecastFrame,
            val payloadBytes: Long,
        ) : FrameAttempt

        data class Failure(
            val frameIndex: Int,
            val error: Throwable,
        ) : FrameAttempt
    }

    private class IncompleteFineSeriesException(loaded: Int, total: Int) :
        IllegalStateException("精細降雨已載入 $loaded/$total，稍後自動補回缺少時段")

    private companion object {
        const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        const val RETRY_COOLDOWN_MS = 15 * 1000L
        const val FRAME_CONNECT_TIMEOUT_MS = 8_000
        const val FRAME_READ_TIMEOUT_MS = 15_000
        const val MAX_CONCURRENT_FRAME_LOADS = 3
    }
}

internal fun buildLocalSwirlsPointSeries(
    timeline: RainForecastTimeline,
    location: LocationInfo,
): RainSwirlsPointSeries? {
    val fine = timeline.buildFinePointSeries(location.latitude, location.longitude) ?: return null
    val samples = fine.rollingSamples.mapIndexed { frameIndex, sample ->
        RainSwirlsPointSample(
            frameIndex = frameIndex,
            validTime = sample.validTime,
            leadMinutes = sample.leadMinutes,
            windowStart = sample.windowStart,
            windowEnd = sample.windowEnd,
            accumulationMm = sample.accumulationMm,
            // The Current tile does not surface spatial spread. The rainfall
            // value itself still comes from the same four-grid bilinear sample.
            spatialSpreadMm = 0.0,
        )
    }
    val peak = samples.maxByOrNull { it.accumulationMm } ?: return null
    val firstWet = samples.firstOrNull { it.accumulationMm >= 0.05 }
    return RainSwirlsPointSeries(
        workerVersion = "android-frame-series",
        runTime = timeline.issueTime,
        cadenceMinutes = timeline.cadenceMinutes,
        accumulationMinutes = timeline.accumulationMinutes,
        unit = timeline.unit,
        location = RainPointLocation(location.latitude, location.longitude),
        interpolation = "bilinear-four-grid-points-local",
        sampleCount = samples.size,
        peakAccumulationMm = peak.accumulationMm,
        peakLeadMinutes = peak.leadMinutes,
        firstWetLeadMinutes = firstWet?.leadMinutes,
        samples = samples,
    )
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

private const val EXPECTED_FRAME_COUNT = 16
