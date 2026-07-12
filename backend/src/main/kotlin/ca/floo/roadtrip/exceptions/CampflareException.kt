package ca.floo.roadtrip.exceptions

class CampflareException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
