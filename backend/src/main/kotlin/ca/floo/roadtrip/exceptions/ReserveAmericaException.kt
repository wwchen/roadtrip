package ca.floo.roadtrip.exceptions

class ReserveAmericaException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
