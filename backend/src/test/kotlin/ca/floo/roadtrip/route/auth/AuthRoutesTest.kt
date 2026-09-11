package ca.floo.roadtrip.route.auth

import ca.floo.roadtrip.config.AuthConfig
import ca.floo.roadtrip.model.domain.auth.AuthorizationRequest
import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.auth.UserStatus
import ca.floo.roadtrip.repo.JooqUnitOfWork
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSessionRepo
import ca.floo.roadtrip.service.auth.AuthController
import ca.floo.roadtrip.service.auth.IdentityProvider
import ca.floo.roadtrip.service.auth.IdentityProviderId
import ca.floo.roadtrip.service.auth.IdentityProviderRegistry
import ca.floo.roadtrip.service.auth.OidcIdentityProvider
import ca.floo.roadtrip.service.auth.SessionService
import ca.floo.roadtrip.service.auth.UserProvisioningService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

private val detachedCtx = DSL.using(SQLDialect.POSTGRES)

private val stubUserId = UserId(1L)
private val stubUser =
    User(
        id = stubUserId,
        email = "test-user@example.com",
        isEmailVerified = true,
        displayName = "Sandbox User",
        theme = "system",
        status = UserStatus.ACTIVE,
        roles = setOf(Role.ADMIN),
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
    )

/** Stub [UserRepo] returning [stubUser] for [stubUserId] and null for everything else. */
private val stubUserRepo: UserRepo =
    object : UserRepo(ctx = detachedCtx) {
        override fun findById(id: UserId): User? = if (id == stubUserId) stubUser else null
    }

/**
 * Stub [SessionService] that resolves a single hard-coded token to [principal],
 * without touching any repo. Extends [SessionService] with a detached DSL
 * context so no DB call is ever issued; only [resolve] is called in the
 * auth-on `/api/me` path.
 */
private class StubSessionService(
    private val token: String,
    private val principal: Principal.User,
) : SessionService(
        userRepo = stubUserRepo,
        userSessionRepo = UserSessionRepo(detachedCtx),
        sessionTtl = Duration.ofHours(1),
    ) {
    override fun resolve(token: String): Principal.User? = if (token == this.token) principal else null
}

private const val AUTH_ON_TOKEN = "valid-session-token"
private val authOnPrincipal = Principal.User(stubUserId, setOf(Role.ADMIN))

private const val AUTHORIZE_URL = "https://test.example/authorize"

/**
 * Stands in for the OIDC provider, whose real authorization URL comes from
 * discovery over the network. It echoes the connection hint it was handed, which
 * is what makes the route's allowlist observable from outside.
 */
private class EchoingIdentityProvider : IdentityProvider {
    override val id: String = OidcIdentityProvider.ID

    override suspend fun authorizationRequest(
        returnTo: String,
        connection: String?,
    ): AuthorizationRequest =
        AuthorizationRequest(
            authorizationUrl = connection?.let { "$AUTHORIZE_URL?connection=$it" } ?: AUTHORIZE_URL,
            state = "state",
            nonce = "nonce",
            codeVerifier = "verifier",
        )

    override suspend fun exchange(
        code: String,
        codeVerifier: String,
        expectedNonce: String,
    ): IdentityClaims =
        IdentityClaims(
            subject = "oidc|user-1",
            email = stubUser.email,
            isEmailVerified = true,
            displayName = stubUser.displayName,
        )

    override suspend fun logoutUrl(returnTo: String): String? = null
}

/** Minimal [AuthRouteWiring]: only `resolve` and `beginLogin` are exercised. */
private fun authOnWiring(): AuthRouteWiring {
    val fakeAuthConfig =
        AuthConfig(
            issuer = "https://test.example",
            clientId = "test-client",
            clientSecret = "test-secret-that-is-long-enough",
            provider = "oidc",
            sessionTtl = Duration.ofHours(1),
            isCookieSecure = false,
            realm = "Username-Password-Authentication",
            embeddedDomain = "test.example",
            roleGrants = emptyMap(),
        )
    val sessionService = StubSessionService(AUTH_ON_TOKEN, authOnPrincipal)
    val authController =
        AuthController(
            config = fakeAuthConfig,
            identityProviderRegistry =
                IdentityProviderRegistry(
                    providers = listOf(EchoingIdentityProvider()),
                    activeId = IdentityProviderId(OidcIdentityProvider.ID),
                ),
            userProvisioningService = UserProvisioningService(JooqUnitOfWork(detachedCtx)),
            sessionService = sessionService,
            userRepo = stubUserRepo,
        )
    return AuthRouteWiring(
        authController = authController,
        flowSigningKey = ByteArray(32),
        isCookieSecure = false,
        sessionMaxAgeSeconds = 3600,
        appRootUrl = null,
        authClientId = "test-client",
        authDomain = "test.example",
        authRealm = "Username-Password-Authentication",
        redirectUri = "https://test.example/auth/callback",
        providerLabel = null,
        isEmbeddedLogin = true,
        allowedConnections = setOf("google-oauth2"),
    )
}

class AuthRoutesTest {
    // ── auth off ─────────────────────────────────────────────────────────────

    @Test
    fun `GET me with no cookie and auth off reports not authenticated`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = { Principal.Anonymous } }
                routing { authRoutes(wiring = null) }
            }
            val resp = client.get("/api/me")
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(false, obj["auth_enabled"]!!.jsonPrimitive.boolean)
            assertEquals(false, obj["authenticated"]!!.jsonPrimitive.boolean)
            // Anonymous visitors follow prefers-color-scheme; the server has
            // nothing to say about them, so no user (and therefore no theme)
            // is ever present on this shape.
            assertEquals(null, obj["user"])
        }

    // ── auth on ──────────────────────────────────────────────────────────────

    @Test
    fun `GET me with valid session and auth on reports authenticated and auth enabled with user fields`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = { Principal.Anonymous } }
                routing { authRoutes(wiring = authOnWiring()) }
            }
            val resp =
                client.get("/api/me") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$AUTH_ON_TOKEN") }
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, obj["auth_enabled"]!!.jsonPrimitive.boolean)
            assertEquals(true, obj["authenticated"]!!.jsonPrimitive.boolean)
            val user = obj["user"]!!.jsonObject
            assertEquals(stubUserId.value, user["id"]!!.jsonPrimitive.long)
            assertEquals(stubUser.email, user["email"]!!.jsonPrimitive.content)
            assertEquals(stubUser.displayName, user["display_name"]!!.jsonPrimitive.contentOrNull)
            assertEquals(stubUser.isEmailVerified, user["email_verified"]!!.jsonPrimitive.boolean)
            assertEquals(stubUser.theme, user["theme"]!!.jsonPrimitive.content)
        }

    // ── the connection allowlist ──────────────────────────────────────────────

    @Test
    fun `GET login forwards an allowed connection to the provider`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = { Principal.Anonymous } }
                routing { authRoutes(wiring = authOnWiring()) }
            }
            val resp = createClient { followRedirects = false }.get("/auth/login?connection=google-oauth2")

            assertEquals(HttpStatusCode.Found, resp.status)
            assertEquals("$AUTHORIZE_URL?connection=google-oauth2", resp.headers[HttpHeaders.Location])
        }

    @Test
    fun `GET login drops a connection that is not allowed`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = { Principal.Anonymous } }
                routing { authRoutes(wiring = authOnWiring()) }
            }
            val resp = createClient { followRedirects = false }.get("/auth/login?connection=bogus")

            assertEquals(HttpStatusCode.Found, resp.status)
            assertEquals(AUTHORIZE_URL, resp.headers[HttpHeaders.Location])
        }

    @Test
    fun `GET me with no session and auth on reports not authenticated`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = { Principal.Anonymous } }
                routing { authRoutes(wiring = authOnWiring()) }
            }
            val resp = client.get("/api/me")
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, obj["auth_enabled"]!!.jsonPrimitive.boolean)
            assertEquals(false, obj["authenticated"]!!.jsonPrimitive.boolean)
            assertEquals(null, obj["user"])
        }
}
