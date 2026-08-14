package com.weather.metro.ui.storm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weather.metro.data.storm.StormLiveCache
import com.weather.metro.data.storm.StormService
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.storm.AgencyLiveResult
import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormLiveState
import com.weather.metro.domain.storm.StormTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

internal data class StormAgencyHostState(
    val agency: StormAgency,
    val liveState: StormLiveState = StormLiveState.LOADING,
    val message: String = "準備同步",
    val updatedAt: String? = null,
    val storms: List<StormTrack> = emptyList(),
    val refreshing: Boolean = false,
    val hasSuccessfulSnapshot: Boolean = false,
    val isCached: Boolean = false,
    val cacheSavedAtMillis: Long? = null,
    val errorMessage: String? = null,
)

internal data class StormHostState(
    val location: LocationInfo? = null,
    val sources: Map<StormAgency, StormAgencyHostState> = initialStormSources(),
    val cacheRestored: Boolean = false,
    val hasRefreshAttempted: Boolean = false,
) {
    val isRefreshing: Boolean
        get() = sources.values.any { it.refreshing }

    val activeTrackCount: Int
        get() = sources.values.sumOf { it.storms.size }

    val successfulSourceCount: Int
        get() = sources.values.count { it.hasSuccessfulSnapshot }
}

internal class StormHostViewModel(application: Application) : AndroidViewModel(application) {
    private val service = StormService()
    private val cache = StormLiveCache(application)

    private val _state = MutableStateFlow(StormHostState())
    val state: StateFlow<StormHostState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var cacheJob: Job? = null
    private var refreshGeneration = 0L

    init {
        restoreCache()
    }

    fun bindHostLocation(location: LocationInfo) {
        val current = _state.value.location
        if (current != null && sameStormLocation(current, location)) {
            if (current != location) _state.update { it.copy(location = location) }
            return
        }
        _state.update { it.copy(location = location) }
    }

    fun refreshLive() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        _state.update { current ->
            current.copy(
                hasRefreshAttempted = true,
                sources = current.sources.mapValues { (_, source) ->
                    source.copy(
                        liveState = if (source.hasSuccessfulSnapshot) StormLiveState.STALE else StormLiveState.LOADING,
                        message = if (source.hasSuccessfulSnapshot) "更新中 · 保留最近資料" else "同步中",
                        refreshing = true,
                        errorMessage = null,
                    )
                },
            )
        }

        refreshJob = viewModelScope.launch {
            supervisorScope {
                StormAgency.entries.map { agency ->
                    launch {
                        val incoming = try {
                            service.loadLiveAgency(agency = agency, force = true)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            AgencyLiveResult(
                                agency = agency,
                                state = StormLiveState.ERROR,
                                message = error.message ?: "${agency.name} live load failed",
                                updatedAt = null,
                                storms = emptyList(),
                            )
                        }
                        if (generation != refreshGeneration) return@launch

                        _state.update { current ->
                            val previous = current.sources[agency] ?: initialStormSource(agency)
                            current.copy(
                                sources = current.sources + (agency to mergeStormAgencyResult(previous, incoming)),
                            )
                        }

                        if (incoming.state == StormLiveState.OK || incoming.state == StormLiveState.EMPTY) {
                            try {
                                cache.save(incoming)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                // Network success remains authoritative even if disk persistence fails.
                            }
                        }
                    }
                }.joinAll()
            }
        }
    }

    fun cancelRequests() {
        refreshGeneration += 1
        refreshJob?.cancel()
        refreshJob = null
        _state.update { current ->
            current.copy(
                sources = current.sources.mapValues { (_, source) ->
                    source.copy(
                        refreshing = false,
                        message = if (source.liveState == StormLiveState.LOADING && !source.hasSuccessfulSnapshot) {
                            "已暫停"
                        } else {
                            source.message
                        },
                    )
                },
            )
        }
    }

    fun clearCache() {
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            cache.clear()
            _state.update { current ->
                current.copy(
                    sources = current.sources.mapValues { (agency, source) ->
                        if (source.isCached) {
                            initialStormSource(agency).copy(refreshing = source.refreshing)
                        } else {
                            source.copy(cacheSavedAtMillis = null)
                        }
                    },
                )
            }
        }
    }

    private fun restoreCache() {
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            val cached = runCatching { cache.loadAll() }.getOrElse { emptyMap() }
            _state.update { current ->
                val merged = current.sources.toMutableMap()
                cached.forEach { (agency, entry) ->
                    val existing = merged[agency] ?: initialStormSource(agency)
                    if (!existing.hasSuccessfulSnapshot) {
                        merged[agency] = StormAgencyHostState(
                            agency = agency,
                            liveState = StormLiveState.STALE,
                            message = "裝置快取",
                            updatedAt = entry.result.updatedAt,
                            storms = entry.result.storms,
                            refreshing = existing.refreshing,
                            hasSuccessfulSnapshot = true,
                            isCached = true,
                            cacheSavedAtMillis = entry.savedAtMillis,
                            errorMessage = null,
                        )
                    }
                }
                current.copy(
                    sources = merged,
                    cacheRestored = true,
                )
            }
        }
    }
}

internal fun mergeStormAgencyResult(
    previous: StormAgencyHostState,
    incoming: AgencyLiveResult,
): StormAgencyHostState {
    require(previous.agency == incoming.agency) { "Storm agency merge mismatch" }
    return when (incoming.state) {
        StormLiveState.OK,
        StormLiveState.EMPTY,
        -> StormAgencyHostState(
            agency = incoming.agency,
            liveState = incoming.state,
            message = incoming.message ?: if (incoming.state == StormLiveState.EMPTY) "沒有活躍路徑" else "已更新",
            updatedAt = incoming.updatedAt,
            storms = incoming.storms,
            refreshing = false,
            hasSuccessfulSnapshot = true,
            isCached = false,
            cacheSavedAtMillis = null,
            errorMessage = null,
        )

        StormLiveState.ERROR -> if (previous.hasSuccessfulSnapshot) {
            previous.copy(
                liveState = StormLiveState.STALE,
                message = "即時更新失敗，保留最近資料",
                refreshing = false,
                errorMessage = incoming.message ?: "${incoming.agency.name} live load failed",
            )
        } else {
            StormAgencyHostState(
                agency = incoming.agency,
                liveState = StormLiveState.ERROR,
                message = incoming.message ?: "讀取失敗",
                refreshing = false,
                hasSuccessfulSnapshot = false,
                errorMessage = incoming.message ?: "${incoming.agency.name} live load failed",
            )
        }

        StormLiveState.LOADING,
        StormLiveState.STALE,
        -> previous.copy(
            liveState = incoming.state,
            message = incoming.message ?: previous.message,
            refreshing = incoming.state == StormLiveState.LOADING,
            errorMessage = null,
        )
    }
}

private fun initialStormSources(): Map<StormAgency, StormAgencyHostState> =
    StormAgency.entries.associateWith(::initialStormSource)

private fun initialStormSource(agency: StormAgency): StormAgencyHostState =
    StormAgencyHostState(agency = agency)

private fun sameStormLocation(left: LocationInfo, right: LocationInfo): Boolean =
    abs(left.latitude - right.latitude) <= 0.000001 &&
        abs(left.longitude - right.longitude) <= 0.000001
