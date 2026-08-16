package com.weather.metro.notification

import com.weather.metro.domain.rain.RainForecastFrame
import com.weather.metro.domain.rain.RainForecastGrid
import com.weather.metro.domain.rain.RainForecastSlot
import com.weather.metro.domain.rain.RainForecastSource
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.domain.rain.RainGridBounds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PersonalizedRainRuntimeTest {
    @Test
    fun `dry runtime uses only adaptive baseline frames`() = runBlocking {
        val runEpochMs = Instant.parse("2026-08-16T08:00:00Z").toEpochMilli()
        val source = FakeFrameSource(runEpochMs) { 0.0 }
        val store = FakeStateStore()
        val sink = FakeEventSink()
        val runtime = PersonalizedRainRuntime(source, store, sink)

        val result = runtime.execute(location(), runEpochMs)

        assertEquals("EVALUATED", result.status)
        assertEquals(listOf(0, 1, 3, 5, 6, 9, 12, 15), result.fetchedFrameIndices)
        assertEquals(listOf(0, 1, 3, 5, 6, 9, 12, 15), source.loadedIndices.sorted())
        assertTrue(sink.accepted.isEmpty())
        assertFalse(store.state.committedEpisodeState.active)
        assertEquals(location().district, store.state.evaluationLocation?.district)
    }

    @Test
    fun `wet scout promotes run to all sixteen frames before notifying`() = runBlocking {
        val runEpochMs = Instant.parse("2026-08-16T08:00:00Z").toEpochMilli()
        val source = FakeFrameSource(runEpochMs) { frameIndex ->
            if (frameIndex == 6) 0.2 else 0.0
        }
        val store = FakeStateStore()
        val sink = FakeEventSink()
        val runtime = PersonalizedRainRuntime(source, store, sink)

        val result = runtime.execute(location(), runEpochMs)

        assertEquals((0..15).toList(), result.fetchedFrameIndices)
        assertEquals((0..15).toList(), source.loadedIndices.sorted())
        assertEquals(PersonalizedForecastEventKind.RAIN_APPROACHING, result.publishedEventKind)
        assertEquals(1, sink.accepted.size)
        assertEquals(SOURCE_TYPE_PERSONALIZED_RAIN, sink.accepted.single().sourceType)
        assertEquals("weathermetro://tools", sink.accepted.single().target)
        assertNull(store.state.pendingTransition)
        assertTrue(store.state.committedEpisodeState.active)
    }

    @Test
    fun `pending transition is replayed before any fresh SWIRLS request`() = runBlocking {
        val runEpochMs = Instant.parse("2026-08-16T08:00:00Z").toEpochMilli()
        val actions = mutableListOf<String>()
        val oldLocation = location().toRainEvaluationLocation()
        val target = PersonalizedRainEpisodeState(
            episodeId = "episode-a",
            active = true,
            transitionOrdinal = 1,
            reachedNearTermWet = false,
            lastNotificationEpochMs = runEpochMs - 20 * 60_000L,
            lastEventKind = PersonalizedForecastEventKind.RAIN_APPROACHING,
        )
        val store = FakeStateStore(
            PersonalizedRainDurableState(
                evaluationLocation = oldLocation,
                pendingTransition = PersonalizedRainPendingTransition(
                    eventIdentity = PersonalizedForecastEventIdentity(
                        source = PersonalizedForecastSource.HKO_SWIRLS_GRID,
                        kind = PersonalizedForecastEventKind.RAIN_APPROACHING,
                        episodeId = "episode-a",
                        transitionOrdinal = 0,
                    ),
                    horizon = PersonalizedForecastHorizon.MINUTES_60,
                    detectedAtEpochMs = runEpochMs - 20 * 60_000L,
                    sourceRunEpochMs = runEpochMs - 30 * 60_000L,
                    targetEpisodeState = target,
                ),
            ),
        )
        val source = FakeFrameSource(runEpochMs, amountForFrame = { 0.0 }, actions = actions)
        val sink = FakeEventSink(actions = actions)
        val runtime = PersonalizedRainRuntime(source, store, sink)

        runtime.execute(location(), runEpochMs)

        assertEquals("accept:RAIN_APPROACHING", actions.first())
        assertTrue(actions.indexOf("load:0") > 0)
        assertEquals(1, sink.accepted.size)
    }

    @Test
    fun `moving outside evaluation area clears old pending event instead of replaying it`() = runBlocking {
        val runEpochMs = Instant.parse("2026-08-16T08:00:00Z").toEpochMilli()
        val previous = PersonalizedRainEvaluationLocation(
            latitude = 22.3000,
            longitude = 114.1700,
            label = "舊位置",
            district = "油尖旺區",
        )
        val pendingTarget = PersonalizedRainEpisodeState(
            episodeId = "old",
            active = true,
            transitionOrdinal = 1,
        )
        val store = FakeStateStore(
            PersonalizedRainDurableState(
                evaluationLocation = previous,
                pendingTransition = PersonalizedRainPendingTransition(
                    eventIdentity = PersonalizedForecastEventIdentity(
                        source = PersonalizedForecastSource.HKO_SWIRLS_GRID,
                        kind = PersonalizedForecastEventKind.RAIN_APPROACHING,
                        episodeId = "old",
                        transitionOrdinal = 0,
                    ),
                    horizon = PersonalizedForecastHorizon.MINUTES_60,
                    detectedAtEpochMs = runEpochMs - 5 * 60_000L,
                    sourceRunEpochMs = runEpochMs - 30 * 60_000L,
                    targetEpisodeState = pendingTarget,
                ),
            ),
        )
        val moved = PersonalizedNotificationLocation(
            latitude = 22.3200,
            longitude = 114.1700,
            label = "新位置",
            district = "油尖旺區",
            accuracyMetres = 20,
            updatedAtEpochMs = runEpochMs,
        )
        val sink = FakeEventSink()
        val runtime = PersonalizedRainRuntime(
            FakeFrameSource(runEpochMs) { 0.0 },
            store,
            sink,
        )

        runtime.execute(moved, runEpochMs)

        assertEquals(1, sink.discardCount)
        assertTrue(sink.accepted.isEmpty())
        assertEquals("新位置", store.state.evaluationLocation?.label)
        assertFalse(store.state.committedEpisodeState.active)
    }

    @Test
    fun `deferred publisher keeps transition pending for a later worker`() = runBlocking {
        val runEpochMs = Instant.parse("2026-08-16T08:00:00Z").toEpochMilli()
        val source = FakeFrameSource(runEpochMs) { frameIndex ->
            if (frameIndex == 6) 0.2 else 0.0
        }
        val store = FakeStateStore()
        val sink = FakeEventSink(acceptResult = false)
        val runtime = PersonalizedRainRuntime(source, store, sink)

        val result = runtime.execute(location(), runEpochMs)

        assertEquals("PUBLISH_DEFERRED_RAIN_APPROACHING", result.status)
        assertEquals(1, sink.accepted.size)
        assertEquals(PersonalizedForecastEventKind.RAIN_APPROACHING, store.state.pendingTransition?.eventIdentity?.kind)
        assertFalse(store.state.committedEpisodeState.active)
    }

    @Test
    fun `notification event id is deterministic and clearly derived`() {
        val runEpochMs = Instant.parse("2026-08-16T08:00:00Z").toEpochMilli()
        val pending = PersonalizedRainPendingTransition(
            eventIdentity = PersonalizedForecastEventIdentity(
                source = PersonalizedForecastSource.HKO_SWIRLS_GRID,
                kind = PersonalizedForecastEventKind.HEAVY_RAIN_APPROACHING,
                episodeId = "episode-123",
                transitionOrdinal = 2,
            ),
            horizon = PersonalizedForecastHorizon.MINUTES_30,
            detectedAtEpochMs = runEpochMs,
            sourceRunEpochMs = runEpochMs - 6 * 60_000L,
            targetEpisodeState = PersonalizedRainEpisodeState(active = true),
        )
        val evaluationLocation = location().toRainEvaluationLocation()

        val first = buildPersonalizedRainNotificationEvent(pending, evaluationLocation)
        val second = buildPersonalizedRainNotificationEvent(pending, evaluationLocation)

        assertEquals(first.eventId, second.eventId)
        assertTrue(first.eventId.startsWith("local-swirls-rain:"))
        assertEquals(SOURCE_TYPE_PERSONALIZED_RAIN, first.sourceType)
        assertTrue(first.body.contains("並非天文台雨量警告"))
        assertEquals(0L, first.journalCursor)
    }

    @Test
    fun `same district GPS jitter remains the same evaluation area`() {
        val first = PersonalizedRainEvaluationLocation(22.3000, 114.1700, "A", "油尖旺區")
        val nearby = PersonalizedRainEvaluationLocation(22.3005, 114.1705, "B", "油尖旺區")
        val farther = PersonalizedRainEvaluationLocation(22.3150, 114.1700, "C", "油尖旺區")

        assertTrue(samePersonalizedRainEvaluationArea(first, nearby))
        assertFalse(samePersonalizedRainEvaluationArea(first, farther))
    }

    private class FakeStateStore(
        initial: PersonalizedRainDurableState = PersonalizedRainDurableState(),
    ) : PersonalizedRainStatePersistence {
        var state: PersonalizedRainDurableState = initial
        val writes = mutableListOf<PersonalizedRainDurableState>()

        override fun read(): PersonalizedRainDurableState = state

        override fun write(state: PersonalizedRainDurableState) {
            this.state = state
            writes += state
        }
    }

    private class FakeEventSink(
        private val acceptResult: Boolean = true,
        private val actions: MutableList<String>? = null,
    ) : PersonalizedRainEventSink {
        val accepted = mutableListOf<WeatherNotificationEvent>()
        var discardCount = 0

        override fun accept(event: WeatherNotificationEvent): Boolean {
            actions?.add("accept:${event.eventKind}")
            accepted += event
            return acceptResult
        }

        override fun discardPendingRainEvents() {
            actions?.add("discard")
            discardCount += 1
        }
    }

    private class FakeFrameSource(
        private val runEpochMs: Long,
        private val actions: MutableList<String>? = null,
        private val amountForFrame: (Int) -> Double,
    ) : PersonalizedRainFrameSource {
        val loadedIndices = mutableListOf<Int>()
        private val grid = testGrid()
        private val frames = (0 until 16).associateWith { frameIndex ->
            frame(frameIndex, runEpochMs, amountForFrame(frameIndex), grid)
        }
        private val timeline = timeline(runEpochMs, grid)

        override suspend fun loadDiscovery(): PersonalizedRainDiscovery {
            loadedIndices += 0
            actions?.add("load:0")
            return PersonalizedRainDiscovery(timeline, frames.getValue(0))
        }

        override suspend fun loadFrame(
            timeline: RainForecastTimeline,
            frameIndex: Int,
        ): RainForecastFrame {
            loadedIndices += frameIndex
            actions?.add("load:$frameIndex")
            return frames.getValue(frameIndex)
        }
    }

    companion object {
        private fun location(): PersonalizedNotificationLocation = PersonalizedNotificationLocation(
            latitude = 22.305,
            longitude = 114.175,
            label = "尖沙咀",
            district = "油尖旺區",
            accuracyMetres = 20,
            updatedAtEpochMs = Instant.parse("2026-08-16T08:00:00Z").toEpochMilli(),
        )

        private fun testGrid(): RainForecastGrid = RainForecastGrid(
            rows = 2,
            cols = 2,
            cellCount = 4,
            orientation = "row-major-north-to-south-west-to-east",
            latitudes = doubleArrayOf(22.31, 22.30),
            longitudes = doubleArrayOf(114.17, 114.18),
            stepLat = 0.01,
            stepLon = 0.01,
            bounds = RainGridBounds(
                north = 22.315,
                south = 22.295,
                east = 114.185,
                west = 114.165,
            ),
        )

        private fun frame(
            frameIndex: Int,
            runEpochMs: Long,
            amount: Double,
            grid: RainForecastGrid,
        ): RainForecastFrame {
            val lead = 30 + frameIndex * 6
            val validEpochMs = runEpochMs + lead * 60_000L
            return RainForecastFrame(
                frameIndex = frameIndex,
                runTime = Instant.ofEpochMilli(runEpochMs).toString(),
                validTime = Instant.ofEpochMilli(validEpochMs).toString(),
                leadMinutes = lead,
                windowStart = Instant.ofEpochMilli(validEpochMs - 30 * 60_000L).toString(),
                windowEnd = Instant.ofEpochMilli(validEpochMs).toString(),
                unit = "mm / 30 min",
                grid = grid,
                values = doubleArrayOf(amount, amount, amount, amount),
            )
        }

        private fun timeline(
            runEpochMs: Long,
            grid: RainForecastGrid,
        ): RainForecastTimeline = RainForecastTimeline(
            source = RainForecastSource.SWIRLS,
            issueTime = Instant.ofEpochMilli(runEpochMs).toString(),
            unit = "mm / 30 min",
            cadenceMinutes = 6,
            accumulationMinutes = 30,
            horizonMinutes = 120,
            grid = grid,
            frames = (0 until 16).map { frameIndex ->
                val lead = 30 + frameIndex * 6
                val validEpochMs = runEpochMs + lead * 60_000L
                RainForecastSlot(
                    frameIndex = frameIndex,
                    validTime = Instant.ofEpochMilli(validEpochMs).toString(),
                    leadMinutes = lead,
                    windowStart = Instant.ofEpochMilli(validEpochMs - 30 * 60_000L).toString(),
                    windowEnd = Instant.ofEpochMilli(validEpochMs).toString(),
                )
            },
        )
    }
}
