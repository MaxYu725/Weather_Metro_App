package com.weather.metro

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import com.weather.metro.data.settings.SettingsRepository
import com.weather.metro.notification.LocationHeavyRainScheduler
import com.weather.metro.notification.NotificationChannels
import com.weather.metro.notification.NotificationReconcileScheduler
import com.weather.metro.notification.PersonalizedRainScheduler
import com.weather.metro.notification.shouldSchedulePersonalizedLocationNotifications

class WeatherMetroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)

        val notificationsEnabled = SettingsRepository.notificationsEnabled(this)
        if (notificationsEnabled) {
            // Topic operations are persisted and retried by Firebase, so
            // enrolment does not depend on the location/notification dialogs.
            FirebaseMessaging.getInstance().subscribeToTopic(NotificationChannels.TOPIC_PRODUCTION)
            NotificationReconcileScheduler.ensurePeriodic(this)
            NotificationReconcileScheduler.enqueueNow(this)
        }

        val preciseLocationEnabled = SettingsRepository.preciseLocationEnabled(this)
        val locationHeavyRainEnabled = SettingsRepository.locationHeavyRainNotificationsEnabled(this)
        val personalizedRainEnabled = SettingsRepository.personalizedRainNotificationsEnabled(this)
        if (
            shouldSchedulePersonalizedLocationNotifications(
                notificationsEnabled = notificationsEnabled,
                preciseLocationEnabled = preciseLocationEnabled,
                locationHeavyRainEnabled = locationHeavyRainEnabled,
                personalizedRainEnabled = personalizedRainEnabled,
            )
        ) {
            // The existing 2D1 periodic work remains the sole 15-minute cadence owner.
            // Neither path requests location here; both consume Weather Metro's cached fix.
            LocationHeavyRainScheduler.ensurePeriodic(this)
            if (locationHeavyRainEnabled) LocationHeavyRainScheduler.enqueueNow(this)
            if (personalizedRainEnabled) PersonalizedRainScheduler.enqueueNow(this)
        } else {
            LocationHeavyRainScheduler.disable(this)
            PersonalizedRainScheduler.disable(this)
        }
    }
}
