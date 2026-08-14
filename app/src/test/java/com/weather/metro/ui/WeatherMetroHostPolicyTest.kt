package com.weather.metro.ui

import com.weather.metro.data.settings.PageColourSlot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherMetroHostPolicyTest {
    @Test
    fun `only weather content pages require normal weather load state`() {
        assertTrue(pageRequiresWeatherData(PageColourSlot.CURRENT))
        assertTrue(pageRequiresWeatherData(PageColourSlot.FORECAST))
        assertFalse(pageRequiresWeatherData(PageColourSlot.TOOLS))
        assertFalse(pageRequiresWeatherData(PageColourSlot.SETTINGS))
    }
}
