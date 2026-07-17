package ca.floo.roadtrip.clients.resend

import ca.floo.roadtrip.config.EmailConfig
import com.resend.Resend
import com.resend.services.emails.model.CreateEmailOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Thin transport wrapper around the Resend Java SDK. Business formatting and
 * recipient policy live in the notification service; this class only performs
 * the outbound API call and surfaces delivery-attempt success as a Boolean.
 */
class ResendEmailClient(
    config: EmailConfig,
    private val resend: Resend = Resend(config.resendApiKey),
) : EmailDeliveryClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun send(message: EmailDeliveryMessage): Boolean =
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
                true
            }.onFailure { err ->
                log.warn("Resend email send to {} failed: {}", message.to, err.message)
            }.getOrDefault(false)
        }
}
