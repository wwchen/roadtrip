package ca.floo.roadtrip.model.metadata.registry

data class ReserveAmericaSourceConfig(
    val source: String,
    val host: String,
    val contractCode: String,
    val bookingHorizonDays: Int,
)
