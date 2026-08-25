package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityWatchAccessToken.Companion.AVAILABILITY_WATCH_ACCESS_TOKEN
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime

/**
 * Persistence for `availability_watch_access_token` — the magic-link capability
 * tokens that alert emails carry.
 *
 * Same contract as [UserSessionRepo]: the repo only ever sees a token *hash*.
 * Hashing is the caller's job so the plaintext exists in exactly one place (the
 * service that mints it and hands it to the link builder) and never reaches
 * persistence, a log, or a query plan.
 *
 * "Active" means: not revoked, and not expired as of an explicitly passed clock
 * value. The clock is a parameter rather than `now()` inside the query so tests
 * can drive expiry deterministically.
 */
class WatchAccessTokenRepo(
    private val ctx: DSLContext,
) {
    data class WatchAccessToken(
        val id: Long,
        val watchId: Long,
        val expiresAt: OffsetDateTime,
        val revokedAt: OffsetDateTime?,
        val lastUsedAt: OffsetDateTime?,
        val createdAt: OffsetDateTime,
    )

    fun create(
        watchId: Long,
        tokenHash: ByteArray,
        expiresAt: OffsetDateTime,
    ): WatchAccessToken {
        val id =
            ctx
                .insertInto(AVAILABILITY_WATCH_ACCESS_TOKEN)
                .set(AVAILABILITY_WATCH_ACCESS_TOKEN.WATCH_ID, watchId)
                .set(AVAILABILITY_WATCH_ACCESS_TOKEN.TOKEN_HASH, tokenHash)
                .set(AVAILABILITY_WATCH_ACCESS_TOKEN.EXPIRES_AT, expiresAt)
                .returningResult(AVAILABILITY_WATCH_ACCESS_TOKEN.ID)
                .fetchOne()!!
                .value1()!!
        return findById(id)!!
    }

    /** The link-resolution read. Returns null when absent, revoked, or expired. */
    fun findActiveByTokenHash(
        tokenHash: ByteArray,
        now: OffsetDateTime,
    ): WatchAccessToken? =
        ctx
            .select(AVAILABILITY_WATCH_ACCESS_TOKEN.fields().toList())
            .from(AVAILABILITY_WATCH_ACCESS_TOKEN)
            .where(AVAILABILITY_WATCH_ACCESS_TOKEN.TOKEN_HASH.eq(tokenHash))
            .and(AVAILABILITY_WATCH_ACCESS_TOKEN.REVOKED_AT.isNull)
            .and(AVAILABILITY_WATCH_ACCESS_TOKEN.EXPIRES_AT.gt(now))
            .fetchOne()
            ?.let(::fromRecord)

    /** Records that a link was actually followed. Best-effort telemetry, not a gate. */
    fun touchLastUsed(
        id: Long,
        now: OffsetDateTime,
    ): Boolean =
        ctx
            .update(AVAILABILITY_WATCH_ACCESS_TOKEN)
            .set(AVAILABILITY_WATCH_ACCESS_TOKEN.LAST_USED_AT, now)
            .where(AVAILABILITY_WATCH_ACCESS_TOKEN.ID.eq(id))
            .execute() > 0

    /**
     * Kills every live link for one watch, without waiting for expiry.
     *
     * Deleting a watch already takes its tokens with it (ON DELETE CASCADE), so
     * this is the lever for the case where the watch stays but its mailed links
     * should not: an alert forwarded to the wrong inbox, or an operator killing
     * one user's links after a report.
     */
    fun revokeAllForWatch(
        watchId: Long,
        now: OffsetDateTime,
    ): Int =
        ctx
            .update(AVAILABILITY_WATCH_ACCESS_TOKEN)
            .set(AVAILABILITY_WATCH_ACCESS_TOKEN.REVOKED_AT, now)
            .where(AVAILABILITY_WATCH_ACCESS_TOKEN.WATCH_ID.eq(watchId))
            .and(AVAILABILITY_WATCH_ACCESS_TOKEN.REVOKED_AT.isNull)
            .execute()

    /**
     * Drops rows that are past expiry, for a periodic sweep. Revoked-but-unexpired
     * rows are kept: they still have to answer "this link is dead" until the moment
     * expiry would have retired them anyway.
     */
    fun deleteExpired(now: OffsetDateTime): Int =
        ctx
            .deleteFrom(AVAILABILITY_WATCH_ACCESS_TOKEN)
            .where(AVAILABILITY_WATCH_ACCESS_TOKEN.EXPIRES_AT.le(now))
            .execute()

    private fun findById(id: Long): WatchAccessToken? =
        ctx
            .select(AVAILABILITY_WATCH_ACCESS_TOKEN.fields().toList())
            .from(AVAILABILITY_WATCH_ACCESS_TOKEN)
            .where(AVAILABILITY_WATCH_ACCESS_TOKEN.ID.eq(id))
            .fetchOne()
            ?.let(::fromRecord)

    private fun fromRecord(record: Record): WatchAccessToken =
        WatchAccessToken(
            id = record.get(AVAILABILITY_WATCH_ACCESS_TOKEN.ID)!!,
            watchId = record.get(AVAILABILITY_WATCH_ACCESS_TOKEN.WATCH_ID)!!,
            expiresAt = record.get(AVAILABILITY_WATCH_ACCESS_TOKEN.EXPIRES_AT)!!,
            revokedAt = record.get(AVAILABILITY_WATCH_ACCESS_TOKEN.REVOKED_AT),
            lastUsedAt = record.get(AVAILABILITY_WATCH_ACCESS_TOKEN.LAST_USED_AT),
            createdAt = record.get(AVAILABILITY_WATCH_ACCESS_TOKEN.CREATED_AT)!!,
        )
}
