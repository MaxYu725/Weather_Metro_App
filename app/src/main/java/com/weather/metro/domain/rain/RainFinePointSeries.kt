package com.weather.metro.domain.rain

import kotlin.math.abs

/**
 * A point sample from one SWIRLS forecast frame.
 *
 * Important: [accumulationMm] is still the rainfall accumulated over the frame's
 * 30-minute window. SWIRLS publishes those overlapping 30-minute windows every
 * 6 minutes; it does not publish an independent 6-minute rainfall total here.
 */
data class RainPointRollingSample(
    val validTime: String,
    val leadMinutes: Int,
    val windowStart: String,
    val windowEnd: String,
    val accumulationMm: Double,
)

/**
 * A mathematically reconstructed 6-minute bucket.
 *
 * This is only emitted when a fully dry 30-minute rolling window exists in the
 * same SWIRLS run. That dry window supplies five known zero buckets, removing
 * the four degrees of freedom that otherwise make the rolling-sum system
 * underdetermined. The value remains derived from the SWIRLS product rather
 * than an Observatory-issued 6-minute accumulation.
 */
data class RainSixMinuteBucket(
    val startLeadMinutes: Int,
    val endLeadMinutes: Int,
    val amountMm: Double,
)

data class RainFinePointSeries(
    val source: RainForecastSource,
    val issueTime: String,
    val cadenceMinutes: Int,
    val accumulationMinutes: Int,
    val rollingSamples: List<RainPointRollingSample>,
    val reconstructedSixMinuteBuckets: List<RainSixMinuteBucket>?,
    val reconstructionAnchorLeadMinutes: Int?,
) {
    val hasReconstructedSixMinuteBuckets: Boolean
        get() = reconstructedSixMinuteBuckets != null
}

/**
 * Build the finest point time-series that can be stated directly from the
 * loaded SWIRLS run without inventing temporal precision.
 *
 * The normal result is 16 overlapping 30-minute accumulations at 6-minute
 * cadence. When a genuinely dry rolling window is present, a unique 6-minute
 * bucket reconstruction is also returned and verified by rebuilding every
 * source rolling sum.
 */
fun RainForecastTimeline.buildFinePointSeries(
    latitude: Double,
    longitude: Double,
    dryEpsilonMm: Double = DEFAULT_DRY_EPSILON_MM,
    consistencyToleranceMm: Double = DEFAULT_CONSISTENCY_TOLERANCE_MM,
): RainFinePointSeries? {
    if (source != RainForecastSource.SWIRLS) return null
    if (cadenceMinutes <= 0 || accumulationMinutes <= 0) return null
    if (accumulationMinutes % cadenceMinutes != 0) return null
    if (frames.isEmpty() || frames.any { it.frame == null }) return null

    val rollingSamples = frames.map { slot ->
        val frame = slot.frame ?: return null
        val amountMm = frame.interpolateRainfallAt(latitude, longitude) ?: return null
        RainPointRollingSample(
            validTime = slot.validTime,
            leadMinutes = slot.leadMinutes,
            windowStart = slot.windowStart,
            windowEnd = slot.windowEnd,
            accumulationMm = amountMm.coerceAtLeast(0.0),
        )
    }

    if (rollingSamples.zipWithNext().any { (first, second) ->
            second.leadMinutes - first.leadMinutes != cadenceMinutes
        }
    ) {
        return null
    }

    val reconstruction = reconstructSixMinuteBuckets(
        rollingSamples = rollingSamples,
        cadenceMinutes = cadenceMinutes,
        accumulationMinutes = accumulationMinutes,
        dryEpsilonMm = dryEpsilonMm,
        consistencyToleranceMm = consistencyToleranceMm,
    )

    return RainFinePointSeries(
        source = source,
        issueTime = issueTime,
        cadenceMinutes = cadenceMinutes,
        accumulationMinutes = accumulationMinutes,
        rollingSamples = rollingSamples,
        reconstructedSixMinuteBuckets = reconstruction?.buckets,
        reconstructionAnchorLeadMinutes = reconstruction?.anchorLeadMinutes,
    )
}

private data class SixMinuteReconstruction(
    val buckets: List<RainSixMinuteBucket>,
    val anchorLeadMinutes: Int,
)

private fun reconstructSixMinuteBuckets(
    rollingSamples: List<RainPointRollingSample>,
    cadenceMinutes: Int,
    accumulationMinutes: Int,
    dryEpsilonMm: Double,
    consistencyToleranceMm: Double,
): SixMinuteReconstruction? {
    if (rollingSamples.size < 2) return null
    val bucketsPerWindow = accumulationMinutes / cadenceMinutes
    if (bucketsPerWindow <= 0) return null

    // A non-zero rolling series alone is underdetermined: N rolling sums over
    // K-bucket windows contain N + K - 1 bucket unknowns. A dry rolling window
    // gives K zero bucket constraints and makes the system unique.
    val anchorIndex = rollingSamples.indexOfFirst { abs(it.accumulationMm) <= dryEpsilonMm }
    if (anchorIndex < 0) return null

    val bucketCount = rollingSamples.size + bucketsPerWindow - 1
    val amounts = DoubleArray(bucketCount) { Double.NaN }
    for (bucketIndex in anchorIndex until anchorIndex + bucketsPerWindow) {
        amounts[bucketIndex] = 0.0
    }

    // Y(i+1) - Y(i) = enteringBucket - leavingBucket.
    // Propagate forward from the known dry window.
    for (windowIndex in anchorIndex until rollingSamples.lastIndex) {
        val enteringBucketIndex = windowIndex + bucketsPerWindow
        val delta = rollingSamples[windowIndex + 1].accumulationMm -
            rollingSamples[windowIndex].accumulationMm
        amounts[enteringBucketIndex] = delta + amounts[windowIndex]
    }

    // The same identity can be rearranged to propagate backwards.
    for (windowIndex in anchorIndex - 1 downTo 0) {
        val delta = rollingSamples[windowIndex + 1].accumulationMm -
            rollingSamples[windowIndex].accumulationMm
        amounts[windowIndex] = amounts[windowIndex + bucketsPerWindow] - delta
    }

    if (amounts.any { !it.isFinite() || it < -consistencyToleranceMm }) return null
    val normalized = amounts.map { value ->
        if (value <= consistencyToleranceMm) 0.0 else value
    }

    // Guard against source rounding or an invalid dry anchor. The reconstructed
    // buckets must reproduce every original 30-minute rolling accumulation.
    rollingSamples.forEachIndexed { windowIndex, sample ->
        val rebuilt = normalized
            .subList(windowIndex, windowIndex + bucketsPerWindow)
            .sum()
        if (abs(rebuilt - sample.accumulationMm) > consistencyToleranceMm) return null
    }

    val firstBucketLead = rollingSamples.first().leadMinutes - accumulationMinutes
    val buckets = normalized.mapIndexed { index, amountMm ->
        val start = firstBucketLead + index * cadenceMinutes
        RainSixMinuteBucket(
            startLeadMinutes = start,
            endLeadMinutes = start + cadenceMinutes,
            amountMm = amountMm,
        )
    }

    return SixMinuteReconstruction(
        buckets = buckets,
        anchorLeadMinutes = rollingSamples[anchorIndex].leadMinutes,
    )
}

private fun RainForecastFrame.interpolateRainfallAt(latitude: Double, longitude: Double): Double? {
    val latitudes = grid.latitudes
    val longitudes = grid.longitudes
    if (latitudes.size < 2 || longitudes.size < 2) return null
    if (values.size != grid.rows * grid.cols) return null

    val north = latitudes.first()
    val south = latitudes.last()
    val west = longitudes.first()
    val east = longitudes.last()
    if (latitude > grid.bounds.north || latitude < grid.bounds.south) return null
    if (longitude < grid.bounds.west || longitude > grid.bounds.east) return null

    // The declared grid bounds extend half a cell beyond the centre axes.
    // Clamp an edge location to the nearest centre instead of extrapolating.
    val sampleLatitude = latitude.coerceIn(south, north)
    val sampleLongitude = longitude.coerceIn(west, east)
    val northRow = descendingBracketStart(latitudes, sampleLatitude)
    val westColumn = ascendingBracketStart(longitudes, sampleLongitude)

    val latNorth = latitudes[northRow]
    val latSouth = latitudes[northRow + 1]
    val lonWest = longitudes[westColumn]
    val lonEast = longitudes[westColumn + 1]
    val latFraction = if (abs(latNorth - latSouth) <= AXIS_EPSILON) {
        0.0
    } else {
        ((latNorth - sampleLatitude) / (latNorth - latSouth)).coerceIn(0.0, 1.0)
    }
    val lonFraction = if (abs(lonEast - lonWest) <= AXIS_EPSILON) {
        0.0
    } else {
        ((sampleLongitude - lonWest) / (lonEast - lonWest)).coerceIn(0.0, 1.0)
    }

    fun value(row: Int, column: Int): Double = values[row * grid.cols + column]
    val northWest = value(northRow, westColumn)
    val northEast = value(northRow, westColumn + 1)
    val southWest = value(northRow + 1, westColumn)
    val southEast = value(northRow + 1, westColumn + 1)
    if (listOf(northWest, northEast, southWest, southEast).any { !it.isFinite() }) return null

    val northValue = northWest + (northEast - northWest) * lonFraction
    val southValue = southWest + (southEast - southWest) * lonFraction
    return northValue + (southValue - northValue) * latFraction
}

private fun descendingBracketStart(axis: DoubleArray, value: Double): Int {
    if (value >= axis.first()) return 0
    if (value <= axis.last()) return axis.lastIndex - 1
    var low = 0
    var high = axis.lastIndex
    while (high - low > 1) {
        val middle = (low + high) ushr 1
        if (axis[middle] >= value) low = middle else high = middle
    }
    return low
}

private fun ascendingBracketStart(axis: DoubleArray, value: Double): Int {
    if (value <= axis.first()) return 0
    if (value >= axis.last()) return axis.lastIndex - 1
    var low = 0
    var high = axis.lastIndex
    while (high - low > 1) {
        val middle = (low + high) ushr 1
        if (axis[middle] <= value) low = middle else high = middle
    }
    return low
}

private const val DEFAULT_DRY_EPSILON_MM = 0.000001
private const val DEFAULT_CONSISTENCY_TOLERANCE_MM = 0.02
private const val AXIS_EPSILON = 0.000000001
