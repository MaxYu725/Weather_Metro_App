package com.weather.metro.notification

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Shared cadence owner for location-derived weather notifications.
 *
 * The original 2D1 unique work names and worker class are intentionally retained so upgrades do
 * not create a second 15-minute periodic request. Both periodic and immediate work use explicit
 * dispatch flags, allowing callers to request only the local rain stream that needs an immediate
 * refresh while keeping a single WorkManager owner.
 */
object LocationHeavyRainScheduler {
    const val SOURCE_TYPE = "HKO_LOCATION_DERIVED"
    internal const val INPUT_DISPATCH_LOCATION_HEAVY_RAIN = "dispatch_location_heavy_rain"
    internal const val INPUT_DISPATCH_PERSONALIZED_RAIN = "dispatch_personalized_rain"
    internal const val TAG_DISPATCH_LOCATION_HEAVY_RAIN = "weather-dispatch-location-heavy-rain"
    internal const val TAG_DISPATCH_PERSONALIZED_RAIN = "weather-dispatch-personalized-rain"
    internal const val PERIODIC_WORK_NAME = "weather-location-heavy-rain-periodic"
    internal const val IMMEDIATE_WORK_NAME = "weather-location-heavy-rain-now"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<LocationHeavyRainWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setInputData(
                workDataOf(
                    INPUT_DISPATCH_LOCATION_HEAVY_RAIN to true,
                    INPUT_DISPATCH_PERSONALIZED_RAIN to true,
                ),
            )
            .addTag(TAG_DISPATCH_LOCATION_HEAVY_RAIN)
            .addTag(TAG_DISPATCH_PERSONALIZED_RAIN)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun enqueueNow(
        context: Context,
        dispatchHeavyRain: Boolean = true,
        dispatchPersonalizedRain: Boolean = true,
    ) {
        if (!dispatchHeavyRain && !dispatchPersonalizedRain) return
        val builder = OneTimeWorkRequestBuilder<LocationHeavyRainWorker>()
            .setConstraints(networkConstraint)
            .setInputData(
                workDataOf(
                    INPUT_DISPATCH_LOCATION_HEAVY_RAIN to dispatchHeavyRain,
                    INPUT_DISPATCH_PERSONALIZED_RAIN to dispatchPersonalizedRain,
                ),
            )
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        if (dispatchHeavyRain) builder.addTag(TAG_DISPATCH_LOCATION_HEAVY_RAIN)
        if (dispatchPersonalizedRain) builder.addTag(TAG_DISPATCH_PERSONALIZED_RAIN)
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            builder.build(),
        )
    }

    fun cancelImmediate(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context.applicationContext).apply {
            cancelUniqueWork(PERIODIC_WORK_NAME)
            cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }

    fun resetHeavyRain(context: Context) {
        LocationHeavyRainStateStore(context).reset()
        NotificationEventStore(context).discardPendingBySourceType(SOURCE_TYPE)
    }

    fun resetPersonalizedRain(context: Context) {
        PersonalizedRainEpisodeStateStore(context).reset()
        NotificationEventStore(context).discardPendingBySourceType(SOURCE_TYPE_PERSONALIZED_RAIN)
    }

    fun resetAll(context: Context) {
        disable(context)
        resetHeavyRain(context)
        resetPersonalizedRain(context)
    }

    /** Compatibility alias for pre-2D2E callers while this Draft PR remains open. */
    fun reset(context: Context) = resetAll(context)
}

internal fun shouldSchedulePersonalizedLocationNotifications(
    notificationsEnabled: Boolean,
    preciseLocationEnabled: Boolean,
    locationHeavyRainEnabled: Boolean,
    personalizedRainEnabled: Boolean,
): Boolean =
    notificationsEnabled &&
        preciseLocationEnabled &&
        (locationHeavyRainEnabled || personalizedRainEnabled)

internal fun shouldRunPersonalizedNotificationStream(
    dispatchRequested: Boolean,
    settingEnabled: Boolean,
): Boolean = dispatchRequested && settingEnabled
