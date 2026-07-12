package ca.floo.roadtrip.clients.reservecalifornia

class ReserveCaliforniaException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
