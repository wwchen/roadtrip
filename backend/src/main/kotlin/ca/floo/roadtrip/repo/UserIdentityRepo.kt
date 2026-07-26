package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.UserIdentity.Companion.USER_IDENTITY
import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.UserId
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime

/**
 * Persistence for `user_identity` — the mapping from a provider's notion of a
 * person to ours.
 *
 * Two lookup keys, in priority order:
 *
 *  1. [findByProviderSubject] — the provider we are currently configured
 *     against. The normal sign-in path.
 *  2. [findByUpstreamSubject] — the IdP behind the provider (the Google or Apple
 *     account). Only useful when swapping vendors, where the aggregator's `sub`
 *     has changed but the upstream account has not. This is the reason the
 *     upstream columns are populated from day one.
 *
 * Matching on email is deliberately *not* offered here. It is a linking policy
 * decision that depends on whether the provider asserted verification, and
 * policy belongs to the provisioning service, not to persistence.
 */
class UserIdentityRepo(
    private val ctx: DSLContext,
) {
    data class Identity(
        val id: Long,
        val userId: UserId,
        val provider: String,
        val subject: String,
        val upstreamProvider: String?,
        val upstreamSubject: String?,
        val emailVerifiedAt: OffsetDateTime?,
        val createdAt: OffsetDateTime,
    )

    fun findByProviderSubject(
        provider: String,
        subject: String,
    ): Identity? =
        ctx
            .select(USER_IDENTITY.fields().toList())
            .from(USER_IDENTITY)
            .where(USER_IDENTITY.PROVIDER.eq(provider))
            .and(USER_IDENTITY.SUBJECT.eq(subject))
            .fetchOne()
            ?.let(::fromRecord)

    /**
     * Finds an identity by the upstream IdP account. Used when re-linking after a
     * provider swap; returns null when the upstream subject was never recorded.
     */
    fun findByUpstreamSubject(
        upstreamProvider: String,
        upstreamSubject: String,
    ): Identity? =
        ctx
            .select(USER_IDENTITY.fields().toList())
            .from(USER_IDENTITY)
            .where(USER_IDENTITY.UPSTREAM_PROVIDER.eq(upstreamProvider))
            .and(USER_IDENTITY.UPSTREAM_SUBJECT.eq(upstreamSubject))
            .fetchOne()
            ?.let(::fromRecord)

    fun listForUser(id: UserId): List<Identity> =
        ctx
            .select(USER_IDENTITY.fields().toList())
            .from(USER_IDENTITY)
            .where(USER_IDENTITY.USER_ID.eq(id.value))
            .orderBy(USER_IDENTITY.CREATED_AT.asc())
            .fetch()
            .map(::fromRecord)

    /**
     * Attaches [claims] to [id] as a new identity. The caller owns the decision
     * that this identity may attach to this user — see the class comment.
     */
    fun link(
        id: UserId,
        provider: String,
        claims: IdentityClaims,
    ): Identity {
        val identityId =
            ctx
                .insertInto(USER_IDENTITY)
                .set(USER_IDENTITY.USER_ID, id.value)
                .set(USER_IDENTITY.PROVIDER, provider)
                .set(USER_IDENTITY.SUBJECT, claims.subject)
                .set(USER_IDENTITY.UPSTREAM_PROVIDER, claims.upstreamProvider)
                .set(USER_IDENTITY.UPSTREAM_SUBJECT, claims.upstreamSubject)
                .set(
                    USER_IDENTITY.EMAIL_VERIFIED_AT,
                    OffsetDateTime.now().takeIf { claims.isEmailVerified },
                ).returningResult(USER_IDENTITY.ID)
                .fetchOne()!!
                .value1()!!
        return findById(identityId)!!
    }

    /**
     * Refreshes the mutable facts an existing identity carries. Providers can
     * start exposing upstream identity, or assert verification on a later
     * sign-in, and the row should catch up.
     *
     * Verification is one-way: once `email_verified_at` is set it is never
     * cleared, so an unverified sign-in cannot strip an earned verification.
     */
    fun refresh(
        identityId: Long,
        claims: IdentityClaims,
    ): Identity? {
        var update =
            ctx
                .update(USER_IDENTITY)
                .set(USER_IDENTITY.UPSTREAM_PROVIDER, claims.upstreamProvider)
                .set(USER_IDENTITY.UPSTREAM_SUBJECT, claims.upstreamSubject)
        if (claims.isEmailVerified) {
            update = update.set(USER_IDENTITY.EMAIL_VERIFIED_AT, OffsetDateTime.now())
        }
        update
            .where(USER_IDENTITY.ID.eq(identityId))
            .execute()
        return findById(identityId)
    }

    fun deleteForUser(id: UserId): Int =
        ctx
            .deleteFrom(USER_IDENTITY)
            .where(USER_IDENTITY.USER_ID.eq(id.value))
            .execute()

    private fun findById(identityId: Long): Identity? =
        ctx
            .select(USER_IDENTITY.fields().toList())
            .from(USER_IDENTITY)
            .where(USER_IDENTITY.ID.eq(identityId))
            .fetchOne()
            ?.let(::fromRecord)

    private fun fromRecord(record: Record): Identity =
        Identity(
            id = record.get(USER_IDENTITY.ID)!!,
            userId = UserId(record.get(USER_IDENTITY.USER_ID)!!),
            provider = record.get(USER_IDENTITY.PROVIDER)!!,
            subject = record.get(USER_IDENTITY.SUBJECT)!!,
            upstreamProvider = record.get(USER_IDENTITY.UPSTREAM_PROVIDER),
            upstreamSubject = record.get(USER_IDENTITY.UPSTREAM_SUBJECT),
            emailVerifiedAt = record.get(USER_IDENTITY.EMAIL_VERIFIED_AT),
            createdAt = record.get(USER_IDENTITY.CREATED_AT)!!,
        )
}
