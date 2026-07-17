package ca.floo.roadtrip.service.notification.email

import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.stream.createHTML

private const val TEST_EMAIL_SUBJECT = "Roadtrip test email"

internal object EmailContentTestRenderer {
    fun render(appRootUrl: String?): EmailContent {
        val origin = appRootUrl ?: "unknown"
        val text = "This is a test email from Roadtrip.\nSent from: $origin"
        return EmailContent(
            subject = TEST_EMAIL_SUBJECT,
            text = text,
            html =
                createHTML().div {
                    h2 { +TEST_EMAIL_SUBJECT }
                    p { +"This is a test email from Roadtrip." }
                    p { +"Sent from: $origin" }
                },
        )
    }
}
