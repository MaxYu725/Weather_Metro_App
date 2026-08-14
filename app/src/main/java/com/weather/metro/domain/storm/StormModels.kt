package com.weather.metro.domain.storm

enum class StormAgency {
    HKO,
    CMA,
    JMA,
    CWA;

    companion object {
        fun fromWire(value: String): StormAgency =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: error("Unsupported Storm agency '$value'")
    }
}

enum class StormPointType(val wireValue: String) {
    ANALYSIS("analysis"),
    FORECAST("forecast");

    companion object {
        fun fromWire(value: String): StormPointType =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() }
                ?: error("Unsupported Storm point type '$value'")
    }
}

enum class StormLiveState {
    LOADING,
    OK,
    EMPTY,
    STALE,
    ERROR,
}

data class StormHealth(
    val ok: Boolean,
    val version: String?,
    val checkedAt: String?,
)

data class StormWindRadii(
    val level: String?,
    val northEastKm: Double,
    val southEastKm: Double,
    val southWestKm: Double,
    val northWestKm: Double,
)

data class StormPoint(
    val validAt: String,
    val latitude: Double,
    val longitude: Double,
    val pointType: StormPointType,
    val intensityLabel: String?,
    val intensityCode: String?,
    val windSpeedMs: Double?,
    val pressureHpa: Double?,
    val forecastHour: Int?,
    val probabilityRadiusKm: Double?,
    val maximumGustMs: Double? = null,
    val movingSpeedKmh: Double? = null,
    val movingDirection: String? = null,
    val movementPrediction: String? = null,
    val stateTransfer: String? = null,
    val windRadii: List<StormWindRadii> = emptyList(),
)

data class StormTrack(
    val stableKey: String,
    val agency: StormAgency,
    val agencyStormId: String,
    val internationalNumber: String?,
    val nameEn: String?,
    val nameZh: String?,
    val bulletinTime: String?,
    val analysisPoints: List<StormPoint>,
    val forecastPoints: List<StormPoint>,
)

data class AgencyLiveResult(
    val agency: StormAgency,
    val state: StormLiveState,
    val message: String?,
    val updatedAt: String?,
    val storms: List<StormTrack>,
)

data class ArchiveStorm(
    val id: String,
    val year: Int,
    val internationalNumber: String?,
    val nameEn: String?,
    val nameZh: String?,
    val status: String?,
    val firstSeenAt: String?,
    val lastSeenAt: String?,
    val advisoryCount: Int,
)

data class ArchiveStormDetail(
    val storm: ArchiveStorm,
)

data class ArchiveAdvisorySummary(
    val id: String,
    val stormId: String,
    val agency: StormAgency,
    val issuedAt: String,
    val pointCount: Int,
    val parserVersion: String?,
    val sourceCode: String?,
)

data class ArchiveAdvisoryDetail(
    val advisory: ArchiveAdvisorySummary,
    val points: List<StormPoint>,
)
