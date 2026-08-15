package com.weather.metro.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.R
import com.weather.metro.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.io.FileOutputStream

private const val BACKDROP_CACHE_VERSION = 1
private const val BACKDROP_MAX_EDGE_PX = 1_600
private const val BACKDROP_MIN_EDGE_PX = 320
private const val BACKDROP_REFRESH_MS = 7L * 24L * 60L * 60L * 1_000L

internal data class BackdropSnapshotSize(val width: Int, val height: Int)

internal fun backdropSnapshotSize(width: Int, height: Int): BackdropSnapshotSize {
    if (width <= 0 || height <= 0) return BackdropSnapshotSize(0, 0)
    val longEdge = maxOf(width, height)
    val scale = minOf(1f, BACKDROP_MAX_EDGE_PX.toFloat() / longEdge)
    return BackdropSnapshotSize(
        width = (width * scale).toInt().coerceAtLeast(BACKDROP_MIN_EDGE_PX),
        height = (height * scale).toInt().coerceAtLeast(BACKDROP_MIN_EDGE_PX),
    )
}

@Composable
fun HongKongBackdrop(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val snapshotSize = remember(containerSize) {
        backdropSnapshotSize(containerSize.width, containerSize.height)
    }
    val backdrop by produceState<Bitmap?>(
        initialValue = null,
        context,
        snapshotSize,
    ) {
        if (snapshotSize.width <= 0 || snapshotSize.height <= 0) return@produceState

        val cacheFile = backdropCacheFile(context.filesDir, snapshotSize)
        val cached = withContext(Dispatchers.IO) { loadBackdrop(cacheFile) }
        if (cached != null) value = cached
        val cacheFresh = cached != null &&
            System.currentTimeMillis() - cacheFile.lastModified() < BACKDROP_REFRESH_MS
        if (cacheFresh) return@produceState

        val styleJson = withContext(Dispatchers.IO) {
            context.resources.openRawResource(R.raw.weather_metro_dark_basemap)
                .bufferedReader()
                .use { it.readText() }
        }
        MapLibre.getInstance(context.applicationContext)
        val hongKongRegion = LatLngBounds.Builder()
            .include(LatLng(22.64, 113.82))
            .include(LatLng(21.86, 114.52))
            .build()
        val horizontalPadding = (snapshotSize.width * 0.045f).toInt()
        val verticalPadding = (snapshotSize.height * 0.035f).toInt()
        val options = MapSnapshotter.Options(snapshotSize.width, snapshotSize.height)
            .withStyleBuilder(Style.Builder().fromJson(styleJson))
            .withRegion(hongKongRegion)
            .withPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            .withLogo(false)
            .withAttribution(false)
        val snapshotter = MapSnapshotter(context.applicationContext, options)
        val rendered = try {
            suspendCancellableCoroutine { continuation ->
                snapshotter.start(
                    { snapshot ->
                        if (continuation.isActive) continuation.resume(snapshot.bitmap) { _, _, _ -> }
                    },
                    { _ ->
                        if (continuation.isActive) continuation.resume(null) { _, _, _ -> }
                    },
                )
                continuation.invokeOnCancellation { snapshotter.cancel() }
            }
        } finally {
            snapshotter.cancel()
        }
        if (rendered != null) {
            value = rendered
            withContext(Dispatchers.IO) { saveBackdrop(cacheFile, rendered) }
        }
    }
    val imageAlpha by animateFloatAsState(
        targetValue = if (backdrop == null) 0f else 1f,
        animationSpec = tween(if (reduceMotion) 120 else 700),
        label = "Hong Kong backdrop reveal",
    )

    Box(
        modifier = modifier
            .onSizeChanged { containerSize = it }
            .background(Color(0xFF080B0D)),
    ) {
        backdrop?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = imageAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.38f),
                        0.40f to Color.Black.copy(alpha = 0.20f),
                        1f to Color.Black.copy(alpha = 0.58f),
                    ),
                )
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.34f)),
                            center = Offset(size.width * 0.52f, size.height * 0.42f),
                            radius = size.maxDimension * 0.72f,
                        ),
                    )
                },
        )
    }
}

@Composable
fun HongKongMapAttribution(modifier: Modifier = Modifier) {
    Text(
        text = "© OpenStreetMap · © CARTO",
        color = Color.White.copy(alpha = 0.46f),
        fontSize = 8.sp,
        modifier = modifier.padding(end = 7.dp, bottom = 3.dp),
    )
}

private fun backdropCacheFile(filesDir: File, size: BackdropSnapshotSize): File =
    File(filesDir, "map_backdrops/hong_kong_v${BACKDROP_CACHE_VERSION}_${size.width}x${size.height}.webp")

private fun loadBackdrop(file: File): Bitmap? {
    if (!file.isFile) return null
    return runCatching {
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()
}

@Suppress("DEPRECATION")
private fun saveBackdrop(file: File, bitmap: Bitmap) {
    runCatching {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.WEBP, 88, output))
        }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }
}
