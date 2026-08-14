package com.weather.metro.data.rain

import com.weather.metro.domain.rain.RainForecastSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RainForecastClientTest {
    @Test
    fun parsesSwirlsFrameAndBuildsSixMinuteTimeline() {
        val client = RainForecastClient()
        val frame = client.parseSwirlsFrame(swirlsFixture(frameIndex = 0))
        val timeline = client.buildSwirlsTimeline(frame)

        assertEquals(0, frame.frameIndex)
        assertEquals(121, frame.grid.rows)
        assertEquals(121, frame.grid.cols)
        assertEquals(14_641, frame.values.size)
        assertEquals("mm / 30 min", frame.unit)
        assertEquals(RainForecastSource.SWIRLS, timeline.source)
        assertEquals(16, timeline.frames.size)
        assertEquals(1, timeline.loadedFrameCount)
        assertEquals((30..120 step 6).toList(), timeline.frames.map { it.leadMinutes })
        assertEquals(30, timeline.accumulationMinutes)
        assertEquals(6, timeline.cadenceMinutes)
        assertNotNull(timeline.frame(0))
        assertNull(timeline.frame(15))
    }

    @Test
    fun rejectsSwirlsWrongUnitAndCellCount() {
        val client = RainForecastClient()

        assertTrue(runCatching { client.parseSwirlsFrame(swirlsFixture(unit = "mm / 6 min")) }.isFailure)
        assertTrue(runCatching { client.parseSwirlsFrame(swirlsFixture(valueCount = 14_640)) }.isFailure)
    }

    @Test
    fun rejectsSwirlsFrameFromDifferentModelRun() {
        val client = RainForecastClient()
        val first = client.parseSwirlsFrame(swirlsFixture(frameIndex = 0))
        val timeline = client.buildSwirlsTimeline(first)
        val changedRun = Instant.parse(RUN_TIME).plusSeconds(6 * 60L).toString()
        val next = client.parseSwirlsFrame(swirlsFixture(frameIndex = 1, runTime = changedRun))

        val result = runCatching { client.assertSwirlsFrameCompatible(timeline, next) }

        assertTrue(result.isFailure)
    }

    @Test
    fun clientUsesRolloverSizedTimeoutForSwirls() = runBlocking {
        val transport = RecordingTransport(swirlsFixture(frameIndex = 15))
        val client = RainForecastClient(transport)

        val result = client.loadSwirlsFrame(15)

        assertEquals("https://radar.max-yu.workers.dev/api/rain/swirls/frame?frame=15", transport.lastUrl)
        assertEquals(10_000, transport.connectTimeoutMs)
        assertEquals(60_000, transport.readTimeoutMs)
        assertEquals(15, result.value.frameIndex)
    }

    @Test
    fun normalizesNowcastFromObservedAxesInsteadOfMinimumStep() {
        val timeline = RainForecastClient().parseNowcast(nowcastFixture())

        assertEquals(RainForecastSource.NOWCAST, timeline.source)
        assertEquals(4, timeline.frames.size)
        assertEquals(listOf(30, 60, 90, 120), timeline.frames.map { it.leadMinutes })
        assertEquals(3, timeline.grid.rows)
        assertEquals(3, timeline.grid.cols)
        assertArrayEquals(doubleArrayOf(22.139, 22.119, 22.100), timeline.grid.latitudes, 0.000001)
        assertArrayEquals(doubleArrayOf(114.100, 114.119, 114.139), timeline.grid.longitudes, 0.000001)
        assertEquals(0.0195, timeline.grid.stepLat ?: 0.0, 0.000001)
        assertEquals(0.0195, timeline.grid.stepLon ?: 0.0, 0.000001)
        val firstValues = timeline.frame(0)?.values ?: error("missing fallback frame")
        assertEquals(20.0, firstValues[0], 0.000001)
        assertEquals(22.0, firstValues[2], 0.000001)
        assertEquals(0.0, firstValues[6], 0.000001)
        assertTrue(timeline.frames.all { it.frame != null })
    }

    @Test
    fun rejectsNowcastDuplicateOrMissingGridCell() {
        val result = runCatching {
            RainForecastClient().parseNowcast(nowcastFixture(duplicateFirstFrameCell = true))
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun loadedFrameCanBeAddedWithoutReplacingTimelineIdentity() {
        val client = RainForecastClient()
        val first = client.parseSwirlsFrame(swirlsFixture(frameIndex = 0))
        val timeline = client.buildSwirlsTimeline(first)
        val second = client.parseSwirlsFrame(swirlsFixture(frameIndex = 1))
        client.assertSwirlsFrameCompatible(timeline, second)

        val updated = timeline.withLoadedFrame(second)

        assertEquals(timeline.issueTime, updated.issueTime)
        assertEquals(2, updated.loadedFrameCount)
        assertNotNull(updated.frame(1))
        assertFalse(updated === timeline)
    }

    private class RecordingTransport(private val response: String) : RainHttpTransport {
        var lastUrl: String? = null
        var connectTimeoutMs: Int? = null
        var readTimeoutMs: Int? = null

        override suspend fun get(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
            lastUrl = url
            this.connectTimeoutMs = connectTimeoutMs
            this.readTimeoutMs = readTimeoutMs
            return response
        }
    }

    companion object {
        private const val RUN_TIME = "2026-08-14T04:00:00Z"

        private fun swirlsFixture(
            frameIndex: Int = 0,
            runTime: String = RUN_TIME,
            unit: String = "mm / 30 min",
            valueCount: Int = 14_641,
        ): String {
            val run = Instant.parse(runTime)
            val lead = 30 + frameIndex * 6
            val valid = run.plusSeconds(lead * 60L)
            val latitudes = JSONArray()
            val longitudes = JSONArray()
            repeat(121) { index ->
                latitudes.put(23.48 - index * 0.01)
                longitudes.put(114.0 + index * 0.01)
            }
            val values = JSONArray()
            repeat(valueCount) { index -> values.put((index % 17) / 10.0) }
            return JSONObject()
                .put("ok", true)
                .put("version", "2.5.0")
                .put("frameIndex", frameIndex)
                .put("runTime", run.toString())
                .put("validTime", valid.toString())
                .put("leadMinutes", lead)
                .put("windowStart", valid.minusSeconds(30 * 60L).toString())
                .put("windowEnd", valid.toString())
                .put("unit", unit)
                .put(
                    "grid",
                    JSONObject()
                        .put("rows", 121)
                        .put("cols", 121)
                        .put("cellCount", 14_641)
                        .put("orientation", "row-major-north-to-south-west-to-east")
                        .put("latitudes", latitudes)
                        .put("longitudes", longitudes)
                        .put("stepLat", 0.01)
                        .put("stepLon", 0.01)
                        .put(
                            "bounds",
                            JSONObject()
                                .put("north", 23.485)
                                .put("south", 22.275)
                                .put("west", 113.995)
                                .put("east", 115.205),
                        ),
                )
                .put("values", values)
                .put("validation", JSONObject().put("ready", true).put("runTimeMatchesIndex", true))
                .put("source", JSONObject().put("bytes", 123456))
                .toString()
        }

        private fun nowcastFixture(duplicateFirstFrameCell: Boolean = false): String {
            val issue = Instant.parse(RUN_TIME)
            val latitudes = listOf(22.100, 22.119, 22.139)
            val longitudes = listOf(114.100, 114.119, 114.139)
            val frames = JSONArray()
            listOf(30, 60, 90, 120).forEachIndexed { frameIndex, lead ->
                val points = JSONArray()
                latitudes.forEachIndexed { latIndex, lat ->
                    longitudes.asReversed().forEach { lon ->
                        val lonIndex = longitudes.indexOf(lon)
                        points.put(JSONArray().put(lat).put(lon).put(frameIndex * 100.0 + latIndex * 10.0 + lonIndex))
                    }
                }
                if (duplicateFirstFrameCell && frameIndex == 0) {
                    val duplicate = JSONArray().put(latitudes.first()).put(longitudes.last()).put(999.0)
                    points.put(points.length() - 1, duplicate)
                }
                frames.put(
                    JSONObject()
                        .put("time", issue.plusSeconds(lead * 60L).toString())
                        .put("leadMinutes", lead)
                        .put("points", points),
                )
            }
            return JSONObject()
                .put("ok", true)
                .put("version", "2.5.0")
                .put("issueTime", issue.toString())
                .put("unit", "mm / 30 min")
                .put(
                    "grid",
                    JSONObject()
                        .put("minLat", 22.100)
                        .put("maxLat", 22.139)
                        .put("stepLat", 0.019)
                        .put("minLon", 114.100)
                        .put("maxLon", 114.139)
                        .put("stepLon", 0.019),
                )
                .put("frames", frames)
                .toString()
        }
    }
}
