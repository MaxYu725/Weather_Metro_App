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
    fun zoomOutViewportShowsMoreGeographyWithoutChangingGridBounds() {
        val gridBounds = RainGridBounds(
            north = 22.7,
            south = 22.0,
            east = 114.6,
            west = 113.7,
        )

        val defaultViewport = forecastViewportBounds(gridBounds, FORECAST_DEFAULT_VIEW_SCALE)
        val zoomedOut = forecastViewportBounds(gridBounds, FORECAST_MIN_VIEW_SCALE)
        val zoomedIn = forecastViewportBounds(gridBounds, FORECAST_MAX_VIEW_SCALE)

        val defaultLatSpan = defaultViewport.north - defaultViewport.south
        val defaultLonSpan = defaultViewport.east - defaultViewport.west
        assertTrue(zoomedOut.north - zoomedOut.south > defaultLatSpan)
        assertTrue(zoomedOut.east - zoomedOut.west > defaultLonSpan)
        assertTrue(zoomedIn.north - zoomedIn.south < defaultLatSpan)
        assertTrue(zoomedIn.east - zoomedIn.west < defaultLonSpan)
    }

    @Test
    fun webMercatorPlacesNorthAboveSouthAndProducesBasemapTiles() {
        val north = webMercatorPoint(22.6, 114.1, FORECAST_BASEMAP_ZOOM)
        val south = webMercatorPoint(22.1, 114.1, FORECAST_BASEMAP_ZOOM)
        assertTrue(north.y < south.y)

        val tiles = forecastBasemapTiles(
            RainGridBounds(north = 22.7, south = 22.0, east = 114.6, west = 113.7),
        )
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.size < 30)
        assertTrue(tiles.all { it.url.contains("basemaps.cartocdn.com/dark_all") })
    }

    @Test
    fun forecastTimeUsesHongKongTime() {
        assertEquals("12:30", formatForecastTime("2026-08-14T04:30:00Z"))
    }
}
