package com.weather.metro.ui.motion

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroMotionTest {
    @Test
    fun restProgressKeepsIdentityScale() {
        val scale = metroPressScale(MetroPressPreset.CompactControl, 0f)
        assertEquals(1f, scale.x, 0.0001f)
        assertEquals(1f, scale.y, 0.0001f)
    }

    @Test
    fun fullProgressUsesPresetCompression() {
        val scale = metroPressScale(MetroPressPreset.CompactControl, 1f)
        assertEquals(0.965f, scale.x, 0.0001f)
        assertEquals(0.900f, scale.y, 0.0001f)
        assertTrue(scale.y < scale.x)
    }

    @Test
    fun shortTapImpulseStartsAtReadableMinimumWithoutRewindingStrongerPress() {
        assertEquals(METRO_PRESS_IMPULSE, metroPressImpulse(0f), 0.0001f)
        assertEquals(METRO_PRESS_IMPULSE, metroPressImpulse(0.2f), 0.0001f)
        assertEquals(0.8f, metroPressImpulse(0.8f), 0.0001f)
    }

    @Test
    fun releaseOvershootIsSmallAndBounded() {
        val overshoot = metroPressScale(MetroPressPreset.Tile, -1f)
        val fullyPressed = metroPressScale(MetroPressPreset.Tile, 2f)

        assertEquals(1.003f, overshoot.x, 0.0001f)
        assertEquals(1.014f, overshoot.y, 0.0001f)
        assertTrue(overshoot.x > 1f)
        assertTrue(overshoot.y > 1f)
        assertEquals(0.985f, fullyPressed.x, 0.0001f)
        assertEquals(0.930f, fullyPressed.y, 0.0001f)
    }

    @Test
    fun presetsKeepLargeTilesQuieterThanCompactControls() {
        val compact = metroPressScale(MetroPressPreset.CompactControl, 1f)
        val tile = metroPressScale(MetroPressPreset.Tile, 1f)

        assertTrue(tile.x > compact.x)
        assertTrue(tile.y > compact.y)
    }

    @Test
    fun centrePressHasNoDirectionalTilt() {
        val transform = metroDirectionalTransform(
            preset = MetroPressPreset.Tile,
            progress = 1f,
            touchX = 0.5f,
            touchY = 0.5f,
        )

        assertEquals(0f, transform.rotationX, 0.0001f)
        assertEquals(0f, transform.rotationY, 0.0001f)
        assertEquals(0f, transform.translationXDp, 0.0001f)
        assertEquals(1.4f, transform.translationYDp, 0.0001f)
    }

    @Test
    fun rightEdgePressTiltsAndShiftsTowardTouch() {
        val transform = metroDirectionalTransform(
            preset = MetroPressPreset.Tile,
            progress = 1f,
            touchX = 1f,
            touchY = 0.5f,
        )

        assertTrue(transform.rotationY < 0f)
        assertTrue(transform.translationXDp > 0f)
        assertEquals(1f, transform.originX, 0.0001f)
    }

    @Test
    fun directionalTouchCoordinatesAreClamped() {
        val transform = metroDirectionalTransform(
            preset = MetroPressPreset.Tile,
            progress = 1f,
            touchX = -3f,
            touchY = 9f,
        )

        assertEquals(0f, transform.originX, 0.0001f)
        assertEquals(1f, transform.originY, 0.0001f)
        assertTrue(transform.rotationX > 0f)
        assertTrue(transform.rotationY > 0f)
    }

    @Test
    fun directionalOvershootBrieflyReversesDirection() {
        val pressed = metroDirectionalTransform(MetroPressPreset.Tile, 1f, 1f, 0.5f)
        val overshoot = metroDirectionalTransform(MetroPressPreset.Tile, -1f, 1f, 0.5f)

        assertTrue(pressed.translationXDp > 0f)
        assertTrue(overshoot.translationXDp < 0f)
        assertTrue(overshoot.scaleY > 1f)
    }

    @Test
    fun smallSquareTileUsesCompactProfile() {
        assertEquals(
            MetroDirectionalProfile.COMPACT,
            metroDirectionalProfile(widthDp = 112f, heightDp = 106f),
        )
    }

    @Test
    fun wideShortTileKeepsLargeProfile() {
        assertEquals(
            MetroDirectionalProfile.LARGE,
            metroDirectionalProfile(widthDp = 340f, heightDp = 72f),
        )
    }

    @Test
    fun compactProfileAmplifiesDirectionalFeedbackWithoutChangingLargeTuning() {
        val large = metroDirectionalTransform(
            preset = MetroPressPreset.Tile,
            progress = 1f,
            touchX = 1f,
            touchY = 0.8f,
            profile = MetroDirectionalProfile.LARGE,
        )
        val compact = metroDirectionalTransform(
            preset = MetroPressPreset.Tile,
            progress = 1f,
            touchX = 1f,
            touchY = 0.8f,
            profile = MetroDirectionalProfile.COMPACT,
        )

        assertEquals(-2.6f, large.rotationY, 0.0001f)
        assertEquals(2.8f, large.translationXDp, 0.0001f)
        assertTrue(abs(compact.rotationY) > abs(large.rotationY))
        assertTrue(compact.translationXDp > large.translationXDp)
        assertTrue(compact.scaleX < large.scaleX)
        assertTrue(compact.scaleY < large.scaleY)
    }
}
