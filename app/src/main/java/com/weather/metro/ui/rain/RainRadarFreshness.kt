package com.weather.metro.ui.rain

import com.weather.metro.data.tools.RainRadarMode
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.max

enum class RainRadarFreshnessLevel {
    TEST,
    NORMAL,
    DELAYED,
    STALE,
    UNKNOWN,
}

data class RainRadarFreshness(
    val level: RainRadarFreshnessLevel,
    val ageMinutes: Long?,
    val label: String,
)

internal fun classifyRainRadarFreshness(
    mode: RainRadarMode,
    latestFrameTime: String?,
    refreshFailed: Boolean,
    nowEpochMillis: Long = System.currentTimeMillis(),
): RainRadarFreshness {
    if (mode == RainRadarMode.TEST) {
        return RainRadarFreshness(
            level = RainRadarFreshnessLevel.TEST,
            ageMinutes = null,
            label = "TEST",
        )
    }

    val frameEpochMillis = latestFrameTime?.let(::parseRadarEpochMillis)
        ?: return RainRadarFreshness(
            level = RainRadarFreshnessLevel.UNKNOWN,
            ageMinutes = null,
            label = "最新時間不詳",
        )
    val ageMinutes = max(0L, (nowEpochMillis - frameEpochMillis) / 60_000L)

    if (refreshFailed) {
        return RainRadarFreshness(
            level = RainRadarFreshnessLevel.DELAYED,
            ageMinutes = ageMinutes,
            label = "雷達暫未更新 · ${ageMinutes}分鐘前",
        )
    }

    return when {
        ageMinutes <= RADAR_FRESH_NORMAL_MINUTES -> RainRadarFreshness(
            level = RainRadarFreshnessLevel.NORMAL,
            ageMinutes = ageMinutes,
            label = if (ageMinutes == 0L) "雷達剛更新" else "最新 ${ageMinutes}分鐘前",
        )
        ageMinutes <= RADAR_FRESH_MAX_MINUTES -> RainRadarFreshness(
            level = RainRadarFreshnessLevel.DELAYED,
            ageMinutes = ageMinutes,
            label = "更新稍有延遲 · ${ageMinutes}分鐘",
        )
        else -> RainRadarFreshness(
            level = RainRadarFreshnessLevel.STALE,
            ageMinutes = ageMinutes,
            label = "資料過舊 · ${ageMinutes}分鐘",
        )
    }
}

private fun parseRadarEpochMillis(value: String): Long? =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()

internal const val RADAR_AUTO_REFRESH_MS = 330_000L
private const val RADAR_FRESH_NORMAL_MINUTES = 15L
private const val RADAR_FRESH_MAX_MINUTES = 30L
