package ca.floo.roadtrip.models.availability

data class DayClassification(
    val date: String,
    val status: AvailabilityStatus,
    val availableCampsiteIds: List<Long>? = null,
    val campsiteStatuses: Map<Long, AvailabilityStatus>? = null,
)
