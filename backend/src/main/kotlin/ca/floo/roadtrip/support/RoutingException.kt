package ca.floo.roadtrip.support

enum class RoutingFailure {
    NOT_CONFIGURED,
    UNREACHABLE,
    UPSTREAM_AUTH,
    UPSTREAM_RATE_LIMITED,
    UPSTREAM_ERROR,
    NO_ROUTE,
    NO_SEGMENT,
    INVALID_INPUT,
    CORRIDOR,
}

/** Any routing failure. Caller maps to HTTP. */
class RoutingException(
    message: String,
    cause: Throwable? = null,
    val failure: RoutingFailure = RoutingFailure.UPSTREAM_ERROR,
) : RuntimeException(message, cause)
