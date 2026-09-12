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

/**
 * The check behind the harness, driven through `/api/build-info` — a row with a
 * three-field success DTO and the default `400 ApiErrorSchema`, so each failure
 * mode is a one-line change to what the handler sends.
 *
 * A [ContractBodyMismatch] thrown from the send pipeline does not reach the
 * client: Ktor's fallback turns it into a 500, which is what fails the test that
 * produced the drift. So each failure case here asserts the 500 and reads the
 * message off [ContractLedger], which records every violation whether or not a
 * ledger file is configured.
 */
class ContractBodyCheckTest {
    @Test
    fun `a body the row's DTO encodes to passes`() =
        testApplication {
            application {
                routeTestApplication {
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
                routeTestApplication {
                    get(BUILD_INFO) {
                        call.respondText(
                            """{"env":"test","sha":"abc","branch":"master","surprise":1}""",
                            ContentType.Application.Json,
                        )
                    }.access(RouteAccess.Anonymous)
                }
            }
            assertEquals(HttpStatusCode.InternalServerError, client.get(BUILD_INFO).status)
            val message = violation("GET $BUILD_INFO -> 200")
            assertTrue(message.contains("strict-decode"), message)
            assertTrue(message.contains("surprise"), message)
        }

    @Test
    fun `a status the row does not declare fails, naming the status`() =
        testApplication {
            application {
                routeTestApplication {
                    get(BUILD_INFO) {
                        call.respondEncodedJson(
                            BuildInfoDto(env = "t", sha = "s", branch = "b"),
                            HttpStatusCode.NotFound,
                        )
                    }.access(RouteAccess.Anonymous)
                }
            }
            assertEquals(HttpStatusCode.InternalServerError, client.get(BUILD_INFO).status)
            assertTrue(violation("GET $BUILD_INFO -> 404").contains("declares no body"))
        }

    @Test
    fun `a body that decodes but re-encodes differently fails`() =
        testApplication {
            application {
                routeTestApplication {
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
            assertEquals(HttpStatusCode.InternalServerError, client.get(BUILD_INFO).status)
            assertTrue(violation("GET $BUILD_INFO -> 400").contains("re-encoded"))
        }

    @Test
    fun `a path the contract does not name is ignored`() =
        testApplication {
            application {
                routeTestApplication {
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
                routeTestApplication {
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

    private fun violation(prefix: String): String {
        val recorded = ContractLedger.violationsStartingWith(prefix)
        assertEquals(1, recorded.size, "expected one violation starting with '$prefix', got $recorded")
        return recorded.single()
    }
}
