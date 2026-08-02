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
        // Owner also has a stored channel — the watch's own override must win.
        UserSettingsRepo(ctx).upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")

        val targets =
            resolver().resolve(
                watch(owner, triggerConfig = JsonObject(mapOf("channel" to JsonPrimitive("#watch-override")))),
            )

        assertEquals(listOf(NotificationTarget.Slack(channel = "#watch-override")), slackTargets(targets))
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
        UserSettingsRepo(ctx).upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")

        val targets = resolver().resolve(watch(owner, triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY)))

        assertEquals(listOf(NotificationTarget.Slack(channel = "#owner-channel")), slackTargets(targets))
    }

    @Test
    fun `resolveSlackTarget resolves owner channel regardless of trigger kind - ATC path`() {
        // ATC carries the `atc` kind, not slack_notify, yet must still notify Slack.
        // resolveSlackTarget is not kind-gated; the channel gate still applies.
        val owner = seedOwner()
        UserSettingsRepo(ctx).upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")

        val slack = resolver().resolveSlackTarget(watch(owner, triggerKinds = listOf(AvailabilityTriggerKinds.ATC)))

        assertEquals(NotificationTarget.Slack(channel = "#owner-channel"), slack)
    }

    @Test
    fun `resolveSlackTarget returns null when the owner has no channel - leak closure`() {
        val owner = seedOwner()

        val slack = resolver().resolveSlackTarget(watch(owner, triggerKinds = listOf(AvailabilityTriggerKinds.ATC)))

        assertNull(slack)
    }

    @Test
    fun `null cipher yields a null token even when a token blob is stored`() {
        val owner = seedOwner()
        val repo = UserSettingsRepo(ctx)
        repo.upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")
        repo.setSlackToken(owner, cipher = testCipher.seal("xoxb-owner-token"), hint = "oken")

        // A resolver with no cipher cannot decrypt: token is null, no crash, and
        // the channel still routes to the owner (global bot token is fine).
        val targets = resolver(cipher = null).resolve(watch(owner))

        val slack = slackTargets(targets).single()
        assertEquals("#owner-channel", slack.channel)
        assertNull(slack.token)
    }

    @Test
    fun `undecryptable token blob degrades to null token without throwing`() {
        val owner = seedOwner()
        val repo = UserSettingsRepo(ctx)
        repo.upsertNotifications(owner, notificationEmail = null, slackChannel = "#owner-channel")
        // Seed a blob that's encrypted with a DIFFERENT key than the resolver's cipher.
        val wrongCipher = SecretCipher(ByteArray(32) { (it + 1).toByte() })
        repo.setSlackToken(owner, cipher = wrongCipher.seal("xoxb-owner-token"), hint = "oken")

        // The resolver's testCipher cannot decrypt the blob sealed with wrongCipher.
        // It must degrade to token=null without throwing, so the watch alert still
        // fires via the owner's channel with the global bot token.
        val targets = resolver(cipher = testCipher).resolve(watch(owner))

        val slack = slackTargets(targets).single()
        assertEquals("#owner-channel", slack.channel)
        assertNull(slack.token, "undecryptable blob must yield token=null, not throw")
    }

    private fun emailTo(to: String): JsonObject = JsonObject(mapOf("to" to JsonPrimitive(to)))
}
