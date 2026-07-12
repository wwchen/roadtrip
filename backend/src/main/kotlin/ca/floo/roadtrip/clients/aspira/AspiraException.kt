package ca.floo.roadtrip.clients.aspira

class AspiraException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
