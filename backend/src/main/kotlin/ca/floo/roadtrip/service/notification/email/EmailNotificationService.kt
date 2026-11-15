package ca.floo.roadtrip.service.notification.email

import ca.floo.roadtrip.client.resend.EmailDelivery
import ca.floo.roadtrip.client.resend.EmailDeliveryClient
import ca.floo.roadtrip.client.resend.EmailDeliveryMessage
import ca.floo.roadtrip.client.resend.ResendEmailClient
import ca.floo.roadtrip.config.EmailConfig
import ca.floo.roadtrip.service.notification.common.AtcResultNotice
import ca.floo.roadtrip.service.notification.common.NotificationService
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.notification.common.WatchOpening
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import org.slf4j.LoggerFactory
import java.time.LocalDate

private const val EMAIL_DISABLED = "email is not configured (resend-api-key or from unset)"
private const val NO_RECIPIENTS = "no recipients"

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
        return sendContent(content, emailTarget.recipients, failureContext = "watch #${notice.watchId} status") is EmailDelivery.Sent
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
        return sendContent(content, emailTarget.recipients, failureContext = "watch #$watchId opening alert") is EmailDelivery.Sent
    }

    /**
     * The ATC outcome, to the owner's inbox.
     *
     * The Slack card is fail-closed on a personal token, so email is what makes
     * a hold — or a missed one — reach the person whose watch fired at all.
     * `request` is the companion payload and stays out of the body: it carries
     * the owner's profile id and nothing the recipient can act on.
     */
    override suspend fun sendAtcResult(
        notice: AtcResultNotice,
        target: NotificationTarget,
    ): Boolean {
        val emailTarget = target as? NotificationTarget.Email ?: return false
        val content = EmailContentAtcResultRenderer.render(notice, magicLinkUrl = emailTarget.magicLinkUrl)
        return sendContent(content, emailTarget.recipients, failureContext = "watch #${notice.watchId} ATC result") is EmailDelivery.Sent
    }

    suspend fun sendTestEmail(
        recipients: List<String>,
        appRootUrl: String? = null,
    ): EmailDelivery {
        if (recipients.isEmpty()) return EmailDelivery.Failed(NO_RECIPIENTS)
        return sendContent(EmailContentTestRenderer.render(appRootUrl), recipients, failureContext = "test email")
    }

    private suspend fun sendContent(
        content: EmailContent,
        recipients: List<String>,
        failureContext: String,
    ): EmailDelivery {
        val emailConfig = config
        val emailClient = emailDeliveryClient
        if (emailConfig == null || emailClient == null) {
            log.warn("Email disabled (resend-api-key/from unset); {} not sent", failureContext)
            return EmailDelivery.Failed(EMAIL_DISABLED)
        }
        if (recipients.isEmpty()) {
            log.warn("Email target has no recipients; {} not sent", failureContext)
            return EmailDelivery.Failed(NO_RECIPIENTS)
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
            }.let { deliveries -> deliveries.firstOrNull { it is EmailDelivery.Failed } ?: deliveries.last() }
    }
}
