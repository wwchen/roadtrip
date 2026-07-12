package ca.floo.roadtrip.exceptions

class AspiraException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
