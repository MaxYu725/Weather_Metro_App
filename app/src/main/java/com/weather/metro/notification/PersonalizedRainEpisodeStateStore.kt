package com.weather.metro.notification

import android.content.Context
import org.json.JSONObject

internal data class PersonalizedRainEvaluationLocation(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val district: String,
) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        require(district.isNotBlank())
    }
}

internal data class PersonalizedRainPendingTransition(
    val eventIdentity: PersonalizedForecastEventIdentity,
    val horizon: PersonalizedForecastHorizon?,
    val detectedAtEpochMs: Long,
    val sourceRunEpochMs: Long,
    val targetEpisodeState: PersonalizedRainEpisodeState,
)

internal data class PersonalizedRainDurableState(
    val committedEpisodeState: PersonalizedRainEpisodeState = PersonalizedRainEpisodeState(),
    val pendingTransition: PersonalizedRainPendingTransition? = null,
    val evaluationLocation: PersonalizedRainEvaluationLocation? = null,
    val lastSourceRunEpochMs: Long = 0L,
    val lastCheckedEpochMs: Long = 0L,
    val status: String = "IDLE",
    val lastError: String = "",
)

internal interface PersonalizedRainStatePersistence {
    fun read(): PersonalizedRainDurableState
    fun write(state: PersonalizedRainDurableState)
}

/**
 * Durable local state for the Phase 2D2 personalised rain stream.
 *
 * The evaluator's next state is not considered committed when it contains a notification
 * event. Instead [stagePersonalizedRainDecision] stores a replayable pending transition.
 * A later publisher may insert the deterministic event into NotificationEventStore and
 * only then call [commitPersonalizedRainPendingTransition]. This preserves the existing
 * "detect -> persist pending -> durable inbox -> commit" reliability ordering without
 * writing personalised events into the territory-wide HKO publication journal.
 */
internal class PersonalizedRainEpisodeStateStore(context: Context) : PersonalizedRainStatePersistence {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): PersonalizedRainDurableState =
        PersonalizedRainEpisodeStateCodec.decode(preferences.getString(KEY_STATE, null))

    override fun write(state: PersonalizedRainDurableState) {
        check(
            preferences.edit()
                .putString(KEY_STATE, PersonalizedRainEpisodeStateCodec.encode(state))
                .commit(),
        ) { "Failed to persist personalised rain episode state" }
    }

    fun reset() {
        check(preferences.edit().remove(KEY_STATE).commit()) {
            "Failed to reset personalised rain episode state"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "weather_metro_personalized_rain_episode"
        const val KEY_STATE = "state_v1"
    }
}

internal fun stagePersonalizedRainDecision(
    durableState: PersonalizedRainDurableState,
    decision: PersonalizedRainTransitionDecision,
    sourceRunEpochMs: Long,
    detectedAtEpochMs: Long,
): PersonalizedRainDurableState {
    require(sourceRunEpochMs > 0L) { "Rain source run time must be positive" }
    require(detectedAtEpochMs > 0L) { "Rain transition detection time must be positive" }

    if (durableState.pendingTransition != null) return durableState

    val identity = decision.eventIdentity
    return if (identity == null || decision.eventKind == null) {
        durableState.copy(
            committedEpisodeState = decision.nextState,
            lastSourceRunEpochMs = sourceRunEpochMs,
            lastCheckedEpochMs = detectedAtEpochMs,
            status = "EVALUATED",
            lastError = "",
        )
    } else {
        durableState.copy(
            pendingTransition = PersonalizedRainPendingTransition(
                eventIdentity = identity,
                horizon = decision.horizon,
                detectedAtEpochMs = detectedAtEpochMs,
                sourceRunEpochMs = sourceRunEpochMs,
                targetEpisodeState = decision.nextState,
            ),
            lastSourceRunEpochMs = sourceRunEpochMs,
            lastCheckedEpochMs = detectedAtEpochMs,
            status = "PENDING_${identity.kind.name}",
            lastError = "",
        )
    }
}

internal fun commitPersonalizedRainPendingTransition(
    durableState: PersonalizedRainDurableState,
    committedAtEpochMs: Long,
): PersonalizedRainDurableState {
    val pending = durableState.pendingTransition ?: return durableState
    require(committedAtEpochMs > 0L) { "Rain transition commit time must be positive" }
    return durableState.copy(
        committedEpisodeState = pending.targetEpisodeState,
        pendingTransition = null,
        lastCheckedEpochMs = maxOf(durableState.lastCheckedEpochMs, committedAtEpochMs),
        status = "COMMITTED_${pending.eventIdentity.kind.name}",
        lastError = "",
    )
}

internal object PersonalizedRainEpisodeStateCodec {
    fun encode(state: PersonalizedRainDurableState): String = JSONObject()
        .put("committedEpisodeState", encodeEpisode(state.committedEpisodeState))
        .put("pendingTransition", state.pendingTransition?.let(::encodePending) ?: JSONObject.NULL)
        .put("evaluationLocation", state.evaluationLocation?.let(::encodeLocation) ?: JSONObject.NULL)
        .put("lastSourceRunEpochMs", state.lastSourceRunEpochMs)
        .put("lastCheckedEpochMs", state.lastCheckedEpochMs)
        .put("status", state.status)
        .put("lastError", state.lastError.take(500))
        .toString()

    fun decode(raw: String?): PersonalizedRainDurableState {
        if (raw.isNullOrBlank()) return PersonalizedRainDurableState()
        return runCatching {
            val json = JSONObject(raw)
            PersonalizedRainDurableState(
                committedEpisodeState = decodeEpisode(
                    json.optJSONObject("committedEpisodeState") ?: JSONObject(),
                ),
                pendingTransition = json.optJSONObject("pendingTransition")?.let(::decodePending),
                evaluationLocation = json.optJSONObject("evaluationLocation")?.let(::decodeLocation),
                lastSourceRunEpochMs = json.optLong("lastSourceRunEpochMs", 0L).coerceAtLeast(0L),
                lastCheckedEpochMs = json.optLong("lastCheckedEpochMs", 0L).coerceAtLeast(0L),
                status = json.optString("status", "IDLE").ifBlank { "IDLE" },
                lastError = json.optString("lastError").take(500),
            )
        }.getOrElse {
            PersonalizedRainDurableState(
                status = "STATE_RESET",
                lastError = "Invalid personalised rain state was ignored",
            )
        }
    }

    private fun encodePending(value: PersonalizedRainPendingTransition): JSONObject = JSONObject()
        .put("eventSource", value.eventIdentity.source.name)
        .put("eventKind", value.eventIdentity.kind.name)
        .put("episodeId", value.eventIdentity.episodeId)
        .put("transitionOrdinal", value.eventIdentity.transitionOrdinal)
        .put("horizon", value.horizon?.name ?: "")
        .put("detectedAtEpochMs", value.detectedAtEpochMs)
        .put("sourceRunEpochMs", value.sourceRunEpochMs)
        .put("targetEpisodeState", encodeEpisode(value.targetEpisodeState))

    private fun decodePending(json: JSONObject): PersonalizedRainPendingTransition {
        val source = enumValue<PersonalizedForecastSource>(json.getString("eventSource"))
        val kind = enumValue<PersonalizedForecastEventKind>(json.getString("eventKind"))
        val episodeId = json.getString("episodeId")
        val transitionOrdinal = json.getInt("transitionOrdinal")
        val detectedAtEpochMs = json.getLong("detectedAtEpochMs")
        val sourceRunEpochMs = json.getLong("sourceRunEpochMs")
        require(detectedAtEpochMs > 0L && sourceRunEpochMs > 0L)
        val horizon = json.optString("horizon")
            .takeIf { it.isNotBlank() }
            ?.let { enumValue<PersonalizedForecastHorizon>(it) }
        return PersonalizedRainPendingTransition(
            eventIdentity = PersonalizedForecastEventIdentity(
                source = source,
                kind = kind,
                episodeId = episodeId,
                transitionOrdinal = transitionOrdinal,
            ),
            horizon = horizon,
            detectedAtEpochMs = detectedAtEpochMs,
            sourceRunEpochMs = sourceRunEpochMs,
            targetEpisodeState = decodeEpisode(
                json.optJSONObject("targetEpisodeState") ?: error("Pending target rain state missing"),
            ),
        )
    }

    private fun encodeLocation(value: PersonalizedRainEvaluationLocation): JSONObject = JSONObject()
        .put("latitude", value.latitude)
        .put("longitude", value.longitude)
        .put("label", value.label)
        .put("district", value.district)

    private fun decodeLocation(json: JSONObject): PersonalizedRainEvaluationLocation =
        PersonalizedRainEvaluationLocation(
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            label = json.optString("label"),
            district = json.getString("district"),
        )

    private fun encodeEpisode(value: PersonalizedRainEpisodeState): JSONObject = JSONObject()
        .put("episodeId", value.episodeId)
        .put("active", value.active)
        .put("reachedNearTermWet", value.reachedNearTermWet)
        .put("maxNotifiedIntensity", value.maxNotifiedIntensity.name)
        .put("transitionOrdinal", value.transitionOrdinal)
        .put("dryConfirmationCount", value.dryConfirmationCount)
        .put("dryConfirmationStartedAtEpochMs", value.dryConfirmationStartedAtEpochMs ?: JSONObject.NULL)
        .put("lastNotificationEpochMs", value.lastNotificationEpochMs ?: JSONObject.NULL)
        .put("lastEventKind", value.lastEventKind?.name ?: "")

    private fun decodeEpisode(json: JSONObject): PersonalizedRainEpisodeState =
        PersonalizedRainEpisodeState(
            episodeId = json.optString("episodeId"),
            active = json.optBoolean("active", false),
            reachedNearTermWet = json.optBoolean("reachedNearTermWet", false),
            maxNotifiedIntensity = enumValueOrDefault(
                json.optString("maxNotifiedIntensity"),
                PersonalizedRainIntensity.DRY,
            ),
            transitionOrdinal = json.optInt("transitionOrdinal", 0).coerceAtLeast(0),
            dryConfirmationCount = json.optInt("dryConfirmationCount", 0).coerceAtLeast(0),
            dryConfirmationStartedAtEpochMs = json.optNullableLong("dryConfirmationStartedAtEpochMs"),
            lastNotificationEpochMs = json.optNullableLong("lastNotificationEpochMs"),
            lastEventKind = json.optString("lastEventKind")
                .takeIf { it.isNotBlank() }
                ?.let { enumValue<PersonalizedForecastEventKind>(it) },
        )

    private inline fun <reified T : Enum<T>> enumValue(name: String): T =
        enumValues<T>().firstOrNull { it.name == name }
            ?: error("Unknown ${T::class.java.simpleName}: $name")

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private fun JSONObject.optNullableLong(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return optLong(name).takeIf { it > 0L }
    }
}
