package ca.floo.roadtrip.model.domain

import ca.floo.roadtrip.model.domain.provider.BookingAlias
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * One row in the `campgrounds` table.
 */
data class Campground(
    val id: Long,
    val name: String,
    val parentName: String? = null,
    val status: String?,
    val statusDescription: String?,
    val kind: String?,
    val shortDescription: String?,
    val mediumDescription: String?,
    val longDescription: String?,
    val location: CampgroundLocation?,
    val defaultCampsiteSchedule: CampgroundSchedule? = null,
    val amenities: List<CampgroundAmenity> = emptyList(),
    val maxRvLength: Double?,
    val maxTrailerLength: Double?,
    val hasPullThroughSites: Boolean?,
    val bigRigFriendly: Boolean?,
    val reservationUrl: String?,
    val links: List<CampgroundLink>,
    val photos: List<CatalogPhoto>,
    val alerts: List<CampgroundAlert> = emptyList(),
    val price: CampgroundPrice? = null,
    val cellService: List<CarrierSignal> = emptyList(),
    val management: CampgroundManagement?,
    val contact: CampgroundContact?,
    val connections: JsonElement,
    val metadata: CampgroundMetadata? = null,
    val sourcePayload: JsonElement,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val dataProviderRef: DataProviderRef,
    val bookingProvider: String?,
    val bookingProviderRef: String?,
    val bookingAliases: List<BookingAlias> = emptyList(),
)

fun Campground.bookingRef(): BookingProviderRef? {
    val provider = bookingProvider?.let(BookingProvider::fromIdOrNull) ?: return null
    return bookingProviderRef?.let { BookingProviderRef.parse(provider, it) }
}

/**
 * This campground's identity *on [provider]*: its primary booking ref when
 * that ref is this provider's, else the matching [BookingAlias] parsed as this
 * provider's ref. Null when the row names the provider nowhere.
 */
fun Campground.bookingRefFor(provider: BookingProvider): BookingProviderRef? {
    val primary = bookingRef()
    if (primary?.provider == provider) return primary
    val alias = bookingAliases.firstOrNull { it.provider == provider } ?: return null
    return BookingProviderRef.parse(provider, alias.ref)
}
