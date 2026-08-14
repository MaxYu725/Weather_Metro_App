package com.weather.metro.domain.rain

enum class RainForecastSource {
    SWIRLS,
    NOWCAST,
}

data class RainGridBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
)

data class RainForecastGrid(
    val rows: Int,
    val cols: Int,
    val cellCount: Int,
    val orientation: String,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val stepLat: Double?,
    val stepLon: Double?,
    val bounds: RainGridBounds,
)

data class RainForecastFrame(
    val frameIndex: Int,
    val runTime: String?,
    val validTime: String,
    val leadMinutes: Int,
    val windowStart: String,
    val windowEnd: String,
    val unit: String,
    val grid: RainForecastGrid,
    val values: DoubleArray,
    val sourceBytes: Long? = null,
)

data class RainForecastSlot(
    val frameIndex: Int,
    val validTime: String,
    val leadMinutes: Int,
    val windowStart: String,
    val windowEnd: String,
    val frame: RainForecastFrame? = null,
)

data class RainForecastTimeline(
    val source: RainForecastSource,
    val issueTime: String,
    val unit: String,
    val cadenceMinutes: Int,
    val accumulationMinutes: Int,
    val horizonMinutes: Int,
    val grid: RainForecastGrid,
    val frames: List<RainForecastSlot>,
    val fallbackReason: String? = null,
) {
    val loadedFrameCount: Int
        get() = frames.count { it.frame != null }

    fun frame(index: Int): RainForecastFrame? = frames.getOrNull(index)?.frame

    fun withLoadedFrame(frame: RainForecastFrame): RainForecastTimeline {
        val slot = frames.getOrNull(frame.frameIndex)
            ?: error("Forecast frame ${frame.frameIndex} is outside the active timeline")
        require(slot.validTime == frame.validTime) { "Forecast frame valid time does not match active timeline" }
        require(slot.leadMinutes == frame.leadMinutes) { "Forecast frame lead time does not match active timeline" }
        return copy(
            frames = frames.mapIndexed { index, existing ->
                if (index == frame.frameIndex) existing.copy(frame = frame) else existing
            },
        )
    }
}

class RainForecastRunChangedException(message: String) : IllegalStateException(message)
