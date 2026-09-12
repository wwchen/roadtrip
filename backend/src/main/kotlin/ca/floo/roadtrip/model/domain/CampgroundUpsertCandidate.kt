package ca.floo.roadtrip.model.domain

import ca.floo.roadtrip.model.domain.provider.BookingAlias
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import kotlinx.serialization.json.JsonElement

data class CampgroundUpsertCandidate(
    val dataProviderRef: DataProviderRef,
    val bookingProvider: BookingProvider? = null,
    val bookingProviderRef: String? = null,
    val bookingAliases: List<BookingAlias> = emptyList(),
    val name: String,
    val parentName: String? = null,
    val latitude: Double,
    val longitude: Double,
    val status: String? = null,
    val statusDescription: String? = null,
    val kind: String? = null,
    val shortDescription: String? = null,
    val mediumDescription: String? = null,
    val longDescription: String? = null,
    val location: CampgroundLocation,
    val defaultCampsiteSchedule: CampgroundSchedule? = null,
    val amenities: List<CampgroundAmenity> = emptyList(),
    val maxRvLength: Double? = null,
    val maxTrailerLength: Double? = null,
    val hasPullThroughSites: Boolean? = null,
    val bigRigFriendly: Boolean? = null,
    val reservationUrl: String? = null,
    val links: List<CampgroundLink> = emptyList(),
    val photos: List<CatalogPhoto> = emptyList(),
    val alerts: List<CampgroundAlert> = emptyList(),
    val price: CampgroundPrice? = null,
    val cellService: List<CarrierSignal> = emptyList(),
    val management: CampgroundManagement? = null,
    val contact: CampgroundContact? = null,
    val connections: JsonElement? = null,
    val metadata: CampgroundMetadata? = null,
    val sourceUrl: String? = null,
    val sourcePayload: JsonElement? = null,
    val geometryProvenance: GeometryProvenance? = null,
)
