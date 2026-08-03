package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.Role
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
    private val provisioning by lazy { provisioningWith(emptyMap()) }
    private val userRepo by lazy { UserRepo(ctx) }
    private val userIdentityRepo by lazy { UserIdentityRepo(ctx) }

    private fun provisioningWith(roleGrants: Map<Role, Set<String>>) = UserProvisioningService(ctx, roleGrants)

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

    @Test
    fun `a verified email in the admin allowlist is granted ADMIN`() {
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("user@example.com")))

        val userId = svc.provision(AUTH0, claims("auth0|admin"))

        assertTrue(Role.ADMIN in userRepo.rolesFor(userId))
    }

    @Test
    fun `a verified email not in any allowlist gets no role`() {
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("someone-else@example.com")))

        val userId = svc.provision(AUTH0, claims("auth0|plain"))

        assertTrue(userRepo.rolesFor(userId).isEmpty())
    }

    @Test
    fun `an UNVERIFIED email in the admin allowlist is NOT granted ADMIN`() {
        // Mirrors the account-linking rule: an unverified address confers no authority.
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("newcomer@example.com")))

        val userId =
            svc.provision(
                AUTH0,
                claims(
                    "auth0|unverified",
                    email = "newcomer@example.com",
                    isEmailVerified = false,
                    upstreamProvider = null,
                    upstreamSubject = null,
                ),
            )

        assertTrue(Role.ADMIN !in userRepo.rolesFor(userId))
    }

    @Test
    fun `a returning identity added to the allowlist later is granted on next sign-in`() {
        // First sign-in with no allowlist: account exists, no role.
        val userId = provisioning.provision(AUTH0, claims("auth0|returning"))
        assertTrue(userRepo.rolesFor(userId).isEmpty())

        // Email is added to the allowlist; the SAME identity signs in again.
        // This exercises the returning-identity short-circuit path.
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("user@example.com")))
        val again = svc.provision(AUTH0, claims("auth0|returning"))

        assertEquals(userId, again)
        assertTrue(Role.ADMIN in userRepo.rolesFor(userId))
    }

    @Test
    fun `granting is idempotent across repeat sign-ins`() {
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("user@example.com")))

        val userId = svc.provision(AUTH0, claims("auth0|idem"))
        svc.provision(AUTH0, claims("auth0|idem"))

        assertEquals(setOf(Role.ADMIN), userRepo.rolesFor(userId))
    }

    @Test
    fun `matching is case-insensitive on the email`() {
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("user@example.com")))

        val userId = svc.provision(AUTH0, claims("auth0|case", email = "User@Example.com"))

        assertTrue(Role.ADMIN in userRepo.rolesFor(userId))
    }
}
