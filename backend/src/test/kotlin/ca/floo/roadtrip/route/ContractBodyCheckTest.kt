package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.api.BuildInfoDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.encodeApiJson
import ca.floo.roadtrip.route.common.respondEncodedJson
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BUILD_INFO = "/api/build-info"

/** A row that declares `400 ApiErrorSchema` — `/api/build-info` declares nothing but its 200. */
private const val GEOCODE = "/api/geocode"

/** The one row that declares nothing at all at 400, so a `StatusPages` 400 on it is undeclared. */
private const val SLACK_INTERACTIVITY = "/api/slack/interactivity"

/** A body shaped like `HealthResponseDto`, which is not the class the `/api/build-info` row names. */
private const val WRONG_DTO_BODY = """{"status":"ok","now":17}"""

/** RFC 7807's media type: a `+json` subtype the check has to read as JSON. */
private val problemJson = ContentType("application", "problem+json")

/**
 * The check behind the harness, driven through `/api/build-info` — a row with a
 * three-field success DTO and no declared error at all, so each failure
 * mode is a one-line change to what the handler sends.
 *
 * Drift does not throw: a send-pipeline throw never reaches the client, since
 * Ktor's fallback turns it into a 500 a test may well be asserting. The check
 * rewrites the response to [CONTRACT_DRIFT_STATUS] instead, so each failure case
 * here asserts that status and reads the message off [ContractLedger].
 *
 * This is the one suite that drifts on purpose, so it installs the check as a
 * fixture: its violations go to the ledger under their own kind and the ledger
 * gate needs no exemption for the live row it borrows.
 */
class ContractBodyCheckTest {
    @Test
    fun `a body the row's DTO encodes to passes`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    get(BUILD_INFO) {
                        call.respondEncodedJson(BuildInfoDto(env = "test", sha = "abc", branch = "master"))
                    }.access(RouteAccess.Anonymous)
                }
            }
            assertEquals(HttpStatusCode.OK, client.get(BUILD_INFO).status)
        }

    @Test
    fun `a field the DTO does not declare fails, naming the route and the status`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    get(BUILD_INFO) {
                        call.respondText(
                            """{"env":"test","sha":"abc","branch":"master","surprise":1}""",
                            ContentType.Application.Json,
                        )
                    }.access(RouteAccess.Anonymous)
                }
            }
            assertDrifted(client.get(BUILD_INFO).status)
            val message = violation("GET $BUILD_INFO -> 200", "surprise")
            assertTrue(message.contains("strict-decode"), message)
        }

    /**
     * The spec's own verification step: point a row at the wrong class — here by
     * serving another row's shape — and the check says so.
     */
    @Test
    fun `a body shaped like another row's DTO fails`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    get(BUILD_INFO) {
                        call.respondText(WRONG_DTO_BODY, ContentType.Application.Json)
                    }.access(RouteAccess.Anonymous)
                }
            }
            assertDrifted(client.get(BUILD_INFO).status)
            val message = violation("GET $BUILD_INFO -> 200", "'status'")
            assertTrue(message.contains("strict-decode"), message)
            assertTrue(message.contains(BuildInfoDto::class.qualifiedName!!), message)
        }

    @Test
    fun `a status the row does not declare fails, naming the status`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    get(BUILD_INFO) {
                        call.respondEncodedJson(
                            BuildInfoDto(env = "t", sha = "s", branch = "b"),
                            HttpStatusCode.NotFound,
                        )
                    }.access(RouteAccess.Anonymous)
                }
            }
            assertDrifted(client.get(BUILD_INFO).status)
            violation("GET $BUILD_INFO -> 404", "declares no body")
        }

    @Test
    fun `a body that decodes but re-encodes differently fails`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    get(GEOCODE) {
                        // Decodes fine — `detail` is nullable — but the one encoder omits a
                        // null rather than writing it, so the two trees differ.
                        call.respondText(
                            """{"error":"boom","detail":null}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    }.access(RouteAccess.Anonymous)
                }
            }
            assertDrifted(client.get(GEOCODE).status)
            violation("GET $GEOCODE -> 400", "re-encoded")
        }

    /**
     * A `+json` subtype is JSON. The gate once compared the media type to
     * `application/json` exactly, so an RFC 7807 refusal on a contracted row was
     * skipped silently — no violation, no ledger line — and the row's declared
     * error would have disappeared from everything that checks it.
     */
    @Test
    fun `a problem+json body is read as JSON and checked against the row`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    get(GEOCODE) {
                        call.respondText(
                            """{"error":"boom","surprise":1}""",
                            problemJson,
                            HttpStatusCode.BadRequest,
                        )
                    }.access(RouteAccess.Anonymous)
                }
            }
            assertDrifted(client.get(GEOCODE).status)
            val message = violation("GET $GEOCODE -> 400", "surprise")
            assertTrue(message.contains("strict-decode"), message)
        }

    /**
     * The body a `StatusPages` handler writes is checked too.
     *
     * Ktor catches the throw on the engine call, not on the routing call, so this
     * response reaches the check only through the leaf routing stashed on the way
     * in. The Slack row declares no error bodies at all, so the shared
     * `BadRequestException` handler's `400 ApiErrorSchema` is an undeclared status.
     */
    @Test
    fun `a StatusPages answer on a contracted route is checked against the row`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    post(SLACK_INTERACTIVITY) { throw BadRequestException("no signature") }
                        .access(RouteAccess.Anonymous)
                }
            }
            assertDrifted(client.post(SLACK_INTERACTIVITY).status)
            violation("POST $SLACK_INTERACTIVITY -> 400", "declares no body")
        }

    @Test
    fun `a path the contract does not name is ignored`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    get("/test/anything") {
                        call.respondText("""{"whatever":true}""", ContentType.Application.Json)
                    }.access(RouteAccess.Anonymous)
                }
            }
            assertEquals(HttpStatusCode.OK, client.get("/test/anything").status)
        }

    /**
     * The row declares `BuildInfoDto` at 200, so a `text/plain` 200 is a row the
     * check can no longer read — which is drift, not a skip. Skipping it meant a
     * contracted route could stop serving JSON entirely and the only signal would
     * be the ledger gate calling its success body unexercised.
     */
    @Test
    fun `a non-JSON body on a status the row declares a body at is drift`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    get(BUILD_INFO) { call.respondText("not json at all") }.access(RouteAccess.Anonymous)
                }
            }
            assertDrifted(client.get(BUILD_INFO).status)
            violation("GET $BUILD_INFO -> 200", "cannot read as JSON")
        }

    /**
     * The other half of the same rule: the Slack row's answers are empty
     * `text/plain` bodies Slack only reads the status of, and it declares no body
     * at 200. Nothing to read means nothing to check.
     */
    @Test
    fun `a non-JSON body on a status the row declares body-less is ignored`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    post(SLACK_INTERACTIVITY) { call.respondText("") }.access(RouteAccess.Anonymous)
                }
            }
            assertEquals(HttpStatusCode.OK, client.post(SLACK_INTERACTIVITY).status)
        }

    /**
     * H1's fix stashes the leaf routing matched, so a plugin that refuses a call
     * *before* routing binds a node left the `StatusPages` answer unchecked — 400
     * with `ApiErrorSchema` on the one row that declares nothing at 400, and the
     * client saw the 400. Nothing in `installRoadtripPlugins` throws that early
     * today; the first rate limiter or signature check would. With no leaf to
     * read, the request's own method and path resolve against the contract.
     */
    @Test
    fun `a refusal thrown before routing is checked against the row the path resolves to`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    get(BUILD_INFO) {
                        call.respondEncodedJson(BuildInfoDto(env = "test", sha = "abc", branch = "master"))
                    }.access(RouteAccess.Anonymous)
                }
                intercept(ApplicationCallPipeline.Plugins) {
                    if (call.request.path() == BUILD_INFO) throw BadRequestException("thrown before routing")
                }
            }
            assertDrifted(client.get(BUILD_INFO).status)
            violation("GET $BUILD_INFO -> 400", "declares no body")
        }

    @Test
    fun `the round trip is the one encoder, so a contract DTO always passes its own output`() {
        val dto = BuildInfoDto(env = "test", sha = "abc", branch = "master")
        assertEquals("""{"env":"test","sha":"abc","branch":"master"}""", encodeApiJson(dto))
        assertEquals("""{"error":"boom"}""", encodeApiJson(ApiErrorSchema(error = "boom")))
    }

    private fun assertDrifted(status: HttpStatusCode) = assertEquals(CONTRACT_DRIFT_STATUS, status.value)

    /**
     * The one recorded violation for [prefix] that [marker] identifies — the cases
     * share a route, and two of them share its success status.
     */
    private fun violation(
        prefix: String,
        marker: String,
    ): String {
        val recorded = ContractLedger.violationsStartingWith(prefix).filter { it.contains(marker) }
        assertEquals(1, recorded.size, "expected one violation '$prefix' mentioning '$marker', got $recorded")
        return recorded.single()
    }
}
