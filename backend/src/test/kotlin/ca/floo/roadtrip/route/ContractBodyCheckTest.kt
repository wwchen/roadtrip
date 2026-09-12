package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.api.BuildInfoDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.encodeApiJson
import ca.floo.roadtrip.route.common.respondEncodedJson
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BUILD_INFO = "/api/build-info"

/** A body shaped like `HealthResponseDto`, which is not the class the `/api/build-info` row names. */
private const val WRONG_DTO_BODY = """{"status":"ok","now":17}"""

/**
 * The check behind the harness, driven through `/api/build-info` — a row with a
 * three-field success DTO and the default `400 ApiErrorSchema`, so each failure
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
                    get(BUILD_INFO) {
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
            assertDrifted(client.get(BUILD_INFO).status)
            violation("GET $BUILD_INFO -> 400", "re-encoded")
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

    @Test
    fun `a non-JSON body is ignored`() =
        testApplication {
            application {
                routeTestApplication(fixture = true) {
                    get(BUILD_INFO) { call.respondText("not json at all") }.access(RouteAccess.Anonymous)
                }
            }
            assertEquals(HttpStatusCode.OK, client.get(BUILD_INFO).status)
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
