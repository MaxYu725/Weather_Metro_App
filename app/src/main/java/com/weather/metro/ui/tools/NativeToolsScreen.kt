package com.weather.metro.ui.tools

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.weather.metro.ui.layout.metroSafeTop
import com.weather.metro.ui.map.HongKongBackdrop
import com.weather.metro.ui.map.HongKongMapAttribution
import com.weather.metro.ui.motion.MetroPressPreset
import com.weather.metro.ui.motion.metroPressMotion
import com.weather.metro.ui.rain.RAIN_TOOL_POLICY_TICK_MS
import com.weather.metro.ui.rain.RainForecastMapLibrePanel
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
import com.weather.metro.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

private const val DESTINATION_HOME = "home"
private const val DESTINATION_POINT = "point"
private const val DESTINATION_RADAR = "radar"
private const val DESTINATION_FORECAST = "forecast"
private const val DESTINATION_STORM = "storm"
private const val TOOLS_DESTINATION_TRANSITION_MS = 460
private const val TOOLS_REDUCED_TRANSITION_MS = 140

enum class NativeToolDestination(val route: String) {
    POINT(DESTINATION_POINT),
    RADAR(DESTINATION_RADAR),
    FORECAST(DESTINATION_FORECAST),
    STORM(DESTINATION_STORM),
}

internal fun toolsFullscreenReleaseDelayMs(reduceMotion: Boolean): Long =
    if (reduceMotion) TOOLS_REDUCED_TRANSITION_MS.toLong() else TOOLS_DESTINATION_TRANSITION_MS.toLong()

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
    entryDestination: NativeToolDestination? = null,
    onExitRequested: (() -> Unit)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var destination by rememberSaveable { mutableStateOf(entryDestination?.route ?: DESTINATION_HOME) }
    var selectedRadiusKm by rememberSaveable {
        mutableIntStateOf(rainState.pointRequest?.radiusKm ?: RainHostViewModel.DEFAULT_POINT_RADIUS_KM)
    }
    var homeIntroPlayed by rememberSaveable { mutableStateOf(false) }
    val homeListState = rememberLazyListState()
    val effectiveActive = toolHostIsActive(isActive, lifecycleResumed)
    val reduceMotion = LocalReduceMotion.current

    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        lifecycleResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(entryDestination) {
        entryDestination?.let { requested ->
            if (destination != requested.route) destination = requested.route
        }
    }

    fun cancelDestinationRequests() {
        when (destination) {
            DESTINATION_POINT -> onCancelPointRefresh()
            DESTINATION_RADAR -> onCancelRadarRequests()
            DESTINATION_FORECAST -> onCancelForecastRequests()
            DESTINATION_STORM -> onCancelStormRequests()
        }
    }

    fun closeDestination() {
        cancelDestinationRequests()
        if (entryDestination != null && onExitRequested != null) {
            onExitRequested()
        } else {
            destination = DESTINATION_HOME
        }
    }

    BackHandler(enabled = destination != DESTINATION_HOME) { closeDestination() }

    LaunchedEffect(isActive, destination, reduceMotion) {
        if (!isActive) return@LaunchedEffect
        if (destination == DESTINATION_HOME) delay(toolsFullscreenReleaseDelayMs(reduceMotion))
        onFullscreenChanged(destination != DESTINATION_HOME)
    }

    LaunchedEffect(
        effectiveActive,
        destination,
        rainState.location?.latitude,
        rainState.location?.longitude,
        rainState.pointForecast.status,
        radarState.timeline.status,
        rainState.forecast.status,
    ) {
        if (!effectiveActive) {
            cancelDestinationRequests()
            return@LaunchedEffect
        }
        when (destination) {
            DESTINATION_POINT -> if (rainState.location != null) onEnsurePointFresh(selectedRadiusKm)
            DESTINATION_RADAR -> if (radarState.timeline.status == RainResourceStatus.IDLE) onRefreshRadar()
            DESTINATION_FORECAST -> onEnsureForecastFresh()
        }
    }

    LaunchedEffect(
        effectiveActive,
        destination,
        selectedRadiusKm,
        rainState.location?.latitude,
        rainState.location?.longitude,
    ) {
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

    AnimatedContent(
        targetState = destination,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(tween(140)) togetherWith fadeOut(tween(110))
            } else {
                val duration = TOOLS_DESTINATION_TRANSITION_MS
                val transform = when (toolTransitionDirection(initialState, targetState)) {
                    1 -> (
                        fadeIn(tween(300, delayMillis = 45)) +
                            slideInHorizontally(tween(duration, easing = FastOutSlowInEasing)) { width -> width / 3 } +
                            scaleIn(tween(duration, easing = FastOutSlowInEasing), initialScale = 0.97f)
                        ) togetherWith (
                        fadeOut(tween(260)) +
                            slideOutHorizontally(tween(duration, easing = FastOutSlowInEasing)) { width -> -width / 4 } +
                            scaleOut(tween(duration), targetScale = 0.985f)
                        )
                    -1 -> (
                        fadeIn(tween(280, delayMillis = 35)) +
                            slideInHorizontally(tween(360, easing = FastOutSlowInEasing)) { width -> -width / 8 }
                        ) togetherWith fadeOut(tween(100))
                    else -> fadeIn(tween(300)) togetherWith fadeOut(tween(240))
                }
                transform.using(SizeTransform(clip = false))
            }
        },
        contentKey = { it },
        label = "tools destination",
        modifier = Modifier.fillMaxSize(),
    ) { targetDestination ->
        when (targetDestination) {
            DESTINATION_POINT -> PointToolScreen(
                pageColour = pageColour,
                rainState = rainState,
                selectedRadiusKm = selectedRadiusKm,
                onRadiusChange = { radiusKm ->
                    selectedRadiusKm = radiusKm
                    onRefreshPoint(radiusKm)
                },
                onRefresh = { onRefreshPoint(selectedRadiusKm) },
                onBack = ::closeDestination,
            )
            DESTINATION_RADAR -> RadarMapLibreToolScreen(
                pageColour = pageColour,
                radarState = radarState,
                isActive = effectiveActive && destination == targetDestination,
                onRefresh = onRefreshRadar,
                onSelectFrame = onSelectRadarFrame,
                onSelectRange = onSelectRadarRange,
                onSelectHeight = onSelectRadarHeight,
                onSelectMode = onSelectRadarMode,
                onOpacityChange = onRadarOpacityChange,
                onPlaybackSpeedChange = onRadarPlaybackSpeedChange,
                onJumpToLatest = onJumpRadarToLatest,
                onBack = ::closeDestination,
            )
            DESTINATION_FORECAST -> ForecastToolScreen(
                pageColour = pageColour,
                rainState = rainState,
                isActive = effectiveActive && destination == targetDestination,
                onRefresh = onRefreshForecast,
                onSelectFrame = onLoadForecastFrame,
                onBack = ::closeDestination,
            )
            DESTINATION_STORM -> StormLiveToolScreen(
                pageColour = pageColour,
                stormState = stormState,
                isActive = effectiveActive && destination == targetDestination,
                onRefresh = onRefreshStorm,
                onEnsureFresh = onEnsureStormFresh,
                onCancelRequests = onCancelStormRequests,
                onBack = ::closeDestination,
            )
            else -> ToolsHome(
                pageColour = pageColour,
                listState = homeListState,
                animateReveal = !homeIntroPlayed,
                onOpenPoint = { homeIntroPlayed = true; destination = DESTINATION_POINT },
                onOpenRadar = { homeIntroPlayed = true; destination = DESTINATION_RADAR },
                onOpenForecast = { homeIntroPlayed = true; destination = DESTINATION_FORECAST },
                onOpenStorm = { homeIntroPlayed = true; destination = DESTINATION_STORM },
            )
        }
    }
}

@Composable
private fun ToolsHome(
    pageColour: Color,
    listState: LazyListState,
    animateReveal: Boolean,
    onOpenPoint: () -> Unit,
    onOpenRadar: () -> Unit,
    onOpenForecast: () -> Unit,
    onOpenStorm: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().metroSafeTop(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { ToolHomeReveal(0, animateReveal) { MetroSectionLabel("rain") } }
        item {
            ToolHomeReveal(1, animateReveal) {
                ToolTile("native-point-rain", "定點降雨", "目前位置 · 附近雨勢", "降雨", pageColour, onClick = onOpenPoint)
            }
        }
        item {
            ToolHomeReveal(2, animateReveal) {
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
        }
        item { ToolHomeReveal(3, animateReveal) { MetroSectionLabel("storm") } }
        item {
            ToolHomeReveal(4, animateReveal) {
                ToolTile("native-storm", "熱帶氣旋", "HKO · CMA · JMA · CWA", "live", pageColour, onClick = onOpenStorm)
            }
        }
        item { ToolHomeReveal(5, animateReveal) { MetroSectionLabel("official links") } }
        item {
            ToolHomeReveal(6, animateReveal) {
                OfficialLink("香港天文台雷達圖像") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.hko.gov.hk/tc/wxinfo/radars/radar-range.htm".toUri()))
                }
            }
        }
        item {
            ToolHomeReveal(7, animateReveal) {
                OfficialLink("閃電位置資訊") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://maps.weather.gov.hk/llis/llis.htm".toUri()))
                }
            }
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
    Box(modifier = Modifier.fillMaxSize()) {
        HongKongBackdrop(modifier = Modifier.fillMaxSize())
        LazyColumn(
            modifier = Modifier.fillMaxSize().metroSafeTop(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 16.dp, bottom = 64.dp),
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
        HongKongMapAttribution(modifier = Modifier.align(Alignment.BottomEnd))
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
                .metroSafeTop()
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
        RainForecastMapLibrePanel(
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
        "‹ back",
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
    val interactionSource = remember { MutableInteractionSource() }
    MetroTile(
        seed = seed,
        background = background,
        modifier = modifier.metroPressMotion(interactionSource = interactionSource, preset = MetroPressPreset.Tile),
        onClick = onClick,
        interactionSource = interactionSource,
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
private fun ToolHomeReveal(index: Int, animate: Boolean, content: @Composable () -> Unit) {
    if (!animate) {
        Box(Modifier.fillMaxWidth()) { content() }
        return
    }
    val reduceMotion = LocalReduceMotion.current
    var visible by remember(index) { mutableStateOf(false) }
    LaunchedEffect(index, reduceMotion) {
        if (reduceMotion) {
            visible = true
        } else {
            visible = false
            delay(35L + index * 45L)
            visible = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) fadeIn(tween(120)) else fadeIn(tween(280)) + slideInVertically(tween(360)) { height -> height / 4 },
    ) {
        Box(Modifier.fillMaxWidth()) { content() }
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

internal fun toolTransitionDirection(initial: String, target: String): Int = when {
    initial == DESTINATION_HOME && target != DESTINATION_HOME -> 1
    initial != DESTINATION_HOME && target == DESTINATION_HOME -> -1
    else -> 0
}

internal fun productionForecastRenderer(): String = "maplibre"
