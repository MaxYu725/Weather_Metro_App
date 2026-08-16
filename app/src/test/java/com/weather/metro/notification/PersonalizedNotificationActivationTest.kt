package com.weather.metro.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizedNotificationActivationTest {
    @Test
    fun `shared cadence runs when either location rain stream is enabled`() {
        assertTrue(
            shouldSchedulePersonalizedLocationNotifications(
                notificationsEnabled = true,
                preciseLocationEnabled = true,
                locationHeavyRainEnabled = true,
                personalizedRainEnabled = false,
            ),
        )
        assertTrue(
            shouldSchedulePersonalizedLocationNotifications(
                notificationsEnabled = true,
                preciseLocationEnabled = true,
                locationHeavyRainEnabled = false,
                personalizedRainEnabled = true,
            ),
        )
        assertTrue(
            shouldSchedulePersonalizedLocationNotifications(
                notificationsEnabled = true,
                preciseLocationEnabled = true,
                locationHeavyRainEnabled = true,
                personalizedRainEnabled = true,
            ),
        )
    }

    @Test
    fun `shared cadence stays off without global notifications precise location or enabled streams`() {
        assertFalse(
            shouldSchedulePersonalizedLocationNotifications(
                notificationsEnabled = false,
                preciseLocationEnabled = true,
                locationHeavyRainEnabled = true,
                personalizedRainEnabled = true,
            ),
        )
        assertFalse(
            shouldSchedulePersonalizedLocationNotifications(
                notificationsEnabled = true,
                preciseLocationEnabled = false,
                locationHeavyRainEnabled = true,
                personalizedRainEnabled = true,
            ),
        )
        assertFalse(
            shouldSchedulePersonalizedLocationNotifications(
                notificationsEnabled = true,
                preciseLocationEnabled = true,
                locationHeavyRainEnabled = false,
                personalizedRainEnabled = false,
            ),
        )
    }
}
