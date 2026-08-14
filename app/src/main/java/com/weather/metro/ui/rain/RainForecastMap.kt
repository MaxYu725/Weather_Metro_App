package com.weather.metro.ui.rain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.rain.RainForecastFrame
import com.weather.metro.domain.rain.RainForecastSource
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.ui.components.MetroProgress
import com.weather.metro.ui.components.MetroTile
import com.weather.metro.ui.theme.LocalMetroSubText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun RainForecastPanel(
    state: RainHostState,
    pageColour: Color,
    isActive: Boolean,
    onRefresh: () -> Unit,
    onSelectFrame: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeline = state.forecast.value
    val frame = state.forecastFrame.value
    var playing by rememberSaveable { mutableStateOf(false) }

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

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "兩小時預報",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    text = timeline?.let { "${it.cadenceMinutes}分鐘步進 · 每格 ${it.accumulationMinutes}分鐘累積雨量" }
                        ?: "SWIRLS 原生預報圖層",
                    color = LocalMetroSubText.current,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = "refresh",
                color = pageColour,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable {
                        playing = false
                        onRefresh()
                    }
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            )
        }

        when (state.forecast.status) {
            RainResourceStatus.IDLE -> ForecastLoadTile(pageColour, onRefresh)
            RainResourceStatus.LOADING -> {
                MetroProgress(colour = pageColour)
                Text(
                    if (timeline == null) "正在載入兩小時預報…" else "正在更新兩小時預報…",
                    color = LocalMetroSubText.current,
                    fontSize = 11.sp,
                )
            }
            RainResourceStatus.ERROR -> ForecastErrorTile(
                pageColour = pageColour,
                message = state.forecast.errorMessage ?: "兩小時預報暫時無法使用",
                onRefresh = onRefresh,
            )
            RainResourceStatus.READY -> Unit
        }

        if (timeline != null && frame != null) {
            ForecastStatusLine(timeline, state)
            ForecastMapCanvas(
                frame = frame,
                location = state.location,
                markerColour = pageColour,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(390.dp),
            )
            ForecastLegend()
            ForecastTimeline(
                timeline = timeline,
                selectedIndex = state.forecastFrameIndex ?: 0,
                frameLoading = state.forecastFrame.status == RainResourceStatus.LOADING,
                playing = playing,
                pageColour = pageColour,
                onTogglePlay = { playing = !playing },
                onSelectFrame = { index ->
                    playing = false
                    onSelectFrame(index)
                },
            )
            state.forecastFrame.errorMessage?.let { message ->
                Text(message, color = Color(0xFFFFC107), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ForecastStatusLine(timeline: RainForecastTimeline, state: RainHostState) {
    val source = when (timeline.source) {
        RainForecastSource.SWIRLS -> "SWIRLS 16-frame"
        RainForecastSource.NOWCAST -> "HKO nowcast fallback"
    }
    val stale = if (state.forecast.isStale || state.forecastFrame.isStale) " · 舊資料" else ""
    Text(
        text = "$source · 已載入 ${timeline.loadedFrameCount}/${timeline.frames.size}$stale",
        color = if (stale.isNotEmpty()) Color(0xFFFFC107) else LocalMetroSubText.current,
        fontSize = 10.sp,
    )
    timeline.fallbackReason?.takeIf { it.isNotBlank() }?.let {
        Text("SWIRLS 暫不可用，已切換後備預報。", color = LocalMetroSubText.current, fontSize = 10.sp)
    }
}

@Composable
private fun ForecastMapCanvas(
    frame: RainForecastFrame,
    location: LocationInfo?,
    markerColour: Color,
    modifier: Modifier = Modifier,
) {
    val rainImage = remember(frame) { frame.toRainImageBitmap() }
    val renderBounds = remember(frame.grid) { forecastRenderBounds(frame.grid) }
    val viewport = remember(renderBounds) { paddedForecastBounds(renderBounds) }
    val tileSpecs = remember(viewport) { forecastBasemapTiles(viewport) }
    var tileImages by remember(viewport) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }

    LaunchedEffect(tileSpecs) {
        tileImages = coroutineScope {
            tileSpecs.map { spec ->
                async {
                    val bitmap = ForecastTileLoader.load(spec)
                    spec.key to bitmap?.asImageBitmap()
                }
            }.awaitAll().mapNotNull { (key, image) -> image?.let { key to it } }.toMap()
        }
    }

    Box(modifier = modifier.background(Color(0xFF101010))) {
        Canvas(Modifier.fillMaxSize()) {
            val northWest = webMercatorPoint(viewport.north, viewport.west, FORECAST_BASEMAP_ZOOM)
            val southEast = webMercatorPoint(viewport.south, viewport.east, FORECAST_BASEMAP_ZOOM)
            val worldWidth = (southEast.x - northWest.x).coerceAtLeast(1.0)
            val worldHeight = (southEast.y - northWest.y).coerceAtLeast(1.0)
            val scale = min(size.width / worldWidth.toFloat(), size.height / worldHeight.toFloat())
            val mapWidth = worldWidth.toFloat() * scale
            val mapHeight = worldHeight.toFloat() * scale
            val offsetX = (size.width - mapWidth) / 2f
            val offsetY = (size.height - mapHeight) / 2f

            fun screenX(worldX: Double): Float = offsetX + ((worldX - northWest.x) * scale).toFloat()
            fun screenY(worldY: Double): Float = offsetY + ((worldY - northWest.y) * scale).toFloat()

            tileSpecs.forEach { spec ->
                val image = tileImages[spec.key] ?: return@forEach
                val tileLeft = spec.x * FORECAST_TILE_SIZE_PX
                val tileTop = spec.y * FORECAST_TILE_SIZE_PX
                val left = screenX(tileLeft)
                val top = screenY(tileTop)
                val right = screenX(tileLeft + FORECAST_TILE_SIZE_PX)
                val bottom = screenY(tileTop + FORECAST_TILE_SIZE_PX)
                drawImage(
                    image = image,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(
                        (right - left).roundToInt().coerceAtLeast(1),
                        (bottom - top).roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.Low,
                )
            }

            val gridNorthWest = webMercatorPoint(renderBounds.north, renderBounds.west, FORECAST_BASEMAP_ZOOM)
            val gridSouthEast = webMercatorPoint(renderBounds.south, renderBounds.east, FORECAST_BASEMAP_ZOOM)
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
            drawRect(
                color = Color.White.copy(alpha = 0.32f),
                topLeft = Offset(rainLeft, rainTop),
                size = Size(rainRight - rainLeft, rainBottom - rainTop),
                style = Stroke(width = 1.dp.toPx()),
            )

            location?.let { point ->
                if (
                    point.latitude in viewport.south..viewport.north &&
                    point.longitude in viewport.west..viewport.east
                ) {
                    val world = webMercatorPoint(point.latitude, point.longitude, FORECAST_BASEMAP_ZOOM)
                    val center = Offset(screenX(world.x), screenY(world.y))
                    drawCircle(Color.White, radius = 7.dp.toPx(), center = center)
                    drawCircle(markerColour, radius = 4.5.dp.toPx(), center = center)
                }
            }
        }
        Text(
            text = "${formatForecastTime(frame.validTime)}  +${frame.leadMinutes} · ${frame.unit}",
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        Text(
            text = "© OpenStreetMap © CARTO",
            color = Color.White.copy(alpha = 0.70f),
            fontSize = 8.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color.Black.copy(alpha = 0.56f))
                .padding(horizontal = 5.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ForecastTimeline(
    timeline: RainForecastTimeline,
    selectedIndex: Int,
    frameLoading: Boolean,
    playing: Boolean,
    pageColour: Color,
    onTogglePlay: () -> Unit,
    onSelectFrame: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(pageColour)
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (playing) "❚❚" else "▶", color = Color.White, fontSize = 15.sp)
        }
        Spacer(Modifier.size(6.dp))
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            itemsIndexed(timeline.frames) { index, slot ->
                val selected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .background(if (selected) pageColour else Color(0xFF202020))
                        .clickable(enabled = !frameLoading || selected) { onSelectFrame(index) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(formatForecastTime(slot.validTime), color = Color.White, fontSize = 12.sp)
                    Text("+${slot.leadMinutes}", color = Color.White.copy(alpha = 0.70f), fontSize = 9.sp)
                }
            }
        }
    }
    if (frameLoading) {
        Text("正在載入選定預報格…", color = LocalMetroSubText.current, fontSize = 10.sp)
    }
}

@Composable
private fun ForecastLegend() {
    val stops = listOf(
        "0.05" to Color(0xFF24A2D6),
        "0.2" to Color(0xFF22BBD6),
        "0.5" to Color(0xFF29C768),
        "1" to Color(0xFF6FCF3A),
        "2" to Color(0xFFE8CC32),
        "5" to Color(0xFFF6932D),
        "10+" to Color(0xFFEB483A),
    )
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            stops.forEach { (label, colour) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(colour))
                    Spacer(Modifier.size(2.dp))
                    Text(label, color = LocalMetroSubText.current, fontSize = 8.sp)
                }
            }
        }
        Text("mm / 30分鐘", color = LocalMetroSubText.current, fontSize = 8.sp)
    }
}

@Composable
private fun ForecastLoadTile(pageColour: Color, onRefresh: () -> Unit) {
    MetroTile(
        seed = "forecast-map-load",
        background = Color(0xFF181818),
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh,
    ) {
        Column {
            Text("載入兩小時預報", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light)
            Text("SWIRLS 16-frame · 按需要才下載", color = LocalMetroSubText.current, fontSize = 11.sp)
            Spacer(Modifier.height(7.dp))
            Text("load", color = pageColour, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ForecastErrorTile(pageColour: Color, message: String, onRefresh: () -> Unit) {
    MetroTile(
        seed = "forecast-map-error",
        background = Color(0xFF202020),
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh,
    ) {
        Column {
            Text("兩小時預報暫時無法使用", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(4.dp))
            Text(message, color = LocalMetroSubText.current, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text("retry", color = pageColour, fontSize = 13.sp)
        }
    }
}

private fun RainForecastFrame.toRainImageBitmap(): ImageBitmap {
    val pixels = IntArray(values.size) { index -> rainfallArgb(values[index]) }
    return Bitmap.createBitmap(pixels, grid.cols, grid.rows, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private object ForecastTileLoader {
    private val cache = LruCache<String, Bitmap>(48)

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
