package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.service.notification.WatchOpening
import java.time.LocalDate

/**
 * One concrete opening handed to trigger-action handlers.
 *
 * [notification] is the Slack/display projection. [campsite] and
 * [resolvedTarget] keep the provider identity beside it so booking triggers can
 * act on the same opening without reverse-parsing notification text or URLs.
 */
internal data class TriggerOpening(
    val campsite: CampsiteAvailabilityTarget,
    val date: LocalDate,
    val resolvedTarget: ResolvedAvailabilityTarget?,
    val notification: WatchOpening,
)
