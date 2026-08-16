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
 * not create a second 15-minute periodic request. The periodic request carries an explicit input
 * marker that lets LocationHeavyRainWorker run the opt-in SWIRLS stream in the same execution slot.
 * Immediate 2D1 checks do not carry that marker, so they cannot accidentally multiply SWIRLS loads.
 */
object LocationHeavyRainScheduler {
    const val SOURCE_TYPE = "HKO_LOCATION_DERIVED"
    internal const val INPUT_DISPATCH_PERSONALIZED_RAIN = "dispatch_personalized_rain_cadence"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<LocationHeavyRainWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setInputData(workDataOf(INPUT_DISPATCH_PERSONALIZED_RAIN to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<LocationHeavyRainWorker>()
            .setConstraints(networkConstraint)
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
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
        PersonalizedRainScheduler.reset(context)
    }

    fun resetAll(context: Context) {
        disable(context)
        resetHeavyRain(context)
        resetPersonalizedRain(context)
    }

    /** Compatibility alias for pre-2D2E callers while this Draft PR remains open. */
    fun reset(context: Context) = resetAll(context)

    private const val PERIODIC_WORK_NAME = "weather-location-heavy-rain-periodic"
    private const val IMMEDIATE_WORK_NAME = "weather-location-heavy-rain-now"
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
