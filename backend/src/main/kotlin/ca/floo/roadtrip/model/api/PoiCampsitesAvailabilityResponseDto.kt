package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One campground POI's fused availability week: the campsite streams already
 * rolled up per day, so clients render what the backend decided.
 */
@Serializable
data class PoiCampsitesAvailabilityResponseDto(
    @SerialName("poi_id") val poiId: Long,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    /**
     * The provider's booking horizon: the last date a client may ask for. Null
     * when no registered provider claims the campground — there is no ceiling
     * to state, and inventing one would offer dates the vendor refuses.
     */
    @SerialName("latest_date") val latestDate: String? = null,
    val state: AvailabilityWindowState,
    /** The provider's reopen hint, only when [state] is `closed_for_season`. */
    val season: JsonElement? = null,
    /** The stalest freshness block across the campsite streams. */
    val cache: AvailabilityCacheBlock? = null,
    val days: List<AvailabilityDayDto>,
    @SerialName("watch_capabilities") val watchCapabilities: AvailabilityWatchCapabilitiesDto,
)
