package com.weather.metro.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PageColoursTest {
    @Test
    fun `default pages use distinct Metro colours`() {
        val colours = PageColours()
        val values = PageColourSlot.entries.map(colours::colour)

        assertEquals(listOf("current", "forecast", "live weather", "settings"), PageColourSlot.entries.map { it.label })
        assertEquals(PageColourSlot.entries.size, values.distinct().size)
        assertEquals(DefaultPageColours.CURRENT, colours.currentArgb)
        assertEquals(DefaultPageColours.FORECAST, colours.forecastArgb)
        assertEquals(DefaultPageColours.TOOLS, colours.toolsArgb)
        assertEquals(DefaultPageColours.SETTINGS, colours.settingsArgb)
    }

    @Test
    fun `changing one page colour leaves every other page unchanged`() {
        val original = PageColours()
        val changed = original.withColour(PageColourSlot.FORECAST, 0xFFE51400)

        assertEquals(0xFFE51400, changed.forecastArgb)
        assertNotEquals(original.forecastArgb, changed.forecastArgb)
        PageColourSlot.entries
            .filterNot { it == PageColourSlot.FORECAST }
            .forEach { slot -> assertEquals(original.colour(slot), changed.colour(slot)) }
    }
}
