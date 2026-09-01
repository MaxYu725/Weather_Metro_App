package com.weather.metro.ui.rain

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.weather.metro.data.tools.RainRadarMode
import com.weather.metro.data.tools.ToolEndpoints
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainRadarBounds
import com.weather.metro.domain.rain.RainRadarFrame
import com.weather.metro.domain.rain.RainRadarTimeline
import com.weather.metro.ui.components.MetroFloatingIsland
import com.weather.metro.ui.components.MetroGlassContextSurface
import com.weather.metro.ui.theme.LocalMetroAccent
import com.weather.metro.ui.theme.LocalReduceMotion
import com.weather.metro.ui.tools.ToolLoadingPanel
import com.weather.metro.ui.tools.destroyAfterToolTransition
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.rasterResampling
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val RADAR_MAPLIBRE_FALLBACK_ACCENT = Color(0xFF20A7D8)
private val RADAR_MAPLIBRE_PANEL = Color(0xF20A0A0A)
private val RADAR_MAPLIBRE_MUTED = Color(0xFF9A9A9A)
private const val RADAR_MAPLIBRE_SOURCE = "weather-metro-radar-image"
private const val RADAR_MAPLIBRE_LAYER = "weather-metro-radar-layer"
private const val RADAR_MAPLIBRE_MIN_ZOOM = 7.5
private const val RADAR_MAPLIBRE_MAX_ZOOM = 17.0
private const val RADAR_MAPLIBRE_LOCATION_EPSILON = 0.000001
private const val RADAR_OPACITY_STEP = 0.10f

private val RADAR_MAPLIBRE_BASE_STYLE = """
{
  "version": 8,
  "name": "Weather Metro CARTO Dark Radar",
  "sources": {
    "carto-dark": {
      "type": "raster",
      "tiles": [
        "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "https://c.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "https://d.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"
      ],
      "tileSize": 256,
      "attribution": "© OpenStreetMap © CARTO"
    }
  },
  "layers": [
    {
      "id": "background",
      "type": "background",
      "paint": { "background-color": "#101010" }
    },
    {
      "id": "carto-dark-layer",
      "type": "raster",
      "source": "carto-dark",
      "minzoom": 0,
      "maxzoom": 20
    }
  ]
}
""".trimIndent()

@Composable
fun RainRadarMapLibrePanel(
    state: RainRadarHostState,
    pageColour: Color,
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
    modifier: Modifier = Modifier,
) {
    val timeline = state.timeline.value
    val frame = state.selectedFrame
    val contentReady = timeline != null && frame != null
    var playing by rememberSaveable { mutableStateOf(false) }
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    val accent = if (pageColour.alpha > 0f) pageColour else RADAR_MAPLIBRE_FALLBACK_ACCENT
    val reduceMotion = LocalReduceMotion.current

    LaunchedEffect(isActive) {
        if (!isActive) {
            playing = false
            controlsExpanded = false
        }
    }
    LaunchedEffect(state.rangeKm, state.heightKm, state.mode) {
        playing = false
    }
    LaunchedEffect(
        playing,
        isActive,
        state.selectedFrameIndex,
        state.playbackSpeed,
        timeline?.issueTime,
        timeline?.frames?.size,
    ) {
        if (!playing || !isActive || timeline == null || timeline.frames.size < 2) return@LaunchedEffect
        val current = state.selectedFrameIndex ?: timeline.frames.lastIndex
        val atLatest = current >= timeline.frames.lastIndex
        val frameDelay = if (atLatest) {
            (state.playbackSpeed.delayMs * 1.8).roundToLong()
        } else {
            state.playbackSpeed.delayMs
        }
        delay(frameDelay)
        onSelectFrame(if (atLatest) 0 else current + 1)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AnimatedVisibility(
            visible = contentReady,
            enter = fadeIn(tween(if (reduceMotion) 120 else 320)),
            exit = fadeOut(tween(if (reduceMotion) 100 else 160)),
        ) {
            val activeTimeline = timeline ?: return@AnimatedVisibility
            val activeFrame = frame ?: return@AnimatedVisibility
            RadarMapLibreSurface(
                timeline = activeTimeline,
                frame = activeFrame,
                location = state.location,
                opacity = state.opacity,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AnimatedVisibility(
            visible = !contentReady,
            enter = fadeIn(tween(if (reduceMotion) 120 else 220)),
            exit = fadeOut(tween(if (reduceMotion) 100 else 140)),
        ) {
            RadarCenteredState(
                status = state.timeline.status,
                errorMessage = state.timeline.errorMessage,
                accent = accent,
                onRefresh = onRefresh,
            )
        }

        RadarTopHud(
            state = state,
            timeline = timeline,
            accent = accent,
            onBack = {
                playing = false
                onBack()
            },
            onRefresh = {
                playing = false
                onRefresh()
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        AnimatedVisibility(
            visible = isActive && contentReady,
            enter = fadeIn(tween(if (reduceMotion) 120 else 260)) +
                slideInVertically(tween(if (reduceMotion) 120 else 320)) { height -> height / 4 },
            exit = fadeOut(tween(if (reduceMotion) 100 else 140)) +
                slideOutVertically(tween(if (reduceMotion) 100 else 180)) { height -> height / 5 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        ) {
            val activeTimeline = timeline ?: return@AnimatedVisibility
            val selectedIndex = state.selectedFrameIndex ?: activeTimeline.frames.lastIndex
            val togglePlay = {
                if (activeTimeline.frames.size >= 2) {
                    if (!playing && selectedIndex >= activeTimeline.frames.lastIndex) {
                        onSelectFrame(0)
                    }
                    playing = !playing
                }
            }
            MetroFloatingIsland(
                expanded = controlsExpanded,
                accent = accent,
                modifier = if (controlsExpanded) Modifier.fillMaxWidth() else Modifier,
                collapsedContent = {
                    RadarSquareButton(if (playing) "❚❚" else "▶", accent, true, togglePlay)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(
                            text = formatRadarTime(activeTimeline.frames[selectedIndex].time),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(
                            text = "${selectedIndex + 1}/${activeTimeline.frames.size}",
                            color = RADAR_MAPLIBRE_MUTED,
                            fontSize = 9.sp,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    RadarCompactButton("控制", accent) { controlsExpanded = true }
                },
                expandedContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "雷達控制",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        RadarCompactButton("收起", accent) { controlsExpanded = false }
                    }
                    Spacer(Modifier.size(4.dp))
                    RadarControlsHud(
                        state = state,
                        timeline = activeTimeline,
                        selectedIndex = selectedIndex,
                        playing = playing,
                        accent = accent,
                        onTogglePlay = togglePlay,
                        onSelectFrame = { index ->
                            playing = false
                            onSelectFrame(index)
                        },
                        onSelectRange = { rangeKm ->
                            playing = false
                            onSelectRange(rangeKm)
                        },
                        onSelectHeight = { heightKm ->
                            playing = false
                            onSelectHeight(heightKm)
                        },
                        onSelectMode = { mode ->
                            playing = false
                            onSelectMode(mode)
                        },
                        onOpacityChange = onOpacityChange,
                        onPlaybackSpeedChange = { speed ->
                            playing = false
                            onPlaybackSpeedChange(speed)
                        },
                        onJumpToLatest = {
                            playing = false
                            onJumpToLatest()
                        },
                        showChrome = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        }
    }
}

@Composable
private fun RadarMapLibreSurface(
    timeline: RainRadarTimeline,
    frame: RainRadarFrame,
    location: LocationInfo?,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestFrame by rememberUpdatedState(frame)
    val latestLocation by rememberUpdatedState(location)
    val latestOpacity by rememberUpdatedState(opacity)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var radarSource by remember { mutableStateOf<ImageSource?>(null) }
    var radarLayer by remember { mutableStateOf<RasterLayer?>(null) }
    var cameraBoundLatitude by remember { mutableStateOf(Double.NaN) }
    var cameraBoundLongitude by remember { mutableStateOf(Double.NaN) }
    var cameraBoundRangeKm by remember { mutableStateOf(Int.MIN_VALUE) }

    fun bindCameraToLocation(readyMap: MapLibreMap, point: LocationInfo?) {
        val center = point?.let { LatLng(it.latitude, it.longitude) }
            ?: latestFrame.bounds.center()
        val changed = if (point != null) {
            !cameraBoundLatitude.isFinite() ||
                !cameraBoundLongitude.isFinite() ||
                abs(point.latitude - cameraBoundLatitude) > RADAR_MAPLIBRE_LOCATION_EPSILON ||
                abs(point.longitude - cameraBoundLongitude) > RADAR_MAPLIBRE_LOCATION_EPSILON ||
                cameraBoundRangeKm != timeline.rangeKm
        } else {
            !cameraBoundLatitude.isFinite() ||
                !cameraBoundLongitude.isFinite() ||
                cameraBoundRangeKm != timeline.rangeKm
        }
        if (!changed) return

        readyMap.cameraPosition = CameraPosition.Builder()
            .target(center)
            .zoom(defaultRadarZoom(timeline.rangeKm))
            .build()
        cameraBoundLatitude = center.latitude
        cameraBoundLongitude = center.longitude
        cameraBoundRangeKm = timeline.rangeKm
    }

    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        val initialCenter = location?.let { LatLng(it.latitude, it.longitude) } ?: frame.bounds.center()
        val options = MapLibreMapOptions.createFromAttributes(context, null)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .minZoomPreference(RADAR_MAPLIBRE_MIN_ZOOM)
            .maxZoomPreference(RADAR_MAPLIBRE_MAX_ZOOM)
            .camera(
                CameraPosition.Builder()
                    .target(initialCenter)
                    .zoom(defaultRadarZoom(timeline.rangeKm))
                    .build(),
            )
        MapView(context, options).also { it.onCreate(null) }
    }

    DisposableEffect(mapView, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()

        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) mapView.onDestroy()
            else mapView.destroyAfterToolTransition()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.setMinZoomPreference(RADAR_MAPLIBRE_MIN_ZOOM)
            readyMap.setMaxZoomPreference(RADAR_MAPLIBRE_MAX_ZOOM)
            readyMap.uiSettings.setCompassEnabled(false)
            readyMap.uiSettings.setLogoEnabled(false)
            readyMap.uiSettings.setAttributionEnabled(false)
            readyMap.uiSettings.setRotateGesturesEnabled(false)
            readyMap.uiSettings.setTiltGesturesEnabled(false)
            readyMap.setStyle(Style.Builder().fromJson(RADAR_MAPLIBRE_BASE_STYLE)) { style ->
                val currentFrame = latestFrame
                val source = ImageSource(
                    RADAR_MAPLIBRE_SOURCE,
                    currentFrame.bounds.mapLibreQuad(),
                    URI(ToolEndpoints.rainRadarImage(currentFrame.imageUrl)),
                )
                val layer = RasterLayer(RADAR_MAPLIBRE_LAYER, RADAR_MAPLIBRE_SOURCE)
                    .withProperties(
                        rasterFadeDuration(0f),
                        rasterOpacity(latestOpacity),
                        rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
                    )
                style.addSource(source)
                style.addLayer(layer)
                radarSource = source
                radarLayer = layer
                bindCameraToLocation(readyMap, latestLocation)
            }
        }
    }

    LaunchedEffect(frame, radarSource) {
        val source = radarSource ?: return@LaunchedEffect
        source.setCoordinates(frame.bounds.mapLibreQuad())
        source.setUri(URI(ToolEndpoints.rainRadarImage(frame.imageUrl)))
    }

    LaunchedEffect(opacity, radarLayer) {
        radarLayer?.setProperties(rasterOpacity(opacity))
    }

    LaunchedEffect(location?.latitude, location?.longitude, timeline.rangeKm, map) {
        val readyMap = map ?: return@LaunchedEffect
        bindCameraToLocation(readyMap, location)
    }

    Box(modifier = modifier.background(Color(0xFF101010))) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )

        RadarZoomControls(
            map = map,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 82.dp),
        )
    }
}

@Composable
private fun RadarTopHud(
    state: RainRadarHostState,
    timeline: RainRadarTimeline?,
    accent: Color,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.78f),
                        Color.Black.copy(alpha = 0.46f),
                        accent.copy(alpha = 0.08f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadarHeaderAction("‹ tools", accent, onBack)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = timeline?.let { "雷達 · ${it.rangeKm} km · ${it.heightKm} km高" } ?: "雷達",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when {
                    timeline == null -> "MapLibre native raster"
                    state.timeline.isStale -> "已保留上次成功雷達資料"
                    state.mode == RainRadarMode.TEST -> "TEST 模擬動畫 · ${timeline.frames.size} 幀"
                    state.timeline.status == RainResourceStatus.LOADING -> "正在更新 HKO 即時雷達…"
                    else -> "HKO 即時觀測 · ${timeline.frames.size} 幀"
                },
                color = RADAR_MAPLIBRE_MUTED,
                fontSize = 11.sp,
            )
        }
        RadarHeaderAction("更新", accent, onRefresh)
    }
}

@Composable
private fun RadarHeaderAction(label: String, accent: Color, onClick: () -> Unit) {
    MetroGlassContextSurface(
        accent = accent,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RadarControlsHud(
    state: RainRadarHostState,
    timeline: RainRadarTimeline,
    selectedIndex: Int,
    playing: Boolean,
    accent: Color,
    onTogglePlay: () -> Unit,
    onSelectFrame: (Int) -> Unit,
    onSelectRange: (Int) -> Unit,
    onSelectHeight: (Int) -> Unit,
    onSelectMode: (RainRadarMode) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onPlaybackSpeedChange: (RainRadarPlaybackSpeed) -> Unit,
    onJumpToLatest: () -> Unit,
    showChrome: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val contract = state.contract.value
    val supportedRanges = contract?.rangesKm.orEmpty()
    val supportedHeights = contract?.heightsForRange(state.rangeKm).orEmpty()
    val supportedModes = contract?.modes.orEmpty()
    val surfaceModifier = if (showChrome) {
        Modifier
            .background(RADAR_MAPLIBRE_PANEL)
            .border(1.dp, Color(0xFF343434))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(surfaceModifier),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items(supportedRanges) { rangeKm ->
                RadarChip(
                    label = "$rangeKm km",
                    selected = state.rangeKm == rangeKm,
                    accent = accent,
                    onClick = { onSelectRange(rangeKm) },
                )
            }
            items(supportedHeights) { heightKm ->
                RadarChip(
                    label = "$heightKm km高",
                    selected = state.heightKm == heightKm,
                    enabled = state.mode == RainRadarMode.LIVE,
                    accent = accent,
                    onClick = { onSelectHeight(heightKm) },
                )
            }
            if (RainRadarMode.LIVE.wireValue in supportedModes) {
                item {
                    RadarChip(
                        label = "LIVE",
                        selected = state.mode == RainRadarMode.LIVE,
                        accent = accent,
                        onClick = { onSelectMode(RainRadarMode.LIVE) },
                    )
                }
            }
            if (RainRadarMode.TEST.wireValue in supportedModes) {
                item {
                    RadarChip(
                        label = "TEST",
                        selected = state.mode == RainRadarMode.TEST,
                        accent = accent,
                        onClick = { onSelectMode(RainRadarMode.TEST) },
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            RadarSquareButton(if (playing) "❚❚" else "▶", accent, true, onTogglePlay)
            Text(
                text = formatRadarTime(timeline.frames[selectedIndex].time),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                text = "${selectedIndex + 1}/${timeline.frames.size}",
                color = RADAR_MAPLIBRE_MUTED,
                fontSize = 10.sp,
            )
            Spacer(Modifier.weight(1f))
            RadarCompactButton(
                label = "${state.playbackSpeed.label}速",
                accent = accent,
                onClick = {
                    val next = when (state.playbackSpeed) {
                        RainRadarPlaybackSpeed.SLOW -> RainRadarPlaybackSpeed.NORMAL
                        RainRadarPlaybackSpeed.NORMAL -> RainRadarPlaybackSpeed.FAST
                        RainRadarPlaybackSpeed.FAST -> RainRadarPlaybackSpeed.SLOW
                    }
                    onPlaybackSpeedChange(next)
                },
            )
            RadarCompactButton(
                label = "最新",
                accent = accent,
                enabled = selectedIndex != timeline.frames.lastIndex,
                onClick = onJumpToLatest,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("回波", color = RADAR_MAPLIBRE_MUTED, fontSize = 10.sp)
            RadarMiniButton("−", accent) { onOpacityChange(state.opacity - RADAR_OPACITY_STEP) }
            Text(
                text = "${(state.opacity * 100).roundToInt()}%",
                color = Color.White,
                fontSize = 10.sp,
            )
            RadarMiniButton("+", accent) { onOpacityChange(state.opacity + RADAR_OPACITY_STEP) }
            Spacer(Modifier.size(4.dp))
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                itemsIndexed(timeline.frames) { index, item ->
                    RadarChip(
                        label = formatRadarTime(item.time),
                        selected = index == selectedIndex,
                        accent = accent,
                        onClick = { onSelectFrame(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarChip(
    label: String,
    selected: Boolean,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background = when {
        selected -> accent
        else -> Color(0xFF202020)
    }
    val border = when {
        selected -> accent
        enabled -> Color(0xFF414141)
        else -> Color(0xFF282828)
    }
    Text(
        text = label,
        color = Color.White.copy(alpha = if (enabled) 1f else 0.42f),
        fontSize = 10.sp,
        modifier = Modifier
            .background(background.copy(alpha = if (enabled) 1f else 0.45f))
            .border(1.dp, border)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    )
}

@Composable
private fun RadarSquareButton(
    label: String,
    accent: Color,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(if (emphasized) accent else Color(0xFF151515))
            .border(1.dp, if (emphasized) accent else Color(0xFF414141))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun RadarCompactButton(
    label: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (enabled) accent else RADAR_MAPLIBRE_MUTED.copy(alpha = 0.45f),
        fontSize = 10.sp,
        modifier = Modifier
            .border(1.dp, if (enabled) accent.copy(alpha = 0.7f) else Color(0xFF2B2B2B))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 7.dp),
    )
}

@Composable
private fun RadarMiniButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .border(1.dp, accent.copy(alpha = 0.7f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = accent, fontSize = 14.sp)
    }
}

@Composable
private fun RadarZoomControls(
    map: MapLibreMap?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RadarMapControl("+") {
            val readyMap = map ?: return@RadarMapControl
            val current = readyMap.cameraPosition
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(current.target)
                .zoom((current.zoom + 1.0).coerceAtMost(RADAR_MAPLIBRE_MAX_ZOOM))
                .bearing(current.bearing)
                .tilt(current.tilt)
                .build()
        }
        RadarMapControl("−") {
            val readyMap = map ?: return@RadarMapControl
            val current = readyMap.cameraPosition
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(current.target)
                .zoom((current.zoom - 1.0).coerceAtLeast(RADAR_MAPLIBRE_MIN_ZOOM))
                .bearing(current.bearing)
                .tilt(current.tilt)
                .build()
        }
    }
}

@Composable
private fun RadarMapControl(label: String, onClick: () -> Unit) {
    val accent = LocalMetroAccent.current
    MetroGlassContextSurface(
        accent = accent,
        modifier = Modifier
            .size(42.dp)
            .clickable(onClick = onClick),
    ) {
        Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun RadarHudButton(label: String, accent: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = accent,
        fontSize = 14.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

@Composable
private fun RadarCenteredState(
    status: RainResourceStatus,
    errorMessage: String?,
    accent: Color,
    onRefresh: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (status == RainResourceStatus.LOADING) {
            ToolLoadingPanel(
                title = "正在載入雷達",
                detail = "正在取得最新觀測影像與時間軸",
                accent = accent,
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = errorMessage ?: "尚未載入雷達",
                    color = Color.White,
                    fontSize = 14.sp,
                )
                RadarHudButton("重新載入", accent, onRefresh)
            }
        }
    }
}

private fun RainRadarBounds.mapLibreQuad(): LatLngQuad = LatLngQuad(
    LatLng(north, west),
    LatLng(north, east),
    LatLng(south, east),
    LatLng(south, west),
)

private fun RainRadarBounds.center(): LatLng = LatLng(
    (north + south) / 2.0,
    (east + west) / 2.0,
)

private fun defaultRadarZoom(rangeKm: Int): Double = if (rangeKm >= 256) 8.5 else 10.5

private fun formatRadarTime(value: String): String {
    val parsers = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US),
    )
    val date = parsers.firstNotNullOfOrNull { parser ->
        parser.timeZone = TimeZone.getTimeZone("UTC")
        runCatching { parser.parse(value) }.getOrNull()
    } ?: return value
    return SimpleDateFormat("HH:mm", Locale.TAIWAN).apply {
        timeZone = TimeZone.getTimeZone("Asia/Hong_Kong")
    }.format(date)
}
