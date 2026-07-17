package ca.floo.roadtrip.model.availability.campflare

data class CampflareCampgroundAvailability(
    val campgroundId: String,
    val campsiteAvailability: List<CampflareCampsiteAvailability>,
)
