package ca.floo.roadtrip.support

class CampflareException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
