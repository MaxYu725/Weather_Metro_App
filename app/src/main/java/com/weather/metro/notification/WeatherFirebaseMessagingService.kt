package com.weather.metro.notification

import android.annotation.SuppressLint
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.weather.metro.data.settings.SettingsRepository

@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class WeatherFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        if (SettingsRepository.notificationsEnabled(applicationContext)) {
            FirebaseMessaging.getInstance().subscribeToTopic(NotificationChannels.TOPIC_PRODUCTION)
            NotificationReconcileScheduler.ensurePeriodic(applicationContext)
            NotificationReconcileScheduler.enqueueNow(applicationContext)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val event = WeatherNotificationEventParser.parse(
            data = message.data,
            messageId = message.messageId,
            notificationTitle = message.notification?.title,
            notificationBody = message.notification?.body,
        )
        val journalCursor = message.data["journalCursor"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        var endpointKnown = false
        if (journalCursor > 0L) {
            val state = NotificationJournalState(applicationContext)
            // If this is the first journal-aware event seen by a fresh client,
            // seed one cursor earlier before the bootstrap worker can baseline at
            // the tail. This guarantees the wake-up event itself remains fetchable.
            runCatching { state.initializeForWakeup(journalCursor) }
            val suppliedEndpoint = message.data["journalUrl"]
            runCatching { state.rememberEndpoint(suppliedEndpoint) }
            endpointKnown = runCatching { state.endpoint() != null }.getOrDefault(false)
        }

        val plan = notificationMessageDeliveryPlan(
            previewAvailable = event != null,
            journalCursor = journalCursor,
            endpointKnown = endpointKnown,
        )
        if (plan.publishPreview) {
            // Post the already-validated, byte-bounded FCM preview immediately.
            // Journal reconciliation then upgrades the same stable event ID with
            // the full authoritative body. A dead/stale journal URL can therefore
            // delay full text, but can no longer suppress the notification itself.
            WeatherNotificationPublisher(this).accept(requireNotNull(event))
        }
        if (plan.reconcileJournal) {
            NotificationReconcileScheduler.enqueueNow(applicationContext)
        }
    }

    override fun onDeletedMessages() {
        runCatching { NotificationEventStore(this).markFullSyncRequired() }
        NotificationReconcileScheduler.enqueueNow(applicationContext)
    }
}

internal data class NotificationMessageDeliveryPlan(
    val publishPreview: Boolean,
    val reconcileJournal: Boolean,
)

internal fun notificationMessageDeliveryPlan(
    previewAvailable: Boolean,
    journalCursor: Long,
    endpointKnown: Boolean,
): NotificationMessageDeliveryPlan = NotificationMessageDeliveryPlan(
    // V1-V3 messages use this as the complete event. V4+ messages use it as
    // immediate visible delivery while the journal upgrades/reconciles by ID.
    publishPreview = previewAvailable,
    reconcileJournal = journalCursor > 0L && endpointKnown,
)
