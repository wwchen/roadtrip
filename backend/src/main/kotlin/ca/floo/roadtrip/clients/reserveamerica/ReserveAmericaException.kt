package ca.floo.roadtrip.clients.reserveamerica

class ReserveAmericaException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
