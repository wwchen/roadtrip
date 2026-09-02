package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.model.api.RECGOV_CART_URL
import ca.floo.roadtrip.model.booking.BookingFailureCategory
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.route.auth.SESSION_COOKIE
import ca.floo.roadtrip.route.auth.roadtripAuthorization
import ca.floo.roadtrip.service.booking.AddToCartOutcome
import ca.floo.roadtrip.service.booking.BookingActionCodes
import ca.floo.roadtrip.service.booking.BookingActionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ADD_TO_CART = "/api/booking/add-to-cart"
private const val USER_TOKEN = "user-token"
private val testUser = UserId(7L)

private const val VALID_BODY = """{"campsite_id":42,"start_date":"2026-07-04","end_date":"2026-07-06"}"""

private fun resolve(token: String?): Principal =
    if (token == USER_TOKEN) Principal.User(userId = testUser, roles = emptySet()) else Principal.Anonymous

private fun ApplicationTestBuilder.mount(service: BookingActionPort) {
    application {
        install(roadtripAuthorization) { resolvePrincipal = ::resolve }
        routing { bookingRoutes(service) }
    }
}

private fun HttpRequestBuilder.asUser() = header(HttpHeaders.Cookie, "$SESSION_COOKIE=$USER_TOKEN")

/** Answers a fixed outcome and records what the route asked for. */
private class StubBookingActions(
    private val outcome: AddToCartOutcome = AddToCartOutcome.Held(RECGOV_CART_URL),
) : BookingActionPort {
    var calls = 0
    var lastCaller: UserId? = null
    var lastCampsiteId: Long? = null
    var lastWindow: Pair<LocalDate, LocalDate>? = null

    override suspend fun addToCart(
        caller: UserId,
        campsiteId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AddToCartOutcome {
        calls += 1
        lastCaller = caller
        lastCampsiteId = campsiteId
        lastWindow = startDate to endDate
        return outcome
    }
}

class BookingRoutesTest {
    @Test
    fun `an anonymous caller cannot hold a site`() =
        testApplication {
            val service = StubBookingActions()
            mount(service)

            val resp =
                client.post(ADD_TO_CART) {
                    contentType(ContentType.Application.Json)
                    setBody(VALID_BODY)
                }

            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, service.calls, "an unauthenticated request must not reach a browser")
        }

    @Test
    fun `a held site answers 200 with the cart to finish in`() =
        testApplication {
            val service = StubBookingActions()
            mount(service)

            val resp =
                client.post(ADD_TO_CART) {
                    asUser()
                    contentType(ContentType.Application.Json)
                    setBody(VALID_BODY)
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("completed", json["status"]!!.jsonPrimitive.content)
            assertEquals(RECGOV_CART_URL, json["cart_url"]!!.jsonPrimitive.content)
            // The caller is taken from the session, never from the body.
            assertEquals(testUser, service.lastCaller)
            assertEquals(42L, service.lastCampsiteId)
            assertEquals(LocalDate.parse("2026-07-04") to LocalDate.parse("2026-07-06"), service.lastWindow)
        }

    @Test
    fun `each gate gets its own status so the UI can say what blocked it`() {
        // The mapping IS the behaviour here, so it is asserted as a table
        // rather than four near-identical tests.
        val expected =
            listOf(
                BookingActionCodes.UNSUPPORTED_TARGET to HttpStatusCode.UnprocessableEntity,
                BookingActionCodes.CREDENTIALS_REQUIRED to HttpStatusCode.Forbidden,
                BookingActionCodes.NOT_AVAILABLE to HttpStatusCode.Conflict,
                BookingActionCodes.INVALID_WINDOW to HttpStatusCode.BadRequest,
            )

        for ((code, status) in expected) {
            testApplication {
                mount(StubBookingActions(AddToCartOutcome.Refused(code)))

                val resp =
                    client.post(ADD_TO_CART) {
                        asUser()
                        contentType(ContentType.Application.Json)
                        setBody(VALID_BODY)
                    }

                assertEquals(status, resp.status, "$code must map to $status")
                assertEquals(
                    code,
                    Json
                        .parseToJsonElement(resp.bodyAsText())
                        .jsonObject["error"]!!
                        .jsonPrimitive.content,
                )
            }
        }
    }

    @Test
    fun `each failure category gets its own status, whatever the provider called it`() {
        // The route classifies nothing: it used to keep two sets of rec.gov codes
        // and answer 502 for anything in neither. Which code lands in which
        // category is the adapter's business, and is tested there.
        val expected =
            mapOf(
                BookingFailureCategory.CALLER_ACTION to HttpStatusCode.Forbidden,
                BookingFailureCategory.RETRY_LATER to HttpStatusCode.Conflict,
                BookingFailureCategory.UPSTREAM to HttpStatusCode.BadGateway,
            )
        for ((category, status) in expected) {
            testApplication {
                mount(StubBookingActions(AddToCartOutcome.Failed("provider_said_no", "why", category)))

                val resp =
                    client.post(ADD_TO_CART) {
                        asUser()
                        contentType(ContentType.Application.Json)
                        setBody(VALID_BODY)
                    }

                assertEquals(status, resp.status, "$category")
            }
        }
    }

    @Test
    fun `a quoted campsite_id still decodes to the same Long`() =
        testApplication {
            // Pinned because the frontend used to send String(row.id) and the
            // shape of that bug depends entirely on this: kotlinx coerces a
            // quoted number into a Long here, so it was never the 400 it looked
            // like it should be. The frontend now sends a number regardless —
            // this test exists so the tolerance is a recorded fact rather than
            // an assumption either side is free to break.
            val service = StubBookingActions()
            mount(service)

            val resp =
                client.post(ADD_TO_CART) {
                    asUser()
                    contentType(ContentType.Application.Json)
                    setBody("""{"campsite_id":"42","start_date":"2026-07-04","end_date":"2026-07-06"}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(42L, service.lastCampsiteId)
        }

    @Test
    fun `a broken booking service is a bad gateway, with its own code intact`() =
        testApplication {
            mount(
                StubBookingActions(
                    AddToCartOutcome.Failed(
                        RecGovSessionCodes.COMPANION_UNAVAILABLE,
                        "refused",
                        BookingFailureCategory.UPSTREAM,
                    ),
                ),
            )

            val resp =
                client.post(ADD_TO_CART) {
                    asUser()
                    contentType(ContentType.Application.Json)
                    setBody(VALID_BODY)
                }

            assertEquals(HttpStatusCode.BadGateway, resp.status)
            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(RecGovSessionCodes.COMPANION_UNAVAILABLE, json["error"]!!.jsonPrimitive.content)
            assertEquals("refused", json["detail"]!!.jsonPrimitive.content)
        }

    @Test
    fun `an unparseable date is rejected before the service is called`() =
        testApplication {
            val service = StubBookingActions()
            mount(service)

            val resp =
                client.post(ADD_TO_CART) {
                    asUser()
                    contentType(ContentType.Application.Json)
                    setBody("""{"campsite_id":42,"start_date":"not-a-date","end_date":"2026-07-06"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, service.calls)
        }

    @Test
    fun `a malformed body is rejected without echoing it back`() =
        testApplication {
            val service = StubBookingActions()
            mount(service)

            val resp =
                client.post(ADD_TO_CART) {
                    asUser()
                    contentType(ContentType.Application.Json)
                    setBody("""{"campsite_id":42,,}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, service.calls)
        }
}
