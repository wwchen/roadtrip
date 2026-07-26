package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.UserSession.Companion.USER_SESSION
import ca.floo.roadtrip.model.domain.auth.UserId
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime

/**
 * Persistence for `user_session` — first-party sessions.
 *
 * The repo only ever sees a token *hash*. Hashing is the caller's job so the
 * plaintext token exists in exactly one place (the service that mints it and
 * writes the cookie) and never reaches persistence, a log, or a query plan.
 *
 * "Active" means: not revoked, and not expired as of an explicitly passed clock
 * value. The clock is a parameter rather than `now()` inside the query so tests
 * can drive expiry deterministically.
 */
class UserSessionRepo(
    private val ctx: DSLContext,
) {
    data class Session(
        val id: Long,
        val userId: UserId,
        val expiresAt: OffsetDateTime,
        val revokedAt: OffsetDateTime?,
        val createdAt: OffsetDateTime,
    )

    fun create(
        id: UserId,
        tokenHash: ByteArray,
        expiresAt: OffsetDateTime,
    ): Session {
        val sessionId =
            ctx
                .insertInto(USER_SESSION)
                .set(USER_SESSION.USER_ID, id.value)
                .set(USER_SESSION.TOKEN_HASH, tokenHash)
                .set(USER_SESSION.EXPIRES_AT, expiresAt)
                .returningResult(USER_SESSION.ID)
                .fetchOne()!!
                .value1()!!
        return findById(sessionId)!!
    }

    /** The session-resolution read. Returns null when absent, revoked, or expired. */
    fun findActiveByTokenHash(
        tokenHash: ByteArray,
        now: OffsetDateTime,
    ): Session? =
        ctx
            .select(USER_SESSION.fields().toList())
            .from(USER_SESSION)
            .where(USER_SESSION.TOKEN_HASH.eq(tokenHash))
            .and(USER_SESSION.REVOKED_AT.isNull)
            .and(USER_SESSION.EXPIRES_AT.gt(now))
            .fetchOne()
            ?.let(::fromRecord)

    /** Logout. Idempotent — revoking an already-revoked session is a no-op. */
    fun revokeByTokenHash(
        tokenHash: ByteArray,
        now: OffsetDateTime,
    ): Boolean =
        ctx
            .update(USER_SESSION)
            .set(USER_SESSION.REVOKED_AT, now)
            .where(USER_SESSION.TOKEN_HASH.eq(tokenHash))
            .and(USER_SESSION.REVOKED_AT.isNull)
            .execute() > 0

    /**
     * Revokes every live session for a user. The blunt instrument behind
     * "sign out everywhere", account disable, and credential change.
     */
    fun revokeAllForUser(
        id: UserId,
        now: OffsetDateTime,
    ): Int =
        ctx
            .update(USER_SESSION)
            .set(USER_SESSION.REVOKED_AT, now)
            .where(USER_SESSION.USER_ID.eq(id.value))
            .and(USER_SESSION.REVOKED_AT.isNull)
            .execute()

    fun listActiveForUser(
        id: UserId,
        now: OffsetDateTime,
    ): List<Session> =
        ctx
            .select(USER_SESSION.fields().toList())
            .from(USER_SESSION)
            .where(USER_SESSION.USER_ID.eq(id.value))
            .and(USER_SESSION.REVOKED_AT.isNull)
            .and(USER_SESSION.EXPIRES_AT.gt(now))
            .orderBy(USER_SESSION.CREATED_AT.desc())
            .fetch()
            .map(::fromRecord)

    /**
     * Drops rows that are past expiry, for a periodic sweep. Revoked-but-unexpired
     * rows are kept: they still have to answer "this token is dead" until the
     * moment expiry would have retired them anyway.
     */
    fun deleteExpired(now: OffsetDateTime): Int =
        ctx
            .deleteFrom(USER_SESSION)
            .where(USER_SESSION.EXPIRES_AT.le(now))
            .execute()

    private fun findById(sessionId: Long): Session? =
        ctx
            .select(USER_SESSION.fields().toList())
            .from(USER_SESSION)
            .where(USER_SESSION.ID.eq(sessionId))
            .fetchOne()
            ?.let(::fromRecord)

    private fun fromRecord(record: Record): Session =
        Session(
            id = record.get(USER_SESSION.ID)!!,
            userId = UserId(record.get(USER_SESSION.USER_ID)!!),
            expiresAt = record.get(USER_SESSION.EXPIRES_AT)!!,
            revokedAt = record.get(USER_SESSION.REVOKED_AT),
            createdAt = record.get(USER_SESSION.CREATED_AT)!!,
        )
}
