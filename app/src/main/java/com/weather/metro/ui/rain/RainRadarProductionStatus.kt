package com.weather.metro.ui.rain

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.weather.metro.data.tools.RainRadarMode
import com.weather.metro.ui.components.MetroGlassContextSurface
import kotlinx.coroutines.delay

@Composable
fun RainRadarProductionStatus(
    state: RainRadarHostState,
    isActive: Boolean,
    accent: Color,
    onAutoRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowEpochMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(isActive, state.mode, lifecycleOwner) {
        if (!isActive || state.mode != RainRadarMode.LIVE) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(RADAR_AUTO_REFRESH_MS)
                onAutoRefresh()
            }
        }
    }

    LaunchedEffect(isActive, lifecycleOwner) {
        if (!isActive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                nowEpochMillis = System.currentTimeMillis()
                delay(FRESHNESS_TICK_MS)
            }
        }
    }

    val timeline = state.timeline.value ?: return
    val latestTime = timeline.frames.lastOrNull()?.time
    val freshness = classifyRainRadarFreshness(
        mode = state.mode,
        latestFrameTime = latestTime,
        refreshFailed = state.timeline.isStale && !state.timeline.errorMessage.isNullOrBlank(),
        nowEpochMillis = nowEpochMillis,
    )
    val colour = when (freshness.level) {
        RainRadarFreshnessLevel.TEST -> accent
        RainRadarFreshnessLevel.NORMAL -> Color.White.copy(alpha = 0.82f)
        RainRadarFreshnessLevel.DELAYED -> Color(0xFFFFC857)
        RainRadarFreshnessLevel.STALE -> Color(0xFFFF7676)
        RainRadarFreshnessLevel.UNKNOWN -> Color(0xFF9A9A9A)
    }
    val label = if (
        state.mode == RainRadarMode.LIVE &&
        state.timeline.status == RainResourceStatus.LOADING
    ) {
        "正在更新雷達…"
    } else {
        freshness.label
    }

    MetroGlassContextSurface(
        accent = colour,
        modifier = modifier,
    ) {
        Text(
            text = label,
            color = colour,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private const val FRESHNESS_TICK_MS = 30_000L
