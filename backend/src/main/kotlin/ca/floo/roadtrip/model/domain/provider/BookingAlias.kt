package ca.floo.roadtrip.model.domain.provider

import kotlinx.serialization.Serializable

/**
 * Another provider's identity for inventory whose primary booking ref belongs
 * to someone else: a Campflare campground that rec.gov also sells carries the
 * rec.gov facility id here. One entry of the `booking_aliases` JSONB array on
 * `campgrounds` and `campsites`.
 */
@Serializable
data class BookingAlias(
    val provider: BookingProvider,
    val ref: String,
)
