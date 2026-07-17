package ca.floo.roadtrip.support

class ReserveCaliforniaException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
