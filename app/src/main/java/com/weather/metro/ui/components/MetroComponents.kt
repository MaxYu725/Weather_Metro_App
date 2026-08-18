package com.weather.metro.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.ui.theme.LocalMetroSubText
import com.weather.metro.ui.theme.LocalMetroOutline
import com.weather.metro.ui.theme.LocalMetroSurface
import com.weather.metro.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

@Composable
fun MetroSectionLabel(text: String, modifier: Modifier = Modifier) {
    val compactHomeLabel = text == "alerts & tips" ||
        text == "next 2 hours" ||
        text == "live weather" ||
        text == "conditions"
    Text(
        text = text,
        modifier = modifier.padding(
            top = if (compactHomeLabel) 12.dp else 22.dp,
            bottom = if (compactHomeLabel) 4.dp else 10.dp,
        ),
        color = LocalMetroSubText.current,
        fontSize = if (compactHomeLabel) 15.sp else 18.sp,
        fontWeight = FontWeight.Light,
    )
}

@Composable
fun MetroTile(
    seed: String,
    background: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(12.dp),
    selected: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val neutralSurface = LocalMetroSurface.current
    val neutralOutline = LocalMetroOutline.current
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = resolvedInteractionSource,
            indication = indication,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    val chromeModifier = Modifier
        .background(neutralSurface)
        .background(background.copy(alpha = if (selected) 0.14f else 0.035f))
        .border(
            width = 1.dp,
            color = if (selected) background.copy(alpha = 0.72f) else neutralOutline,
        )
        .drawBehind {
            drawRect(
                color = background.copy(alpha = 0.92f),
                size = Size(3.dp.toPx(), size.height),
            )
        }
    val isHomeConditions = seed == "home-conditions"
    val resolvedModifier = if (isHomeConditions) {
        Modifier.fillMaxWidth().height(48.dp)
    } else {
        modifier
    }
    val resolvedPadding = if (isHomeConditions) {
        androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 5.dp)
    } else {
        contentPadding
    }
    Box(
        modifier = resolvedModifier
            .clip(androidx.compose.ui.graphics.RectangleShape)
            .then(chromeModifier)
            .then(clickableModifier)
            .padding(resolvedPadding),
        content = content,
    )
}

@Composable
fun ExpandableMetroTile(
    seed: String,
    background: Color,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    collapsed: @Composable ColumnScope.() -> Unit,
    expandedContent: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    MetroTile(
        seed = seed,
        background = background,
        onClick = { onExpandedChange(!expanded) },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(tween(if (reduceMotion) 160 else 620)),
        ) {
            collapsed()
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.28f))
                Spacer(Modifier.height(6.dp))
                expandedContent()
            }
        }
    }
}

@Composable
fun MetroStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.76f),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(1.dp))
        Text(text = value, color = Color.White, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Light)
        if (secondary) {
            Text(
                text = "secondary estimate",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 7.sp,
                lineHeight = 9.sp,
            )
        }
    }
}

@Composable
fun HkoRemoteImage(
    url: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fallback: String = "☁",
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, url) {
        value = url?.let { loadBitmap(it) }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(text = fallback, color = Color.White, fontSize = 36.sp)
        }
    }
}

@Composable
fun MetroProgress(modifier: Modifier = Modifier, colour: Color = MaterialTheme.colorScheme.primary) {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "metro loading")
    val animatedPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "metro loading phase",
    )
    val phase = if (reduceMotion) 0.18f else animatedPhase
    Canvas(modifier = modifier.fillMaxWidth().height(10.dp)) {
        val radius = 3.dp.toPx()
        val travel = size.width + radius * 2
        repeat(5) { index ->
            val dotPhase = (phase + index * 0.135f) % 1f
            val x = dotPhase * travel - radius
            val centreFade = 1f - kotlin.math.abs(dotPhase * 2f - 1f)
            drawCircle(
                color = colour.copy(alpha = 0.35f + centreFade * 0.65f),
                radius = radius,
                center = Offset(x, size.height / 2f),
            )
        }
    }
}

private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", "WeatherMetroApp/1.0 (Android)")
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.use(BitmapFactory::decodeStream)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}