package ca.floo.roadtrip.service.notification.email

import ca.floo.roadtrip.client.resend.EmailDeliveryClient
import ca.floo.roadtrip.client.resend.EmailDeliveryMessage
import ca.floo.roadtrip.client.resend.ResendEmailClient
import ca.floo.roadtrip.config.EmailConfig
import ca.floo.roadtrip.service.availability.WatchManagementTokenService
import ca.floo.roadtrip.service.notification.common.NotificationService
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.notification.common.WatchOpening
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import org.slf4j.LoggerFactory
import java.time.LocalDate

class EmailNotificationService(
    private val config: EmailConfig?,
    private val emailDeliveryClient: EmailDeliveryClient? = config?.let { ResendEmailClient(it) },
    private val watchManagementTokens: WatchManagementTokenService? = null,
) : NotificationService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun canHandle(target: NotificationTarget): Boolean = target is NotificationTarget.Email

    override suspend fun sendWatchStatus(
        notice: WatchStatusNotice,
        target: NotificationTarget,
    ): Boolean {
        val emailTarget = target as? NotificationTarget.Email ?: return false
        // Terminal states (done/stopped) have nothing left to manage, so no
        // link needs a token that can outlive them.
        val manageableState = notice.state != WatchStatusNotice.State.DONE && notice.state != WatchStatusNotice.State.STOPPED
        val token = if (manageableState) mintManagementToken(notice.watchId) else null
        val content = EmailContentWatchStatusRenderer.render(notice, token)
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
        val token = mintManagementToken(watchId)
        val content = EmailContentAvailabilityRenderer.openings(watchId, startDate, endDate, openings, appRootUrl, token)
        return sendContent(content, emailTarget.recipients, failureContext = "watch #$watchId opening alert")
    }

    /** Null when token minting is unavailable (wiring gap) — the email still sends, just without a magic link. */
    private fun mintManagementToken(watchId: Long): String? = watchManagementTokens?.issue(watchId)?.token

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
