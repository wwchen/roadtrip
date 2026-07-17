package ca.floo.roadtrip.support

class GeocodeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
