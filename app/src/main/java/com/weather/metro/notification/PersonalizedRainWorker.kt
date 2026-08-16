package com.weather.metro.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.weather.metro.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException

/**
 * WorkManager adapter for the Phase 2D2 personalised SWIRLS runtime.
 *
 * This class is intentionally not scheduled by 2D2D. Activation/cadence remains a separate
 * checkpoint so the runtime can be verified before adding another persistent background job.
 */
internal class PersonalizedRainWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val locationStore = PersonalizedNotificationLocationStore(appContext)
    private val stateStore = PersonalizedRainEpisodeStateStore(appContext)
    private val runtime = PersonalizedRainRuntime(
        frameSource = RainForecastPersonalizedRainFrameSource(),
        stateStore = stateStore,
        eventSink = AndroidPersonalizedRainEventSink(appContext),
    )

    override suspend fun doWork(): Result {
        if (!SettingsRepository.notificationsEnabled(applicationContext)) {
            recordIdle("NOTIFICATIONS_DISABLED")
            return Result.success()
        }
        if (!SettingsRepository.preciseLocationEnabled(applicationContext)) {
            recordIdle("LOCATION_DISABLED")
            return Result.success()
        }

        val nowEpochMs = System.currentTimeMillis()
        val location = locationStore.read()
        if (location == null) {
            recordIdle("LOCATION_UNAVAILABLE")
            return Result.success()
        }
        if (
            nowEpochMs < location.updatedAtEpochMs ||
            nowEpochMs - location.updatedAtEpochMs > PersonalizedForecastNotificationPolicy.LOCATION_MAX_AGE_MS
        ) {
            recordIdle("LOCATION_STALE")
            return Result.success()
        }

        return try {
            runtime.execute(location, nowEpochMs)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val current = stateStore.read()
            runCatching {
                stateStore.write(
                    current.copy(
                        lastCheckedEpochMs = nowEpochMs,
                        status = "ERROR",
                        lastError = (error.message ?: error::class.java.simpleName).take(500),
                    ),
                )
            }
            Result.retry()
        }
    }

    private fun recordIdle(status: String) {
        val current = stateStore.read()
        stateStore.write(
            current.copy(
                lastCheckedEpochMs = System.currentTimeMillis(),
                status = status,
                lastError = "",
            ),
        )
    }
}

internal class AndroidPersonalizedRainEventSink(context: Context) : PersonalizedRainEventSink {
    private val inboxStore = NotificationEventStore(context)
    private val publisher = WeatherNotificationPublisher(context, inboxStore)

    override fun accept(event: WeatherNotificationEvent): Boolean = publisher.accept(event)

    override fun discardPendingRainEvents() {
        inboxStore.discardPendingBySourceType(SOURCE_TYPE_PERSONALIZED_RAIN)
    }
}
