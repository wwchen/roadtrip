package ca.floo.roadtrip.client.resend

import ca.floo.roadtrip.config.EmailConfig
import com.resend.Resend
import com.resend.services.emails.model.CreateEmailOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Thin transport wrapper around the Resend Java SDK. Business formatting and
 * recipient policy live in the notification service; this class only performs
 * the outbound API call and returns the outcome, with Resend's reason on failure.
 */
class ResendEmailClient(
    config: EmailConfig,
    private val resend: Resend = Resend(config.resendApiKey),
) : EmailDeliveryClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun send(message: EmailDeliveryMessage): EmailDelivery =
        withContext(Dispatchers.IO) {
            runCatching {
                val options =
                    CreateEmailOptions
                        .builder()
                        .from(message.from)
                        .to(message.to)
                        .subject(message.subject)
                        .text(message.text)
                        .html(message.html)
                        .build()

                val response = resend.emails().send(options)
                log.info("Resend email sent to {}: {}", message.to, response.getId())
                EmailDelivery.Sent(response.getId())
            }.getOrElse { err ->
                val reason = err.message ?: err.javaClass.simpleName
                log.warn("Resend email send to {} failed: {}", message.to, reason)
                EmailDelivery.Failed(reason)
            }
        }
}
