package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.notification.EmailNotificationService

/**
 * The `email_notify` trigger action: sends the same hydrated opening set as
 * Slack, but through the configured email notification service.
 */
internal class EmailNotifyHandler(
    private val email: EmailNotificationService,
    private val appRootUrl: String?,
) : TriggerActionHandler {
    override val kind: String = KIND

    override suspend fun fire(
        watch: AvailabilityWatchRepo.Watch,
        openings: List<TriggerOpening>,
    ): Boolean =
        email.sendWatchOpenings(
            watchId = watch.id,
            startDate = watch.startDate,
            endDate = watch.endDate,
            openings = openings.map { it.notification },
            appRootUrl = appRootUrl,
        )

    companion object {
        const val KIND = AvailabilityTriggerKinds.EMAIL_NOTIFY
    }
}
