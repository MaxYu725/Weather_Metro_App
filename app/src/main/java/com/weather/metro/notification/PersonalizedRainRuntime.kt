package com.weather.metro.notification

import com.weather.metro.data.rain.RainForecastClient
import com.weather.metro.domain.rain.RainForecastFrame
import com.weather.metro.domain.rain.RainForecastTimeline
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class PersonalizedRainDiscovery(
    val timeline: RainForecastTimeline,
    val firstFrame: RainForecastFrame,
)

internal interface PersonalizedRainFrameSource {
    suspend fun loadDiscovery(): PersonalizedRainDiscovery
    suspend fun loadFrame(timeline: RainForecastTimeline, frameIndex: Int): RainForecastFrame
}

internal class RainForecastPersonalizedRainFrameSource(
    private val client: RainForecastClient = RainForecastClient(),
) : PersonalizedRainFrameSource {
    override suspend fun loadDiscovery(): PersonalizedRainDiscovery {
        val first = client.loadSwirlsFrame(PersonalizedRainBackgroundFetchPlanner.DISCOVERY_FRAME_INDEX).value
        return PersonalizedRainDiscovery(
            timeline = client.buildSwirlsTimeline(first),
            firstFrame = first,
        )
    }

    override suspend fun loadFrame(
        timeline: RainForecastTimeline,
        frameIndex: Int,
    ): RainForecastFrame {
        val frame = client.loadSwirlsFrame(frameIndex).value
        client.assertSwirlsFrameCompatible(timeline, frame)
        return frame
    }
}

internal interface PersonalizedRainEventSink {
    fun accept(event: WeatherNotificationEvent): Boolean
    fun discardPendingRainEvents()
}

internal data class PersonalizedRainRuntimeResult(
    val status: String,
    val fetchedFrameIndices: List<Int> = emptyList(),
    val publishedEventKind: PersonalizedForecastEventKind? = null,
)

/**
 * Phase 2D2D orchestration for location-private SWIRLS rain notifications.
 *
 * This runtime owns no location provider and performs no central location upload. The caller
 * supplies Weather Metro's already-cached precise location. It stages a deterministic local
 * transition before publication, then commits evaluator state only after the event sink confirms
 * the notification is durably present in NotificationEventStore.
 */
internal class PersonalizedRainRuntime(
    private val frameSource: PersonalizedRainFrameSource,
    private val stateStore: PersonalizedRainStatePersistence,
    private val eventSink: PersonalizedRainEventSink,
) {
    suspend fun execute(
        location: PersonalizedNotificationLocation,
        nowEpochMs: Long,
    ): PersonalizedRainRuntimeResult {
        require(nowEpochMs > 0L)
        val evaluationLocation = location.toRainEvaluationLocation()
        var durable = stateStore.read()

        if (
            durable.evaluationLocation != null &&
            !samePersonalizedRainEvaluationArea(durable.evaluationLocation, evaluationLocation)
        ) {
            eventSink.discardPendingRainEvents()
            durable = PersonalizedRainDurableState(
                evaluationLocation = evaluationLocation,
                lastCheckedEpochMs = nowEpochMs,
                status = "LOCATION_CHANGED",
            )
            stateStore.write(durable)
        } else if (durable.evaluationLocation == null) {
            durable = durable.copy(evaluationLocation = evaluationLocation)
            stateStore.write(durable)
        }

        durable.pendingTransition?.let { pending ->
            val event = buildPersonalizedRainNotificationEvent(
                pending = pending,
                location = durable.evaluationLocation ?: evaluationLocation,
            )
            if (!eventSink.accept(event)) {
                val deferred = durable.copy(
                    lastCheckedEpochMs = nowEpochMs,
                    status = "PUBLISH_DEFERRED_${pending.eventIdentity.kind.name}",
                )
                stateStore.write(deferred)
                return PersonalizedRainRuntimeResult(status = deferred.status)
            }
            durable = commitPersonalizedRainPendingTransition(durable, nowEpochMs)
            stateStore.write(durable)
        }

        val discovery = frameSource.loadDiscovery()
        val runEpochMs = discovery.firstFrame.runTime
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: error("SWIRLS discovery frame has no valid run time")

        if (
            !PersonalizedForecastNotificationPolicy.isSourceFresh(
                sourceEpochMs = runEpochMs,
                nowEpochMs = nowEpochMs,
                maxAgeMs = PersonalizedForecastNotificationPolicy.RAIN_SOURCE_MAX_AGE_MS,
            )
        ) {
            val stale = durable.copy(
                evaluationLocation = evaluationLocation,
                lastSourceRunEpochMs = runEpochMs,
                lastCheckedEpochMs = nowEpochMs,
                status = "SOURCE_STALE",
                lastError = "",
            )
            stateStore.write(stale)
            return PersonalizedRainRuntimeResult(
                status = stale.status,
                fetchedFrameIndices = listOf(discovery.firstFrame.frameIndex),
            )
        }

        if (
            PersonalizedRainGridSampler.sample(
                discovery.firstFrame,
                evaluationLocation.latitude,
                evaluationLocation.longitude,
            ) == null
        ) {
            val outside = durable.copy(
                evaluationLocation = evaluationLocation,
                lastSourceRunEpochMs = runEpochMs,
                lastCheckedEpochMs = nowEpochMs,
                status = "LOCATION_OUTSIDE_SWIRLS_GRID",
                lastError = "",
            )
            stateStore.write(outside)
            return PersonalizedRainRuntimeResult(
                status = outside.status,
                fetchedFrameIndices = listOf(discovery.firstFrame.frameIndex),
            )
        }

        val loaded = linkedMapOf(discovery.firstFrame.frameIndex to discovery.firstFrame)
        val plan = PersonalizedRainBackgroundFetchPlanner.plan(
            timeline = discovery.timeline,
            nowEpochMs = nowEpochMs,
            loadedFrameIndices = loaded.keys,
            activeEpisode = durable.committedEpisodeState.active,
        ) ?: error("Unable to build SWIRLS background fetch plan")

        loadFrameBatch(
            timeline = discovery.timeline,
            frameIndices = plan.baselineNetworkFrameIndices,
            loaded = loaded,
        )

        var profile = buildPersonalizedRainProfile(
            frames = loaded.values.toList(),
            latitude = evaluationLocation.latitude,
            longitude = evaluationLocation.longitude,
            nowEpochMs = nowEpochMs,
        ) ?: error("Unable to build local SWIRLS baseline profile")

        if (
            PersonalizedRainBackgroundFetchPlanner.requiresDenseCompletion(
                activeEpisode = durable.committedEpisodeState.active,
                baselineSamples = profile.futureSamples(nowEpochMs),
            )
        ) {
            loadFrameBatch(
                timeline = discovery.timeline,
                frameIndices = plan.denseCompletionFrameIndices.filterNot(loaded::containsKey),
                loaded = loaded,
            )
            profile = buildPersonalizedRainProfile(
                frames = loaded.values.toList(),
                latitude = evaluationLocation.latitude,
                longitude = evaluationLocation.longitude,
                nowEpochMs = nowEpochMs,
            ) ?: error("Unable to build dense local SWIRLS profile")
        }

        val decision = evaluatePersonalizedRainTransition(
            profile = profile,
            state = durable.committedEpisodeState,
            nowEpochMs = nowEpochMs,
        )
        var staged = stagePersonalizedRainDecision(
            durableState = durable.copy(evaluationLocation = evaluationLocation),
            decision = decision,
            sourceRunEpochMs = profile.sourceRunEpochMs,
            detectedAtEpochMs = nowEpochMs,
        )
        stateStore.write(staged)

        val pending = staged.pendingTransition
        if (pending == null) {
            return PersonalizedRainRuntimeResult(
                status = staged.status,
                fetchedFrameIndices = loaded.keys.sorted(),
            )
        }

        val event = buildPersonalizedRainNotificationEvent(
            pending = pending,
            location = staged.evaluationLocation ?: evaluationLocation,
        )
        if (!eventSink.accept(event)) {
            staged = staged.copy(status = "PUBLISH_DEFERRED_${pending.eventIdentity.kind.name}")
            stateStore.write(staged)
            return PersonalizedRainRuntimeResult(
                status = staged.status,
                fetchedFrameIndices = loaded.keys.sorted(),
            )
        }

        val committed = commitPersonalizedRainPendingTransition(staged, nowEpochMs)
        stateStore.write(committed)
        return PersonalizedRainRuntimeResult(
            status = committed.status,
            fetchedFrameIndices = loaded.keys.sorted(),
            publishedEventKind = pending.eventIdentity.kind,
        )
    }

    private suspend fun loadFrameBatch(
        timeline: RainForecastTimeline,
        frameIndices: List<Int>,
        loaded: MutableMap<Int, RainForecastFrame>,
    ) {
        frameIndices
            .distinct()
            .filterNot(loaded::containsKey)
            .chunked(MAX_PARALLEL_FRAME_FETCHES)
            .forEach { chunk ->
                val frames = coroutineScope {
                    chunk.map { frameIndex ->
                        async { frameIndex to frameSource.loadFrame(timeline, frameIndex) }
                    }.awaitAll()
                }
                frames.forEach { (frameIndex, frame) -> loaded[frameIndex] = frame }
            }
    }

    private companion object {
        const val MAX_PARALLEL_FRAME_FETCHES = 3
    }
}

internal fun buildPersonalizedRainNotificationEvent(
    pending: PersonalizedRainPendingTransition,
    location: PersonalizedRainEvaluationLocation,
): WeatherNotificationEvent {
    val kind = pending.eventIdentity.kind
    val place = location.label.takeIf { it.isNotBlank() } ?: location.district
    val horizonText = when (pending.horizon) {
        PersonalizedForecastHorizon.MINUTES_30 -> "未來30分鐘內"
        PersonalizedForecastHorizon.MINUTES_60 -> "未來60分鐘內"
        PersonalizedForecastHorizon.MINUTES_120 -> "未來兩小時內"
        null -> "短時間內"
    }
    val title = when (kind) {
        PersonalizedForecastEventKind.RAIN_APPROACHING -> "所在地區稍後可能下雨"
        PersonalizedForecastEventKind.RAIN_STARTING_SOON -> "所在地區短時間內可能下雨"
        PersonalizedForecastEventKind.HEAVY_RAIN_APPROACHING -> "所在地區可能有較大雨勢"
        PersonalizedForecastEventKind.RAIN_INTENSIFYING -> "所在地區雨勢可能增強"
        PersonalizedForecastEventKind.RAIN_ENDING -> "所在地區雨勢可能逐步結束"
        else -> error("Unsupported personalised rain event kind: $kind")
    }
    val body = when (kind) {
        PersonalizedForecastEventKind.RAIN_APPROACHING,
        PersonalizedForecastEventKind.RAIN_STARTING_SOON,
        -> "香港天文台 SWIRLS 降雨臨近預報顯示，$place ${horizonText}可能開始有雨。這是 Weather Metro 按你裝置上的位置計算的本機提示。"
        PersonalizedForecastEventKind.HEAVY_RAIN_APPROACHING ->
            "香港天文台 SWIRLS 降雨臨近預報顯示，$place ${horizonText}可能出現較大雨勢。這是 Weather Metro 的本機衍生提示，並非天文台雨量警告。"
        PersonalizedForecastEventKind.RAIN_INTENSIFYING ->
            "香港天文台 SWIRLS 降雨臨近預報顯示，$place ${horizonText}雨勢可能增強。請按實際天氣及官方警告安排出行。"
        PersonalizedForecastEventKind.RAIN_ENDING ->
            "香港天文台 SWIRLS 降雨臨近預報顯示，$place 的短時間預報已轉乾，這一輪雨勢可能逐步減弱或結束。"
        else -> error("Unsupported personalised rain event kind: $kind")
    }

    return WeatherNotificationEvent(
        eventId = "local-swirls-rain:${stableRainDigest(pending.eventIdentity.dedupeKey())}",
        title = title,
        body = body,
        channel = NotificationChannels.GENERAL,
        target = "weathermetro://tools",
        alertId = "personalized-rain:${location.district}",
        alertCode = "LOC_SWIRLS_${kind.name}",
        eventKind = kind.name,
        sourceType = SOURCE_TYPE_PERSONALIZED_RAIN,
        sourceTime = Instant.ofEpochMilli(pending.sourceRunEpochMs).toString(),
        journalCursor = 0L,
        sentAtEpochMillis = pending.detectedAtEpochMs,
    )
}

internal fun PersonalizedNotificationLocation.toRainEvaluationLocation(): PersonalizedRainEvaluationLocation =
    PersonalizedRainEvaluationLocation(
        latitude = latitude,
        longitude = longitude,
        label = label,
        district = district,
    )

internal fun samePersonalizedRainEvaluationArea(
    previous: PersonalizedRainEvaluationLocation,
    current: PersonalizedRainEvaluationLocation,
): Boolean {
    if (!previous.district.equals(current.district, ignoreCase = true)) return false
    return haversineKm(previous.latitude, previous.longitude, current.latitude, current.longitude) <= 1.0
}

private fun haversineKm(
    latitudeA: Double,
    longitudeA: Double,
    latitudeB: Double,
    longitudeB: Double,
): Double {
    val earthRadiusKm = 6371.0088
    val lat1 = Math.toRadians(latitudeA)
    val lat2 = Math.toRadians(latitudeB)
    val deltaLat = Math.toRadians(latitudeB - latitudeA)
    val deltaLon = Math.toRadians(longitudeB - longitudeA)
    val sinLat = sin(deltaLat / 2.0)
    val sinLon = sin(deltaLon / 2.0)
    val a = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
    return earthRadiusKm * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
}

private fun stableRainDigest(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
