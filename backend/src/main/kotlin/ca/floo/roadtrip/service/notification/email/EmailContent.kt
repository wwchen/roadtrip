package ca.floo.roadtrip.service.notification.email

internal data class EmailContent(
    val subject: String,
    val text: String,
    val html: String,
)

/** A labelled link in an email body. */
internal data class EmailLink(
    val label: String,
    val url: String,
)
