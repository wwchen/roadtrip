package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.resend.EmailDeliveryClient
import ca.floo.roadtrip.clients.resend.EmailDeliveryMessage
import ca.floo.roadtrip.clients.resend.ResendEmailClient
import ca.floo.roadtrip.config.EmailConfig
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Email notification service for watch opening alerts. Configuration owns the
 * default recipient list; callers pass only watch/opening domain data.
 */
class EmailNotificationServiceImpl(
    private val config: EmailConfig?,
    private val client: EmailDeliveryClient? = config?.let { ResendEmailClient(it) },
) : EmailNotificationService {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun sendWatchOpenings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        appRootUrl: String?,
    ): Boolean {
        if (openings.isEmpty()) return false
        if (config == null || client == null) {
            log.warn("Email disabled (resend-api-key/from/default-to unset); watch #{} opening alert not sent", watchId)
            return false
        }
        val content = EmailContentAvailabilityRenderer.openings(watchId, startDate, endDate, openings, appRootUrl)
        return config.defaultTo
            .map { recipient ->
                client.send(
                    EmailDeliveryMessage(
                        from = config.from,
                        to = recipient,
                        subject = content.subject,
                        text = content.text,
                        html = content.html,
                    ),
                )
            }.all { it }
    }
}
