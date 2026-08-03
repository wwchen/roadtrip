package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.UserIdentityRepo
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.support.AuthException
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

/**
 * Turns verified [IdentityClaims] into a local user, creating or linking as
 * needed. This is where account-linking *policy* lives; the repos only store
 * what they are told.
 *
 * A returning identity (same provider, same subject) short-circuits. Otherwise
 * the claims are used to find the owning user, in descending order of trust,
 * and the new identity is linked to whatever that search found:
 *
 *  1. **(upstreamProvider, upstreamSubject)** — same Google or Apple account,
 *     different aggregator record. This is the vendor-swap path: after a
 *     migration the new provider issues a different `sub`, but the upstream
 *     account id is unchanged. The new provider's identity is linked alongside
 *     the old one, so the swap converges after a single sign-in instead of
 *     resolving through the upstream key forever.
 *  2. **Verified email** — last resort, and *only* when the provider asserted
 *     verification.
 *  3. Otherwise a new user.
 *
 * Step 2's condition is the security-critical one. Linking on an unverified
 * address lets anyone who can sign up with a victim's email inherit that
 * victim's account and everything it owns. An unverified claim on an address
 * that already belongs to someone is therefore refused outright — see
 * [createUser]. Creating a shadow account would be the other option, but two
 * accounts for one address is its own support burden and invites exactly the
 * confusion the attack relies on.
 */
class UserProvisioningService(
    private val ctx: DSLContext,
    private val roleGrants: Map<Role, Set<String>> = emptyMap(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param provider the configured provider slug, stored as
     *        `user_identity.provider` so a later swap can tell old rows apart.
     * @throws AuthException when the provider returned no email address, or when
     *         an unverified identity claims an address that already belongs to
     *         an account.
     */
    fun provision(
        provider: String,
        claims: IdentityClaims,
    ): UserId =
        ctx.transactionResult { config ->
            val txn = config.dsl()
            val userRepo = UserRepo(txn)
            val userIdentityRepo = UserIdentityRepo(txn)

            // The common path: this identity has signed in before.
            val returning = userIdentityRepo.findByProviderSubject(provider, claims.subject)
            val userId =
                if (returning != null) {
                    userIdentityRepo.refresh(returning.id, claims)
                    if (claims.isEmailVerified) userRepo.markEmailVerified(returning.userId)
                    returning.userId
                } else {
                    val email =
                        claims.email
                            ?: throw AuthException("identity ${claims.subject} from '$provider' carries no email address")

                    val resolved =
                        userByUpstreamIdentity(userIdentityRepo, provider, claims)
                            ?: userByVerifiedEmail(userRepo, claims, email)
                            ?: createUser(userRepo, claims, email)

                    userIdentityRepo.link(resolved, provider, claims)
                    resolved
                }

            grantConfiguredRoles(userRepo, claims, userId)
            userId
        }

    /** Step 1: the same upstream IdP account, reached through another provider. */
    private fun userByUpstreamIdentity(
        userIdentityRepo: UserIdentityRepo,
        provider: String,
        claims: IdentityClaims,
    ): UserId? {
        val upstreamProvider = claims.upstreamProvider ?: return null
        val upstreamSubject = claims.upstreamSubject ?: return null
        val existing = userIdentityRepo.findByUpstreamSubject(upstreamProvider, upstreamSubject) ?: return null

        log.info(
            "linking a '{}' identity to user_id={} by upstream {}:{} — previously seen via provider '{}'",
            provider,
            existing.userId.value,
            upstreamProvider,
            upstreamSubject,
            existing.provider,
        )
        return existing.userId
    }

    /** Step 2: an existing account this *verified* address may safely join. */
    private fun userByVerifiedEmail(
        userRepo: UserRepo,
        claims: IdentityClaims,
        email: String,
    ): UserId? {
        if (!claims.isEmailVerified) return null
        val existing = userRepo.findByEmail(email) ?: return null
        userRepo.markEmailVerified(existing.id)
        log.info("linking identity {} to existing user_id={} by verified email", claims.subject, existing.id.value)
        return existing.id
    }

    /**
     * Step 3. Refuses when the address is already taken — only reachable when
     * this identity's email is unverified, because a verified one would have
     * linked in step 2. Letting it through would either merge an unproven
     * identity into someone else's account or mint a duplicate account for one
     * address; neither is acceptable. The user's remedy is to verify the address
     * with their provider and sign in again.
     */
    private fun createUser(
        userRepo: UserRepo,
        claims: IdentityClaims,
        email: String,
    ): UserId {
        userRepo.findByEmail(email)?.let { existing ->
            log.warn(
                "refusing identity {}: unverified email claims an address already held by user_id={}",
                claims.subject,
                existing.id.value,
            )
            throw AuthException("unverified identity ${claims.subject} claims an address that already has an account")
        }
        return userRepo
            .create(
                email = email,
                displayName = claims.displayName,
                isEmailVerified = claims.isEmailVerified,
            ).id
    }

    /**
     * Grants every role whose allowlist contains this identity's verified email.
     * Grant-only and idempotent (see [UserRepo.grantRole]); an unverified email
     * grants nothing, mirroring the linking rule enforced elsewhere here.
     */
    private fun grantConfiguredRoles(
        userRepo: UserRepo,
        claims: IdentityClaims,
        userId: UserId,
    ) {
        if (!claims.isEmailVerified) return
        val email = claims.email?.lowercase() ?: return
        roleGrants.forEach { (role, emails) ->
            if (email in emails && userRepo.grantRole(userId, role)) {
                log.info("granted {} to user_id={} via role-emails allowlist", role, userId.value)
            }
        }
    }
}
