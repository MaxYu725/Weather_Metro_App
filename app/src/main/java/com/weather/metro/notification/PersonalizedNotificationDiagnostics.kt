package com.weather.metro.notification

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.weather.metro.data.settings.SettingsRepository

internal enum class PersonalizedNotificationDiagnosticVerdict {
    READY,
    DISABLED,
    LOCATION_UNAVAILABLE,
    LOCATION_STALE,
    PERIODIC_MISSING,
    PERIODIC_DUPLICATE,
    PERIODIC_DISPATCH_INVALID,
    STOPPING_OR_STALE_WORK,
    READ_ERROR,
}

internal data class PersonalizedNotificationDiagnostics(
    val checkedAtEpochMs: Long = 0L,
    val verdict: PersonalizedNotificationDiagnosticVerdict = PersonalizedNotificationDiagnosticVerdict.DISABLED,
    val scheduleExpected: Boolean = false,
    val periodicActiveCount: Int = 0,
    val periodicDispatchHeavyRain: Boolean = false,
    val periodicDispatchPersonalizedRain: Boolean = false,
    val immediateActiveCount: Int = 0,
    val immediateDispatchHeavyRain: Boolean = false,
    val immediateDispatchPersonalizedRain: Boolean = false,
    val locationDistrict: String = "",
    val locationAgeMs: Long? = null,
    val locationFresh: Boolean = false,
    val heavyRainStatus: String = "IDLE",
    val heavyRainLastCheckedEpochMs: Long = 0L,
    val personalizedRainStatus: String = "IDLE",
    val personalizedRainLastCheckedEpochMs: Long = 0L,
    val personalizedRainLastSourceRunEpochMs: Long = 0L,
    val personalizedRainPendingKind: String = "",
    val error: String = "",
)

internal data class PersonalizedNotificationDiagnosticGate(
    val scheduleExpected: Boolean,
    val locationAvailable: Boolean,
    val locationFresh: Boolean,
    val periodicActiveCount: Int,
    val periodicDispatchHeavyRain: Boolean,
    val periodicDispatchPersonalizedRain: Boolean,
)

internal fun assessPersonalizedNotificationDiagnostics(
    gate: PersonalizedNotificationDiagnosticGate,
): PersonalizedNotificationDiagnosticVerdict {
    if (!gate.scheduleExpected) {
        return if (gate.periodicActiveCount == 0) {
            PersonalizedNotificationDiagnosticVerdict.DISABLED
        } else {
            PersonalizedNotificationDiagnosticVerdict.STOPPING_OR_STALE_WORK
        }
    }
    if (!gate.locationAvailable) return PersonalizedNotificationDiagnosticVerdict.LOCATION_UNAVAILABLE
    if (!gate.locationFresh) return PersonalizedNotificationDiagnosticVerdict.LOCATION_STALE
    if (gate.periodicActiveCount == 0) return PersonalizedNotificationDiagnosticVerdict.PERIODIC_MISSING
    if (gate.periodicActiveCount > 1) return PersonalizedNotificationDiagnosticVerdict.PERIODIC_DUPLICATE
    if (!gate.periodicDispatchHeavyRain || !gate.periodicDispatchPersonalizedRain) {
        return PersonalizedNotificationDiagnosticVerdict.PERIODIC_DISPATCH_INVALID
    }
    return PersonalizedNotificationDiagnosticVerdict.READY
}

/**
 * Reads only local Android state for real-device activation verification.
 *
 * Exact latitude/longitude never leaves the location store and is intentionally not returned by
 * this diagnostics model. The UI receives only district and age/freshness information.
 */
internal class PersonalizedNotificationDiagnosticsReader(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val locationStore = PersonalizedNotificationLocationStore(appContext)
    private val heavyRainStateStore = LocationHeavyRainStateStore(appContext)
    private val personalizedRainStateStore = PersonalizedRainEpisodeStateStore(appContext)

    fun read(nowEpochMs: Long = System.currentTimeMillis()): PersonalizedNotificationDiagnostics {
        return runCatching {
            val notificationsEnabled = SettingsRepository.notificationsEnabled(appContext)
            val preciseLocationEnabled = SettingsRepository.preciseLocationEnabled(appContext)
            val heavyRainEnabled = SettingsRepository.locationHeavyRainNotificationsEnabled(appContext)
            val personalizedRainEnabled = SettingsRepository.personalizedRainNotificationsEnabled(appContext)
            val scheduleExpected = shouldSchedulePersonalizedLocationNotifications(
                notificationsEnabled = notificationsEnabled,
                preciseLocationEnabled = preciseLocationEnabled,
                locationHeavyRainEnabled = heavyRainEnabled,
                personalizedRainEnabled = personalizedRainEnabled,
            )

            val periodic = workManager
                .getWorkInfosForUniqueWork(LocationHeavyRainScheduler.PERIODIC_WORK_NAME)
                .get()
                .filter(WorkInfo::isActiveForPersonalizedDiagnostics)
            val immediate = workManager
                .getWorkInfosForUniqueWork(LocationHeavyRainScheduler.IMMEDIATE_WORK_NAME)
                .get()
                .filter(WorkInfo::isActiveForPersonalizedDiagnostics)

            val periodicHeavy = periodic.any {
                it.inputData.getBoolean(LocationHeavyRainScheduler.INPUT_DISPATCH_LOCATION_HEAVY_RAIN, false)
            }
            val periodicPersonalized = periodic.any {
                it.inputData.getBoolean(LocationHeavyRainScheduler.INPUT_DISPATCH_PERSONALIZED_RAIN, false)
            }
            val immediateHeavy = immediate.any {
                it.inputData.getBoolean(LocationHeavyRainScheduler.INPUT_DISPATCH_LOCATION_HEAVY_RAIN, false)
            }
            val immediatePersonalized = immediate.any {
                it.inputData.getBoolean(LocationHeavyRainScheduler.INPUT_DISPATCH_PERSONALIZED_RAIN, false)
            }

            val location = locationStore.read()
            val locationAgeMs = location?.updatedAtEpochMs?.let { updatedAt ->
                (nowEpochMs - updatedAt).takeIf { it >= 0L }
            }
            val locationFresh = locationAgeMs != null &&
                locationAgeMs <= PersonalizedForecastNotificationPolicy.LOCATION_MAX_AGE_MS
            val heavyState = heavyRainStateStore.read()
            val personalizedState = personalizedRainStateStore.read()

            val verdict = assessPersonalizedNotificationDiagnostics(
                PersonalizedNotificationDiagnosticGate(
                    scheduleExpected = scheduleExpected,
                    locationAvailable = location != null,
                    locationFresh = locationFresh,
                    periodicActiveCount = periodic.size,
                    periodicDispatchHeavyRain = periodicHeavy,
                    periodicDispatchPersonalizedRain = periodicPersonalized,
                ),
            )

            PersonalizedNotificationDiagnostics(
                checkedAtEpochMs = nowEpochMs,
                verdict = verdict,
                scheduleExpected = scheduleExpected,
                periodicActiveCount = periodic.size,
                periodicDispatchHeavyRain = periodicHeavy,
                periodicDispatchPersonalizedRain = periodicPersonalized,
                immediateActiveCount = immediate.size,
                immediateDispatchHeavyRain = immediateHeavy,
                immediateDispatchPersonalizedRain = immediatePersonalized,
                locationDistrict = location?.district.orEmpty(),
                locationAgeMs = locationAgeMs,
                locationFresh = locationFresh,
                heavyRainStatus = heavyState.status,
                heavyRainLastCheckedEpochMs = heavyState.lastCheckedEpochMs,
                personalizedRainStatus = personalizedState.status,
                personalizedRainLastCheckedEpochMs = personalizedState.lastCheckedEpochMs,
                personalizedRainLastSourceRunEpochMs = personalizedState.lastSourceRunEpochMs,
                personalizedRainPendingKind = personalizedState.pendingTransition?.eventIdentity?.kind?.name.orEmpty(),
            )
        }.getOrElse { error ->
            PersonalizedNotificationDiagnostics(
                checkedAtEpochMs = nowEpochMs,
                verdict = PersonalizedNotificationDiagnosticVerdict.READ_ERROR,
                error = (error.message ?: error::class.java.simpleName).take(300),
            )
        }
    }
}

private fun WorkInfo.isActiveForPersonalizedDiagnostics(): Boolean = when (state) {
    WorkInfo.State.ENQUEUED,
    WorkInfo.State.RUNNING,
    WorkInfo.State.BLOCKED,
    -> true

    WorkInfo.State.SUCCEEDED,
    WorkInfo.State.FAILED,
    WorkInfo.State.CANCELLED,
    -> false
}
