package ca.floo.roadtrip.service.availability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Frozen polling intent stored in `availability_job.intent_payload`. The
 * worker reads this and never reads back to `availability_watch`, so
 * editing a watch never retroactively changes what an in-flight run
 * polls. The watch service rebuilds and writes a fresh intent_payload
 * whenever any underlying watch field changes.
 *
 * Two variants today:
 *   - [Reservable]: poll one reservable's per-day availability.
 *   - [Poi]: POI-scoped watch — full fan-out lands in PR 3.
 *
 * `kind` is the discriminator used at the JSONB layer.
 */
@Serializable
sealed class AvailabilityJobIntent {
    abstract val targetDates: List<String>
    abstract val minNights: Int

    @Serializable
    @SerialName("reservable")
    data class Reservable(
        @SerialName("reservable_id") val reservableId: Long,
        @SerialName("reservable_rid") val reservableRid: String,
        @SerialName("target_dates") override val targetDates: List<String>,
        @SerialName("min_nights") override val minNights: Int,
    ) : AvailabilityJobIntent()

    @Serializable
    @SerialName("poi")
    data class Poi(
        @SerialName("poi_id") val poiId: Long,
        @SerialName("reservable_filters") val reservableFilters: JsonObject = JsonObject(emptyMap()),
        @SerialName("target_dates") override val targetDates: List<String>,
        @SerialName("min_nights") override val minNights: Int,
    ) : AvailabilityJobIntent()

    fun toJsonObject(): JsonObject = JSON.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        // Sealed class polymorphism is class-discriminator-by-default; the
        // SerialName values above become the "type" key value in the
        // emitted JSON. We use a fixed key name so the DB schema and the
        // generator-generated bindings agree.
        val JSON =
            Json {
                classDiscriminator = "kind"
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            }

        fun fromJsonObject(obj: JsonObject): AvailabilityJobIntent =
            JSON.decodeFromJsonElement(serializer(), obj)
    }
}
