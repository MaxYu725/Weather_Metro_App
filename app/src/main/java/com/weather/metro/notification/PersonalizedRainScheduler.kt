package com.weather.metro.notification

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * One-shot scheduler for the SWIRLS personalised-rain runtime.
 *
 * There is intentionally no PeriodicWorkRequest here. The existing 2D1 location-weather
 * periodic work remains the only 15-minute cadence owner and dispatches this worker when
 * the dedicated opt-in is enabled.
 */
object PersonalizedRainScheduler {
    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueFromCadence(context: Context) {
        enqueue(
            context = context,
            policy = ExistingWorkPolicy.KEEP,
            expedited = false,
        )
    }

    fun enqueueNow(context: Context) {
        enqueue(
            context = context,
            policy = ExistingWorkPolicy.REPLACE,
            expedited = true,
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    fun reset(context: Context) {
        disable(context)
        PersonalizedRainEpisodeStateStore(context).reset()
        NotificationEventStore(context).discardPendingBySourceType(SOURCE_TYPE_PERSONALIZED_RAIN)
    }

    private fun enqueue(
        context: Context,
        policy: ExistingWorkPolicy,
        expedited: Boolean,
    ) {
        val builder = OneTimeWorkRequestBuilder<PersonalizedRainWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        if (expedited) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            policy,
            builder.build(),
        )
    }

    private const val WORK_NAME = "weather-personalized-rain-now"
}
