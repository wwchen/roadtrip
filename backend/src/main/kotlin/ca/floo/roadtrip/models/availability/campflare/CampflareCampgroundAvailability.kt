package ca.floo.roadtrip.models.availability.campflare

data class CampflareCampgroundAvailability(
    val campgroundId: String,
    val campsiteAvailability: List<CampflareCampsiteAvailability>,
)
