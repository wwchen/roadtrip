package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BulkAvailabilityResponseDto(
    val pois: List<BulkPoiAvailabilityDto>,
)

/**
 * One requested POI. Exactly one of [campsites], [summary] and [error] is set —
 * [campsites] under the `campsites` view, [summary] under `summary`. An empty
 * [campsites] list means the POI resolved but no site met `min_nights`.
 */
@Serializable
data class BulkPoiAvailabilityDto(
    @SerialName("poi_id") val poiId: Long,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val campsites: List<AvailabilityResponseDto>? = null,
    val summary: BulkPoiSummaryDto? = null,
    val error: String? = null,
)

/**
 * A stay at one campground, reduced to what a result card says: the window is
 * the request's, every night of it from `start_date` up to (not including)
 * `end_date`.
 */
@Serializable
data class BulkPoiSummaryDto(
    val status: StayAvailabilityStatus,
    /** Campsites bookable online for every night of the stay. */
    @SerialName("open_sites") val openSites: Int,
    /** Campsites the answer is over: those of the requested type that fit the group. */
    @SerialName("checked_sites") val checkedSites: Int,
    /** How old the observations behind the answer are; absent when nothing was read. */
    val cache: AvailabilityCacheBlock? = null,
)
