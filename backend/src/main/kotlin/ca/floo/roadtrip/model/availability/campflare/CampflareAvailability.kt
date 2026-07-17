package ca.floo.roadtrip.model.availability.campflare

import java.time.Instant

data class CampflareAvailability(
    val campgrounds: Map<String, CampflareCampgroundAvailability>,
    val observedAt: Instant,
)
