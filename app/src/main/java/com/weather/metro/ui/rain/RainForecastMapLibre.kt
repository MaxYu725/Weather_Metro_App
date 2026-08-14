package com.weather.metro.ui.rain

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainForecastFrame
import com.weather.metro.domain.rain.RainForecastSource
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.ui.components.MetroProgress
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource

private val MAPLIBRE_FALLBACK_ACCENT = Color(0xFF20A7D8)
private val MAPLIBRE_MUTED = Color(0xFF8E8E8E)
private val MAPLIBRE_PANEL = Color(0xF20A0A0A)
private const val MAPLIBRE_RAIN_SOURCE = "weather-metro-rain-image"
private const val MAPLIBRE_RAIN_LAYER = "weather-metro-rain-layer"
private const val MAPLIBRE_DEFAULT_ZOOM = 15.5
private const val MAPLIBRE_MIN_ZOOM = 10.0
private const val MAPLIBRE_MAX_ZOOM = 18.0

private val MAPLIBRE_BASE_STYLE = """
{
  "version": 8,
  "name": "Weather Metro CARTO Dark",
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
fun RainForecastMapLibrePanel(
    state: RainHostState,
    pageColour: Color,
    isActive: Boolean,
    onRefresh: () -> Unit,
    onSelectFrame: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeline = state.forecast.value
    val frame = state.forecastFrame.value
    var playing by rememberSaveable { mutableStateOf(false) }
    val accent = if (pageColour.alpha > 0f) pageColour else MAPLIBRE_FALLBACK_ACCENT

    LaunchedEffect(isActive) {
        if (!isActive) playing = false
    }
    LaunchedEffect(
        playing,
        isActive,
        state.forecastFrame.status,
        state.forecastFrameIndex,
        timeline?.issueTime,
        timeline?.frames?.size,
    ) {
        if (!playing || !isActive || timeline == null) return@LaunchedEffect
        if (state.forecastFrame.status != RainResourceStatus.READY) return@LaunchedEffect
        delay(1_000)
        val current = state.forecastFrameIndex ?: 0
        val next = if (current >= timeline.frames.lastIndex) 0 else current + 1
        onSelectFrame(next)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (timeline != null && frame != null) {
            MapLibreForecastSurface(
                frame = frame,
                location = state.location,
                markerColour = accent,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MapLibreCenteredState(
                status = state.forecast.status,
                errorMessage = state.forecast.errorMessage,
                accent = accent,
                onRefresh = onRefresh,
            )
        }

        MapLibreTopHud(
            state = state,
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

        if (timeline != null && frame != null) {
            MapLibreTimelineHud(
                timeline = timeline,
                frame = frame,
                selectedIndex = state.forecastFrameIndex ?: frame.frameIndex,
                frameLoading = state.forecastFrame.status == RainResourceStatus.LOADING,
                isStale = state.forecast.isStale || state.forecastFrame.isStale,
                playing = playing,
                accent = accent,
                onTogglePlay = { playing = !playing },
                onSelectFrame = { index ->
                    playing = false
                    onSelectFrame(index)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            )
        }
    }
}

@Composable
private fun MapLibreForecastSurface(
    frame: RainForecastFrame,
    location: LocationInfo?,
    markerColour: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestFrame by rememberUpdatedState(frame)
    val latestLocation by rememberUpdatedState(location)
    val latestMarkerColour by rememberUpdatedState(markerColour)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var rainSource by remember { mutableStateOf<ImageSource?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }

    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        val initialBounds = forecastRenderBounds(frame.grid)
        val initialLat = location?.latitude ?: (initialBounds.north + initialBounds.south) / 2.0
        val initialLon = location?.longitude ?: (initialBounds.east + initialBounds.west) / 2.0
        val options = MapLibreMapOptions.createFromAttributes(context, null)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .minZoomPreference(MAPLIBRE_MIN_ZOOM)
            .maxZoomPreference(MAPLIBRE_MAX_ZOOM)
            .camera(
                CameraPosition.Builder()
                    .target(LatLng(initialLat, initialLon))
                    .zoom(MAPLIBRE_DEFAULT_ZOOM)
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
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.setMinZoomPreference(MAPLIBRE_MIN_ZOOM)
            readyMap.setMaxZoomPreference(MAPLIBRE_MAX_ZOOM)
            readyMap.uiSettings.setCompassEnabled(false)
            readyMap.uiSettings.setLogoEnabled(false)
            readyMap.uiSettings.setAttributionEnabled(false)
            readyMap.uiSettings.setRotateGesturesEnabled(false)
            readyMap.uiSettings.setTiltGesturesEnabled(false)
            readyMap.setStyle(Style.Builder().fromJson(MAPLIBRE_BASE_STYLE)) { style ->
                val currentFrame = latestFrame
                val source = ImageSource(
                    MAPLIBRE_RAIN_SOURCE,
                    currentFrame.mapLibreQuad(),
                    currentFrame.toAndroidRainBitmap(),
                )
                style.addSource(source)
                style.addLayer(
                    RasterLayer(MAPLIBRE_RAIN_LAYER, MAPLIBRE_RAIN_SOURCE)
                        .withProperties(rasterFadeDuration(0f)),
                )
                rainSource = source
                marker = readyMap.replaceLocationMarker(
                    current = marker,
                    location = latestLocation,
                    markerColour = latestMarkerColour,
                    context = context,
                )
            }
        }
    }

    LaunchedEffect(frame, rainSource) {
        val source = rainSource ?: return@LaunchedEffect
        source.setCoordinates(frame.mapLibreQuad())
        source.setImage(frame.toAndroidRainBitmap())
    }

    LaunchedEffect(location?.latitude, location?.longitude, markerColour, map) {
        val readyMap = map ?: return@LaunchedEffect
        marker = readyMap.replaceLocationMarker(
            current = marker,
            location = location,
            markerColour = markerColour,
            context = context,
        )
    }

    Box(modifier = modifier.background(Color(0xFF101010))) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )

        MapLibreZoomControls(
            map = map,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 82.dp),
        )
    }
}

@Suppress("DEPRECATION")
private fun MapLibreMap.replaceLocationMarker(
    current: Marker?,
    location: LocationInfo?,
    markerColour: Color,
    context: android.content.Context,
): Marker? {
    current?.let { removeMarker(it) }
    val point = location ?: return null
    val icon = IconFactory.getInstance(context).fromBitmap(locationMarkerBitmap(markerColour.toArgb()))
    return addMarker(
        MarkerOptions()
            .position(LatLng(point.latitude, point.longitude))
            .icon(icon),
    )
}

private fun locationMarkerBitmap(accent: Int): Bitmap {
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(size / 2f, size / 2f, 12f, paint)
    paint.color = accent
    canvas.drawCircle(size / 2f, size / 2f, 8f, paint)
    return bitmap
}

private fun RainForecastFrame.mapLibreQuad(): LatLngQuad {
    val bounds = forecastRenderBounds(grid)
    return LatLngQuad(
        LatLng(bounds.north, bounds.west),
        LatLng(bounds.north, bounds.east),
        LatLng(bounds.south, bounds.east),
        LatLng(bounds.south, bounds.west),
    )
}

private fun RainForecastFrame.toAndroidRainBitmap(): Bitmap {
    val pixels = IntArray(values.size) { index -> rainfallArgb(values[index]) }
    return Bitmap.createBitmap(pixels, grid.cols, grid.rows, Bitmap.Config.ARGB_8888)
}

@Composable
private fun MapLibreZoomControls(
    map: MapLibreMap?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        MapLibreMapControl("+") {
            val readyMap = map ?: return@MapLibreMapControl
            val current = readyMap.cameraPosition
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(current.target)
                .zoom((current.zoom + 1.0).coerceAtMost(MAPLIBRE_MAX_ZOOM))
                .bearing(current.bearing)
                .tilt(current.tilt)
                .build()
        }
        MapLibreMapControl("−") {
            val readyMap = map ?: return@MapLibreMapControl
            val current = readyMap.cameraPosition
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(current.target)
                .zoom((current.zoom - 1.0).coerceAtLeast(MAPLIBRE_MIN_ZOOM))
                .bearing(current.bearing)
                .tilt(current.tilt)
                .build()
        }
    }
}

@Composable
private fun MapLibreMapControl(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(Color(0xE60A0A0A))
            .border(1.dp, Color(0xFF2E2E2E))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun MapLibreTopHud(
    state: RainHostState,
    accent: Color,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val location = state.location?.label ?: "目前位置"
    val refreshing = state.forecast.status == RainResourceStatus.LOADING
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xF20A0A0A))
            .padding(start = 18.dp, end = 14.dp, top = 13.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = location,
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MapLibreHudActionButton("‹", accent, false, false, onBack)
            MapLibreHudActionButton("↻", accent, refreshing, true, onRefresh)
        }
    }
}

@Composable
private fun MapLibreHudActionButton(
    label: String,
    accent: Color,
    emphasized: Boolean,
    accentLabel: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(if (emphasized) accent else Color(0xFF0D0D0D))
            .border(1.dp, if (emphasized) accent else Color(0xFF3A3A3A))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                emphasized -> Color.White
                accentLabel -> accent
                else -> Color.White
            },
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun MapLibreTimelineHud(
    timeline: RainForecastTimeline,
    frame: RainForecastFrame,
    selectedIndex: Int,
    frameLoading: Boolean,
    isStale: Boolean,
    playing: Boolean,
    accent: Color,
    onTogglePlay: () -> Unit,
    onSelectFrame: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MAPLIBRE_PANEL)
            .border(1.dp, Color(0xFF474747))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${timeline.cadenceMinutes}分鐘步進", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.size(7.dp))
            Text(
                "mm / ${timeline.accumulationMinutes}分鐘",
                color = accent,
                fontSize = 10.sp,
                modifier = Modifier.border(1.dp, accent.copy(alpha = 0.72f)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${formatForecastTime(frame.windowStart)}–${formatForecastTime(frame.windowEnd)}  ${timeline.loadedFrameCount}/${timeline.frames.size}",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 10.sp,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 45.dp, height = 46.dp)
                    .background(accent)
                    .border(1.dp, accent)
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (playing) "❚❚" else "▶", color = Color.White, fontSize = 14.sp)
            }
            Spacer(Modifier.size(5.dp))
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                itemsIndexed(timeline.frames) { index, slot ->
                    val selected = index == selectedIndex
                    Column(
                        modifier = Modifier
                            .background(if (selected) accent else Color(0xFF101010))
                            .border(1.dp, if (selected) accent else Color(0xFF444444))
                            .clickable { onSelectFrame(index) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(formatForecastTime(slot.validTime), color = Color.White, fontSize = 11.sp)
                        Text(
                            "+${slot.leadMinutes} 分",
                            color = if (selected) Color.White.copy(alpha = 0.78f) else MAPLIBRE_MUTED,
                            fontSize = 8.sp,
                        )
                    }
                }
            }
        }

        MapLibreLegendCompact()
        val source = when (timeline.source) {
            RainForecastSource.SWIRLS -> "HKO SWIRLS 基準 ${formatForecastTime(timeline.issueTime)}"
            RainForecastSource.NOWCAST -> "HKO nowcast 後備預報"
        }
        val preload = if (timeline.source == RainForecastSource.SWIRLS && timeline.loadedFrameCount < timeline.frames.size) {
            " · 背景預載中"
        } else {
            ""
        }
        val stale = if (isStale) " · 舊資料" else ""
        Text(
            text = "$source · 每${timeline.cadenceMinutes}分鐘一格 · 每格為${timeline.accumulationMinutes}分鐘累積$preload$stale · MapLibre · © OSM © CARTO",
            color = if (isStale) Color(0xFFFFB300) else MAPLIBRE_MUTED,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (frameLoading) {
            Text("正在準備選定時段，MapLibre 地圖保持目前視野…", color = accent, fontSize = 8.sp)
        }
    }
}

@Composable
private fun MapLibreLegendCompact() {
    val stops = listOf(
        Color(0xFF24A2D6),
        Color(0xFF22BBD6),
        Color(0xFF29C768),
        Color(0xFF6FCF3A),
        Color(0xFFE8CC32),
        Color(0xFFF6932D),
        Color(0xFFEB483A),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("30分鐘累積雨量  0.05", color = Color.White.copy(alpha = 0.74f), fontSize = 8.sp)
        Spacer(Modifier.size(5.dp))
        stops.forEach { colour ->
            Box(Modifier.size(width = 17.dp, height = 6.dp).background(colour))
        }
        Spacer(Modifier.size(5.dp))
        Text("10+ mm", color = Color.White.copy(alpha = 0.74f), fontSize = 8.sp)
    }
}

@Composable
private fun MapLibreCenteredState(
    status: RainResourceStatus,
    errorMessage: String?,
    accent: Color,
    onRefresh: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (status) {
                RainResourceStatus.ERROR -> {
                    Text("兩小時預報暫時無法使用", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.size(6.dp))
                    Text(errorMessage ?: "請稍後再試", color = MAPLIBRE_MUTED, fontSize = 10.sp, maxLines = 2)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "retry",
                        color = accent,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onRefresh).padding(10.dp),
                    )
                }
                else -> {
                    MetroProgress(colour = accent)
                    Spacer(Modifier.size(10.dp))
                    Text("正在載入兩小時預報…", color = MAPLIBRE_MUTED, fontSize = 11.sp)
                }
            }
        }
    }
}
