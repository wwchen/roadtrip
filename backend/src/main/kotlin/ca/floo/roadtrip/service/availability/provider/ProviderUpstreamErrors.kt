package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.availability.AvailabilityProviderError

/** Upstream said "slow down". Always retryable, always its own outcome. */
const val HTTP_TOO_MANY_REQUESTS = 429
const val HTTP_UNAUTHORIZED = 401
const val HTTP_FORBIDDEN = 403
const val HTTP_SERVICE_UNAVAILABLE = 503

/**
 * Translates a vendor exception's HTTP status into the provider-neutral
 * [AvailabilityProviderError] the failover fetcher and cooldown tracker
 * understand. One implementation for every adapter: the classification decides
 * whether we retry, fail over, or cool a provider down, and four copies of that
 * `when` drifted apart once already.
 *
 * What stays per-adapter is *policy*, expressed as arguments:
 * [blockedStatuses] are the statuses this vendor uses for a bot/WAF block
 * rather than an outage (empty for vendors that never block), and
 * [blockedMessageMarker] catches vendors whose block arrives with an innocuous
 * status but a telltale body (Aspira's Azure WAF). Everything unclassified is
 * [AvailabilityProviderError.UpstreamUnavailable] — the retryable default, so a
 * new upstream failure mode fails over instead of stopping the walk.
 */
fun upstreamAvailabilityError(
    cause: Throwable,
    httpStatus: Int?,
    blockedStatuses: Set<Int> = emptySet(),
    blockedMessageMarker: String? = null,
): AvailabilityProviderError =
    when {
        httpStatus == HTTP_TOO_MANY_REQUESTS -> AvailabilityProviderError.RateLimited(cause)
        httpStatus != null && httpStatus in blockedStatuses -> AvailabilityProviderError.UpstreamBlocked(cause)
        blockedMessageMarker != null && cause.message?.contains(blockedMessageMarker) == true ->
            AvailabilityProviderError.UpstreamBlocked(cause)

        httpStatus == null && isTransportFailure(cause) -> AvailabilityProviderError.UpstreamUnreachable(cause)
        else -> AvailabilityProviderError.UpstreamUnavailable(cause)
    }

/** Depth cap so a self-referential cause chain can't spin. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * True when the exchange never reached the vendor: DNS, connect, TLS, socket.
 *
 * Guarded by `httpStatus == null` at the call site — once upstream has given
 * us a status, a later IO failure is a bad response, not an unreachable host.
 */
private fun isTransportFailure(cause: Throwable): Boolean {
    var t: Throwable? = cause
    var depth = 0
    val seen = mutableSetOf<Throwable>()
    while (t != null && depth++ < MAX_CAUSE_DEPTH && seen.add(t)) {
        when (t) {
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.NoRouteToHostException,
            is java.net.PortUnreachableException,
            is java.net.SocketTimeoutException,
            is java.nio.channels.ClosedChannelException,
            is javax.net.ssl.SSLException,
            is java.net.http.HttpConnectTimeoutException,
            -> return true
        }
        t = t.cause
    }
    return false
}
