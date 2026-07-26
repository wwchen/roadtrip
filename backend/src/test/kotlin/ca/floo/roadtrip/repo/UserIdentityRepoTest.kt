package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.UserId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROVIDER = "auth0"

class UserIdentityRepoTest : SharedDbTest() {
    private val userRepo by lazy { UserRepo(ctx) }
    private val userIdentityRepo by lazy { UserIdentityRepo(ctx) }
    private var emailSeq = 0

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM app_user")
    }

    private fun newUser(): UserId =
        userRepo
            .create(email = "user-${emailSeq++}@example.com", displayName = null, isEmailVerified = true)
            .id

    private fun claims(
        subject: String,
        upstreamProvider: String? = "google",
        upstreamSubject: String? = "google-sub-$subject",
        isEmailVerified: Boolean = true,
    ) = IdentityClaims(
        subject = subject,
        email = "user@example.com",
        isEmailVerified = isEmailVerified,
        displayName = "User",
        upstreamProvider = upstreamProvider,
        upstreamSubject = upstreamSubject,
    )

    @Test
    fun `link then find by provider subject round-trips`() {
        val userId = newUser()

        val linked = userIdentityRepo.link(userId, PROVIDER, claims("auth0|abc"))

        val found = assertNotNull(userIdentityRepo.findByProviderSubject(PROVIDER, "auth0|abc"))
        assertEquals(linked.id, found.id)
        assertEquals(userId, found.userId)
        assertEquals("google", found.upstreamProvider)
        assertEquals("google-sub-auth0|abc", found.upstreamSubject)
        assertNotNull(found.emailVerifiedAt)
    }

    @Test
    fun `unverified claims leave emailVerifiedAt null`() {
        val userId = newUser()

        userIdentityRepo.link(userId, PROVIDER, claims("auth0|unverified", isEmailVerified = false))

        val found = assertNotNull(userIdentityRepo.findByProviderSubject(PROVIDER, "auth0|unverified"))
        assertNull(found.emailVerifiedAt)
    }

    @Test
    fun `findByUpstreamSubject locates the identity after a vendor swap`() {
        val userId = newUser()
        userIdentityRepo.link(userId, PROVIDER, claims("auth0|xyz", upstreamSubject = "google-123"))

        // The aggregator's `sub` would change on a swap; the upstream one does not.
        val found = assertNotNull(userIdentityRepo.findByUpstreamSubject("google", "google-123"))

        assertEquals(userId, found.userId)
    }

    @Test
    fun `findByUpstreamSubject misses when upstream was never recorded`() {
        val userId = newUser()
        userIdentityRepo.link(
            userId,
            PROVIDER,
            claims("auth0|noupstream", upstreamProvider = null, upstreamSubject = null),
        )

        assertNull(userIdentityRepo.findByUpstreamSubject("google", "google-123"))
    }

    @Test
    fun `the same provider subject cannot be linked twice`() {
        userIdentityRepo.link(newUser(), PROVIDER, claims("auth0|dup"))

        val second = runCatching { userIdentityRepo.link(newUser(), PROVIDER, claims("auth0|dup")) }

        assertTrue(second.isFailure, "expected the (provider, subject) unique constraint to reject the duplicate")
    }

    @Test
    fun `refresh backfills upstream identity and never clears verification`() {
        val userId = newUser()
        val linked =
            userIdentityRepo.link(
                userId,
                PROVIDER,
                claims("auth0|late", upstreamProvider = null, upstreamSubject = null),
            )
        assertNull(linked.upstreamSubject)

        // The provider starts exposing upstream identity on a later sign-in.
        val refreshed =
            assertNotNull(
                userIdentityRepo.refresh(linked.id, claims("auth0|late", upstreamSubject = "google-999")),
            )
        assertEquals("google-999", refreshed.upstreamSubject)
        assertNotNull(refreshed.emailVerifiedAt)

        // A later unverified sign-in must not strip the earned verification.
        val afterUnverified =
            assertNotNull(
                userIdentityRepo.refresh(
                    linked.id,
                    claims("auth0|late", upstreamSubject = "google-999", isEmailVerified = false),
                ),
            )
        assertNotNull(afterUnverified.emailVerifiedAt)
    }

    @Test
    fun `identities cascade when the user is deleted`() {
        val userId = newUser()
        userIdentityRepo.link(userId, PROVIDER, claims("auth0|cascade"))

        ctx.execute("DELETE FROM app_user WHERE id = ?", userId.value)

        assertNull(userIdentityRepo.findByProviderSubject(PROVIDER, "auth0|cascade"))
    }

    @Test
    fun `listForUser returns every linked identity`() {
        val userId = newUser()
        userIdentityRepo.link(userId, PROVIDER, claims("auth0|one", upstreamProvider = "google"))
        userIdentityRepo.link(userId, PROVIDER, claims("auth0|two", upstreamProvider = "apple"))

        val identities = userIdentityRepo.listForUser(userId)

        assertEquals(listOf("auth0|one", "auth0|two"), identities.map { it.subject })
    }
}
