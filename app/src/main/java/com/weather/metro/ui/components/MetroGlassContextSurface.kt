package com.weather.metro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.weather.metro.ui.theme.LocalMetroSurface
import com.weather.metro.ui.theme.MetroGlassTokens

/**
 * Small Visual V2 Glass surface for contextual map controls and passive status badges.
 *
 * Unlike [MetroFloatingIsland], this surface has no expand/collapse state. It only provides the
 * shared depth/material language; callers retain full ownership of click handling and layout.
 */
@Composable
fun MetroGlassContextSurface(
    accent: Color,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val neutralSurface = LocalMetroSurface.current
    val shape = RoundedCornerShape(MetroGlassTokens.ContextCornerRadiusDp.dp)

    Box(
        modifier = modifier
            .shadow(
                elevation = MetroGlassTokens.ContextElevationDp.dp,
                shape = shape,
                clip = false,
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = MetroGlassTokens.ContextHighlightAlpha),
                        neutralSurface.copy(alpha = MetroGlassTokens.ContextCoreAlpha),
                        accent.copy(alpha = MetroGlassTokens.ContextAccentWashAlpha),
                    ),
                ),
                shape = shape,
            )
            .border(
                width = MetroGlassTokens.ContextOutlineWidthDp.dp,
                color = Color.White.copy(alpha = MetroGlassTokens.ContextOutlineAlpha),
                shape = shape,
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}
