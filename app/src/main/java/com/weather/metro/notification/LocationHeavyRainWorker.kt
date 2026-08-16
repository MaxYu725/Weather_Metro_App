package com.weather.metro.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.weather.metro.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Locale

/**
 * Single WorkManager host for location-derived weather notifications.
 *
 * Phase 2D2E intentionally reuses the existing 2D1 worker/cadence instead of scheduling a second
 * periodic SWIRLS worker. District observed rain and personalised SWIRLS rain keep separate state,
 * source identities, inbox cleanup and runtime error handling inside this shared execution slot.
 */
internal class LocationHeavyRainWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val locationStore = PersonalizedNotificationLocationStore(appContext)

    private val stateStore = LocationHeavyRainStateStore(appContext)
    private val inboxStore = NotificationEventStore(appContext)
    private val rainClient = HkoDistrictRainClient()
    private val publisher = WeatherNotificationPublisher(appContext)

    private val personalizedRainStateStore = PersonalizedRainEpisodeStateStore(appContext)
    private val personalizedRainRuntime = PersonalizedRainRuntime(
        frameSource = RainForecastPersonalizedRainFrameSource(),
        stateStore = personalizedRainStateStore,
        eventSink = AndroidPersonalizedRainEventSink(appContext),
    )

    override suspend fun doWork(): Result {
        if (!SettingsRepository.notificationsEnabled(applicationContext)) return Result.success()

        val locationHeavyRainEnabled =
            SettingsRepository.locationHeavyRainNotificationsEnabled(applicationContext)
        val personalizedRainEnabled =
            inputData.getBoolean(LocationHeavyRainScheduler.INPUT_DISPATCH_PERSONALIZED_RAIN) &&
                SettingsRepository.personalizedRainNotificationsEnabled(applicationContext)
        if (!locationHeavyRainEnabled && !personalizedRainEnabled) return Result.success()

        if (!SettingsRepository.preciseLocationEnabled(applicationContext)) {
            if (locationHeavyRainEnabled) recordHeavyRainIdle("LOCATION_DISABLED")
            if (personalizedRainEnabled) recordPersonalizedRainIdle("LOCATION_DISABLED")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val location = locationStore.read()
        if (location == null) {
            if (locationHeavyRainEnabled) recordHeavyRainIdle("LOCATION_UNAVAILABLE", now)
            if (personalizedRainEnabled) recordPersonalizedRainIdle("LOCATION_UNAVAILABLE", now)
            return Result.success()
        }
        if (
            now < location.updatedAtEpochMs ||
            now - location.updatedAtEpochMs > MAX_LOCATION_AGE_MS
        ) {
            if (locationHeavyRainEnabled) recordHeavyRainIdle("LOCATION_STALE", now)
            if (personalizedRainEnabled) recordPersonalizedRainIdle("LOCATION_STALE", now)
            return Result.success()
        }

        var retryRequired = false
        if (locationHeavyRainEnabled && runLocationHeavyRain(location, now) == RunOutcome.RETRY) {
            retryRequired = true
        }
        if (personalizedRainEnabled && runPersonalizedRain(location, now) == RunOutcome.RETRY) {
            retryRequired = true
        }
        return if (retryRequired) Result.retry() else Result.success()
    }

    private suspend fun runLocationHeavyRain(
        location: PersonalizedNotificationLocation,
        now: Long,
    ): RunOutcome {
        var state = stateStore.read()
        if (state.district.isNotBlank() && state.district != location.district) {
            inboxStore.discardPendingBySourceType(SOURCE_TYPE_LOCATION_DERIVED)
            state = LocationHeavyRainState(district = location.district, status = "LOCATION_CHANGED")
            stateStore.write(state)
        } else if (state.district.isBlank()) {
            state = state.copy(district = location.district)
            stateStore.write(state)
        }

        return try {
            val pendingLevel = state.pendingLevel
            if (pendingLevel != null) {
                if (!publishPending(location, state, now)) return RunOutcome.SUCCESS
                state = state.copy(
                    pendingLevel = null,
                    pendingObservedMm = null,
                    pendingObservedAt = "",
                    lastNotificationEpochMs = now,
                    status = "NOTIFIED_${pendingLevel.thresholdMm}",
                    lastError = "",
                )
                stateStore.write(state)
            }

            val observation = rainClient.load(location.district)
            val observedAt = observation.observedAt
            val observedMm = observation.pastHourMaxMm
            val sourceEpochMs = runCatching {
                OffsetDateTime.parse(observedAt).toInstant().toEpochMilli()
            }.getOrNull()

            if (!isLocationHeavyRainSourceFresh(sourceEpochMs, now)) {
                stateStore.write(
                    state.copy(
                        lastObservedMm = observedMm,
                        lastObservedAt = observedAt,
                        lastCheckedEpochMs = now,
                        status = "SOURCE_STALE",
                        lastError = "",
                    ),
                )
                return RunOutcome.SUCCESS
            }

            if (observedMm == null) {
                stateStore.write(
                    state.copy(
                        lastObservedMm = null,
                        lastObservedAt = observedAt,
                        lastCheckedEpochMs = now,
                        status = "NO_DISTRICT_DATA",
                        lastError = "",
                    ),
                )
                return RunOutcome.SUCCESS
            }

            val decision = evaluateLocationHeavyRain(observedMm, state.activeLevel)
            val resetEpisode = decision.nextActiveLevel == LocationHeavyRainLevel.NONE
            var episodeId = if (resetEpisode) "" else state.episodeId
            if (episodeId.isBlank() && decision.nextActiveLevel != LocationHeavyRainLevel.NONE) {
                episodeId = observedAt.ifBlank { now.toString() }
            }

            var next = state.copy(
                district = location.district,
                activeLevel = decision.nextActiveLevel,
                episodeId = episodeId,
                lastObservedMm = observedMm,
                lastObservedAt = observedAt,
                lastCheckedEpochMs = now,
                status = when (decision.nextActiveLevel) {
                    LocationHeavyRainLevel.NONE -> "BELOW_THRESHOLD"
                    LocationHeavyRainLevel.HEAVY_50 -> "TRACKING_50"
                    LocationHeavyRainLevel.VERY_HEAVY_70 -> "TRACKING_70"
                },
                lastError = "",
            )

            val notificationLevel = decision.notificationLevel
            if (notificationLevel == null) {
                stateStore.write(next)
                return RunOutcome.SUCCESS
            }

            // Persist the pending transition before touching the local inbox. If
            // the process stops, the next worker replays exactly the same event.
            next = next.copy(
                pendingLevel = notificationLevel,
                pendingObservedMm = observedMm,
                pendingObservedAt = observedAt,
                status = "PENDING_${notificationLevel.thresholdMm}",
            )
            stateStore.write(next)

            if (!publishPending(location, next, now)) return RunOutcome.SUCCESS
            stateStore.write(
                next.copy(
                    pendingLevel = null,
                    pendingObservedMm = null,
                    pendingObservedAt = "",
                    lastNotificationEpochMs = now,
                    status = "NOTIFIED_${notificationLevel.thresholdMm}",
                    lastError = "",
                ),
            )
            RunOutcome.SUCCESS
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val current = stateStore.read()
            runCatching {
                stateStore.write(
                    current.copy(
                        lastCheckedEpochMs = now,
                        status = "ERROR",
                        lastError = (error.message ?: error::class.java.simpleName).take(500),
                    ),
                )
            }
            RunOutcome.RETRY
        }
    }

    private suspend fun runPersonalizedRain(
        location: PersonalizedNotificationLocation,
        now: Long,
    ): RunOutcome = try {
        personalizedRainRuntime.execute(location, now)
        RunOutcome.SUCCESS
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        val current = personalizedRainStateStore.read()
        runCatching {
            personalizedRainStateStore.write(
                current.copy(
                    lastCheckedEpochMs = now,
                    status = "ERROR",
                    lastError = (error.message ?: error::class.java.simpleName).take(500),
                ),
            )
        }
        RunOutcome.RETRY
    }

    private fun publishPending(
        location: PersonalizedNotificationLocation,
        state: LocationHeavyRainState,
        nowEpochMs: Long,
    ): Boolean {
        val level = state.pendingLevel ?: return true
        val observedMm = state.pendingObservedMm ?: return true
        val episode = state.episodeId.ifBlank { state.pendingObservedAt.ifBlank { nowEpochMs.toString() } }
        val event = WeatherNotificationEvent(
            eventId = "local-heavy-rain:" + stableDigest(
                listOf(location.district, episode, level.thresholdMm.toString()).joinToString("|"),
            ),
            title = if (level == LocationHeavyRainLevel.VERY_HEAVY_70) {
                "所在地區非常大雨通知"
            } else {
                "所在地區大雨通知"
            },
            body = buildBody(location.district, observedMm, level.thresholdMm),
            channel = NotificationChannels.GENERAL,
            target = "weathermetro://current",
            alertId = "location-heavy-rain:${location.district}",
            alertCode = "LOC_RAIN_${level.thresholdMm}",
            eventKind = "LOCATION_HEAVY_RAIN",
            sourceType = SOURCE_TYPE_LOCATION_DERIVED,
            sourceTime = state.pendingObservedAt,
            journalCursor = 0,
            sentAtEpochMillis = nowEpochMs,
        )
        return publisher.accept(event)
    }

    private fun buildBody(district: String, observedMm: Double, thresholdMm: Int): String {
        val amount = if (observedMm % 1.0 == 0.0) {
            observedMm.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", observedMm)
        }
        return "香港天文台分區雨量資料顯示，${district}過去60分鐘最高雨量約 $amount mm，" +
            "已達 $thresholdMm mm。請留意低窪地區、水浸及出行風險。"
    }

    private fun recordHeavyRainIdle(status: String, nowEpochMs: Long = System.currentTimeMillis()) {
        val current = stateStore.read()
        stateStore.write(
            current.copy(
                lastCheckedEpochMs = nowEpochMs,
                status = status,
                lastError = "",
            ),
        )
    }

    private fun recordPersonalizedRainIdle(status: String, nowEpochMs: Long = System.currentTimeMillis()) {
        val current = personalizedRainStateStore.read()
        personalizedRainStateStore.write(
            current.copy(
                lastCheckedEpochMs = nowEpochMs,
                status = status,
                lastError = "",
            ),
        )
    }

    private fun stableDigest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private enum class RunOutcome {
        SUCCESS,
        RETRY,
    }

    private companion object {
        const val MAX_LOCATION_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
