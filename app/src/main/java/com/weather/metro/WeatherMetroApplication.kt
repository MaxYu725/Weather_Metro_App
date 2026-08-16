package com.weather.metro

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import com.weather.metro.data.settings.SettingsRepository
import com.weather.metro.notification.NotificationChannels
import com.weather.metro.notification.NotificationReconcileScheduler

class WeatherMetroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        if (SettingsRepository.notificationsEnabled(this)) {
            // Topic operations are persisted and retried by Firebase, so
            // enrolment does not depend on the location/notification dialogs.
            FirebaseMessaging.getInstance().subscribeToTopic(NotificationChannels.TOPIC_PRODUCTION)
            NotificationReconcileScheduler.ensurePeriodic(this)
            NotificationReconcileScheduler.enqueueNow(this)
        }
    }
}
