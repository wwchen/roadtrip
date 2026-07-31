package ca.floo.roadtrip.support

/**
 * Aspira transport/protocol failure.
 *
 * [cause] carries the underlying exception on the transport path — it is the
 * only thing that says *why* a connect failed (DNS, refused, unreachable,
 * TLS, closed channel). Dropping it collapses every distinct network fault
 * into one indistinguishable `upstream_5xx`.
 */
class AspiraException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
