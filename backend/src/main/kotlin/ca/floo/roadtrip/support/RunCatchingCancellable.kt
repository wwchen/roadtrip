package ca.floo.roadtrip.support

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` for suspend code: every failure but cancellation is a [Result].
 *
 * Plain `runCatching` catches [CancellationException] along with everything
 * else, which turns "this coroutine was cancelled" into a fake outcome the
 * caller then reports. On the ATC fire path that meant a cancelled request
 * reaching the owner as an emailed companion failure, and one bogus
 * `unavailable` metric per profile in the keepalive sweep at shutdown.
 *
 * The same rule the availability adapters' `mapUpstreamErrors` ladder already
 * enforces upstream — stated once here for the callers that want a `Result`
 * rather than a typed provider error.
 */
suspend inline fun <T> runCatchingCancellable(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
