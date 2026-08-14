package com.weather.metro.ui.rain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainForecastFrame
import com.weather.metro.domain.rain.RainForecastSource
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.ui.components.MetroProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

private val RAIN_MAP_FALLBACK_ACCENT = Color(0xFF20A7D8)
private val RAIN_MUTED = Color(0xFF8E8E8E)
private val RAIN_PANEL = Color(0xF20A0A0A)

@Composable
fun RainForecastPanel(
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
    val accent = if (pageColour.alpha > 0f) pageColour else RAIN_MAP_FALLBACK_ACCENT

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
            ForecastMapCanvas(
                frame = frame,
                location = state.location,
                markerColour = accent,
                isActive = isActive,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CenteredForecastState(
                status = state.forecast.status,
                errorMessage = state.forecast.errorMessage,
                accent = accent,
                onRefresh = onRefresh,
            )
        }

        ForecastTopHud(
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
            ForecastTimelineHud(
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
private fun ForecastTopHud(
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
            HudActionButton(
                label = "‹",
                accent = accent,
                emphasized = false,
                accentLabel = false,
                onClick = onBack,
            )
            HudActionButton(
                label = "↻",
                accent = accent,
                emphasized = refreshing,
                accentLabel = true,
                onClick = onRefresh,
            )
        }
    }
}

@Composable
private fun HudActionButton(
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
private fun ForecastMapCanvas(
    frame: RainForecastFrame,
    location: LocationInfo?,
    markerColour: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val rainImage = remember(frame) { frame.toRainImageBitmap() }
    val renderBounds = remember(frame.grid) { forecastRenderBounds(frame.grid) }
    val fallbackLatitude = (renderBounds.north + renderBounds.south) / 2.0
    val fallbackLongitude = (renderBounds.east + renderBounds.west) / 2.0
    var centerLatitude by rememberSaveable {
        mutableStateOf(location?.latitude ?: fallbackLatitude)
    }
    var centerLongitude by rememberSaveable {
        mutableStateOf(location?.longitude ?: fallbackLongitude)
    }
    var mapZoom by rememberSaveable { mutableStateOf(FORECAST_DEFAULT_MAP_ZOOM) }
    var boundLatitude by rememberSaveable { mutableStateOf(Double.NaN) }
    var boundLongitude by rememberSaveable { mutableStateOf(Double.NaN) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var tileImages by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var stableTileSpecs by remember { mutableStateOf<List<ForecastTileSpec>>(emptyList()) }

    LaunchedEffect(location?.latitude, location?.longitude) {
        val point = location ?: return@LaunchedEffect
        val changed = !boundLatitude.isFinite() || !boundLongitude.isFinite() ||
            abs(point.latitude - boundLatitude) > 0.000001 ||
            abs(point.longitude - boundLongitude) > 0.000001
        if (changed) {
            centerLatitude = point.latitude
            centerLongitude = point.longitude
            mapZoom = FORECAST_DEFAULT_MAP_ZOOM
            boundLatitude = point.latitude
            boundLongitude = point.longitude
            stableTileSpecs = emptyList()
        }
    }

    val tileSpecs = remember(
        centerLatitude,
        centerLongitude,
        mapZoom,
        viewportSize,
    ) {
        forecastBasemapTiles(
            centerLatitude = centerLatitude,
            centerLongitude = centerLongitude,
            mapZoom = mapZoom,
            viewportWidthPx = viewportSize.width,
            viewportHeightPx = viewportSize.height,
        )
    }

    LaunchedEffect(tileSpecs, isActive) {
        if (!isActive || tileSpecs.isEmpty()) return@LaunchedEffect
        val activeKeys = tileSpecs.mapTo(mutableSetOf()) { it.key }
        val stableKeys = stableTileSpecs.mapTo(mutableSetOf()) { it.key }
        val missingTiles = tileSpecs.filterNot { tileImages.containsKey(it.key) }
        if (missingTiles.isEmpty()) {
            tileImages = tileImages.filterKeys { it in activeKeys || it in stableKeys }
            stableTileSpecs = tileSpecs
            return@LaunchedEffect
        }
        val loadedTiles = coroutineScope {
            missingTiles.map { spec ->
                async {
                    val bitmap = ForecastTileLoader.load(spec)
                    spec.key to bitmap?.asImageBitmap()
                }
            }.awaitAll().mapNotNull { (key, image) -> image?.let { key to it } }.toMap()
        }
        if (loadedTiles.isNotEmpty()) {
            val merged = tileImages + loadedTiles
            val complete = tileSpecs.all { merged.containsKey(it.key) }
            tileImages = if (complete) {
                merged.filterKeys { it in activeKeys || it in stableKeys }
            } else {
                merged
            }
            if (complete) stableTileSpecs = tileSpecs
        }
    }

    Box(modifier = modifier.background(Color(0xFF101010))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        if (pan != Offset.Zero) {
                            val moved = forecastMapCenterAfterPan(
                                latitude = centerLatitude,
                                longitude = centerLongitude,
                                mapZoom = mapZoom,
                                panX = pan.x,
                                panY = pan.y,
                            )
                            centerLatitude = moved.latitude
                            centerLongitude = moved.longitude
                        }
                        if (zoomChange > 0f && zoomChange != 1f) {
                            val zoomDelta = ln(zoomChange.toDouble()) / ln(2.0)
                            mapZoom = (mapZoom + zoomDelta)
                                .coerceIn(FORECAST_MIN_MAP_ZOOM, FORECAST_MAX_MAP_ZOOM)
                        }
                    }
                },
        ) {
            val tileZoom = forecastTileZoom(mapZoom)
            val visualScale = forecastVisualScale(mapZoom)

            fun drawBasemapLayer(specs: List<ForecastTileSpec>) {
                if (specs.isEmpty()) return
                val layerZoom = specs.first().zoom
                val zoomDifference = tileZoom - layerZoom
                val zoomFactor = when {
                    zoomDifference > 0 -> (1 shl zoomDifference).toDouble()
                    zoomDifference < 0 -> 1.0 / (1 shl -zoomDifference).toDouble()
                    else -> 1.0
                }
                val layerScale = visualScale * zoomFactor
                val layerCenter = webMercatorPoint(centerLatitude, centerLongitude, layerZoom)

                fun layerScreenX(worldX: Double): Float =
                    size.width / 2f + ((worldX - layerCenter.x) * layerScale).toFloat()
                fun layerScreenY(worldY: Double): Float =
                    size.height / 2f + ((worldY - layerCenter.y) * layerScale).toFloat()

                specs.forEach { spec ->
                    val image = tileImages[spec.key] ?: return@forEach
                    val tileLeft = spec.x * FORECAST_TILE_SIZE_PX
                    val tileTop = spec.y * FORECAST_TILE_SIZE_PX
                    val left = layerScreenX(tileLeft)
                    val top = layerScreenY(tileTop)
                    val tileSize = (FORECAST_TILE_SIZE_PX * layerScale).toFloat()
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(
                            tileSize.roundToInt().coerceAtLeast(1),
                            tileSize.roundToInt().coerceAtLeast(1),
                        ),
                        filterQuality = FilterQuality.Low,
                    )
                }
            }

            if (stableTileSpecs != tileSpecs) {
                drawBasemapLayer(stableTileSpecs)
            }
            drawBasemapLayer(tileSpecs)

            val centerWorld = webMercatorPoint(centerLatitude, centerLongitude, tileZoom)
            fun screenX(worldX: Double): Float =
                size.width / 2f + ((worldX - centerWorld.x) * visualScale).toFloat()
            fun screenY(worldY: Double): Float =
                size.height / 2f + ((worldY - centerWorld.y) * visualScale).toFloat()

            val gridNorthWest = webMercatorPoint(renderBounds.north, renderBounds.west, tileZoom)
            val gridSouthEast = webMercatorPoint(renderBounds.south, renderBounds.east, tileZoom)
            val rainLeft = screenX(gridNorthWest.x)
            val rainTop = screenY(gridNorthWest.y)
            val rainRight = screenX(gridSouthEast.x)
            val rainBottom = screenY(gridSouthEast.y)
            drawImage(
                image = rainImage,
                dstOffset = IntOffset(rainLeft.roundToInt(), rainTop.roundToInt()),
                dstSize = IntSize(
                    (rainRight - rainLeft).roundToInt().coerceAtLeast(1),
                    (rainBottom - rainTop).roundToInt().coerceAtLeast(1),
                ),
                filterQuality = FilterQuality.Low,
            )

            location?.let { point ->
                val world = webMercatorPoint(point.latitude, point.longitude, tileZoom)
                val markerCenter = Offset(screenX(world.x), screenY(world.y))
                if (
                    markerCenter.x >= -30.dp.toPx() &&
                    markerCenter.x <= size.width + 30.dp.toPx() &&
                    markerCenter.y >= -30.dp.toPx() &&
                    markerCenter.y <= size.height + 30.dp.toPx()
                ) {
                    drawCircle(Color.White, radius = 10.dp.toPx(), center = markerCenter)
                    drawCircle(markerColour, radius = 6.8.dp.toPx(), center = markerCenter)
                }
            }
        }

        ForecastMapZoomControls(
            mapZoom = mapZoom,
            onZoomOut = {
                mapZoom = (mapZoom - 1.0).coerceAtLeast(FORECAST_MIN_MAP_ZOOM)
            },
            onZoomIn = {
                mapZoom = (mapZoom + 1.0).coerceAtMost(FORECAST_MAX_MAP_ZOOM)
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 82.dp),
        )
    }
}

@Composable
private fun ForecastMapZoomControls(
    mapZoom: Double,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ForecastMapControl(
            label = "+",
            enabled = mapZoom < FORECAST_MAX_MAP_ZOOM - 0.001,
            onClick = onZoomIn,
        )
        ForecastMapControl(
            label = "−",
            enabled = mapZoom > FORECAST_MIN_MAP_ZOOM + 0.001,
            onClick = onZoomOut,
        )
    }
}

@Composable
private fun ForecastMapControl(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(Color(0xE60A0A0A))
            .border(1.dp, Color(0xFF2E2E2E))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.36f),
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun ForecastTimelineHud(
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
            .background(RAIN_PANEL)
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
                modifier = Modifier
                    .border(1.dp, accent.copy(alpha = 0.72f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${formatForecastTime(frame.windowStart)}–${formatForecastTime(frame.windowEnd)}  ${timeline.loadedFrameCount}/${timeline.frames.size}",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 10.sp,
            )
        }

        ForecastTimeline(
            timeline = timeline,
            selectedIndex = selectedIndex,
            playing = playing,
            accent = accent,
            onTogglePlay = onTogglePlay,
            onSelectFrame = onSelectFrame,
        )

        ForecastLegendCompact()

        val source = when (timeline.source) {
            RainForecastSource.SWIRLS -> "HKO SWIRLS 基準 ${formatForecastTime(timeline.issueTime)}"
            RainForecastSource.NOWCAST -> "HKO nowcast 後備預報"
        }
        val loadingMode = if (
            timeline.source == RainForecastSource.SWIRLS &&
            timeline.loadedFrameCount < timeline.frames.size
        ) {
            " · 時段按需要載入"
        } else {
            ""
        }
        val stale = if (isStale) " · 舊資料" else ""
        Text(
            text = "$source · 每${timeline.cadenceMinutes}分鐘一格 · 每格為${timeline.accumulationMinutes}分鐘累積$loadingMode$stale · © OSM © CARTO",
            color = if (isStale) Color(0xFFFFB300) else RAIN_MUTED,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (frameLoading) {
            Text("正在準備選定時段，地圖保持目前視野…", color = accent, fontSize = 8.sp)
        }
    }
}

@Composable
private fun ForecastTimeline(
    timeline: RainForecastTimeline,
    selectedIndex: Int,
    playing: Boolean,
    accent: Color,
    onTogglePlay: () -> Unit,
    onSelectFrame: (Int) -> Unit,
) {
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
                    Text(
                        formatForecastTime(slot.validTime),
                        color = Color.White,
                        fontSize = 11.sp,
                    )
                    Text(
                        "+${slot.leadMinutes} 分",
                        color = if (selected) Color.White.copy(alpha = 0.78f) else RAIN_MUTED,
                        fontSize = 8.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ForecastLegendCompact() {
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
private fun CenteredForecastState(
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
                    Spacer(Modifier.height(6.dp))
                    Text(
                        errorMessage ?: "請稍後再試",
                        color = RAIN_MUTED,
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "retry",
                        color = accent,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable(onClick = onRefresh)
                            .padding(10.dp),
                    )
                }
                else -> {
                    MetroProgress(colour = accent)
                    Spacer(Modifier.height(10.dp))
                    Text("正在載入兩小時預報…", color = RAIN_MUTED, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun RainForecastFrame.toRainImageBitmap(): ImageBitmap {
    val pixels = IntArray(values.size) { index -> rainfallArgb(values[index]) }
    return Bitmap.createBitmap(pixels, grid.cols, grid.rows, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private object ForecastTileLoader {
    private val cache = LruCache<String, Bitmap>(96)

    suspend fun load(spec: ForecastTileSpec): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(cache) { cache.get(spec.key) }?.let { return@withContext it }
        val bitmap = runCatching {
            val connection = URI(spec.url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "WeatherMetro/1.1 Android")
            connection.connect()
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
        if (bitmap != null) synchronized(cache) { cache.put(spec.key, bitmap) }
        bitmap
    }
}
