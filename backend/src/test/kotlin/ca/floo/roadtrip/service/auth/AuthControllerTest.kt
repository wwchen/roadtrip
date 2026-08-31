package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.config.AuthConfig
import ca.floo.roadtrip.model.domain.auth.AuthorizationRequest
import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSessionRepo
import ca.floo.roadtrip.support.AuthException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PROVIDER = "auth0"

/**
 * A fake provider so the flow is exercised end to end without an OIDC server.
 * It records what it was asked, which is how the tests assert that the callback
 * hands back exactly the secrets the authorization request minted.
 */
private class FakeIdentityProvider(
    private val claims: IdentityClaims,
) : IdentityProvider {
    override val id: String = OidcIdentityProvider.ID

    var lastCodeVerifier: String? = null
    var lastNonce: String? = null
    var exchangeCount: Int = 0
    private var counter = 0

    override suspend fun authorizationRequest(
        returnTo: String,
        connection: String?,
    ): AuthorizationRequest {
        counter++
        val connectionSuffix = if (connection != null) "&connection=$connection" else ""
        return AuthorizationRequest(
            authorizationUrl = "https://idp.example.com/authorize?n=$counter$connectionSuffix",
            state = "state-$counter",
            nonce = "nonce-$counter",
            codeVerifier = "verifier-$counter",
        )
    }

    override suspend fun exchange(
        code: String,
        codeVerifier: String,
        expectedNonce: String,
    ): IdentityClaims {
        exchangeCount++
        lastCodeVerifier = codeVerifier
        lastNonce = expectedNonce
        if (code != "good-code") throw AuthException("bad code")
        return claims
    }

    override suspend fun logoutUrl(returnTo: String): String? = "https://idp.example.com/logout?to=$returnTo"

    /** SharedDbTest is PER_CLASS, so this instance outlives a single test. */
    fun reset() {
        lastCodeVerifier = null
        lastNonce = null
        exchangeCount = 0
    }
}

class AuthControllerTest : SharedDbTest() {
    private val claims =
        IdentityClaims(
            subject = "auth0|user-1",
            email = "user@example.com",
            isEmailVerified = true,
            displayName = "User",
            upstreamProvider = "google",
            upstreamSubject = "google-1",
        )

    private val identityProvider = FakeIdentityProvider(claims)
    private val userRepo by lazy { UserRepo(ctx) }

    private val authController by lazy {
        AuthController(
            config =
                AuthConfig(
                    issuer = "https://idp.example.com",
                    clientId = "client-abc",
                    clientSecret = "shh",
                    provider = PROVIDER,
                    sessionTtl = Duration.ofDays(30),
                    isCookieSecure = true,
                    realm = "Username-Password-Authentication",
                    embeddedDomain = "idp.example.com",
                    roleGrants = emptyMap(),
                ),
            identityProviderRegistry =
                IdentityProviderRegistry(
                    providers = listOf(identityProvider),
                    activeId = IdentityProviderId(OidcIdentityProvider.ID),
                ),
            userProvisioningService = UserProvisioningService(ctx),
            sessionService =
                SessionService(
                    userRepo = userRepo,
                    userSessionRepo = UserSessionRepo(ctx),
                    sessionTtl = Duration.ofDays(30),
                ),
            userRepo = userRepo,
        )
    }

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM app_user")
        identityProvider.reset()
    }

    @Test
    fun `a full sign-in provisions a user and issues a resolvable session`() {
        val start = kotlinx.coroutines.runBlocking { authController.beginLogin("/watches") }

        val result =
            kotlinx.coroutines.runBlocking {
                authController.completeLogin("good-code", start.flow.state, start.flow)
            }

        assertEquals("/watches", result.returnTo)
        val principal = authController.resolve(result.session.token)
        val user = assertNotNull(userRepo.findByEmail("user@example.com"))
        assertEquals(user.id, (principal as Principal.User).userId)
    }

    @Test
    fun `the callback hands back exactly the secrets the request minted`() {
        // The whole point of the flow cookie: a second attempt must not be able
        // to complete using the first attempt's verifier or nonce.
        val start = kotlinx.coroutines.runBlocking { authController.beginLogin(null) }

        kotlinx.coroutines.runBlocking {
            authController.completeLogin("good-code", start.flow.state, start.flow)
        }

        assertEquals(start.flow.codeVerifier, identityProvider.lastCodeVerifier)
        assertEquals(start.flow.nonce, identityProvider.lastNonce)
    }

    @Test
    fun `a mismatched state is refused before the code is ever redeemed`() {
        val start = kotlinx.coroutines.runBlocking { authController.beginLogin(null) }

        assertFailsWith<AuthException> {
            kotlinx.coroutines.runBlocking {
                authController.completeLogin("good-code", "state-from-another-flow", start.flow)
            }
        }

        assertEquals(0, identityProvider.exchangeCount, "state must be checked before spending the code")
    }

    @Test
    fun `an off-origin return_to is reduced to the site root`() {
        // Otherwise /auth/login is an open redirect — credible phishing exactly
        // because the link genuinely starts on our domain.
        val hostile =
            listOf(
                "https://evil.example.com",
                "//evil.example.com",
                "/\\evil.example.com",
                "javascript:alert(1)",
                "",
            )

        hostile.forEach { candidate ->
            val start = kotlinx.coroutines.runBlocking { authController.beginLogin(candidate) }
            assertEquals("/", start.flow.returnTo, "should have rejected '$candidate'")
        }
    }

    @Test
    fun `a same-origin path with query and fragment is preserved`() {
        val start = kotlinx.coroutines.runBlocking { authController.beginLogin("/watches?poi=42#row-7") }

        assertEquals("/watches?poi=42#row-7", start.flow.returnTo)
    }

    @Test
    fun `resolve reports anonymous for absent, blank and unknown tokens`() {
        assertTrue(authController.resolve(null) is Principal.Anonymous)
        assertTrue(authController.resolve("  ") is Principal.Anonymous)
        assertTrue(authController.resolve("not-a-session") is Principal.Anonymous)
    }

    @Test
    fun `logout makes the session stop resolving`() {
        val start = kotlinx.coroutines.runBlocking { authController.beginLogin(null) }
        val result =
            kotlinx.coroutines.runBlocking {
                authController.completeLogin("good-code", start.flow.state, start.flow)
            }
        assertTrue(authController.resolve(result.session.token) is Principal.User)

        authController.logout(result.session.token)

        assertTrue(authController.resolve(result.session.token) is Principal.Anonymous)
    }

    @Test
    fun `logout tolerates a missing session`() {
        authController.logout(null)
        authController.logout("")
    }

    @Test
    fun `beginPasswordLogin mints a flow whose challenge derives from its verifier`() {
        val start = kotlinx.coroutines.runBlocking { authController.beginPasswordLogin("/watches") }

        assertNotNull(start.flow.state)
        assertNotNull(start.flow.codeVerifier)
        assertEquals(Pkce.challengeFor(start.flow.codeVerifier), start.passwordChallenge)
        assertEquals("/watches", start.flow.returnTo)
    }

    @Test
    fun `beginLogin with google-oauth2 connection produces an authorization URL containing connection param`() {
        val start = kotlinx.coroutines.runBlocking { authController.beginLogin("/x", "google-oauth2") }
        assertTrue(
            start.authorizationUrl.contains("connection=google-oauth2"),
            "expected 'connection=google-oauth2' in '${start.authorizationUrl}'",
        )
    }

    @Test
    fun `beginLogin without connection produces an authorization URL with no connection param`() {
        val start = kotlinx.coroutines.runBlocking { authController.beginLogin("/x") }
        assertTrue(
            !start.authorizationUrl.contains("connection="),
            "expected no 'connection=' in '${start.authorizationUrl}'",
        )
    }

    @Test
    fun `completeLogin from a password-begin flow issues a resolvable session`() {
        val start = kotlinx.coroutines.runBlocking { authController.beginPasswordLogin("/watches") }
        val result =
            kotlinx.coroutines.runBlocking {
                authController.completeLogin("good-code", start.flow.state, start.flow)
            }
        val principal = authController.resolve(result.session.token)
        assertTrue(principal is Principal.User)
        assertEquals("/watches", result.returnTo)
    }
}
