package com.weather.metro.ui.rain

import com.weather.metro.domain.rain.RainForecastGrid
import com.weather.metro.domain.rain.RainGridBounds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

internal const val FORECAST_TILE_SIZE_PX = 256.0
internal const val FORECAST_DEFAULT_MAP_ZOOM = 15.5
internal const val FORECAST_MIN_MAP_ZOOM = 10.0
internal const val FORECAST_MAX_MAP_ZOOM = 18.0
private const val MAX_MERCATOR_LAT = 85.05112878

internal data class MercatorPoint(
    val x: Double,
    val y: Double,
)

internal data class ForecastMapCenter(
    val latitude: Double,
    val longitude: Double,
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

internal fun inverseWebMercatorPoint(point: MercatorPoint, zoom: Int): ForecastMapCenter {
    require(zoom in 0..22) { "Unsupported map zoom" }
    val worldSize = FORECAST_TILE_SIZE_PX * (1 shl zoom)
    val longitude = point.x / worldSize * 360.0 - 180.0
    val n = PI - (2.0 * PI * point.y / worldSize)
    val latitude = Math.toDegrees(atan(sinh(n))).coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
    return ForecastMapCenter(
        latitude = latitude,
        longitude = longitude.coerceIn(-180.0, 180.0),
    )
}

internal fun forecastTileZoom(mapZoom: Double): Int =
    floor(mapZoom.coerceIn(FORECAST_MIN_MAP_ZOOM, FORECAST_MAX_MAP_ZOOM)).toInt().coerceIn(0, 22)

internal fun forecastVisualScale(mapZoom: Double): Double {
    val zoom = mapZoom.coerceIn(FORECAST_MIN_MAP_ZOOM, FORECAST_MAX_MAP_ZOOM)
    return 2.0.pow(zoom - forecastTileZoom(zoom))
}

internal fun forecastMapCenterAfterPan(
    latitude: Double,
    longitude: Double,
    mapZoom: Double,
    panX: Float,
    panY: Float,
): ForecastMapCenter {
    val tileZoom = forecastTileZoom(mapZoom)
    val scale = forecastVisualScale(mapZoom)
    val center = webMercatorPoint(latitude, longitude, tileZoom)
    return inverseWebMercatorPoint(
        MercatorPoint(
            x = center.x - panX / scale,
            y = center.y - panY / scale,
        ),
        tileZoom,
    )
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

internal fun forecastBasemapTiles(
    centerLatitude: Double,
    centerLongitude: Double,
    mapZoom: Double,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
): List<ForecastTileSpec> {
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return emptyList()
    val tileZoom = forecastTileZoom(mapZoom)
    val scale = forecastVisualScale(mapZoom)
    val center = webMercatorPoint(centerLatitude, centerLongitude, tileZoom)
    val halfWidth = viewportWidthPx / 2.0 / scale
    val halfHeight = viewportHeightPx / 2.0 / scale
    val maxTile = (1 shl tileZoom) - 1
    val startX = (floor((center.x - halfWidth) / FORECAST_TILE_SIZE_PX).toInt() - 1).coerceIn(0, maxTile)
    val endX = (floor((center.x + halfWidth) / FORECAST_TILE_SIZE_PX).toInt() + 1).coerceIn(0, maxTile)
    val startY = (floor((center.y - halfHeight) / FORECAST_TILE_SIZE_PX).toInt() - 1).coerceIn(0, maxTile)
    val endY = (floor((center.y + halfHeight) / FORECAST_TILE_SIZE_PX).toInt() + 1).coerceIn(0, maxTile)

    return buildList {
        for (y in min(startY, endY)..max(startY, endY)) {
            for (x in min(startX, endX)..max(startX, endX)) {
                add(ForecastTileSpec(zoom = tileZoom, x = x, y = y))
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
