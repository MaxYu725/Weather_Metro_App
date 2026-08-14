package com.weather.metro.ui.rain

import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.domain.rain.RainPointForecast
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.max

internal const val RAIN_TOOL_FRESH_MS = 15 * 60_000L
internal const val RAIN_TOOL_RETRY_BACKOFF_MS = 5 * 60_000L
internal const val RAIN_TOOL_POLICY_TICK_MS = 60_000L

internal fun shouldRefreshRainPoint(
    status: RainResourceStatus,
    isStale: Boolean,
    forecast: RainPointForecast?,
    acceptedAtEpochMs: Long?,
    lastAttemptEpochMs: Long?,
    nowEpochMs: Long,
): Boolean {
    if (status == RainResourceStatus.LOADING) return false
    if (forecast == null) {
        return status == RainResourceStatus.IDLE || retryBackoffElapsed(lastAttemptEpochMs, nowEpochMs)
    }

    val staleByAge = rainPointAgeMillis(forecast, acceptedAtEpochMs, nowEpochMs)
        ?.let { it > RAIN_TOOL_FRESH_MS }
        ?: true
    if (!isStale && !staleByAge) return false
    return retryBackoffElapsed(lastAttemptEpochMs, nowEpochMs)
}

internal fun shouldRefreshRainForecast(
    status: RainResourceStatus,
    isStale: Boolean,
    timeline: RainForecastTimeline?,
    acceptedAtEpochMs: Long?,
    lastAttemptEpochMs: Long?,
    nowEpochMs: Long,
): Boolean {
    if (status == RainResourceStatus.LOADING) return false
    if (timeline == null) {
        return status == RainResourceStatus.IDLE || retryBackoffElapsed(lastAttemptEpochMs, nowEpochMs)
    }

    val staleByAge = rainForecastAgeMillis(timeline, acceptedAtEpochMs, nowEpochMs)
        ?.let { it > RAIN_TOOL_FRESH_MS }
        ?: true
    if (!isStale && !staleByAge) return false
    return retryBackoffElapsed(lastAttemptEpochMs, nowEpochMs)
}

internal fun forecastPrefetchIndexes(
    frameCount: Int,
    selectedIndex: Int,
    maxPrefetch: Int = 3,
): List<Int> {
    if (frameCount <= 1 || maxPrefetch <= 0) return emptyList()
    require(selectedIndex in 0 until frameCount) { "Selected forecast frame is outside timeline" }

    val offsets = buildList {
        for (distance in 1 until frameCount) {
            add(distance)
            add(-distance)
        }
    }
    return offsets
        .asSequence()
        .map { selectedIndex + it }
        .filter { it in 0 until frameCount && it != selectedIndex }
        .distinct()
        .take(maxPrefetch)
        .toList()
}

private fun rainPointAgeMillis(
    forecast: RainPointForecast,
    acceptedAtEpochMs: Long?,
    nowEpochMs: Long,
): Long? {
    val timestamp = sequenceOf(
        forecast.sourceUpdatedAt,
        forecast.issueTime,
        forecast.generatedAt,
    ).filterNotNull().firstNotNullOfOrNull(::parseEpochMillis)
    if (timestamp != null) return max(0L, nowEpochMs - timestamp)

    val sourceAgeMinutes = forecast.quality?.freshness?.sourceAgeMinutes
    if (sourceAgeMinutes != null && sourceAgeMinutes.isFinite() && sourceAgeMinutes >= 0.0) {
        val elapsedSinceAccept = acceptedAtEpochMs?.let { max(0L, nowEpochMs - it) } ?: 0L
        return (sourceAgeMinutes * 60_000.0).toLong() + elapsedSinceAccept
    }
    return acceptedAtEpochMs?.let { max(0L, nowEpochMs - it) }
}

private fun rainForecastAgeMillis(
    timeline: RainForecastTimeline,
    acceptedAtEpochMs: Long?,
    nowEpochMs: Long,
): Long? {
    val issueEpochMs = parseEpochMillis(timeline.issueTime)
    return if (issueEpochMs != null) {
        max(0L, nowEpochMs - issueEpochMs)
    } else {
        acceptedAtEpochMs?.let { max(0L, nowEpochMs - it) }
    }
}

private fun retryBackoffElapsed(lastAttemptEpochMs: Long?, nowEpochMs: Long): Boolean =
    lastAttemptEpochMs == null || max(0L, nowEpochMs - lastAttemptEpochMs) >= RAIN_TOOL_RETRY_BACKOFF_MS

private fun parseEpochMillis(value: String): Long? =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    }
