package com.weather.metro.ui.storm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.weather.metro.domain.storm.StormTrack
import com.weather.metro.ui.theme.LocalMetroSubText

private val STORM_PANEL = Color(0xEB090909)
private val STORM_MUTED_PANEL = Color(0xE6111111)

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        StormMapLibreSurface(
            tracksByAgency = tracksByAgency,
            enabledAgencies = enabledAgencies,
            fitToken = fitToken,
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

        StormBottomHud(
            state = state,
            visibleTracks = visibleTracks,
            enabledAgencyCount = enabledAgencies.size,
            pageColour = pageColour,
            onFit = { fitToken += 1 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        )
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(STORM_PANEL)
            .padding(6.dp),
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
}

@Composable
private fun StormAgencyChip(
    source: StormAgencyHostState,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (enabled) accent else Color(0xFF3A3A3A)
    val background = if (enabled) accent.copy(alpha = 0.12f) else Color(0xB80B0B0B)
    Column(
        modifier = modifier
            .border(1.dp, border)
            .background(background)
            .clickable(onClick = onClick)
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
private fun StormBottomHud(
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(STORM_MUTED_PANEL)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        enabledAgencyCount == 0 -> "未選擇來源"
                        state.isRefreshing -> "正在同步四機構官方資料…"
                        visibleTracks.isEmpty() -> "目前沒有可顯示的活躍路徑"
                        else -> "顯示 ${visibleTracks.size} 條機構路徑 · $enabledAgencyCount/4 來源"
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (state.cacheRestored) {
                        "分析實線 · 預測虛線 · 實心分析點 · 空心預測點 · 預報圓 / 風圈"
                    } else {
                        "正在讀取 Storm 裝置快取…"
                    },
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
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(onClick = onFit)
                    .padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
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
    }
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
