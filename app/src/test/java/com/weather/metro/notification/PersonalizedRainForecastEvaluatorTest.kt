package com.weather.metro.notification

import com.weather.metro.domain.rain.RainForecastFrame
import com.weather.metro.domain.rain.RainForecastGrid
import com.weather.metro.domain.rain.RainGridBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PersonalizedRainForecastEvaluatorTest {
    @Test
    fun `bilinear sampler interpolates the four surrounding cells locally`() {
        val grid = RainForecastGrid(
            rows = 2,
            cols = 2,
            cellCount = 4,
            orientation = "row-major-north-to-south-west-to-east",
            latitudes = doubleArrayOf(23.0, 22.0),
            longitudes = doubleArrayOf(114.0, 115.0),
            stepLat = 1.0,
            stepLon = 1.0,
            bounds = RainGridBounds(north = 23.5, south = 21.5, east = 115.5, west = 113.5),
        )
        val frame = frame(
            frameIndex = 0,
            validLeadMinutes = 30,
            amount = 0.0,
            grid = grid,
            values = doubleArrayOf(0.0, 2.0, 4.0, 6.0),
        )

        val sampled = PersonalizedRainGridSampler.sample(frame, latitude = 22.5, longitude = 114.5)

        assertNotNull(sampled)
        assertEquals(3.0, sampled!!, 0.000001)
    }

    @Test
    fun `sampler fails closed outside the downloaded grid`() {
        val frame = frame(frameIndex = 0, validLeadMinutes = 30, amount = 1.0)

        assertNull(PersonalizedRainGridSampler.sample(frame, latitude = 22.4, longitude = 114.0))
    }

    @Test
    fun `rain compatibility thresholds match Rain Track point levels`() {
        val thresholds = PersonalizedRainThresholds()

        assertEquals(PersonalizedRainIntensity.DRY, thresholds.classify(0.19))
        assertEquals(PersonalizedRainIntensity.LIGHT, thresholds.classify(0.2))
        assertEquals(PersonalizedRainIntensity.MODERATE, thresholds.classify(0.5))
        assertEquals(PersonalizedRainIntensity.HEAVY, thresholds.classify(2.0))
        assertEquals(PersonalizedRainIntensity.VERY_HEAVY, thresholds.classify(10.0))
    }

    @Test
    fun `profile rejects a stale SWIRLS run`() {
        val now = RUN_EPOCH_MS + 46 * MINUTE_MS

        val profile = buildPersonalizedRainProfile(
            frames = listOf(frame(0, 60, 1.0)),
            latitude = TEST_LATITUDE,
            longitude = TEST_LONGITUDE,
            nowEpochMs = now,
        )

        assertNull(profile)
    }

    @Test
    fun `horizon summaries use valid time relative to evaluation time`() {
        val now = RUN_EPOCH_MS + 10 * MINUTE_MS
        val profile = requireNotNull(
            buildPersonalizedRainProfile(
                frames = listOf(
                    frame(0, 30, 0.3),
                    frame(5, 60, 3.0),
                    frame(12, 100, 12.0),
                ),
                latitude = TEST_LATITUDE,
                longitude = TEST_LONGITUDE,
                nowEpochMs = now,
            ),
        )

        val thirty = profile.horizonSummary(PersonalizedForecastHorizon.MINUTES_30, now)
        val sixty = profile.horizonSummary(PersonalizedForecastHorizon.MINUTES_60, now)
        val oneTwenty = profile.horizonSummary(PersonalizedForecastHorizon.MINUTES_120, now)

        assertEquals(1, thirty.sampleCount)
        assertEquals(PersonalizedRainIntensity.LIGHT, thirty.peakIntensity)
        assertEquals(20, thirty.firstWetWindowEndLeadMinutes)
        assertEquals(2, sixty.sampleCount)
        assertEquals(PersonalizedRainIntensity.HEAVY, sixty.peakIntensity)
        assertEquals(3, oneTwenty.sampleCount)
        assertEquals(PersonalizedRainIntensity.VERY_HEAVY, oneTwenty.peakIntensity)
    }

    @Test
    fun `new near term rain episode emits starting soon once`() {
        val now = RUN_EPOCH_MS + 10 * MINUTE_MS
        val profile = profile(now, frame(0, 30, 0.3))

        val decision = evaluatePersonalizedRainTransition(
            profile = profile,
            state = PersonalizedRainEpisodeState(),
            nowEpochMs = now,
        )

        assertEquals(PersonalizedForecastEventKind.RAIN_STARTING_SOON, decision.eventKind)
        assertEquals(PersonalizedForecastHorizon.MINUTES_30, decision.horizon)
        assertTrue(decision.nextState.active)
        assertTrue(decision.nextState.reachedNearTermWet)
        assertEquals(1, decision.nextState.transitionOrdinal)
        assertEquals(PersonalizedForecastSource.HKO_SWIRLS_GRID, decision.eventIdentity?.source)
    }

    @Test
    fun `rain more than thirty minutes away emits approaching`() {
        val now = RUN_EPOCH_MS + 10 * MINUTE_MS
        val profile = profile(now, frame(3, 48, 0.3))

        val decision = evaluatePersonalizedRainTransition(
            profile = profile,
            state = PersonalizedRainEpisodeState(),
            nowEpochMs = now,
        )

        assertEquals(PersonalizedForecastEventKind.RAIN_APPROACHING, decision.eventKind)
        assertEquals(PersonalizedForecastHorizon.MINUTES_60, decision.horizon)
        assertFalse(decision.nextState.reachedNearTermWet)
    }

    @Test
    fun `near term heavy rain has stronger initial transition`() {
        val now = RUN_EPOCH_MS + 10 * MINUTE_MS
        val profile = profile(now, frame(0, 30, 2.5))

        val decision = evaluatePersonalizedRainTransition(
            profile = profile,
            state = PersonalizedRainEpisodeState(),
            nowEpochMs = now,
        )

        assertEquals(PersonalizedForecastEventKind.HEAVY_RAIN_APPROACHING, decision.eventKind)
        assertEquals(PersonalizedRainIntensity.HEAVY, decision.nextState.maxNotifiedIntensity)
    }

    @Test
    fun `approaching episode can transition to starting soon after cooldown`() {
        val firstNow = RUN_EPOCH_MS + 10 * MINUTE_MS
        val profile = profile(firstNow, frame(3, 48, 0.3))
        val approaching = evaluatePersonalizedRainTransition(
            profile = profile,
            state = PersonalizedRainEpisodeState(),
            nowEpochMs = firstNow,
        )
        val secondNow = firstNow + 12 * MINUTE_MS

        val starting = evaluatePersonalizedRainTransition(
            profile = profile,
            state = approaching.nextState,
            nowEpochMs = secondNow,
        )

        assertEquals(PersonalizedForecastEventKind.RAIN_STARTING_SOON, starting.eventKind)
        assertTrue(starting.nextState.reachedNearTermWet)
        assertEquals(2, starting.nextState.transitionOrdinal)
        assertEquals(approaching.nextState.episodeId, starting.nextState.episodeId)
    }

    @Test
    fun `same episode can notify meaningful near term intensity increase`() {
        val firstNow = RUN_EPOCH_MS + 5 * MINUTE_MS
        val lightProfile = profile(firstNow, frame(0, 30, 0.3))
        val starting = evaluatePersonalizedRainTransition(
            profile = lightProfile,
            state = PersonalizedRainEpisodeState(),
            nowEpochMs = firstNow,
        )
        val secondNow = firstNow + 12 * MINUTE_MS
        val heavyProfile = profile(secondNow, frame(2, 42, 3.0))

        val intensified = evaluatePersonalizedRainTransition(
            profile = heavyProfile,
            state = starting.nextState,
            nowEpochMs = secondNow,
        )

        assertEquals(PersonalizedForecastEventKind.RAIN_INTENSIFYING, intensified.eventKind)
        assertEquals(PersonalizedRainIntensity.HEAVY, intensified.nextState.maxNotifiedIntensity)
        assertEquals(starting.nextState.episodeId, intensified.nextState.episodeId)
    }

    @Test
    fun `rapid refresh does not duplicate a transition inside cooldown`() {
        val firstNow = RUN_EPOCH_MS + 5 * MINUTE_MS
        val lightProfile = profile(firstNow, frame(0, 30, 0.3))
        val starting = evaluatePersonalizedRainTransition(
            profile = lightProfile,
            state = PersonalizedRainEpisodeState(),
            nowEpochMs = firstNow,
        )
        val heavyProfile = profile(firstNow + 6 * MINUTE_MS, frame(1, 36, 3.0))

        val decision = evaluatePersonalizedRainTransition(
            profile = heavyProfile,
            state = starting.nextState,
            nowEpochMs = firstNow + 6 * MINUTE_MS,
        )

        assertNull(decision.eventKind)
        assertEquals(1, decision.nextState.transitionOrdinal)
    }

    @Test
    fun `near term episode needs two dry confirmations spanning twelve minutes before ending`() {
        val firstNow = RUN_EPOCH_MS + 5 * MINUTE_MS
        val wetProfile = profile(firstNow, frame(0, 30, 0.3))
        val starting = evaluatePersonalizedRainTransition(
            profile = wetProfile,
            state = PersonalizedRainEpisodeState(),
            nowEpochMs = firstNow,
        )
        val dryFrame = frame(4, 54, 0.0)

        val firstDryAt = firstNow + 6 * MINUTE_MS
        val firstDry = evaluatePersonalizedRainTransition(
            profile = profile(firstDryAt, dryFrame),
            state = starting.nextState,
            nowEpochMs = firstDryAt,
        )
        assertNull(firstDry.eventKind)
        assertTrue(firstDry.nextState.active)
        assertEquals(1, firstDry.nextState.dryConfirmationCount)

        val secondDryAt = firstDryAt + 12 * MINUTE_MS
        val secondDry = evaluatePersonalizedRainTransition(
            profile = profile(secondDryAt, dryFrame),
            state = firstDry.nextState,
            nowEpochMs = secondDryAt,
        )

        assertEquals(PersonalizedForecastEventKind.RAIN_ENDING, secondDry.eventKind)
        assertFalse(secondDry.nextState.active)
        assertEquals(starting.nextState.episodeId, secondDry.eventIdentity?.episodeId)
    }

    @Test
    fun `withdrawn approaching forecast resets silently before it ever reaches near term`() {
        val firstNow = RUN_EPOCH_MS + 5 * MINUTE_MS
        val approachingProfile = profile(firstNow, frame(4, 54, 0.3))
        val approaching = evaluatePersonalizedRainTransition(
            profile = approachingProfile,
            state = PersonalizedRainEpisodeState(),
            nowEpochMs = firstNow,
        )
        assertFalse(approaching.nextState.reachedNearTermWet)

        val dryFrame = frame(10, 90, 0.0)
        val firstDryAt = firstNow + 6 * MINUTE_MS
        val firstDry = evaluatePersonalizedRainTransition(
            profile = profile(firstDryAt, dryFrame),
            state = approaching.nextState,
            nowEpochMs = firstDryAt,
        )
        val secondDryAt = firstDryAt + 12 * MINUTE_MS
        val reset = evaluatePersonalizedRainTransition(
            profile = profile(secondDryAt, dryFrame),
            state = firstDry.nextState,
            nowEpochMs = secondDryAt,
        )

        assertNull(reset.eventKind)
        assertFalse(reset.nextState.active)
        assertEquals("", reset.nextState.episodeId)
    }

    private fun profile(
        nowEpochMs: Long,
        vararg frames: RainForecastFrame,
    ): PersonalizedRainProfile = requireNotNull(
        buildPersonalizedRainProfile(
            frames = frames.toList(),
            latitude = TEST_LATITUDE,
            longitude = TEST_LONGITUDE,
            nowEpochMs = nowEpochMs,
        ),
    )

    private fun frame(
        frameIndex: Int,
        validLeadMinutes: Int,
        amount: Double,
        grid: RainForecastGrid = SINGLE_CELL_GRID,
        values: DoubleArray = doubleArrayOf(amount),
    ): RainForecastFrame {
        val run = Instant.ofEpochMilli(RUN_EPOCH_MS)
        val valid = run.plusSeconds(validLeadMinutes * 60L)
        val start = valid.minusSeconds(30 * 60L)
        return RainForecastFrame(
            frameIndex = frameIndex,
            runTime = run.toString(),
            validTime = valid.toString(),
            leadMinutes = validLeadMinutes,
            windowStart = start.toString(),
            windowEnd = valid.toString(),
            unit = "mm / 30 min",
            grid = grid,
            values = values,
        )
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        val RUN_EPOCH_MS: Long = Instant.parse("2026-08-16T09:00:00Z").toEpochMilli()
        const val TEST_LATITUDE = 22.3
        const val TEST_LONGITUDE = 114.2
        val SINGLE_CELL_GRID = RainForecastGrid(
            rows = 1,
            cols = 1,
            cellCount = 1,
            orientation = "row-major-north-to-south-west-to-east",
            latitudes = doubleArrayOf(TEST_LATITUDE),
            longitudes = doubleArrayOf(TEST_LONGITUDE),
            stepLat = null,
            stepLon = null,
            bounds = RainGridBounds(north = 22.31, south = 22.29, east = 114.21, west = 114.19),
        )
    }
}
