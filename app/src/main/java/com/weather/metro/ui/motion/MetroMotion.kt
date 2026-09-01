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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.weather.metro.ui.theme.LocalReduceMotion

/**
 * Shared press-response language for Weather Metro.
 *
 * V1.1 deliberately observes the existing [MutableInteractionSource] rather than owning pointer
 * input. Pager and MapLibre therefore keep full gesture arbitration while flat Metro surfaces gain
 * a more visible bottom-anchored squash and spring settle.
 */
enum class MetroPressPreset(
    internal val pressedScaleX: Float,
    internal val pressedScaleY: Float,
    internal val pressInMillis: Int,
    internal val releaseDampingRatio: Float,
    internal val releaseStiffness: Float,
) {
    /** Small selectors: compact travel, but enough vertical compression to be readable on flat UI. */
    CompactControl(
        pressedScaleX = 0.965f,
        pressedScaleY = 0.900f,
        pressInMillis = 64,
        releaseDampingRatio = 0.50f,
        releaseStiffness = Spring.StiffnessMedium,
    ),

    /** Chips and medium controls: retain density while making the press materially visible. */
    Chip(
        pressedScaleX = 0.975f,
        pressedScaleY = 0.920f,
        pressInMillis = 68,
        releaseDampingRatio = 0.52f,
        releaseStiffness = Spring.StiffnessMedium,
    ),

    /** Flat Metro tiles: bottom-anchored vertical squash is more legible than uniform scale. */
    Tile(
        pressedScaleX = 0.985f,
        pressedScaleY = 0.930f,
        pressInMillis = 72,
        releaseDampingRatio = 0.50f,
        releaseStiffness = Spring.StiffnessMedium,
    ),
}

internal data class MetroPressScale(
    val x: Float,
    val y: Float,
)

/**
 * Pure deformation calculation kept separate so motion tuning can be regression-tested.
 *
 * A small negative range is intentional: spring release can travel slightly past rest, producing
 * the visible overshoot that was previously lost by clamping progress to 0..1.
 */
internal fun metroPressScale(
    preset: MetroPressPreset,
    progress: Float,
): MetroPressScale {
    val deformation = progress.coerceIn(-0.20f, 1f)
    return MetroPressScale(
        x = 1f - ((1f - preset.pressedScaleX) * deformation),
        y = 1f - ((1f - preset.pressedScaleY) * deformation),
    )
}

/**
 * Applies Weather Metro's flexible press response without installing any pointer-input handler.
 *
 * Press-in is intentionally short. Release uses an under-damped spring and a bottom transform
 * origin so a rectangular Metro tile feels compressed rather than merely scaled smaller.
 * Reduced-motion keeps the surface at rest.
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
        val scale = metroPressScale(preset, progress.value)
        transformOrigin = TransformOrigin(0.5f, 1f)
        scaleX = scale.x
        scaleY = scale.y
    }
}
