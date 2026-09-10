package ca.floo.roadtrip.model.api.poi

import ca.floo.roadtrip.model.api.BookingRefDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PoiCategoryDetailSchema(
    // Vendors represented by this canonical row (currently one source per campground row).
    val sources: List<String> = emptyList(),
    @SerialName("availability_provider") val availabilityProvider: String? = null,
    @SerialName("time_zone") val timeZone: String? = null,
    @SerialName("earliest_date") val earliestDate: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("reserve_url") val reserveUrl: String? = null,
    @SerialName("booking_site") val bookingSite: String? = null,
    val phone: String? = null,
    @SerialName("info_url") val infoUrl: String? = null,
    // As the source states them, unparsed — the FE prints the string.
    @SerialName("opening_hours") val openingHours: String? = null,
    val brand: String? = null,
    val address: JsonElement? = null,
    val description: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("booking_ref") val bookingRef: BookingRefDto? = null,
    @SerialName("availability_supported") val availabilitySupported: Boolean? = null,
    // Backend-computed ordered CTAs for the pin's action buttons. The first
    // entry is the primary action; later entries are secondary actions. Picks
    // URLs + labels from booking_ref / info_url so the FE can render blindly
    // without owning per-vendor precedence rules. null when the row has no
    // usable upstream link (FE falls back to name search).
    val cta: List<PoiCtaSchema>? = null,
    // Display name for the booking system that reservations on this pin
    // flow through ("Recreation.gov", "Aspira NextGen (BC Parks)", …).
    // Used by the drawer footer; null when the pin has no known provider.
    @SerialName("booking_system") val bookingSystem: String? = null,
    val raw: JsonElement? = null,
    // Key/values for the drawer's "Upstream data" table. Backend-shaped so the
    // FE never reaches into `raw` for a vendor's own nesting.
    val upstream: JsonElement? = null,
    // Campground canonical columns, served as named fields so the FE reads
    // them directly instead of digging a vendor-shaped payload out of `raw`.
    val status: String? = null,
    @SerialName("status_description") val statusDescription: String? = null,
    val kind: String? = null,
    @SerialName("parent_name") val parentName: String? = null,
    // The typed campground bags. Labels are resolved backend-side so the
    // amenity and carrier vocabularies live in one registry, not two.
    val amenities: List<AmenityDto> = emptyList(),
    @SerialName("cell_coverage") val cellCoverage: List<CarrierSignalDto> = emptyList(),
    val activities: List<String> = emptyList(),
    val rating: RatingDto? = null,
    val price: PriceDto? = null,
    val schedule: ScheduleDto? = null,
    @SerialName("max_rv_length") val maxRvLength: Double? = null,
    @SerialName("max_trailer_length") val maxTrailerLength: Double? = null,
    @SerialName("has_pull_through_sites") val hasPullThroughSites: Boolean? = null,
    @SerialName("big_rig_friendly") val bigRigFriendly: Boolean? = null,
    val links: JsonElement? = null,
    val alerts: List<AlertDto> = emptyList(),
    val connections: JsonElement? = null,
    val management: JsonElement? = null,
    val contact: JsonElement? = null,
    val email: String? = null,
    val elevation: Double? = null,
    @SerialName("last_verified") val lastVerified: String? = null,
    // Charger canonical columns, served as named fields for the same reason as
    // the campground block above. `status` and `time_zone` are shared with
    // campgrounds and reused rather than duplicated; `amenities` is not —
    // it now carries the campground vocabulary only.
    @SerialName("stall_count") val stallCount: Int? = null,
    @SerialName("power_kilowatt") val powerKilowatt: Int? = null,
    val pricebooks: JsonElement? = null,
    @SerialName("availability_profile") val availabilityProfile: JsonElement? = null,
    @SerialName("open_to_non_teslas") val openToNonTeslas: Boolean? = null,
    @SerialName("trailer_friendly") val trailerFriendly: Boolean? = null,
    @SerialName("twenty_four_seven") val twentyFourSeven: Boolean? = null,
)
