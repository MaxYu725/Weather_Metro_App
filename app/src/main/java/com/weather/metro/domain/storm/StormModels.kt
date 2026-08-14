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

data class StormHealth(
    val ok: Boolean,
    val version: String?,
    val checkedAt: String?,
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
)

data class StormTrack(
    val stableKey: String,
    val agency: StormAgency,
    val agencyStormId: String,
    val internationalNumber: String?,
    val nameEn: String?,
    val nameZh: String?,
    val analysisPoints: List<StormPoint>,
    val forecastPoints: List<StormPoint>,
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
