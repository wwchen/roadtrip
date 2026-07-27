package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.UserSettings.Companion.USER_SETTINGS
import ca.floo.roadtrip.model.domain.auth.UserId
import org.jooq.DSLContext
import java.time.OffsetDateTime

/** Persistence for `user_settings`. Stores the Slack token only as ciphertext. */
class UserSettingsRepo(
    private val ctx: DSLContext,
) {
    data class Settings(
        val notificationEmail: String?,
        val slackChannel: String?,
        val slackTokenCipher: ByteArray?,
        val slackTokenHint: String?,
    )

    fun find(userId: UserId): Settings? =
        ctx
            .select(
                USER_SETTINGS.NOTIFICATION_EMAIL,
                USER_SETTINGS.SLACK_CHANNEL,
                USER_SETTINGS.SLACK_TOKEN_CIPHER,
                USER_SETTINGS.SLACK_TOKEN_HINT,
            ).from(USER_SETTINGS)
            .where(USER_SETTINGS.USER_ID.eq(userId.value))
            .fetchOne()
            ?.let {
                Settings(
                    it[USER_SETTINGS.NOTIFICATION_EMAIL],
                    it[USER_SETTINGS.SLACK_CHANNEL],
                    it[USER_SETTINGS.SLACK_TOKEN_CIPHER],
                    it[USER_SETTINGS.SLACK_TOKEN_HINT],
                )
            }

    fun upsertNotifications(
        userId: UserId,
        notificationEmail: String?,
        slackChannel: String?,
    ) {
        ctx
            .insertInto(USER_SETTINGS)
            .set(USER_SETTINGS.USER_ID, userId.value)
            .set(USER_SETTINGS.NOTIFICATION_EMAIL, notificationEmail)
            .set(USER_SETTINGS.SLACK_CHANNEL, slackChannel)
            .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now())
            .onConflict(USER_SETTINGS.USER_ID)
            .doUpdate()
            .set(USER_SETTINGS.NOTIFICATION_EMAIL, notificationEmail)
            .set(USER_SETTINGS.SLACK_CHANNEL, slackChannel)
            .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now())
            .execute()
    }

    fun setSlackToken(
        userId: UserId,
        cipher: ByteArray,
        hint: String,
    ) {
        ctx
            .insertInto(USER_SETTINGS)
            .set(USER_SETTINGS.USER_ID, userId.value)
            .set(USER_SETTINGS.SLACK_TOKEN_CIPHER, cipher)
            .set(USER_SETTINGS.SLACK_TOKEN_HINT, hint)
            .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now())
            .onConflict(USER_SETTINGS.USER_ID)
            .doUpdate()
            .set(USER_SETTINGS.SLACK_TOKEN_CIPHER, cipher)
            .set(USER_SETTINGS.SLACK_TOKEN_HINT, hint)
            .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now())
            .execute()
    }

    fun clearSlack(userId: UserId) {
        ctx
            .update(USER_SETTINGS)
            .setNull(USER_SETTINGS.SLACK_TOKEN_CIPHER)
            .setNull(USER_SETTINGS.SLACK_TOKEN_HINT)
            .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now())
            .where(USER_SETTINGS.USER_ID.eq(userId.value))
            .execute()
    }
}
