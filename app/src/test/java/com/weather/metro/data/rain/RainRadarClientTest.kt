package com.weather.metro.data.rain

import com.weather.metro.data.tools.RainRadarMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainRadarClientTest {
    @Test
    fun parsesProductionRadarContract() {
        val contract = RainRadarClient().parseContract(CAPABILITIES_FIXTURE)

        assertEquals("1.0", contract.version)
        assertEquals(listOf(64, 256), contract.rangesKm)
        assertEquals(listOf(2, 3), contract.heightsForRange(64))
        assertEquals(listOf(3), contract.heightsForRange(256))
        assertEquals(3, contract.defaultHeightKm)
        assertEquals(listOf("live", "test"), contract.modes)
        assertEquals(6, contract.cadenceMinutes)
        assertEquals(30, contract.maxFrames)
        assertTrue(contract.supports(64, 2))
        assertTrue(contract.supports(64, 3))
        assertTrue(contract.supports(256, 3))
        assertFalse(contract.supports(256, 2))
    }

    @Test
    fun parsesLiveRadarFramesAndGeographicBounds() {
        val timeline = RainRadarClient().parseFrames(
            payload = LIVE_FRAMES_FIXTURE,
            expectedRangeKm = 64,
            expectedHeightKm = 2,
            expectedMode = RainRadarMode.LIVE,
        )

        assertEquals("2.5.0", timeline.workerVersion)
        assertEquals("1.0", timeline.contractVersion)
        assertEquals(64, timeline.rangeKm)
        assertEquals(2, timeline.heightKm)
        assertEquals("live", timeline.mode)
        assertEquals(6, timeline.cadenceMinutes)
        assertEquals("transparent-georeferenced-overlay", timeline.renderMode)
        assertEquals(2, timeline.frames.size)
        assertEquals("2026-08-14T10:42:00.000Z", timeline.frames.last().time)
        assertEquals("/api/radar/image?id=def", timeline.frames.last().imageUrl)
        assertEquals(22.87890, timeline.frames.first().bounds.north, 0.000001)
        assertEquals(21.72777, timeline.frames.first().bounds.south, 0.000001)
        assertEquals(114.79378, timeline.frames.first().bounds.east, 0.000001)
        assertEquals(113.54956, timeline.frames.first().bounds.west, 0.000001)
    }

    @Test
    fun acceptsWorkerTestImageRouteForTestMode() {
        val payload = LIVE_FRAMES_FIXTURE
            .replace("\"mode\": \"live\"", "\"mode\": \"test\"")
            .replace("/api/radar/image?id=abc", "/api/radar/test-image?range=64&frame=0")
            .replace("/api/radar/image?id=def", "/api/radar/test-image?range=64&frame=1")

        val timeline = RainRadarClient().parseFrames(
            payload = payload,
            expectedRangeKm = 64,
            expectedHeightKm = 2,
            expectedMode = RainRadarMode.TEST,
        )

        assertEquals("test", timeline.mode)
        assertTrue(timeline.frames.all { it.imageUrl.startsWith("/api/radar/test-image?") })
    }

    @Test
    fun rejectsRadarImageOutsideWorkerAllowList() {
        val broken = LIVE_FRAMES_FIXTURE.replace(
            "/api/radar/image?id=abc",
            "https://example.com/radar.png",
        )

        val result = runCatching { RainRadarClient().parseFrames(broken) }

        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsMismatchedRadarProduct() {
        val result = runCatching {
            RainRadarClient().parseFrames(
                payload = LIVE_FRAMES_FIXTURE,
                expectedRangeKm = 256,
                expectedHeightKm = 3,
                expectedMode = RainRadarMode.LIVE,
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun clientBuildsRadarEndpointThroughRegistry() = runBlocking {
        val transport = RecordingRadarTransport(textResponse = LIVE_FRAMES_FIXTURE)
        val client = RainRadarClient(transport)

        val result = client.loadFrames(64, 2, RainRadarMode.LIVE)

        assertEquals(
            "https://radar.max-yu.workers.dev/api/radar/frames?range=64&height=2&mode=live",
            transport.lastTextUrl,
        )
        assertEquals(2, result.value.frames.size)
        assertFalse(result.rawPayload.isBlank())
    }

    @Test
    fun imageLoadUsesApprovedWorkerRouteAndLongerReadTimeout() = runBlocking {
        val expected = byteArrayOf(1, 2, 3, 4)
        val transport = RecordingRadarTransport(
            textResponse = LIVE_FRAMES_FIXTURE,
            bytesResponse = expected,
        )
        val client = RainRadarClient(transport)

        val bytes = client.loadImage("/api/radar/image?id=abc")

        assertEquals(
            "https://radar.max-yu.workers.dev/api/radar/image?id=abc",
            transport.lastBytesUrl,
        )
        assertEquals(10_000, transport.lastBytesConnectTimeoutMs)
        assertEquals(30_000, transport.lastBytesReadTimeoutMs)
        assertArrayEquals(expected, bytes)
    }

    private class RecordingRadarTransport(
        private val textResponse: String,
        private val bytesResponse: ByteArray = byteArrayOf(7),
    ) : RainRadarHttpTransport {
        var lastTextUrl: String? = null
        var lastBytesUrl: String? = null
        var lastBytesConnectTimeoutMs: Int? = null
        var lastBytesReadTimeoutMs: Int? = null

        override suspend fun getText(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
            lastTextUrl = url
            assertEquals(10_000, connectTimeoutMs)
            assertEquals(20_000, readTimeoutMs)
            return textResponse
        }

        override suspend fun getBytes(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): ByteArray {
            lastBytesUrl = url
            lastBytesConnectTimeoutMs = connectTimeoutMs
            lastBytesReadTimeoutMs = readTimeoutMs
            return bytesResponse
        }
    }

    companion object {
        private val CAPABILITIES_FIXTURE = """
            {
              "ok": true,
              "version": "2.5.0",
              "capabilities": {
                "radarFrames": true,
                "radar": {
                  "version": "1.0",
                  "enabled": true,
                  "rangesKm": [64, 256],
                  "heightsKmByRange": { "64": [2, 3], "256": [3] },
                  "defaultHeightKm": 3,
                  "modes": ["live", "test"],
                  "cadenceMinutes": 6,
                  "maxFrames": 30
                }
              },
              "radarContract": {
                "version": "1.0",
                "enabled": true,
                "rangesKm": [64, 256],
                "heightsKmByRange": { "64": [2, 3], "256": [3] },
                "defaultHeightKm": 3,
                "modes": ["live", "test"],
                "cadenceMinutes": 6,
                "maxFrames": 30
              }
            }
        """.trimIndent()

        private val LIVE_FRAMES_FIXTURE = """
            {
              "ok": true,
              "version": "2.5.0",
              "contractVersion": "1.0",
              "rangeKm": 64,
              "heightKm": 2,
              "mode": "live",
              "renderMode": "transparent-georeferenced-overlay",
              "issueTime": "2026-08-14T10:42:00.000Z",
              "cadenceMinutes": 6,
              "frameCount": 2,
              "frames": [
                {
                  "id": "abc",
                  "index": 0,
                  "time": "2026-08-14T10:36:00.000Z",
                  "imageUrl": "/api/radar/image?id=abc",
                  "bounds": {
                    "north": 22.87890,
                    "south": 21.72777,
                    "east": 114.79378,
                    "west": 113.54956
                  }
                },
                {
                  "id": "def",
                  "index": 1,
                  "time": "2026-08-14T10:42:00.000Z",
                  "imageUrl": "/api/radar/image?id=def",
                  "bounds": {
                    "north": 22.87890,
                    "south": 21.72777,
                    "east": 114.79378,
                    "west": 113.54956
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
