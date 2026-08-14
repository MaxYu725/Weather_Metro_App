package com.weather.metro.ui.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsHostLifecycleTest {
    @Test
    fun `tool active only when page is selected and app is resumed`() {
        assertTrue(toolHostIsActive(pageActive = true, lifecycleResumed = true))
        assertFalse(toolHostIsActive(pageActive = true, lifecycleResumed = false))
        assertFalse(toolHostIsActive(pageActive = false, lifecycleResumed = true))
        assertFalse(toolHostIsActive(pageActive = false, lifecycleResumed = false))
    }
}
