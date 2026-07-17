package ca.floo.roadtrip.support

/** Any routing failure. Caller maps to HTTP. */
class RoutingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
