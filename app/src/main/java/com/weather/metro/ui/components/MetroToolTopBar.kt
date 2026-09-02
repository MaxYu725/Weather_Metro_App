package com.weather.metro.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.ui.motion.MetroPressPreset
import com.weather.metro.ui.motion.metroPressMotion
import com.weather.metro.ui.theme.LocalMetroSubText
import com.weather.metro.ui.theme.LocalReduceMotion

/**
 * Shared top chrome for fullscreen tools.
 *
 * All tool pages use the same left navigation, accent marker, title hierarchy and
 * right-side action language. Callers own safe-area placement so the backdrop/map
 * remains edge-to-edge behind the glass surface.
 */
@Composable
fun MetroToolTopBar(
    title: String,
    subtitle: String?,
    accent: Color,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    MetroGlassContextSurface(
        accent = accent,
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "‹",
                    color = accent,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Light,
                )
            }
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(24.dp)
                    .background(accent, RoundedCornerShape(3.dp)),
            )
            Column(
                modifier = Modifier
                    .padding(start = 9.dp)
                    .weight(1f),
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = LocalMetroSubText.current,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailingContent()
            onRefresh?.let { refresh ->
                MetroRefreshAction(
                    refreshing = refreshing,
                    accent = accent,
                    onRefresh = refresh,
                )
            }
        }
    }
}

/**
 * Stable-width refresh affordance used by all fullscreen tool headers.
 * The text moves instead of the surrounding layout so a refresh never nudges the title or controls.
 */
@Composable
fun MetroRefreshAction(
    refreshing: Boolean,
    accent: Color,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .width(48.dp)
            .height(32.dp)
            .metroPressMotion(
                interactionSource = interactionSource,
                preset = MetroPressPreset.CompactControl,
                enabled = !refreshing,
            )
            .clickable(
                enabled = !refreshing,
                interactionSource = interactionSource,
                indication = null,
                onClick = onRefresh,
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        AnimatedContent(
            targetState = refreshing,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(tween(100)) togetherWith fadeOut(tween(80))
                } else {
                    val enterOffset: (Int) -> Int = { height -> if (targetState) height / 3 else -height / 3 }
                    val exitOffset: (Int) -> Int = { height -> if (targetState) -height / 3 else height / 3 }
                    (fadeIn(tween(160)) + slideInVertically(tween(180), initialOffsetY = enterOffset)) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically(tween(150), targetOffsetY = exitOffset))
                }
            },
            contentKey = { it },
            label = "tool refresh state",
        ) { isRefreshing ->
            Text(
                text = if (isRefreshing) "更新中" else "更新",
                color = if (isRefreshing) LocalMetroSubText.current else accent,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}
