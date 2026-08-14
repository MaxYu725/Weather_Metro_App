package com.weather.metro.ui.rain

import com.weather.metro.domain.rain.RainForecastGrid
import com.weather.metro.domain.rain.RainGridBounds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal const val FORECAST_BASEMAP_ZOOM = 10
internal const val FORECAST_TILE_SIZE_PX = 256.0
internal const val FORECAST_DEFAULT_VIEW_SCALE = 0.78
internal const val FORECAST_MIN_VIEW_SCALE = 0.62
internal const val FORECAST_MAX_VIEW_SCALE = 1.10
private const val MAX_MERCATOR_LAT = 85.05112878

internal data class MercatorPoint(
    val x: Double,
    val y: Double,
)

internal data class ForecastTileSpec(
    val zoom: Int,
    val x: Int,
    val y: Int,
) {
    val key: String get() = "$zoom/$x/$y"
    val url: String
        get() {
            val subdomains = charArrayOf('a', 'b', 'c', 'd')
            val subdomain = subdomains[Math.floorMod(x + y, subdomains.size)]
            return "https://$subdomain.basemaps.cartocdn.com/dark_all/$zoom/$x/$y.png"
        }
}

internal fun webMercatorPoint(latitude: Double, longitude: Double, zoom: Int): MercatorPoint {
    require(zoom in 0..22) { "Unsupported map zoom" }
    val lat = latitude.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
    val lon = longitude.coerceIn(-180.0, 180.0)
    val worldSize = FORECAST_TILE_SIZE_PX * (1 shl zoom)
    val x = (lon + 180.0) / 360.0 * worldSize
    val sinLat = sin(Math.toRadians(lat))
    val y = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * worldSize
    return MercatorPoint(x = x, y = y)
}

/**
 * Convert centre-point grid axes into display edges using the adjacent observed axes.
 * This deliberately does not reconstruct an axis from one minimum step.
 */
internal fun forecastRenderBounds(grid: RainForecastGrid): RainGridBounds {
    val latitudes = grid.latitudes
    val longitudes = grid.longitudes
    if (latitudes.size < 2 || longitudes.size < 2) return grid.bounds

    val northHalf = abs(latitudes[0] - latitudes[1]) / 2.0
    val southHalf = abs(latitudes[latitudes.lastIndex - 1] - latitudes.last()) / 2.0
    val westHalf = abs(longitudes[1] - longitudes[0]) / 2.0
    val eastHalf = abs(longitudes.last() - longitudes[longitudes.lastIndex - 1]) / 2.0

    return RainGridBounds(
        north = max(latitudes.first(), latitudes.last()) + northHalf,
        south = min(latitudes.first(), latitudes.last()) - southHalf,
        east = max(longitudes.first(), longitudes.last()) + eastHalf,
        west = min(longitudes.first(), longitudes.last()) - westHalf,
    )
}

internal fun paddedForecastBounds(bounds: RainGridBounds, fraction: Double = 0.045): RainGridBounds {
    val latSpan = (bounds.north - bounds.south).coerceAtLeast(0.01)
    val lonSpan = (bounds.east - bounds.west).coerceAtLeast(0.01)
    val latPadding = latSpan * fraction
    val lonPadding = lonSpan * fraction
    return RainGridBounds(
        north = (bounds.north + latPadding).coerceAtMost(MAX_MERCATOR_LAT),
        south = (bounds.south - latPadding).coerceAtLeast(-MAX_MERCATOR_LAT),
        east = (bounds.east + lonPadding).coerceAtMost(180.0),
        west = (bounds.west - lonPadding).coerceAtLeast(-180.0),
    )
}

/**
 * Scale the visible viewport around the forecast grid centre.
 * A value below 1.0 zooms out and shows more surrounding geography; above 1.0 zooms in.
 */
internal fun forecastViewportBounds(
    bounds: RainGridBounds,
    viewScale: Double = FORECAST_DEFAULT_VIEW_SCALE,
): RainGridBounds {
    val base = paddedForecastBounds(bounds, fraction = 0.06)
    val scale = viewScale.coerceIn(FORECAST_MIN_VIEW_SCALE, FORECAST_MAX_VIEW_SCALE)
    val centerLat = (base.north + base.south) / 2.0
    val centerLon = (base.east + base.west) / 2.0
    val halfLat = (base.north - base.south).coerceAtLeast(0.01) / 2.0 / scale
    val halfLon = (base.east - base.west).coerceAtLeast(0.01) / 2.0 / scale
    return RainGridBounds(
        north = (centerLat + halfLat).coerceAtMost(MAX_MERCATOR_LAT),
        south = (centerLat - halfLat).coerceAtLeast(-MAX_MERCATOR_LAT),
        east = (centerLon + halfLon).coerceAtMost(180.0),
        west = (centerLon - halfLon).coerceAtLeast(-180.0),
    )
}

internal fun forecastBasemapTiles(
    bounds: RainGridBounds,
    zoom: Int = FORECAST_BASEMAP_ZOOM,
): List<ForecastTileSpec> {
    val northWest = webMercatorPoint(bounds.north, bounds.west, zoom)
    val southEast = webMercatorPoint(bounds.south, bounds.east, zoom)
    val maxTile = (1 shl zoom) - 1
    val startX = floor(northWest.x / FORECAST_TILE_SIZE_PX).toInt().coerceIn(0, maxTile)
    val endX = floor(southEast.x / FORECAST_TILE_SIZE_PX).toInt().coerceIn(0, maxTile)
    val startY = floor(northWest.y / FORECAST_TILE_SIZE_PX).toInt().coerceIn(0, maxTile)
    val endY = floor(southEast.y / FORECAST_TILE_SIZE_PX).toInt().coerceIn(0, maxTile)

    return buildList {
        for (y in min(startY, endY)..max(startY, endY)) {
            for (x in min(startX, endX)..max(startX, endX)) {
                add(ForecastTileSpec(zoom = zoom, x = x, y = y))
            }
        }
    }
}

internal fun rainfallArgb(value: Double): Int {
    if (!value.isFinite() || value < 0.05) return 0x00000000
    val rgba = when {
        value >= 10.0 -> intArrayOf(235, 72, 58, 245)
        value >= 5.0 -> intArrayOf(246, 147, 45, 240)
        value >= 2.0 -> intArrayOf(232, 204, 50, 235)
        value >= 1.0 -> intArrayOf(111, 207, 58, 230)
        value >= 0.5 -> intArrayOf(41, 199, 104, 225)
        value >= 0.2 -> intArrayOf(34, 187, 214, 220)
        else -> intArrayOf(36, 162, 214, 210)
    }
    return (rgba[3] shl 24) or (rgba[0] shl 16) or (rgba[1] shl 8) or rgba[2]
}

internal fun formatForecastTime(value: String): String = runCatching {
    FORECAST_HKT_TIME.format(Instant.parse(value))
}.getOrElse {
    value.replace("T", " ").takeLast(5)
}

private val FORECAST_HKT_TIME: DateTimeFormatter = DateTimeFormatter
    .ofPattern("HH:mm")
    .withZone(ZoneId.of("Asia/Hong_Kong"))
