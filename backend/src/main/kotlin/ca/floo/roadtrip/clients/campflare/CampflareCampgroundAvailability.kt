package ca.floo.roadtrip.clients.campflare

data class CampflareCampgroundAvailability(
    val campgroundId: String,
    val campsiteAvailability: List<CampflareCampsiteAvailability>,
)
