package ca.floo.roadtrip.models.api.poi

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
    val address: JsonElement? = null,
    val description: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("provider_ref") val providerRef: JsonElement? = null,
    // Backend-owned capability flag. The FE should not know individual
    // provider_ref shapes just to decide whether to mount availability UI.
    @SerialName("availability_supported") val availabilitySupported: Boolean? = null,
    // Backend-computed ordered CTAs for the pin's action buttons. The first
    // entry is the primary action; later entries are secondary actions. Picks
    // URLs + labels from provider_ref / info_url so the FE can render blindly
    // without owning per-vendor precedence rules. null when the row has no
    // usable upstream link (FE falls back to name search).
    val cta: List<PoiCtaSchema>? = null,
    // Display name for the booking system that reservations on this pin
    // flow through ("Recreation.gov", "Aspira NextGen (BC Parks)", …).
    // Used by the drawer footer; null when the pin has no known provider.
    @SerialName("booking_system") val bookingSystem: String? = null,
    val raw: JsonElement,
)
