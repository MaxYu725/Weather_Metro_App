package com.weather.metro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.weather.metro.data.settings.PageColourSlot
import com.weather.metro.data.settings.UiSettings
import com.weather.metro.domain.WeatherLoadState
import com.weather.metro.notification.PersonalizedNotificationDiagnostics
import com.weather.metro.ui.components.MetroGlassContextSurface
import com.weather.metro.ui.components.MetroProgress
import com.weather.metro.ui.map.HongKongBackdrop
import com.weather.metro.ui.map.HongKongMapAttribution
import com.weather.metro.ui.rain.RainHostState
import com.weather.metro.ui.rain.RainHostViewModel
import com.weather.metro.ui.rain.RainLocationTrendState
import com.weather.metro.ui.rain.RainLocationTrendViewModel
import com.weather.metro.ui.rain.RainRadarHostState
import com.weather.metro.ui.rain.RainRadarHostViewModel
import com.weather.metro.ui.screens.FoldableSettingsScreen
import com.weather.metro.ui.screens.ForecastScreen
import com.weather.metro.ui.screens.HomeCurrentScreen
import com.weather.metro.ui.storm.StormHostState
import com.weather.metro.ui.storm.StormHostViewModel
import com.weather.metro.ui.theme.LocalMetroSubText
import com.weather.metro.ui.theme.MetroPageTheme
import com.weather.metro.ui.theme.argbColor
import com.weather.metro.ui.tools.NativeToolDestination
import com.weather.metro.ui.tools.NativeToolsScreen

internal const val FOLDABLE_DUAL_PANE_MIN_WIDTH_DP = 700

/**
 * Unfolded/tablet host.
 *
 * Weather mode is always a true 50/50 split: Current stays mounted on the left,
 * while the right pane switches between Forecast and Live Weather. Settings is
 * a separate 35/65 master-detail surface.
 */
@Composable
internal fun FoldableWeatherLayout(
    settings: UiSettings,
    notificationDiagnostics: PersonalizedNotificationDiagnostics,
    loadState: WeatherLoadState,
    navigationRequest: AppNavigationRequest?,
    rainState: RainHostState,
    locationTrendState: RainLocationTrendState,
    radarState: RainRadarHostState,
    stormState: StormHostState,
    viewModel: WeatherViewModel,
    rainViewModel: RainHostViewModel,
    locationTrendViewModel: RainLocationTrendViewModel,
    radarViewModel: RainRadarHostViewModel,
    stormViewModel: StormHostViewModel,
    requestLocationPermission: () -> Unit,
    requestNotificationPermission: () -> Unit,
    openNotificationSettings: () -> Unit,
) {
    val currentColour = argbColor(settings.pageColours.colour(PageColourSlot.CURRENT))
    val forecastColour = argbColor(settings.pageColours.colour(PageColourSlot.FORECAST))
    val toolsColour = argbColor(settings.pageColours.colour(PageColourSlot.TOOLS))
    val settingsColour = argbColor(settings.pageColours.colour(PageColourSlot.SETTINGS))

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLiveWeather by rememberSaveable { mutableStateOf(false) }
    var requestedToolRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedToolToken by rememberSaveable { mutableIntStateOf(0) }
    val requestedTool = NativeToolDestination.entries.firstOrNull { it.route == requestedToolRoute }

    val productionRadarState = radarState.copy(
        contract = radarState.contract.copy(
            value = radarState.contract.value?.let { contract ->
                contract.copy(
                    modes = contract.modes.filter { it == com.weather.metro.data.tools.RainRadarMode.LIVE.wireValue },
                )
            },
        ),
    )

    fun stopLiveWeatherRequests() {
        rainViewModel.cancelPointRefresh()
        radarViewModel.cancelRequests()
        rainViewModel.cancelForecastRequests()
        stormViewModel.cancelRequests()
    }

    fun openLiveWeather(destination: NativeToolDestination? = null) {
        requestedToolRoute = destination?.route
        requestedToolToken += 1
        showLiveWeather = true
        showSettings = false
    }

    fun showForecastPane() {
        if (showLiveWeather) stopLiveWeatherRequests()
        showLiveWeather = false
        requestedToolRoute = null
    }

    fun openSettingsPane() {
        if (showLiveWeather) stopLiveWeatherRequests()
        showSettings = true
        showLiveWeather = false
        requestedToolRoute = null
    }

    BackHandler(enabled = showSettings || showLiveWeather) {
        if (showSettings) {
            showSettings = false
        } else {
            showForecastPane()
        }
    }

    LaunchedEffect(showSettings) {
        if (showSettings) viewModel.refreshNotificationDiagnostics()
    }

    LaunchedEffect(
        showSettings,
        rainState.location?.latitude,
        rainState.location?.longitude,
        rainState.pointForecast.status,
    ) {
        val location = rainState.location
        if (showSettings || location == null) {
            locationTrendViewModel.cancelRefresh()
            return@LaunchedEffect
        }

        locationTrendViewModel.bindHostLocation(location)
        rainViewModel.refreshPointForecastIfStale()
        val fastPathStatus = rainViewModel.state.value.pointForecast.status
        if (
            locationTrendMayRun(
                page = PageColourSlot.CURRENT,
                hasActiveTool = false,
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
        when (request.page) {
            PageColourSlot.SETTINGS -> {
                openSettingsPane()
                if (!request.showAlerts) viewModel.consumeNavigation(request.token)
            }
            PageColourSlot.CURRENT -> {
                showSettings = false
                if (!request.showAlerts) viewModel.consumeNavigation(request.token)
            }
            PageColourSlot.FORECAST -> {
                showSettings = false
                showForecastPane()
                viewModel.consumeNavigation(request.token)
            }
            PageColourSlot.TOOLS -> {
                openLiveWeather()
                viewModel.consumeNavigation(request.token)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080B0D)),
    ) {
        HongKongBackdrop(Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            FoldablePrimaryDataStatus(loadState)

            if (showSettings) {
                MetroPageTheme(settingsColour) {
                    FoldableSettingsScreen(
                        settings = settings,
                        notificationDiagnostics = notificationDiagnostics,
                        pageColour = settingsColour,
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
                        onBackToWeather = { showSettings = false },
                    )
                }
            } else {
                FoldableWeatherHeader(
                    showLiveWeather = showLiveWeather,
                    currentColour = currentColour,
                    onShowForecast = ::showForecastPane,
                    onShowLiveWeather = { openLiveWeather() },
                    onOpenSettings = ::openSettingsPane,
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        MetroPageTheme(currentColour) {
                            FoldableCurrentPane(
                                loadState = loadState,
                                rainState = rainState,
                                locationTrendState = locationTrendState,
                                stormState = stormState,
                                pageColour = currentColour,
                                requestLocationPermission = requestLocationPermission,
                                onOpenPointRain = { openLiveWeather(NativeToolDestination.POINT) },
                                onOpenRadar = { openLiveWeather(NativeToolDestination.RADAR) },
                                onOpenForecastMap = { openLiveWeather(NativeToolDestination.FORECAST) },
                                onOpenStorm = { openLiveWeather(NativeToolDestination.STORM) },
                                navigationRequest = navigationRequest,
                                onNavigationHandled = viewModel::consumeNavigation,
                                onRetry = viewModel::refresh,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.10f)),
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        if (showLiveWeather) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .consumeWindowInsets(WindowInsets.displayCutout.only(WindowInsetsSides.Top)),
                            ) {
                                MetroPageTheme(toolsColour) {
                                    key(requestedToolToken) {
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
                                            entryDestination = requestedTool,
                                            onExitRequested = null,
                                        )
                                    }
                                }
                            }
                        } else {
                            MetroPageTheme(forecastColour) {
                                when (val state = loadState) {
                                    WeatherLoadState.Loading -> FoldableLoadingPage()
                                    is WeatherLoadState.Error -> FoldableErrorPage(state.message, viewModel::refresh)
                                    is WeatherLoadState.Ready -> ForecastScreen(state.snapshot, forecastColour)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!showSettings && !showLiveWeather) {
            HongKongMapAttribution(modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun FoldableCurrentPane(
    loadState: WeatherLoadState,
    rainState: RainHostState,
    locationTrendState: RainLocationTrendState,
    stormState: StormHostState,
    pageColour: Color,
    requestLocationPermission: () -> Unit,
    onOpenPointRain: () -> Unit,
    onOpenRadar: () -> Unit,
    onOpenForecastMap: () -> Unit,
    onOpenStorm: () -> Unit,
    navigationRequest: AppNavigationRequest?,
    onNavigationHandled: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    when (val state = loadState) {
        WeatherLoadState.Loading -> FoldableLoadingPage()
        is WeatherLoadState.Error -> FoldableErrorPage(state.message, onRetry)
        is WeatherLoadState.Ready -> HomeCurrentScreen(
            snapshot = state.snapshot,
            rainState = rainState,
            locationTrendState = locationTrendState,
            stormState = stormState,
            pageColour = pageColour,
            onRequestLocation = requestLocationPermission,
            onOpenPointRain = onOpenPointRain,
            onOpenRadar = onOpenRadar,
            onOpenForecastMap = onOpenForecastMap,
            onOpenStorm = onOpenStorm,
            navigationRequest = navigationRequest?.takeIf {
                it.page == PageColourSlot.CURRENT && it.showAlerts
            },
            onNavigationHandled = onNavigationHandled,
        )
    }
}

@Composable
private fun FoldableWeatherHeader(
    showLiveWeather: Boolean,
    currentColour: Color,
    onShowForecast: () -> Unit,
    onShowLiveWeather: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 22.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "current",
                color = Color.White,
                fontSize = 42.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1.2).sp,
            )
            Spacer(Modifier.weight(1f))
            MetroGlassContextSurface(
                accent = currentColour,
                modifier = Modifier.clickable(onClick = onOpenSettings),
            ) {
                Text(
                    "settings",
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.10f)),
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 22.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val activeLabel = if (showLiveWeather) "live weather" else "forecast"
            val nextLabel = if (showLiveWeather) "forecast" else "live weather"
            val onNext = if (showLiveWeather) onShowForecast else onShowLiveWeather

            Text(
                text = activeLabel,
                color = Color.White,
                fontSize = 42.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1.2).sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(18.dp))
            Text(
                text = nextLabel,
                color = Color(0xFF3D3D3D),
                fontSize = 37.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onNext)
                    .padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun FoldablePrimaryDataStatus(state: WeatherLoadState) {
    val statusText: String
    val statusColour: Color
    when (state) {
        WeatherLoadState.Loading -> {
            statusText = "正在取得香港天文台資料"
            statusColour = Color(0xFF8A8A8A)
        }
        is WeatherLoadState.Error -> {
            statusText = if (state.cached != null) "資料更新失敗 · 顯示快取" else "香港天文台資料暫時無法更新"
            statusColour = if (state.cached != null) Color(0xFFF09609) else Color(0xFFE51400)
        }
        is WeatherLoadState.Ready -> when {
            state.refreshing -> {
                statusText = "正在更新香港天文台資料"
                statusColour = Color(0xFF8A8A8A)
            }
            state.snapshot.isStale -> {
                statusText = "顯示離線快取"
                statusColour = Color(0xFFF09609)
            }
            else -> {
                statusText = "香港天文台資料已同步"
                statusColour = Color(0xFF00C853)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .padding(start = 22.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(6.dp).background(statusColour))
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusText,
            color = LocalMetroSubText.current,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FoldableLoadingPage() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(180.dp)) { MetroProgress() }
            Spacer(Modifier.height(14.dp))
            Text(
                "正在取得香港天文台資料…",
                color = LocalMetroSubText.current,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}

@Composable
private fun FoldableErrorPage(message: String, retry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.TopStart) {
        Column {
            Text("資料暫時無法更新", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(10.dp))
            Text(message, color = LocalMetroSubText.current)
            Spacer(Modifier.height(18.dp))
            Text(
                "retry",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 18.sp,
                modifier = Modifier.clickable(onClick = retry).padding(vertical = 12.dp),
            )
        }
    }
}
