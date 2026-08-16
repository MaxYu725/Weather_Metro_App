package com.weather.metro.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.weather.metro.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotificationJournalWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!SettingsRepository.notificationsEnabled(applicationContext)) {
            return@withContext Result.success()
        }

        val state = NotificationJournalState(applicationContext)
        val endpoint = state.endpoint() ?: return@withContext Result.success()
        val client = NotificationJournalClient()
        val publisher = WeatherNotificationPublisher(applicationContext)

        try {
            // A fresh installation should not replay months of historical alerts.
            // Ask for a cursor beyond the journal tail; the API returns an empty
            // page whose latestCursor is an atomic server-side baseline. Anything
            // appended after this response remains greater than the saved cursor.
            //
            // Do not return after initialization. If the first FCM wake-up races
            // this request, initializeForWakeup() may already have seeded one
            // cursor earlier. Continuing immediately from the persisted cursor
            // closes that race without waiting for the periodic safety net.
            if (!state.isInitialized()) {
                val baseline = client.fetch(endpoint, BOOTSTRAP_AFTER_CURSOR, 1)
                state.initializeAt(baseline.latestCursor)
            }

            var cursor = state.cursor()
            repeat(MAX_PAGES_PER_RUN) {
                val page = client.fetch(endpoint, cursor, PAGE_SIZE)
                check(page.latestCursor >= cursor) {
                    "Notification journal cursor regressed from $cursor to ${page.latestCursor}"
                }

                page.events.forEach { event ->
                    check(event.journalCursor > cursor) {
                        "Notification journal returned a non-forward cursor ${event.journalCursor} after $cursor"
                    }
                    if (!publisher.accept(event)) {
                        return@withContext Result.success()
                    }
                    // The local inbox commit happens inside accept(). Advance the
                    // server cursor only after that durable write has succeeded.
                    state.advanceCursor(event.journalCursor)
                    cursor = event.journalCursor
                }

                if (!page.hasMore) return@withContext Result.success()
                check(page.events.isNotEmpty()) {
                    "Notification journal reported more data without returning an event"
                }
            }
            error("Notification journal exceeded $MAX_PAGES_PER_RUN pages in one reconciliation")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES_PER_RUN = 20
        // JavaScript's largest exact integer; safely beyond any realistic Sheet row cursor.
        const val BOOTSTRAP_AFTER_CURSOR = 9_007_199_254_740_991L
    }
}
