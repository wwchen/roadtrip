package ca.floo.roadtrip.support

class ReserveAmericaException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
