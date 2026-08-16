package com.weather.metro.notification

/**
 * Phase 2D2A contract for location-derived rain/lightning notifications.
 *
 * This contract is intentionally independent from the territory-wide HKO journal.
 * Inputs may be official public weather data, but event evaluation, episode state,
 * dedupe and delivery are local to the Android device.
 */
internal enum class PersonalizedForecastSource {
    HKO_SWIRLS_GRID,
    HKO_LIGHTNING_LOCATION,
    HKO_LIGHTNING_NOWCAST,
}

internal enum class PersonalizedForecastEventKind {
    RAIN_APPROACHING,
    RAIN_STARTING_SOON,
    HEAVY_RAIN_APPROACHING,
    RAIN_INTENSIFYING,
    RAIN_ENDING,
    LIGHTNING_NEARBY,
    LIGHTNING_CLOSER,
}

internal enum class PersonalizedForecastHorizon(val maxLeadMinutes: Int) {
    MINUTES_30(30),
    MINUTES_60(60),
    MINUTES_120(120),
}

internal enum class LightningProximityBand(val radiusKm: Double?) {
    OUTSIDE(null),
    NEARBY_15(15.0),
    CLOSE_10(10.0),
}

internal data class PersonalizedForecastEventIdentity(
    val source: PersonalizedForecastSource,
    val kind: PersonalizedForecastEventKind,
    val episodeId: String,
    val transitionOrdinal: Int,
) {
    init {
        require(episodeId.isNotBlank()) { "Personalized event episode id must not be blank" }
        require(transitionOrdinal >= 0) { "Personalized event transition ordinal must be non-negative" }
    }

    /** Stable local dedupe key. It must never be inserted into the global HKO journal. */
    fun dedupeKey(): String = listOf(
        source.name,
        kind.name,
        episodeId,
        transitionOrdinal.toString(),
    ).joinToString("|")
}

internal object PersonalizedForecastNotificationPolicy {
    /** Reuse the host app's cached precise fix; background workers never request a new fix. */
    const val LOCATION_MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /**
     * SWIRLS run-time age gate. A fresh run may still expose less than a full 120-minute
     * forward horizon from evaluation time; evaluators must use frame validTime rather
     * than assuming leadMinutes is relative to "now".
     */
    const val RAIN_SOURCE_MAX_AGE_MS = 45 * 60 * 1000L

    /** Avoid near-identical rain transition notifications from rapid foreground refreshes. */
    const val RAIN_TRANSITION_COOLDOWN_MS = 12 * 60 * 1000L

    /**
     * LLIS observations are published on a five-minute cycle and may be delayed by a few
     * minutes. The first client should fail closed if its machine feed is older than this.
     */
    const val LIGHTNING_SOURCE_MAX_AGE_MS = 12 * 60 * 1000L

    /** Rain must look dry in two consecutive 6-minute evaluations before re-arming. */
    const val RAIN_DRY_CONFIRMATION_COUNT = 2
    const val RAIN_DRY_CONFIRMATION_MIN_SPAN_MS = 12 * 60 * 1000L

    /** A lightning episode clears only after no strike is seen within 15 km for 30 minutes. */
    const val LIGHTNING_CLEAR_INTERVAL_MS = 30 * 60 * 1000L

    /** Keep deterministic local event ids long enough to survive retries/restarts. */
    const val DEDUPE_RETENTION_MS = 24 * 60 * 60 * 1000L

    fun horizonForLeadMinutes(leadMinutes: Int): PersonalizedForecastHorizon? = when (leadMinutes) {
        in 0..30 -> PersonalizedForecastHorizon.MINUTES_30
        in 31..60 -> PersonalizedForecastHorizon.MINUTES_60
        in 61..120 -> PersonalizedForecastHorizon.MINUTES_120
        else -> null
    }

    fun isSourceFresh(
        sourceEpochMs: Long?,
        nowEpochMs: Long,
        maxAgeMs: Long,
        futureToleranceMs: Long = 5 * 60 * 1000L,
    ): Boolean {
        val source = sourceEpochMs ?: return false
        if (source <= 0L || nowEpochMs <= 0L || maxAgeMs <= 0L) return false
        val age = nowEpochMs - source
        return age in -futureToleranceMs..maxAgeMs
    }

    fun lightningBand(distanceKm: Double?): LightningProximityBand {
        if (distanceKm == null || !distanceKm.isFinite() || distanceKm < 0.0) {
            return LightningProximityBand.OUTSIDE
        }
        return when {
            distanceKm <= LightningProximityBand.CLOSE_10.radiusKm!! -> LightningProximityBand.CLOSE_10
            distanceKm <= LightningProximityBand.NEARBY_15.radiusKm!! -> LightningProximityBand.NEARBY_15
            else -> LightningProximityBand.OUTSIDE
        }
    }

    fun lightningEpisodeCleared(
        lastNearbyStrikeEpochMs: Long?,
        nowEpochMs: Long,
    ): Boolean {
        val lastStrike = lastNearbyStrikeEpochMs ?: return true
        if (lastStrike <= 0L || nowEpochMs <= 0L || lastStrike > nowEpochMs) return false
        return nowEpochMs - lastStrike >= LIGHTNING_CLEAR_INTERVAL_MS
    }
}
