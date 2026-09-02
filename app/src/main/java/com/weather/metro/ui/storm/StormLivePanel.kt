package com.weather.metro.ui.storm

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormLiveState
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormPointType
import com.weather.metro.domain.storm.StormTrack
import com.weather.metro.ui.components.MetroFloatingIsland
import com.weather.metro.ui.layout.metroSafeBottom
import com.weather.metro.ui.layout.metroSafeTop
import com.weather.metro.ui.motion.MetroPressPreset
import com.weather.metro.ui.motion.metroPressMotion
import com.weather.metro.ui.theme.LocalMetroSubText
import com.weather.metro.ui.theme.LocalReduceMotion
import com.weather.metro.ui.tools.ToolInitialLoadingOverlay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val HONG_KONG_LAT = 22.3023
private const val HONG_KONG_LON = 114.1746
private const val EARTH_RADIUS_KM = 6371.0088

internal data class StormPointSelection(
    val track: StormTrack,
    val point: StormPoint,
    val ref: StormMapPointRef,
)

@Composable
internal fun StormLivePanel(
    state: StormHostState,
    pageColour: Color,
    isActive: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onCancelRequests: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isActive) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && isActive) onCancelRequests()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var hkoEnabled by rememberSaveable { mutableStateOf(true) }
    var cmaEnabled by rememberSaveable { mutableStateOf(true) }
    var jmaEnabled by rememberSaveable { mutableStateOf(true) }
    var cwaEnabled by rememberSaveable { mutableStateOf(true) }
    var fitToken by rememberSaveable { mutableIntStateOf(0) }
    var bottomHudExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedStormKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPointRef by remember { mutableStateOf<StormMapPointRef?>(null) }

    val enabledAgencies = buildSet {
        if (hkoEnabled) add(StormAgency.HKO)
        if (cmaEnabled) add(StormAgency.CMA)
        if (jmaEnabled) add(StormAgency.JMA)
        if (cwaEnabled) add(StormAgency.CWA)
    }
    val allTracksByAgency = StormAgency.entries.associateWith { agency ->
        state.sources[agency]?.storms.orEmpty()
    }
    val stormGroups = buildStormDisplayGroups(allTracksByAgency)
    val focusedGroup = stormGroups.firstOrNull { it.key == selectedStormKey } ?: stormGroups.firstOrNull()
    val tracksByAgency = focusedGroup?.tracksByAgency
        ?: StormAgency.entries.associateWith { emptyList<StormTrack>() }
    val visibleTracks = StormAgency.entries
        .filter { it in enabledAgencies }
        .flatMap { tracksByAgency[it].orEmpty() }
    val selectedPoint = resolveStormPointSelection(selectedPointRef, tracksByAgency)

    LaunchedEffect(isActive) {
        if (!isActive) bottomHudExpanded = false
    }

    LaunchedEffect(stormGroups.map { it.key }, selectedStormKey) {
        val nextKey = stormGroups.firstOrNull { it.key == selectedStormKey }?.key ?: stormGroups.firstOrNull()?.key
        if (nextKey != selectedStormKey) {
            selectedStormKey = nextKey
            selectedPointRef = null
            bottomHudExpanded = false
            fitToken += 1
        }
    }

    LaunchedEffect(enabledAgencies, tracksByAgency, selectedPointRef) {
        val ref = selectedPointRef ?: return@LaunchedEffect
        if (ref.agency !in enabledAgencies || resolveStormPointSelection(ref, tracksByAgency) == null) {
            selectedPointRef = null
            bottomHudExpanded = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        StormMapLibreSurface(
            tracksByAgency = tracksByAgency,
            enabledAgencies = enabledAgencies,
            selectedPointRef = selectedPointRef,
            fitToken = fitToken,
            onPointSelected = { ref ->
                selectedPointRef = ref
                bottomHudExpanded = ref != null
            },
            modifier = Modifier.fillMaxSize(),
        )

        ToolInitialLoadingOverlay(
            visible = !state.cacheRestored || (state.isRefreshing && visibleTracks.isEmpty()),
            title = if (state.cacheRestored) "正在同步熱帶氣旋" else "正在載入熱帶氣旋",
            detail = if (state.cacheRestored) "正在整合四個官方機構的最新路徑" else "正在讀取裝置快取與官方來源",
            accent = pageColour,
            modifier = Modifier.fillMaxSize(),
        )

        StormTopControlHub(
            groups = stormGroups,
            selectedKey = focusedGroup?.key,
            state = state,
            pageColour = pageColour,
            isActive = isActive,
            refreshing = state.isRefreshing,
            hkoEnabled = hkoEnabled,
            cmaEnabled = cmaEnabled,
            jmaEnabled = jmaEnabled,
            cwaEnabled = cwaEnabled,
            onBack = onBack,
            onRefresh = onRefresh,
            onSelectStorm = { key ->
                if (selectedStormKey != key) {
                    selectedStormKey = key
                    selectedPointRef = null
                    bottomHudExpanded = false
                    fitToken += 1
                }
            },
            onToggleHko = { hkoEnabled = !hkoEnabled },
            onToggleCma = { cmaEnabled = !cmaEnabled },
            onToggleJma = { jmaEnabled = !jmaEnabled },
            onToggleCwa = { cwaEnabled = !cwaEnabled },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .metroSafeTop()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp),
        )

        StormBottomIsland(
            expanded = bottomHudExpanded,
            onExpandedChange = { bottomHudExpanded = it },
            selectedPoint = selectedPoint,
            state = state,
            visibleTracks = visibleTracks,
            enabledAgencyCount = enabledAgencies.size,
            pageColour = pageColour,
            onFit = { fitToken += 1 },
            onDismissPoint = {
                selectedPointRef = null
                bottomHudExpanded = false
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .metroSafeBottom()
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun StormTopControlHub(
    groups: List<StormDisplayGroup>,
    selectedKey: String?,
    state: StormHostState,
    pageColour: Color,
    isActive: Boolean,
    refreshing: Boolean,
    hkoEnabled: Boolean,
    cmaEnabled: Boolean,
    jmaEnabled: Boolean,
    cwaEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectStorm: (String) -> Unit,
    onToggleHko: () -> Unit,
    onToggleCma: () -> Unit,
    onToggleJma: () -> Unit,
    onToggleCwa: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = groups.firstOrNull { it.key == selectedKey } ?: groups.firstOrNull()
    val enabledCount = listOf(hkoEnabled, cmaEnabled, jmaEnabled, cwaEnabled).count { it }
    val agencyStates = listOf(
        StormAgency.HKO to hkoEnabled,
        StormAgency.CMA to cmaEnabled,
        StormAgency.JMA to jmaEnabled,
        StormAgency.CWA to cwaEnabled,
    )

    LaunchedEffect(isActive) {
        if (!isActive) expanded = false
    }

    MetroFloatingIsland(
        expanded = expanded,
        accent = pageColour,
        modifier = modifier,
        collapsedContent = {
            Text(
                text = "‹",
                color = pageColour,
                fontSize = 23.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
            )
            Box(
                Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(pageColour),
            )
            Column(
                modifier = Modifier
                    .padding(start = 7.dp)
                    .weight(1f),
            ) {
                Text(
                    text = selected?.displayName ?: "熱帶氣旋",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = selected?.nearestHongKongKm?.let { "最近香港 ${it.roundToInt()} km" } ?: "HKO · CMA · JMA · CWA",
                    color = LocalMetroSubText.current,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 6.dp),
            ) {
                agencyStates.forEach { (agency, enabled) ->
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (enabled) agencyColour(agency) else Color(0xFF4A4A4A)),
                    )
                }
            }
            Text("$enabledCount/4", color = LocalMetroSubText.current, fontSize = 9.sp, modifier = Modifier.padding(start = 5.dp))
            Text(
                text = "控制",
                color = pageColour,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(start = 7.dp, top = 7.dp, bottom = 7.dp),
            )
            Text(
                text = if (refreshing) "…" else "更新",
                color = if (refreshing) LocalMetroSubText.current else pageColour,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable(enabled = !refreshing, onClick = onRefresh)
                    .padding(start = 7.dp, top = 7.dp, bottom = 7.dp),
            )
        },
        expandedContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "‹ tools",
                    color = pageColour,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 10.dp, top = 6.dp, bottom = 6.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = selected?.displayName ?: "熱帶氣旋",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (groups.isEmpty()) "沒有活躍系統" else "${groups.size} 個活躍系統 · $enabledCount/4 來源已啟用",
                        color = LocalMetroSubText.current,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                }
                Text(
                    text = if (refreshing) "更新中" else "更新",
                    color = if (refreshing) LocalMetroSubText.current else pageColour,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable(enabled = !refreshing, onClick = onRefresh)
                        .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                )
                Text(
                    text = "收起",
                    color = pageColour,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { expanded = false }
                        .padding(start = 9.dp, top = 6.dp, bottom = 6.dp),
                )
            }

            if (groups.size > 1) {
                Spacer(Modifier.height(7.dp))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    groups.take(4).forEach { group ->
                        StormFocusOptionRow(
                            group = group,
                            selected = group.key == selected?.key,
                            pageColour = pageColour,
                            onClick = { onSelectStorm(group.key) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
            Spacer(Modifier.height(7.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("官方來源", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("$enabledCount/4 已啟用", color = LocalMetroSubText.current, fontSize = 9.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.sources[StormAgency.HKO]?.let { source ->
                    StormAgencyChip(source, hkoEnabled, agencyColour(StormAgency.HKO), onToggleHko, Modifier.weight(1f))
                }
                state.sources[StormAgency.CMA]?.let { source ->
                    StormAgencyChip(source, cmaEnabled, agencyColour(StormAgency.CMA), onToggleCma, Modifier.weight(1f))
                }
                state.sources[StormAgency.JMA]?.let { source ->
                    StormAgencyChip(source, jmaEnabled, agencyColour(StormAgency.JMA), onToggleJma, Modifier.weight(1f))
                }
                state.sources[StormAgency.CWA]?.let { source ->
                    StormAgencyChip(source, cwaEnabled, agencyColour(StormAgency.CWA), onToggleCwa, Modifier.weight(1f))
                }
            }
        },
    )
}

@Composable
private fun StormFocusOptionRow(
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
            .background(if (selected) pageColour.copy(alpha = 0.13f) else Color.Black.copy(alpha = 0.24f), shape)
            .border(
                1.dp,
                if (selected) pageColour.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.08f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = group.displayName,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(StormAgency.HKO, StormAgency.CMA, StormAgency.JMA, StormAgency.CWA).forEach { agency ->
                    if (group.tracksByAgency[agency].orEmpty().isNotEmpty()) {
                        Text(
                            text = agency.name,
                            color = agencyColour(agency),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Text(
            text = group.nearestHongKongKm?.let { "${it.roundToInt()} km" } ?: "—",
            color = LocalMetroSubText.current,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun StormAgencyChip(
    source: StormAgencyHostState,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(15.dp)
    val border by animateColorAsState(
        targetValue = if (enabled) accent.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.12f),
        animationSpec = tween(if (reduceMotion) 100 else 180),
        label = "storm agency border",
    )
    val background by animateColorAsState(
        targetValue = if (enabled) accent.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.34f),
        animationSpec = tween(if (reduceMotion) 100 else 180),
        label = "storm agency background",
    )
    Column(
        modifier = modifier
            .heightIn(min = 52.dp)
            .metroPressMotion(interactionSource = interactionSource, preset = MetroPressPreset.Chip)
            .clip(shape)
            .background(background, shape)
            .border(1.dp, border, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (enabled) accent else Color(0xFF555555)),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = if (enabled) "✓ ${source.agency.name}" else source.agency.name,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.42f),
                fontSize = 11.sp,
                fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
        Text(
            text = sourceStateLabel(source.liveState, source.refreshing),
            color = if (enabled) sourceStateColour(source.liveState, accent) else Color.White.copy(alpha = 0.32f),
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StormBottomIsland(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedPoint: StormPointSelection?,
    state: StormHostState,
    visibleTracks: List<StormTrack>,
    enabledAgencyCount: Int,
    pageColour: Color,
    onFit: () -> Unit,
    onDismissPoint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceError = state.sources.values
        .firstOrNull { !it.errorMessage.isNullOrBlank() }
        ?.let { source -> "${source.agency.name}: ${source.errorMessage}" }
    val summary = when {
        enabledAgencyCount == 0 -> "未選擇來源"
        state.isRefreshing -> "正在同步四機構官方資料…"
        visibleTracks.isEmpty() -> "目前沒有可顯示的活躍路徑"
        else -> "顯示 ${visibleTracks.size} 條機構路徑 · $enabledAgencyCount/4 來源"
    }
    val compactSummary = when {
        enabledAgencyCount == 0 -> "未選擇來源"
        state.isRefreshing -> "同步中 · $enabledAgencyCount/4"
        visibleTracks.isEmpty() -> "暫無路徑 · $enabledAgencyCount/4"
        else -> "${visibleTracks.size} 路徑 · $enabledAgencyCount/4 來源"
    }
    val guidance = if (state.cacheRestored) {
        "實線＝分析 · 虛線＝預報 · 外圈＝機構 · 點色＝強度"
    } else {
        "正在讀取 Storm 裝置快取…"
    }

    MetroFloatingIsland(
        expanded = expanded,
        accent = selectedPoint?.track?.agency?.let(::agencyColour) ?: pageColour,
        modifier = modifier,
        collapsedContent = {
            if (selectedPoint != null) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 230.dp)
                        .weight(1f, fill = false),
                ) {
                    Text(
                        text = "${formatStormPointTime(selectedPoint.point.validAt)} · ${selectedPoint.track.agency.name} ${stormPointTypeLabel(selectedPoint)}",
                        color = agencyColour(selectedPoint.track.agency),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${trackDisplayName(selectedPoint.track)} · ${stormPointMetrics(selectedPoint.point)}",
                        color = LocalMetroSubText.current,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("全景", color = pageColour, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onFit).padding(start = 10.dp, top = 8.dp, bottom = 8.dp))
                Text("詳情", color = pageColour, fontSize = 12.sp, modifier = Modifier.clickable { onExpandedChange(true) }.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
                Text("×", color = pageColour, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onDismissPoint).padding(start = 7.dp, top = 6.dp, bottom = 6.dp))
            } else {
                Column(Modifier.widthIn(max = 190.dp)) {
                    Text(
                        text = compactSummary,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (state.cacheRestored) "點擊路徑點查看資料" else "正在讀取裝置快取…",
                        color = LocalMetroSubText.current,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("全景", color = pageColour, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onFit).padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
                Text("詳情", color = pageColour, fontSize = 12.sp, modifier = Modifier.clickable { onExpandedChange(true) }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp))
            }
        },
        expandedContent = {
            if (selectedPoint != null) {
                StormSelectedPointDetail(
                    selected = selectedPoint,
                    pageColour = pageColour,
                    onFit = onFit,
                    onCollapse = { onExpandedChange(false) },
                    onDismiss = onDismissPoint,
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(summary, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(guidance, color = LocalMetroSubText.current, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("全景", color = pageColour, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onFit).padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
                    Text("收起", color = pageColour, fontSize = 12.sp, modifier = Modifier.clickable { onExpandedChange(false) }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp))
                }
                if (visibleTracks.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = visibleTracks.take(4).joinToString(" · ") { track -> "${track.agency.name} ${trackDisplayName(track)}" },
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                sourceError?.let { message ->
                    Spacer(Modifier.height(4.dp))
                    Text(message, color = Color(0xFFFF9E9E), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    )
}

@Composable
private fun StormSelectedPointDetail(
    selected: StormPointSelection,
    pageColour: Color,
    onFit: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sourceAccent = agencyColour(selected.track.agency)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "${trackDisplayName(selected.track)} · ${selected.track.agency.name} ${stormPointTypeLabel(selected)}",
                color = sourceAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "時間點 ${formatStormPointTime(selected.point.validAt)} · 距港約 ${stormDistanceToHongKongKm(selected.point).roundToInt()} 公里",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        Text("全景", color = pageColour, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onFit).padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
        Text("收起", color = pageColour, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onCollapse).padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
        Text("×", color = pageColour, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(start = 7.dp, top = 4.dp, bottom = 4.dp))
    }
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
    Spacer(Modifier.height(6.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 205.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        stormPopupRows(selected).forEach { (label, value) -> StormDetailRow(label, value) }
    }
}

@Composable
private fun StormDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("$label：", color = LocalMetroSubText.current, fontSize = 10.sp, lineHeight = 15.sp)
        Text(value, color = Color.White, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
    }
}

private fun stormPopupRows(selected: StormPointSelection): List<Pair<String, String>> {
    val point = selected.point
    val track = selected.track
    return buildList {
        add("時間" to formatStormPointTime(point.validAt))
        add("位置" to formatStormLatLon(point.latitude, point.longitude))
        add("強度" to stormIntensityDisplayLabel(point))
        add("最高持續風速" to point.windSpeedMs?.let { "${formatNumber(it)} m/s" }.orNotProvided())
        add("中心氣壓" to point.pressureHpa?.let { "${formatNumber(it)} hPa" }.orNotProvided())
        point.forecastHour?.let { add("預報時效" to "+$it 小時") }
        point.maximumGustMs?.let { add("最大陣風" to "${formatNumber(it)} m/s") }
        val movement = listOfNotNull(
            point.movingDirection?.takeIf { it.isNotBlank() },
            point.movingSpeedKmh?.let { "${formatNumber(it)} km/h" },
        ).joinToString(" ")
        if (movement.isNotBlank()) add("移動" to movement)
        point.movementPrediction?.takeIf { it.isNotBlank() }?.let { add("移動預測" to it) }
        point.stateTransfer?.takeIf { it.isNotBlank() }?.let { add("強度趨勢" to it) }
        point.probabilityRadiusKm?.let { add("${track.agency.name} 70% 預報圓半徑" to "約 ${formatNumber(it)} 公里") }
        if (point.windRadii.isNotEmpty()) add("風圈" to formatStormWindRadii(point))
        add("距離香港" to "約 ${stormDistanceToHongKongKm(point).roundToInt()} 公里")
        add("來源" to stormAgencySourceName(track.agency))
    }
}

private fun String?.orNotProvided(): String = this ?: "未提供"

private fun stormPointTypeLabel(selected: StormPointSelection): String = when {
    selected.point.pointType == StormPointType.FORECAST -> "預測"
    selected.ref.pointIndex == selected.track.analysisPoints.lastIndex -> "最新分析"
    else -> "實測 / 分析"
}

private fun formatStormWindRadii(point: StormPoint): String = point.windRadii.joinToString("；") { radii ->
    val max = maxOf(radii.northEastKm, radii.southEastKm, radii.southWestKm, radii.northWestKm)
    "${radii.level ?: "風圈"} 最大約 ${formatNumber(max)} km"
}

private fun stormAgencySourceName(agency: StormAgency): String = when (agency) {
    StormAgency.HKO -> "香港天文台"
    StormAgency.CMA -> "中央氣象台"
    StormAgency.JMA -> "日本氣象廳"
    StormAgency.CWA -> "中央氣象署"
}

private fun formatStormLatLon(latitude: Double, longitude: Double): String {
    val latDirection = if (latitude >= 0) "N" else "S"
    val lonDirection = if (longitude >= 0) "E" else "W"
    return "${"%.1f".format(kotlin.math.abs(latitude))}°$latDirection, ${"%.1f".format(kotlin.math.abs(longitude))}°$lonDirection"
}

private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) "%.0f".format(value) else "%.1f".format(value)

internal fun stormDistanceToHongKongKm(point: StormPoint): Double {
    val lat1 = Math.toRadians(HONG_KONG_LAT)
    val lat2 = Math.toRadians(point.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(point.longitude - HONG_KONG_LON)
    val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
        cos(lat1) * cos(lat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
    return 2.0 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

internal fun resolveStormPointSelection(
    ref: StormMapPointRef?,
    tracksByAgency: Map<StormAgency, List<StormTrack>>,
): StormPointSelection? {
    ref ?: return null
    val track = tracksByAgency[ref.agency].orEmpty().firstOrNull { it.stableKey == ref.stableKey } ?: return null
    val points = when (ref.pointType) {
        StormPointType.ANALYSIS -> track.analysisPoints
        StormPointType.FORECAST -> track.forecastPoints
    }
    val point = points.getOrNull(ref.pointIndex) ?: return null
    return StormPointSelection(track, point, ref)
}

internal fun stormPointMetrics(point: StormPoint): String {
    val metrics = buildList {
        add(stormIntensityDisplayLabel(point))
        point.windSpeedMs?.let { add("最大風速 ${formatNumber(it)} m/s") }
        point.maximumGustMs?.let { add("陣風 ${formatNumber(it)} m/s") }
        point.pressureHpa?.let { add("${formatNumber(it)} hPa") }
        point.forecastHour?.let { add("+$it h") }
        point.probabilityRadiusKm?.let { add("預報圓 ${formatNumber(it)} km") }
        point.movingDirection?.takeIf { it.isNotBlank() }?.let { direction ->
            point.movingSpeedKmh?.let { speed -> add("$direction ${formatNumber(speed)} km/h") } ?: add(direction)
        }
    }
    return metrics.ifEmpty { listOf("此資料點沒有額外強度資料") }.joinToString(" · ")
}

private fun trackDisplayName(track: StormTrack): String = when {
    !track.nameZh.isNullOrBlank() && !track.nameEn.isNullOrBlank() -> "${track.nameZh} (${track.nameEn})"
    !track.nameZh.isNullOrBlank() -> track.nameZh
    !track.nameEn.isNullOrBlank() -> track.nameEn
    else -> track.agencyStormId
}

private fun sourceStateLabel(state: StormLiveState, refreshing: Boolean): String {
    if (refreshing) return "同步中"
    return when (state) {
        StormLiveState.LOADING -> "準備"
        StormLiveState.OK -> "正常"
        StormLiveState.EMPTY -> "無風暴"
        StormLiveState.STALE -> "保留"
        StormLiveState.ERROR -> "失敗"
    }
}

private fun sourceStateColour(state: StormLiveState, accent: Color): Color = when (state) {
    StormLiveState.OK -> accent
    StormLiveState.EMPTY -> Color(0xFF8A8A8A)
    StormLiveState.STALE -> Color(0xFFFFC857)
    StormLiveState.ERROR -> Color(0xFFFF6B6B)
    StormLiveState.LOADING -> Color(0xFFB0B0B0)
}

private fun agencyColour(agency: StormAgency): Color = when (agency) {
    StormAgency.HKO -> Color.White
    StormAgency.CMA -> Color(0xFFFF4B55)
    StormAgency.JMA -> Color(0xFF00D8FF)
    StormAgency.CWA -> Color(0xFFFFEA00)
}

private fun formatStormPointTime(value: String): String = runCatching {
    STORM_POINT_TIME_FORMATTER.format(Instant.parse(value))
}.getOrElse { value }

private val STORM_POINT_TIME_FORMATTER = DateTimeFormatter
    .ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.of("Asia/Hong_Kong"))
