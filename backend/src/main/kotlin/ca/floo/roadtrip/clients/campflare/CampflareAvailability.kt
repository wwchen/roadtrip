package ca.floo.roadtrip.clients.campflare

import java.time.Instant

data class CampflareAvailability(
    val campgrounds: Map<String, CampflareCampgroundAvailability>,
    val observedAt: Instant,
)
