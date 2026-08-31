package ca.floo.roadtrip.support

/** [cause] carries the transport failure; mirrors the other vendor wrappers. */
class RecGovException(
    message: String,
    override val httpStatus: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause),
    UpstreamHttpException
