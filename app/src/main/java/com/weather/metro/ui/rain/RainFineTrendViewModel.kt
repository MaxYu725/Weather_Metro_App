package com.weather.metro.ui.rain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weather.metro.data.rain.RainForecastClient
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainFinePointSeries
import com.weather.metro.domain.rain.RainForecastRunChangedException
import com.weather.metro.domain.rain.RainForecastSource
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.domain.rain.buildFinePointSeries
import java.nio.charset.StandardCharsets
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

enum class RainFineTrendStatus {
    IDLE,
    LOADING,
    READY,
    ERROR,
}

data class RainFineTrendState(
    val status: RainFineTrendStatus = RainFineTrendStatus.IDLE,
    val series: RainFinePointSeries? = null,
    val loadedFrameCount: Int = 0,
    val totalFrameCount: Int = 0,
    val downloadedPayloadBytes: Long = 0L,
    val elapsedMillis: Long? = null,
    val errorMessage: String? = null,
)

/**
 * Explicit, foreground loader used only by the detailed two-hour forecast UI.
 *
 * It intentionally does not change RainHostViewModel's normal lazy-prefetch policy.
 * A user has to open the fine-trend panel before the remaining SWIRLS frames are
 * fetched. The loader keeps only the final point series in state; full frame grids
 * can be reclaimed after the request finishes.
 */
class RainFineTrendViewModel : ViewModel() {
    private val client = RainForecastClient()
    private val _state = MutableStateFlow(RainFineTrendState())
    val state: StateFlow<RainFineTrendState> = _state.asStateFlow()

    private var job: Job? = null
    private var generation = 0L
    private var activeRequestKey: String? = null

    fun load(timeline: RainForecastTimeline, location: LocationInfo) {
        val requestKey = buildRequestKey(timeline, location)
        val current = _state.value
        if (
            activeRequestKey == requestKey &&
            (current.status == RainFineTrendStatus.LOADING || current.status == RainFineTrendStatus.READY)
        ) {
            return
        }

        if (timeline.source != RainForecastSource.SWIRLS) {
            cancel()
            activeRequestKey = requestKey
            _state.value = RainFineTrendState(
                status = RainFineTrendStatus.ERROR,
                loadedFrameCount = timeline.loadedFrameCount,
                totalFrameCount = timeline.frames.size,
                errorMessage = "精細趨勢只適用於 SWIRLS 6 分鐘步進預報",
            )
            return
        }

        val requestGeneration = ++generation
        job?.cancel()
        activeRequestKey = requestKey
        _state.value = RainFineTrendState(
            status = RainFineTrendStatus.LOADING,
            loadedFrameCount = timeline.loadedFrameCount,
            totalFrameCount = timeline.frames.size,
        )

        job = viewModelScope.launch {
            val startedAt = System.nanoTime()
            var downloadedBytes = 0L
            var workingTimeline = timeline
            try {
                val missingIndexes = timeline.frames.indices.filter { timeline.frame(it) == null }
                for (chunk in missingIndexes.chunked(MAX_CONCURRENT_FRAME_LOADS)) {
                    val loaded = coroutineScope {
                        chunk.map { frameIndex ->
                            async {
                                val network = client.loadSwirlsFrame(frameIndex)
                                client.assertSwirlsFrameCompatible(timeline, network.value)
                                LoadedFineFrame(
                                    frameIndex = frameIndex,
                                    frame = network.value,
                                    payloadBytes = network.rawPayload
                                        .toByteArray(StandardCharsets.UTF_8)
                                        .size
                                        .toLong(),
                                )
                            }
                        }.awaitAll()
                    }

                    if (requestGeneration != generation) return@launch
                    loaded.sortedBy { it.frameIndex }.forEach { result ->
                        workingTimeline = workingTimeline.withLoadedFrame(result.frame)
                        downloadedBytes += result.payloadBytes
                    }
                    _state.update {
                        it.copy(
                            loadedFrameCount = workingTimeline.loadedFrameCount,
                            downloadedPayloadBytes = downloadedBytes,
                        )
                    }
                }

                if (requestGeneration != generation) return@launch
                val series = workingTimeline.buildFinePointSeries(
                    latitude = location.latitude,
                    longitude = location.longitude,
                ) ?: error("16 個 SWIRLS 時段未能建立所在地精細趨勢")
                val elapsed = elapsedMillis(startedAt)
                _state.value = RainFineTrendState(
                    status = RainFineTrendStatus.READY,
                    series = series,
                    loadedFrameCount = workingTimeline.loadedFrameCount,
                    totalFrameCount = workingTimeline.frames.size,
                    downloadedPayloadBytes = downloadedBytes,
                    elapsedMillis = elapsed,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RainForecastRunChangedException) {
                if (requestGeneration != generation) return@launch
                _state.value = RainFineTrendState(
                    status = RainFineTrendStatus.ERROR,
                    loadedFrameCount = workingTimeline.loadedFrameCount,
                    totalFrameCount = timeline.frames.size,
                    downloadedPayloadBytes = downloadedBytes,
                    elapsedMillis = elapsedMillis(startedAt),
                    errorMessage = "SWIRLS 預報剛更新，請重新整理後再載入精細趨勢",
                )
            } catch (error: Throwable) {
                if (requestGeneration != generation) return@launch
                _state.value = RainFineTrendState(
                    status = RainFineTrendStatus.ERROR,
                    loadedFrameCount = workingTimeline.loadedFrameCount,
                    totalFrameCount = timeline.frames.size,
                    downloadedPayloadBytes = downloadedBytes,
                    elapsedMillis = elapsedMillis(startedAt),
                    errorMessage = error.message ?: "精細趨勢暫時無法載入",
                )
            }
        }
    }

    fun cancel() {
        generation += 1
        job?.cancel()
        job = null
        if (_state.value.status == RainFineTrendStatus.LOADING) {
            _state.update {
                it.copy(
                    status = RainFineTrendStatus.IDLE,
                    errorMessage = null,
                )
            }
        }
    }

    private fun buildRequestKey(timeline: RainForecastTimeline, location: LocationInfo): String =
        "${timeline.source.name}|${timeline.issueTime}|${location.latitude}|${location.longitude}"

    private fun elapsedMillis(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

    private data class LoadedFineFrame(
        val frameIndex: Int,
        val frame: com.weather.metro.domain.rain.RainForecastFrame,
        val payloadBytes: Long,
    )

    companion object {
        private const val MAX_CONCURRENT_FRAME_LOADS = 3
    }
}
