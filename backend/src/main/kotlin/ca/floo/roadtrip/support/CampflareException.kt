package ca.floo.roadtrip.support

/** [cause] carries the transport failure; see [AspiraException]. */
class CampflareException(
    message: String,
    override val httpStatus: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause),
    UpstreamHttpException
