package ca.floo.roadtrip.support

class AspiraException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
