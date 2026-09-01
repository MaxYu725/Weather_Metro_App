package com.weather.metro.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.weather.metro.ui.theme.LocalReduceMotion

/**
 * Shared press-response language for Weather Metro.
 *
 * V1 deliberately observes the existing [MutableInteractionSource] rather than owning pointer
 * input. This keeps Pager and MapLibre gesture arbitration unchanged while replacing the old
 * per-component uniform press-scale tweens with a controlled squash + spring return.
 */
enum class MetroPressPreset(
    internal val pressedScaleX: Float,
    internal val pressedScaleY: Float,
    internal val pressInMillis: Int,
    internal val releaseDampingRatio: Float,
    internal val releaseStiffness: Float,
) {
    /** Small selectors and compact controls: clear tactile compression without large travel. */
    CompactControl(
        pressedScaleX = 0.965f,
        pressedScaleY = 0.920f,
        pressInMillis = 72,
        releaseDampingRatio = 0.62f,
        releaseStiffness = Spring.StiffnessMedium,
    ),

    /** Chips and medium controls: slightly quieter deformation for dense tool chrome. */
    Chip(
        pressedScaleX = 0.975f,
        pressedScaleY = 0.940f,
        pressInMillis = 78,
        releaseDampingRatio = 0.66f,
        releaseStiffness = Spring.StiffnessMedium,
    ),

    /** Large tiles: preserve layout stability while still giving a soft material response. */
    Tile(
        pressedScaleX = 0.985f,
        pressedScaleY = 0.965f,
        pressInMillis = 84,
        releaseDampingRatio = 0.70f,
        releaseStiffness = Spring.StiffnessMediumLow,
    ),
}

/**
 * Applies Weather Metro's flexible press response without installing any pointer-input handler.
 *
 * Press-in is intentionally short and controlled. Release uses a spring so the surface can settle
 * naturally instead of replaying a symmetric tween. Reduced-motion keeps the surface at rest.
 */
@Composable
fun Modifier.metroPressMotion(
    interactionSource: MutableInteractionSource,
    preset: MetroPressPreset = MetroPressPreset.Tile,
    enabled: Boolean = true,
): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val pressed by interactionSource.collectIsPressedAsState()
    val progress = remember { Animatable(0f) }

    LaunchedEffect(pressed, reduceMotion, enabled, preset) {
        when {
            reduceMotion || !enabled -> progress.snapTo(0f)
            pressed -> progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = preset.pressInMillis),
            )
            else -> progress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = preset.releaseDampingRatio,
                    stiffness = preset.releaseStiffness,
                ),
            )
        }
    }

    return graphicsLayer {
        val deformation = progress.value
        scaleX = 1f - ((1f - preset.pressedScaleX) * deformation)
        scaleY = 1f - ((1f - preset.pressedScaleY) * deformation)
    }
}
