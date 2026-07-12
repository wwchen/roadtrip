package ca.floo.roadtrip.clients.mapbox

class GeocodeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
