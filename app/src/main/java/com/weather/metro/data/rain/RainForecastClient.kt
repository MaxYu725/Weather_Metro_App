package com.weather.metro.data.rain

import com.weather.metro.data.tools.ToolEndpoints
import com.weather.metro.domain.rain.RainForecastFrame
import com.weather.metro.domain.rain.RainForecastGrid
import com.weather.metro.domain.rain.RainForecastRunChangedException
import com.weather.metro.domain.rain.RainForecastSlot
import com.weather.metro.domain.rain.RainForecastSource
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.domain.rain.RainGridBounds
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong

class RainForecastClient internal constructor(
    private val transport: RainHttpTransport = UrlConnectionRainTransport(),
) {
    suspend fun loadSwirlsFrame(frameIndex: Int): RainNetworkResult<RainForecastFrame> {
        val payload = transport.get(
            ToolEndpoints.rainSwirlsFrame(frameIndex),
            connectTimeoutMs = STANDARD_CONNECT_TIMEOUT_MS,
            readTimeoutMs = SWIRLS_READ_TIMEOUT_MS,
        )
        return RainNetworkResult(RainForecastParsers.parseSwirlsFrame(payload), payload)
    }

    suspend fun loadNowcast(fallbackReason: String? = null): RainNetworkResult<RainForecastTimeline> {
        val payload = transport.get(
            ToolEndpoints.rainNowcast(),
            connectTimeoutMs = STANDARD_CONNECT_TIMEOUT_MS,
            readTimeoutMs = STANDARD_READ_TIMEOUT_MS,
        )
        return RainNetworkResult(
            RainForecastParsers.parseNowcast(payload, fallbackReason),
            payload,
        )
    }

    fun parseSwirlsFrame(payload: String): RainForecastFrame =
        RainForecastParsers.parseSwirlsFrame(payload)

    fun buildSwirlsTimeline(firstFrame: RainForecastFrame): RainForecastTimeline =
        RainForecastParsers.buildSwirlsTimeline(firstFrame)

    fun parseNowcast(payload: String, fallbackReason: String? = null): RainForecastTimeline =
        RainForecastParsers.parseNowcast(payload, fallbackReason)

    fun assertSwirlsFrameCompatible(
        timeline: RainForecastTimeline,
        frame: RainForecastFrame,
    ) = RainForecastParsers.assertSwirlsFrameCompatible(timeline, frame)

    companion object {
        internal const val STANDARD_CONNECT_TIMEOUT_MS = 10_000
        internal const val STANDARD_READ_TIMEOUT_MS = 20_000
        internal const val SWIRLS_READ_TIMEOUT_MS = 60_000
    }
}

internal object RainForecastParsers {
    const val UNIT = "mm / 30 min"
    const val ORIENTATION = "row-major-north-to-south-west-to-east"
    const val SWIRLS_FRAME_COUNT = 16
    const val SWIRLS_CADENCE_MINUTES = 6
    const val ACCUMULATION_MINUTES = 30
    const val FIRST_LEAD_MINUTES = 30
    const val LAST_LEAD_MINUTES = 120
    const val SWIRLS_ROWS = 121
    const val SWIRLS_COLS = 121
    const val SWIRLS_CELL_COUNT = SWIRLS_ROWS * SWIRLS_COLS
    val NOWCAST_LEADS = listOf(30, 60, 90, 120)

    fun parseSwirlsFrame(payload: String): RainForecastFrame {
        val root = JSONObject(payload)
        requireOk(root, "SWIRLS frame")

        val frameIndex = root.requiredInt("frameIndex")
        require(frameIndex in 0 until SWIRLS_FRAME_COUNT) { "SWIRLS frame index must be 0..15" }
        val runTime = root.requiredIso("runTime")
        val validTime = root.requiredIso("validTime")
        val leadMinutes = root.requiredInt("leadMinutes")
        val expectedLead = FIRST_LEAD_MINUTES + frameIndex * SWIRLS_CADENCE_MINUTES
        require(leadMinutes == expectedLead) {
            "SWIRLS lead time mismatch: expected $expectedLead, got $leadMinutes"
        }
        require(minutesBetween(runTime, validTime) == expectedLead) {
            "SWIRLS valid time does not match run time"
        }

        val unit = root.requiredString("unit")
        require(unit == UNIT) { "Unexpected SWIRLS rainfall unit: $unit" }
        val grid = parseSwirlsGrid(root.optJSONObject("grid") ?: error("SWIRLS grid missing"))
        val values = parseValues(
            root.optJSONArray("values") ?: error("SWIRLS values missing"),
            expectedCount = SWIRLS_CELL_COUNT,
            label = "SWIRLS",
        )

        val validation = root.optJSONObject("validation")
            ?: error("SWIRLS validation missing")
        require(validation.optBoolean("ready", false)) { "SWIRLS frame is not ready" }
        require(validation.optBoolean("runTimeMatchesIndex", false)) {
            "SWIRLS runtime does not match index"
        }

        val windowEnd = root.optIso("windowEnd") ?: validTime
        val windowStart = root.optIso("windowStart")
            ?: Instant.ofEpochMilli(Instant.parse(validTime).toEpochMilli() - ACCUMULATION_MINUTES * 60_000L).toString()
        require(minutesBetween(windowStart, windowEnd) == ACCUMULATION_MINUTES) {
            "SWIRLS accumulation window must be 30 minutes"
        }
        require(windowEnd == validTime) { "SWIRLS accumulation window must end at valid time" }

        val sourceBytes = root.optJSONObject("source")?.optLongOrNull("bytes")

        return RainForecastFrame(
            frameIndex = frameIndex,
            runTime = runTime,
            validTime = validTime,
            leadMinutes = leadMinutes,
            windowStart = windowStart,
            windowEnd = windowEnd,
            unit = unit,
            grid = grid,
            values = values,
            sourceBytes = sourceBytes,
        )
    }

    fun buildSwirlsTimeline(firstFrame: RainForecastFrame): RainForecastTimeline {
        require(firstFrame.frameIndex == 0) { "SWIRLS timeline must initialize from frame 0" }
        val runTime = firstFrame.runTime ?: error("SWIRLS frame 0 run time missing")
        val runMs = Instant.parse(runTime).toEpochMilli()
        val frames = (0 until SWIRLS_FRAME_COUNT).map { frameIndex ->
            val leadMinutes = FIRST_LEAD_MINUTES + frameIndex * SWIRLS_CADENCE_MINUTES
            val validTime = Instant.ofEpochMilli(runMs + leadMinutes * 60_000L).toString()
            val windowStart = Instant.ofEpochMilli(
                runMs + (leadMinutes - ACCUMULATION_MINUTES) * 60_000L,
            ).toString()
            RainForecastSlot(
                frameIndex = frameIndex,
                validTime = validTime,
                leadMinutes = leadMinutes,
                windowStart = windowStart,
                windowEnd = validTime,
                frame = if (frameIndex == 0) firstFrame else null,
            )
        }
        require(frames.first().validTime == firstFrame.validTime) {
            "SWIRLS frame 0 timeline does not match run time"
        }

        return RainForecastTimeline(
            source = RainForecastSource.SWIRLS,
            issueTime = runTime,
            unit = UNIT,
            cadenceMinutes = SWIRLS_CADENCE_MINUTES,
            accumulationMinutes = ACCUMULATION_MINUTES,
            horizonMinutes = LAST_LEAD_MINUTES,
            grid = firstFrame.grid,
            frames = frames,
        )
    }

    fun assertSwirlsFrameCompatible(
        timeline: RainForecastTimeline,
        frame: RainForecastFrame,
    ) {
        require(timeline.source == RainForecastSource.SWIRLS) { "Active forecast is not SWIRLS" }
        if (frame.runTime != timeline.issueTime) {
            throw RainForecastRunChangedException(
                "SWIRLS model run changed from ${timeline.issueTime} to ${frame.runTime}",
            )
        }
        val expected = timeline.frames.getOrNull(frame.frameIndex)
            ?: error("SWIRLS frame index outside active timeline")
        require(expected.validTime == frame.validTime) { "SWIRLS frame valid time changed" }
        require(expected.leadMinutes == frame.leadMinutes) { "SWIRLS frame lead time changed" }
        require(frame.unit == timeline.unit) { "SWIRLS frame unit changed" }
        require(sameGrid(timeline.grid, frame.grid)) { "SWIRLS frame grid changed" }
    }

    fun parseNowcast(payload: String, fallbackReason: String? = null): RainForecastTimeline {
        val root = JSONObject(payload)
        requireOk(root, "Rain nowcast")
        val issueTimeRaw = root.optString("issueTime").ifBlank { root.optString("baseTime") }
        val issueTime = canonicalIso(issueTimeRaw, "Rain nowcast issue time")
        val unit = root.optString("unit").ifBlank { UNIT }
        require(unit == UNIT) { "Unexpected nowcast rainfall unit: $unit" }
        val framesJson = root.optJSONArray("frames") ?: error("Rain nowcast frames missing")
        require(framesJson.length() > 0) { "Rain nowcast frames empty" }

        val requiredFrames = linkedMapOf<Int, JSONObject>()
        for (index in 0 until framesJson.length()) {
            val frame = framesJson.optJSONObject(index) ?: error("Rain nowcast frame $index is invalid")
            val validTime = frame.requiredIso("time")
            val explicitLead = frame.optIntOrNull("leadMinutes")
            val derivedLead = minutesBetween(issueTime, validTime)
            val lead = explicitLead ?: derivedLead
            if (explicitLead != null) {
                require(explicitLead == derivedLead) {
                    "Rain nowcast lead/time mismatch at +$explicitLead minutes"
                }
            }
            if (lead in NOWCAST_LEADS && !requiredFrames.containsKey(lead)) {
                requiredFrames[lead] = frame
            }
        }
        require(requiredFrames.keys.toList() == NOWCAST_LEADS) {
            "Rain nowcast must contain +30/+60/+90/+120 minute periods"
        }

        val latitudeKeys = sortedSetOf<Long>()
        val longitudeKeys = sortedSetOf<Long>()
        for (frame in requiredFrames.values) {
            val points = frame.optJSONArray("points") ?: error("Rain nowcast frame points missing")
            for (index in 0 until points.length()) {
                val point = points.optJSONArray(index) ?: error("Rain nowcast point $index is invalid")
                require(point.length() >= 3) { "Rain nowcast point $index is incomplete" }
                val lat = point.requiredFiniteDouble(0, "latitude")
                val lon = point.requiredFiniteDouble(1, "longitude")
                latitudeKeys += quantize(lat)
                longitudeKeys += quantize(lon)
            }
        }

        require(latitudeKeys.size > 1 && longitudeKeys.size > 1) {
            "Rain nowcast observed grid axes are incomplete"
        }
        val latitudeAsc = latitudeKeys.map(::dequantize)
        val longitudeAsc = longitudeKeys.map(::dequantize)
        val latitudeDesc = latitudeAsc.asReversed()
        val expectedCellCount = latitudeDesc.size * longitudeAsc.size
        require(expectedCellCount in 1..40_000) { "Rain nowcast grid size is unreasonable" }

        val grid = RainForecastGrid(
            rows = latitudeDesc.size,
            cols = longitudeAsc.size,
            cellCount = expectedCellCount,
            orientation = ORIENTATION,
            latitudes = latitudeDesc.toDoubleArray(),
            longitudes = longitudeAsc.toDoubleArray(),
            stepLat = averageStep(latitudeAsc),
            stepLon = averageStep(longitudeAsc),
            bounds = RainGridBounds(
                north = axisEdgeMax(latitudeAsc),
                south = axisEdgeMin(latitudeAsc),
                east = axisEdgeMax(longitudeAsc),
                west = axisEdgeMin(longitudeAsc),
            ),
        )

        val forecastFrames = NOWCAST_LEADS.mapIndexed { frameIndex, leadMinutes ->
            val frameJson = requiredFrames.getValue(leadMinutes)
            val validTime = frameJson.requiredIso("time")
            val points = frameJson.optJSONArray("points") ?: error("Rain nowcast frame points missing")
            require(points.length() == expectedCellCount) {
                "Rain nowcast +$leadMinutes source point count mismatch"
            }

            val pointIndex = HashMap<GridCellKey, Double>(expectedCellCount * 2)
            for (pointIndexValue in 0 until points.length()) {
                val point = points.optJSONArray(pointIndexValue)
                    ?: error("Rain nowcast point $pointIndexValue is invalid")
                require(point.length() >= 3) { "Rain nowcast point $pointIndexValue is incomplete" }
                val lat = point.requiredFiniteDouble(0, "latitude")
                val lon = point.requiredFiniteDouble(1, "longitude")
                val rainfall = point.requiredFiniteDouble(2, "rainfall")
                require(rainfall >= 0.0) { "Rain nowcast rainfall must be non-negative" }
                val key = GridCellKey(quantize(lat), quantize(lon))
                require(pointIndex.put(key, rainfall) == null) {
                    "Rain nowcast +$leadMinutes contains duplicate grid cells"
                }
            }
            require(pointIndex.size == expectedCellCount) {
                "Rain nowcast +$leadMinutes unique cell count mismatch"
            }

            val values = DoubleArray(expectedCellCount)
            var offset = 0
            for (lat in latitudeDesc) {
                for (lon in longitudeAsc) {
                    values[offset++] = pointIndex[GridCellKey(quantize(lat), quantize(lon))]
                        ?: error("Rain nowcast +$leadMinutes is missing a grid cell")
                }
            }
            val windowStart = Instant.ofEpochMilli(
                Instant.parse(validTime).toEpochMilli() - ACCUMULATION_MINUTES * 60_000L,
            ).toString()
            RainForecastFrame(
                frameIndex = frameIndex,
                runTime = issueTime,
                validTime = validTime,
                leadMinutes = leadMinutes,
                windowStart = windowStart,
                windowEnd = validTime,
                unit = UNIT,
                grid = grid,
                values = values,
            )
        }

        return RainForecastTimeline(
            source = RainForecastSource.NOWCAST,
            issueTime = issueTime,
            unit = UNIT,
            cadenceMinutes = 30,
            accumulationMinutes = ACCUMULATION_MINUTES,
            horizonMinutes = LAST_LEAD_MINUTES,
            grid = grid,
            frames = forecastFrames.map { frame ->
                RainForecastSlot(
                    frameIndex = frame.frameIndex,
                    validTime = frame.validTime,
                    leadMinutes = frame.leadMinutes,
                    windowStart = frame.windowStart,
                    windowEnd = frame.windowEnd,
                    frame = frame,
                )
            },
            fallbackReason = fallbackReason,
        )
    }

    private fun parseSwirlsGrid(value: JSONObject): RainForecastGrid {
        val rows = value.requiredInt("rows")
        val cols = value.requiredInt("cols")
        val cellCount = value.requiredInt("cellCount")
        require(rows == SWIRLS_ROWS && cols == SWIRLS_COLS && cellCount == SWIRLS_CELL_COUNT) {
            "SWIRLS grid must be 121x121 / 14,641 cells"
        }
        val orientation = value.requiredString("orientation")
        require(orientation == ORIENTATION) { "Unexpected SWIRLS grid orientation" }

        val latitudes = value.requiredDoubleArray("latitudes", SWIRLS_ROWS)
        val longitudes = value.requiredDoubleArray("longitudes", SWIRLS_COLS)
        require(strictlyDescending(latitudes)) { "SWIRLS latitude axis must be north to south" }
        require(strictlyAscending(longitudes)) { "SWIRLS longitude axis must be west to east" }

        val boundsObject = value.optJSONObject("bounds") ?: error("SWIRLS grid bounds missing")
        val bounds = RainGridBounds(
            north = boundsObject.requiredFiniteDouble("north"),
            south = boundsObject.requiredFiniteDouble("south"),
            east = boundsObject.requiredFiniteDouble("east"),
            west = boundsObject.requiredFiniteDouble("west"),
        )
        require(bounds.north > bounds.south && bounds.east > bounds.west) {
            "SWIRLS grid bounds invalid"
        }
        require(latitudes.first() <= bounds.north + EPSILON && latitudes.last() >= bounds.south - EPSILON) {
            "SWIRLS latitude axis falls outside declared bounds"
        }
        require(longitudes.first() >= bounds.west - EPSILON && longitudes.last() <= bounds.east + EPSILON) {
            "SWIRLS longitude axis falls outside declared bounds"
        }

        val stepLat = value.optFiniteDouble("stepLat")?.also { require(it > 0.0) }
        val stepLon = value.optFiniteDouble("stepLon")?.also { require(it > 0.0) }
        return RainForecastGrid(
            rows = rows,
            cols = cols,
            cellCount = cellCount,
            orientation = orientation,
            latitudes = latitudes,
            longitudes = longitudes,
            stepLat = stepLat,
            stepLon = stepLon,
            bounds = bounds,
        )
    }

    private fun parseValues(array: JSONArray, expectedCount: Int, label: String): DoubleArray {
        require(array.length() == expectedCount) { "$label values count mismatch" }
        return DoubleArray(expectedCount) { index ->
            val value = array.optDouble(index, Double.NaN)
            require(value.isFinite() && value >= 0.0) { "$label contains invalid rainfall value" }
            value
        }
    }

    private fun sameGrid(first: RainForecastGrid, second: RainForecastGrid): Boolean {
        if (first.rows != second.rows || first.cols != second.cols || first.cellCount != second.cellCount) return false
        if (first.orientation != second.orientation) return false
        if (!sameNumber(first.bounds.north, second.bounds.north) ||
            !sameNumber(first.bounds.south, second.bounds.south) ||
            !sameNumber(first.bounds.east, second.bounds.east) ||
            !sameNumber(first.bounds.west, second.bounds.west)
        ) return false
        if (first.latitudes.size != second.latitudes.size || first.longitudes.size != second.longitudes.size) return false
        if (first.latitudes.indices.any { !sameNumber(first.latitudes[it], second.latitudes[it]) }) return false
        if (first.longitudes.indices.any { !sameNumber(first.longitudes[it], second.longitudes[it]) }) return false
        return true
    }

    private fun requireOk(root: JSONObject, label: String) {
        require(root.optBoolean("ok", false)) {
            "$label failed: ${root.optString("error").ifBlank { "unknown Worker error" }}"
        }
    }

    private fun JSONObject.requiredString(key: String): String =
        optString(key).takeIf { it.isNotBlank() } ?: error("Required field '$key' missing")

    private fun JSONObject.requiredInt(key: String): Int {
        if (!has(key) || isNull(key)) error("Required field '$key' missing")
        val value = optInt(key, Int.MIN_VALUE)
        if (value == Int.MIN_VALUE) error("Required integer '$key' invalid")
        return value
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = optInt(key, Int.MIN_VALUE)
        return value.takeIf { it != Int.MIN_VALUE }
    }

    private fun JSONObject.requiredIso(key: String): String =
        canonicalIso(requiredString(key), key)

    private fun JSONObject.optIso(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key).takeIf { it.isNotBlank() } ?: return null
        return canonicalIso(value, key)
    }

    private fun JSONObject.requiredFiniteDouble(key: String): Double =
        optFiniteDouble(key) ?: error("Required numeric field '$key' missing")

    private fun JSONObject.optFiniteDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key, Double.NaN).takeIf { it.isFinite() }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val value = optLong(key, Long.MIN_VALUE)
        return value.takeIf { it != Long.MIN_VALUE && it >= 0L }
    }

    private fun JSONObject.requiredDoubleArray(key: String, expectedCount: Int): DoubleArray {
        val array = optJSONArray(key) ?: error("Required array '$key' missing")
        require(array.length() == expectedCount) { "$key length mismatch" }
        return DoubleArray(expectedCount) { index ->
            array.optDouble(index, Double.NaN).also {
                require(it.isFinite()) { "$key contains invalid value" }
            }
        }
    }

    private fun JSONArray.requiredFiniteDouble(index: Int, label: String): Double =
        optDouble(index, Double.NaN).also { require(it.isFinite()) { "Rain nowcast $label is invalid" } }

    private fun canonicalIso(value: String, label: String): String = try {
        Instant.parse(value).toString()
    } catch (_: Throwable) {
        error("$label is not a valid ISO-8601 instant")
    }

    private fun minutesBetween(start: String, end: String): Int =
        ((Instant.parse(end).toEpochMilli() - Instant.parse(start).toEpochMilli()) / 60_000L).toInt()

    private fun strictlyDescending(values: DoubleArray): Boolean =
        values.size > 1 && (1 until values.size).all { values[it - 1] > values[it] }

    private fun strictlyAscending(values: DoubleArray): Boolean =
        values.size > 1 && (1 until values.size).all { values[it - 1] < values[it] }

    private fun quantize(value: Double): Long = (value * AXIS_SCALE).roundToLong()

    private fun dequantize(value: Long): Double = value.toDouble() / AXIS_SCALE

    private fun averageStep(axis: List<Double>): Double? {
        if (axis.size < 2) return null
        return round6((axis.last() - axis.first()) / (axis.size - 1))
    }

    private fun axisEdgeMin(axis: List<Double>): Double {
        require(axis.size >= 2)
        return round6(axis.first() - (axis[1] - axis.first()) / 2.0)
    }

    private fun axisEdgeMax(axis: List<Double>): Double {
        require(axis.size >= 2)
        val last = axis.lastIndex
        return round6(axis[last] + (axis[last] - axis[last - 1]) / 2.0)
    }

    private fun round6(value: Double): Double = kotlin.math.round(value * AXIS_SCALE) / AXIS_SCALE

    private fun sameNumber(first: Double, second: Double): Boolean = abs(first - second) <= EPSILON

    private data class GridCellKey(val latitude: Long, val longitude: Long)

    private const val AXIS_SCALE = 1_000_000.0
    private const val EPSILON = 0.000001
}
