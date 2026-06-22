package ca.floo.roadtrip.models.availability

data class DayClassification(
    val date: String,
    val status: AvailabilityStatus,
    val availableReservableIds: List<String>? = null,
    val reservableStatuses: Map<String, AvailabilityStatus>? = null,
)
