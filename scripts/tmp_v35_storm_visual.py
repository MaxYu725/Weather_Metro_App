from pathlib import Path

live_path = Path('app/src/main/java/com/weather/metro/ui/storm/StormLivePanel.kt')
map_path = Path('app/src/main/java/com/weather/metro/ui/storm/StormMapLibre.kt')

live = live_path.read_text()
map_src = map_path.read_text()


def replace_once(text, old, new, label):
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'anchor missing: {label}')
    return text.replace(old, new, 1)

# Imports for rounded Glass agency chips.
live = replace_once(
    live,
    'import androidx.compose.foundation.rememberScrollState\n',
    'import androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.shape.CircleShape\nimport androidx.compose.foundation.shape.RoundedCornerShape\n',
    'storm chip shapes',
)
live = replace_once(
    live,
    'import androidx.compose.ui.Alignment\n',
    'import androidx.compose.ui.Alignment\nimport androidx.compose.ui.draw.clip\n',
    'storm chip clip',
)

# Compact source indicator: dots instead of flat bars.
old_indicator = '''            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .background(if (hkoEnabled) agencyColour(StormAgency.HKO) else Color(0xFF555555)),
                )
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .background(if (cmaEnabled) agencyColour(StormAgency.CMA) else Color(0xFF555555)),
                )
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .background(if (jmaEnabled) agencyColour(StormAgency.JMA) else Color(0xFF555555)),
                )
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .background(if (cwaEnabled) agencyColour(StormAgency.CWA) else Color(0xFF555555)),
                )
            }
'''
new_indicator = '''            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    StormAgency.HKO to hkoEnabled,
                    StormAgency.CMA to cmaEnabled,
                    StormAgency.JMA to jmaEnabled,
                    StormAgency.CWA to cwaEnabled,
                ).forEach { (agency, enabled) ->
                    Box(
                        Modifier
                            .width(5.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (enabled) agencyColour(agency) else Color(0xFF4A4A4A)),
                    )
                }
            }
'''
live = replace_once(live, old_indicator, new_indicator, 'compact agency indicator')

start = live.index('@Composable\nprivate fun StormAgencyChip(')
end = live.index('\n@Composable\nprivate fun StormBottomIsland(', start)
new_chip = '''@Composable
private fun StormAgencyChip(
    source: StormAgencyHostState,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val border by animateColorAsState(
        targetValue = if (enabled) accent.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.12f),
        animationSpec = tween(if (reduceMotion) 100 else 180),
        label = "storm agency border",
    )
    val background by animateColorAsState(
        targetValue = if (enabled) accent.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.34f),
        animationSpec = tween(if (reduceMotion) 100 else 180),
        label = "storm agency background",
    )
    Column(
        modifier = modifier
            .heightIn(min = 58.dp)
            .metroPressMotion(
                interactionSource = interactionSource,
                preset = MetroPressPreset.Chip,
            )
            .clip(shape)
            .background(background, shape)
            .border(1.dp, border, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(5.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (enabled) accent else Color(0xFF555555)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (enabled) "✓ ${source.agency.name}" else source.agency.name,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.42f),
                fontSize = 12.sp,
                fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
        Text(
            text = sourceStateLabel(source.liveState, source.refreshing),
            color = if (enabled) sourceStateColour(source.liveState, accent) else Color.White.copy(alpha = 0.32f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
'''
live = live[:start] + new_chip + live[end:]

live = live.replace(
    '"點擊路徑點查看完整資料 · 路徑線按機構色 · 路徑點按強度色"',
    '"實線＝分析 · 虛線＝預報 · 外圈＝機構 · 點色＝強度"',
)

# Route hierarchy: background polygons -> line halo/core -> point halo/core.
start = map_src.index('private fun addAgencyLayers(style: Style, agency: StormAgency): StormAgencySources {')
end = map_src.index('\nprivate fun stormPointLayerIds(', start)
new_layers = '''private fun addAgencyLayers(style: Style, agency: StormAgency): StormAgencySources {
    val prefix = "storm-${agency.name.lowercase()}"
    val color = stormAgencyMapColor(agency)
    val probabilitySource = GeoJsonSource("$prefix-probability", EMPTY_GEO_JSON)
    val windSource = GeoJsonSource("$prefix-wind", EMPTY_GEO_JSON)
    val analysisLineSource = GeoJsonSource("$prefix-analysis-line", EMPTY_GEO_JSON)
    val forecastLineSource = GeoJsonSource("$prefix-forecast-line", EMPTY_GEO_JSON)
    val analysisPointSource = GeoJsonSource("$prefix-analysis-points", EMPTY_GEO_JSON)
    val forecastPointSource = GeoJsonSource("$prefix-forecast-points", EMPTY_GEO_JSON)

    listOf(
        probabilitySource,
        windSource,
        analysisLineSource,
        forecastLineSource,
        analysisPointSource,
        forecastPointSource,
    ).forEach(style::addSource)

    style.addLayer(
        FillLayer("$prefix-probability-fill", probabilitySource.id).withProperties(
            fillColor(color), fillOpacity(0.060f),
            fillOutlineColor(stormMapColorWithAlpha(color, 0.28f)), fillAntialias(true),
        ),
    )
    style.addLayer(
        FillLayer("$prefix-wind-fill", windSource.id).withProperties(
            fillColor(color), fillOpacity(0.045f),
            fillOutlineColor(stormMapColorWithAlpha(color, 0.22f)), fillAntialias(true),
        ),
    )

    style.addLayer(
        LineLayer("$prefix-analysis-line-halo", analysisLineSource.id).withProperties(
            lineColor(AndroidColor.argb(210, 0, 0, 0)), lineWidth(6.0f), lineOpacity(0.72f),
        ),
    )
    style.addLayer(
        LineLayer("$prefix-analysis-line-layer", analysisLineSource.id).withProperties(
            lineColor(color), lineWidth(3.2f), lineOpacity(0.98f),
        ),
    )
    style.addLayer(
        LineLayer("$prefix-forecast-line-halo", forecastLineSource.id).withProperties(
            lineColor(AndroidColor.argb(188, 0, 0, 0)), lineWidth(4.6f), lineOpacity(0.58f),
            lineDasharray(arrayOf(1.4f, 1.2f)),
        ),
    )
    style.addLayer(
        LineLayer("$prefix-forecast-line-layer", forecastLineSource.id).withProperties(
            lineColor(color), lineWidth(2.2f), lineOpacity(0.80f),
            lineDasharray(arrayOf(1.4f, 1.2f)),
        ),
    )

    style.addLayer(
        CircleLayer("$prefix-analysis-point-halo", analysisPointSource.id).withProperties(
            circleColor(color), circleRadius(8.0f), circleOpacity(0.15f),
            circleStrokeWidth(0f),
        ),
    )
    style.addLayer(
        CircleLayer("$prefix-forecast-point-halo", forecastPointSource.id).withProperties(
            circleColor(color), circleRadius(7.0f), circleOpacity(0.10f),
            circleStrokeWidth(0f),
        ),
    )
    style.addLayer(
        CircleLayer("$prefix-analysis-point-layer", analysisPointSource.id).withProperties(
            circleColor(toColor(get("color"))), circleRadius(5.4f), circleOpacity(0.98f),
            circleStrokeColor(color), circleStrokeWidth(2.0f),
        ),
    )
    style.addLayer(
        CircleLayer("$prefix-forecast-point-layer", forecastPointSource.id).withProperties(
            circleColor(toColor(get("color"))), circleRadius(4.7f), circleOpacity(0.86f),
            circleStrokeColor(color), circleStrokeWidth(1.5f),
        ),
    )

    return StormAgencySources(
        analysisLines = analysisLineSource,
        forecastLines = forecastLineSource,
        analysisPoints = analysisPointSource,
        forecastPoints = forecastPointSource,
        probabilityPolygons = probabilitySource,
        windPolygons = windSource,
    )
}

private fun stormMapColorWithAlpha(color: Int, alpha: Float): Int = AndroidColor.argb(
    (alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
    AndroidColor.red(color),
    AndroidColor.green(color),
    AndroidColor.blue(color),
)
'''
map_src = map_src[:start] + new_layers + map_src[end:]

live_path.write_text(live)
map_path.write_text(map_src)
