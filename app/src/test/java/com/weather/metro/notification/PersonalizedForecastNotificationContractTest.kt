package com.weather.metro.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizedForecastNotificationContractTest {
    @Test
    fun `rain horizons map only forward leads through two hours`() {
        assertNull(PersonalizedForecastNotificationPolicy.horizonForLeadMinutes(-1))
        assertEquals(
            PersonalizedForecastHorizon.MINUTES_30,
            PersonalizedForecastNotificationPolicy.horizonForLeadMinutes(0),
        )
        assertEquals(
            PersonalizedForecastHorizon.MINUTES_30,
            PersonalizedForecastNotificationPolicy.horizonForLeadMinutes(30),
        )
        assertEquals(
            PersonalizedForecastHorizon.MINUTES_60,
            PersonalizedForecastNotificationPolicy.horizonForLeadMinutes(31),
        )
        assertEquals(
            PersonalizedForecastHorizon.MINUTES_60,
            PersonalizedForecastNotificationPolicy.horizonForLeadMinutes(60),
        )
        assertEquals(
            PersonalizedForecastHorizon.MINUTES_120,
            PersonalizedForecastNotificationPolicy.horizonForLeadMinutes(61),
        )
        assertEquals(
            PersonalizedForecastHorizon.MINUTES_120,
            PersonalizedForecastNotificationPolicy.horizonForLeadMinutes(120),
        )
        assertNull(PersonalizedForecastNotificationPolicy.horizonForLeadMinutes(121))
    }

    @Test
    fun `source freshness fails closed outside the configured age window`() {
        val now = 20_000_000L
        val maxAge = 12 * 60 * 1000L

        assertTrue(
            PersonalizedForecastNotificationPolicy.isSourceFresh(
                sourceEpochMs = now - maxAge,
                nowEpochMs = now,
                maxAgeMs = maxAge,
            ),
        )
        assertTrue(
            PersonalizedForecastNotificationPolicy.isSourceFresh(
                sourceEpochMs = now + 5 * 60 * 1000L,
                nowEpochMs = now,
                maxAgeMs = maxAge,
            ),
        )
        assertFalse(
            PersonalizedForecastNotificationPolicy.isSourceFresh(
                sourceEpochMs = now - maxAge - 1,
                nowEpochMs = now,
                maxAgeMs = maxAge,
            ),
        )
        assertFalse(
            PersonalizedForecastNotificationPolicy.isSourceFresh(
                sourceEpochMs = now + 5 * 60 * 1000L + 1,
                nowEpochMs = now,
                maxAgeMs = maxAge,
            ),
        )
        assertFalse(
            PersonalizedForecastNotificationPolicy.isSourceFresh(
                sourceEpochMs = null,
                nowEpochMs = now,
                maxAgeMs = maxAge,
            ),
        )
    }

    @Test
    fun `lightning proximity uses hko aligned ten and fifteen kilometre bands`() {
        assertEquals(
            LightningProximityBand.CLOSE_10,
            PersonalizedForecastNotificationPolicy.lightningBand(9.9),
        )
        assertEquals(
            LightningProximityBand.CLOSE_10,
            PersonalizedForecastNotificationPolicy.lightningBand(10.0),
        )
        assertEquals(
            LightningProximityBand.NEARBY_15,
            PersonalizedForecastNotificationPolicy.lightningBand(10.1),
        )
        assertEquals(
            LightningProximityBand.NEARBY_15,
            PersonalizedForecastNotificationPolicy.lightningBand(15.0),
        )
        assertEquals(
            LightningProximityBand.OUTSIDE,
            PersonalizedForecastNotificationPolicy.lightningBand(15.1),
        )
        assertEquals(
            LightningProximityBand.OUTSIDE,
            PersonalizedForecastNotificationPolicy.lightningBand(null),
        )
        assertEquals(
            LightningProximityBand.OUTSIDE,
            PersonalizedForecastNotificationPolicy.lightningBand(-1.0),
        )
    }

    @Test
    fun `lightning episode clears only after thirty quiet minutes`() {
        val now = 50_000_000L
        val thirtyMinutes = PersonalizedForecastNotificationPolicy.LIGHTNING_CLEAR_INTERVAL_MS

        assertTrue(
            PersonalizedForecastNotificationPolicy.lightningEpisodeCleared(
                lastNearbyStrikeEpochMs = null,
                nowEpochMs = now,
            ),
        )
        assertFalse(
            PersonalizedForecastNotificationPolicy.lightningEpisodeCleared(
                lastNearbyStrikeEpochMs = now - thirtyMinutes + 1,
                nowEpochMs = now,
            ),
        )
        assertTrue(
            PersonalizedForecastNotificationPolicy.lightningEpisodeCleared(
                lastNearbyStrikeEpochMs = now - thirtyMinutes,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun `event identity is deterministic and transition ordinal participates in dedupe`() {
        val first = PersonalizedForecastEventIdentity(
            source = PersonalizedForecastSource.HKO_SWIRLS_GRID,
            kind = PersonalizedForecastEventKind.RAIN_APPROACHING,
            episodeId = "run-2026-08-16T09:00Z",
            transitionOrdinal = 0,
        )
        val replay = first.copy()
        val escalation = first.copy(
            kind = PersonalizedForecastEventKind.HEAVY_RAIN_APPROACHING,
            transitionOrdinal = 1,
        )

        assertEquals(first.dedupeKey(), replay.dedupeKey())
        assertNotEquals(first.dedupeKey(), escalation.dedupeKey())
    }
}
