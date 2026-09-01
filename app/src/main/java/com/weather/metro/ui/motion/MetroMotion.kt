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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.weather.metro.ui.theme.LocalReduceMotion

/**
 * Shared press-response language for Weather Metro / Visual V2.
 *
 * The base V1.1 modifier only observes [MutableInteractionSource]. V2 adds an optional pointer
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

internal enum class MetroDirectionalProfile(
    val scaleXGain: Float,
    val scaleYGain: Float,
    val rotationXDegrees: Float,
    val rotationYDegrees: Float,
    val translationXDp: Float,
    val translationYDp: Float,
) {
    /** Device-tested V2.1 tuning. Keep large weather cards deliberately calm. */
    LARGE(
        scaleXGain = 1f,
        scaleYGain = 1f,
        rotationXDegrees = 1.6f,
        rotationYDegrees = 2.6f,
        translationXDp = 2.8f,
        translationYDp = 1.4f,
    ),

    /** Small square-ish action cards need more travel for the same motion to remain legible. */
    COMPACT(
        scaleXGain = 1.35f,
        scaleYGain = 1.25f,
        rotationXDegrees = 2.2f,
        rotationYDegrees = 4.0f,
        translationXDp = 4.2f,
        translationYDp = 1.8f,
    ),
}

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
 * Classifies only genuinely compact, approximately button-sized tiles as COMPACT.
 * Wide strips stay on the calmer LARGE profile even when short, avoiding excessive perspective.
 */
internal fun metroDirectionalProfile(
    widthDp: Float,
    heightDp: Float,
): MetroDirectionalProfile = if (
    widthDp > 0f &&
    heightDp > 0f &&
    widthDp <= 180f &&
    heightDp <= 120f
) {
    MetroDirectionalProfile.COMPACT
} else {
    MetroDirectionalProfile.LARGE
}

/**
 * Converts touch origin + spring progress into a bounded Glass-surface transform.
 *
 * Touch coordinates are normalized to 0..1. Large cards preserve the device-tested V2.1 values;
 * compact cards receive stronger but still bounded deformation because their shorter edges otherwise
 * make the same normalized movement visually disappear.
 */
internal fun metroDirectionalTransform(
    preset: MetroPressPreset,
    progress: Float,
    touchX: Float,
    touchY: Float,
    profile: MetroDirectionalProfile = MetroDirectionalProfile.LARGE,
): MetroDirectionalTransform {
    val deformation = progress.coerceIn(-0.20f, 1f)
    val x = touchX.coerceIn(0f, 1f)
    val y = touchY.coerceIn(0f, 1f)
    val horizontal = (x - 0.5f) * 2f
    val vertical = (y - 0.5f) * 2f
    val baseScale = metroPressScale(preset, deformation)
    val tunedScaleX = 1f - ((1f - baseScale.x) * profile.scaleXGain)
    val tunedScaleY = 1f - ((1f - baseScale.y) * profile.scaleYGain)

    return MetroDirectionalTransform(
        scaleX = tunedScaleX,
        scaleY = tunedScaleY,
        rotationX = vertical * profile.rotationXDegrees * deformation,
        rotationY = -horizontal * profile.rotationYDegrees * deformation,
        translationXDp = horizontal * profile.translationXDp * deformation,
        translationYDp = profile.translationYDp * deformation,
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
 * Glass V2 press response with touch-origin tilt, displacement and automatic size-aware tuning.
 *
 * Pointer events are observed only; no change is consumed. Once Clickable/Pager cancels the pressed
 * interaction because a gesture becomes a drag, progress springs back to rest while Pager keeps the
 * gesture. This remains the key safety boundary for directional motion on the home surface.
 */
@Composable
fun Modifier.metroDirectionalPressMotion(
    interactionSource: MutableInteractionSource,
    preset: MetroPressPreset = MetroPressPreset.Tile,
    enabled: Boolean = true,
): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val pressed by interactionSource.collectIsPressedAsState()
    val progress = remember { Animatable(0f) }
    var touchOrigin by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var elementSize by remember { mutableStateOf(IntSize.Zero) }

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
        onSizeChanged { elementSize = it }
            .pointerInput(Unit) {
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
        val widthDp = if (density.density > 0f) elementSize.width / density.density else 0f
        val heightDp = if (density.density > 0f) elementSize.height / density.density else 0f
        val transform = metroDirectionalTransform(
            preset = preset,
            progress = progress.value,
            touchX = touchOrigin.x,
            touchY = touchOrigin.y,
            profile = metroDirectionalProfile(widthDp, heightDp),
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
