package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.security.SecretCipher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DB-backed test for the owner-scoped Slack routing that closes the cross-user
 * mutation hole. The resolver reads real `user_settings` rows through
 * [UserSettingsRepo]; the watch itself is an in-memory row carrying only the
 * fields the resolver reads (owner id, trigger config/kinds).
 */
class WatchNotificationTargetResolverTest : SharedDbTest() {
    private var userSeq = 0
    private val testCipher = SecretCipher(ByteArray(32) { it.toByte() })

    private fun seedOwner(): UserId =
        UserRepo(ctx)
            .create(email = "wntr-owner-${userSeq++}@example.com", displayName = null, isEmailVerified = true)
            .id

    private fun resolver(cipher: SecretCipher? = testCipher): WatchNotificationTargetResolver =
        WatchNotificationTargetResolver(userSettingsRepo = UserSettingsRepo(ctx), cipher = cipher)

    private fun watch(
        owner: UserId,
        triggerKinds: List<String> = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY),
        triggerConfig: JsonObject = JsonObject(emptyMap()),
    ): AvailabilityWatchRepo.Watch =
        AvailabilityWatchRepo.Watch(
            id = 1L,
            ownerUserId = owner.value,
            targets = emptyList<AvailabilityWatchTargetRepo.WatchTarget>(),
            campsiteFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-06"),
            cadenceSec = null,
            triggerKinds = triggerKinds,
            triggerConfig = triggerConfig,
            stopWhenTriggered = false,
            status = WatchStatus.ACTIVE,
            createdAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
        )

    private fun slackTargets(targets: List<NotificationTarget>) = targets.filterIsInstance<NotificationTarget.Slack>()

    @Test
    fun `watch channel override wins and is used as the slack channel`() {
        val owner = seedOwner()
        val repo = UserSettingsRepo(ctx)
        // Owner also has a stored channel — the watch's own override must win.
        repo.upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")
        // A personal token is now required to emit any Slack target.
        repo.setSlackToken(owner, cipher = testCipher.seal("xoxb-owner-token"), hint = "oken")

        val targets =
            resolver().resolve(
                watch(owner, triggerConfig = JsonObject(mapOf("channel" to JsonPrimitive("#watch-override")))),
            )

        assertEquals(
            listOf(NotificationTarget.Slack(channel = "#watch-override", token = "xoxb-owner-token")),
            slackTargets(targets),
        )
    }

    @Test
    fun `owner channel and token are used when the watch has no override`() {
        val owner = seedOwner()
        val repo = UserSettingsRepo(ctx)
        repo.upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")
        repo.setSlackToken(owner, cipher = testCipher.seal("xoxb-owner-token"), hint = "oken")

        val targets = resolver().resolve(watch(owner))

        assertEquals(
            listOf(NotificationTarget.Slack(channel = "#owner-channel", token = "xoxb-owner-token")),
            slackTargets(targets),
        )
    }

    @Test
    fun `no override and no owner channel emits no slack target - leak closure`() {
        // THE leak-closure assertion: with no watch override and no owner channel,
        // the resolver must NOT fall back to a shared default. No Slack target is
        // produced, so the card never reaches a channel a stranger can act on.
        val owner = seedOwner()

        val targets =
            resolver().resolve(
                watch(
                    owner,
                    triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.EMAIL_NOTIFY),
                    triggerConfig = JsonObject(mapOf(AvailabilityTriggerKinds.EMAIL_NOTIFY to emailTo("alerts@example.test"))),
                ),
            )

        assertTrue(slackTargets(targets).isEmpty(), "no owner-controlled channel must yield no Slack target")
        // Email still fires when configured.
        assertEquals(
            listOf(NotificationTarget.Email(listOf("alerts@example.test"))),
            targets.filterIsInstance<NotificationTarget.Email>(),
        )
    }

    @Test
    fun `email-only watch with an owner channel emits no slack target - opt-in preserved`() {
        // Regression closure: a watch that opted into email_notify only (no
        // slack_notify) must NOT get an unsolicited Slack card even when the owner
        // has a stored slack_channel. resolve() is kind-gated on slack_notify.
        val owner = seedOwner()
        UserSettingsRepo(ctx).upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")

        val targets =
            resolver().resolve(
                watch(
                    owner,
                    triggerKinds = listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY),
                    triggerConfig = JsonObject(mapOf(AvailabilityTriggerKinds.EMAIL_NOTIFY to emailTo("alerts@example.test"))),
                ),
            )

        assertTrue(slackTargets(targets).isEmpty(), "email-only watch must not produce a Slack card")
        assertEquals(
            listOf(NotificationTarget.Email(listOf("alerts@example.test"))),
            targets.filterIsInstance<NotificationTarget.Email>(),
        )
    }

    @Test
    fun `slack_notify watch with an owner channel still gets its slack card`() {
        // Guard against over-correcting: the opt-in path must still deliver.
        val owner = seedOwner()
        val repo = UserSettingsRepo(ctx)
        repo.upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")
        // A personal token is now required to emit any Slack target.
        repo.setSlackToken(owner, cipher = testCipher.seal("xoxb-owner-token"), hint = "oken")

        val targets = resolver().resolve(watch(owner, triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY)))

        assertEquals(
            listOf(NotificationTarget.Slack(channel = "#owner-channel", token = "xoxb-owner-token")),
            slackTargets(targets),
        )
    }

    @Test
    fun `resolveSlackTarget resolves owner channel regardless of trigger kind - ATC path`() {
        // ATC carries the `atc` kind, not slack_notify, yet must still notify Slack.
        // resolveSlackTarget is not kind-gated; the channel gate still applies.
        val owner = seedOwner()
        val repo = UserSettingsRepo(ctx)
        repo.upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")
        // A personal token is now required to emit any Slack target.
        repo.setSlackToken(owner, cipher = testCipher.seal("xoxb-owner-token"), hint = "oken")

        val slack = resolver().resolveSlackTarget(watch(owner, triggerKinds = listOf(AvailabilityTriggerKinds.ATC)))

        assertEquals(NotificationTarget.Slack(channel = "#owner-channel", token = "xoxb-owner-token"), slack)
    }

    @Test
    fun `resolveSlackTarget returns null when the owner has no channel - leak closure`() {
        val owner = seedOwner()

        val slack = resolver().resolveSlackTarget(watch(owner, triggerKinds = listOf(AvailabilityTriggerKinds.ATC)))

        assertNull(slack)
    }

    @Test
    fun `owner has channel but no token emits no slack target - security invariant`() {
        // Core security gate: even when a channel is configured (owner channel or
        // watch override), if the owner has NO personal token, NO Slack target is
        // produced. This prevents the card from being posted via the shared global
        // bot to a user-named channel a non-owner could act on. Email still fires
        // when enabled.
        val owner = seedOwner()
        UserSettingsRepo(ctx).upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")
        // No token set for the owner — token resolves to null.

        val targets =
            resolver().resolve(
                watch(
                    owner,
                    triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.EMAIL_NOTIFY),
                    triggerConfig = JsonObject(mapOf(AvailabilityTriggerKinds.EMAIL_NOTIFY to emailTo("alerts@example.test"))),
                ),
            )

        assertTrue(slackTargets(targets).isEmpty(), "channel without token must yield no Slack target")
        // Email still fires when configured.
        assertEquals(
            listOf(NotificationTarget.Email(listOf("alerts@example.test"))),
            targets.filterIsInstance<NotificationTarget.Email>(),
        )
    }

    @Test
    fun `watch override channel but owner has no token emits no slack target`() {
        // Variant of the core security gate: even when the WATCH specifies a channel
        // override (bypassing the owner's stored channel), if the owner has NO
        // personal token, NO Slack target is produced. The override alone is not
        // enough — the token is what confines delivery.
        val owner = seedOwner()
        // No channel or token set for the owner.

        val targets =
            resolver().resolve(
                watch(owner, triggerConfig = JsonObject(mapOf("channel" to JsonPrimitive("#watch-override")))),
            )

        assertTrue(slackTargets(targets).isEmpty(), "watch override channel without token must yield no Slack target")
    }

    @Test
    fun `null cipher yields no slack target when token cannot be decrypted`() {
        val owner = seedOwner()
        val repo = UserSettingsRepo(ctx)
        repo.upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")
        repo.setSlackToken(owner, cipher = testCipher.seal("xoxb-owner-token"), hint = "oken")

        // A resolver with no cipher cannot decrypt: token resolves to null, and
        // per the new security invariant (both channel AND token required), no
        // Slack target is produced. This prevents cards from being delivered via
        // the shared global bot to a user-named channel a non-owner could act on.
        val targets = resolver(cipher = null).resolve(watch(owner))

        assertTrue(slackTargets(targets).isEmpty(), "no decryptable token must yield no Slack target")
    }

    @Test
    fun `undecryptable token blob yields no slack target without throwing`() {
        val owner = seedOwner()
        val repo = UserSettingsRepo(ctx)
        repo.upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")
        // Seed a blob that's encrypted with a DIFFERENT key than the resolver's cipher.
        val wrongCipher = SecretCipher(ByteArray(32) { (it + 1).toByte() })
        repo.setSlackToken(owner, cipher = wrongCipher.seal("xoxb-owner-token"), hint = "oken")

        // The resolver's testCipher cannot decrypt the blob sealed with wrongCipher.
        // It degrades to token=null gracefully (no throw), and per the new security
        // invariant (both channel AND token required), no Slack target is produced.
        // This prevents cards from being delivered via the shared global bot to a
        // user-named channel a non-owner could act on.
        val targets = resolver(cipher = testCipher).resolve(watch(owner))

        assertTrue(slackTargets(targets).isEmpty(), "undecryptable blob must yield no Slack target (graceful, no throw)")
    }

    private fun emailTo(to: String): JsonObject = JsonObject(mapOf("to" to JsonPrimitive(to)))
}
