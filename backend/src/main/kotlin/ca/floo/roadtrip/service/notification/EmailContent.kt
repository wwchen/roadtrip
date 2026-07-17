package ca.floo.roadtrip.service.notification

internal data class EmailContent(
    val subject: String,
    val text: String,
    val html: String,
)
