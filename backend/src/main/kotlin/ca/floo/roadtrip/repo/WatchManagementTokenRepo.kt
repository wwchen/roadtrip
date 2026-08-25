package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.WatchManagementToken.Companion.WATCH_MANAGEMENT_TOKEN
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime

/**
 * Persistence for `watch_management_token` — the per-watch magic-link tokens
 * embedded in alert emails so a recipient can pause/resume/delete that one
 * watch without signing in.
 *
 * Mirrors [UserSessionRepo]: the repo only ever sees a token hash, and
 * "active" means not revoked and not expired as of an explicitly passed
 * clock value.
 */
class WatchManagementTokenRepo(
    private val ctx: DSLContext,
) {
    data class ManagementToken(
        val id: Long,
        val watchId: Long,
        val expiresAt: OffsetDateTime,
        val revokedAt: OffsetDateTime?,
        val createdAt: OffsetDateTime,
    )

    fun create(
        watchId: Long,
        tokenHash: ByteArray,
        expiresAt: OffsetDateTime,
    ): ManagementToken {
        val id =
            ctx
                .insertInto(WATCH_MANAGEMENT_TOKEN)
                .set(WATCH_MANAGEMENT_TOKEN.WATCH_ID, watchId)
                .set(WATCH_MANAGEMENT_TOKEN.TOKEN_HASH, tokenHash)
                .set(WATCH_MANAGEMENT_TOKEN.EXPIRES_AT, expiresAt)
                .returningResult(WATCH_MANAGEMENT_TOKEN.ID)
                .fetchOne()!!
                .value1()!!
        return findById(id)!!
    }

    /** The token-resolution read. Returns null when absent, revoked, or expired. */
    fun findActiveByTokenHash(
        tokenHash: ByteArray,
        now: OffsetDateTime,
    ): ManagementToken? =
        ctx
            .select(WATCH_MANAGEMENT_TOKEN.fields().toList())
            .from(WATCH_MANAGEMENT_TOKEN)
            .where(WATCH_MANAGEMENT_TOKEN.TOKEN_HASH.eq(tokenHash))
            .and(WATCH_MANAGEMENT_TOKEN.REVOKED_AT.isNull)
            .and(WATCH_MANAGEMENT_TOKEN.EXPIRES_AT.gt(now))
            .fetchOne()
            ?.let(::fromRecord)

    /** Retires rows past expiry, for a periodic sweep. */
    fun deleteExpired(now: OffsetDateTime): Int =
        ctx
            .deleteFrom(WATCH_MANAGEMENT_TOKEN)
            .where(WATCH_MANAGEMENT_TOKEN.EXPIRES_AT.le(now))
            .execute()

    private fun findById(id: Long): ManagementToken? =
        ctx
            .select(WATCH_MANAGEMENT_TOKEN.fields().toList())
            .from(WATCH_MANAGEMENT_TOKEN)
            .where(WATCH_MANAGEMENT_TOKEN.ID.eq(id))
            .fetchOne()
            ?.let(::fromRecord)

    private fun fromRecord(record: Record): ManagementToken =
        ManagementToken(
            id = record.get(WATCH_MANAGEMENT_TOKEN.ID)!!,
            watchId = record.get(WATCH_MANAGEMENT_TOKEN.WATCH_ID)!!,
            expiresAt = record.get(WATCH_MANAGEMENT_TOKEN.EXPIRES_AT)!!,
            revokedAt = record.get(WATCH_MANAGEMENT_TOKEN.REVOKED_AT),
            createdAt = record.get(WATCH_MANAGEMENT_TOKEN.CREATED_AT)!!,
        )
}
