package com.weather.metro.ui.storm

import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormTrack
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val FOCUS_HONG_KONG_LAT = 22.3023
private const val FOCUS_HONG_KONG_LON = 114.1746
private const val FOCUS_EARTH_RADIUS_KM = 6371.0088
private const val FOCUS_PROXIMITY_KM = 260.0
private const val FOCUS_TIME_WINDOW_HOURS = 18L

internal data class StormDisplayGroup(
    val key: String,
    val displayName: String,
    val tracksByAgency: Map<StormAgency, List<StormTrack>>,
    val nearestHongKongKm: Double?,
) {
    val agencyCount: Int
        get() = tracksByAgency.values.count { it.isNotEmpty() }

    val tracks: List<StormTrack>
        get() = StormAgency.entries.flatMap { tracksByAgency[it].orEmpty() }
}

private data class MutableStormDisplayGroup(
    val nameKeys: MutableSet<String> = linkedSetOf(),
    val tracks: MutableMap<StormAgency, StormTrack> = linkedMapOf(),
)

internal fun buildStormDisplayGroups(
    tracksByAgency: Map<StormAgency, List<StormTrack>>,
): List<StormDisplayGroup> {
    val groups = mutableListOf<MutableStormDisplayGroup>()
    val orderedTracks = STORM_FOCUS_AGENCY_PRIORITY.flatMap { agency -> tracksByAgency[agency].orEmpty() }

    orderedTracks.forEach { track ->
        val keys = stormFocusSpecificNameKeys(track)
        var group = groups.firstOrNull { candidate -> keys.any(candidate.nameKeys::contains) }

        if (group == null) {
            val current = stormFocusCurrentPoint(track)
            group = current?.let { point ->
                groups.firstOrNull { candidate ->
                    val candidateHasSpecificName = candidate.nameKeys.isNotEmpty()
                    if (keys.isNotEmpty() && candidateHasSpecificName) return@firstOrNull false
                    val candidateTrack = candidate.tracks.values.firstOrNull() ?: return@firstOrNull false
                    val other = stormFocusCurrentPoint(candidateTrack) ?: return@firstOrNull false
                    stormFocusTimeCompatible(point, other) &&
                        stormFocusDistanceKm(point.latitude, point.longitude, other.latitude, other.longitude) < FOCUS_PROXIMITY_KM
                }
            }
        }

        if (group == null) {
            group = MutableStormDisplayGroup()
            groups += group
        }

        group.nameKeys += keys
        val existing = group.tracks[track.agency]
        if (existing == null || stormFocusTrackTime(track) >= stormFocusTrackTime(existing)) {
            group.tracks[track.agency] = track
        }
    }

    val usedKeys = mutableSetOf<String>()
    return groups.map { group ->
        val representative = STORM_FOCUS_AGENCY_PRIORITY.firstNotNullOfOrNull { group.tracks[it] }
            ?: group.tracks.values.first()
        val baseKey = group.nameKeys.firstOrNull()
            ?: stormFocusNormalizeName(representative.nameEn)
            ?: stormFocusNormalizeName(representative.nameZh)
            ?: representative.stableKey
        var key = baseKey
        var suffix = 2
        while (!usedKeys.add(key)) {
            key = "$baseKey#$suffix"
            suffix += 1
        }
        val map = StormAgency.entries.associateWith { agency -> group.tracks[agency]?.let(::listOf).orEmpty() }
        StormDisplayGroup(
            key = key,
            displayName = stormFocusTrackDisplayName(representative),
            tracksByAgency = map,
            nearestHongKongKm = group.tracks.values
                .flatMap { track -> buildList {
                    track.analysisPoints.lastOrNull()?.let(::add)
                    addAll(track.forecastPoints)
                } }
                .minOfOrNull { point -> stormFocusDistanceToHongKongKm(point) },
        )
    }.sortedWith(
        compareBy<StormDisplayGroup> { it.nearestHongKongKm ?: Double.POSITIVE_INFINITY }
            .thenByDescending { it.tracksByAgency[StormAgency.HKO].orEmpty().isNotEmpty() }
            .thenBy { it.displayName },
    )
}

private fun stormFocusSpecificNameKeys(track: StormTrack): Set<String> = buildSet {
    listOf(track.nameEn, track.nameZh).forEach { raw ->
        val normalized = stormFocusNormalizeName(raw) ?: return@forEach
        if (!stormFocusIsGenericName(normalized)) add(normalized)
    }
}

private fun stormFocusNormalizeName(value: String?): String? = value
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.filter { it.isLetterOrDigit() }
    ?.uppercase()
    ?.takeIf { it.isNotEmpty() }

private fun stormFocusIsGenericName(value: String): Boolean {
    val compact = value.uppercase()
    if (compact in setOf(
            "UNNAMED", "NAMELESS", "TROPICALDEPRESSION", "TROPICALSTORM", "TD", "TS",
            "熱帶低氣壓", "熱帶低壓", "热带低气压", "热带低压", "熱帶風暴", "热带风暴",
            "未命名熱帶氣旋", "未命名热带气旋", "未命名",
        )
    ) return true
    return Regex("^(?:TROPICALDEPRESSION|TROPICALSTORM|熱帶低氣壓|熱帶低壓|热带低气压|热带低压|熱帶風暴|热带风暴)?(?:TC|TD|TS)?\\d+[A-Z]?$", RegexOption.IGNORE_CASE)
        .matches(compact)
}

private fun stormFocusCurrentPoint(track: StormTrack): StormPoint? =
    track.analysisPoints.lastOrNull() ?: track.forecastPoints.firstOrNull()

private fun stormFocusTrackTime(track: StormTrack): Long = listOfNotNull(
    track.bulletinTime,
    track.analysisPoints.lastOrNull()?.validAt,
    track.forecastPoints.firstOrNull()?.validAt,
).firstNotNullOfOrNull(::stormFocusParseTime) ?: Long.MIN_VALUE

private fun stormFocusParseTime(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

private fun stormFocusTimeCompatible(a: StormPoint, b: StormPoint): Boolean {
    val aTime = stormFocusParseTime(a.validAt) ?: return true
    val bTime = stormFocusParseTime(b.validAt) ?: return true
    return kotlin.math.abs(aTime - bTime) <= FOCUS_TIME_WINDOW_HOURS * 3_600_000L
}

private fun stormFocusTrackDisplayName(track: StormTrack): String = when {
    !track.nameZh.isNullOrBlank() && !track.nameEn.isNullOrBlank() -> "${track.nameZh} (${track.nameEn})"
    !track.nameZh.isNullOrBlank() -> track.nameZh
    !track.nameEn.isNullOrBlank() -> track.nameEn
    else -> track.agencyStormId
}

private fun stormFocusDistanceToHongKongKm(point: StormPoint): Double =
    stormFocusDistanceKm(FOCUS_HONG_KONG_LAT, FOCUS_HONG_KONG_LON, point.latitude, point.longitude)

private fun stormFocusDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val firstLat = Math.toRadians(lat1)
    val secondLat = Math.toRadians(lat2)
    val dLat = secondLat - firstLat
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
        cos(firstLat) * cos(secondLat) * sin(dLon / 2.0) * sin(dLon / 2.0)
    return 2.0 * FOCUS_EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private val STORM_FOCUS_AGENCY_PRIORITY = listOf(
    StormAgency.HKO,
    StormAgency.CWA,
    StormAgency.CMA,
    StormAgency.JMA,
)
