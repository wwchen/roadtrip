package ca.floo.roadtrip.model.domain

import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * One row in the `campsites` table.
 */
@Serializable
data class Campsite(
    val id: Long,
    @SerialName("campground_id")
    val campgroundId: Long,
    val name: String,
    val kind: String,
    @SerialName("loop_name")
    val loopName: String?,
    val latitude: Double?,
    val longitude: Double?,
    @SerialName("reservation_url")
    val reservationUrl: String?,
    val equipment: JsonElement?,
    @SerialName("kind_listed")
    val kindListed: String?,
    val schedule: JsonElement,
    val price: JsonElement,
    val firepit: Boolean?,
    @SerialName("picnic_table")
    val picnicTable: Boolean?,
    @SerialName("ada_accessible")
    val adaAccessible: Boolean?,
    @SerialName("water_hookups")
    val waterHookups: Boolean?,
    @SerialName("electric_hookups")
    val electricHookups: Boolean?,
    @SerialName("sewer_hookups")
    val sewerHookups: Boolean?,
    @SerialName("max_people")
    val maxPeople: Int?,
    @SerialName("max_cars")
    val maxCars: Int?,
    @SerialName("pull_through")
    val pullThrough: Boolean?,
    @SerialName("driveway_length")
    val drivewayLength: Int?,
    @SerialName("max_rv_length")
    val maxRvLength: Int?,
    @SerialName("max_trailer_length")
    val maxTrailerLength: Double?,
    val photos: JsonElement,
    @SerialName("source_payload")
    val sourcePayload: JsonElement,
    @Serializable(with = InstantIsoStringSerializer::class)
    @SerialName("created_at")
    val createdAt: Instant,
    @Serializable(with = InstantIsoStringSerializer::class)
    @SerialName("updated_at")
    val updatedAt: Instant,
    @Serializable(with = InstantIsoStringSerializer::class)
    @SerialName("deleted_at")
    val deletedAt: Instant?,
    @SerialName("data_provider")
    val dataProvider: String,
    @SerialName("data_provider_ref")
    val dataProviderRefValue: String,
    @SerialName("booking_provider")
    val bookingProvider: String?,
    @SerialName("booking_provider_ref")
    val bookingProviderRef: String?,
) {
    @Transient
    val dataProviderRef: DataProviderRef = parseDataProviderRef(dataProvider, dataProviderRefValue)
}

private object InstantIsoStringSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InstantIsoString", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

private fun parseDataProviderRef(
    dataProvider: String,
    dataProviderRef: String,
): DataProviderRef =
    DataProviderRef.parse(DataProvider.fromId(dataProvider), dataProviderRef)
        ?: throw SerializationException("Failed to parse DataProviderRef for provider=$dataProvider ref=$dataProviderRef")
