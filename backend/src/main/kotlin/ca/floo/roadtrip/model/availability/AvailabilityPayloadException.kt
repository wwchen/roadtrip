package ca.floo.roadtrip.model.availability

class AvailabilityPayloadException(
    val provider: String,
    val problem: String,
    cause: Throwable? = null,
) : RuntimeException("$provider availability payload unreadable: $problem", cause)
