package com.weather.metro.notification

import android.content.Context

internal data class NotificationJournalRuntimeDiagnostics(
    val lastAttemptEpochMs: Long = 0L,
    val lastSuccessEpochMs: Long = 0L,
    val latestServerCursor: Long = 0L,
    val localCursorAtSuccess: Long = 0L,
    val deliveredEventsLastRun: Int = 0,
    val lastError: String = "",
)

/**
 * Small local-only trace for the authoritative HKO journal path.
 *
 * The personalized diagnostics already prove the shared 2D1/SWIRLS cadence. This
 * store fills the missing half of the picture: whether the separate official HKO
 * journal worker actually reached the Apps Script endpoint and advanced its cursor.
 * No coordinates, notification bodies, Firebase tokens or endpoint credentials are
 * stored here.
 */
internal class NotificationJournalDiagnosticsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(): NotificationJournalRuntimeDiagnostics = NotificationJournalRuntimeDiagnostics(
        lastAttemptEpochMs = preferences.getLong(KEY_LAST_ATTEMPT, 0L).coerceAtLeast(0L),
        lastSuccessEpochMs = preferences.getLong(KEY_LAST_SUCCESS, 0L).coerceAtLeast(0L),
        latestServerCursor = preferences.getLong(KEY_LATEST_SERVER_CURSOR, 0L).coerceAtLeast(0L),
        localCursorAtSuccess = preferences.getLong(KEY_LOCAL_CURSOR, 0L).coerceAtLeast(0L),
        deliveredEventsLastRun = preferences.getInt(KEY_DELIVERED_EVENTS, 0).coerceAtLeast(0),
        lastError = preferences.getString(KEY_LAST_ERROR, "").orEmpty(),
    )

    fun markAttempt(nowEpochMs: Long) {
        if (nowEpochMs <= 0L) return
        preferences.edit()
            .putLong(KEY_LAST_ATTEMPT, nowEpochMs)
            .apply()
    }

    fun markSuccess(
        nowEpochMs: Long,
        latestServerCursor: Long,
        localCursor: Long,
        deliveredEvents: Int,
    ) {
        if (nowEpochMs <= 0L) return
        preferences.edit()
            .putLong(KEY_LAST_ATTEMPT, nowEpochMs)
            .putLong(KEY_LAST_SUCCESS, nowEpochMs)
            .putLong(KEY_LATEST_SERVER_CURSOR, latestServerCursor.coerceAtLeast(0L))
            .putLong(KEY_LOCAL_CURSOR, localCursor.coerceAtLeast(0L))
            .putInt(KEY_DELIVERED_EVENTS, deliveredEvents.coerceAtLeast(0))
            .putString(KEY_LAST_ERROR, "")
            .apply()
    }

    fun markFailure(nowEpochMs: Long, error: Throwable) {
        if (nowEpochMs <= 0L) return
        val message = (error.message ?: error::class.java.simpleName)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(MAX_ERROR_LENGTH)
        preferences.edit()
            .putLong(KEY_LAST_ATTEMPT, nowEpochMs)
            .putString(KEY_LAST_ERROR, message)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "weather_notification_journal_diagnostics"
        const val KEY_LAST_ATTEMPT = "last_attempt_epoch_ms_v1"
        const val KEY_LAST_SUCCESS = "last_success_epoch_ms_v1"
        const val KEY_LATEST_SERVER_CURSOR = "latest_server_cursor_v1"
        const val KEY_LOCAL_CURSOR = "local_cursor_at_success_v1"
        const val KEY_DELIVERED_EVENTS = "delivered_events_last_run_v1"
        const val KEY_LAST_ERROR = "last_error_v1"
        const val MAX_ERROR_LENGTH = 300
    }
}
