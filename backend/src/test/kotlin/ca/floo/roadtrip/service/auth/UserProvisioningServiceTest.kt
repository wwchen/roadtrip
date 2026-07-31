package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.UserIdentityRepo
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.support.AuthException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val AUTH0 = "auth0"
private const val WORKOS = "workos"

class UserProvisioningServiceTest : SharedDbTest() {
    private val provisioning by lazy { UserProvisioningService(ctx) }
    private val userRepo by lazy { UserRepo(ctx) }
    private val userIdentityRepo by lazy { UserIdentityRepo(ctx) }

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM app_user")
    }

    private fun claims(
        subject: String,
        email: String? = "user@example.com",
        isEmailVerified: Boolean = true,
        upstreamProvider: String? = "google",
        upstreamSubject: String? = "google-1",
        displayName: String? = "User",
    ) = IdentityClaims(
        subject = subject,
        email = email,
        isEmailVerified = isEmailVerified,
        displayName = displayName,
        upstreamProvider = upstreamProvider,
        upstreamSubject = upstreamSubject,
    )

    @Test
    fun `a new identity creates a user and links it`() {
        val userId = provisioning.provision(AUTH0, claims("auth0|new"))

        val user = assertNotNull(userRepo.findById(userId))
        assertEquals("user@example.com", user.email)
        assertTrue(user.isEmailVerified)
        assertEquals(listOf("auth0|new"), userIdentityRepo.listForUser(userId).map { it.subject })
    }

    @Test
    fun `the same identity signing in again resolves to the same user`() {
        val first = provisioning.provision(AUTH0, claims("auth0|repeat"))
        val second = provisioning.provision(AUTH0, claims("auth0|repeat"))

        assertEquals(first, second)
        assertEquals(1, userIdentityRepo.listForUser(first).size)
    }

    @Test
    fun `a vendor swap re-links by upstream subject rather than creating a user`() {
        val original = provisioning.provision(AUTH0, claims("auth0|abc", upstreamSubject = "google-42"))

        // Same human, same Google account, new aggregator issuing a new `sub`.
        val afterSwap =
            provisioning.provision(WORKOS, claims("user_01HXYZ", upstreamSubject = "google-42"))

        assertEquals(original, afterSwap)
        val identities = userIdentityRepo.listForUser(original)
        assertEquals(setOf(AUTH0, WORKOS), identities.map { it.provider }.toSet())
    }

    @Test
    fun `a verified email links to the existing account`() {
        val original = provisioning.provision(AUTH0, claims("auth0|first", upstreamProvider = null, upstreamSubject = null))

        val linked =
            provisioning.provision(
                AUTH0,
                claims("auth0|second", upstreamProvider = null, upstreamSubject = null),
            )

        assertEquals(original, linked)
    }

    @Test
    fun `an unverified email must NOT link to an existing account`() {
        val victim = provisioning.provision(AUTH0, claims("auth0|victim", email = "target@example.com"))

        // An attacker signs up elsewhere claiming the victim's address, without
        // the provider ever verifying it. Linking here would hand over the
        // victim's account and everything it owns, so the sign-in is refused.
        assertFailsWith<AuthException> {
            provisioning.provision(
                AUTH0,
                claims(
                    "auth0|attacker",
                    email = "target@example.com",
                    isEmailVerified = false,
                    upstreamProvider = null,
                    upstreamSubject = null,
                ),
            )
        }

        // The victim's account is untouched and still owned by the original identity.
        assertEquals(listOf("auth0|victim"), userIdentityRepo.listForUser(victim).map { it.subject })
    }

    @Test
    fun `an unverified identity on a fresh address still gets an account`() {
        val userId =
            provisioning.provision(
                AUTH0,
                claims(
                    "auth0|newcomer",
                    email = "newcomer@example.com",
                    isEmailVerified = false,
                    upstreamProvider = null,
                    upstreamSubject = null,
                ),
            )

        assertTrue(!userRepo.findById(userId)!!.isEmailVerified)
    }

    @Test
    fun `an identity with no email cannot be provisioned`() {
        assertFailsWith<AuthException> {
            provisioning.provision(AUTH0, claims("auth0|noemail", email = null))
        }
    }

    @Test
    fun `a later verified sign-in upgrades an unverified account`() {
        val userId =
            provisioning.provision(
                AUTH0,
                claims("auth0|grow", isEmailVerified = false, upstreamProvider = null, upstreamSubject = null),
            )
        assertTrue(!userRepo.findById(userId)!!.isEmailVerified)

        provisioning.provision(AUTH0, claims("auth0|grow", isEmailVerified = true, upstreamProvider = null, upstreamSubject = null))

        assertTrue(userRepo.findById(userId)!!.isEmailVerified)
    }

    @Test
    fun `upstream identity is backfilled when the provider starts reporting it`() {
        val userId =
            provisioning.provision(
                AUTH0,
                claims("auth0|late", upstreamProvider = null, upstreamSubject = null),
            )

        provisioning.provision(AUTH0, claims("auth0|late", upstreamSubject = "google-late"))

        val identity = userIdentityRepo.listForUser(userId).single()
        assertEquals("google-late", identity.upstreamSubject)
        assertEquals("google", identity.upstreamProvider)
    }

    // --- bootstrap admin -----------------------------------------------------
    //
    // The only writer of `user_role` in the app. Everything below is about what
    // it must refuse as much as what it grants.

    private fun bootstrapping(vararg emails: String) = UserProvisioningService(ctx, emails.toSet())

    private fun rolesOf(userId: UserId) = userRepo.findById(userId)!!.roles

    @Test
    fun `a configured address is granted admin on sign-in`() {
        val userId = bootstrapping("user@example.com").provision(AUTH0, claims("auth0|boot"))

        assertEquals(setOf(Role.ADMIN), rolesOf(userId))
    }

    @Test
    fun `an address that is not configured gets no role`() {
        val userId = bootstrapping("someone-else@example.com").provision(AUTH0, claims("auth0|ordinary"))

        assertEquals(emptySet(), rolesOf(userId))
    }

    @Test
    fun `no configured addresses means nobody is granted anything`() {
        // The default everywhere, including CI: the feature is off until an
        // operator names an address.
        val userId = provisioning.provision(AUTH0, claims("auth0|nobody"))

        assertEquals(emptySet(), rolesOf(userId))
    }

    @Test
    fun `an unverified address must NOT be granted admin`() {
        // Same rule that gates account linking. Without it, anyone who can sign
        // up claiming the bootstrap address — without the provider ever
        // verifying it — becomes an administrator.
        val userId =
            bootstrapping("user@example.com").provision(
                AUTH0,
                claims("auth0|unverified", isEmailVerified = false, upstreamProvider = null, upstreamSubject = null),
            )

        assertEquals(emptySet(), rolesOf(userId))
    }

    @Test
    fun `an unverified account is promoted once the provider verifies the address`() {
        val service = bootstrapping("user@example.com")
        val userId =
            service.provision(
                AUTH0,
                claims("auth0|later", isEmailVerified = false, upstreamProvider = null, upstreamSubject = null),
            )
        assertEquals(emptySet(), rolesOf(userId))

        service.provision(AUTH0, claims("auth0|later", isEmailVerified = true, upstreamProvider = null, upstreamSubject = null))

        assertEquals(setOf(Role.ADMIN), rolesOf(userId))
    }

    @Test
    fun `matching is case-insensitive`() {
        val userId = bootstrapping("user@example.com").provision(AUTH0, claims("auth0|shouty", email = "USER@Example.COM"))

        assertEquals(setOf(Role.ADMIN), rolesOf(userId))
    }

    @Test
    fun `an account that predates the config is promoted on its next sign-in`() {
        // The returning-identity path short-circuits before any linking work, so
        // it needs its own grant call. Without this the first operator would have
        // to delete their own account to become an admin.
        val userId = provisioning.provision(AUTH0, claims("auth0|early"))
        assertEquals(emptySet(), rolesOf(userId))

        val afterConfig = bootstrapping("user@example.com").provision(AUTH0, claims("auth0|early"))

        assertEquals(userId, afterConfig)
        assertEquals(setOf(Role.ADMIN), rolesOf(userId))
    }

    @Test
    fun `repeated sign-ins re-apply the grant without failing`() {
        // `user_role` is keyed (user_id, role), so a second insert would violate
        // the primary key if grantRole were not idempotent — the sign-in would
        // throw rather than merely double-write.
        val service = bootstrapping("user@example.com")
        val userId = service.provision(AUTH0, claims("auth0|repeat-admin"))
        service.provision(AUTH0, claims("auth0|repeat-admin"))

        assertEquals(setOf(Role.ADMIN), rolesOf(userId))
    }

    @Test
    fun `removing an address from the config does not revoke the role`() {
        // Grant-only by design: a config typo that silently demoted every admin
        // is a worse failure than a stale grant, and revocation gets its own path.
        val userId = bootstrapping("user@example.com").provision(AUTH0, claims("auth0|sticky"))
        assertEquals(setOf(Role.ADMIN), rolesOf(userId))

        provisioning.provision(AUTH0, claims("auth0|sticky"))

        assertEquals(setOf(Role.ADMIN), rolesOf(userId))
    }

    @Test
    fun `an account reached by linking a new identity is granted admin`() {
        // The other call site: not the returning-identity short-circuit, but the
        // path that resolves an existing user and links a fresh identity to it.
        val userId =
            provisioning.provision(AUTH0, claims("auth0|one", upstreamProvider = null, upstreamSubject = null))
        assertEquals(emptySet(), rolesOf(userId))

        val linked =
            bootstrapping("user@example.com").provision(
                WORKOS,
                claims("workos|two", upstreamProvider = null, upstreamSubject = null),
            )

        assertEquals(userId, linked)
        assertEquals(setOf(Role.ADMIN), rolesOf(userId))
    }
}
