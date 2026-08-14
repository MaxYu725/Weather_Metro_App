package com.weather.metro.ui.rain

import android.app.Application
import android.content.Context
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

enum class RainRadarPlaybackSpeed(
    val delayMs: Long,
    val label: String,
) {
    SLOW(1_100L, "慢"),
    NORMAL(750L, "標準"),
    FAST(500L, "快");

    companion object {
        fun fromDelay(value: Long): RainRadarPlaybackSpeed =
            entries.firstOrNull { it.delayMs == value } ?: NORMAL
    }
}

data class RainRadarHostState(
    val location: LocationInfo? = null,
    val contract: RainResourceState<RainRadarContract> = RainResourceState(),
    val timeline: RainResourceState<RainRadarTimeline> = RainResourceState(),
    val selectedFrameIndex: Int? = null,
    val rangeKm: Int = 64,
    val heightKm: Int = 3,
    val mode: RainRadarMode = RainRadarMode.LIVE,
    val opacity: Float = 0.82f,
    val playbackSpeed: RainRadarPlaybackSpeed = RainRadarPlaybackSpeed.NORMAL,
) {
    val selectedFrame: RainRadarFrame?
        get() = timeline.value?.frames?.getOrNull(selectedFrameIndex ?: -1)
}

class RainRadarHostViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RainRepository(
        client = RainTrackClient(),
        cache = RainCache(application),
    )
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        RainRadarHostState(
            rangeKm = preferences.getInt(KEY_RANGE_KM, DEFAULT_RANGE_KM).takeIf { it > 0 } ?: DEFAULT_RANGE_KM,
            heightKm = preferences.getInt(KEY_HEIGHT_KM, DEFAULT_HEIGHT_KM).takeIf { it > 0 } ?: DEFAULT_HEIGHT_KM,
            mode = if (preferences.getString(KEY_MODE, RainRadarMode.LIVE.wireValue) == RainRadarMode.TEST.wireValue) {
                RainRadarMode.TEST
            } else {
                RainRadarMode.LIVE
            },
            opacity = preferences.getFloat(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(0f, 1f),
            playbackSpeed = RainRadarPlaybackSpeed.fromDelay(
                preferences.getLong(KEY_PLAYBACK_DELAY_MS, RainRadarPlaybackSpeed.NORMAL.delayMs),
            ),
        ),
    )
    val state: StateFlow<RainRadarHostState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var prefetchJob: Job? = null
    private var refreshGeneration = 0L
    private var prefetchGeneration = 0L
    private val prefetchedImages = object : LinkedHashMap<String, ByteArray>(PREFETCH_CACHE_FRAME_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > PREFETCH_CACHE_FRAME_LIMIT
    }

    fun bindHostLocation(location: LocationInfo) {
        val current = _state.value.location
        if (current != null && sameRadarLocation(current, location)) {
            if (current != location) _state.update { it.copy(location = location) }
            return
        }
        _state.update { it.copy(location = location) }
    }

    fun refreshRadar() {
        val current = _state.value
        refreshRadarInternal(
            requestedRangeKm = current.rangeKm,
            requestedHeightKm = current.heightKm,
            requestedMode = current.mode,
            preserveSelection = true,
            quiet = current.timeline.value != null,
        )
    }

    fun selectRange(rangeKm: Int) {
        val contract = _state.value.contract.value ?: return
        require(rangeKm in contract.rangesKm) { "Radar range $rangeKm km is unavailable" }
        val current = _state.value
        val heightKm = normalizedHeight(
            contract = contract,
            rangeKm = rangeKm,
            requestedHeightKm = current.heightKm,
            mode = current.mode,
        )
        if (current.rangeKm == rangeKm && current.heightKm == heightKm) return
        persistProduct(rangeKm, heightKm, current.mode)
        _state.update { it.copy(rangeKm = rangeKm, heightKm = heightKm) }
        refreshRadarInternal(rangeKm, heightKm, current.mode)
    }

    fun selectHeight(heightKm: Int) {
        val current = _state.value
        val contract = current.contract.value ?: return
        val normalized = normalizedHeight(
            contract = contract,
            rangeKm = current.rangeKm,
            requestedHeightKm = heightKm,
            mode = current.mode,
        )
        require(normalized == heightKm) {
            "Radar height $heightKm km is unavailable for ${current.rangeKm} km / ${current.mode.wireValue}"
        }
        if (current.heightKm == normalized) return
        persistProduct(current.rangeKm, normalized, current.mode)
        _state.update { it.copy(heightKm = normalized) }
        refreshRadarInternal(current.rangeKm, normalized, current.mode)
    }

    fun selectMode(mode: RainRadarMode) {
        val current = _state.value
        val contract = current.contract.value ?: return
        require(mode.wireValue in contract.modes) { "Radar mode ${mode.wireValue} is unavailable" }
        val heightKm = normalizedHeight(
            contract = contract,
            rangeKm = current.rangeKm,
            requestedHeightKm = current.heightKm,
            mode = mode,
        )
        if (current.mode == mode && current.heightKm == heightKm) return
        persistProduct(current.rangeKm, heightKm, mode)
        _state.update { it.copy(mode = mode, heightKm = heightKm) }
        refreshRadarInternal(current.rangeKm, heightKm, mode)
    }

    fun setOpacity(value: Float) {
        val opacity = value.coerceIn(0f, 1f)
        if (abs(_state.value.opacity - opacity) < 0.001f) return
        preferences.edit().putFloat(KEY_OPACITY, opacity).apply()
        _state.update { it.copy(opacity = opacity) }
    }

    fun setPlaybackSpeed(speed: RainRadarPlaybackSpeed) {
        if (_state.value.playbackSpeed == speed) return
        preferences.edit().putLong(KEY_PLAYBACK_DELAY_MS, speed.delayMs).apply()
        _state.update { it.copy(playbackSpeed = speed) }
    }

    fun selectFrame(index: Int) {
        val timeline = _state.value.timeline.value ?: return
        require(index in timeline.frames.indices) { "Radar frame index outside active timeline" }
        _state.update { it.copy(selectedFrameIndex = index) }
        scheduleFramePrefetch(timeline, index)
    }

    fun jumpToLatest() {
        val timeline = _state.value.timeline.value ?: return
        if (timeline.frames.isEmpty()) return
        val index = timeline.frames.lastIndex
        _state.update { it.copy(selectedFrameIndex = index) }
        scheduleFramePrefetch(timeline, index)
    }

    fun cancelRequests() {
        refreshGeneration += 1
        prefetchGeneration += 1
        refreshJob?.cancel()
        refreshJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        _state.update {
            it.copy(
                contract = settleRadarCancelled(it.contract),
                timeline = settleRadarCancelled(it.timeline),
            )
        }
    }

    private fun refreshRadarInternal(
        requestedRangeKm: Int,
        requestedHeightKm: Int,
        requestedMode: RainRadarMode,
        preserveSelection: Boolean = false,
        quiet: Boolean = false,
    ) {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        prefetchGeneration += 1
        prefetchJob?.cancel()
        prefetchJob = null

        val stateBeforeRefresh = _state.value
        val previousContract = stateBeforeRefresh.contract
        val previousTimeline = stateBeforeRefresh.timeline
        val previousTimelineMatchesRequest = previousTimeline.value?.matchesRadarProduct(
            requestedRangeKm,
            requestedHeightKm,
            requestedMode,
        ) == true
        val previousSelectedIndex = stateBeforeRefresh.selectedFrameIndex
            ?.takeIf { previousTimelineMatchesRequest }
        val previousSelectedTime = previousTimeline.value
            ?.frames
            ?.getOrNull(previousSelectedIndex ?: -1)
            ?.time
        val previousWasLatest = previousTimeline.value?.let { timeline ->
            previousSelectedIndex != null && previousSelectedIndex >= timeline.frames.lastIndex
        } == true

        _state.update {
            it.copy(
                contract = if (quiet && previousContract.value != null) {
                    previousContract.copy(errorMessage = null)
                } else {
                    previousContract.copy(
                        status = if (previousContract.value == null) RainResourceStatus.LOADING else previousContract.status,
                        errorMessage = null,
                    )
                },
                timeline = if (previousTimelineMatchesRequest) {
                    previousTimeline.copy(
                        status = if (quiet) RainResourceStatus.READY else RainResourceStatus.LOADING,
                        errorMessage = null,
                    )
                } else {
                    RainResourceState(status = RainResourceStatus.LOADING)
                },
                selectedFrameIndex = if (previousTimelineMatchesRequest) it.selectedFrameIndex else null,
            )
        }

        refreshJob = viewModelScope.launch {
            try {
                val contract = repository.loadRadarContract().value
                val rangeKm = normalizedRange(contract, requestedRangeKm)
                val mode = normalizedMode(contract, requestedMode)
                val heightKm = normalizedHeight(
                    contract = contract,
                    rangeKm = rangeKm,
                    requestedHeightKm = requestedHeightKm,
                    mode = mode,
                )
                if (generation != refreshGeneration) return@launch

                persistProduct(rangeKm, heightKm, mode)
                _state.update {
                    it.copy(
                        rangeKm = rangeKm,
                        heightKm = heightKm,
                        mode = mode,
                        contract = RainResourceState(
                            status = RainResourceStatus.READY,
                            value = contract,
                        ),
                    )
                }

                val timeline = repository.loadRadarTimeline(rangeKm, heightKm, mode).value
                if (generation != refreshGeneration) return@launch
                val selectedIndex = when {
                    preserveSelection &&
                        previousTimelineMatchesRequest &&
                        !previousWasLatest &&
                        previousSelectedTime != null -> {
                        timeline.frames.indexOfFirst { it.time == previousSelectedTime }
                            .takeIf { it >= 0 }
                            ?: timeline.frames.lastIndex
                    }
                    else -> timeline.frames.lastIndex
                }
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
                        selectedFrameIndex = selectedIndex,
                    )
                }
                scheduleFramePrefetch(timeline, selectedIndex)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation != refreshGeneration) return@launch
                _state.update { current ->
                    val retainedContract = current.contract.value ?: previousContract.value
                    val retainedTimeline = previousTimeline.value?.takeIf {
                        it.matchesRadarProduct(
                            current.rangeKm,
                            current.heightKm,
                            current.mode,
                        )
                    }
                    val retainedIndex = retainedTimeline?.let { timeline ->
                        previousSelectedIndex
                            ?.takeIf { preserveSelection && it in timeline.frames.indices }
                            ?: timeline.frames.lastIndex
                    }
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
                        selectedFrameIndex = retainedIndex,
                    )
                }
                previousTimeline.value?.takeIf { previousTimelineMatchesRequest }?.let { retained ->
                    val index = _state.value.selectedFrameIndex ?: retained.frames.lastIndex
                    scheduleFramePrefetch(retained, index)
                }
            }
        }
    }

    private fun scheduleFramePrefetch(timeline: RainRadarTimeline, selectedIndex: Int) {
        if (timeline.frames.isEmpty()) return
        val generation = ++prefetchGeneration
        prefetchJob?.cancel()
        val candidateIndexes = buildList {
            add(selectedIndex)
            add(selectedIndex - 1)
            add(selectedIndex + 1)
            add(selectedIndex - 2)
            add(selectedIndex + 2)
            for (index in timeline.frames.lastIndex downTo 0) {
                if (size >= RECENT_PREFETCH_FRAME_COUNT) break
                add(index)
            }
        }.distinct().filter { it in timeline.frames.indices }

        prefetchJob = viewModelScope.launch {
            for (index in candidateIndexes) {
                if (generation != prefetchGeneration) return@launch
                val imageUrl = timeline.frames[index].imageUrl
                val alreadyCached = synchronized(prefetchedImages) {
                    prefetchedImages[imageUrl] != null
                }
                if (alreadyCached) continue
                val bytes = try {
                    repository.loadRadarImage(imageUrl)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    continue
                }
                if (generation != prefetchGeneration) return@launch
                synchronized(prefetchedImages) {
                    prefetchedImages[imageUrl] = bytes
                }
            }
        }
    }

    private fun persistProduct(rangeKm: Int, heightKm: Int, mode: RainRadarMode) {
        preferences.edit()
            .putInt(KEY_RANGE_KM, rangeKm)
            .putInt(KEY_HEIGHT_KM, heightKm)
            .putString(KEY_MODE, mode.wireValue)
            .apply()
    }

    companion object {
        const val DEFAULT_RANGE_KM = 64
        const val DEFAULT_HEIGHT_KM = 3
        const val DEFAULT_OPACITY = 0.82f

        private const val PREFERENCES_NAME = "weather_metro_radar"
        private const val KEY_RANGE_KM = "range_km"
        private const val KEY_HEIGHT_KM = "height_km"
        private const val KEY_MODE = "mode"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_PLAYBACK_DELAY_MS = "playback_delay_ms"
        private const val RECENT_PREFETCH_FRAME_COUNT = 12
        private const val PREFETCH_CACHE_FRAME_LIMIT = 12
    }
}

private fun normalizedRange(contract: RainRadarContract, requestedRangeKm: Int): Int =
    requestedRangeKm.takeIf { it in contract.rangesKm }
        ?: DEFAULT_RANGE_FALLBACK.takeIf { it in contract.rangesKm }
        ?: contract.rangesKm.firstOrNull()
        ?: error("Radar contract has no supported ranges")

private fun normalizedMode(contract: RainRadarContract, requestedMode: RainRadarMode): RainRadarMode {
    if (requestedMode.wireValue in contract.modes) return requestedMode
    if (RainRadarMode.LIVE.wireValue in contract.modes) return RainRadarMode.LIVE
    if (RainRadarMode.TEST.wireValue in contract.modes) return RainRadarMode.TEST
    error("Radar contract has no supported modes")
}

private fun normalizedHeight(
    contract: RainRadarContract,
    rangeKm: Int,
    requestedHeightKm: Int,
    mode: RainRadarMode,
): Int {
    val available = contract.heightsForRange(rangeKm)
    require(available.isNotEmpty()) { "Radar range $rangeKm km has no supported heights" }
    if (mode == RainRadarMode.TEST) {
        return TEST_HEIGHT_FALLBACK.takeIf { it in available }
            ?: contract.defaultHeightKm.takeIf { it in available }
            ?: available.first()
    }
    return requestedHeightKm.takeIf { it in available }
        ?: contract.defaultHeightKm.takeIf { it in available }
        ?: available.first()
}

private fun RainRadarTimeline.matchesRadarProduct(
    rangeKm: Int,
    heightKm: Int,
    mode: RainRadarMode,
): Boolean =
    this.rangeKm == rangeKm &&
        this.heightKm == heightKm &&
        this.mode == mode.wireValue

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

private const val DEFAULT_RANGE_FALLBACK = 64
private const val TEST_HEIGHT_FALLBACK = 3
