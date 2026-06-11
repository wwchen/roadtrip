package ca.floo.roadtrip.service.booking

/**
 * Provider-agnostic error surface. Adapters translate vendor-specific
 * exceptions (Aspira HTTP 429, rec.gov rate-limit message strings, etc.)
 * into one of these so the route layer maps to HTTP without knowing what
 * upstream answered.
 *
 * Anything that doesn't map to a known case gets `Unknown` — the route
 * still produces a 503 with the original cause logged.
 */
sealed class BookingProviderError(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Upstream told us we're sending too many requests. */
    class RateLimited(
        cause: Throwable? = null,
    ) : BookingProviderError("rate_limited", cause)

    /** Upstream is up but blocking us (WAF, captcha, anti-bot). */
    class UpstreamBlocked(
        cause: Throwable? = null,
    ) : BookingProviderError("upstream_blocked", cause)

    /** Upstream returned 5xx, network error, parse failure. */
    class UpstreamUnavailable(
        cause: Throwable,
    ) : BookingProviderError("upstream_5xx", cause)

    /** Adapter doesn't yet support the requested operation (capability stub). */
    class Unsupported(
        operation: String,
        providerId: BookingProviderId,
    ) : BookingProviderError("$providerId does not support $operation")

    /**
     * Registry handed an adapter a `ProviderRef` of the wrong shape.
     * Programmer error — registry construction is wrong, not the request.
     */
    class WrongRefType(
        providerId: BookingProviderId,
        gotType: String,
    ) : BookingProviderError("$providerId received ProviderRef of type $gotType")
}
