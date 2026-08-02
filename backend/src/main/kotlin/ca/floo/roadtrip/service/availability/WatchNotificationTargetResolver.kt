package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.security.SecretCipher

/**
 * Translates a watch's persisted trigger intent into concrete notification
 * targets, resolving the Slack destination from **owner-controlled sources
 * only**. This is the privacy boundary for watch alert cards: a card carries
 * Pause/Resume/Delete buttons and the Slack interactivity port applies those
 * mutations with no per-click owner check, so isolation must come from *where*
 * the card is delivered. Each owner's cards land only in a channel that owner
 * controls; a different user never sees the card, so can't click it.
 *
 * Slack channel priority (never falls back to the shared global default):
 *  1. the watch's own `trigger_config.slack.channel` override
 *     ([AvailabilityWatchRepo.Watch.channelOverride]),
 *  2. else the OWNER's `user_settings.slack_channel`.
 * When neither yields a channel, **no Slack target is produced** — Slack alerts
 * simply don't fire for that watch (email still does). That is the safe default:
 * silence beats leaking a card into a shared channel a stranger can act on.
 *
 * Slack token: the owner's decrypted `user_settings.slack_token_cipher` when
 * present ([SecretCipher.open]), else null. A null token means "use the global
 * bot token" — acceptable, because the security property is the owner-scoped
 * CHANNEL, not the token. [cipher] is null when the encryption key is not
 * configured; in that case the token stays null (no crash).
 *
 * Email is unchanged: the recipients the watch opted into.
 */
internal class WatchNotificationTargetResolver(
    private val userSettingsRepo: UserSettingsRepo,
    private val cipher: SecretCipher?,
) {
    fun resolve(watch: AvailabilityWatchRepo.Watch): List<NotificationTarget> =
        buildList {
            slackTarget(watch)?.let(::add)
            if (AvailabilityTriggerKinds.EMAIL_NOTIFY in watch.triggerKinds) {
                add(NotificationTarget.Email(recipients = watch.emailRecipients()))
            }
        }

    /**
     * The owner-scoped Slack target, or null when no owner-controlled channel is
     * available (the leak-closure case). Kind-agnostic by design: it resolves
     * purely from the channel sources, because ATC is Slack-only yet carries the
     * `atc` kind rather than `slack_notify`. The channel gate (not a trigger-kind
     * gate) is what decides whether Slack fires: no owner-controlled channel means
     * no card, for any kind.
     */
    private fun slackTarget(watch: AvailabilityWatchRepo.Watch): NotificationTarget.Slack? {
        val ownerSettings = userSettingsRepo.find(UserId(watch.ownerUserId))
        val channel = watch.channelOverride() ?: ownerSettings?.slackChannel ?: return null
        val token = ownerSettings?.slackTokenCipher?.let { blob -> cipher?.open(blob) }
        return NotificationTarget.Slack(channel = channel, token = token)
    }
}
