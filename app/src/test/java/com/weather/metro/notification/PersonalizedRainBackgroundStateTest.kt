package com.weather.metro.notification

import com.weather.metro.domain.rain.RainForecastGrid
import com.weather.metro.domain.rain.RainForecastSlot
import com.weather.metro.domain.rain.RainForecastSource
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.domain.rain.RainGridBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PersonalizedRainBackgroundStateTest {
    @Test
    fun `dry baseline keeps near term dense and scouts farther horizons`() {
        val run = Instant.parse("2026-08-16T08:00:00Z").toEpochMilli()
        val timeline = timeline(run)

        val plan = PersonalizedRainBackgroundFetchPlanner.plan(
            timeline = timeline,
            nowEpochMs = run,
            loadedFrameIndices = setOf(0),
            activeEpisode = false,
        )

        assertNotNull(plan)
        assertEquals(listOf(0, 1, 3, 5, 6, 9, 12, 15), plan!!.baselineFrameIndices)
        assertEquals(listOf(1, 3, 5, 6, 9, 12, 15), plan.baselineNetworkFrameIndices)
        assertEquals((2..15).filterNot { it in setOf(3, 5, 6, 9, 12, 15) }, plan.denseCompletionFrameIndices)
        assertEquals(16, plan.fullRunFrameCount)
        assertEquals(14_641, plan.gridCellCount)
        assertEquals(7L * 14_641L, plan.baselineNetworkCellValues)
        assertEquals(16L * 14_641L, plan.fullRunCellValues)
        assertEquals(0.5, plan.baselineRunFraction, 0.0001)
    }

    @Test
    fun `active episode requests every missing future frame`() {
        val run = Instant.parse("2026-08-16T08:00:00Z").toEpochMilli()
        val plan = PersonalizedRainBackgroundFetchPlanner.plan(
            timeline = timeline(run),
            nowEpochMs = run,
            loadedFrameIndices = setOf(0),
            activeEpisode = true,
        )!!

        assertEquals((1..15).toList(), plan.denseCompletionFrameIndices)
    }

    @Test
    fun `later device time drops expired frames and keeps next thirty minutes dense`() {
        val run = Instant.parse("2026-08-16T08:00:00Z").toEpochMilli()
        val now = run + 40 * 60_000L
        val plan = PersonalizedRainBackgroundFetchPlanner.plan(
            timeline = timeline(run),
            nowEpochMs = now,
            loadedFrameIndices = setOf(0),
        )!!

        assertEquals((2..15).toList(), plan.futureFrameIndices)
        assertTrue((2..6).all { it in plan.baselineFrameIndices })
        assertFalse(1 in plan.baselineFrameIndices)
    }

    @Test
    fun `wet scout promotes background evaluation to dense completion`() {
        val dry = sample(frameIndex = 3, intensity = PersonalizedRainIntensity.DRY)
        val wet = sample(frameIndex = 6, intensity = PersonalizedRainIntensity.LIGHT)

        assertFalse(
            PersonalizedRainBackgroundFetchPlanner.requiresDenseCompletion(
                activeEpisode = false,
                baselineSamples = listOf(dry),
            ),
        )
        assertTrue(
            PersonalizedRainBackgroundFetchPlanner.requiresDenseCompletion(
                activeEpisode = false,
                baselineSamples = listOf(dry, wet),
            ),
        )
        assertTrue(
            PersonalizedRainBackgroundFetchPlanner.requiresDenseCompletion(
                activeEpisode = true,
                baselineSamples = emptyList(),
            ),
        )
    }

    @Test
    fun `event decision is staged before committed episode advances`() {
        val committed = PersonalizedRainEpisodeState(
            episodeId = "old-episode",
            active = true,
            reachedNearTermWet = true,
            maxNotifiedIntensity = PersonalizedRainIntensity.LIGHT,
            transitionOrdinal = 1,
            lastNotificationEpochMs = 1000L,
            lastEventKind = PersonalizedForecastEventKind.RAIN_STARTING_SOON,
        )
        val target = committed.copy(
            maxNotifiedIntensity = PersonalizedRainIntensity.HEAVY,
            transitionOrdinal = 2,
            lastNotificationEpochMs = 20_000L,
            lastEventKind = PersonalizedForecastEventKind.RAIN_INTENSIFYING,
        )
        val decision = PersonalizedRainTransitionDecision(
            nextState = target,
            eventKind = PersonalizedForecastEventKind.RAIN_INTENSIFYING,
            eventIdentity = PersonalizedForecastEventIdentity(
                source = PersonalizedForecastSource.HKO_SWIRLS_GRID,
                kind = PersonalizedForecastEventKind.RAIN_INTENSIFYING,
                episodeId = "old-episode",
                transitionOrdinal = 1,
            ),
            horizon = PersonalizedForecastHorizon.MINUTES_30,
        )

        val staged = stagePersonalizedRainDecision(
            durableState = PersonalizedRainDurableState(committedEpisodeState = committed),
            decision = decision,
            sourceRunEpochMs = 10_000L,
            detectedAtEpochMs = 20_000L,
        )

        assertEquals(committed, staged.committedEpisodeState)
        assertNotNull(staged.pendingTransition)
        assertEquals(target, staged.pendingTransition!!.targetEpisodeState)
        assertEquals("PENDING_RAIN_INTENSIFYING", staged.status)

        val committedResult = commitPersonalizedRainPendingTransition(staged, 21_000L)
        assertEquals(target, committedResult.committedEpisodeState)
        assertNull(committedResult.pendingTransition)
        assertEquals("COMMITTED_RAIN_INTENSIFYING", committedResult.status)
    }

    @Test
    fun `non notification evaluator progress commits immediately`() {
        val next = PersonalizedRainEpisodeState(
            episodeId = "episode",
            active = true,
            dryConfirmationCount = 1,
            dryConfirmationStartedAtEpochMs = 10_000L,
        )
        val staged = stagePersonalizedRainDecision(
            durableState = PersonalizedRainDurableState(),
            decision = PersonalizedRainTransitionDecision(nextState = next),
            sourceRunEpochMs = 12_000L,
            detectedAtEpochMs = 13_000L,
        )

        assertEquals(next, staged.committedEpisodeState)
        assertNull(staged.pendingTransition)
        assertEquals("EVALUATED", staged.status)
    }

    @Test
    fun `existing pending transition is never overwritten by fresh evaluation`() {
        val target = PersonalizedRainEpisodeState(
            episodeId = "episode-a",
            active = true,
            transitionOrdinal = 1,
            lastEventKind = PersonalizedForecastEventKind.RAIN_APPROACHING,
        )
        val identity = PersonalizedForecastEventIdentity(
            source = PersonalizedForecastSource.HKO_SWIRLS_GRID,
            kind = PersonalizedForecastEventKind.RAIN_APPROACHING,
            episodeId = "episode-a",
            transitionOrdinal = 0,
        )
        val existing = PersonalizedRainDurableState(
            pendingTransition = PersonalizedRainPendingTransition(
                eventIdentity = identity,
                horizon = PersonalizedForecastHorizon.MINUTES_60,
                detectedAtEpochMs = 20_000L,
                sourceRunEpochMs = 10_000L,
                targetEpisodeState = target,
            ),
        )

        val result = stagePersonalizedRainDecision(
            durableState = existing,
            decision = PersonalizedRainTransitionDecision(
                nextState = PersonalizedRainEpisodeState(active = false),
            ),
            sourceRunEpochMs = 30_000L,
            detectedAtEpochMs = 31_000L,
        )

        assertEquals(existing, result)
    }

    @Test
    fun `durable codec round trips a pending transition`() {
        val episode = PersonalizedRainEpisodeState(
            episodeId = "run-123-42",
            active = true,
            reachedNearTermWet = true,
            maxNotifiedIntensity = PersonalizedRainIntensity.MODERATE,
            transitionOrdinal = 3,
            dryConfirmationCount = 1,
            dryConfirmationStartedAtEpochMs = 50_000L,
            lastNotificationEpochMs = 40_000L,
            lastEventKind = PersonalizedForecastEventKind.RAIN_STARTING_SOON,
        )
        val pendingTarget = episode.copy(
            maxNotifiedIntensity = PersonalizedRainIntensity.HEAVY,
            transitionOrdinal = 4,
            lastNotificationEpochMs = 60_000L,
            lastEventKind = PersonalizedForecastEventKind.RAIN_INTENSIFYING,
        )
        val state = PersonalizedRainDurableState(
            committedEpisodeState = episode,
            pendingTransition = PersonalizedRainPendingTransition(
                eventIdentity = PersonalizedForecastEventIdentity(
                    source = PersonalizedForecastSource.HKO_SWIRLS_GRID,
                    kind = PersonalizedForecastEventKind.RAIN_INTENSIFYING,
                    episodeId = episode.episodeId,
                    transitionOrdinal = 3,
                ),
                horizon = PersonalizedForecastHorizon.MINUTES_30,
                detectedAtEpochMs = 60_000L,
                sourceRunEpochMs = 30_000L,
                targetEpisodeState = pendingTarget,
            ),
            lastSourceRunEpochMs = 30_000L,
            lastCheckedEpochMs = 60_000L,
            status = "PENDING_RAIN_INTENSIFYING",
        )

        val decoded = PersonalizedRainEpisodeStateCodec.decode(
            PersonalizedRainEpisodeStateCodec.encode(state),
        )

        assertEquals(state, decoded)
    }

    @Test
    fun `invalid durable json fails closed to reset state`() {
        val decoded = PersonalizedRainEpisodeStateCodec.decode("{\"pendingTransition\":{\"bad\":true}}")

        assertEquals(PersonalizedRainEpisodeState(), decoded.committedEpisodeState)
        assertNull(decoded.pendingTransition)
        assertEquals("STATE_RESET", decoded.status)
    }

    private fun timeline(runEpochMs: Long): RainForecastTimeline {
        val slots = (0 until 16).map { index ->
            val lead = 30 + index * 6
            val valid = runEpochMs + lead * 60_000L
            RainForecastSlot(
                frameIndex = index,
                validTime = Instant.ofEpochMilli(valid).toString(),
                leadMinutes = lead,
                windowStart = Instant.ofEpochMilli(valid - 30 * 60_000L).toString(),
                windowEnd = Instant.ofEpochMilli(valid).toString(),
            )
        }
        return RainForecastTimeline(
            source = RainForecastSource.SWIRLS,
            issueTime = Instant.ofEpochMilli(runEpochMs).toString(),
            unit = "mm / 30 min",
            cadenceMinutes = 6,
            accumulationMinutes = 30,
            horizonMinutes = 120,
            grid = RainForecastGrid(
                rows = 121,
                cols = 121,
                cellCount = 14_641,
                orientation = "row-major-north-to-south-west-to-east",
                latitudes = doubleArrayOf(),
                longitudes = doubleArrayOf(),
                stepLat = null,
                stepLon = null,
                bounds = RainGridBounds(
                    north = 23.487,
                    south = 21.328,
                    east = 115.291,
                    west = 112.956,
                ),
            ),
            frames = slots,
        )
    }

    private fun sample(
        frameIndex: Int,
        intensity: PersonalizedRainIntensity,
    ): PersonalizedRainLocalSample = PersonalizedRainLocalSample(
        frameIndex = frameIndex,
        windowStartEpochMs = 1_000L,
        windowEndEpochMs = 2_000L,
        validTimeEpochMs = 2_000L,
        amountMmPer30Min = if (intensity == PersonalizedRainIntensity.DRY) 0.0 else 0.2,
        intensity = intensity,
    )
}
