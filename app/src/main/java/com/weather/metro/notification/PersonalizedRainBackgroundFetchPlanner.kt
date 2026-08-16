package com.weather.metro.notification

import com.weather.metro.domain.rain.RainForecastSource
import com.weather.metro.domain.rain.RainForecastTimeline
import java.time.Instant

internal data class PersonalizedRainFetchPlan(
    val discoveryFrameIndex: Int,
    val futureFrameIndices: List<Int>,
    val baselineFrameIndices: List<Int>,
    val baselineNetworkFrameIndices: List<Int>,
    val denseCompletionFrameIndices: List<Int>,
    val fullRunFrameCount: Int,
    val gridCellCount: Int,
) {
    val baselineTotalFrameCount: Int
        get() = baselineFrameIndices.size

    val baselineNetworkCellValues: Long
        get() = baselineNetworkFrameIndices.size.toLong() * gridCellCount

    val fullRunCellValues: Long
        get() = fullRunFrameCount.toLong() * gridCellCount

    val baselineRunFraction: Double
        get() = if (fullRunFrameCount <= 0) 0.0 else baselineTotalFrameCount.toDouble() / fullRunFrameCount
}

/**
 * Adaptive background plan for the location-private SWIRLS evaluator.
 *
 * The first frame remains the discovery frame because the current Rain Worker exposes
 * timeline metadata together with frame 0. Once that timeline is known, dry/background
 * evaluation does not blindly download all sixteen 121x121 grids:
 *
 * - <= 30 minutes from device-now: every available 6-minute frame;
 * - 31..60 minutes: every second frame (about 12 minutes), plus the band end;
 * - 61..120 minutes: every third frame (about 18 minutes), plus the horizon end.
 *
 * If an episode is already active, or a scout later observes rain, the caller switches
 * to [denseCompletionFrameIndices] so event evaluation returns to full 6-minute input.
 */
internal object PersonalizedRainBackgroundFetchPlanner {
    const val DISCOVERY_FRAME_INDEX = 0
    private const val NEAR_TERM_MINUTES = 30
    private const val MID_TERM_MINUTES = 60
    private const val MAX_HORIZON_MINUTES = 120
    private const val MID_TERM_STRIDE = 2
    private const val FAR_TERM_STRIDE = 3

    fun plan(
        timeline: RainForecastTimeline,
        nowEpochMs: Long,
        loadedFrameIndices: Set<Int> = setOf(DISCOVERY_FRAME_INDEX),
        activeEpisode: Boolean = false,
    ): PersonalizedRainFetchPlan? {
        if (timeline.source != RainForecastSource.SWIRLS || nowEpochMs <= 0L) return null

        val future = timeline.frames.mapNotNull { slot ->
            val validEpochMs = parseEpochMs(slot.validTime) ?: return null
            val relativeLeadMinutes = ((validEpochMs - nowEpochMs) / 60_000.0)
            if (relativeLeadMinutes <= 0.0 || relativeLeadMinutes > MAX_HORIZON_MINUTES) return@mapNotNull null
            PlannedSlot(slot.frameIndex, relativeLeadMinutes)
        }.sortedBy { it.relativeLeadMinutes }

        val near = future.filter { it.relativeLeadMinutes <= NEAR_TERM_MINUTES }
        val mid = future.filter {
            it.relativeLeadMinutes > NEAR_TERM_MINUTES && it.relativeLeadMinutes <= MID_TERM_MINUTES
        }
        val far = future.filter {
            it.relativeLeadMinutes > MID_TERM_MINUTES && it.relativeLeadMinutes <= MAX_HORIZON_MINUTES
        }

        val baseline = buildList {
            addAll(near.map { it.frameIndex })
            addAll(stridedBand(mid, MID_TERM_STRIDE))
            addAll(stridedBand(far, FAR_TERM_STRIDE))
        }.distinct().sorted()

        val dense = future.map { it.frameIndex }.distinct().sorted()
        val baselineNetwork = baseline.filterNot(loadedFrameIndices::contains)
        val denseCompletion = if (activeEpisode) {
            dense.filterNot(loadedFrameIndices::contains)
        } else {
            dense.filterNot { it in loadedFrameIndices || it in baseline }
        }

        return PersonalizedRainFetchPlan(
            discoveryFrameIndex = DISCOVERY_FRAME_INDEX,
            futureFrameIndices = dense,
            baselineFrameIndices = baseline,
            baselineNetworkFrameIndices = baselineNetwork,
            denseCompletionFrameIndices = denseCompletion,
            fullRunFrameCount = timeline.frames.size,
            gridCellCount = timeline.grid.cellCount,
        )
    }

    fun requiresDenseCompletion(
        activeEpisode: Boolean,
        baselineSamples: Collection<PersonalizedRainLocalSample>,
    ): Boolean = activeEpisode || baselineSamples.any { it.intensity != PersonalizedRainIntensity.DRY }

    private fun stridedBand(slots: List<PlannedSlot>, stride: Int): List<Int> {
        if (slots.isEmpty()) return emptyList()
        val selected = slots.filterIndexed { index, _ -> index % stride == 0 }
            .map { it.frameIndex }
            .toMutableList()
        val last = slots.last().frameIndex
        if (last !in selected) selected += last
        return selected
    }

    private fun parseEpochMs(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private data class PlannedSlot(
        val frameIndex: Int,
        val relativeLeadMinutes: Double,
    )
}
