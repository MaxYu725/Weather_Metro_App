package com.weather.metro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.domain.AlertSeverity
import com.weather.metro.domain.AstronomyInfo
import com.weather.metro.domain.LocalForecast
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.WeatherAlert
import com.weather.metro.domain.WeatherSnapshot
import com.weather.metro.domain.rain.RainLocationTrendSample
import com.weather.metro.ui.AppNavigationRequest
import com.weather.metro.ui.components.ExpandableMetroTile
import com.weather.metro.ui.components.HkoRemoteImage
import com.weather.metro.ui.components.MetroGlassContextSurface
import com.weather.metro.ui.components.MetroSectionLabel
import com.weather.metro.ui.components.MetroStat
import com.weather.metro.ui.components.MetroTile
import com.weather.metro.ui.rain.RainHostState
import com.weather.metro.ui.rain.RainLocationTrendState
import com.weather.metro.ui.rain.RainResourceStatus
import com.weather.metro.ui.rain.locationTrendDisplaySamples
import com.weather.metro.ui.rain.locationTrendHeadline
import com.weather.metro.ui.storm.StormHostState
import com.weather.metro.ui.theme.LocalMetroSubText
import com.weather.metro.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Phase 3B home surface: keep the useful Phase 3 integrations while tightening the vertical rhythm
 * for real phone screens. Active warnings remain compact, the two-hour rain card leads with its
 * actionable summary, and detailed observations stay in the expandable current-weather card.
 */
@Composable
fun HomeCurrentScreen(
    snapshot: WeatherSnapshot,
    rainState: RainHostState,
    locationTrendState: RainLocationTrendState,
    stormState: StormHostState,
    pageColour: Color,
    onRequestLocation: () -> Unit,
    onOpenPointRain: () -> Unit,
    onOpenRadar: () -> Unit,
    onOpenForecastMap: () -> Unit,
    onOpenStorm: () -> Unit,
    navigationRequest: AppNavigationRequest?,
    onNavigationHandled: (Long) -> Unit,
) {
    var heroExpanded by remember { mutableStateOf(false) }
    var localForecastExpanded by remember { mutableStateOf(false) }
    val current = snapshot.current
    val hasActiveAlerts = snapshot.alerts.isNotEmpty()
    val localForecastIndex = if (hasActiveAlerts) 3 else 1
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val scrollToItem: (Int) -> Unit = { index ->
        scope.launch {
            if (!reduceMotion) delay(150)
            listState.animateScrollToItem(index)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        overscrollEffect = null,
    ) {
        item {
            ExpandableMetroTile(
                seed = "current:${snapshot.location.district}",
                background = pageColour,
                expanded = heroExpanded,
                onExpandedChange = {
                    heroExpanded = it
                    if (it) scrollToItem(0)
                },
                collapsed = {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                snapshot.location.label,
                                color = Color.White,
                                fontSize = if (heroExpanded) 23.sp else 28.sp,
                                fontWeight = FontWeight.Light,
                            )
                            Text(
                                homeLocationContextLine(snapshot.location),
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                            )
                        }
                        HomeDetailBadge(heroExpanded, pageColour)
                    }
                    Spacer(Modifier.height(if (heroExpanded) 6.dp else 14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = current.temperatureC?.let { "${it.roundToInt()}°" } ?: "--°",
                            color = Color.White,
                            fontSize = if (heroExpanded) 50.sp else 72.sp,
                            lineHeight = if (heroExpanded) 52.sp else 72.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.weight(1f),
                        )
                        HkoRemoteImage(
                            url = current.weatherIconCode?.let(::homeHkoWeatherIconUrl),
                            contentDescription = "香港天文台天氣圖示",
                            modifier = Modifier.size(if (heroExpanded) 64.dp else 92.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "濕度 ${current.humidityPercent.homeDisplay("%")}" +
                            "  ·  ↓ ${current.minTemperatureC.homeDisplay("°")}  ↑ ${current.maxTemperatureC.homeDisplay("°")}",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                    )
                },
                expandedContent = {
                    HomeStatGrid(
                        listOf(
                            Triple("體感溫度", current.feelsLikeC.homeDisplay("°C"), true),
                            Triple(
                                "風向風速",
                                "${current.windDirection ?: "--"} ${current.windSpeedKmh.homeDisplay(" km/h")}",
                                true,
                            ),
                            Triple("最高陣風", current.gustKmh.homeDisplay(" km/h"), true),
                            Triple("所在地區雨量", current.rainfallMm.homeDisplay(" mm"), false),
                            Triple(
                                "紫外線",
                                listOfNotNull(current.uvIndex?.toString(), current.uvDescription)
                                    .joinToString(" ")
                                    .ifBlank { "--" },
                                false,
                            ),
                            Triple("能見度", current.visibilityKm.homeDisplay(" km"), false),
                            Triple("氣壓", current.pressureHpa.homeDisplay(" hPa"), true),
                            Triple("露點", current.dewPointC.homeDisplay("°C"), true),
                        ),
                    )
                    snapshot.location.accuracyMetres?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "定位精度 ±${it} m · 點按重新定位",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            modifier = Modifier.clickable(onClick = onRequestLocation),
                        )
                    }
                    HomeAstronomyPanel(snapshot.astronomy)
                },
            )
        }

        if (hasActiveAlerts) {
            item { MetroSectionLabel("alerts & tips") }
            item {
                HomeAlertsSection(
                    alerts = snapshot.alerts,
                    pageColour = pageColour,
                    navigationRequest = navigationRequest,
                    onNavigationHandled = onNavigationHandled,
                ) { scrollToItem(2) }
            }
        }

        item {
            ExpandableMetroTile(
                seed = "local-forecast",
                background = pageColour,
                expanded = localForecastExpanded,
                onExpandedChange = {
                    localForecastExpanded = it
                    if (it) scrollToItem(localForecastIndex)
                },
                collapsed = {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "本港預報",
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.weight(1f),
                        )
                        HomeDetailBadge(localForecastExpanded, pageColour)
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(
                        snapshot.localForecast.generalSituation
                            .ifBlank { snapshot.localForecast.forecastDescription }
                            .ifBlank { "香港天文台暫未提供本港預報。" },
                        color = Color.White,
                        maxLines = if (localForecastExpanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    )
                    Text(
                        homeFormatHkoTime(snapshot.localForecast.updatedAt),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 10.sp,
                    )
                },
                expandedContent = { HomeLocalForecastDetails(snapshot.localForecast) },
            )
        }

        item { MetroSectionLabel("next 2 hours") }
        item {
            HomeRainNowcastTile(
                rainState = rainState,
                locationTrendState = locationTrendState,
                pageColour = pageColour,
                onClick = onOpenPointRain,
            )
        }

        item { MetroSectionLabel("live weather") }
        item {
            HomeToolActions(
                pageColour = pageColour,
                stormState = stormState,
                onOpenRadar = onOpenRadar,
                onOpenForecastMap = onOpenForecastMap,
                onOpenStorm = onOpenStorm,
            )
        }

        if (!hasActiveAlerts) {
            item { MetroSectionLabel("alerts & tips") }
            item {
                HomeAlertsSection(
                    alerts = snapshot.alerts,
                    pageColour = pageColour,
                    navigationRequest = navigationRequest,
                    onNavigationHandled = onNavigationHandled,
                ) { scrollToItem(7) }
            }
        }
    }
}

@Composable
private fun HomeDetailBadge(expanded: Boolean, pageColour: Color) {
    MetroGlassContextSurface(accent = pageColour) {
        Box(
            modifier = Modifier.width(42.dp).height(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (expanded) "收起" else "詳情",
                color = Color.White.copy(alpha = 0.86f),
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

private data class HomeRainSample(
    val leadMinutes: Int,
    val time: String,
    val amountMm: Double,
)

@Composable
private fun HomeRainNowcastTile(
    rainState: RainHostState,
    locationTrendState: RainLocationTrendState,
    pageColour: Color,
    onClick: () -> Unit,
) {
    val trendSamples = locationTrendDisplaySamples(locationTrendState.samples)
    val headline = locationTrendHeadline(trendSamples) ?: homeRainSummary(rainState)
    MetroTile(
        seed = "home-rain-nowcast",
        background = pageColour,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column {
            Text(
                headline,
                color = Color.White,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Light,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (trendSamples.isNotEmpty()) {
                HomeLocationRainTrend(trendSamples)
            } else {
                val samples = homeRainSamples(rainState)
                if (samples.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        samples.forEach { sample ->
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier.height(31.dp).fillMaxWidth(),
                                    contentAlignment = Alignment.BottomCenter,
                                ) {
                                    Box(
                                        Modifier
                                            .width(19.dp)
                                            .height(homeRainBarHeight(sample.amountMm))
                                            .background(
                                                Color.White.copy(
                                                    alpha = if (sample.amountMm < 0.1) 0.30f else 0.92f,
                                                ),
                                            ),
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    homeRainClock(sample.time),
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                )
                                Text(
                                    if (sample.leadMinutes == 0) "現在" else "+${sample.leadMinutes}m",
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 8.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("詳細降雨 ›", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun HomeLocationRainTrend(samples: List<RainLocationTrendSample>) {
    val byFrame = samples.associateBy { it.frameIndex }
    Spacer(Modifier.height(10.dp))
    Text(
        "降雨訊號趨勢",
        color = Color.White.copy(alpha = 0.72f),
        fontSize = 10.sp,
    )
    Spacer(Modifier.height(5.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(16) { frameIndex ->
            val sample = byFrame[frameIndex]
            Box(
                modifier = Modifier.weight(1f).height(31.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (sample != null) {
                    Box(
                        Modifier
                            .width(6.dp)
                            .height(homeRainBarHeight(sample.amountMm))
                            .background(
                                Color.White.copy(
                                    alpha = if (sample.amountMm < 0.1) 0.30f else 0.92f,
                                ),
                            ),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf("+30", "+60", "+90", "+120").forEach { anchor ->
            Text(
                anchor,
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
    }
}

private fun homeRainSummary(state: RainHostState): String {
    val resource = state.pointForecast
    val forecast = resource.value
    if (forecast != null) {
        val summary = forecast.summary
        val wetPeriods = summary?.wetPeriodCount ?: forecast.periods.count { it.amountMm >= 0.1 }
        val totalMm = summary?.totalMm ?: forecast.periods.sumOf { it.amountMm }
        if (wetPeriods == 0 || totalMm < 0.1) return "未來 2 小時暫無明顯降雨"

        summary?.rainStartLeadMinutes?.takeIf { it > 0 }?.let { lead ->
            return "約 $lead 分鐘後可能開始有雨 · 2 小時約 ${totalMm.homeDisplay(" mm")}"
        }
        if (totalMm >= 0.1) {
            return "未來 2 小時預計有雨 · 累積約 ${totalMm.homeDisplay(" mm")}"
        }
        summary?.text?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return when (resource.status) {
        RainResourceStatus.IDLE -> "準備取得所在地降雨預測"
        RainResourceStatus.LOADING -> "正在取得所在地降雨預測…"
        RainResourceStatus.ERROR -> "暫時未能取得降雨預測"
        RainResourceStatus.READY -> "暫未有可用降雨預測"
    }
}

private fun homeRainSamples(state: RainHostState): List<HomeRainSample> {
    val periods = state.pointForecast.value?.periods.orEmpty()
    if (periods.isEmpty()) return emptyList()
    val withLead = periods.filter { it.leadMinutes != null }
    if (withLead.isEmpty()) {
        return periods.take(5).mapIndexed { index, period ->
            HomeRainSample(
                leadMinutes = index * 30,
                time = period.time,
                amountMm = period.amountMm,
            )
        }
    }
    return listOf(0, 30, 60, 90, 120).mapNotNull { target ->
        withLead.minByOrNull { period -> abs((period.leadMinutes ?: target) - target) }
            ?.let { period ->
                HomeRainSample(
                    leadMinutes = target,
                    time = period.time,
                    amountMm = period.amountMm,
                )
            }
    }
}

private fun homeRainBarHeight(amountMm: Double) = when {
    amountMm < 0.1 -> 2.dp
    amountMm < 0.5 -> 8.dp
    amountMm < 2.0 -> 14.dp
    amountMm < 5.0 -> 22.dp
    else -> 30.dp
}

private fun homeRainClock(value: String): String {
    if (value.isBlank()) return "--:--"
    return runCatching { HOME_RAIN_TIME.format(Instant.parse(value)) }
        .getOrElse { value.replace("T", " ").takeLast(5) }
}

@Composable
private fun HomeToolActions(
    pageColour: Color,
    stormState: StormHostState,
    onOpenRadar: () -> Unit,
    onOpenForecastMap: () -> Unit,
    onOpenStorm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        HomeToolAction(
  seed = "home-radar",
  title = "降雨雷達",
  status = "即時觀測",
  pageColour = pageColour,
  modifier = Modifier.weight(1f),
  onClick = onOpenRadar,
        )
        HomeToolAction(
  seed = "home-forecast-map",
  title = "兩小時降雨",
  status = "短臨預報",
  pageColour = pageColour,
  modifier = Modifier.weight(1f),
  onClick = onOpenForecastMap,
        )
        HomeToolAction(
  seed = "home-storm",
  title = "熱帶氣旋",
  status = if (stormState.isRefreshing) "路徑追蹤 · 更新中" else "路徑追蹤",
  pageColour = pageColour,
  modifier = Modifier.weight(1f),
  onClick = onOpenStorm,
        )
    }
}

@Composable
private fun HomeToolAction(
    seed: String,
    title: String,
    status: String,
    pageColour: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    MetroTile(
        seed = seed,
        background = pageColour,
        modifier = modifier.height(106.dp),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(status, color = Color.White.copy(alpha = 0.66f), fontSize = 9.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light, maxLines = 1)
            Text("open ›", color = Color.White.copy(alpha = 0.62f), fontSize = 9.sp)
        }
    }
}

@Composable
private fun HomeAstronomyPanel(info: AstronomyInfo) {
    Spacer(Modifier.height(10.dp))
    Text("astronomy", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
    Spacer(Modifier.height(4.dp))
    HomeStatGrid(
        listOf(
            Triple("日出", info.sunrise ?: "--", false),
            Triple("日中", info.solarTransit ?: "--", false),
            Triple("日落", info.sunset ?: "--", false),
            Triple("月出", info.moonrise ?: "--", false),
            Triple("月中", info.moonTransit ?: "--", false),
            Triple("月落", info.moonset ?: "--", false),
            Triple("月相", info.moonPhase ?: "--", true),
            Triple("月面照明", info.moonIlluminationPercent.homeDisplay("%"), true),
        ),
    )
    if (info.tides.isNotEmpty()) {
        Spacer(Modifier.height(7.dp))
        Text("鄰近潮汐站", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        info.tides.forEach { tide ->
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Text(tide.time, color = Color.White, modifier = Modifier.width(64.dp), fontSize = 16.sp)
                Text(tide.type, color = Color.White, modifier = Modifier.weight(1f))
                Text(tide.heightMetres.homeDisplay(" m"), color = Color.White)
            }
        }
    }
}

@Composable
private fun HomeStatGrid(items: List<Triple<String, String, Boolean>>) {
    val rows = items.chunked(2)
    rows.forEachIndexed { index, rowItems ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (rowItems.size == 1) {
                val (label, value, secondary) = rowItems.single()
                MetroStat(label, value, Modifier.fillMaxWidth(), secondary)
            } else {
                rowItems.forEach { (label, value, secondary) ->
                    MetroStat(label, value, Modifier.weight(1f), secondary)
                }
            }
        }
        if (index < rows.lastIndex) Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun HomeOverviewSection(title: String, body: String) {
    if (body.isBlank()) return
    Text(
        title.ifBlank { "預測" },
        color = Color.Black,
        modifier = Modifier.background(Color.White).padding(horizontal = 7.dp, vertical = 2.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    Text(body, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Justify)
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun HomeAlertsSection(
    alerts: List<WeatherAlert>,
    pageColour: Color,
    navigationRequest: AppNavigationRequest?,
    onNavigationHandled: (Long) -> Unit,
    onAlertExpanded: () -> Unit,
) {
    var selectedId by remember(alerts) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(navigationRequest?.token, alerts) {
        val request = navigationRequest ?: return@LaunchedEffect
        val selected = alerts.firstOrNull {
            it.id == request.alertId || (!request.alertCode.isNullOrBlank() && it.code == request.alertCode)
        }
        if (selected != null) selectedId = selected.id
        onAlertExpanded()
        if (selected != null || request.eventKind == "CANCEL") onNavigationHandled(request.token)
    }
    if (alerts.isEmpty()) {
        MetroTile(
            seed = "home-no-alerts",
            background = pageColour,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Text("現時沒有生效的天氣警告或特別提示。", color = LocalMetroSubText.current, fontSize = 12.sp)
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        alerts.chunked(2).forEach { group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                group.forEach { alert ->
                    HomeAlertSmallTile(
                        alert = alert,
                        selected = alert.id == selectedId,
                        modifier = if (group.size == 1) Modifier.fillMaxWidth() else Modifier.weight(1f),
                        onClick = {
                            val next = if (selectedId == alert.id) null else alert.id
                            selectedId = next
                            if (next != null) onAlertExpanded()
                        },
                    )
                }
            }
            val selected = group.firstOrNull { it.id == selectedId }
            if (selected != null) HomeAlertDetailTile(selected)
        }
    }
}

@Composable
private fun HomeAlertSmallTile(
    alert: WeatherAlert,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    MetroTile(
        seed = alert.id,
        background = homeAlertColor(alert.severity),
        modifier = modifier.height(74.dp),
        onClick = onClick,
        selected = selected,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HkoRemoteImage(
                url = alert.iconUrl,
                contentDescription = alert.title,
                modifier = Modifier.size(34.dp),
                fallback = homeAlertFallback(alert),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    alert.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    homeFormatHkoTime(alert.updatedAt),
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 9.sp,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(if (selected) "−" else "+", color = Color.White, fontSize = 18.sp)
        }
    }
}

@Composable
private fun HomeAlertDetailTile(alert: WeatherAlert) {
    MetroTile(
        seed = "home-detail:${alert.id}",
        background = homeAlertColor(alert.severity),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(alert.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                    Text(
                        "${alert.actionCode.lowercase()} · ${homeFormatHkoTime(alert.updatedAt)}",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                    )
                }
                HkoRemoteImage(alert.iconUrl, alert.title, Modifier.size(42.dp), homeAlertFallback(alert))
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.3f))
            Spacer(Modifier.height(6.dp))
            Text(alert.content, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Justify)
        }
    }
}

@Composable
private fun HomeLocalForecastDetails(forecast: LocalForecast) {
    HomeOverviewSection("熱帶氣旋消息", forecast.tropicalCycloneInfo)
    HomeOverviewSection(forecast.forecastPeriod, forecast.forecastDescription)
    HomeOverviewSection("展望", forecast.outlook)
    HomeOverviewSection("火險", forecast.fireDangerWarning)
}

private fun homeAlertColor(severity: AlertSeverity) = when (severity) {
    AlertSeverity.URGENT -> Color(0xFFE51400)
    AlertSeverity.WARNING -> Color(0xFFF09609)
    AlertSeverity.ADVISORY -> Color(0xFF339933)
    AlertSeverity.TIP -> Color(0xFFB81B53)
}

private fun homeAlertFallback(alert: WeatherAlert): String = when {
    alert.isTip -> "i"
    alert.code == "WTS" -> "ϟ"
    alert.code.startsWith("WRAIN") -> "☂"
    alert.code.startsWith("TC") -> "▲"
    else -> "!"
}

private fun homeHkoWeatherIconUrl(code: Int): String =
    "https://www.hko.gov.hk/images/HKOWxIconOutline/pic$code.png"

private fun homeFormatHkoTime(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        val instant = Instant.parse(value)
        DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.of("Asia/Hong_Kong")).format(instant)
    }.getOrElse {
        value.replace("T", " ").take(16)
    }
}

private fun homeLocationContextLine(location: LocationInfo): String {
    val labelKey = homeLocationKey(location.label)
    val districtKey = homeLocationKey(location.district)
    val stationKey = homeLocationKey(location.stationName)
    return buildList {
        if (districtKey != labelKey) add(location.district)
        if (stationKey != labelKey && stationKey != districtKey) {
            add("${location.stationName}觀測站")
        }
        if (isEmpty()) add("香港天文台觀測站")
    }.distinct().joinToString(" · ")
}

private fun homeLocationKey(value: String): String = value
    .lowercase()
    .replace("觀測站", "")
    .replace("天氣站", "")
    .replace(" district", "")
    .replace("區", "")
    .filter { it.isLetterOrDigit() }

private fun Number?.homeDisplay(suffix: String): String = when (this) {
    null -> "--"
    is Double -> if (this % 1.0 == 0.0) "${roundToInt()}$suffix" else "${"%.1f".format(Locale.US, this)}$suffix"
    else -> "$this$suffix"
}

private val HOME_RAIN_TIME: DateTimeFormatter = DateTimeFormatter
    .ofPattern("HH:mm")
    .withZone(ZoneId.of("Asia/Hong_Kong"))