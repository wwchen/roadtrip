package ca.floo.roadtrip.clients.campflare

class CampflareException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
