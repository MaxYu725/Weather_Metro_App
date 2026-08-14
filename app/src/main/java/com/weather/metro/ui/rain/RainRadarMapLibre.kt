package com.weather.metro.ui.rain

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.weather.metro.data.tools.ToolEndpoints
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainRadarBounds
import com.weather.metro.domain.rain.RainRadarFrame
import com.weather.metro.domain.rain.RainRadarTimeline
import com.weather.metro.ui.components.MetroProgress
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

private val RADAR_MAPLIBRE_FALLBACK_ACCENT = Color(0xFF20A7D8)
private val RADAR_MAPLIBRE_PANEL = Color(0xF20A0A0A)
private val RADAR_MAPLIBRE_MUTED = Color(0xFF9A9A9A)
private const val RADAR_MAPLIBRE_SOURCE = "weather-metro-radar-image"
private const val RADAR_MAPLIBRE_LAYER = "weather-metro-radar-layer"
private const val RADAR_MAPLIBRE_MIN_ZOOM = 7.5
private const val RADAR_MAPLIBRE_MAX_ZOOM = 17.0
private const val RADAR_MAPLIBRE_LOCATION_EPSILON = 0.000001
private const val RADAR_MAPLIBRE_OPACITY = 0.82f

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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeline = state.timeline.value
    val frame = state.selectedFrame
    val accent = if (pageColour.alpha > 0f) pageColour else RADAR_MAPLIBRE_FALLBACK_ACCENT

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (timeline != null && frame != null) {
            RadarMapLibreSurface(
                timeline = timeline,
                frame = frame,
                location = state.location,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            RadarCenteredState(
                status = state.timeline.status,
                errorMessage = state.timeline.errorMessage,
                accent = accent,
                onRefresh = onRefresh,
            )
        }

        RadarTopHud(
            timeline = timeline,
            isStale = state.timeline.isStale,
            accent = accent,
            onBack = onBack,
            onRefresh = onRefresh,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (isActive && timeline != null && frame != null) {
            RadarTimelineHud(
                timeline = timeline,
                selectedIndex = state.selectedFrameIndex ?: timeline.frames.lastIndex,
                accent = accent,
                onSelectFrame = onSelectFrame,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            )
        }
    }
}

@Composable
private fun RadarMapLibreSurface(
    timeline: RainRadarTimeline,
    frame: RainRadarFrame,
    location: LocationInfo?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestFrame by rememberUpdatedState(frame)
    val latestLocation by rememberUpdatedState(location)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var radarSource by remember { mutableStateOf<ImageSource?>(null) }
    var cameraBoundLatitude by remember { mutableStateOf(Double.NaN) }
    var cameraBoundLongitude by remember { mutableStateOf(Double.NaN) }

    fun bindCameraToLocation(readyMap: MapLibreMap, point: LocationInfo?) {
        val center = point?.let { LatLng(it.latitude, it.longitude) }
            ?: latestFrame.bounds.center()
        val changed = if (point != null) {
            !cameraBoundLatitude.isFinite() ||
                !cameraBoundLongitude.isFinite() ||
                abs(point.latitude - cameraBoundLatitude) > RADAR_MAPLIBRE_LOCATION_EPSILON ||
                abs(point.longitude - cameraBoundLongitude) > RADAR_MAPLIBRE_LOCATION_EPSILON
        } else {
            !cameraBoundLatitude.isFinite() || !cameraBoundLongitude.isFinite()
        }
        if (!changed) return

        readyMap.cameraPosition = CameraPosition.Builder()
            .target(center)
            .zoom(defaultRadarZoom(timeline.rangeKm))
            .build()
        cameraBoundLatitude = center.latitude
        cameraBoundLongitude = center.longitude
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
            mapView.onDestroy()
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
                style.addSource(source)
                style.addLayer(
                    RasterLayer(RADAR_MAPLIBRE_LAYER, RADAR_MAPLIBRE_SOURCE)
                        .withProperties(
                            rasterFadeDuration(0f),
                            rasterOpacity(RADAR_MAPLIBRE_OPACITY),
                            rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
                        ),
                )
                radarSource = source
                bindCameraToLocation(readyMap, latestLocation)
            }
        }
    }

    LaunchedEffect(frame, radarSource) {
        val source = radarSource ?: return@LaunchedEffect
        source.setCoordinates(frame.bounds.mapLibreQuad())
        source.setUri(URI(ToolEndpoints.rainRadarImage(frame.imageUrl)))
    }

    LaunchedEffect(location?.latitude, location?.longitude, map) {
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
    timeline: RainRadarTimeline?,
    isStale: Boolean,
    accent: Color,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RADAR_MAPLIBRE_PANEL)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadarHudButton("‹ tools", accent, onBack)
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
                    isStale -> "已保留上次成功雷達資料"
                    else -> "HKO 即時觀測 · ${timeline.frames.size} 幀"
                },
                color = RADAR_MAPLIBRE_MUTED,
                fontSize = 11.sp,
            )
        }
        RadarHudButton("更新", accent, onRefresh)
    }
}

@Composable
private fun RadarTimelineHud(
    timeline: RainRadarTimeline,
    selectedIndex: Int,
    accent: Color,
    onSelectFrame: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(RADAR_MAPLIBRE_PANEL)
            .border(1.dp, Color(0xFF2B2B2B))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatRadarTime(timeline.frames[selectedIndex].time),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${selectedIndex + 1}/${timeline.frames.size}",
                color = RADAR_MAPLIBRE_MUTED,
                fontSize = 11.sp,
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(timeline.frames) { index, item ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .background(if (selected) accent else Color(0xFF202020))
                        .border(1.dp, if (selected) accent else Color(0xFF363636))
                        .clickable { onSelectFrame(index) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = formatRadarTime(item.time),
                        color = Color.White,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarZoomControls(
    map: MapLibreMap?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (status == RainResourceStatus.LOADING) {
                Box(Modifier.fillMaxWidth(0.55f)) { MetroProgress(colour = accent) }
                Text("正在載入雷達…", color = Color.White, fontSize = 14.sp)
            } else {
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
