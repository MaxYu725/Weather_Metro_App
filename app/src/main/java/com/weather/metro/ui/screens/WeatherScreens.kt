package com.weather.metro.ui.screens

import android.content.Intent
import com.weather.metro.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.weather.metro.data.settings.PageColourSlot
import com.weather.metro.data.settings.UiSettings
import com.weather.metro.domain.AlertSeverity
import com.weather.metro.domain.AstronomyInfo
import com.weather.metro.domain.LocalForecast
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.WeatherAlert
import com.weather.metro.domain.WeatherSnapshot
import com.weather.metro.ui.AppNavigationRequest
import com.weather.metro.ui.components.ExpandableMetroTile
import com.weather.metro.ui.components.MetroTileTreatment
import com.weather.metro.ui.components.HkoRemoteImage
import com.weather.metro.ui.components.MetroSectionLabel
import com.weather.metro.ui.components.MetroStat
import com.weather.metro.ui.components.MetroTile
import com.weather.metro.ui.components.MetroProgress
import com.weather.metro.ui.theme.LocalMetroSubText
import com.weather.metro.ui.theme.LocalReduceMotion
import com.weather.metro.ui.theme.argbColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CurrentScreen(
    snapshot: WeatherSnapshot,
    pageColour: Color,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onRequestLocation: () -> Unit,
    navigationRequest: AppNavigationRequest?,
    onNavigationHandled: (Long) -> Unit,
) {
    var heroExpanded by remember { mutableStateOf(false) }
    var localForecastExpanded by remember { mutableStateOf(false) }
    val current = snapshot.current
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (refreshing) {
                    Box(Modifier.width(54.dp)) { MetroProgress(colour = pageColour) }
                } else {
                    Box(Modifier.size(8.dp).background(if (snapshot.isStale) Color(0xFFF09609) else Color(0xFF00C853)))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = when {
                        refreshing -> "正在更新香港天文台資料"
                        snapshot.isStale -> "顯示離線快取"
                        else -> "香港天文台資料已同步"
                    },
                    color = LocalMetroSubText.current,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (refreshing) "updating" else "refresh",
                    color = pageColour,
                    fontSize = 13.sp,
                    modifier = if (refreshing) Modifier else Modifier.clickable(onClick = onRefresh),
                )
            }
        }

        item {
            ExpandableMetroTile(
                seed = "current:${snapshot.location.district}",
                background = pageColour,
                treatment = MetroTileTreatment.NEUTRAL_SURFACE,
                expanded = heroExpanded,
                onExpandedChange = {
                    heroExpanded = it
                    if (it) scrollToItem(1)
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
                            locationContextLine(snapshot.location),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                        )
                    }
                    Text(if (heroExpanded) "−" else "+", color = Color.White, fontSize = 25.sp)
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
                        url = current.weatherIconCode?.let(::hkoWeatherIconUrl),
                        contentDescription = "香港天文台天氣圖示",
                        modifier = Modifier.size(if (heroExpanded) 64.dp else 92.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
                Spacer(Modifier.height(6.dp))
                Text(
                    "濕度 ${current.humidityPercent.display("%")}" +
                        "  ·  ↓ ${current.minTemperatureC.display("°")}  ↑ ${current.maxTemperatureC.display("°")}",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                )
                },
                expandedContent = {
                StatGrid(
                    listOf(
                        Triple("體感溫度", current.feelsLikeC.display("°C"), true),
                        Triple("風向風速", "${current.windDirection ?: "--"} ${current.windSpeedKmh.display(" km/h")}", true),
                        Triple("最高陣風", current.gustKmh.display(" km/h"), true),
                        Triple("所在地區雨量", current.rainfallMm.display(" mm"), false),
                        Triple("紫外線", listOfNotNull(current.uvIndex?.toString(), current.uvDescription).joinToString(" ").ifBlank { "--" }, false),
                        Triple("能見度", current.visibilityKm.display(" km"), false),
                        Triple("氣壓", current.pressureHpa.display(" hPa"), true),
                        Triple("露點", current.dewPointC.display("°C"), true),
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
                AstronomyPanel(snapshot.astronomy)
                },
            )
        }

        item {
            ExpandableMetroTile(
                seed = "local-forecast",
                background = pageColour,
                treatment = MetroTileTreatment.NEUTRAL_SURFACE,
                expanded = localForecastExpanded,
                onExpandedChange = {
                    localForecastExpanded = it
                    if (it) scrollToItem(2)
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
                    Text(if (localForecastExpanded) "−" else "+", color = Color.White, fontSize = 25.sp)
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
                Text(formatHkoTime(snapshot.localForecast.updatedAt), color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
                },
                expandedContent = { LocalForecastDetails(snapshot.localForecast) },
            )
        }

        item { MetroSectionLabel("alerts & tips") }
        item {
            AlertsSection(
                alerts = snapshot.alerts,
                pageColour = pageColour,
                navigationRequest = navigationRequest,
                onNavigationHandled = onNavigationHandled,
            ) { scrollToItem(4) }
        }
    }
}

@Composable
private fun AstronomyPanel(info: AstronomyInfo) {
    Spacer(Modifier.height(10.dp))
    Text("astronomy", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
    Spacer(Modifier.height(4.dp))
    StatGrid(
        listOf(
            Triple("日出", info.sunrise ?: "--", false),
            Triple("日中", info.solarTransit ?: "--", false),
            Triple("日落", info.sunset ?: "--", false),
            Triple("月出", info.moonrise ?: "--", false),
            Triple("月中", info.moonTransit ?: "--", false),
            Triple("月落", info.moonset ?: "--", false),
            Triple("月相", info.moonPhase ?: "--", true),
            Triple("月面照明", info.moonIlluminationPercent.display("%"), true),
        ),
    )
    if (info.tides.isNotEmpty()) {
        Spacer(Modifier.height(7.dp))
        Text("鄰近潮汐站", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        info.tides.forEach { tide ->
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Text(tide.time, color = Color.White, modifier = Modifier.width(64.dp), fontSize = 16.sp)
                Text(tide.type, color = Color.White, modifier = Modifier.weight(1f))
                Text(tide.heightMetres.display(" m"), color = Color.White)
            }
        }
    }
}

@Composable
private fun StatGrid(items: List<Triple<String, String, Boolean>>) {
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
private fun OverviewSection(title: String, body: String) {
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
private fun AlertsSection(
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
            seed = "no-alerts",
            background = pageColour,
            modifier = Modifier.fillMaxWidth(),
            treatment = MetroTileTreatment.NEUTRAL_SURFACE,
        ) {
            Text("現時沒有生效的天氣警告或特別提示。", color = LocalMetroSubText.current)
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        alerts.chunked(4).forEach { group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                group.forEach { alert ->
                    AlertSmallTile(
                        alert = alert,
                        selected = alert.id == selectedId,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val next = if (selectedId == alert.id) null else alert.id
                            selectedId = next
                            if (next != null) onAlertExpanded()
                        },
                    )
                }
                repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
            }
            val selected = group.firstOrNull { it.id == selectedId }
            if (selected != null) AlertDetailTile(selected)
        }
    }
}

@Composable
private fun AlertSmallTile(
    alert: WeatherAlert,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    MetroTile(
        seed = alert.id,
        background = alertColor(alert.severity),
        modifier = modifier.aspectRatio(0.70f),
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                HkoRemoteImage(
                    url = alert.iconUrl,
                    contentDescription = alert.title,
                    modifier = Modifier.size(30.dp),
                    fallback = alertFallback(alert),
                )
                Spacer(Modifier.weight(1f))
                Text(if (selected) "−" else "+", color = Color.White, fontSize = 17.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                alert.title,
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatHkoTime(alert.updatedAt), color = Color.White.copy(alpha = 0.72f), fontSize = 9.sp)
        }
    }
}

@Composable
private fun AlertDetailTile(alert: WeatherAlert) {
    MetroTile(
        seed = "detail:${alert.id}",
        background = alertColor(alert.severity),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(alert.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                    Text(
                        "${alert.actionCode.lowercase()} · ${formatHkoTime(alert.updatedAt)}",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                    )
                }
                HkoRemoteImage(alert.iconUrl, alert.title, Modifier.size(42.dp), alertFallback(alert))
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.3f))
            Spacer(Modifier.height(6.dp))
            Text(alert.content, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Justify)
        }
    }
}

@Composable
fun ForecastScreen(snapshot: WeatherSnapshot, pageColour: Color) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val hasNineDaySummary = snapshot.nineDayForecast.generalSituation.isNotBlank()
    val summaryOffset = if (hasNineDaySummary) 1 else 0
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasNineDaySummary) {
            item {
                MetroTile("forecast-summary", pageColour, Modifier.fillMaxWidth()) {
                    Column {
                        Text("九天天氣概況", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Light)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            snapshot.nineDayForecast.generalSituation,
                            color = Color.White,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            textAlign = TextAlign.Justify,
                        )
                        Text(formatHkoTime(snapshot.nineDayForecast.updatedAt), color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
                    }
                }
            }
        }
        itemsIndexed(snapshot.nineDayForecast.days, key = { _, day -> day.date }) { index, day ->
            var expanded by remember(day.date) { mutableStateOf(false) }
            ExpandableMetroTile(
                seed = "forecast:${day.date}",
                background = pageColour,
                expanded = expanded,
                onExpandedChange = {
                    expanded = it
                    if (it) {
                        scope.launch {
                            if (!reduceMotion) delay(150)
                            listState.animateScrollToItem(index + summaryOffset)
                        }
                    }
                },
                collapsed = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(76.dp)) {
                        Text(day.weekday, color = Color.White, fontSize = 22.sp)
                        Text(day.date, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                    HkoRemoteImage(hkoWeatherIconUrl(day.iconCode), day.description, Modifier.size(44.dp))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${day.minTemperatureC.roundToInt()}° – ${day.maxTemperatureC.roundToInt()}°",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
                },
                expandedContent = {
                Text(day.description, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Justify)
                Spacer(Modifier.height(5.dp))
                Text(day.wind, color = Color.White.copy(alpha = 0.82f), fontSize = 14.sp)
                Text(
                    "濕度 ${day.minHumidityPercent}–${day.maxHumidityPercent}% · 顯著降雨概率 ${day.precipitationProbability}",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                )
                },
            )
        }
    }
}

@Composable
private fun LocalForecastDetails(forecast: LocalForecast) {
    OverviewSection("熱帶氣旋消息", forecast.tropicalCycloneInfo)
    OverviewSection(forecast.forecastPeriod, forecast.forecastDescription)
    OverviewSection("展望", forecast.outlook)
    OverviewSection("火險", forecast.fireDangerWarning)
}

@Composable
fun ToolsScreen(pageColour: Color) {
    val context = LocalContext.current
    val tools = listOf(
        Triple("rainfall", "定點降雨及閃電預報", "https://maps.weather.gov.hk/ocf/index_uc.html?data=ncrf"),
        Triple("radar", "香港天文台雷達圖像", "https://www.hko.gov.hk/tc/wxinfo/radars/radar-range.htm"),
        Triple("cyclone", "熱帶氣旋位置及路徑", "https://www.hko.gov.hk/tc/wxinfo/currwx/tc_gis.htm"),
        Triple("lightning", "閃電位置資訊", "https://maps.weather.gov.hk/llis/llis.htm"),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { Text("官方香港天文台工具將於瀏覽器開啟。", color = LocalMetroSubText.current) }
        itemsIndexed(tools) { _, tool ->
            MetroTile(
                seed = tool.first,
                background = pageColour,
                modifier = Modifier.fillMaxWidth().height(142.dp),
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, tool.third.toUri())) },
            ) {
                Column(Modifier.fillMaxSize()) {
                    Text("official tool", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(tool.second, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Light)
                    Text("open ↗", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: UiSettings,
    pageColour: Color,
    onPageColourChange: (PageColourSlot, Long) -> Unit,
    onTextScaleChange: (Float) -> Unit,
    onReduceMotionChange: (Boolean) -> Unit,
    onHighContrastChange: (Boolean) -> Unit,
    onPreciseLocationChange: (Boolean) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onClearCache: () -> Unit,
) {
    val accents = listOf(0xFF1BA1E2, 0xFF00A300, 0xFFA200FF, 0xFFE671B8, 0xFFF09609, 0xFFE51400)
    var selectedPage by rememberSaveable { mutableStateOf(PageColourSlot.CURRENT) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            MetroTile("page-colours", pageColour, Modifier.fillMaxWidth()) {
                Column {
                    SettingTitle("page accents", "為每個 Pivot 頁面設定局部強調色")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PageColourSlot.entries.forEach { slot ->
                            val selected = selectedPage == slot
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .background(argbColor(settings.pageColours.colour(slot)))
                                    .clickable { selectedPage = slot }
                                    .padding(horizontal = 3.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (selected) "✓ ${slot.label}" else slot.label,
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${selectedPage.label} colour",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accents.forEach { value ->
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .background(argbColor(value))
                                    .clickable { onPageColourChange(selectedPage, value) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (settings.pageColours.colour(selectedPage) == value) {
                                    Text("✓", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            MetroTile("text-settings", pageColour, Modifier.fillMaxWidth()) {
                Column {
                    SettingTitle("text size", "${(settings.textScale * 100).roundToInt()}%")
                    Slider(
                        value = settings.textScale,
                        onValueChange = onTextScaleChange,
                        valueRange = 0.9f..1.5f,
                        steps = 5,
                        colors = metroSliderColors(),
                    )
                }
            }
        }
        item { SettingToggleTile("reduce-motion", "reduce motion", "使用短淡化過場，減少大幅移動", pageColour, settings.reduceMotion, onReduceMotionChange) }
        item { SettingToggleTile("contrast", "high contrast", "提高次要文字對比度", pageColour, settings.highContrast, onHighContrastChange) }
        item { SettingToggleTile("location", "precise location", "使用精確定位及香港街區解析", pageColour, settings.preciseLocation, onPreciseLocationChange) }
        item { SettingToggleTile("notifications", "weather notifications", "訂閱香港天文台警告更新", pageColour, settings.notificationsEnabled, onNotificationsChange) }
        item {
            MetroTile("cache", pageColour, Modifier.fillMaxWidth(), onClick = onClearCache) {
                Column {
                    SettingTitle("clear cache", "移除離線天氣資料並重新同步")
                    Text("clear now", color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
                }
            }
        }
        item {
            Text(
                "Weather Metro ${BuildConfig.VERSION_NAME}\nWeather: Hong Kong Observatory first\nHourly estimates: Open-Meteo",
                color = LocalMetroSubText.current,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun SettingToggleTile(
    seed: String,
    title: String,
    description: String,
    pageColour: Color,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    MetroTile(seed, pageColour, Modifier.fillMaxWidth(), onClick = { onChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SettingTitle(title, description) }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun SettingTitle(title: String, description: String) {
    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
    Text(description, color = LocalMetroSubText.current, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun metroSliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.Black.copy(alpha = 0.55f),
    activeTickColor = Color.Black.copy(alpha = 0.4f),
    inactiveTickColor = Color.White.copy(alpha = 0.5f),
)

private fun alertColor(severity: AlertSeverity) = when (severity) {
    AlertSeverity.URGENT -> Color(0xFFE51400)
    AlertSeverity.WARNING -> Color(0xFFF09609)
    AlertSeverity.ADVISORY -> Color(0xFF339933)
    AlertSeverity.TIP -> Color(0xFFB81B53)
}

private fun alertFallback(alert: WeatherAlert): String = when {
    alert.isTip -> "i"
    alert.code == "WTS" -> "ϟ"
    alert.code.startsWith("WRAIN") -> "☂"
    alert.code.startsWith("TC") -> "▲"
    else -> "!"
}

private fun hkoWeatherIconUrl(code: Int): String =
    "https://www.hko.gov.hk/images/HKOWxIconOutline/pic$code.png"

private fun formatHkoTime(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        val instant = Instant.parse(value)
        DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.of("Asia/Hong_Kong")).format(instant)
    }.getOrElse {
        value.replace("T", " ").take(16)
    }
}

private fun locationContextLine(location: LocationInfo): String {
    val labelKey = locationKey(location.label)
    val districtKey = locationKey(location.district)
    val stationKey = locationKey(location.stationName)
    return buildList {
        if (districtKey != labelKey) add(location.district)
        if (stationKey != labelKey && stationKey != districtKey) {
            add("${location.stationName}觀測站")
        }
        if (isEmpty()) add("香港天文台觀測站")
    }.distinct().joinToString(" · ")
}

private fun locationKey(value: String): String = value
    .lowercase()
    .replace("觀測站", "")
    .replace("天氣站", "")
    .replace(" district", "")
    .replace("區", "")
    .filter { it.isLetterOrDigit() }

private fun Number?.display(suffix: String): String = when (this) {
    null -> "--"
    is Double -> if (this % 1.0 == 0.0) "${roundToInt()}$suffix" else "${"%.1f".format(Locale.US, this)}$suffix"
    else -> "$this$suffix"
}
