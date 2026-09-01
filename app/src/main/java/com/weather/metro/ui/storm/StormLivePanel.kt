package com.weather.metro.ui.storm

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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

private val STORM_PANEL = Color(0xEB090909)
private val STORM_POPUP_PANEL = Color(0xF20D0D0D)
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
    var selectedPointRef by remember { mutableStateOf<StormMapPointRef?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val enabledAgencies = buildSet {
        if (hkoEnabled) add(StormAgency.HKO)
        if (cmaEnabled) add(StormAgency.CMA)
        if (jmaEnabled) add(StormAgency.JMA)
        if (cwaEnabled) add(StormAgency.CWA)
    }
    val tracksByAgency = StormAgency.entries.associateWith { agency ->
        state.sources[agency]?.storms.orEmpty()
    }
    val visibleTracks = StormAgency.entries
        .filter { it in enabledAgencies }
        .flatMap { tracksByAgency[it].orEmpty() }
    val selectedPoint = resolveStormPointSelection(selectedPointRef, tracksByAgency)
    val reduceMotion = LocalReduceMotion.current

    LaunchedEffect(isActive) {
        if (!isActive) bottomHudExpanded = false
    }

    LaunchedEffect(enabledAgencies, tracksByAgency, selectedPointRef) {
        val ref = selectedPointRef ?: return@LaunchedEffect
        if (ref.agency !in enabledAgencies || resolveStormPointSelection(ref, tracksByAgency) == null) {
            selectedPointRef = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it },
    ) {
        StormMapLibreSurface(
            tracksByAgency = tracksByAgency,
            enabledAgencies = enabledAgencies,
            fitToken = fitToken,
            onPointSelected = { selectedPointRef = it },
            modifier = Modifier.fillMaxSize(),
        )

        ToolInitialLoadingOverlay(
            visible = !state.cacheRestored || (state.isRefreshing && visibleTracks.isEmpty()),
            title = if (state.cacheRestored) "正在同步熱帶氣旋" else "正在載入熱帶氣旋",
            detail = if (state.cacheRestored) "正在整合四個官方機構的最新路徑" else "正在讀取裝置快取與官方來源",
            accent = pageColour,
            modifier = Modifier.fillMaxSize(),
        )

        StormTopBar(
            pageColour = pageColour,
            refreshing = state.isRefreshing,
            onBack = onBack,
            onRefresh = onRefresh,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        StormAgencyControls(
            state = state,
            pageColour = pageColour,
            isActive = isActive,
            hkoEnabled = hkoEnabled,
            cmaEnabled = cmaEnabled,
            jmaEnabled = jmaEnabled,
            cwaEnabled = cwaEnabled,
            onToggleHko = { hkoEnabled = !hkoEnabled },
            onToggleCma = { cmaEnabled = !cmaEnabled },
            onToggleJma = { jmaEnabled = !jmaEnabled },
            onToggleCwa = { cwaEnabled = !cwaEnabled },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 68.dp, start = 8.dp, end = 8.dp),
        )

        StormBottomIsland(
            expanded = bottomHudExpanded,
            onExpandedChange = { bottomHudExpanded = it },
            state = state,
            visibleTracks = visibleTracks,
            enabledAgencyCount = enabledAgencies.size,
            pageColour = pageColour,
            onFit = {
                selectedPointRef = null
                fitToken += 1
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        )

        AnimatedContent(
            targetState = selectedPoint,
            transitionSpec = {
                val duration = if (reduceMotion) 120 else 200
                (fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = 0.96f)) togetherWith
                    (fadeOut(tween(duration)) + scaleOut(tween(duration), targetScale = 0.98f))
            },
            contentKey = { it?.ref },
            label = "storm point popup",
            modifier = Modifier.fillMaxSize(),
        ) { selected ->
            selected?.let {
                StormPointPopup(
                    selected = it,
                    containerSize = containerSize,
                    pageColour = pageColour,
                    onDismiss = { selectedPointRef = null },
                )
            }
        }
    }
}

@Composable
private fun StormTopBar(
    pageColour: Color,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(STORM_PANEL)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "‹ tools",
            color = pageColour,
            fontSize = 16.sp,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(top = 12.dp, end = 14.dp, bottom = 12.dp),
        )
        Column {
            Text(
                text = "熱帶氣旋",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                text = "HKO · CMA · JMA · CWA",
                color = LocalMetroSubText.current,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = if (refreshing) "更新中" else "更新",
            color = if (refreshing) LocalMetroSubText.current else pageColour,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(enabled = !refreshing, onClick = onRefresh)
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun StormAgencyControls(
    state: StormHostState,
    pageColour: Color,
    isActive: Boolean,
    hkoEnabled: Boolean,
    cmaEnabled: Boolean,
    jmaEnabled: Boolean,
    cwaEnabled: Boolean,
    onToggleHko: () -> Unit,
    onToggleCma: () -> Unit,
    onToggleJma: () -> Unit,
    onToggleCwa: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val enabledCount = listOf(hkoEnabled, cmaEnabled, jmaEnabled, cwaEnabled).count { it }

    LaunchedEffect(isActive) {
        if (!isActive) expanded = false
    }

    MetroFloatingIsland(
        expanded = expanded,
        accent = pageColour,
        modifier = modifier,
        collapsedContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .background(if (hkoEnabled) agencyColour(StormAgency.HKO) else Color(0xFF555555)),
                )
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .background(if (cmaEnabled) agencyColour(StormAgency.CMA) else Color(0xFF555555)),
                )
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .background(if (jmaEnabled) agencyColour(StormAgency.JMA) else Color(0xFF555555)),
                )
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .background(if (cwaEnabled) agencyColour(StormAgency.CWA) else Color(0xFF555555)),
                )
            }
            Text(
                text = "$enabledCount/4 機構",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 9.dp),
            )
            Text(
                text = "來源",
                color = pageColour,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
            )
        },
        expandedContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "官方來源",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$enabledCount/4 已啟用",
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StormAgencyChip(
                    source = state.sources[StormAgency.HKO] ?: return@Row,
                    enabled = hkoEnabled,
                    accent = agencyColour(StormAgency.HKO),
                    onClick = onToggleHko,
                    modifier = Modifier.weight(1f),
                )
                StormAgencyChip(
                    source = state.sources[StormAgency.CMA] ?: return@Row,
                    enabled = cmaEnabled,
                    accent = agencyColour(StormAgency.CMA),
                    onClick = onToggleCma,
                    modifier = Modifier.weight(1f),
                )
                StormAgencyChip(
                    source = state.sources[StormAgency.JMA] ?: return@Row,
                    enabled = jmaEnabled,
                    accent = agencyColour(StormAgency.JMA),
                    onClick = onToggleJma,
                    modifier = Modifier.weight(1f),
                )
                StormAgencyChip(
                    source = state.sources[StormAgency.CWA] ?: return@Row,
                    enabled = cwaEnabled,
                    accent = agencyColour(StormAgency.CWA),
                    onClick = onToggleCwa,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
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
    val border by animateColorAsState(
        targetValue = if (enabled) accent else Color(0xFF3A3A3A),
        animationSpec = tween(if (reduceMotion) 100 else 180),
        label = "storm agency border",
    )
    val background by animateColorAsState(
        targetValue = if (enabled) accent.copy(alpha = 0.12f) else Color(0xB80B0B0B),
        animationSpec = tween(if (reduceMotion) 100 else 180),
        label = "storm agency background",
    )
    Column(
        modifier = modifier
            .metroPressMotion(
                interactionSource = interactionSource,
                preset = MetroPressPreset.Chip,
            )
            .border(1.dp, border)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 7.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .background(if (enabled) accent else Color(0xFF555555)),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = source.agency.name,
                color = if (enabled) Color.White else Color(0xFF777777),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = sourceStateLabel(source.liveState, source.refreshing),
            color = if (enabled) sourceStateColour(source.liveState, accent) else Color(0xFF666666),
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun StormBottomIsland(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    state: StormHostState,
    visibleTracks: List<StormTrack>,
    enabledAgencyCount: Int,
    pageColour: Color,
    onFit: () -> Unit,
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
        "點擊路徑點查看完整資料 · 路徑線按機構色 · 路徑點按強度色"
    } else {
        "正在讀取 Storm 裝置快取…"
    }

    MetroFloatingIsland(
        expanded = expanded,
        accent = pageColour,
        modifier = modifier,
        collapsedContent = {
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
            Text(
                text = "全景",
                color = pageColour,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onFit)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            )
            Text(
                text = "詳情",
                color = pageColour,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { onExpandedChange(true) }
                    .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
            )
        },
        expandedContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = summary,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = guidance,
                        color = LocalMetroSubText.current,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "全景",
                    color = pageColour,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(onClick = onFit)
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                )
                Text(
                    text = "收起",
                    color = pageColour,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { onExpandedChange(false) }
                        .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                )
            }

            if (visibleTracks.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = visibleTracks.take(4).joinToString(" · ") { track ->
                        "${track.agency.name} ${trackDisplayName(track)}"
                    },
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            sourceError?.let { message ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message,
                    color = Color(0xFFFF9E9E),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

@Composable
private fun StormPointPopup(
    selected: StormPointSelection,
    containerSize: IntSize,
    pageColour: Color,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    var popupSize by remember(selected.ref) { mutableStateOf(IntSize.Zero) }
    val marginPx = with(density) { 10.dp.toPx() }
    val gapPx = with(density) { 14.dp.toPx() }
    val safeTopPx = with(density) { 138.dp.toPx() }
    val safeBottomPx = with(density) { 92.dp.toPx() }
    val anchorX = selected.ref.anchorXPx ?: containerSize.width / 2f
    val anchorY = selected.ref.anchorYPx ?: containerSize.height / 2f
    val popupWidth = popupSize.width.toFloat()
    val popupHeight = popupSize.height.toFloat()
    val maxX = (containerSize.width - popupWidth - marginPx).coerceAtLeast(marginPx)
    val centeredX = anchorX - popupWidth / 2f
    val x = centeredX.coerceIn(marginPx, maxX)
    val preferredAbove = anchorY - popupHeight - gapPx
    val preferredBelow = anchorY + gapPx
    val maxY = (containerSize.height - popupHeight - safeBottomPx).coerceAtLeast(safeTopPx)
    val y = if (preferredAbove >= safeTopPx) preferredAbove else preferredBelow.coerceAtMost(maxY)

    val track = selected.track
    val point = selected.point
    val sourceAccent = agencyColour(track.agency)
    val typeLabel = stormPointTypeLabel(selected)
    val rows = stormPopupRows(selected)

    Column(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.coerceIn(safeTopPx, maxY).roundToInt()) }
            .widthIn(min = 240.dp, max = 310.dp)
            .heightIn(max = 350.dp)
            .onSizeChanged { popupSize = it }
            .border(1.dp, Color(0xFF3A3A3A))
            .background(STORM_POPUP_PANEL)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "${trackDisplayName(track)} · ${track.agency.name} $typeLabel",
                color = sourceAccent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "×",
                color = pageColour,
                fontSize = 17.sp,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(start = 10.dp, bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
        Spacer(Modifier.height(7.dp))
        rows.forEach { (label, value) ->
            StormPopupRow(label = label, value = value)
        }
    }
}

@Composable
private fun StormPopupRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$label：",
            color = LocalMetroSubText.current,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f),
        )
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
        point.probabilityRadiusKm?.let {
            add("${track.agency.name} 70% 預報圓半徑" to "約 ${formatNumber(it)} 公里")
        }
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

private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) {
    "%.0f".format(value)
} else {
    "%.1f".format(value)
}

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
    val track = tracksByAgency[ref.agency]
        .orEmpty()
        .firstOrNull { it.stableKey == ref.stableKey }
        ?: return null
    val points = when (ref.pointType) {
        StormPointType.ANALYSIS -> track.analysisPoints
        StormPointType.FORECAST -> track.forecastPoints
    }
    val point = points.getOrNull(ref.pointIndex) ?: return null
    return StormPointSelection(track = track, point = point, ref = ref)
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
