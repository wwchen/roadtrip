package ca.floo.roadtrip.client.companion

import ca.floo.roadtrip.config.RecGovAtcConfig
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionLoginResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PROFILE_ID = "42"
private const val COMPANION_TOKEN = "shared-companion-secret"
private val timeout: Duration = Duration.ofSeconds(5)

private fun clientFor(
    baseUrl: String,
    token: String? = COMPANION_TOKEN,
) = CompanionSessionClient(RecGovAtcConfig(baseUrl, timeout, token))

class CompanionSessionClientTest {
    @Test
    fun `login posts the profile and credentials with the shared secret`() =
        runBlocking {
            CompanionTestServer
                .of(mapOf("/login" to TestResponse(body = LOGGED_IN)))
                .use { server ->
                    val result = clientFor(server.baseUrl).login(PROFILE_ID, "ada@example.com", "hunter2")

                    assertEquals<CompanionLoginResult>(CompanionLoginResult.Ok, result)
                    assertEquals(listOf("/login"), server.paths)
                    assertEquals(listOf<String?>(COMPANION_TOKEN), server.companionTokens.toList())
                    val body = server.bodies.single()
                    assertTrue(body.contains("\"profile_id\":\"$PROFILE_ID\""), body)
                    assertTrue(body.contains("\"username\":\"ada@example.com\""), body)
                    assertTrue(body.contains("\"password\":\"hunter2\""), body)
                }
        }

    @Test
    fun `an interactive login never marks itself unattended`() =
        runBlocking {
            CompanionTestServer
                .of(mapOf("/login" to TestResponse(body = LOGGED_IN)))
                .use { server ->
                    clientFor(server.baseUrl).login(PROFILE_ID, "ada@example.com", "hunter2")

                    // Present would make the companion refuse to open a challenge
                    // for a user who is sitting right there waiting to type a code.
                    assertFalse(server.bodies.single().contains("unattended"), server.bodies.single())
                }
        }

    @Test
    fun `the pre-hold checks run on the short budget, not the ATC one`() =
        runBlocking {
            // A fire that spent the full cart-run timeout on a health check and
            // again on a re-login would reach the browser minutes late, by which
            // point the site is somebody else's.
            val config =
                RecGovAtcConfig(
                    companionBaseUrl = "http://companion.invalid",
                    companionTimeout = Duration.ofSeconds(180),
                    fireTimeout = Duration.ofSeconds(5),
                )
            val recorder = RecordingTimeoutClient()
            val client = CompanionSessionClient(config, recorder)

            client.health(PROFILE_ID)
            client.login(PROFILE_ID, "ada@example.com", "hunter2", unattended = true)
            client.login(PROFILE_ID, "ada@example.com", "hunter2")

            assertEquals(
                listOf(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(180)),
                recorder.timeouts,
            )
        }

    @Test
    fun `an unattended login says so on the wire`() =
        runBlocking {
            CompanionTestServer
                .of(mapOf("/login" to TestResponse(body = LOGGED_IN)))
                .use { server ->
                    clientFor(server.baseUrl).login(PROFILE_ID, "ada@example.com", "hunter2", unattended = true)

                    assertTrue(server.bodies.single().contains("\"unattended\":true"), server.bodies.single())
                }
        }

    @Test
    fun `login surfaces the MFA challenge id`() =
        runBlocking {
            CompanionTestServer
                .of(
                    mapOf(
                        "/login" to
                            TestResponse(
                                status = 401,
                                body =
                                    """
                                    {"ok":false,"error":"mfa_required","challenge_id":"chal-1",
                                     "expires_at":"2026-08-31T00:05:00Z","recgov_auth":{"logged_in":false}}
                                    """.trimIndent(),
                            ),
                    ),
                ).use { server ->
                    val result = clientFor(server.baseUrl).login(PROFILE_ID, "ada@example.com", "hunter2")

                    assertEquals<CompanionLoginResult>(CompanionLoginResult.MfaRequired("chal-1", "2026-08-31T00:05:00Z"), result)
                }
        }

    @Test
    fun `a captcha blocker rides in recgov_auth reason and becomes captcha_required`() =
        runBlocking {
            CompanionTestServer
                .of(
                    mapOf(
                        "/login" to
                            TestResponse(
                                status = 401,
                                body =
                                    """
                                    {"ok":false,"recgov_auth":{"logged_in":false,"error":"recgov_login_failed",
                                     "reason":"captcha_required","detail":"challenge shown"}}
                                    """.trimIndent(),
                            ),
                    ),
                ).use { server ->
                    val result = clientFor(server.baseUrl).login(PROFILE_ID, "ada@example.com", "hunter2")

                    assertEquals<CompanionLoginResult>(
                        CompanionLoginResult.Failed(RecGovSessionCodes.CAPTCHA_REQUIRED, "challenge shown"),
                        result,
                    )
                }
        }

    @Test
    fun `a plain credential rejection becomes login_failed`() =
        runBlocking {
            CompanionTestServer
                .of(
                    mapOf(
                        "/login" to
                            TestResponse(
                                status = 401,
                                body =
                                    """
                                    {"ok":false,"recgov_auth":{"logged_in":false,"error":"recgov_login_failed",
                                     "reason":"login_rejected","detail":"bad password"}}
                                    """.trimIndent(),
                            ),
                    ),
                ).use { server ->
                    val result = clientFor(server.baseUrl).login(PROFILE_ID, "ada@example.com", "nope")

                    assertEquals<CompanionLoginResult>(CompanionLoginResult.Failed(RecGovSessionCodes.LOGIN_FAILED, "bad password"), result)
                }
        }

    @Test
    fun `login backoff is passed through as its own code`() =
        runBlocking {
            CompanionTestServer
                .of(
                    mapOf(
                        "/login" to
                            TestResponse(
                                status = 429,
                                body = """{"ok":false,"error":"login_backoff","detail":"wait"}""",
                            ),
                    ),
                ).use { server ->
                    val result = clientFor(server.baseUrl).login(PROFILE_ID, "ada@example.com", "hunter2")

                    assertEquals<CompanionLoginResult>(CompanionLoginResult.Failed(RecGovSessionCodes.LOGIN_BACKOFF, "wait"), result)
                }
        }

    @Test
    fun `an unreachable companion never throws`() =
        runBlocking {
            // Port 1 on loopback refuses connections; no server is started.
            val result = clientFor("http://127.0.0.1:1").login(PROFILE_ID, "ada@example.com", "hunter2")

            assertTrue(result is CompanionLoginResult.Failed)
            assertEquals(RecGovSessionCodes.COMPANION_UNAVAILABLE, result.code)
        }

    @Test
    fun `completeMfa sends the challenge id and code, never the password`() =
        runBlocking {
            CompanionTestServer
                .of(mapOf("/login" to TestResponse(body = LOGGED_IN)))
                .use { server ->
                    val result = clientFor(server.baseUrl).completeMfa(PROFILE_ID, "chal-1", "123456")

                    assertEquals<CompanionLoginResult>(CompanionLoginResult.Ok, result)
                    val body = server.bodies.single()
                    assertTrue(body.contains("\"challenge_id\":\"chal-1\""), body)
                    assertTrue(body.contains("\"mfa_code\":\"123456\""), body)
                    assertTrue(!body.contains("password"), body)
                }
        }

    @Test
    fun `a rejected code becomes mfa_invalid`() =
        runBlocking {
            CompanionTestServer
                .of(
                    mapOf(
                        "/login" to
                            TestResponse(
                                status = 401,
                                body = """{"ok":false,"error":"mfa_invalid","detail":"Recreation.gov rejected the code"}""",
                            ),
                    ),
                ).use { server ->
                    val result = clientFor(server.baseUrl).completeMfa(PROFILE_ID, "chal-1", "000000")

                    assertEquals<CompanionLoginResult>(
                        CompanionLoginResult.Failed(RecGovSessionCodes.MFA_INVALID, "Recreation.gov rejected the code"),
                        result,
                    )
                }
        }

    @Test
    fun `verify reports a live session`() =
        runBlocking {
            CompanionTestServer
                .of(
                    mapOf(
                        "/verify" to
                            TestResponse(body = """{"ok":true,"profile_id":"42","verify":{"ok":true,"logged_in":true}}"""),
                    ),
                ).use { server ->
                    val result = clientFor(server.baseUrl).verify(PROFILE_ID)

                    assertEquals<CompanionActionResult>(CompanionActionResult.Ok, result)
                    assertEquals("""{"profile_id":"$PROFILE_ID"}""", server.bodies.single())
                }
        }

    @Test
    fun `verify reports a dead session with the companion's own code`() =
        runBlocking {
            CompanionTestServer
                .of(
                    mapOf(
                        "/verify" to
                            TestResponse(
                                status = 401,
                                body =
                                    """
                                    {"ok":false,"profile_id":"42","verify":{"ok":false,
                                     "error":"recgov_not_authenticated","detail":"session gone"}}
                                    """.trimIndent(),
                            ),
                    ),
                ).use { server ->
                    val result = clientFor(server.baseUrl).verify(PROFILE_ID)

                    assertEquals<CompanionActionResult>(
                        CompanionActionResult.Failed(RecGovSessionCodes.NOT_AUTHENTICATED, "session gone"),
                        result,
                    )
                }
        }

    @Test
    fun `logout posts the profile id`() =
        runBlocking {
            CompanionTestServer
                .of(mapOf("/logout" to TestResponse(body = LOGGED_OUT)))
                .use { server ->
                    val result = clientFor(server.baseUrl).logout(PROFILE_ID)

                    assertEquals<CompanionActionResult>(CompanionActionResult.Ok, result)
                    assertEquals(listOf("/logout"), server.paths)
                    assertEquals("""{"profile_id":"$PROFILE_ID"}""", server.bodies.single())
                }
        }

    @Test
    fun `health asks for one profile and reports an active session`() =
        runBlocking {
            CompanionTestServer
                .of(mapOf("/health" to TestResponse(body = HEALTH_LOGGED_IN)))
                .use { server ->
                    val result = clientFor(server.baseUrl).health(PROFILE_ID)

                    assertEquals<CompanionSessionHealth>(CompanionSessionHealth.Active, result)
                    assertEquals("profile_id=$PROFILE_ID", server.queries.single())
                }
        }

    @Test
    fun `health reports an expired session with the auth error`() =
        runBlocking {
            CompanionTestServer
                .of(
                    mapOf(
                        "/health" to
                            TestResponse(
                                body =
                                    """
                                    {"ok":true,"busy":false,"recgov_auth":{"logged_in":false,
                                     "error":"recgov_not_authenticated"}}
                                    """.trimIndent(),
                            ),
                    ),
                ).use { server ->
                    val result = clientFor(server.baseUrl).health(PROFILE_ID)

                    assertEquals<CompanionSessionHealth>(CompanionSessionHealth.Inactive(RecGovSessionCodes.NOT_AUTHENTICATED), result)
                }
        }

    @Test
    fun `health degrades rather than throwing when a field is an array, not a string`() =
        runBlocking {
            // Nothing throws is the contract, and the status route only catches
            // SettingsError — an unread field must not become a 500.
            CompanionTestServer
                .of(
                    mapOf(
                        "/health" to
                            TestResponse(
                                body =
                                    """
                                    {"ok":true,"busy":false,"recgov_auth":{"logged_in":false,
                                     "error":["recgov_not_authenticated","stale"],"detail":{"why":"cookies"}}}
                                    """.trimIndent(),
                            ),
                    ),
                ).use { server ->
                    val result = clientFor(server.baseUrl).health(PROFILE_ID)

                    assertEquals<CompanionSessionHealth>(CompanionSessionHealth.Inactive(null), result)
                }
        }

    @Test
    fun `a login answer with a structured detail still maps to a code`() =
        runBlocking {
            CompanionTestServer
                .of(
                    mapOf(
                        "/login" to
                            TestResponse(
                                status = 401,
                                body =
                                    """
                                    {"ok":false,"detail":["bad","password"],
                                     "recgov_auth":{"logged_in":false,"error":"recgov_login_failed","reason":{"code":1}}}
                                    """.trimIndent(),
                            ),
                    ),
                ).use { server ->
                    val result = clientFor(server.baseUrl).login(PROFILE_ID, "ada@example.com", "nope")

                    assertEquals<CompanionLoginResult>(
                        CompanionLoginResult.Failed(RecGovSessionCodes.LOGIN_FAILED, null),
                        result,
                    )
                }
        }

    @Test
    fun `health degrades rather than failing when the companion is unreachable`() =
        runBlocking {
            val result = clientFor("http://127.0.0.1:1").health(PROFILE_ID)

            assertTrue(result is CompanionSessionHealth.Unavailable)
        }

    @Test
    fun `no shared secret configured sends no token header`() =
        runBlocking {
            CompanionTestServer
                .of(mapOf("/logout" to TestResponse(body = LOGGED_OUT)))
                .use { server ->
                    clientFor(server.baseUrl, token = null).logout(PROFILE_ID)

                    assertEquals(listOf<String?>(null), server.companionTokens)
                }
        }

    private companion object {
        const val LOGGED_IN = """{"ok":true,"recgov_auth":{"login_status":"ok","logged_in":true},"diagnostics":null}"""
        const val LOGGED_OUT = """{"ok":true,"recgov_auth":{"login_status":"logged_out","logged_in":false}}"""
        const val HEALTH_LOGGED_IN =
            """{"ok":true,"busy":false,"profile_id":"42","recgov_auth":{"login_status":"ok","logged_in":true}}"""
    }
}

/**
 * Records the per-request timeout and answers an empty JSON object.
 *
 * A loopback server cannot see the timeout the caller chose, and the choice —
 * short budget for the pre-hold checks, full budget for a browser-driven run —
 * is the behaviour under test.
 */
private class RecordingTimeoutClient : HttpClient() {
    val timeouts = mutableListOf<Duration?>()

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> {
        timeouts += request.timeout().orElse(null)
        @Suppress("UNCHECKED_CAST")
        return CompletableFuture.completedFuture(EmptyJsonResponse(request) as HttpResponse<T>)
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)

    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> = sendAsync(request, responseBodyHandler).join()

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): HttpClient.Redirect = HttpClient.Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()
}

private class EmptyJsonResponse(
    private val request: HttpRequest,
) : HttpResponse<String> {
    override fun statusCode(): Int = 200

    override fun request(): HttpRequest = request

    override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

    override fun body(): String = "{}"

    override fun sslSession(): Optional<javax.net.ssl.SSLSession> = Optional.empty()

    override fun uri(): java.net.URI = request.uri()

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}
