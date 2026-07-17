package ca.floo.roadtrip.service.notification.email

import ca.floo.roadtrip.clients.resend.EmailDeliveryClient
import ca.floo.roadtrip.clients.resend.EmailDeliveryMessage
import ca.floo.roadtrip.clients.resend.ResendEmailClient
import ca.floo.roadtrip.config.EmailConfig
import ca.floo.roadtrip.service.notification.common.NotificationService
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.notification.common.WatchOpening
import org.slf4j.LoggerFactory
import java.time.LocalDate

class EmailNotificationService(
    private val config: EmailConfig?,
    private val client: EmailDeliveryClient? = config?.let { ResendEmailClient(it) },
) : NotificationService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun canHandle(target: NotificationTarget): Boolean = target is NotificationTarget.Email

    override suspend fun sendWatchOpenings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        target: NotificationTarget,
        appRootUrl: String?,
    ): Boolean {
        val emailTarget = target as? NotificationTarget.Email ?: return false
        if (config == null || client == null) {
            log.warn("Email disabled (resend-api-key/from/default-to unset); watch #{} opening alert not sent", watchId)
            return false
        }
        val recipients = emailTarget.recipients.ifEmpty { config.defaultTo }
        if (recipients.isEmpty()) {
            log.warn("Email target has no recipients; watch #{} opening alert not sent", watchId)
            return false
        }
        val content = EmailContentAvailabilityRenderer.openings(watchId, startDate, endDate, openings, appRootUrl)
        return recipients
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
