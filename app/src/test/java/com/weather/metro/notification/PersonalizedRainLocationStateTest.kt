package com.weather.metro.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizedRainLocationStateTest {
    @Test
    fun `durable codec round trips local evaluation location`() {
        val state = PersonalizedRainDurableState(
            evaluationLocation = PersonalizedRainEvaluationLocation(
                latitude = 22.3023,
                longitude = 114.1746,
                label = "尖沙咀",
                district = "油尖旺區",
            ),
            status = "EVALUATED",
        )

        val decoded = PersonalizedRainEpisodeStateCodec.decode(
            PersonalizedRainEpisodeStateCodec.encode(state),
        )

        assertNotNull(decoded.evaluationLocation)
        assertEquals(state.evaluationLocation, decoded.evaluationLocation)
    }

    @Test
    fun `pre 2D2D state without evaluation location remains readable`() {
        val decoded = PersonalizedRainEpisodeStateCodec.decode(
            """{"committedEpisodeState":{},"lastSourceRunEpochMs":1,"lastCheckedEpochMs":2,"status":"EVALUATED","lastError":""}""",
        )

        assertEquals("EVALUATED", decoded.status)
        assertEquals(null, decoded.evaluationLocation)
    }

    @Test
    fun `personalised SWIRLS events use same derived notification TTL boundary`() {
        val event = WeatherNotificationEvent(
            eventId = "local-swirls-rain:test",
            title = "test",
            body = "test",
            channel = NotificationChannels.GENERAL,
            target = "weathermetro://tools",
            sourceType = SOURCE_TYPE_PERSONALIZED_RAIN,
            sentAtEpochMillis = 1_000L,
        )

        assertFalse(shouldExpireBeforePosting(event, 1_000L + 90 * 60 * 1000L))
        assertTrue(shouldExpireBeforePosting(event, 1_000L + 90 * 60 * 1000L + 1L))
        assertTrue(isLocationDerivedSourceType(SOURCE_TYPE_PERSONALIZED_RAIN))
        assertTrue(isLocationDerivedSourceType(SOURCE_TYPE_LOCATION_DERIVED))
    }
}
