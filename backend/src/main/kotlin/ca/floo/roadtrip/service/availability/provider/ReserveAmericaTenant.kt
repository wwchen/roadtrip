package ca.floo.roadtrip.service.availability.provider

data class ReserveAmericaTenant(
    val source: String,
    val host: String,
    val contractCode: String,
    val bookingHorizonDays: Int,
)
