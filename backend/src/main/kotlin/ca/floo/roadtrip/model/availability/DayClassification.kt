package ca.floo.roadtrip.model.availability

data class DayClassification(
    val date: String,
    val status: AvailabilityStatus,
    val availableCampsiteIds: List<Long>? = null,
    val campsiteStatuses: Map<Long, AvailabilityStatus>? = null,
)
