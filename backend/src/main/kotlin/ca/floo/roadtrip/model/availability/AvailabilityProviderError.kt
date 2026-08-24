package ca.floo.roadtrip.model.availability

/**
 * Provider-agnostic error surface. Adapters translate vendor-specific
 * exceptions (Aspira HTTP 429, rec.gov rate-limit message strings, etc.)
 * into one of these so the route layer maps to HTTP without knowing what
 * upstream answered.
 *
 * Anything that doesn't map to a known case gets `Unknown` — the route
 * still produces a 503 with the original cause logged.
 */
sealed class AvailabilityProviderError(
    /**
     * Wire error code for this failure. Lives on the variant so routes and
     * services read one source of truth instead of each re-deriving it.
     * [message] stays free-form — some variants carry diagnostic prose.
     */
    val code: String,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: code, cause) {
    /** Upstream told us we're sending too many requests. */
    class RateLimited(
        cause: Throwable? = null,
    ) : AvailabilityProviderError("rate_limited", cause = cause)

    /** Upstream is up but blocking us (WAF, captcha, anti-bot). */
    class UpstreamBlocked(
        cause: Throwable? = null,
    ) : AvailabilityProviderError("upstream_blocked", cause = cause)

    /** Upstream answered, with a 5xx or an unusable body. */
    class UpstreamUnavailable(
        cause: Throwable,
    ) : AvailabilityProviderError("upstream_5xx", cause = cause)

    /**
     * We never got an answer: DNS, connect, TLS, or socket failure. Distinct
     * from [UpstreamUnavailable] because nothing upstream was reached, so
     * there is no upstream status to report and "upstream is down" may be a
     * lie — our own egress is equally likely (see the 2026-07-30 incident,
     * where a JDK HttpClient ConnectException read as `upstream_5xx` while
     * the vendor was serving 200s).
     */
    class UpstreamUnreachable(
        cause: Throwable,
    ) : AvailabilityProviderError("upstream_unreachable", cause = cause)

    /**
     * Our own config or catalog data is wrong for this provider — an
     * unconfigured tenant, an id that doesn't fit the vendor's type. Retrying
     * cannot help; a human has to fix config.
     */
    class Misconfigured(
        providerId: String,
        reason: String,
        cause: Throwable,
    ) : AvailabilityProviderError("provider_misconfigured", "$providerId misconfigured: $reason", cause)

    /** Adapter doesn't yet support the requested operation (capability stub). */
    class Unsupported(
        operation: String,
        providerId: String,
    ) : AvailabilityProviderError("unsupported", "$providerId does not support $operation")

    /**
     * Registry handed an adapter a `BookingProviderRef` of the wrong shape.
     * Programmer error — registry construction is wrong, not the request.
     */
    class WrongRefType(
        providerId: String,
        gotType: String,
    ) : AvailabilityProviderError(
            "provider_misconfigured",
            "$providerId received BookingProviderRef of type $gotType",
        )
}
