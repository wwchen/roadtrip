package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.api.HTTP_TOO_MANY_REQUESTS
import ca.floo.roadtrip.model.availability.AvailabilityPayloadException
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException

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
        // Upstream said "slow down". Always retryable, always its own outcome.
        httpStatus == HTTP_TOO_MANY_REQUESTS -> AvailabilityProviderError.RateLimited(cause)
        httpStatus != null && httpStatus in blockedStatuses -> AvailabilityProviderError.UpstreamBlocked(cause)
        blockedMessageMarker != null && cause.message?.contains(blockedMessageMarker) == true ->
            AvailabilityProviderError.UpstreamBlocked(cause)

        httpStatus == null && isTransportFailure(cause) -> AvailabilityProviderError.UpstreamUnreachable(cause)
        else -> AvailabilityProviderError.UpstreamUnavailable(cause)
    }

suspend inline fun <reified V : Exception, T> mapUpstreamErrors(
    crossinline vendorError: (V) -> AvailabilityProviderError,
    crossinline block: suspend () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AvailabilityProviderError) {
        throw e
    } catch (e: Exception) {
        throw if (e is V) vendorError(e) else ownAvailabilityError(e)
    }

fun ownAvailabilityError(cause: Exception): AvailabilityProviderError =
    when {
        causeChainHas(cause) { it is AvailabilityPayloadException || it is SerializationException } ->
            AvailabilityProviderError.UnreadableResponse(cause)
        causeChainHas(cause) { it is IOException } -> upstreamAvailabilityError(cause = cause, httpStatus = null)
        else -> AvailabilityProviderError.Unknown(cause)
    }

/** Depth cap so a self-referential cause chain can't spin. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * True when the exchange never reached the vendor: DNS, connect, TLS, socket.
 *
 * Guarded by `httpStatus == null` at the call site — once upstream has given
 * us a status, a later IO failure is a bad response, not an unreachable host.
 */
private fun isTransportFailure(cause: Throwable): Boolean =
    causeChainHas(cause) { t ->
        t is java.net.UnknownHostException ||
            t is java.net.ConnectException ||
            t is java.net.NoRouteToHostException ||
            t is java.net.PortUnreachableException ||
            t is java.net.SocketTimeoutException ||
            t is java.nio.channels.ClosedChannelException ||
            t is javax.net.ssl.SSLException ||
            t is java.net.http.HttpConnectTimeoutException
    }

private fun causeChainHas(
    cause: Throwable,
    matches: (Throwable) -> Boolean,
): Boolean {
    var t: Throwable? = cause
    var depth = 0
    val seen = mutableSetOf<Throwable>()
    while (t != null && depth++ < MAX_CAUSE_DEPTH && seen.add(t)) {
        if (matches(t)) return true
        t = t.cause
    }
    return false
}
