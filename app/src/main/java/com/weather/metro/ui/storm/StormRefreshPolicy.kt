package com.weather.metro.ui.storm

import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormLiveState
import kotlin.math.max

internal const val STORM_SUCCESS_STALE_AFTER_MS = 15L * 60L * 1000L
internal const val STORM_FAILURE_RETRY_AFTER_MS = 5L * 60L * 1000L
internal const val STORM_FOREGROUND_POLICY_TICK_MS = 60L * 1000L

internal fun stormAgenciesNeedingRefresh(
    sources: Map<StormAgency, StormAgencyHostState>,
    nowMillis: Long,
    successStaleAfterMs: Long = STORM_SUCCESS_STALE_AFTER_MS,
    failureRetryAfterMs: Long = STORM_FAILURE_RETRY_AFTER_MS,
): Set<StormAgency> = sources.values
    .filter { source ->
        stormAgencyNeedsRefresh(
            source = source,
            nowMillis = nowMillis,
            successStaleAfterMs = successStaleAfterMs,
            failureRetryAfterMs = failureRetryAfterMs,
        )
    }
    .mapTo(linkedSetOf()) { it.agency }

internal fun stormAgencyNeedsRefresh(
    source: StormAgencyHostState,
    nowMillis: Long,
    successStaleAfterMs: Long = STORM_SUCCESS_STALE_AFTER_MS,
    failureRetryAfterMs: Long = STORM_FAILURE_RETRY_AFTER_MS,
): Boolean {
    require(successStaleAfterMs >= 0L) { "Storm success stale threshold must be non-negative" }
    require(failureRetryAfterMs >= 0L) { "Storm failure retry threshold must be non-negative" }
    if (source.refreshing) return false

    val lastAttempt = source.lastAttemptAtMillis
    if (!source.hasSuccessfulSnapshot) {
        return lastAttempt == null || elapsedMillis(nowMillis, lastAttempt) >= failureRetryAfterMs
    }

    if (
        source.liveState == StormLiveState.ERROR ||
        (source.liveState == StormLiveState.STALE && lastAttempt != null)
    ) {
        return lastAttempt == null || elapsedMillis(nowMillis, lastAttempt) >= failureRetryAfterMs
    }

    val lastSuccess = source.lastSuccessAtMillis ?: return true
    return elapsedMillis(nowMillis, lastSuccess) >= successStaleAfterMs
}

internal fun stormLastSuccessAgeLabel(lastSuccessAtMillis: Long?, nowMillis: Long): String? {
    lastSuccessAtMillis ?: return null
    val ageMillis = elapsedMillis(nowMillis, lastSuccessAtMillis)
    val minutes = ageMillis / 60_000L
    return when {
        minutes <= 0L -> "剛更新"
        minutes < 60L -> "${minutes}分前"
        minutes < 24L * 60L -> "${minutes / 60L}小時前"
        else -> "${minutes / (24L * 60L)}日前"
    }
}

internal fun stormUserFacingError(rawMessage: String?): String {
    val raw = rawMessage.orEmpty().lowercase()
    return when {
        raw.contains("timeout") || raw.contains("timed out") -> "資料來源回應逾時"
        raw.contains("parser") ||
            raw.contains("parsing") ||
            raw.contains("parse ") ||
            raw.contains("xml") ||
            raw.contains("json") ||
            raw.contains("specification") -> "資料格式暫時無法讀取"
        raw.contains("http") ||
            raw.contains("network") ||
            raw.contains("connection") ||
            raw.contains("unreachable") -> "資料來源暫時無法連線"
        else -> "即時資料暫時無法更新"
    }
}

private fun elapsedMillis(nowMillis: Long, thenMillis: Long): Long = max(0L, nowMillis - thenMillis)
