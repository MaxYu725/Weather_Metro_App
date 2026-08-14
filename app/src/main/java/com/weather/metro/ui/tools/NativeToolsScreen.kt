package com.weather.metro.ui.tools

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.weather.metro.data.tools.RainRadarMode
import com.weather.metro.ui.components.MetroSectionLabel
import com.weather.metro.ui.components.MetroTile
import com.weather.metro.ui.rain.RAIN_TOOL_POLICY_TICK_MS
import com.weather.metro.ui.rain.RainForecastPanel
import com.weather.metro.ui.rain.RainHostState
import com.weather.metro.ui.rain.RainHostViewModel
import com.weather.metro.ui.rain.RainPointPanel
import com.weather.metro.ui.rain.RainRadarHostState
import com.weather.metro.ui.rain.RainRadarMapLibrePanel
import com.weather.metro.ui.rain.RainRadarPlaybackSpeed
import com.weather.metro.ui.rain.RainRadarProductionStatus
import com.weather.metro.ui.rain.RainResourceStatus
import com.weather.metro.ui.storm.STORM_FOREGROUND_POLICY_TICK_MS
import com.weather.metro.ui.storm.StormHostState
import com.weather.metro.ui.storm.StormLivePanel
import com.weather.metro.ui.theme.LocalMetroSubText
import kotlinx.coroutines.delay

private const val DESTINATION_HOME = "home"
private const val DESTINATION_POINT = "point"
private const val DESTINATION_RADAR = "radar"
private const val DESTINATION_FORECAST = "forecast"
private const val DESTINATION_STORM = "storm"

@Composable
fun NativeToolsScreen(
    pageColour: Color,
    rainState: RainHostState,
    radarState: RainRadarHostState,
    stormState: StormHostState,
    isActive: Boolean,
    onFullscreenChanged: (Boolean) -> Unit,
    onRefreshPoint: (Int) -> Unit,
    onEnsurePointFresh: (Int) -> Unit,
    onCancelPointRefresh: () -> Unit,
    onRefreshRadar: () -> Unit,
    onSelectRadarFrame: (Int) -> Unit,
    onSelectRadarRange: (Int) -> Unit,
    onSelectRadarHeight: (Int) -> Unit,
    onSelectRadarMode: (RainRadarMode) -> Unit,
    onRadarOpacityChange: (Float) -> Unit,
    onRadarPlaybackSpeedChange: (RainRadarPlaybackSpeed) -> Unit,
    onJumpRadarToLatest: () -> Unit,
    onCancelRadarRequests: () -> Unit,
    onRefreshForecast: () -> Unit,
    onEnsureForecastFresh: () -> Unit,
    onLoadForecastFrame: (Int) -> Unit,
    onCancelForecastRequests: () -> Unit,
    onRefreshStorm: () -> Unit,
    onEnsureStormFresh: () -> Unit,
    onCancelStormRequests: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var destination by rememberSaveable { mutableStateOf(DESTINATION_HOME) }
    var selectedRadiusKm by rememberSaveable {
        mutableIntStateOf(rainState.pointRequest?.radiusKm ?: RainHostViewModel.DEFAULT_POINT_RADIUS_KM)
    }
    val effectiveActive = toolHostIsActive(isActive, lifecycleResumed)

    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        lifecycleResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun cancelDestinationRequests() {
        when (destination) {
            DESTINATION_POINT -> onCancelPointRefresh()
            DESTINATION_RADAR -> onCancelRadarRequests()
            DESTINATION_FORECAST -> onCancelForecastRequests()
            DESTINATION_STORM -> onCancelStormRequests()
        }
    }

    BackHandler(enabled = destination != DESTINATION_HOME) {
        cancelDestinationRequests()
        destination = DESTINATION_HOME
    }

    LaunchedEffect(isActive, destination) {
        if (isActive) onFullscreenChanged(destination != DESTINATION_HOME)
    }

    LaunchedEffect(
        effectiveActive,
        destination,
        rainState.pointForecast.status,
        radarState.timeline.status,
        rainState.forecast.status,
    ) {
        if (!effectiveActive) {
            cancelDestinationRequests()
            return@LaunchedEffect
        }
        when (destination) {
            DESTINATION_POINT -> if (rainState.location != null) {
                onEnsurePointFresh(selectedRadiusKm)
            }
            DESTINATION_RADAR -> if (radarState.timeline.status == RainResourceStatus.IDLE) {
                onRefreshRadar()
            }
            DESTINATION_FORECAST -> onEnsureForecastFresh()
        }
    }

    LaunchedEffect(effectiveActive, destination, selectedRadiusKm) {
        if (!effectiveActive) return@LaunchedEffect
        if (destination != DESTINATION_POINT && destination != DESTINATION_FORECAST) return@LaunchedEffect
        while (true) {
            delay(RAIN_TOOL_POLICY_TICK_MS)
            when (destination) {
                DESTINATION_POINT -> if (rainState.location != null) onEnsurePointFresh(selectedRadiusKm)
                DESTINATION_FORECAST -> onEnsureForecastFresh()
            }
        }
    }

    when (destination) {
        DESTINATION_POINT -> PointToolScreen(
            pageColour = pageColour,
            rainState = rainState,
            selectedRadiusKm = selectedRadiusKm,
            onRadiusChange = { radiusKm ->
                selectedRadiusKm = radiusKm
                onRefreshPoint(radiusKm)
            },
            onRefresh = { onRefreshPoint(selectedRadiusKm) },
            onBack = {
                onCancelPointRefresh()
                destination = DESTINATION_HOME
            },
        )
        DESTINATION_RADAR -> RadarMapLibreToolScreen(
            pageColour = pageColour,
            radarState = radarState,
            isActive = effectiveActive,
            onRefresh = onRefreshRadar,
            onSelectFrame = onSelectRadarFrame,
            onSelectRange = onSelectRadarRange,
            onSelectHeight = onSelectRadarHeight,
            onSelectMode = onSelectRadarMode,
            onOpacityChange = onRadarOpacityChange,
            onPlaybackSpeedChange = onRadarPlaybackSpeedChange,
            onJumpToLatest = onJumpRadarToLatest,
            onBack = {
                onCancelRadarRequests()
                destination = DESTINATION_HOME
            },
        )
        DESTINATION_FORECAST -> ForecastToolScreen(
            pageColour = pageColour,
            rainState = rainState,
            isActive = effectiveActive,
            onRefresh = onRefreshForecast,
            onSelectFrame = onLoadForecastFrame,
            onBack = {
                onCancelForecastRequests()
                destination = DESTINATION_HOME
            },
        )
        DESTINATION_STORM -> StormLiveToolScreen(
            pageColour = pageColour,
            stormState = stormState,
            isActive = effectiveActive,
            onRefresh = onRefreshStorm,
            onEnsureFresh = onEnsureStormFresh,
            onCancelRequests = onCancelStormRequests,
            onBack = {
                onCancelStormRequests()
                destination = DESTINATION_HOME
            },
        )
        else -> ToolsHome(
            pageColour = pageColour,
            onOpenPoint = { destination = DESTINATION_POINT },
            onOpenRadar = { destination = DESTINATION_RADAR },
            onOpenForecast = { destination = DESTINATION_FORECAST },
            onOpenStorm = { destination = DESTINATION_STORM },
        )
    }
}

@Composable
private fun ToolsHome(
    pageColour: Color,
    onOpenPoint: () -> Unit,
    onOpenRadar: () -> Unit,
    onOpenForecast: () -> Unit,
    onOpenStorm: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { MetroSectionLabel("rain") }
        item {
            ToolTile(
                seed = "native-point-rain",
                title = "定點降雨",
                description = "目前位置 · 附近雨勢",
                status = "降雨",
                background = pageColour,
                onClick = onOpenPoint,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ToolTile(
                    seed = "native-radar",
                    title = "雷達",
                    description = "即時觀測 · 64 / 256 km",
                    status = "觀測",
                    background = pageColour,
                    modifier = Modifier.weight(1f).height(132.dp),
                    onClick = onOpenRadar,
                )
                ToolTile(
                    seed = "native-forecast-map",
                    title = "2小時預報",
                    description = "未來兩小時 · 6分鐘步進",
                    status = "預報",
                    background = pageColour,
                    modifier = Modifier.weight(1f).height(132.dp),
                    onClick = onOpenForecast,
                )
            }
        }

        item { MetroSectionLabel("storm") }
        item {
            ToolTile(
                seed = "native-storm",
                title = "熱帶氣旋",
                description = "HKO · CMA · JMA · CWA",
                status = "live",
                background = pageColour,
                onClick = onOpenStorm,
            )
        }

        item { MetroSectionLabel("official links") }
        item {
            OfficialLink(
                title = "香港天文台雷達圖像",
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://www.hko.gov.hk/tc/wxinfo/radars/radar-range.htm".toUri()),
                    )
                },
            )
        }
        item {
            OfficialLink(
                title = "閃電位置資訊",
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://maps.weather.gov.hk/llis/llis.htm".toUri()),
                    )
                },
            )
        }
    }
}

@Composable
private fun PointToolScreen(
    pageColour: Color,
    rainState: RainHostState,
    selectedRadiusKm: Int,
    onRadiusChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { ToolBackButton(pageColour, onBack) }
        item {
            RainPointPanel(
                state = rainState,
                pageColour = pageColour,
                selectedRadiusKm = selectedRadiusKm,
                onRadiusChange = onRadiusChange,
                onRefresh = onRefresh,
            )
        }
    }
}

@Composable
private fun RadarMapLibreToolScreen(
    pageColour: Color,
    radarState: RainRadarHostState,
    isActive: Boolean,
    onRefresh: () -> Unit,
    onSelectFrame: (Int) -> Unit,
    onSelectRange: (Int) -> Unit,
    onSelectHeight: (Int) -> Unit,
    onSelectMode: (RainRadarMode) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onPlaybackSpeedChange: (RainRadarPlaybackSpeed) -> Unit,
    onJumpToLatest: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        RainRadarMapLibrePanel(
            state = radarState,
            pageColour = pageColour,
            isActive = isActive,
            onRefresh = onRefresh,
            onSelectFrame = onSelectFrame,
            onSelectRange = onSelectRange,
            onSelectHeight = onSelectHeight,
            onSelectMode = onSelectMode,
            onOpacityChange = onOpacityChange,
            onPlaybackSpeedChange = onPlaybackSpeedChange,
            onJumpToLatest = onJumpToLatest,
            onBack = onBack,
            modifier = Modifier.fillMaxSize(),
        )
        RainRadarProductionStatus(
            state = radarState,
            isActive = isActive,
            accent = pageColour,
            onAutoRefresh = onRefresh,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp),
        )
    }
}

@Composable
private fun ForecastToolScreen(
    pageColour: Color,
    rainState: RainHostState,
    isActive: Boolean,
    onRefresh: () -> Unit,
    onSelectFrame: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        RainForecastPanel(
            state = rainState,
            pageColour = pageColour,
            isActive = isActive,
            onRefresh = onRefresh,
            onSelectFrame = onSelectFrame,
            onBack = onBack,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun StormLiveToolScreen(
    pageColour: Color,
    stormState: StormHostState,
    isActive: Boolean,
    onRefresh: () -> Unit,
    onEnsureFresh: () -> Unit,
    onCancelRequests: () -> Unit,
    onBack: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, isActive, stormState.cacheRestored) {
        if (!isActive || !stormState.cacheRestored) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                onEnsureFresh()
                delay(STORM_FOREGROUND_POLICY_TICK_MS)
            }
        }
    }

    StormLivePanel(
        state = stormState,
        pageColour = pageColour,
        isActive = isActive,
        onRefresh = onRefresh,
        onBack = onBack,
        onCancelRequests = onCancelRequests,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun ToolBackButton(pageColour: Color, onBack: () -> Unit) {
    Text(
        "‹ tools",
        color = pageColour,
        fontSize = 16.sp,
        modifier = Modifier
            .clickable(onClick = onBack)
            .padding(start = 0.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun ToolTile(
    seed: String,
    title: String,
    description: String,
    status: String,
    background: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(142.dp),
    onClick: (() -> Unit)? = null,
) {
    MetroTile(
        seed = seed,
        background = background,
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(status, color = Color.White.copy(alpha = 0.68f), fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Light)
            Text(description, color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun OfficialLink(title: String, onClick: () -> Unit) {
    Text(
        text = "$title  ↗",
        color = LocalMetroSubText.current,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

internal fun toolHostIsActive(pageActive: Boolean, lifecycleResumed: Boolean): Boolean =
    pageActive && lifecycleResumed
