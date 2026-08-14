package com.weather.metro.ui.rain

import com.weather.metro.data.tools.RainRadarMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RainRadarFreshnessTest {
    private val now = Instant.parse("2026-08-14T12:00:00Z").toEpochMilli()

    @Test
    fun liveRadarWithinFifteenMinutesIsNormal() {
        val result = classifyRainRadarFreshness(
            mode = RainRadarMode.LIVE,
            latestFrameTime = "2026-08-14T11:50:00Z",
            refreshFailed = false,
            nowEpochMillis = now,
        )

        assertEquals(RainRadarFreshnessLevel.NORMAL, result.level)
        assertEquals(10L, result.ageMinutes)
        assertEquals("最新 10分鐘前", result.label)
    }

    @Test
    fun liveRadarBetweenFifteenAndThirtyMinutesIsDelayed() {
        val result = classifyRainRadarFreshness(
            mode = RainRadarMode.LIVE,
            latestFrameTime = "2026-08-14T11:40:00Z",
            refreshFailed = false,
            nowEpochMillis = now,
        )

        assertEquals(RainRadarFreshnessLevel.DELAYED, result.level)
        assertEquals(20L, result.ageMinutes)
    }

    @Test
    fun liveRadarOlderThanThirtyMinutesIsStale() {
        val result = classifyRainRadarFreshness(
            mode = RainRadarMode.LIVE,
            latestFrameTime = "2026-08-14T11:20:00Z",
            refreshFailed = false,
            nowEpochMillis = now,
        )

        assertEquals(RainRadarFreshnessLevel.STALE, result.level)
        assertEquals(40L, result.ageMinutes)
    }

    @Test
    fun failedRefreshKeepsAgeButReportsDelayedState() {
        val result = classifyRainRadarFreshness(
            mode = RainRadarMode.LIVE,
            latestFrameTime = "2026-08-14T11:55:00Z",
            refreshFailed = true,
            nowEpochMillis = now,
        )

        assertEquals(RainRadarFreshnessLevel.DELAYED, result.level)
        assertEquals("雷達暫未更新 · 5分鐘前", result.label)
    }

    @Test
    fun testModeDoesNotExposeSyntheticAge() {
        val result = classifyRainRadarFreshness(
            mode = RainRadarMode.TEST,
            latestFrameTime = "2026-08-14T11:55:00Z",
            refreshFailed = false,
            nowEpochMillis = now,
        )

        assertEquals(RainRadarFreshnessLevel.TEST, result.level)
        assertEquals(null, result.ageMinutes)
        assertEquals("TEST", result.label)
    }

    @Test
    fun invalidTimestampReportsUnknown() {
        val result = classifyRainRadarFreshness(
            mode = RainRadarMode.LIVE,
            latestFrameTime = "invalid",
            refreshFailed = false,
            nowEpochMillis = now,
        )

        assertEquals(RainRadarFreshnessLevel.UNKNOWN, result.level)
        assertEquals("最新時間不詳", result.label)
    }
}
