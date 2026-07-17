package ca.floo.roadtrip.service.notification.email

import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.stream.createHTML

private const val TEST_EMAIL_SUBJECT = "Roadtrip test email"
private const val TEST_EMAIL_TEXT = "This is a test email from Roadtrip."

internal object EmailContentTestRenderer {
    fun render(): EmailContent =
        EmailContent(
            subject = TEST_EMAIL_SUBJECT,
            text = TEST_EMAIL_TEXT,
            html =
                createHTML().div {
                    h2 { +TEST_EMAIL_SUBJECT }
                    p { +TEST_EMAIL_TEXT }
                },
        )
}
