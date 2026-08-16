package com.weather.metro

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import com.weather.metro.data.settings.SettingsRepository
import com.weather.metro.notification.LocationHeavyRainScheduler
import com.weather.metro.notification.NotificationChannels
import com.weather.metro.notification.NotificationReconcileScheduler
import com.weather.metro.notification.shouldSchedulePersonalizedLocationNotifications

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

            val preciseLocationEnabled = SettingsRepository.preciseLocationEnabled(this)
            val locationHeavyRainEnabled = SettingsRepository.locationHeavyRainNotificationsEnabled(this)
            val personalizedRainEnabled = SettingsRepository.personalizedRainNotificationsEnabled(this)
            if (
                shouldSchedulePersonalizedLocationNotifications(
                    notificationsEnabled = true,
                    preciseLocationEnabled = preciseLocationEnabled,
                    locationHeavyRainEnabled = locationHeavyRainEnabled,
                    personalizedRainEnabled = personalizedRainEnabled,
                )
            ) {
                // A single existing WorkManager cadence consumes the last precise location
                // already resolved by Weather Metro. Startup never creates a second location
                // owner or a second 15-minute personalised-weather periodic request.
                LocationHeavyRainScheduler.ensurePeriodic(this)
                LocationHeavyRainScheduler.enqueueNow(this)
            }
        }
    }
}
