package com.weather.metro.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weather.metro.data.settings.PageColourSlot
import com.weather.metro.data.tools.RainRadarMode
import com.weather.metro.domain.WeatherLoadState
import com.weather.metro.ui.components.MetroProgress
import com.weather.metro.ui.layout.metroSafeTop
import com.weather.metro.ui.map.HongKongBackdrop
import com.weather.metro.ui.map.HongKongMapAttribution
import com.weather.metro.ui.rain.RainHostViewModel
import com.weather.metro.ui.rain.RainLocationTrendViewModel
import com.weather.metro.ui.rain.RainRadarHostViewModel
import com.weather.metro.ui.rain.RainResourceStatus
import com.weather.metro.ui.screens.ForecastScreen
import com.weather.metro.ui.screens.HomeCurrentScreen
import com.weather.metro.ui.screens.SettingsScreen
import com.weather.metro.ui.storm.StormHostViewModel
import com.weather.metro.ui.theme.LocalMetroSubText
import com.weather.metro.ui.theme.LocalReduceMotion
import com.weather.metro.ui.theme.MetroPageTheme
import com.weather.metro.ui.theme.WeatherMetroTheme
import com.weather.metro.ui.theme.argbColor
import com.weather.metro.ui.tools.NativeToolDestination
import com.weather.metro.ui.tools.NativeToolsScreen

private val pages = listOf(
    PageColourSlot.CURRENT,
    PageColourSlot.FORECAST,
    PageColourSlot.SETTINGS,
)

internal fun pageRequiresWeatherData(page: PageColourSlot): Boolean =
    page == PageColourSlot.CURRENT || page == PageColourSlot.FORECAST

internal fun locationTrendMayRun(
    page: PageColourSlot,
    hasActiveTool: Boolean,
    hasLocation: Boolean,
    pointStatus: RainResourceStatus,
): Boolean =
    page == PageColourSlot.CURRENT &&
        !hasActiveTool &&
        hasLocation &&
        (pointStatus == RainResourceStatus.READY || pointStatus == RainResourceStatus.ERROR)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WeatherMetroRoot(
    viewModel: WeatherViewModel,
    rainViewModel: RainHostViewModel,
    requestLocationPermission: () -> Unit,
    requestNotificationPermission: () -> Unit,
    openNotificationSettings: () -> Unit,
) {
    val locationTrendViewModel: RainLocationTrendViewModel = viewModel()
    val radarViewModel: RainRadarHostViewModel = viewModel()
    val stormViewModel: StormHostViewModel = viewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val notificationDiagnostics by viewModel.notificationDiagnostics.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val toolLocation by viewModel.toolLocation.collectAsStateWithLifecycle()
    val navigationRequest by viewModel.navigationRequest.collectAsStateWithLifecycle()
    val rainState by rainViewModel.state.collectAsStateWithLifecycle()
    val locationTrendState by locationTrendViewModel.state.collectAsStateWithLifecycle()
    val radarState by radarViewModel.state.collectAsStateWithLifecycle()
    val stormState by stormViewModel.state.collectAsStateWithLifecycle()
    val productionRadarState = radarState.copy(
        contract = radarState.contract.copy(
            value = radarState.contract.value?.let { contract ->
                contract.copy(
                    modes = contract.modes.filter { it == RainRadarMode.LIVE.wireValue },
                )
            },
        ),
    )

    LaunchedEffect(toolLocation) {
        toolLocation?.let { location ->
            rainViewModel.bindHostLocation(location)
            locationTrendViewModel.bindHostLocation(location)
            radarViewModel.bindHostLocation(location)
            stormViewModel.bindHostLocation(location)
        }
    }

    LaunchedEffect(Unit) {
        if (settings.preciseLocation && !viewModel.hasLocationPermission()) {
            requestLocationPermission()
        } else {
            requestNotificationPermission()
        }
    }

    WeatherMetroTheme(settings) {
        val alignedInitialPage = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % pages.size)
        val pagerState = rememberPagerState(initialPage = alignedInitialPage) { Int.MAX_VALUE }
        val pageIndex = pagerState.currentPage.mod(pages.size)
        val activePage = pages[pageIndex]
        val activePageColour = argbColor(settings.pageColours.colour(activePage))
        val toolsColour = argbColor(settings.pageColours.colour(PageColourSlot.TOOLS))
        val reduceMotion = LocalReduceMotion.current
        var activeTool by remember { mutableStateOf<NativeToolDestination?>(null) }
        val pagerFlingBehavior = PagerDefaults.flingBehavior(
            state = pagerState,
            snapAnimationSpec = tween(durationMillis = if (reduceMotion) 180 else 520),
        )

        LaunchedEffect(pageIndex) {
            if (activePage == PageColourSlot.SETTINGS) viewModel.refreshNotificationDiagnostics()
        }

        LaunchedEffect(
            activePage,
            activeTool,
            rainState.location?.latitude,
            rainState.location?.longitude,
            rainState.pointForecast.status,
        ) {
            val location = rainState.location
            val currentIsActive = activePage == PageColourSlot.CURRENT && activeTool == null && location != null
            if (!currentIsActive) {
                locationTrendViewModel.cancelRefresh()
                return@LaunchedEffect
            }

            locationTrendViewModel.bindHostLocation(location)
            rainViewModel.refreshPointForecastIfStale()
            val fastPathStatus = rainViewModel.state.value.pointForecast.status
            if (
                locationTrendMayRun(
                    page = activePage,
                    hasActiveTool = activeTool != null,
                    hasLocation = true,
                    pointStatus = fastPathStatus,
                )
            ) {
                locationTrendViewModel.refreshIfNeeded()
            } else {
                locationTrendViewModel.cancelRefresh()
            }
        }

        LaunchedEffect(navigationRequest?.token) {
            val request = navigationRequest ?: return@LaunchedEffect
            val destinationIndex = pages.indexOf(request.page)
            if (destinationIndex < 0) {
                viewModel.consumeNavigation(request.token)
                return@LaunchedEffect
            }
            val currentIndex = pagerState.currentPage.mod(pages.size)
            var delta = destinationIndex - currentIndex
            if (delta > pages.size / 2) delta -= pages.size
            if (delta < -pages.size / 2) delta += pages.size
            val destinationPage = pagerState.currentPage + delta
            pagerState.animateScrollToPage(
                destinationPage,
                animationSpec = tween(if (reduceMotion) 180 else 520),
            )
            if (!request.showAlerts) viewModel.consumeNavigation(request.token)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF080B0D)),
        ) {
            HongKongBackdrop(Modifier.fillMaxSize())
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = activeTool == null,
                    enter = if (reduceMotion) {
                        fadeIn(tween(120))
                    } else {
                        fadeIn(tween(220, delayMillis = 80)) + expandVertically(tween(360))
                    },
                    exit = if (reduceMotion) {
                        fadeOut(tween(100))
                    } else {
                        fadeOut(tween(180)) + shrinkVertically(tween(360))
                    },
                ) {
                    val showWeatherProgress = pageRequiresWeatherData(activePage) && (
                        loadState is WeatherLoadState.Loading ||
                            (loadState as? WeatherLoadState.Ready)?.refreshing == true
                        )
                    Column {
                        if (showWeatherProgress) {
                            MetroProgress(colour = activePageColour)
                        } else {
                            Spacer(Modifier.height(3.dp))
                        }
                        PivotHeader(
                            current = activePage.label,
                            next = pages[(pageIndex + 1) % pages.size].label,
                            accent = activePageColour,
                            reduceMotion = reduceMotion,
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    key = { it },
                    flingBehavior = pagerFlingBehavior,
                    userScrollEnabled = activeTool == null,
                    modifier = Modifier.fillMaxSize(),
                ) { virtualPage ->
                    val index = virtualPage.mod(pages.size)
                    val page = pages[index]
                    val pageColour = argbColor(settings.pageColours.colour(page))
                    MetroPageTheme(pageColour) {
                        when (page) {
                            PageColourSlot.TOOLS -> Unit
                            PageColourSlot.SETTINGS -> SettingsScreen(
                                settings = settings,
                                notificationDiagnostics = notificationDiagnostics,
                                pageColour = pageColour,
                                onPageColourChange = viewModel::setPageColour,
                                onTextScaleChange = viewModel::setTextScale,
                                onReduceMotionChange = viewModel::setReduceMotion,
                                onHighContrastChange = viewModel::setHighContrast,
                                onPreciseLocationChange = viewModel::setPreciseLocation,
                                onNotificationsChange = { enabled ->
                                    viewModel.setNotificationsEnabled(enabled)
                                    if (enabled) requestNotificationPermission()
                                },
                                onLocationHeavyRainNotificationsChange = viewModel::setLocationHeavyRainNotificationsEnabled,
                                onPersonalizedRainNotificationsChange = viewModel::setPersonalizedRainNotificationsEnabled,
                                onRefreshNotificationDiagnostics = viewModel::refreshNotificationDiagnostics,
                                onOpenNotificationSettings = openNotificationSettings,
                                onClearCache = {
                                    viewModel.clearCache()
                                    rainViewModel.clearCache()
                                    locationTrendViewModel.cancelRefresh()
                                    radarViewModel.clearTransientCache()
                                    stormViewModel.clearCache()
                                },
                            )
                            PageColourSlot.CURRENT,
                            PageColourSlot.FORECAST,
                            -> when (val state = loadState) {
                                WeatherLoadState.Loading -> LoadingPage()
                                is WeatherLoadState.Error -> ErrorPage(message = state.message, retry = viewModel::refresh)
                                is WeatherLoadState.Ready -> if (page == PageColourSlot.CURRENT) {
                                    HomeCurrentScreen(
                                        snapshot = state.snapshot,
                                        rainState = rainState,
                                        locationTrendState = locationTrendState,
                                        stormState = stormState,
                                        pageColour = pageColour,
                                        refreshing = state.refreshing,
                                        onRefresh = viewModel::refresh,
                                        onRequestLocation = requestLocationPermission,
                                        onOpenPointRain = { activeTool = NativeToolDestination.POINT },
                                        onOpenRadar = { activeTool = NativeToolDestination.RADAR },
                                        onOpenForecastMap = {
                                            locationTrendViewModel.cancelRefresh()
                                            activeTool = NativeToolDestination.FORECAST
                                        },
                                        onOpenStorm = { activeTool = NativeToolDestination.STORM },
                                        navigationRequest = navigationRequest?.takeIf {
                                            it.page == PageColourSlot.CURRENT && it.showAlerts
                                        },
                                        onNavigationHandled = viewModel::consumeNavigation,
                                    )
                                } else {
                                    ForecastScreen(state.snapshot, pageColour)
                                }
                            }
                        }
                    }
                }
            }

            activeTool?.let { destination ->
                MetroPageTheme(toolsColour) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF080B0D)),
                    ) {
                        NativeToolsScreen(
                            pageColour = toolsColour,
                            rainState = rainState,
                            radarState = productionRadarState,
                            stormState = stormState,
                            isActive = true,
                            onFullscreenChanged = {},
                            onRefreshPoint = rainViewModel::refreshPointForecast,
                            onEnsurePointFresh = rainViewModel::refreshPointForecastIfStale,
                            onCancelPointRefresh = rainViewModel::cancelPointRefresh,
                            onRefreshRadar = radarViewModel::refreshRadar,
                            onSelectRadarFrame = radarViewModel::selectFrame,
                            onSelectRadarRange = radarViewModel::selectRange,
                            onSelectRadarHeight = radarViewModel::selectHeight,
                            onSelectRadarMode = radarViewModel::selectMode,
                            onRadarOpacityChange = radarViewModel::setOpacity,
                            onRadarPlaybackSpeedChange = radarViewModel::setPlaybackSpeed,
                            onJumpRadarToLatest = radarViewModel::jumpToLatest,
                            onCancelRadarRequests = radarViewModel::cancelRequests,
                            onRefreshForecast = rainViewModel::refreshForecast,
                            onEnsureForecastFresh = rainViewModel::refreshForecastIfStale,
                            onLoadForecastFrame = rainViewModel::loadForecastFrame,
                            onCancelForecastRequests = rainViewModel::cancelForecastRequests,
                            onRefreshStorm = stormViewModel::refreshLive,
                            onEnsureStormFresh = { stormViewModel.refreshLiveIfStale() },
                            onCancelStormRequests = stormViewModel::cancelRequests,
                            entryDestination = destination,
                            onExitRequested = { activeTool = null },
                        )
                    }
                }
            }

            if (activeTool == null) {
                HongKongMapAttribution(modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

@Composable
private fun PivotHeader(current: String, next: String, accent: Color, reduceMotion: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .metroSafeTop()
            .height(68.dp)
            .padding(start = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedContent(
            targetState = current to next,
            transitionSpec = {
                if (reduceMotion) fadeIn(tween(120)) togetherWith fadeOut(tween(100))
                else fadeIn(tween(420)) togetherWith fadeOut(tween(320))
            },
            label = "pivot header",
        ) { (active, upcoming) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(30.dp)
                        .background(accent, RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = active,
                    color = Color.White,
                    fontSize = 48.sp,
                    lineHeight = 52.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-1.3).sp,
                    maxLines = 1,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = upcoming,
                    color = Color(0xFF3D3D3D),
                    fontSize = 44.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun LoadingPage() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(220.dp)) { MetroProgress() }
            Spacer(Modifier.height(14.dp))
            Text(
                "正在取得香港天文台資料…",
                color = LocalMetroSubText.current,
                fontSize = 17.sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}

@Composable
private fun ErrorPage(message: String, retry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.TopStart) {
        Column {
            Text("資料暫時無法更新", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(10.dp))
            Text(message, color = LocalMetroSubText.current)
            Spacer(Modifier.height(18.dp))
            Text(
                "retry",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 20.sp,
                modifier = Modifier.clickable(onClick = retry).padding(vertical = 12.dp),
            )
        }
    }
}
