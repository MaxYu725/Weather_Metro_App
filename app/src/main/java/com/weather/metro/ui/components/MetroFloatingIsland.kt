package com.weather.metro.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.weather.metro.ui.theme.LocalMetroSurface
import com.weather.metro.ui.theme.LocalReduceMotion
import com.weather.metro.ui.theme.MetroGlassTokens
import com.weather.metro.ui.theme.metroTokenLerp

/**
 * 44465-inspired contextual Glass island.
 *
 * It only owns its material and size/shape morph. Callers keep full ownership of gestures and state,
 * so Radar/Storm controls can adopt it without introducing a second navigation system.
 *
 * The transparent outer host is always full-width and anchors the material island to BottomCenter.
 * This prevents intrinsic/full-width constraint changes from making a collapsing island appear to
 * travel from TopStart before it settles back into the centre.
 */
@Composable
fun MetroFloatingIsland(
    expanded: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    collapsedContent: @Composable RowScope.() -> Unit,
    expandedContent: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val neutralSurface = LocalMetroSurface.current
    val morphDuration = if (reduceMotion) 1 else if (expanded) 280 else 240
    val morphProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = morphDuration,
            easing = FastOutSlowInEasing,
        ),
        label = "floating island shape morph",
    )
    val cornerRadius = metroTokenLerp(
        MetroGlassTokens.IslandCollapsedCornerRadiusDp,
        MetroGlassTokens.IslandExpandedCornerRadiusDp,
        morphProgress,
    ).dp
    val horizontalPadding = metroTokenLerp(
        MetroGlassTokens.IslandCollapsedHorizontalPaddingDp,
        MetroGlassTokens.IslandExpandedHorizontalPaddingDp,
        morphProgress,
    ).dp
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedContent(
            targetState = expanded,
            contentAlignment = Alignment.BottomCenter,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(tween(1)) togetherWith fadeOut(tween(1))
                } else {
                    val expanding = targetState
                    val duration = if (expanding) 280 else 240
                    val enter = if (expanding) {
                        fadeIn(
                            tween(
                                durationMillis = 140,
                                delayMillis = 70,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    } else {
                        fadeIn(
                            tween(
                                durationMillis = 120,
                                delayMillis = 55,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    }
                    val exit = fadeOut(
                        tween(
                            durationMillis = if (expanding) 90 else 80,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                    (enter togetherWith exit).using(
                        SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ ->
                                tween(
                                    durationMillis = duration,
                                    easing = FastOutSlowInEasing,
                                )
                            },
                        ),
                    )
                }
            },
            label = "floating island content morph",
            modifier = Modifier
                .shadow(
                    elevation = MetroGlassTokens.IslandElevationDp.dp,
                    shape = shape,
                    clip = false,
                )
                .clip(shape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = MetroGlassTokens.IslandHighlightAlpha),
                            neutralSurface.copy(alpha = MetroGlassTokens.IslandCoreAlpha),
                            accent.copy(alpha = MetroGlassTokens.IslandAccentWashAlpha),
                        ),
                    ),
                    shape = shape,
                )
                .border(
                    width = MetroGlassTokens.IslandOutlineWidthDp.dp,
                    color = Color.White.copy(alpha = MetroGlassTokens.IslandOutlineAlpha),
                    shape = shape,
                )
                .padding(
                    horizontal = horizontalPadding,
                    vertical = MetroGlassTokens.IslandVerticalPaddingDp.dp,
                ),
        ) { targetExpanded ->
            if (targetExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    content = expandedContent,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = collapsedContent,
                )
            }
        }
    }
}
