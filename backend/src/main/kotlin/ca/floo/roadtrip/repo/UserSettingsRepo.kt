package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.UserSettings.Companion.USER_SETTINGS
import ca.floo.roadtrip.model.domain.auth.UserId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime

/** Persistence for `user_settings`. Stores the Slack token only as ciphertext. */
open class UserSettingsRepo(
    private val ctx: DSLContext,
) {
    data class Settings(
        val notificationEmail: String?,
        val slackChannel: String?,
        val slackTokenCipher: ByteArray?,
        val slackTokenHint: String?,
        val recgovUsername: String? = null,
        val recgovPasswordCipher: ByteArray? = null,
    )

    open fun find(userId: UserId): Settings? =
        ctx
            .select(
                USER_SETTINGS.NOTIFICATION_EMAIL,
                USER_SETTINGS.SLACK_CHANNEL,
                USER_SETTINGS.SLACK_TOKEN_CIPHER,
                USER_SETTINGS.SLACK_TOKEN_HINT,
                USER_SETTINGS.RECGOV_USERNAME,
                USER_SETTINGS.RECGOV_PASSWORD_CIPHER,
            ).from(USER_SETTINGS)
            .where(USER_SETTINGS.USER_ID.eq(userId.value))
            .fetchOne()
            ?.let {
                Settings(
                    it[USER_SETTINGS.NOTIFICATION_EMAIL],
                    it[USER_SETTINGS.SLACK_CHANNEL],
                    it[USER_SETTINGS.SLACK_TOKEN_CIPHER],
                    it[USER_SETTINGS.SLACK_TOKEN_HINT],
                    it[USER_SETTINGS.RECGOV_USERNAME],
                    it[USER_SETTINGS.RECGOV_PASSWORD_CIPHER],
                )
            }

    open fun upsertNotifications(
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

    open fun setSlackToken(
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

    /**
     * Atomically upserts notification preferences and — when [slackTokenCipher] and
     * [slackTokenHint] are both non-null — the Slack token, all inside one jOOQ
     * transaction. When the token args are null the token columns are left untouched,
     * preserving any previously-stored token (same semantics as [upsertNotifications]).
     */
    open fun saveNotifications(
        userId: UserId,
        notificationEmail: String?,
        slackChannel: String?,
        slackTokenCipher: ByteArray?,
        slackTokenHint: String?,
    ) {
        ctx.transaction { config ->
            val tx = DSL.using(config)
            val now = OffsetDateTime.now()

            // Upsert notification email + channel
            tx
                .insertInto(USER_SETTINGS)
                .set(USER_SETTINGS.USER_ID, userId.value)
                .set(USER_SETTINGS.NOTIFICATION_EMAIL, notificationEmail)
                .set(USER_SETTINGS.SLACK_CHANNEL, slackChannel)
                .set(USER_SETTINGS.UPDATED_AT, now)
                .onConflict(USER_SETTINGS.USER_ID)
                .doUpdate()
                .set(USER_SETTINGS.NOTIFICATION_EMAIL, notificationEmail)
                .set(USER_SETTINGS.SLACK_CHANNEL, slackChannel)
                .set(USER_SETTINGS.UPDATED_AT, now)
                .execute()

            // Optionally set the token within the same transaction
            if (slackTokenCipher != null && slackTokenHint != null) {
                tx
                    .update(USER_SETTINGS)
                    .set(USER_SETTINGS.SLACK_TOKEN_CIPHER, slackTokenCipher)
                    .set(USER_SETTINGS.SLACK_TOKEN_HINT, slackTokenHint)
                    .set(USER_SETTINGS.UPDATED_AT, now)
                    .where(USER_SETTINGS.USER_ID.eq(userId.value))
                    .execute()
            }
        }
    }

    /**
     * Upserts the rec.gov username and — when [passwordCipher] is non-null — the
     * sealed password, in one statement.
     *
     * A null [passwordCipher] means "leave the stored password untouched", the
     * write-only `SecretField` contract the Slack token already follows. Unlike
     * that token there is no hint column: see V53 for why a human password's
     * last 4 characters are credential material, not a display aid.
     */
    open fun saveRecgovCredentials(
        userId: UserId,
        username: String,
        passwordCipher: ByteArray?,
    ) {
        val now = OffsetDateTime.now()
        val insert =
            ctx
                .insertInto(USER_SETTINGS)
                .set(USER_SETTINGS.USER_ID, userId.value)
                .set(USER_SETTINGS.RECGOV_USERNAME, username)
                .set(USER_SETTINGS.RECGOV_PASSWORD_CIPHER, passwordCipher)
                .set(USER_SETTINGS.UPDATED_AT, now)
                .onConflict(USER_SETTINGS.USER_ID)
                .doUpdate()
                .set(USER_SETTINGS.RECGOV_USERNAME, username)
                .set(USER_SETTINGS.UPDATED_AT, now)
        if (passwordCipher != null) {
            insert.set(USER_SETTINGS.RECGOV_PASSWORD_CIPHER, passwordCipher)
        }
        insert.execute()
    }

    open fun clearRecgov(userId: UserId) {
        ctx
            .update(USER_SETTINGS)
            .setNull(USER_SETTINGS.RECGOV_USERNAME)
            .setNull(USER_SETTINGS.RECGOV_PASSWORD_CIPHER)
            .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now())
            .where(USER_SETTINGS.USER_ID.eq(userId.value))
            .execute()
    }

    open fun clearSlack(userId: UserId) {
        ctx
            .update(USER_SETTINGS)
            .setNull(USER_SETTINGS.SLACK_TOKEN_CIPHER)
            .setNull(USER_SETTINGS.SLACK_TOKEN_HINT)
            .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now())
            .where(USER_SETTINGS.USER_ID.eq(userId.value))
            .execute()
    }
}
