package com.weather.metro.ui.storm

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormPointType
import com.weather.metro.domain.storm.StormTrack
import com.weather.metro.domain.storm.StormWindRadii
import com.weather.metro.ui.tools.destroyAfterToolTransition
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillAntialias
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.sources.GeoJsonSource
import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private const val STORM_MAP_MIN_ZOOM = 1.5
private const val STORM_MAP_MAX_ZOOM = 9.0
private const val STORM_MAP_INITIAL_ZOOM = 3.1
private const val STORM_MAP_SINGLE_POINT_ZOOM = 5.5
private const val STORM_EARTH_RADIUS_KM = 6371.0088
private const val STORM_CIRCLE_SEGMENTS = 48
private const val STORM_ANALYSIS_MARKER_INTERVAL_HOURS = 6L
private const val SELECTED_POINT_SOURCE = "storm-selected-point"
private const val HONG_KONG_REFERENCE_SOURCE = "storm-hong-kong-reference"
private const val HONG_KONG_REFERENCE_LAT = 22.3023
private const val HONG_KONG_REFERENCE_LON = 114.1746
private const val EMPTY_GEO_JSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"

private val STORM_BASE_STYLE = """
{
  "version": 8,
  "name": "Weather Metro CARTO Dark Storm",
  "sources": {
    "carto-dark": {
      "type": "raster",
      "tiles": [
        "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "https://c.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "https://d.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"
      ],
      "tileSize": 256,
      "attribution": "© OpenStreetMap © CARTO"
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#101010" } },
    { "id": "carto-dark-layer", "type": "raster", "source": "carto-dark", "minzoom": 0, "maxzoom": 20 }
  ]
}
""".trimIndent()

internal data class StormMapCoordinate(val latitude: Double, val longitude: Double)

internal data class StormMapPointRef(
    val agency: StormAgency,
    val stableKey: String,
    val pointType: StormPointType,
    val pointIndex: Int,
    val anchorXPx: Float? = null,
    val anchorYPx: Float? = null,
)

internal data class StormAgencyMapData(
    val analysisLines: String,
    val forecastLines: String,
    val analysisPoints: String,
    val forecastPoints: String,
    val probabilityPolygons: String,
    val windPolygons: String,
    val boundsCoordinates: List<StormMapCoordinate>,
)

private data class StormAgencySources(
    val analysisLines: GeoJsonSource,
    val forecastLines: GeoJsonSource,
    val analysisPoints: GeoJsonSource,
    val forecastPoints: GeoJsonSource,
    val probabilityPolygons: GeoJsonSource,
    val windPolygons: GeoJsonSource,
)

@Composable
internal fun StormMapLibreSurface(
    tracksByAgency: Map<StormAgency, List<StormTrack>>,
    enabledAgencies: Set<StormAgency>,
    selectedPointRef: StormMapPointRef?,
    fitToken: Int,
    onPointSelected: (StormMapPointRef?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestTracks by rememberUpdatedState(tracksByAgency)
    val latestEnabled by rememberUpdatedState(enabledAgencies)
    val latestSelected by rememberUpdatedState(selectedPointRef)
    val latestOnPointSelected by rememberUpdatedState(onPointSelected)
    val agencySources = remember { mutableMapOf<StormAgency, StormAgencySources>() }
    var selectedSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleGeneration by remember { mutableIntStateOf(0) }

    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context, null)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .minZoomPreference(STORM_MAP_MIN_ZOOM)
            .maxZoomPreference(STORM_MAP_MAX_ZOOM)
            .camera(
                CameraPosition.Builder()
                    .target(LatLng(20.0, 135.0))
                    .zoom(STORM_MAP_INITIAL_ZOOM)
                    .build(),
            )
        MapView(context, options).also { it.onCreate(null) }
    }

    DisposableEffect(mapView, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) mapView.onDestroy()
            else mapView.destroyAfterToolTransition()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.setMinZoomPreference(STORM_MAP_MIN_ZOOM)
            readyMap.setMaxZoomPreference(STORM_MAP_MAX_ZOOM)
            readyMap.uiSettings.setCompassEnabled(false)
            readyMap.uiSettings.setLogoEnabled(false)
            readyMap.uiSettings.setAttributionEnabled(false)
            readyMap.uiSettings.setRotateGesturesEnabled(false)
            readyMap.uiSettings.setTiltGesturesEnabled(false)
            readyMap.setStyle(Style.Builder().fromJson(STORM_BASE_STYLE)) { style ->
                agencySources.clear()
                StormAgency.entries.forEach { agency -> agencySources[agency] = addAgencyLayers(style, agency) }
                addHongKongReferenceLayers(style)
                selectedSource = addSelectedPointLayers(style)
                styleGeneration += 1
                updateStormSources(agencySources, latestTracks, latestEnabled)
                selectedSource?.setGeoJson(selectedPointGeoJson(latestSelected, latestTracks))
                fitStormCamera(readyMap, latestTracks, latestEnabled)
            }
            readyMap.addOnMapClickListener { point ->
                val layerIds = stormPointLayerIds(latestEnabled)
                val screenPoint = readyMap.projection.toScreenLocation(point)
                val feature = if (layerIds.isEmpty()) null
                else readyMap.queryRenderedFeatures(screenPoint, *layerIds.toTypedArray()).firstOrNull()
                val ref = feature?.let { hit ->
                    runCatching {
                        StormMapPointRef(
                            agency = StormAgency.fromWire(hit.getStringProperty("agency")),
                            stableKey = hit.getStringProperty("storm"),
                            pointType = StormPointType.fromWire(hit.getStringProperty("kind")),
                            pointIndex = hit.getStringProperty("index").toInt(),
                            anchorXPx = screenPoint.x,
                            anchorYPx = screenPoint.y,
                        )
                    }.getOrNull()
                }
                latestOnPointSelected(ref)
                ref != null
            }
        }
    }

    LaunchedEffect(tracksByAgency, enabledAgencies, styleGeneration) {
        if (styleGeneration > 0) updateStormSources(agencySources, tracksByAgency, enabledAgencies)
    }

    LaunchedEffect(selectedPointRef, tracksByAgency, styleGeneration) {
        if (styleGeneration > 0) selectedSource?.setGeoJson(selectedPointGeoJson(selectedPointRef, tracksByAgency))
    }

    LaunchedEffect(tracksByAgency, enabledAgencies, fitToken, map, styleGeneration) {
        if (styleGeneration > 0) map?.let { fitStormCamera(it, tracksByAgency, enabledAgencies) }
    }

    Box(modifier = modifier.background(Color(0xFF101010))) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    }
}

private fun addHongKongReferenceLayers(style: Style) {
    val feature = JSONObject()
        .put("type", "Feature")
        .put("properties", JSONObject().put("label", "香港"))
        .put(
            "geometry",
            JSONObject()
                .put("type", "Point")
                .put("coordinates", JSONArray().put(HONG_KONG_REFERENCE_LON).put(HONG_KONG_REFERENCE_LAT)),
        )
    val geoJson = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", JSONArray().put(feature))
        .toString()
    val source = GeoJsonSource(HONG_KONG_REFERENCE_SOURCE, geoJson)
    style.addSource(source)
    style.addLayer(
        CircleLayer("storm-hong-kong-halo", HONG_KONG_REFERENCE_SOURCE).withProperties(
            circleColor(AndroidColor.WHITE),
            circleRadius(8.0f),
            circleOpacity(0.15f),
            circleStrokeWidth(0f),
        ),
    )
    style.addLayer(
        CircleLayer("storm-hong-kong-point", HONG_KONG_REFERENCE_SOURCE).withProperties(
            circleColor(AndroidColor.rgb(78, 192, 255)),
            circleRadius(4.2f),
            circleOpacity(1.0f),
            circleStrokeColor(AndroidColor.WHITE),
            circleStrokeWidth(1.6f),
        ),
    )
    style.addLayer(
        SymbolLayer("storm-hong-kong-label", HONG_KONG_REFERENCE_SOURCE).withProperties(
            textField("香港"),
            textSize(10.5f),
            textColor(AndroidColor.WHITE),
            textHaloColor(AndroidColor.argb(230, 0, 0, 0)),
            textHaloWidth(1.2f),
            textOffset(arrayOf(0f, -1.45f)),
        ),
    )
}

private fun addSelectedPointLayers(style: Style): GeoJsonSource {
    val source = GeoJsonSource(SELECTED_POINT_SOURCE, EMPTY_GEO_JSON)
    style.addSource(source)
    style.addLayer(
        CircleLayer("storm-selected-point-halo", SELECTED_POINT_SOURCE).withProperties(
            circleColor(toColor(get("agencyColor"))),
            circleRadius(10.5f),
            circleOpacity(0.20f),
            circleStrokeColor(AndroidColor.WHITE),
            circleStrokeWidth(1.0f),
        ),
    )
    style.addLayer(
        CircleLayer("storm-selected-point-ring", SELECTED_POINT_SOURCE).withProperties(
            circleColor(AndroidColor.TRANSPARENT),
            circleRadius(7.5f),
            circleOpacity(1.0f),
            circleStrokeColor(toColor(get("agencyColor"))),
            circleStrokeWidth(3.0f),
        ),
    )
    return source
}

private fun selectedPointGeoJson(
    ref: StormMapPointRef?,
    tracksByAgency: Map<StormAgency, List<StormTrack>>,
): String {
    ref ?: return EMPTY_GEO_JSON
    val track = tracksByAgency[ref.agency].orEmpty().firstOrNull { it.stableKey == ref.stableKey } ?: return EMPTY_GEO_JSON
    val points = when (ref.pointType) {
        StormPointType.ANALYSIS -> track.analysisPoints
        StormPointType.FORECAST -> track.forecastPoints
    }
    val point = points.getOrNull(ref.pointIndex) ?: return EMPTY_GEO_JSON
    val properties = JSONObject()
        .put("agencyColor", stormAgencyMapColorHex(ref.agency))
        .put("time", point.validAt)
    val feature = JSONObject()
        .put("type", "Feature")
        .put("properties", properties)
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(point.longitude).put(point.latitude)))
    return JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(feature)).toString()
}

private fun addAgencyLayers(style: Style, agency: StormAgency): StormAgencySources {
    val prefix = "storm-${agency.name.lowercase()}"
    val color = stormAgencyMapColor(agency)
    val analysisWidth = if (agency == StormAgency.HKO) 2.8f else 2.15f
    val analysisOpacity = if (agency == StormAgency.HKO) 0.94f else 0.74f
    val forecastWidth = if (agency == StormAgency.HKO) 2.45f else 2.20f
    val probabilitySource = GeoJsonSource("$prefix-probability", EMPTY_GEO_JSON)
    val windSource = GeoJsonSource("$prefix-wind", EMPTY_GEO_JSON)
    val analysisLineSource = GeoJsonSource("$prefix-analysis-line", EMPTY_GEO_JSON)
    val forecastLineSource = GeoJsonSource("$prefix-forecast-line", EMPTY_GEO_JSON)
    val analysisPointSource = GeoJsonSource("$prefix-analysis-points", EMPTY_GEO_JSON)
    val forecastPointSource = GeoJsonSource("$prefix-forecast-points", EMPTY_GEO_JSON)

    listOf(probabilitySource, windSource, analysisLineSource, forecastLineSource, analysisPointSource, forecastPointSource)
        .forEach(style::addSource)

    style.addLayer(
        FillLayer("$prefix-probability-fill", probabilitySource.id).withProperties(
            fillColor(color), fillOpacity(0.035f),
            fillOutlineColor(stormMapColorWithAlpha(color, 0.20f)), fillAntialias(true),
        ),
    )
    style.addLayer(
        FillLayer("$prefix-wind-fill", windSource.id).withProperties(
            fillColor(color), fillOpacity(0.025f),
            fillOutlineColor(stormMapColorWithAlpha(color, 0.16f)), fillAntialias(true),
        ),
    )
    style.addLayer(
        LineLayer("$prefix-analysis-line-halo", analysisLineSource.id).withProperties(
            lineColor(AndroidColor.argb(196, 0, 0, 0)), lineWidth(analysisWidth + 2.4f), lineOpacity(0.64f),
        ),
    )
    style.addLayer(
        LineLayer("$prefix-analysis-line-layer", analysisLineSource.id).withProperties(
            lineColor(color), lineWidth(analysisWidth), lineOpacity(analysisOpacity),
        ),
    )
    style.addLayer(
        LineLayer("$prefix-forecast-line-halo", forecastLineSource.id).withProperties(
            lineColor(AndroidColor.argb(174, 0, 0, 0)), lineWidth(forecastWidth + 2.0f), lineOpacity(0.50f),
            lineDasharray(arrayOf(1.4f, 1.2f)),
        ),
    )
    style.addLayer(
        LineLayer("$prefix-forecast-line-layer", forecastLineSource.id).withProperties(
            lineColor(color), lineWidth(forecastWidth), lineOpacity(0.90f),
            lineDasharray(arrayOf(1.4f, 1.2f)),
        ),
    )
    style.addLayer(
        CircleLayer("$prefix-analysis-point-halo", analysisPointSource.id).withProperties(
            circleColor(color), circleRadius(6.5f), circleOpacity(0.10f), circleStrokeWidth(0f),
        ),
    )
    style.addLayer(
        CircleLayer("$prefix-forecast-point-halo", forecastPointSource.id).withProperties(
            circleColor(color), circleRadius(5.8f), circleOpacity(0.07f), circleStrokeWidth(0f),
        ),
    )
    style.addLayer(
        CircleLayer("$prefix-analysis-point-layer", analysisPointSource.id).withProperties(
            circleColor(toColor(get("color"))), circleRadius(4.6f), circleOpacity(0.96f),
            circleStrokeColor(color), circleStrokeWidth(1.5f),
        ),
    )
    style.addLayer(
        CircleLayer("$prefix-forecast-point-layer", forecastPointSource.id).withProperties(
            circleColor(toColor(get("color"))), circleRadius(4.2f), circleOpacity(0.84f),
            circleStrokeColor(color), circleStrokeWidth(1.3f),
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
    AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color),
)

private fun stormPointLayerIds(enabledAgencies: Set<StormAgency>): List<String> = buildList {
    enabledAgencies.forEach { agency ->
        val prefix = "storm-${agency.name.lowercase()}"
        add("$prefix-analysis-point-layer")
        add("$prefix-forecast-point-layer")
    }
}

private fun updateStormSources(
    sources: Map<StormAgency, StormAgencySources>,
    tracksByAgency: Map<StormAgency, List<StormTrack>>,
    enabledAgencies: Set<StormAgency>,
) {
    val hasHkoReference = StormAgency.HKO in enabledAgencies && tracksByAgency[StormAgency.HKO].orEmpty().isNotEmpty()
    StormAgency.entries.forEach { agency ->
        val target = sources[agency] ?: return@forEach
        val data = if (agency in enabledAgencies) {
            buildStormAgencyMapData(
                tracks = tracksByAgency[agency].orEmpty(),
                showFullAnalysisHistory = !hasHkoReference || agency == StormAgency.HKO,
            )
        } else EMPTY_STORM_MAP_DATA
        target.analysisLines.setGeoJson(data.analysisLines)
        target.forecastLines.setGeoJson(data.forecastLines)
        target.analysisPoints.setGeoJson(data.analysisPoints)
        target.forecastPoints.setGeoJson(data.forecastPoints)
        target.probabilityPolygons.setGeoJson(data.probabilityPolygons)
        target.windPolygons.setGeoJson(data.windPolygons)
    }
}

private fun fitStormCamera(
    map: MapLibreMap,
    tracksByAgency: Map<StormAgency, List<StormTrack>>,
    enabledAgencies: Set<StormAgency>,
) {
    val hasHkoReference = StormAgency.HKO in enabledAgencies && tracksByAgency[StormAgency.HKO].orEmpty().isNotEmpty()
    val coordinates = enabledAgencies
        .flatMap { agency ->
            buildStormAgencyMapData(
                tracks = tracksByAgency[agency].orEmpty(),
                showFullAnalysisHistory = !hasHkoReference || agency == StormAgency.HKO,
            ).boundsCoordinates
        }
        .distinctBy { "${it.latitude}:${it.longitude}" }
    if (coordinates.isEmpty()) return
    if (coordinates.size == 1) {
        val point = coordinates.first()
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(STORM_MAP_SINGLE_POINT_ZOOM)
            .build()
        return
    }
    val builder = LatLngBounds.Builder()
    coordinates.forEach { point -> builder.include(LatLng(point.latitude, point.longitude)) }
    map.getCameraForLatLngBounds(builder.build(), intArrayOf(44, 176, 44, 190))?.let { map.cameraPosition = it }
}

internal fun buildStormAgencyMapData(
    tracks: List<StormTrack>,
    showFullAnalysisHistory: Boolean = true,
): StormAgencyMapData {
    val analysisLineFeatures = JSONArray()
    val forecastLineFeatures = JSONArray()
    val analysisPointFeatures = JSONArray()
    val forecastPointFeatures = JSONArray()
    val probabilityFeatures = JSONArray()
    val windFeatures = JSONArray()
    val bounds = mutableListOf<StormMapCoordinate>()

    tracks.forEach { track ->
        val displayedAnalysisPoints = if (showFullAnalysisHistory) track.analysisPoints else track.analysisPoints.takeLast(1)
        val analysis = displayedAnalysisPoints.map { StormMapCoordinate(it.latitude, it.longitude) }
        val forecast = track.forecastPoints.map { StormMapCoordinate(it.latitude, it.longitude) }
        bounds += analysis
        bounds += forecast

        if (analysis.size >= 2) analysisLineFeatures.put(lineFeature(analysis, track.stableKey, "analysis"))
        val forecastPath = buildList {
            track.analysisPoints.lastOrNull()?.let { add(StormMapCoordinate(it.latitude, it.longitude)) }
            addAll(forecast)
        }
        if (forecastPath.size >= 2) forecastLineFeatures.put(lineFeature(forecastPath, track.stableKey, "forecast"))

        stormAnalysisPointIndexes(track.analysisPoints, showFullAnalysisHistory).forEach { index ->
            val point = track.analysisPoints[index]
            analysisPointFeatures.put(
                pointFeature(point.longitude, point.latitude, track.agency, track.stableKey, StormPointType.ANALYSIS, index, stormIntensityColorHex(point)),
            )
        }
        track.forecastPoints.forEachIndexed { index, point ->
            forecastPointFeatures.put(
                pointFeature(point.longitude, point.latitude, track.agency, track.stableKey, StormPointType.FORECAST, index, stormIntensityColorHex(point)),
            )
            point.probabilityRadiusKm?.takeIf { it > 0.0 }?.let { radius ->
                probabilityFeatures.put(polygonFeature(stormCirclePolygonCoordinates(point.latitude, point.longitude, radius), track.stableKey, "probability"))
            }
        }

        track.analysisPoints.lastOrNull()?.let { current ->
            current.windRadii.forEach { radii ->
                if (maximumWindRadius(radii) <= 0.0) return@forEach
                windFeatures.put(
                    polygonFeature(
                        stormWindPolygonCoordinates(current.latitude, current.longitude, radii),
                        track.stableKey,
                        radii.level ?: "wind",
                    ),
                )
            }
        }
    }

    return StormAgencyMapData(
        analysisLines = featureCollection(analysisLineFeatures),
        forecastLines = featureCollection(forecastLineFeatures),
        analysisPoints = featureCollection(analysisPointFeatures),
        forecastPoints = featureCollection(forecastPointFeatures),
        probabilityPolygons = featureCollection(probabilityFeatures),
        windPolygons = featureCollection(windFeatures),
        boundsCoordinates = bounds,
    )
}

private fun stormAnalysisPointIndexes(points: List<StormPoint>, showFullAnalysisHistory: Boolean): List<Int> {
    if (points.isEmpty()) return emptyList()
    if (!showFullAnalysisHistory) return listOf(points.lastIndex)
    if (points.size <= 2) return points.indices.toList()
    val result = mutableListOf(0)
    var lastAcceptedTime = stormPointEpochMillis(points.first())
    for (index in 1 until points.lastIndex) {
        val time = stormPointEpochMillis(points[index])
        val accept = when {
            time == null || lastAcceptedTime == null -> true
            else -> time - lastAcceptedTime >= STORM_ANALYSIS_MARKER_INTERVAL_HOURS * 3_600_000L - 60_000L
        }
        if (accept) {
            result += index
            lastAcceptedTime = time
        }
    }
    if (result.last() != points.lastIndex) result += points.lastIndex
    return result
}

private fun stormPointEpochMillis(point: StormPoint): Long? = runCatching { Instant.parse(point.validAt).toEpochMilli() }.getOrNull()

internal fun stormCirclePolygonCoordinates(
    latitude: Double,
    longitude: Double,
    radiusKm: Double,
    segments: Int = STORM_CIRCLE_SEGMENTS,
): List<StormMapCoordinate> {
    require(radiusKm >= 0.0) { "Storm radius must be non-negative" }
    require(segments >= 8) { "Storm circle requires at least 8 segments" }
    if (radiusKm == 0.0) return listOf(StormMapCoordinate(latitude, longitude))
    val points = (0 until segments).map { index ->
        destinationPoint(latitude, longitude, index.toDouble() * 360.0 / segments.toDouble(), radiusKm)
    }.toMutableList()
    points += points.first()
    return points
}

internal fun stormWindPolygonCoordinates(
    latitude: Double,
    longitude: Double,
    radii: StormWindRadii,
    segments: Int = STORM_CIRCLE_SEGMENTS,
): List<StormMapCoordinate> {
    require(segments >= 8) { "Storm wind polygon requires at least 8 segments" }
    val points = (0 until segments).map { index ->
        val bearing = index.toDouble() * 360.0 / segments.toDouble()
        val radius = when {
            bearing < 90.0 -> radii.northEastKm
            bearing < 180.0 -> radii.southEastKm
            bearing < 270.0 -> radii.southWestKm
            else -> radii.northWestKm
        }.coerceAtLeast(0.0)
        destinationPoint(latitude, longitude, bearing, radius)
    }.toMutableList()
    points += points.first()
    return points
}

private fun destinationPoint(latitude: Double, longitude: Double, bearingDegrees: Double, distanceKm: Double): StormMapCoordinate {
    if (distanceKm <= 0.0) return StormMapCoordinate(latitude, longitude)
    val angularDistance = distanceKm / STORM_EARTH_RADIUS_KM
    val bearing = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(latitude)
    val lon1 = Math.toRadians(longitude)
    val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing))
    val lon2 = lon1 + atan2(
        sin(bearing) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2),
    )
    return StormMapCoordinate(
        latitude = Math.toDegrees(lat2),
        longitude = ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0,
    )
}

private fun lineFeature(coordinates: List<StormMapCoordinate>, stableKey: String, kind: String): JSONObject = JSONObject()
    .put("type", "Feature")
    .put("properties", JSONObject().put("storm", stableKey).put("kind", kind))
    .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinateArray(coordinates)))

private fun pointFeature(
    longitude: Double,
    latitude: Double,
    agency: StormAgency,
    stableKey: String,
    kind: StormPointType,
    pointIndex: Int,
    intensityColor: String,
): JSONObject = JSONObject()
    .put("type", "Feature")
    .put(
        "properties",
        JSONObject()
            .put("agency", agency.name)
            .put("storm", stableKey)
            .put("kind", kind.wireValue)
            .put("index", pointIndex.toString())
            .put("color", intensityColor),
    )
    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(longitude).put(latitude)))

private fun polygonFeature(coordinates: List<StormMapCoordinate>, stableKey: String, kind: String): JSONObject = JSONObject()
    .put("type", "Feature")
    .put("properties", JSONObject().put("storm", stableKey).put("kind", kind))
    .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(coordinateArray(coordinates))))

private fun coordinateArray(coordinates: List<StormMapCoordinate>): JSONArray = JSONArray().apply {
    coordinates.forEach { point -> put(JSONArray().put(point.longitude).put(point.latitude)) }
}

private fun featureCollection(features: JSONArray): String = JSONObject()
    .put("type", "FeatureCollection")
    .put("features", features)
    .toString()

private fun maximumWindRadius(radii: StormWindRadii): Double = max(
    max(radii.northEastKm, radii.southEastKm),
    max(radii.southWestKm, radii.northWestKm),
)

private fun stormAgencyMapColor(agency: StormAgency): Int = when (agency) {
    StormAgency.HKO -> AndroidColor.WHITE
    StormAgency.CMA -> AndroidColor.rgb(255, 75, 85)
    StormAgency.JMA -> AndroidColor.rgb(0, 216, 255)
    StormAgency.CWA -> AndroidColor.rgb(255, 234, 0)
}

private fun stormAgencyMapColorHex(agency: StormAgency): String = when (agency) {
    StormAgency.HKO -> "#FFFFFF"
    StormAgency.CMA -> "#FF4B55"
    StormAgency.JMA -> "#00D8FF"
    StormAgency.CWA -> "#FFEA00"
}

private val EMPTY_STORM_MAP_DATA = StormAgencyMapData(
    analysisLines = EMPTY_GEO_JSON,
    forecastLines = EMPTY_GEO_JSON,
    analysisPoints = EMPTY_GEO_JSON,
    forecastPoints = EMPTY_GEO_JSON,
    probabilityPolygons = EMPTY_GEO_JSON,
    windPolygons = EMPTY_GEO_JSON,
    boundsCoordinates = emptyList(),
)
