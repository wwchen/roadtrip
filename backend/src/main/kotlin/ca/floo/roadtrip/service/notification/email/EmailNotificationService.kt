package ca.floo.roadtrip.service.notification.email

import ca.floo.roadtrip.client.resend.EmailDeliveryClient
import ca.floo.roadtrip.client.resend.EmailDeliveryMessage
import ca.floo.roadtrip.client.resend.ResendEmailClient
import ca.floo.roadtrip.config.EmailConfig
import ca.floo.roadtrip.service.notification.common.NotificationService
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.notification.common.WatchOpening
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * The email transport. Every watch message carries the target's magic link,
 * because email is the one transport whose recipient has no other way in — a
 * Slack card has buttons, a signed-in browser has the watches page.
 */
class EmailNotificationService(
    private val config: EmailConfig?,
    private val emailDeliveryClient: EmailDeliveryClient? = config?.let { ResendEmailClient(it) },
) : NotificationService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun canHandle(target: NotificationTarget): Boolean = target is NotificationTarget.Email

    override suspend fun sendWatchStatus(
        notice: WatchStatusNotice,
        target: NotificationTarget,
    ): Boolean {
        val emailTarget = target as? NotificationTarget.Email ?: return false
        val content = EmailContentWatchStatusRenderer.render(notice, emailTarget.magicLinkUrl)
        return sendContent(content, emailTarget.recipients, failureContext = "watch #${notice.watchId} status")
    }

    override suspend fun sendWatchOpenings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        target: NotificationTarget,
        appRootUrl: String?,
    ): Boolean {
        val emailTarget = target as? NotificationTarget.Email ?: return false
        val content =
            EmailContentAvailabilityRenderer.openings(
                watchId = watchId,
                startDate = startDate,
                endDate = endDate,
                openings = openings,
                appRootUrl = appRootUrl,
                magicLinkUrl = emailTarget.magicLinkUrl,
            )
        return sendContent(content, emailTarget.recipients, failureContext = "watch #$watchId opening alert")
    }

    suspend fun sendTestEmail(
        recipients: List<String>,
        appRootUrl: String? = null,
    ): Boolean {
        if (recipients.isEmpty()) return false
        return sendContent(EmailContentTestRenderer.render(appRootUrl), recipients, failureContext = "test email")
    }

    private suspend fun sendContent(
        content: EmailContent,
        recipients: List<String>,
        failureContext: String,
    ): Boolean {
        val emailConfig = config
        val emailClient = emailDeliveryClient
        if (emailConfig == null || emailClient == null) {
            log.warn("Email disabled (resend-api-key/from unset); {} not sent", failureContext)
            return false
        }
        if (recipients.isEmpty()) {
            log.warn("Email target has no recipients; {} not sent", failureContext)
            return false
        }
        return recipients
            .map { recipient ->
                emailClient.send(
                    EmailDeliveryMessage(
                        from = emailConfig.from,
                        to = recipient,
                        subject = content.subject,
                        text = content.text,
                        html = content.html,
                    ),
                )
            }.all { it }
    }
}
