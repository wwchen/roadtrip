package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.CampsiteAttribute
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A campsite as the API serves it: the facts a client renders, and nothing
 * else. The `campsites` row keeps the raw source payload, the timestamps and
 * the vendor schedule/price blobs off the wire.
 */
@Serializable
data class CampsiteDto(
    val id: Long,
    @SerialName("campground_id") val campgroundId: Long,
    val name: String,
    val kind: String,
    /** The backend's display wording for [kind]; `kind_listed` keeps the vendor's. */
    @SerialName("kind_label") val kindLabel: String,
    @SerialName("kind_listed") val kindListed: String? = null,
    @SerialName("loop_name") val loopName: String? = null,
    val description: String? = null,
    @SerialName("min_people") val minPeople: Int? = null,
    @SerialName("max_people") val maxPeople: Int? = null,
    @SerialName("max_cars") val maxCars: Int? = null,
    @SerialName("driveway_length") val drivewayLength: Int? = null,
    @SerialName("max_rv_length") val maxRvLength: Int? = null,
    @SerialName("max_trailer_length") val maxTrailerLength: Double? = null,
    val firepit: Boolean? = null,
    @SerialName("picnic_table") val picnicTable: Boolean? = null,
    @SerialName("ada_accessible") val adaAccessible: Boolean? = null,
    @SerialName("water_hookups") val waterHookups: Boolean? = null,
    @SerialName("electric_hookups") val electricHookups: Boolean? = null,
    @SerialName("sewer_hookups") val sewerHookups: Boolean? = null,
    @SerialName("pull_through") val pullThrough: Boolean? = null,
    val equipment: List<String> = emptyList(),
    val attributes: List<CampsiteAttribute> = emptyList(),
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("data_provider") val dataProvider: String,
    @SerialName("data_provider_ref") val dataProviderRef: String,
    @SerialName("booking_provider") val bookingProvider: String? = null,
    /** The site this row's booking identity opens, by the same resolver as the drawer. */
    @SerialName("booking_system") val bookingSystem: String? = null,
) {
    companion object {
        fun from(
            row: Campsite,
            bookingSystem: String? = null,
        ): CampsiteDto =
            CampsiteDto(
                id = row.id,
                campgroundId = row.campgroundId,
                name = row.name,
                kind = row.kind.wire,
                kindLabel = row.kind.label,
                kindListed = row.kindListed,
                loopName = row.loopName,
                description = row.description,
                minPeople = row.minPeople,
                maxPeople = row.maxPeople,
                maxCars = row.maxCars,
                drivewayLength = row.drivewayLength,
                maxRvLength = row.maxRvLength,
                maxTrailerLength = row.maxTrailerLength,
                firepit = row.firepit,
                picnicTable = row.picnicTable,
                adaAccessible = row.adaAccessible,
                waterHookups = row.waterHookups,
                electricHookups = row.electricHookups,
                sewerHookups = row.sewerHookups,
                pullThrough = row.pullThrough,
                equipment = row.equipment,
                attributes = row.attributes,
                photoUrl = row.photos.firstOrNull()?.url,
                dataProvider = row.dataProvider,
                dataProviderRef = row.dataProviderRefValue,
                bookingProvider = row.bookingProvider,
                bookingSystem = bookingSystem,
            )
    }
}
