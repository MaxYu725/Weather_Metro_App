package com.weather.metro.ui.rain

import com.weather.metro.domain.rain.RainLocationTrendSample
import kotlin.math.max

private const val LOCATION_TREND_WET_THRESHOLD_MM = 0.1
private const val LOCATION_TREND_COMPLETE_FRAME_COUNT = 16

/**
 * Returns a user-facing headline only when the progressive SWIRLS samples add information beyond the
 * existing Current fast path. A null result means the fast `/api/rain/point` headline should remain.
 *
 * No samples are summed: every value remains a rolling 30-minute accumulation observed at a native
 * six-minute SWIRLS valid time.
 */
internal fun locationTrendHeadline(samples: List<RainLocationTrendSample>): String? {
    val ordered = samples
        .distinctBy { it.frameIndex }
        .sortedBy { it.frameIndex }
    if (ordered.isEmpty()) return null

    val onset = ordered.zipWithNext().firstOrNull { (previous, current) ->
        previous.amountMm < LOCATION_TREND_WET_THRESHOLD_MM &&
            current.amountMm >= LOCATION_TREND_WET_THRESHOLD_MM &&
            current.leadMinutes > previous.leadMinutes &&
            current.leadMinutes - previous.leadMinutes <= 12
    }
    if (onset != null) {
        val lead = onset.second.leadMinutes
        return if (lead > 0) "約 $lead 分鐘後可能開始有雨" else "短時間內可能開始有雨"
    }

    val wet = ordered.filter { it.amountMm >= LOCATION_TREND_WET_THRESHOLD_MM }
    if (wet.isEmpty()) {
        return if (isCompleteLocationTrend(ordered)) "未來 2 小時暫無明顯降雨" else null
    }

    if (ordered.size >= 4) {
        val first = ordered.first()
        val last = ordered.last()
        val spanMinutes = last.leadMinutes - first.leadMinutes
        val meaningfulDelta = max(0.2, first.amountMm * 0.35)
        if (spanMinutes >= 18 && last.amountMm >= first.amountMm + meaningfulDelta) {
            return if (spanMinutes >= 48) {
                "未來 1 小時降雨訊號逐步增強"
            } else {
                "稍後降雨訊號逐步增強"
            }
        }
        if (
            spanMinutes >= 18 &&
            first.amountMm >= LOCATION_TREND_WET_THRESHOLD_MM &&
            last.amountMm <= first.amountMm - meaningfulDelta
        ) {
            return "降雨訊號逐步減弱"
        }
    }

    return "未來 2 小時有降雨訊號"
}

internal fun isCompleteLocationTrend(samples: List<RainLocationTrendSample>): Boolean {
    if (samples.size < LOCATION_TREND_COMPLETE_FRAME_COUNT) return false
    val indexes = samples.map { it.frameIndex }.toSet()
    return (0 until LOCATION_TREND_COMPLETE_FRAME_COUNT).all(indexes::contains)
}

internal fun locationTrendDisplaySamples(
    samples: List<RainLocationTrendSample>,
): List<RainLocationTrendSample> = samples
    .distinctBy { it.frameIndex }
    .sortedBy { it.frameIndex }
