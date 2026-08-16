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
        val journalCursor = message.data["journalCursor"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        if (journalCursor > 0L) {
            val state = NotificationJournalState(applicationContext)
            val suppliedEndpoint = message.data["journalUrl"]
            runCatching { state.rememberEndpoint(suppliedEndpoint) }
            val endpointKnown = runCatching { state.endpoint() != null }.getOrDefault(false)
            if (endpointKnown) {
                // Do not display the byte-bounded FCM preview. WorkManager fetches
                // the complete authoritative event from the durable journal.
                NotificationReconcileScheduler.enqueueNow(applicationContext)
                return
            }
        }

        // Compatibility fallback for V1-V3 messages or a deployment that has
        // not yet published/configured its journal endpoint.
        val event = WeatherNotificationEventParser.parse(
            data = message.data,
            messageId = message.messageId,
            notificationTitle = message.notification?.title,
            notificationBody = message.notification?.body,
        ) ?: return
        WeatherNotificationPublisher(this).accept(event)
    }

    override fun onDeletedMessages() {
        runCatching { NotificationEventStore(this).markFullSyncRequired() }
        NotificationReconcileScheduler.enqueueNow(applicationContext)
    }
}
