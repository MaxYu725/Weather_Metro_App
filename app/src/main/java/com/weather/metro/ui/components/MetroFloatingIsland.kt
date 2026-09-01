package com.weather.metro.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    val morphProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 1 else 220),
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
        modifier = modifier
            .animateContentSize(
                animationSpec = if (reduceMotion) {
                    tween(durationMillis = 1)
                } else {
                    spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                },
            )
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
    ) {
        if (expanded) {
            Column(content = expandedContent)
        } else {
            Row(content = collapsedContent)
        }
    }
}
