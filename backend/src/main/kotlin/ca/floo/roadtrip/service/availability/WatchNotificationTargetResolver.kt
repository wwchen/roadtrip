package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.security.SecretCipher
import org.slf4j.LoggerFactory

/**
 * Translates a watch's persisted trigger intent into concrete notification
 * targets, resolving the Slack destination from **owner-controlled sources
 * only**. This is the privacy boundary for watch alert cards: a card carries
 * Pause/Resume/Delete buttons and the Slack interactivity port applies those
 * mutations with no per-click owner check, so isolation must come from *where*
 * the card is delivered. Each owner's cards land only in a channel that owner
 * controls; a different user never sees the card, so can't click it.
 *
 * A Slack target is produced **only when the owner has BOTH a resolved channel
 * AND a personal token**, so the card is delivered via the owner's own token into
 * a space that owner controls — never via the shared global bot to a user-named
 * channel (which a non-owner could read and click). If either is missing, no Slack
 * target is produced: Slack alerts simply don't fire for that watch (email still
 * does). That is the safe, fail-closed default — silence beats an actionable card
 * a stranger can act on.
 *
 * Slack channel priority:
 *  1. the watch's own `trigger_config.slack.channel` override
 *     ([AvailabilityWatchRepo.Watch.channelOverride]),
 *  2. else the OWNER's `user_settings.slack_channel`.
 *
 * Slack token: the owner's decrypted `user_settings.slack_token_cipher`
 * ([SecretCipher.open]). Required — a null token (no stored token, an
 * undecryptable blob, or [cipher] null because the encryption key is not
 * configured) means no Slack target, never a global-bot fallback. Decryption
 * failure degrades that one watch to no-Slack rather than throwing.
 *
 * Two entry points keep opt-in intact while ATC stays owner-scoped:
 *  - [resolve] is the general notify path: it emits the Slack target ONLY when the
 *    watch opted into `slack_notify`, and the Email target only when it opted into
 *    `email_notify`. So an email-only watch never gets an unsolicited Slack card,
 *    even if the owner happens to have a channel configured.
 *  - [resolveSlackTarget] is the raw owner-scoped Slack resolution, with no
 *    trigger-kind gate. ATC uses it directly, because ATC is Slack-only yet carries
 *    the `atc` kind rather than `slack_notify`. Both entry points share the same
 *    channel/token logic, so the no-shared-default-fallback property lives in one place.
 */
internal class WatchNotificationTargetResolver(
    private val userSettingsRepo: UserSettingsRepo,
    private val cipher: SecretCipher?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(watch: AvailabilityWatchRepo.Watch): List<NotificationTarget> =
        buildList {
            if (AvailabilityTriggerKinds.SLACK_NOTIFY in watch.triggerKinds) {
                resolveSlackTarget(watch)?.let(::add)
            }
            if (AvailabilityTriggerKinds.EMAIL_NOTIFY in watch.triggerKinds) {
                add(NotificationTarget.Email(recipients = watch.emailRecipients()))
            }
        }

    /**
     * The owner-scoped Slack target, or null when the security invariant cannot be
     * met. A Slack target is produced ONLY when the owner has BOTH a resolved
     * channel AND a personal token, so the card is delivered via the owner's own
     * token into a space the owner controls — never via the shared global bot to a
     * user-named channel.
     *
     * Channel = the watch's own override, else the OWNER's stored `slack_channel`;
     * when neither yields a channel this returns null. Token = the owner's decrypted
     * `slack_token_cipher` when present; when absent or undecryptable this also
     * returns null. The leak-closure: if either channel OR token is missing, no
     * Slack card is emitted (email still fires when enabled).
     *
     * Trigger-kind-agnostic: callers decide when Slack applies ([resolve] gates on
     * `slack_notify`; ATC calls this directly regardless of kind).
     */
    fun resolveSlackTarget(watch: AvailabilityWatchRepo.Watch): NotificationTarget.Slack? {
        val ownerSettings = userSettingsRepo.find(UserId(watch.ownerUserId))
        val channel = watch.channelOverride() ?: ownerSettings?.slackChannel ?: return null
        val token =
            ownerSettings?.slackTokenCipher?.let { blob ->
                runCatching { cipher?.open(blob) }
                    .onFailure {
                        log.warn("Failed to decrypt Slack token for owner user_id={}: {}", watch.ownerUserId, it.message)
                    }.getOrNull()
            } ?: return null
        return NotificationTarget.Slack(channel = channel, token = token)
    }
}
