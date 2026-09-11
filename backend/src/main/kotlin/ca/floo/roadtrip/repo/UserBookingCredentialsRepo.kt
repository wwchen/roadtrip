package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.UserBookingCredentials.Companion.USER_BOOKING_CREDENTIALS
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import org.jooq.DSLContext
import java.time.OffsetDateTime

/**
 * One provider account as it is stored: an identifier in the clear and a sealed
 * secret. Opening the secret is the credential service's job, not this repo's.
 */
class BookingCredentials(
    val username: String,
    val secretCipher: ByteArray,
) {
    /** Value equality: the array's contents, not its identity. */
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is BookingCredentials && username == other.username && secretCipher.contentEquals(other.secretCipher))

    override fun hashCode(): Int = HASH_SEED * username.hashCode() + secretCipher.contentHashCode()

    /** Never prints the cipher: one log line is all a leak would take. */
    override fun toString(): String = "BookingCredentials(username=$username, secretCipher=<sealed>)"

    private companion object {
        const val HASH_SEED = 31
    }
}

/**
 * Persistence for `user_booking_credentials` — one row per user per booking
 * provider. Every method is keyed by provider, so no vendor is privileged here.
 */
open class UserBookingCredentialsRepo(
    private val ctx: DSLContext,
) {
    open fun find(
        user: UserId,
        provider: BookingProvider,
    ): BookingCredentials? =
        ctx
            .select(USER_BOOKING_CREDENTIALS.USERNAME, USER_BOOKING_CREDENTIALS.SECRET_CIPHER)
            .from(USER_BOOKING_CREDENTIALS)
            .where(USER_BOOKING_CREDENTIALS.USER_ID.eq(user.value))
            .and(USER_BOOKING_CREDENTIALS.PROVIDER.eq(provider.id))
            .fetchOne()
            ?.let {
                BookingCredentials(
                    username = it.get(USER_BOOKING_CREDENTIALS.USERNAME)!!,
                    secretCipher = it.get(USER_BOOKING_CREDENTIALS.SECRET_CIPHER)!!,
                )
            }

    /**
     * Stores the account for one provider, replacing whatever was there.
     *
     * Both halves are required: a username with no sealed secret cannot log in,
     * so "partially configured" is not a state this table can hold. A caller
     * changing only the username uses [updateUsername] instead.
     */
    open fun save(
        user: UserId,
        provider: BookingProvider,
        username: String,
        secretCipher: ByteArray,
    ) {
        val now = OffsetDateTime.now()
        ctx
            .insertInto(USER_BOOKING_CREDENTIALS)
            .set(USER_BOOKING_CREDENTIALS.USER_ID, user.value)
            .set(USER_BOOKING_CREDENTIALS.PROVIDER, provider.id)
            .set(USER_BOOKING_CREDENTIALS.USERNAME, username)
            .set(USER_BOOKING_CREDENTIALS.SECRET_CIPHER, secretCipher)
            .set(USER_BOOKING_CREDENTIALS.UPDATED_AT, now)
            .onConflict(USER_BOOKING_CREDENTIALS.USER_ID, USER_BOOKING_CREDENTIALS.PROVIDER)
            .doUpdate()
            .set(USER_BOOKING_CREDENTIALS.USERNAME, username)
            .set(USER_BOOKING_CREDENTIALS.SECRET_CIPHER, secretCipher)
            .set(USER_BOOKING_CREDENTIALS.UPDATED_AT, now)
            .execute()
    }

    /**
     * Renames the stored account in place, leaving the sealed secret untouched.
     * False when there was no row: an upsert would resurrect one removed since
     * the caller read it.
     */
    open fun updateUsername(
        user: UserId,
        provider: BookingProvider,
        username: String,
    ): Boolean =
        ctx
            .update(USER_BOOKING_CREDENTIALS)
            .set(USER_BOOKING_CREDENTIALS.USERNAME, username)
            .set(USER_BOOKING_CREDENTIALS.UPDATED_AT, OffsetDateTime.now())
            .where(USER_BOOKING_CREDENTIALS.USER_ID.eq(user.value))
            .and(USER_BOOKING_CREDENTIALS.PROVIDER.eq(provider.id))
            .execute() > 0

    /** True when a row was removed, so the caller can tell a wipe from a no-op. */
    open fun clear(
        user: UserId,
        provider: BookingProvider,
    ): Boolean =
        ctx
            .deleteFrom(USER_BOOKING_CREDENTIALS)
            .where(USER_BOOKING_CREDENTIALS.USER_ID.eq(user.value))
            .and(USER_BOOKING_CREDENTIALS.PROVIDER.eq(provider.id))
            .execute() > 0

    /** Everyone holding an account with [provider], for the keep-warm sweeps. */
    open fun userIdsWithCredentials(provider: BookingProvider): List<UserId> =
        ctx
            .select(USER_BOOKING_CREDENTIALS.USER_ID)
            .from(USER_BOOKING_CREDENTIALS)
            .where(USER_BOOKING_CREDENTIALS.PROVIDER.eq(provider.id))
            .fetch(USER_BOOKING_CREDENTIALS.USER_ID)
            .filterNotNull()
            .map(::UserId)
}
