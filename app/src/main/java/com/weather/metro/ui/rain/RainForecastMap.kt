package com.weather.metro.ui.rain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

private val RAIN_MAP_ACCENT = Color(0xFF20A7D8)
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
    val pointModel = state.pointForecast.value?.let(::buildRainPointUiModel)
    var playing by rememberSaveable { mutableStateOf(false) }
    var sheetExpanded by rememberSaveable { mutableStateOf(false) }
    val sheetHeight = if (sheetExpanded) 300.dp else 132.dp
    val accent = if (pageColour.alpha > 0f) RAIN_MAP_ACCENT else RAIN_MAP_ACCENT

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
                radiusKm = state.pointRequest?.radiusKm ?: RainHostViewModel.DEFAULT_POINT_RADIUS_KM,
                markerColour = accent,
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
            pointModel = pointModel,
            accent = accent,
            onBack = {
                playing = false
                onBack()
            },
            onRefresh = {
                playing = false
                onRefresh()
            },
            onToggleDetails = { sheetExpanded = !sheetExpanded },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (timeline != null && frame != null) {
            ForecastDataStatusChip(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 88.dp, end = 14.dp),
            )

            ForecastTimelineHud(
                timeline = timeline,
                frame = frame,
                selectedIndex = state.forecastFrameIndex ?: frame.frameIndex,
                frameLoading = state.forecastFrame.status == RainResourceStatus.LOADING,
                playing = playing,
                accent = accent,
                onTogglePlay = { playing = !playing },
                onSelectFrame = { index ->
                    playing = false
                    onSelectFrame(index)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 14.dp, end = 14.dp, bottom = sheetHeight + 10.dp),
            )
        }

        RainPointBottomSheet(
            state = state,
            pointModel = pointModel,
            expanded = sheetExpanded,
            height = sheetHeight,
            accent = accent,
            onToggle = { sheetExpanded = !sheetExpanded },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ForecastTopHud(
    state: RainHostState,
    pointModel: RainPointUiModel?,
    accent: Color,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val location = state.location?.label ?: "目前位置"
    val summary = pointModel?.headline ?: when (state.pointForecast.status) {
        RainResourceStatus.LOADING -> "正在取得定點雨量"
        RainResourceStatus.ERROR -> "定點雨量暫時無法更新"
        else -> "香港定點雨量"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xF20A0A0A))
            .padding(start = 18.dp, end = 14.dp, top = 13.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "香港定點雨量",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = "$location · $summary",
                color = RAIN_MUTED,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            HudActionButton("‹", accent, onBack)
            HudActionButton("↻", accent, onRefresh)
            HudActionButton("≡", accent, onToggleDetails)
        }
    }
}

@Composable
private fun HudActionButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Color(0xFF0D0D0D))
            .border(1.dp, Color(0xFF3A3A3A))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (label == "↻") accent else Color.White,
            fontSize = if (label == "≡") 20.sp else 24.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun ForecastDataStatusChip(
    state: RainHostState,
    modifier: Modifier = Modifier,
) {
    val (dot, text) = when {
        state.forecast.status == RainResourceStatus.LOADING -> Color(0xFFFFB300) to "正在更新預報資料"
        state.forecast.status == RainResourceStatus.ERROR -> Color(0xFFEF5350) to "預報資料暫時異常"
        state.forecast.isStale || state.forecastFrame.isStale -> Color(0xFFFFB300) to "正在使用較舊預報資料"
        else -> Color(0xFF35D47A) to "預報資料更新正常"
    }
    Row(
        modifier = modifier
            .background(Color(0xDD0A0A0A))
            .border(1.dp, Color(0xFF4A4A4A))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(dot, RoundedCornerShape(50)))
        Text(text, color = Color.White.copy(alpha = 0.86f), fontSize = 11.sp)
    }
}

@Composable
private fun ForecastMapCanvas(
    frame: RainForecastFrame,
    location: LocationInfo?,
    radiusKm: Int,
    markerColour: Color,
    modifier: Modifier = Modifier,
) {
    val rainImage = remember(frame) { frame.toRainImageBitmap() }
    val renderBounds = remember(frame.grid) { forecastRenderBounds(frame.grid) }
    var viewScale by rememberSaveable { mutableStateOf(FORECAST_DEFAULT_VIEW_SCALE) }
    val viewport = remember(renderBounds, viewScale) {
        forecastViewportBounds(renderBounds, viewScale)
    }
    val tileSpecs = remember(viewport) { forecastBasemapTiles(viewport) }
    var tileImages by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }

    LaunchedEffect(tileSpecs) {
        val missingTiles = tileSpecs.filterNot { tileImages.containsKey(it.key) }
        if (missingTiles.isEmpty()) return@LaunchedEffect
        val loadedTiles = coroutineScope {
            missingTiles.map { spec ->
                async {
                    val bitmap = ForecastTileLoader.load(spec)
                    spec.key to bitmap?.asImageBitmap()
                }
            }.awaitAll().mapNotNull { (key, image) -> image?.let { key to it } }.toMap()
        }
        if (loadedTiles.isNotEmpty()) tileImages = tileImages + loadedTiles
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

            location?.let { point ->
                if (
                    point.latitude in viewport.south..viewport.north &&
                    point.longitude in viewport.west..viewport.east
                ) {
                    val world = webMercatorPoint(point.latitude, point.longitude, FORECAST_BASEMAP_ZOOM)
                    val center = Offset(screenX(world.x), screenY(world.y))
                    val lonDelta = radiusKm.toDouble() /
                        (111.32 * cos(Math.toRadians(point.latitude)).coerceAtLeast(0.2))
                    val radiusWorld = webMercatorPoint(
                        point.latitude,
                        point.longitude + lonDelta,
                        FORECAST_BASEMAP_ZOOM,
                    )
                    val radiusPx = abs(screenX(radiusWorld.x) - center.x).coerceAtLeast(8.dp.toPx())
                    drawCircle(markerColour.copy(alpha = 0.055f), radius = radiusPx, center = center)
                    drawCircle(
                        markerColour.copy(alpha = 0.72f),
                        radius = radiusPx,
                        center = center,
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(8.dp.toPx(), 7.dp.toPx()),
                            ),
                        ),
                    )
                    drawCircle(Color.White, radius = 10.dp.toPx(), center = center)
                    drawCircle(markerColour, radius = 6.8.dp.toPx(), center = center)
                }
            }
        }

        ForecastMapZoomControls(
            viewScale = viewScale,
            onZoomOut = {
                viewScale = (viewScale - 0.12).coerceAtLeast(FORECAST_MIN_VIEW_SCALE)
            },
            onZoomIn = {
                viewScale = (viewScale + 0.12).coerceAtMost(FORECAST_MAX_VIEW_SCALE)
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 110.dp),
        )
    }
}

@Composable
private fun ForecastMapZoomControls(
    viewScale: Double,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ForecastMapControl(
            label = "+",
            enabled = viewScale < FORECAST_MAX_VIEW_SCALE - 0.001,
            onClick = onZoomIn,
        )
        ForecastMapControl(
            label = "−",
            enabled = viewScale > FORECAST_MIN_VIEW_SCALE + 0.001,
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
            Text("兩小時預報 · ${timeline.cadenceMinutes}分鐘步進", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.size(7.dp))
            Text(
                "mm / ${timeline.accumulationMinutes}分鐘",
                color = Color(0xFFB8DDEA),
                fontSize = 10.sp,
                modifier = Modifier
                    .border(1.dp, accent.copy(alpha = 0.48f))
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
        val preload = if (
            timeline.source == RainForecastSource.SWIRLS &&
            timeline.loadedFrameCount < timeline.frames.size
        ) {
            " · 背景預載中"
        } else {
            ""
        }
        Text(
            text = "$source · 每${timeline.cadenceMinutes}分鐘一格 · 每格為${timeline.accumulationMinutes}分鐘累積$preload · © OSM © CARTO",
            color = RAIN_MUTED,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (frameLoading) {
            Text("正在準備選定時段，地圖保持目前畫面…", color = accent, fontSize = 8.sp)
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
                .background(Color(0xFF101010))
                .border(1.dp, accent.copy(alpha = 0.65f))
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
                        .background(Color(0xFF101010))
                        .border(1.dp, if (selected) accent else Color(0xFF444444))
                        .clickable { onSelectFrame(index) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        formatForecastTime(slot.validTime),
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                    )
                    Text(
                        "+${slot.leadMinutes} 分",
                        color = if (selected) accent else RAIN_MUTED,
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
private fun RainPointBottomSheet(
    state: RainHostState,
    pointModel: RainPointUiModel?,
    expanded: Boolean,
    height: Dp,
    accent: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val location = state.location?.label ?: "目前位置"
    val headline = pointModel?.headline ?: when (state.pointForecast.status) {
        RainResourceStatus.LOADING -> "正在載入定點降雨…"
        RainResourceStatus.ERROR -> "定點降雨暫時無法使用"
        else -> "未來兩小時定點降雨"
    }
    val total = pointModel?.total ?: "-- mm"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                color = Color(0xFA070707),
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            )
            .clickable(onClick = onToggle)
            .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 48.dp, height = 4.dp)
                .background(Color(0xFF5A5A5A), RoundedCornerShape(50)),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(location, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    headline,
                    color = RAIN_MUTED,
                    fontSize = 11.sp,
                    maxLines = if (expanded) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = total,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("最高時段  ${pointModel?.peak ?: "-- mm"}", color = Color.White.copy(alpha = 0.80f), fontSize = 10.sp)
                Text("開始下雨  ${pointModel?.rainStart ?: "--"}", color = Color.White.copy(alpha = 0.80f), fontSize = 10.sp)
            }
            Spacer(Modifier.height(10.dp))
            pointModel?.periods?.take(4)?.forEach { period ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(period.time, color = RAIN_MUTED, fontSize = 10.sp, modifier = Modifier.weight(0.8f))
                    Text(period.amount, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(0.8f))
                    Text(period.nearby, color = RAIN_MUTED, fontSize = 9.sp, modifier = Modifier.weight(1.5f))
                }
            }
            state.pointForecast.errorMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Color(0xFFFFB300), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "點擊收起 · 附近 ${state.pointRequest?.radiusKm ?: RainHostViewModel.DEFAULT_POINT_RADIUS_KM} km",
                color = accent,
                fontSize = 9.sp,
            )
        }
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
    private val cache = LruCache<String, Bitmap>(72)

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
