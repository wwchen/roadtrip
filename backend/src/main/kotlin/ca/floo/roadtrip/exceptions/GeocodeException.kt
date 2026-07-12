package ca.floo.roadtrip.exceptions

class GeocodeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
