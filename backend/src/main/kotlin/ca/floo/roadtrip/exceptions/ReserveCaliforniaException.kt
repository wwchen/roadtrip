package ca.floo.roadtrip.exceptions

class ReserveCaliforniaException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
