package ca.floo.roadtrip.exceptions

/** Any routing failure. Caller maps to HTTP. */
class RoutingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
