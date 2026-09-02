package com.weather.metro.ui.storm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormTrack
import com.weather.metro.ui.components.MetroFloatingIsland
import com.weather.metro.ui.theme.LocalMetroSubText
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

@Composable
internal fun StormFocusControls(
    groups: List<StormDisplayGroup>,
    selectedKey: String?,
    pageColour: Color,
    isActive: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = groups.firstOrNull { it.key == selectedKey } ?: groups.first()

    LaunchedEffect(isActive) {
        if (!isActive) expanded = false
    }

    MetroFloatingIsland(
        expanded = expanded,
        accent = pageColour,
        modifier = modifier,
        collapsedContent = {
            Box(
                Modifier
                    .width(5.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(pageColour),
            )
            Text(
                text = selected.displayName,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f, fill = false),
            )
            Text(
                text = "${selected.agencyCount}/4",
                color = LocalMetroSubText.current,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
            if (groups.size > 1) {
                Text(
                    text = "切換",
                    color = pageColour,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { expanded = true }
                        .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                )
            }
        },
        expandedContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "風暴焦點",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${groups.size} 個活躍系統",
                    color = LocalMetroSubText.current,
                    fontSize = 10.sp,
                )
                Text(
                    text = "收起",
                    color = pageColour,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { expanded = false }
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                groups.take(5).forEach { group ->
                    StormFocusRow(
                        group = group,
                        selected = group.key == selected.key,
                        pageColour = pageColour,
                        onClick = {
                            onSelect(group.key)
                            expanded = false
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun StormFocusRow(
    group: StormDisplayGroup,
    selected: Boolean,
    pageColour: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) pageColour.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.26f), shape)
            .border(
                width = 1.dp,
                color = if (selected) pageColour.copy(alpha = 0.68f) else Color.White.copy(alpha = 0.10f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = group.displayName,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                STORM_FOCUS_AGENCY_PRIORITY.forEach { agency ->
                    if (group.tracksByAgency[agency].orEmpty().isNotEmpty()) {
                        Text(
                            text = agency.name,
                            color = stormFocusAgencyColour(agency),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Text(
            text = group.nearestHongKongKm?.let { "最近香港 ${it.toInt()} km" } ?: "距港未提供",
            color = LocalMetroSubText.current,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
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

private fun stormFocusAgencyColour(agency: StormAgency): Color = when (agency) {
    StormAgency.HKO -> Color.White
    StormAgency.CMA -> Color(0xFFFF4B55)
    StormAgency.JMA -> Color(0xFF00D8FF)
    StormAgency.CWA -> Color(0xFFFFEA00)
}

private val STORM_FOCUS_AGENCY_PRIORITY = listOf(
    StormAgency.HKO,
    StormAgency.CWA,
    StormAgency.CMA,
    StormAgency.JMA,
)
