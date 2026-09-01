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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.weather.metro.ui.theme.LocalReduceMotion

/**
 * Shared press-response language for Weather Metro / Visual V2.
 *
 * The base V1.1 modifier only observes [MutableInteractionSource]. V2.1 adds an optional pointer
 * observer for Glass surfaces. The observer runs on [PointerEventPass.Initial] and never consumes a
 * pointer change, so HorizontalPager and MapLibre remain responsible for gesture arbitration.
 */
enum class MetroPressPreset(
    internal val pressedScaleX: Float,
    internal val pressedScaleY: Float,
    internal val pressInMillis: Int,
    internal val releaseDampingRatio: Float,
    internal val releaseStiffness: Float,
) {
    CompactControl(
        pressedScaleX = 0.965f,
        pressedScaleY = 0.900f,
        pressInMillis = 64,
        releaseDampingRatio = 0.50f,
        releaseStiffness = Spring.StiffnessMedium,
    ),

    Chip(
        pressedScaleX = 0.975f,
        pressedScaleY = 0.920f,
        pressInMillis = 68,
        releaseDampingRatio = 0.52f,
        releaseStiffness = Spring.StiffnessMedium,
    ),

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

internal data class MetroDirectionalTransform(
    val scaleX: Float,
    val scaleY: Float,
    val rotationX: Float,
    val rotationY: Float,
    val translationXDp: Float,
    val translationYDp: Float,
    val originX: Float,
    val originY: Float,
)

/** Small negative deformation is intentional so release can overshoot past rest. */
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
 * Converts touch origin + spring progress into a bounded Glass-surface transform.
 *
 * Touch coordinates are normalized to 0..1. The effect is deliberately subtle: the material should
 * appear to yield toward the finger, not behave like a freely rotating 3D card.
 */
internal fun metroDirectionalTransform(
    preset: MetroPressPreset,
    progress: Float,
    touchX: Float,
    touchY: Float,
): MetroDirectionalTransform {
    val deformation = progress.coerceIn(-0.20f, 1f)
    val x = touchX.coerceIn(0f, 1f)
    val y = touchY.coerceIn(0f, 1f)
    val horizontal = (x - 0.5f) * 2f
    val vertical = (y - 0.5f) * 2f
    val scale = metroPressScale(preset, deformation)

    return MetroDirectionalTransform(
        scaleX = scale.x,
        scaleY = scale.y,
        rotationX = vertical * 1.6f * deformation,
        rotationY = -horizontal * 2.6f * deformation,
        translationXDp = horizontal * 2.8f * deformation,
        translationYDp = 1.4f * deformation,
        originX = x,
        originY = y,
    )
}

/** Base non-directional press response retained for flat surfaces and compact controls. */
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

/**
 * Glass V2.1 press response with touch-origin tilt and displacement.
 *
 * Pointer events are observed only; no change is consumed. Once Clickable/Pager cancels the pressed
 * interaction because a gesture becomes a drag, progress springs back to rest while Pager keeps the
 * gesture. This is the key safety boundary for introducing directional motion on the home surface.
 */
@Composable
fun Modifier.metroDirectionalPressMotion(
    interactionSource: MutableInteractionSource,
    preset: MetroPressPreset = MetroPressPreset.Tile,
    enabled: Boolean = true,
): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val pressed by interactionSource.collectIsPressedAsState()
    val progress = remember { Animatable(0f) }
    var touchOrigin by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

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

    val observed = if (!reduceMotion && enabled) {
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.pressed } ?: continue
                    if (size.width > 0 && size.height > 0) {
                        touchOrigin = Offset(
                            x = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f),
                            y = (change.position.y / size.height.toFloat()).coerceIn(0f, 1f),
                        )
                    }
                }
            }
        }
    } else {
        this
    }

    return observed.graphicsLayer {
        val transform = metroDirectionalTransform(
            preset = preset,
            progress = progress.value,
            touchX = touchOrigin.x,
            touchY = touchOrigin.y,
        )
        transformOrigin = TransformOrigin(transform.originX, transform.originY)
        scaleX = transform.scaleX
        scaleY = transform.scaleY
        rotationX = transform.rotationX
        rotationY = transform.rotationY
        translationX = transform.translationXDp.dp.toPx()
        translationY = transform.translationYDp.dp.toPx()
    }
}
