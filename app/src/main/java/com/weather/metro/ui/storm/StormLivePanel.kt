package com.weather.metro.ui.storm

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormLiveState
import com.weather.metro.domain.storm.StormTrack
import com.weather.metro.ui.theme.LocalMetroSubText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        StormTopBar(
            pageColour = pageColour,
            refreshing = state.isRefreshing,
            onBack = onBack,
            onRefresh = onRefresh,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp,
                end = 18.dp,
                bottom = 44.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = if (state.cacheRestored) {
                        "四機構 Live data host · ${state.successfulSourceCount}/4 有最近成功資料"
                    } else {
                        "正在讀取 Storm 裝置快取…"
                    },
                    color = LocalMetroSubText.current,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    StormAgency.entries.forEach { agency ->
                        val source = state.sources[agency] ?: return@forEach
                        StormSourceRow(source = source, accent = agencyColour(agency))
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "活躍路徑",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Light,
                )
            }

            val tracks = StormAgency.entries.flatMap { agency ->
                state.sources[agency]?.storms.orEmpty()
            }
            if (tracks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111111))
                            .padding(16.dp),
                    ) {
                        Text(
                            text = if (state.isRefreshing) "正在同步官方資料…" else "目前沒有可顯示的活躍路徑",
                            color = LocalMetroSubText.current,
                            fontSize = 14.sp,
                        )
                    }
                }
            } else {
                items(
                    items = tracks,
                    key = { "${it.agency.name}:${it.stableKey}" },
                ) { track ->
                    StormTrackCard(track = track, accent = agencyColour(track.agency))
                }
            }

            item {
                Text(
                    text = "S1C 先驗證 native state、逐來源更新及 last-success cache；MapLibre 路徑、預報圓與風圈會在下一 checkpoint 接入。",
                    color = LocalMetroSubText.current.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 6.dp),
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "‹ tools",
            color = pageColour,
            fontSize = 16.sp,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(vertical = 12.dp, end = 14.dp),
        )
        Text(
            text = "熱帶氣旋",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (refreshing) "更新中" else "更新",
            color = if (refreshing) LocalMetroSubText.current else pageColour,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(enabled = !refreshing, onClick = onRefresh)
                .padding(vertical = 12.dp, start = 14.dp),
        )
    }
}

@Composable
private fun StormSourceRow(
    source: StormAgencyHostState,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(34.dp)
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.agency.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (source.isCached) {
                    Spacer(Modifier.width(8.dp))
                    Text("快取", color = accent, fontSize = 10.sp)
                }
            }
            Text(
                text = buildString {
                    append(source.message)
                    source.updatedAt?.let { append(" · ").append(formatStormTime(it)) }
                },
                color = LocalMetroSubText.current,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            source.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = Color(0xFFFF9E9E),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }
        Text(
            text = sourceStateLabel(source.liveState, source.refreshing),
            color = sourceStateColour(source.liveState, accent),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun StormTrackCard(track: StormTrack, accent: Color) {
    val latest = track.analysisPoints.lastOrNull() ?: track.forecastPoints.firstOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = trackDisplayName(track),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.weight(1f),
            )
            Text(track.agency.name, color = accent, fontSize = 12.sp)
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = "分析 ${track.analysisPoints.size} 點 · 預測 ${track.forecastPoints.size} 點",
            color = LocalMetroSubText.current,
            fontSize = 11.sp,
        )
        latest?.let { point ->
            Text(
                text = "最新位置 ${"%.1f".format(point.latitude)}°, ${"%.1f".format(point.longitude)}° · ${formatStormTime(point.validAt)}",
                color = LocalMetroSubText.current,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        track.bulletinTime?.let { bulletin ->
            Text(
                text = "公報 ${formatStormTime(bulletin)}",
                color = LocalMetroSubText.current,
                fontSize = 11.sp,
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
        StormLiveState.STALE -> "保留資料"
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

private fun formatStormTime(value: String): String = runCatching {
    STORM_TIME_FORMATTER.format(Instant.parse(value))
}.getOrElse { value }

private val STORM_TIME_FORMATTER = DateTimeFormatter
    .ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.of("Asia/Hong_Kong"))
