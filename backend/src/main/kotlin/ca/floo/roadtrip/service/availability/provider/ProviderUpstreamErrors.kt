package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import kotlinx.coroutines.CancellationException

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

/**
 * The one catch ladder every adapter wraps its upstream call in. Five copies of
 * it existed and had already drifted — Aspira's was missing the
 * [CancellationException] arm, so cancelling a poll run turned into a fake
 * `upstream_5xx` that cooled the provider down.
 *
 * The order is the contract: cancellation is never an upstream failure, an
 * [AvailabilityProviderError] a nested call already classified passes through
 * untouched, [vendorError] classifies the adapter's own vendor exception, and
 * [otherError] catches everything else — retryable-5xx by default, so an
 * unfamiliar failure fails over instead of stopping the candidate walk.
 */
suspend inline fun <reified V : Exception, T> mapUpstreamErrors(
    crossinline vendorError: (V) -> AvailabilityProviderError,
    crossinline otherError: (Exception) -> AvailabilityProviderError = {
        AvailabilityProviderError.UpstreamUnavailable(it)
    },
    crossinline block: suspend () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AvailabilityProviderError) {
        throw e
    } catch (e: Exception) {
        throw if (e is V) vendorError(e) else otherError(e)
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
