package com.weather.metro.data.storm

import com.weather.metro.data.tools.ToolEndpoints
import com.weather.metro.domain.storm.AgencyLiveResult
import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormLiveState
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormPointType
import com.weather.metro.domain.storm.StormTrack
import com.weather.metro.domain.storm.StormWindRadii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

internal interface StormLiveHttpTransport {
    suspend fun getText(
        url: String,
        accept: String,
        timeoutMs: Int,
    ): String
}

internal class UrlConnectionStormLiveTransport : StormLiveHttpTransport {
    override suspend fun getText(
        url: String,
        accept: String,
        timeoutMs: Int,
    ): String = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "WeatherMetroApp/1.1 StormLive")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching {
                    JSONObject(text).optString("detail").ifBlank { JSONObject(text).optString("error") }
                }.getOrNull().orEmpty()
                error("HTTP $code${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
            }
            require(text.isNotBlank()) { "Storm live response is empty" }
            text
        } finally {
            connection.disconnect()
        }
    }
}

internal class StormLiveLoader(
    private val transport: StormLiveHttpTransport = UrlConnectionStormLiveTransport(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun loadAll(): List<AgencyLiveResult> = supervisorScope {
        StormAgency.entries.map { agency ->
            async { loadAgency(agency) }
        }.awaitAll()
    }

    suspend fun loadAgency(agency: StormAgency): AgencyLiveResult = try {
        val storms = loadAgencyOrThrow(agency)
        AgencyLiveResult(
            agency = agency,
            state = if (storms.isEmpty()) StormLiveState.EMPTY else StormLiveState.OK,
            message = if (storms.isEmpty()) emptyMessage(agency) else "${storms.size} active storm(s)",
            updatedAt = storms.mapNotNull { it.bulletinTime }.maxOrNull(),
            storms = storms,
        )
    } catch (error: Exception) {
        AgencyLiveResult(
            agency = agency,
            state = StormLiveState.ERROR,
            message = error.message ?: "${agency.name} live load failed",
            updatedAt = null,
            storms = emptyList(),
        )
    }

    internal suspend fun loadAgencyOrThrow(agency: StormAgency): List<StormTrack> = when (agency) {
        StormAgency.HKO -> loadHko()
        StormAgency.CMA -> loadCma()
        StormAgency.JMA -> loadJma()
        StormAgency.CWA -> loadCwa()
    }

    private suspend fun loadHko(): List<StormTrack> {
        val listXml = getXml(ToolEndpoints.stormHkoListLive())
        val references = parseHkoList(listXml)
        if (references.isEmpty()) return emptyList()
        val tracks = supervisorScope {
            references.map { reference ->
                async {
                    runCatching {
                        val xml = getXml(ToolEndpoints.stormHkoTrackLive(reference.url))
                        parseHkoTrack(xml, reference)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        if (tracks.isEmpty()) error("HKO active list exists but track parsing failed")
        return tracks
    }

    private suspend fun loadCma(): List<StormTrack> {
        val stamp = nowMillis().coerceAtLeast(0L)
        val list = parseJsonpObject(getJson(ToolEndpoints.stormCmaListLive(stamp)))
            .optJSONArray("typhoonList") ?: JSONArray()
        val active = buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONArray(index) ?: continue
                val state = item.optString(7).trim().lowercase()
                if (state in setOf("start", "active", "1")) add(item)
            }
        }
        if (active.isEmpty()) return emptyList()
        val tracks = supervisorScope {
            active.map { item ->
                async {
                    runCatching {
                        val id = item.optString(0).trim()
                        require(id.isNotBlank()) { "CMA storm id missing" }
                        val payload = getJson(ToolEndpoints.stormCmaDetailLive(id, nowMillis().coerceAtLeast(0L)))
                        parseCmaTrack(
                            parseJsonpObject(payload),
                            CmaReference(
                                id = id,
                                nameEn = item.optString(1).trim(),
                                nameZh = item.optString(2).trim(),
                            ),
                        )
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        if (tracks.isEmpty()) error("CMA active storm exists but BABJ forecast is unavailable")
        return tracks
    }

    private suspend fun loadJma(): List<StormTrack> {
        var candidates: List<JmaCandidate> = emptyList()
        var lastError: Throwable? = null
        for (longFeed in listOf(false, true)) {
            try {
                candidates = parseJmaFeed(getXml(ToolEndpoints.stormJmaFeedLive(longFeed)))
                if (candidates.isNotEmpty()) break
            } catch (error: Throwable) {
                lastError = error
            }
        }
        if (candidates.isEmpty()) {
            if (lastError != null) throw IllegalStateException(lastError.message ?: "JMA feed failed", lastError)
            return emptyList()
        }
        val tracks = supervisorScope {
            candidates.map { candidate ->
                async {
                    runCatching {
                        parseJmaTrack(
                            getXml(ToolEndpoints.stormJmaDocumentLive(candidate.url)),
                            candidate,
                        )
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        val latestById = linkedMapOf<String, StormTrack>()
        tracks.forEach { track ->
            val key = track.agencyStormId
            val current = latestById[key]
            if (current == null || (track.bulletinTime.orEmpty() > current.bulletinTime.orEmpty())) {
                latestById[key] = track
            }
        }
        if (latestById.isEmpty()) error("JMA VPTW XML parsing failed")
        return latestById.values.toList()
    }

    private suspend fun loadCwa(): List<StormTrack> {
        val root = JSONObject(getJson(ToolEndpoints.stormCwaLive()))
        if (root.has("success") && root.opt("success").toString().equals("false", ignoreCase = true)) {
            error("CWA API reported failure")
        }
        val raw = root.optJSONObject("records")
            ?.optJSONObject("TropicalCyclones")
            ?.opt("TropicalCyclone")
        return jsonObjects(raw).mapNotNull(::parseCwaTrack)
    }

    private suspend fun getXml(url: String): String = transport.getText(
        url = url,
        accept = XML_ACCEPT,
        timeoutMs = LIVE_TIMEOUT_MS,
    )

    private suspend fun getJson(url: String): String = transport.getText(
        url = url,
        accept = JSON_ACCEPT,
        timeoutMs = LIVE_TIMEOUT_MS,
    )

    internal fun parseHkoList(xml: String): List<HkoReference> {
        val doc = parseXml(xml)
        return doc.elements("TropicalCyclone").mapNotNull { node ->
            val url = node.text("TropicalCycloneURL")
            if (url.isBlank()) return@mapNotNull null
            HkoReference(
                id = node.text("TropicalCycloneID"),
                nameZh = node.text("TropicalCycloneChineseName"),
                nameEn = node.text("TropicalCycloneEnglishName"),
                url = url,
            )
        }
    }

    internal fun parseHkoTrack(xml: String, reference: HkoReference): StormTrack? {
        val doc = parseXml(xml)
        val bulletinTime = doc.text("BulletinTime").ifBlank { null }
        val report = doc.elements("WeatherReport").firstOrNull() ?: doc.documentElement
        val nameEn = reference.nameEn.ifBlank { report.text("TropicalCycloneName") }.ifBlank { null }
        val nameZh = reference.nameZh.ifBlank { null }
        val analysis = buildList {
            report.elements("PastInformation").mapNotNullTo(this) { parseHkoPoint(it, StormPointType.ANALYSIS) }
            report.elements("AnalysisInformation").mapNotNullTo(this) { parseHkoPoint(it, StormPointType.ANALYSIS) }
        }.dedupePoints()
        val forecast = report.elements("ForecastInformation")
            .mapNotNull { parseHkoPoint(it, StormPointType.FORECAST) }
            .dedupePoints()
        if (analysis.isEmpty() && forecast.isEmpty()) return null
        val id = reference.id.ifBlank { nameEn ?: nameZh ?: "unknown" }
        return StormTrack(
            stableKey = "HKO:$id",
            agency = StormAgency.HKO,
            agencyStormId = id,
            internationalNumber = null,
            nameEn = nameEn,
            nameZh = nameZh,
            bulletinTime = bulletinTime,
            analysisPoints = analysis,
            forecastPoints = forecast,
        )
    }

    private fun parseHkoPoint(node: Element, type: StormPointType): StormPoint? {
        val lat = parseDirectionalCoordinate(node.text("Latitude"), latitude = true) ?: return null
        val lon = parseDirectionalCoordinate(node.text("Longitude"), latitude = false) ?: return null
        val time = node.text("Time").ifBlank { return null }
        return StormPoint(
            validAt = time,
            latitude = lat,
            longitude = lon,
            pointType = type,
            intensityLabel = node.text("Intensity").ifBlank { null },
            intensityCode = null,
            windSpeedMs = node.text("MaximumWind").numberOrNull(),
            pressureHpa = node.text("CentralPressure").numberOrNull(),
            forecastHour = null,
            probabilityRadiusKm = null,
        )
    }

    internal fun parseCmaTrack(root: JSONObject, reference: CmaReference): StormTrack? {
        val storm = root.optJSONArray("typhoon") ?: return null
        val history = storm.optJSONArray(8) ?: JSONArray()
        val analysis = buildList {
            for (index in 0 until history.length()) {
                parseCmaHistoryPoint(history.optJSONArray(index))?.let(::add)
            }
        }.dedupePoints()
        var basePoint: JSONArray? = null
        var forecastArray: JSONArray? = null
        for (index in history.length() - 1 downTo 0) {
            val point = history.optJSONArray(index) ?: continue
            val container = point.optJSONObject(11) ?: continue
            val key = container.keys().asSequence().firstOrNull { it.equals("BABJ", ignoreCase = true) } ?: continue
            val candidate = container.optJSONArray(key) ?: continue
            if (candidate.length() > 0) {
                basePoint = point
                forecastArray = candidate
                break
            }
        }
        val forecast = buildList {
            val values = forecastArray ?: JSONArray()
            for (index in 0 until values.length()) {
                parseCmaForecastPoint(values.optJSONArray(index))?.let(::add)
            }
        }.dedupePoints()
        if (forecast.isEmpty()) return null
        val bulletin = normalizeNmcTime(basePoint?.opt(1)) ?: analysis.lastOrNull()?.validAt
        return StormTrack(
            stableKey = "CMA:${reference.id}",
            agency = StormAgency.CMA,
            agencyStormId = reference.id,
            internationalNumber = null,
            nameEn = reference.nameEn.ifBlank { null },
            nameZh = reference.nameZh.ifBlank { null },
            bulletinTime = bulletin,
            analysisPoints = analysis,
            forecastPoints = forecast,
        )
    }

    private fun parseCmaHistoryPoint(point: JSONArray?): StormPoint? {
        point ?: return null
        val lon = point.optDoubleFinite(4) ?: return null
        val lat = point.optDoubleFinite(5) ?: return null
        val time = normalizeNmcTime(point.opt(1)) ?: return null
        return StormPoint(
            validAt = time,
            latitude = lat,
            longitude = lon,
            pointType = StormPointType.ANALYSIS,
            intensityLabel = point.optString(3).ifBlank { null },
            intensityCode = point.optString(3).ifBlank { null },
            windSpeedMs = point.optDoubleFinite(7),
            pressureHpa = point.optDoubleFinite(6),
            forecastHour = null,
            probabilityRadiusKm = null,
            windRadii = parseCmaWindRadii(point.optJSONArray(10)),
        )
    }

    private fun parseCmaForecastPoint(point: JSONArray?): StormPoint? {
        point ?: return null
        val hour = point.optIntOrNull(0) ?: return null
        val baseTime = normalizeNmcTime(point.opt(1)) ?: return null
        val time = addHours(baseTime, hour) ?: return null
        val lon = point.optDoubleFinite(2) ?: return null
        val lat = point.optDoubleFinite(3) ?: return null
        return StormPoint(
            validAt = time,
            latitude = lat,
            longitude = lon,
            pointType = StormPointType.FORECAST,
            intensityLabel = point.optString(7).ifBlank { null },
            intensityCode = point.optString(7).ifBlank { null },
            windSpeedMs = point.optDoubleFinite(5),
            pressureHpa = point.optDoubleFinite(4),
            forecastHour = hour,
            probabilityRadiusKm = null,
        )
    }

    private fun parseCmaWindRadii(values: JSONArray?): List<StormWindRadii> = buildList {
        values ?: return@buildList
        for (index in 0 until values.length()) {
            val row = values.optJSONArray(index) ?: continue
            add(
                StormWindRadii(
                    level = row.optString(0).ifBlank { null },
                    northEastKm = row.optDoubleFinite(1) ?: 0.0,
                    southEastKm = row.optDoubleFinite(2) ?: 0.0,
                    southWestKm = row.optDoubleFinite(3) ?: 0.0,
                    northWestKm = row.optDoubleFinite(4) ?: 0.0,
                ),
            )
        }
    }

    internal fun parseJmaFeed(xml: String): List<JmaCandidate> {
        val doc = parseXml(xml)
        val seen = mutableSetOf<String>()
        return buildList {
            for (entry in doc.elements("entry")) {
                val title = entry.text("title")
                val updated = entry.text("updated")
                val url = entry.elements("link")
                    .firstNotNullOfOrNull { it.getAttribute("href").takeIf(String::isNotBlank) }
                    .orEmpty()
                val code = Regex("_VPTW(6[0-5])_", RegexOption.IGNORE_CASE)
                    .find(url)?.groupValues?.getOrNull(1)?.let { "VPTW$it" }
                    ?: continue
                if (!title.contains("台風解析・予報情報") && !url.contains("VPTW", ignoreCase = true)) continue
                if (!seen.add(code.uppercase())) continue
                add(JmaCandidate(code = code.uppercase(), title = title, updated = updated, url = url))
                if (size >= 6) break
            }
        }
    }

    internal fun parseJmaTrack(xml: String, candidate: JmaCandidate): StormTrack? {
        val doc = parseXml(xml)
        val head = doc.elements("Head").firstOrNull() ?: doc.documentElement
        val eventId = head.text("EventID").ifBlank { candidate.code }
        val bulletinTime = head.text("ReportDateTime").ifBlank { candidate.updated.ifBlank { null } }
        val documentNamePart = doc.elements("TyphoonNamePart").firstOrNull()
        var nameEn = documentNamePart?.text("Name").orEmpty().uppercase()
        var number = documentNamePart?.text("Number").orEmpty()
        var fallbackNameZh = ""
        val analysis = mutableListOf<StormPoint>()
        val forecast = mutableListOf<StormPoint>()

        doc.elements("MeteorologicalInfo").forEach { info ->
            val dateNode = info.elements("DateTime").firstOrNull() ?: return@forEach
            val time = dateNode.textContent?.trim().orEmpty()
            if (time.isBlank()) return@forEach
            val timeType = dateNode.getAttribute("type").orEmpty()
            val coordinate = selectJmaCoordinate(info, timeType) ?: return@forEach
            val namePart = info.elements("TyphoonNamePart").firstOrNull()
            if (nameEn.isBlank()) nameEn = namePart?.text("Name").orEmpty().uppercase()
            if (number.isBlank()) number = namePart?.text("Number").orEmpty()
            if (fallbackNameZh.isBlank()) {
                fallbackNameZh = normalizeJmaAreaName(info.elements("Area").firstOrNull()?.text("Name").orEmpty())
            }
            val maximumWind = selectJmaWindSpeed(info)?.textContent.numberOrNull()
            val pressure = info.elements("Pressure").firstOrNull()?.textContent.numberOrNull()
            val intensity = listOf("IntensityClass", "StormClass", "TropicalCycloneClass")
                .firstNotNullOfOrNull { tag -> info.text(tag).ifBlank { null } }
                ?: classifyWindIntensity(maximumWind)
            val type = if (timeType.contains("実況")) StormPointType.ANALYSIS else StormPointType.FORECAST
            val point = StormPoint(
                validAt = time,
                latitude = coordinate.first,
                longitude = coordinate.second,
                pointType = type,
                intensityLabel = intensity.ifBlank { null },
                intensityCode = null,
                windSpeedMs = maximumWind,
                pressureHpa = pressure,
                forecastHour = null,
                probabilityRadiusKm = parseJmaForecastRadius(info),
            )
            if (type == StormPointType.ANALYSIS) analysis.add(point) else forecast.add(point)
        }

        val cleanAnalysis = analysis.dedupePoints().takeLast(1)
        val cleanForecast = forecast.dedupePoints()
        if (cleanAnalysis.isEmpty() && cleanForecast.isEmpty()) return null
        val fallbackId = eventId.ifBlank { number.ifBlank { candidate.code } }
        return StormTrack(
            stableKey = "JMA:$fallbackId",
            agency = StormAgency.JMA,
            agencyStormId = fallbackId,
            internationalNumber = number.ifBlank { null },
            nameEn = nameEn.ifBlank { fallbackId },
            nameZh = fallbackNameZh.ifBlank { null },
            bulletinTime = bulletinTime,
            analysisPoints = cleanAnalysis,
            forecastPoints = cleanForecast,
        )
    }

    private fun selectJmaCoordinate(info: Element, timeType: String): Pair<Double, Double>? {
        val forecast = !timeType.contains("実況")
        if (forecast) {
            val circle = info.elements("ProbabilityCircle").firstOrNull()
            val base = circle?.elements("BasePoint")?.let(::pickJmaPositionNode)
            if (base != null) return parseJmaCoordinate(base.textContent, base.getAttribute("type"))
        }
        val center = info.elements("CenterPart").firstOrNull()
        val coordinate = pickJmaPositionNode(center?.elements("Coordinate").orEmpty())
            ?: pickJmaPositionNode(info.elements("Coordinate"))
        if (coordinate != null) return parseJmaCoordinate(coordinate.textContent, coordinate.getAttribute("type"))
        return pickJmaPositionNode(info.elements("BasePoint"))
            ?.let { parseJmaCoordinate(it.textContent, it.getAttribute("type")) }
    }

    private fun pickJmaPositionNode(nodes: List<Element>): Element? =
        nodes.firstOrNull { it.getAttribute("type").contains("中心位置") && !it.getAttribute("type").contains("度分") }
            ?: nodes.firstOrNull { !it.getAttribute("type").contains("度分") }
            ?: nodes.firstOrNull()

    private fun parseJmaCoordinate(value: String?, type: String?): Pair<Double, Double>? {
        val raw = value.orEmpty().trim().removeSuffix("/")
        val match = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)$").matchEntire(raw) ?: return null
        fun convert(token: String, latitude: Boolean): Double {
            val sign = if (token.startsWith("-")) -1 else 1
            val body = token.drop(1)
            if (type.orEmpty().contains("度分") && !body.contains('.')) {
                val degreeDigits = if (latitude) 2 else 3
                val degrees = body.take(degreeDigits).toDoubleOrNull() ?: return Double.NaN
                val minutes = body.drop(degreeDigits).toDoubleOrNull() ?: return Double.NaN
                return sign * (degrees + minutes / 60.0)
            }
            return sign * (body.toDoubleOrNull() ?: Double.NaN)
        }
        val lat = convert(match.groupValues[1], true)
        val lon = convert(match.groupValues[2], false)
        return if (lat.isFinite() && lon.isFinite()) lat to lon else null
    }

    private fun selectJmaWindSpeed(info: Element): Element? {
        val candidates = info.elements("WindSpeed").filter { it.getAttribute("type").contains("最大風速") }
        return candidates.firstOrNull { node ->
            val unit = node.getAttribute("unit")
            unit.contains("m/s", ignoreCase = true) || unit.contains("メートル毎秒")
        } ?: candidates.firstOrNull() ?: info.elements("WindSpeed").firstOrNull()
    }

    private fun parseJmaForecastRadius(info: Element): Double? {
        val radius = info.elements("ProbabilityCircle").firstOrNull()
            ?.elements("Radius")?.firstOrNull() ?: return null
        val value = radius.textContent.numberOrNull() ?: return null
        val unit = radius.getAttribute("unit").lowercase()
        return if (unit.contains("海里") || unit == "nm") value * 1.852 else value
    }

    internal fun parseCwaTrack(cyclone: JSONObject): StormTrack? {
        val analysis = jsonObjects(cyclone.optJSONObject("AnalysisData")?.opt("Fix"))
            .mapNotNull { parseCwaPoint(it, forecast = false) }
            .dedupePoints()
        val forecast = jsonObjects(cyclone.optJSONObject("ForecastData")?.opt("Fix"))
            .mapNotNull { parseCwaPoint(it, forecast = true) }
            .dedupePoints()
        if (analysis.isEmpty() && forecast.isEmpty()) return null
        val tdNo = cyclone.optString("CwaTdNo").trim()
        val tyNo = cyclone.optString("CwaTyNo").trim()
        val nameEn = cyclone.optString("TyphoonName").trim()
        val nameZh = cyclone.optString("CwaTyphoonName").trim().ifBlank {
            if (tdNo.isNotBlank()) "熱帶低氣壓 $tdNo" else "未命名熱帶氣旋"
        }
        val sourceId = listOf(cyclone.optString("Year"), tyNo.ifBlank { tdNo }, nameEn.ifBlank { nameZh })
            .filter { it.isNotBlank() }.joinToString("-")
        val bulletin = forecast.firstOrNull()?.validAt ?: analysis.lastOrNull()?.validAt
        return StormTrack(
            stableKey = "CWA:$sourceId",
            agency = StormAgency.CWA,
            agencyStormId = sourceId,
            internationalNumber = null,
            nameEn = nameEn.ifBlank { null },
            nameZh = nameZh,
            bulletinTime = bulletin,
            analysisPoints = analysis,
            forecastPoints = forecast,
        )
    }

    private fun parseCwaPoint(item: JSONObject, forecast: Boolean): StormPoint? {
        val lon = item.optDoubleFinite("CoordinateLongitude") ?: return null
        val lat = item.optDoubleFinite("CoordinateLatitude") ?: return null
        val hour = if (forecast) item.optIntOrNull("ForecastHour") else null
        val validAt = if (forecast) {
            val initial = normalizeIsoTime(item.optString("InitialTime")) ?: return null
            addHours(initial, hour ?: return null) ?: return null
        } else {
            normalizeIsoTime(item.optString("DateTime")) ?: return null
        }
        val wind = item.optDoubleFinite("MaxWindSpeed")
        return StormPoint(
            validAt = validAt,
            latitude = lat,
            longitude = lon,
            pointType = if (forecast) StormPointType.FORECAST else StormPointType.ANALYSIS,
            intensityLabel = classifyWindIntensity(wind).ifBlank { null },
            intensityCode = null,
            windSpeedMs = wind,
            pressureHpa = item.optDoubleFinite("Pressure"),
            forecastHour = hour,
            probabilityRadiusKm = item.optDoubleFinite("Radius70PercentProbability"),
            maximumGustMs = item.optDoubleFinite("MaxGustSpeed"),
            movingSpeedKmh = item.optDoubleFinite("MovingSpeed"),
            movingDirection = item.optString("MovingDirection").ifBlank { null },
            movementPrediction = localizedCwaText(item.opt("MovingPrediction")),
            stateTransfer = localizedCwaText(item.opt("StateTransfer")),
            windRadii = parseCwaWindRadii(item),
        )
    }

    private fun parseCwaWindRadii(item: JSONObject): List<StormWindRadii> = listOfNotNull(
        parseCwaCircle("15 m/s", item.optJSONObject("Circle15ms")),
        parseCwaCircle("25 m/s", item.optJSONObject("Circle25ms")),
    )

    private fun parseCwaCircle(level: String, circle: JSONObject?): StormWindRadii? {
        circle ?: return null
        val scalar = circle.optDoubleFinite("Radius") ?: 0.0
        val values = mutableMapOf("NE" to scalar, "SE" to scalar, "SW" to scalar, "NW" to scalar)
        jsonObjects(circle.optJSONObject("QuadrantRadii")?.opt("Radius")).forEach { entry ->
            val direction = entry.optString("dir").uppercase()
            val value = entry.optDoubleFinite("value")
            if (direction in values && value != null) values[direction] = value
        }
        return StormWindRadii(
            level = level,
            northEastKm = values.getValue("NE"),
            southEastKm = values.getValue("SE"),
            southWestKm = values.getValue("SW"),
            northWestKm = values.getValue("NW"),
        )
    }

    private fun localizedCwaText(value: Any?): String? {
        val items = jsonObjects(value)
        if (items.isNotEmpty()) {
            val preferred = items.firstOrNull { it.optString("lang").lowercase().startsWith("zh") } ?: items.first()
            return preferred.optString("value").trim().ifBlank { null }
        }
        return value?.toString()?.trim()?.ifBlank { null }
    }

    private fun parseXml(text: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(text.byteInputStream(StandardCharsets.UTF_8))

    private fun Node.elements(localName: String): List<Element> {
        val list = when (this) {
            is org.w3c.dom.Document -> getElementsByTagNameNS("*", localName)
            is Element -> getElementsByTagNameNS("*", localName)
            else -> return emptyList()
        }
        return buildList {
            for (index in 0 until list.length) {
                (list.item(index) as? Element)?.let(::add)
            }
        }
    }

    private fun Node.text(localName: String): String = elements(localName).firstOrNull()?.textContent?.trim().orEmpty()

    private fun parseJsonpObject(text: String): JSONObject {
        val trimmed = text.removePrefix("\uFEFF").trim()
        require(trimmed.isNotBlank()) { "CMA response is empty" }
        val candidates = mutableListOf(trimmed)
        val firstParen = trimmed.indexOf('(')
        val lastParen = trimmed.lastIndexOf(')')
        if (firstParen >= 0 && lastParen > firstParen) {
            var inner = trimmed.substring(firstParen + 1, lastParen).trim()
            candidates += inner
            while (inner.startsWith('(') && inner.endsWith(')')) {
                inner = inner.drop(1).dropLast(1).trim()
                candidates += inner
            }
        }
        val objectStart = trimmed.indexOf('{')
        val objectEnd = trimmed.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) candidates += trimmed.substring(objectStart, objectEnd + 1)
        var last: Throwable? = null
        candidates.distinct().forEach { candidate ->
            try {
                return JSONObject(candidate)
            } catch (error: Throwable) {
                last = error
            }
        }
        error("CMA JSON/JSONP parsing failed: ${last?.message ?: "unsupported format"}")
    }

    private fun normalizeNmcTime(value: Any?): String? {
        val raw = value?.toString()?.trim().orEmpty()
        if (raw.isBlank() || raw == "null") return null
        if (Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(raw)) {
            if (Regex("Z$|[+-]\\d{2}:?\\d{2}$").containsMatchIn(raw)) return raw
            return raw.replace(' ', 'T') + "Z"
        }
        val digits = raw.filter(Char::isDigit)
        if (digits.length >= 12) {
            return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}T${digits.substring(8, 10)}:${digits.substring(10, 12)}:00Z"
        }
        return raw
    }

    private fun normalizeIsoTime(value: String): String? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        return runCatching { java.time.Instant.parse(raw).toString() }.getOrElse { raw }
    }

    private fun addHours(baseIso: String, hours: Int): String? = runCatching {
        java.time.Instant.parse(baseIso).plusSeconds(hours.toLong() * 3600L).toString()
    }.getOrNull()

    private fun parseDirectionalCoordinate(value: String, latitude: Boolean): Double? {
        val cleaned = value.trim().uppercase()
        val number = cleaned.replace(Regex("[^0-9.+-]"), "").toDoubleOrNull() ?: return null
        val signed = if ((latitude && cleaned.endsWith('S')) || (!latitude && cleaned.endsWith('W'))) -number else number
        return signed.takeIf { it.isFinite() && if (latitude) it in -90.0..90.0 else it in -180.0..180.0 }
    }

    private fun normalizeJmaAreaName(value: String): String = value.trim()
        .replace("熱帯低気圧", "熱帶低氣壓")
        .replace(Regex("台風第\\s*(\\d+)\\s*号"), "颱風第$1號")

    private fun classifyWindIntensity(value: Double?): String {
        val wind = value ?: return ""
        return when {
            wind >= 51 -> "超強颱風"
            wind >= 41 -> "強颱風"
            wind >= 33 -> "颱風"
            wind >= 25 -> "強烈熱帶風暴"
            wind >= 17 -> "熱帶風暴"
            else -> "熱帶低氣壓"
        }
    }

    private fun String?.numberOrNull(): Double? {
        val value = this?.replace(Regex("[^0-9.+-]"), "")?.toDoubleOrNull()
        return value?.takeIf(Double::isFinite)
    }

    private fun JSONArray.optDoubleFinite(index: Int): Double? {
        if (index !in 0 until length() || isNull(index)) return null
        return opt(index)?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    private fun JSONArray.optIntOrNull(index: Int): Int? {
        if (index !in 0 until length() || isNull(index)) return null
        return opt(index)?.toString()?.toDoubleOrNull()?.toInt()
    }

    private fun JSONObject.optDoubleFinite(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return opt(key)?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return opt(key)?.toString()?.toDoubleOrNull()?.toInt()
    }

    private fun jsonObjects(value: Any?): List<JSONObject> = when (value) {
        is JSONObject -> listOf(value)
        is JSONArray -> buildList {
            for (index in 0 until value.length()) value.optJSONObject(index)?.let(::add)
        }
        else -> emptyList()
    }

    private fun List<StormPoint>.dedupePoints(): List<StormPoint> =
        distinctBy { "${it.pointType}:${it.validAt}:${it.latitude}:${it.longitude}" }
            .sortedBy { it.validAt }

    private fun emptyMessage(agency: StormAgency): String = when (agency) {
        StormAgency.HKO -> "No active HKO track"
        StormAgency.CMA -> "No active CMA forecast"
        StormAgency.JMA -> "No active JMA VPTW track"
        StormAgency.CWA -> "No active CWA track"
    }

    data class HkoReference(
        val id: String,
        val nameZh: String,
        val nameEn: String,
        val url: String,
    )

    data class CmaReference(
        val id: String,
        val nameEn: String,
        val nameZh: String,
    )

    data class JmaCandidate(
        val code: String,
        val title: String,
        val updated: String,
        val url: String,
    )

    companion object {
        private const val LIVE_TIMEOUT_MS = 16_000
        private const val XML_ACCEPT = "application/xml,text/xml,text/plain,*/*"
        private const val JSON_ACCEPT = "application/json,application/javascript,text/plain,*/*"
    }
}
