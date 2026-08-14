package com.weather.metro.ui.rain

import com.weather.metro.domain.rain.RainForecastGrid
import com.weather.metro.domain.rain.RainGridBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RainForecastMapMathTest {
    @Test
    fun rainfallScaleKeepsRainTrackThresholds() {
        assertEquals(0x00000000, rainfallArgb(0.049))
        assertEquals(0xD224A2D6.toInt(), rainfallArgb(0.05))
        assertEquals(0xDC22BBD6.toInt(), rainfallArgb(0.2))
        assertEquals(0xE66FCF3A.toInt(), rainfallArgb(1.0))
        assertEquals(0xF5EB483A.toInt(), rainfallArgb(12.0))
    }

    @Test
    fun renderBoundsUseObservedNeighbourAxesInsteadOfMinimumStep() {
        val grid = RainForecastGrid(
            rows = 3,
            cols = 3,
            cellCount = 9,
            orientation = "row-major-north-to-south-west-to-east",
            latitudes = doubleArrayOf(22.300, 22.281, 22.261),
            longitudes = doubleArrayOf(114.000, 114.019, 114.039),
            stepLat = null,
            stepLon = null,
            bounds = RainGridBounds(north = 22.300, south = 22.261, east = 114.039, west = 114.000),
        )

        val bounds = forecastRenderBounds(grid)

        assertEquals(22.3095, bounds.north, 0.000001)
        assertEquals(22.2510, bounds.south, 0.000001)
        assertEquals(113.9905, bounds.west, 0.000001)
        assertEquals(114.0490, bounds.east, 0.000001)
    }

    @Test
    fun defaultMapZoomIsStreetLevelAndMercatorRoundTrips() {
        assertTrue(FORECAST_DEFAULT_MAP_ZOOM in 15.0..16.0)
        val point = webMercatorPoint(22.4967, 114.1412, forecastTileZoom(FORECAST_DEFAULT_MAP_ZOOM))
        val restored = inverseWebMercatorPoint(point, forecastTileZoom(FORECAST_DEFAULT_MAP_ZOOM))
        assertEquals(22.4967, restored.latitude, 0.000001)
        assertEquals(114.1412, restored.longitude, 0.000001)
    }

    @Test
    fun draggingMapRightMovesMapCenterWest() {
        val moved = forecastMapCenterAfterPan(
            latitude = 22.4967,
            longitude = 114.1412,
            mapZoom = FORECAST_DEFAULT_MAP_ZOOM,
            panX = 120f,
            panY = 0f,
        )
        assertTrue(moved.longitude < 114.1412)
        assertEquals(22.4967, moved.latitude, 0.0005)
    }

    @Test
    fun streetViewportProducesBoundedCartoTileSet() {
        val tiles = forecastBasemapTiles(
            centerLatitude = 22.4967,
            centerLongitude = 114.1412,
            mapZoom = FORECAST_DEFAULT_MAP_ZOOM,
            viewportWidthPx = 1080,
            viewportHeightPx = 2200,
        )
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.size < 100)
        assertTrue(tiles.all { it.zoom == 15 })
        assertTrue(tiles.all { it.url.contains("basemaps.cartocdn.com/dark_all") })
    }

    @Test
    fun forecastTimeUsesHongKongTime() {
        assertEquals("12:30", formatForecastTime("2026-08-14T04:30:00Z"))
    }
}
