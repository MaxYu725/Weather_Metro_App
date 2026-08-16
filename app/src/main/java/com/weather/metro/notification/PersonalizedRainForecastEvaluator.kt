package com.weather.metro.notification

import com.weather.metro.domain.rain.RainForecastFrame
import com.weather.metro.domain.rain.RainForecastGrid
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil

internal enum class PersonalizedRainIntensity(val rank: Int) {
    DRY(0),
    LIGHT(1),
    MODERATE(2),
    HEAVY(3),
    VERY_HEAVY(4),
}

/**
 * Compatibility thresholds already used by Rain-Track's point forecast.
 *
 * These are product/evaluator thresholds for mm / 30 min. They are not HKO
 * warning thresholds and must not be presented as territory-wide criteria.
 */
internal data class PersonalizedRainThresholds(
    val wetMmPer30Min: Double = 0.2,
    val moderateMmPer30Min: Double = 0.5,
    val heavyMmPer30Min: Double = 2.0,
    val veryHeavyMmPer30Min: Double = 10.0,
) {
    init {
        require(wetMmPer30Min > 0.0)
        require(moderateMmPer30Min >= wetMmPer30Min)
        require(heavyMmPer30Min >= moderateMmPer30Min)
        require(veryHeavyMmPer30Min >= heavyMmPer30Min)
    }

    fun classify(amountMmPer30Min: Double): PersonalizedRainIntensity = when {
        !amountMmPer30Min.isFinite() || amountMmPer30Min < 0.0 -> PersonalizedRainIntensity.DRY
        amountMmPer30Min >= veryHeavyMmPer30Min -> PersonalizedRainIntensity.VERY_HEAVY
        amountMmPer30Min >= heavyMmPer30Min -> PersonalizedRainIntensity.HEAVY
        amountMmPer30Min >= moderateMmPer30Min -> PersonalizedRainIntensity.MODERATE
        amountMmPer30Min >= wetMmPer30Min -> PersonalizedRainIntensity.LIGHT
        else -> PersonalizedRainIntensity.DRY
    }
}

internal data class PersonalizedRainLocalSample(
    val frameIndex: Int,
    val windowStartEpochMs: Long,
    val windowEndEpochMs: Long,
    val validTimeEpochMs: Long,
    val amountMmPer30Min: Double,
    val intensity: PersonalizedRainIntensity,
) {
    fun windowEndLeadMinutes(nowEpochMs: Long): Int =
        ceil((windowEndEpochMs - nowEpochMs) / 60_000.0).toInt()
}

internal data class PersonalizedRainHorizonSummary(
    val horizon: PersonalizedForecastHorizon,
    val sampleCount: Int,
    val maxAmountMmPer30Min: Double,
    val peakIntensity: PersonalizedRainIntensity,
    val firstWetWindowEndLeadMinutes: Int?,
)

internal data class PersonalizedRainProfile(
    val sourceRunEpochMs: Long,
    val latitude: Double,
    val longitude: Double,
    val cadenceMinutes: Int,
    val thresholds: PersonalizedRainThresholds,
    val samples: List<PersonalizedRainLocalSample>,
) {
    fun futureSamples(nowEpochMs: Long): List<PersonalizedRainLocalSample> =
        samples.filter { it.windowEndEpochMs > nowEpochMs }

    fun firstWetWithin(
        nowEpochMs: Long,
        maxLeadMinutes: Int = PersonalizedForecastHorizon.MINUTES_120.maxLeadMinutes,
    ): PersonalizedRainLocalSample? = futureSamples(nowEpochMs)
        .asSequence()
        .filter { it.intensity != PersonalizedRainIntensity.DRY }
        .filter { it.windowEndLeadMinutes(nowEpochMs) in 0..maxLeadMinutes }
        .minByOrNull { it.windowEndEpochMs }

    fun firstAtLeastWithin(
        minimumIntensity: PersonalizedRainIntensity,
        nowEpochMs: Long,
        maxLeadMinutes: Int,
    ): PersonalizedRainLocalSample? = futureSamples(nowEpochMs)
        .asSequence()
        .filter { it.intensity.rank >= minimumIntensity.rank }
        .filter { it.windowEndLeadMinutes(nowEpochMs) in 0..maxLeadMinutes }
        .minByOrNull { it.windowEndEpochMs }

    fun peakWithin(
        nowEpochMs: Long,
        maxLeadMinutes: Int = PersonalizedForecastHorizon.MINUTES_120.maxLeadMinutes,
    ): PersonalizedRainIntensity = futureSamples(nowEpochMs)
        .asSequence()
        .filter { it.windowEndLeadMinutes(nowEpochMs) in 0..maxLeadMinutes }
        .maxByOrNull { it.intensity.rank }
        ?.intensity
        ?: PersonalizedRainIntensity.DRY

    fun horizonSummary(
        horizon: PersonalizedForecastHorizon,
        nowEpochMs: Long,
    ): PersonalizedRainHorizonSummary {
        val selected = futureSamples(nowEpochMs)
            .filter { it.windowEndLeadMinutes(nowEpochMs) in 0..horizon.maxLeadMinutes }
        val wet = selected.firstOrNull { it.intensity != PersonalizedRainIntensity.DRY }
        val peak = selected.maxByOrNull { it.intensity.rank }
        return PersonalizedRainHorizonSummary(
            horizon = horizon,
            sampleCount = selected.size,
            maxAmountMmPer30Min = selected.maxOfOrNull { it.amountMmPer30Min } ?: 0.0,
            peakIntensity = peak?.intensity ?: PersonalizedRainIntensity.DRY,
            firstWetWindowEndLeadMinutes = wet?.windowEndLeadMinutes(nowEpochMs),
        )
    }
}

/**
 * Pure local bilinear sampler for a parsed RainForecastFrame.
 *
 * No location is sent to Rain-Track or any backend. The caller supplies the host
 * app's cached location and a location-independent SWIRLS grid already downloaded
 * by Weather Metro.
 */
internal object PersonalizedRainGridSampler {
    fun sample(
        frame: RainForecastFrame,
        latitude: Double,
        longitude: Double,
    ): Double? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        val grid = frame.grid
        if (!validGrid(grid, frame.values)) return null

        val rowBracket = descendingBracket(grid.latitudes, latitude) ?: return null
        val colBracket = ascendingBracket(grid.longitudes, longitude) ?: return null
        val row0 = rowBracket.first
        val row1 = rowBracket.second
        val col0 = colBracket.first
        val col1 = colBracket.second

        fun value(row: Int, col: Int): Double? {
            val candidate = frame.values.getOrNull(row * grid.cols + col) ?: return null
            return candidate.takeIf { it.isFinite() && it >= 0.0 }
        }

        val northWest = value(row0, col0) ?: return null
        if (row0 == row1 && col0 == col1) return northWest

        if (row0 == row1) {
            val northEast = value(row0, col1) ?: return null
            val fraction = fractionBetween(grid.longitudes[col0], grid.longitudes[col1], longitude)
                ?: return null
            return lerp(northWest, northEast, fraction)
        }

        if (col0 == col1) {
            val southWest = value(row1, col0) ?: return null
            val fraction = fractionBetween(grid.latitudes[row0], grid.latitudes[row1], latitude)
                ?: return null
            return lerp(northWest, southWest, fraction)
        }

        val northEast = value(row0, col1) ?: return null
        val southWest = value(row1, col0) ?: return null
        val southEast = value(row1, col1) ?: return null
        val latitudeFraction = fractionBetween(grid.latitudes[row0], grid.latitudes[row1], latitude)
            ?: return null
        val longitudeFraction = fractionBetween(grid.longitudes[col0], grid.longitudes[col1], longitude)
            ?: return null
        val north = lerp(northWest, northEast, longitudeFraction)
        val south = lerp(southWest, southEast, longitudeFraction)
        return lerp(north, south, latitudeFraction)
    }

    private fun validGrid(grid: RainForecastGrid, values: DoubleArray): Boolean =
        grid.rows > 0 &&
            grid.cols > 0 &&
            grid.latitudes.size == grid.rows &&
            grid.longitudes.size == grid.cols &&
            values.size == grid.rows * grid.cols &&
            grid.latitudes.all { it.isFinite() } &&
            grid.longitudes.all { it.isFinite() }

    private fun descendingBracket(axis: DoubleArray, value: Double): Pair<Int, Int>? {
        if (axis.isEmpty()) return null
        if (axis.size == 1) return if (abs(value - axis[0]) <= AXIS_EPSILON) 0 to 0 else null
        if (value > axis.first() + AXIS_EPSILON || value < axis.last() - AXIS_EPSILON) return null
        for (index in 0 until axis.lastIndex) {
            val high = axis[index]
            val low = axis[index + 1]
            if (high + AXIS_EPSILON < low) return null
            if (abs(value - high) <= AXIS_EPSILON) return index to index
            if (value < high && value > low) return index to index + 1
            if (abs(value - low) <= AXIS_EPSILON) return index + 1 to index + 1
        }
        return null
    }

    private fun ascendingBracket(axis: DoubleArray, value: Double): Pair<Int, Int>? {
        if (axis.isEmpty()) return null
        if (axis.size == 1) return if (abs(value - axis[0]) <= AXIS_EPSILON) 0 to 0 else null
        if (value < axis.first() - AXIS_EPSILON || value > axis.last() + AXIS_EPSILON) return null
        for (index in 0 until axis.lastIndex) {
            val low = axis[index]
            val high = axis[index + 1]
            if (low > high + AXIS_EPSILON) return null
            if (abs(value - low) <= AXIS_EPSILON) return index to index
            if (value > low && value < high) return index to index + 1
            if (abs(value - high) <= AXIS_EPSILON) return index + 1 to index + 1
        }
        return null
    }

    private fun fractionBetween(start: Double, end: Double, value: Double): Double? {
        val span = end - start
        if (abs(span) <= AXIS_EPSILON) return null
        return ((value - start) / span).coerceIn(0.0, 1.0)
    }

    private fun lerp(start: Double, end: Double, fraction: Double): Double =
        start + (end - start) * fraction

    private const val AXIS_EPSILON = 0.0000001
}

internal fun buildPersonalizedRainProfile(
    frames: List<RainForecastFrame>,
    latitude: Double,
    longitude: Double,
    nowEpochMs: Long,
    thresholds: PersonalizedRainThresholds = PersonalizedRainThresholds(),
): PersonalizedRainProfile? {
    if (frames.isEmpty() || !latitude.isFinite() || !longitude.isFinite()) return null
    val ordered = frames.sortedBy { it.frameIndex }
    if (ordered.map { it.frameIndex }.distinct().size != ordered.size) return null

    val runTime = ordered.first().runTime ?: return null
    if (ordered.any { it.runTime != runTime || it.unit != "mm / 30 min" }) return null
    val runEpochMs = parseEpochMs(runTime) ?: return null
    if (
        !PersonalizedForecastNotificationPolicy.isSourceFresh(
            sourceEpochMs = runEpochMs,
            nowEpochMs = nowEpochMs,
            maxAgeMs = PersonalizedForecastNotificationPolicy.RAIN_SOURCE_MAX_AGE_MS,
        )
    ) {
        return null
    }

    val referenceGrid = ordered.first().grid
    if (ordered.any { !sameGrid(referenceGrid, it.grid) }) return null

    val samples = ordered.map { frame ->
        val amount = PersonalizedRainGridSampler.sample(frame, latitude, longitude) ?: return null
        val windowStart = parseEpochMs(frame.windowStart) ?: return null
        val windowEnd = parseEpochMs(frame.windowEnd) ?: return null
        val validTime = parseEpochMs(frame.validTime) ?: return null
        if (windowStart >= windowEnd || windowEnd != validTime) return null
        PersonalizedRainLocalSample(
            frameIndex = frame.frameIndex,
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowEnd,
            validTimeEpochMs = validTime,
            amountMmPer30Min = amount,
            intensity = thresholds.classify(amount),
        )
    }

    val cadence = ordered.zipWithNext()
        .mapNotNull { (first, second) ->
            val firstTime = parseEpochMs(first.validTime) ?: return@mapNotNull null
            val secondTime = parseEpochMs(second.validTime) ?: return@mapNotNull null
            ((secondTime - firstTime) / 60_000L).toInt().takeIf { it > 0 }
        }
        .minOrNull()
        ?: 6

    return PersonalizedRainProfile(
        sourceRunEpochMs = runEpochMs,
        latitude = latitude,
        longitude = longitude,
        cadenceMinutes = cadence,
        thresholds = thresholds,
        samples = samples,
    )
}

internal data class PersonalizedRainEpisodeState(
    val episodeId: String = "",
    val active: Boolean = false,
    val reachedNearTermWet: Boolean = false,
    val maxNotifiedIntensity: PersonalizedRainIntensity = PersonalizedRainIntensity.DRY,
    val transitionOrdinal: Int = 0,
    val dryConfirmationCount: Int = 0,
    val dryConfirmationStartedAtEpochMs: Long? = null,
    val lastNotificationEpochMs: Long? = null,
    val lastEventKind: PersonalizedForecastEventKind? = null,
)

internal data class PersonalizedRainTransitionDecision(
    val nextState: PersonalizedRainEpisodeState,
    val eventKind: PersonalizedForecastEventKind? = null,
    val eventIdentity: PersonalizedForecastEventIdentity? = null,
    val horizon: PersonalizedForecastHorizon? = null,
)

internal fun evaluatePersonalizedRainTransition(
    profile: PersonalizedRainProfile,
    state: PersonalizedRainEpisodeState,
    nowEpochMs: Long,
): PersonalizedRainTransitionDecision {
    val firstWet = profile.firstWetWithin(nowEpochMs)
    if (!state.active) {
        if (firstWet == null) return PersonalizedRainTransitionDecision(PersonalizedRainEpisodeState())
        val eventKind = initialRainEventKind(profile, firstWet, nowEpochMs)
        val eventHorizon = PersonalizedForecastNotificationPolicy.horizonForLeadMinutes(
            firstWet.windowEndLeadMinutes(nowEpochMs),
        )
        val episodeId = rainEpisodeId(profile, firstWet)
        val initialIntensity = if (eventKind == PersonalizedForecastEventKind.HEAVY_RAIN_APPROACHING) {
            profile.firstAtLeastWithin(
                PersonalizedRainIntensity.HEAVY,
                nowEpochMs,
                PersonalizedForecastHorizon.MINUTES_30.maxLeadMinutes,
            )?.intensity ?: firstWet.intensity
        } else {
            firstWet.intensity
        }
        val identity = PersonalizedForecastEventIdentity(
            source = PersonalizedForecastSource.HKO_SWIRLS_GRID,
            kind = eventKind,
            episodeId = episodeId,
            transitionOrdinal = state.transitionOrdinal,
        )
        return PersonalizedRainTransitionDecision(
            nextState = PersonalizedRainEpisodeState(
                episodeId = episodeId,
                active = true,
                reachedNearTermWet = firstWet.windowEndLeadMinutes(nowEpochMs) <= 30,
                maxNotifiedIntensity = initialIntensity,
                transitionOrdinal = state.transitionOrdinal + 1,
                lastNotificationEpochMs = nowEpochMs,
                lastEventKind = eventKind,
            ),
            eventKind = eventKind,
            eventIdentity = identity,
            horizon = eventHorizon,
        )
    }

    if (firstWet == null) {
        return evaluateDryRainTransition(state, nowEpochMs)
    }

    val nearTermWet = firstWet.windowEndLeadMinutes(nowEpochMs) <= 30
    var next = state.copy(
        reachedNearTermWet = state.reachedNearTermWet || nearTermWet,
        dryConfirmationCount = 0,
        dryConfirmationStartedAtEpochMs = null,
    )

    val nearTermPeak = profile.peakWithin(
        nowEpochMs = nowEpochMs,
        maxLeadMinutes = PersonalizedForecastHorizon.MINUTES_30.maxLeadMinutes,
    )
    val canNotify = rainCooldownElapsed(state.lastNotificationEpochMs, nowEpochMs)

    if (
        canNotify &&
        nearTermPeak.rank > state.maxNotifiedIntensity.rank &&
        nearTermPeak.rank >= PersonalizedRainIntensity.HEAVY.rank
    ) {
        return notifyRainTransition(
            state = next,
            eventKind = PersonalizedForecastEventKind.RAIN_INTENSIFYING,
            horizon = PersonalizedForecastHorizon.MINUTES_30,
            notifiedIntensity = nearTermPeak,
            nowEpochMs = nowEpochMs,
        )
    }

    if (
        canNotify &&
        !state.reachedNearTermWet &&
        nearTermWet &&
        state.lastEventKind != PersonalizedForecastEventKind.RAIN_STARTING_SOON
    ) {
        return notifyRainTransition(
            state = next,
            eventKind = PersonalizedForecastEventKind.RAIN_STARTING_SOON,
            horizon = PersonalizedForecastHorizon.MINUTES_30,
            notifiedIntensity = firstWet.intensity,
            nowEpochMs = nowEpochMs,
        )
    }

    next = next.copy(
        maxNotifiedIntensity = maxOf(
            next.maxNotifiedIntensity,
            PersonalizedRainIntensity.DRY,
            compareBy { it.rank },
        ),
    )
    return PersonalizedRainTransitionDecision(nextState = next)
}

private fun evaluateDryRainTransition(
    state: PersonalizedRainEpisodeState,
    nowEpochMs: Long,
): PersonalizedRainTransitionDecision {
    val start = state.dryConfirmationStartedAtEpochMs ?: nowEpochMs
    val count = state.dryConfirmationCount + 1
    val confirmed = count >= PersonalizedForecastNotificationPolicy.RAIN_DRY_CONFIRMATION_COUNT &&
        nowEpochMs - start >= PersonalizedForecastNotificationPolicy.RAIN_DRY_CONFIRMATION_MIN_SPAN_MS

    if (!confirmed) {
        return PersonalizedRainTransitionDecision(
            nextState = state.copy(
                dryConfirmationCount = count,
                dryConfirmationStartedAtEpochMs = start,
            ),
        )
    }

    if (!state.reachedNearTermWet) {
        return PersonalizedRainTransitionDecision(nextState = PersonalizedRainEpisodeState())
    }

    if (!rainCooldownElapsed(state.lastNotificationEpochMs, nowEpochMs)) {
        return PersonalizedRainTransitionDecision(
            nextState = state.copy(
                dryConfirmationCount = count,
                dryConfirmationStartedAtEpochMs = start,
            ),
        )
    }

    val identity = PersonalizedForecastEventIdentity(
        source = PersonalizedForecastSource.HKO_SWIRLS_GRID,
        kind = PersonalizedForecastEventKind.RAIN_ENDING,
        episodeId = state.episodeId,
        transitionOrdinal = state.transitionOrdinal,
    )
    return PersonalizedRainTransitionDecision(
        nextState = PersonalizedRainEpisodeState(
            transitionOrdinal = state.transitionOrdinal + 1,
            lastNotificationEpochMs = nowEpochMs,
            lastEventKind = PersonalizedForecastEventKind.RAIN_ENDING,
        ),
        eventKind = PersonalizedForecastEventKind.RAIN_ENDING,
        eventIdentity = identity,
        horizon = PersonalizedForecastHorizon.MINUTES_30,
    )
}

private fun initialRainEventKind(
    profile: PersonalizedRainProfile,
    firstWet: PersonalizedRainLocalSample,
    nowEpochMs: Long,
): PersonalizedForecastEventKind {
    val firstHeavy = profile.firstAtLeastWithin(
        PersonalizedRainIntensity.HEAVY,
        nowEpochMs,
        PersonalizedForecastHorizon.MINUTES_30.maxLeadMinutes,
    )
    return when {
        firstHeavy != null -> PersonalizedForecastEventKind.HEAVY_RAIN_APPROACHING
        firstWet.windowEndLeadMinutes(nowEpochMs) <= 30 -> PersonalizedForecastEventKind.RAIN_STARTING_SOON
        else -> PersonalizedForecastEventKind.RAIN_APPROACHING
    }
}

private fun notifyRainTransition(
    state: PersonalizedRainEpisodeState,
    eventKind: PersonalizedForecastEventKind,
    horizon: PersonalizedForecastHorizon,
    notifiedIntensity: PersonalizedRainIntensity,
    nowEpochMs: Long,
): PersonalizedRainTransitionDecision {
    val identity = PersonalizedForecastEventIdentity(
        source = PersonalizedForecastSource.HKO_SWIRLS_GRID,
        kind = eventKind,
        episodeId = state.episodeId,
        transitionOrdinal = state.transitionOrdinal,
    )
    return PersonalizedRainTransitionDecision(
        nextState = state.copy(
            maxNotifiedIntensity = if (notifiedIntensity.rank > state.maxNotifiedIntensity.rank) {
                notifiedIntensity
            } else {
                state.maxNotifiedIntensity
            },
            transitionOrdinal = state.transitionOrdinal + 1,
            lastNotificationEpochMs = nowEpochMs,
            lastEventKind = eventKind,
        ),
        eventKind = eventKind,
        eventIdentity = identity,
        horizon = horizon,
    )
}

private fun rainCooldownElapsed(lastNotificationEpochMs: Long?, nowEpochMs: Long): Boolean {
    val last = lastNotificationEpochMs ?: return true
    if (last <= 0L || nowEpochMs <= 0L || last > nowEpochMs) return false
    return nowEpochMs - last >= PersonalizedForecastNotificationPolicy.RAIN_TRANSITION_COOLDOWN_MS
}

private fun rainEpisodeId(
    profile: PersonalizedRainProfile,
    firstWet: PersonalizedRainLocalSample,
): String = "rain:${profile.sourceRunEpochMs}:${firstWet.windowEndEpochMs}:${firstWet.frameIndex}"

private fun parseEpochMs(value: String): Long? = runCatching {
    Instant.parse(value).toEpochMilli()
}.getOrNull()

private fun sameGrid(first: RainForecastGrid, second: RainForecastGrid): Boolean =
    first.rows == second.rows &&
        first.cols == second.cols &&
        first.cellCount == second.cellCount &&
        first.orientation == second.orientation &&
        first.latitudes.contentEquals(second.latitudes) &&
        first.longitudes.contentEquals(second.longitudes)

private fun maxOf(
    first: PersonalizedRainIntensity,
    second: PersonalizedRainIntensity,
    comparator: Comparator<PersonalizedRainIntensity>,
): PersonalizedRainIntensity = if (comparator.compare(first, second) >= 0) first else second
