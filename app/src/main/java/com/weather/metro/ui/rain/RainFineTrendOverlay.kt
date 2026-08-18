package com.weather.metro.ui.rain

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weather.metro.domain.rain.RainFinePointSeries
import com.weather.metro.domain.rain.RainForecastSource
import java.util.Locale
import kotlin.math.max

private val FINE_PANEL = Color(0xF20A0A0A)
private val FINE_MUTED = Color(0xFF9A9A9A)
private val FINE_WARNING = Color(0xFFFFB300)

/**
 * Phase 3C measurement surface for the detailed two-hour forecast tool.
 *
 * Nothing is downloaded until the user expands this panel. The payload counter
 * reports only the additional decoded JSON fetched by this explicit probe; it
 * is not a claim about total on-wire transfer and excludes frames already held
 * by the host timeline.
 */
@Composable
fun RainFineTrendOverlay(
    rainState: RainHostState,
    pageColour: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val fineViewModel: RainFineTrendViewModel = viewModel()
    val fineState by fineViewModel.state.collectAsStateWithLifecycle()
    val timeline = rainState.forecast.value
    val location = rainState.location
    val eligible = timeline?.source == RainForecastSource.SWIRLS && location != null
    var expanded by rememberSaveable { mutableStateOf(false) }
    val requestIdentity = remember(timeline?.issueTime, location?.latitude, location?.longitude) {
        listOf(timeline?.issueTime, location?.latitude, location?.longitude).joinToString("|")
    }
    var observedIdentity by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(isActive) {
        if (!isActive) {
            expanded = false
            fineViewModel.cancel()
        }
    }

    LaunchedEffect(requestIdentity) {
        if (observedIdentity == null) {
            observedIdentity = requestIdentity
            return@LaunchedEffect
        }
        if (observedIdentity != requestIdentity) {
            observedIdentity = requestIdentity
            expanded = false
            fineViewModel.cancel()
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = when {
                fineState.status == RainFineTrendStatus.LOADING ->
                    "精細 ${fineState.loadedFrameCount}/${fineState.totalFrameCount}"
                expanded -> "收起精細"
                else -> "精細趨勢"
            },
            color = if (eligible) pageColour else FINE_MUTED.copy(alpha = 0.55f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(Color(0xE60A0A0A))
                .border(1.dp, if (eligible) pageColour.copy(alpha = 0.72f) else Color(0xFF333333))
                .clickable(enabled = eligible) {
                    val activeTimeline = timeline ?: return@clickable
                    val activeLocation = location ?: return@clickable
                    expanded = !expanded
                    if (expanded) {
                        fineViewModel.load(activeTimeline, activeLocation)
                    } else if (fineState.status == RainFineTrendStatus.LOADING) {
                        fineViewModel.cancel()
                    }
                }
                .padding(horizontal = 9.dp, vertical = 8.dp),
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            FineTrendPanel(
                state = fineState,
                accent = pageColour,
                onRetry = {
                    val activeTimeline = timeline ?: return@FineTrendPanel
                    val activeLocation = location ?: return@FineTrendPanel
                    fineViewModel.load(activeTimeline, activeLocation)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FineTrendPanel(
    state: RainFineTrendState,
    accent: Color,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(FINE_PANEL)
            .border(1.dp, Color(0xFF474747))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        when (state.status) {
            RainFineTrendStatus.IDLE -> {
                Text("精細趨勢已暫停", color = Color.White, fontSize = 13.sp)
                Text("再次點按「精細趨勢」開始載入", color = FINE_MUTED, fontSize = 9.sp)
            }
            RainFineTrendStatus.LOADING -> {
                Text("正在建立所在地精細趨勢", color = Color.White, fontSize = 13.sp)
                FineLoadProgress(
                    loaded = state.loadedFrameCount,
                    total = state.totalFrameCount,
                    accent = accent,
                )
                Text(
                    "${state.loadedFrameCount}/${state.totalFrameCount} frames · 額外 JSON ${formatPayloadBytes(state.downloadedPayloadBytes)}",
                    color = FINE_MUTED,
                    fontSize = 9.sp,
                )
            }
            RainFineTrendStatus.READY -> {
                val series = state.series
                if (series != null) {
                    FineSeriesContent(series, accent)
                }
                Text(
                    "16/16 frames · 額外 JSON ${formatPayloadBytes(state.downloadedPayloadBytes)} · ${formatElapsed(state.elapsedMillis)}",
                    color = FINE_MUTED,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RainFineTrendStatus.ERROR -> {
                Text("精細趨勢暫時無法完成", color = FINE_WARNING, fontSize = 13.sp)
                Text(
                    state.errorMessage ?: "未知錯誤",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "已載入 ${state.loadedFrameCount}/${state.totalFrameCount} · 額外 JSON ${formatPayloadBytes(state.downloadedPayloadBytes)} · retry",
                    color = accent,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clickable(onClick = onRetry)
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FineSeriesContent(series: RainFinePointSeries, accent: Color) {
    val buckets = series.reconstructedSixMinuteBuckets
    val values = if (buckets != null) {
        buckets.map { it.amountMm }
    } else {
        series.rollingSamples.map { it.accumulationMm }
    }
    val label = if (buckets != null) {
        "6分鐘推算雨量 · ${values.size}格"
    } else {
        "30分鐘滾動雨量 · 6分鐘取樣 · ${values.size}格"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        val peak = values.maxOrNull() ?: 0.0
        Text("峰值 ${formatMmCompact(peak)}", color = accent, fontSize = 9.sp)
    }

    FineBars(values = values, accent = accent)

    Row(Modifier.fillMaxWidth()) {
        Text(if (buckets != null) "現在" else "+30", color = FINE_MUTED, fontSize = 8.sp)
        Spacer(Modifier.weight(1f))
        Text("+60", color = FINE_MUTED, fontSize = 8.sp)
        Spacer(Modifier.weight(1f))
        Text("+120 分", color = FINE_MUTED, fontSize = 8.sp)
    }

    if (buckets != null) {
        Text(
            "乾燥 30 分鐘窗口提供唯一解錨點 +${series.reconstructionAnchorLeadMinutes ?: 0} 分 · 此為數學重建，並非天文台直接發布的 6 分鐘累積量",
            color = FINE_MUTED,
            fontSize = 8.sp,
            lineHeight = 11.sp,
        )
    } else {
        Text(
            "每柱仍是 30 分鐘累積值，只是每 6 分鐘重新取樣；沒有足夠約束時不製造假的 6 分鐘總雨量",
            color = FINE_MUTED,
            fontSize = 8.sp,
            lineHeight = 11.sp,
        )
    }
}

@Composable
private fun FineBars(values: List<Double>, accent: Color) {
    val peak = max(values.maxOrNull() ?: 0.0, 0.001)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEach { value ->
            val fraction = (value / peak).coerceIn(0.0, 1.0).toFloat()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(if (fraction <= 0f) 0.035f else fraction.coerceAtLeast(0.08f))
                        .background(if (value > 0.0) accent else Color(0xFF414141)),
                )
            }
        }
    }
}

@Composable
private fun FineLoadProgress(loaded: Int, total: Int, accent: Color) {
    val fraction = if (total <= 0) 0f else (loaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Color(0xFF333333)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .background(accent),
        )
    }
}

internal fun formatPayloadBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / 1_048_576.0)
}

internal fun formatElapsed(milliseconds: Long?): String = when {
    milliseconds == null -> "--"
    milliseconds < 1_000L -> "$milliseconds ms"
    else -> String.format(Locale.US, "%.2f s", milliseconds / 1_000.0)
}

private fun formatMmCompact(value: Double): String = when {
    value <= 0.0 -> "0 mm"
    value >= 10.0 -> String.format(Locale.US, "%.1f mm", value)
    else -> String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.') + " mm"
}
