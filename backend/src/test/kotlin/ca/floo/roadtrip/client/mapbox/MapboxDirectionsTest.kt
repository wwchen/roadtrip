package ca.floo.roadtrip.client.mapbox

import ca.floo.roadtrip.support.RoutingException
import ca.floo.roadtrip.support.RoutingFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val waypoints = listOf(-123.1 to 49.28, -122.33 to 47.61)

class MapboxDirectionsTest {
    private fun directionsAnswering(
        status: HttpStatusCode,
        body: String,
    ): MapboxDirections {
        val engine =
            MockEngine {
                respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        return MapboxDirections(token = "pk.test", httpClient = HttpClient(engine))
    }

    private fun failureOf(directions: MapboxDirections): RoutingException =
        assertFailsWith<RoutingException> { runBlocking { directions.directions(waypoints) } }

    @Test
    fun `NoRoute keeps Mapbox's code and message`() {
        val failure =
            failureOf(directionsAnswering(HttpStatusCode.OK, """{"code":"NoRoute","message":"No route found"}"""))

        assertEquals(RoutingFailure.NO_ROUTE, failure.failure)
        assertContains(failure.message!!, "NoRoute")
        assertContains(failure.message!!, "No route found")
    }

    @Test
    fun `NoSegment is its own failure`() {
        val failure =
            failureOf(directionsAnswering(HttpStatusCode.OK, """{"code":"NoSegment","message":"Could not find a matching segment"}"""))

        assertEquals(RoutingFailure.NO_SEGMENT, failure.failure)
        assertContains(failure.message!!, "Could not find a matching segment")
    }

    @Test
    fun `InvalidInput on a 422 is invalid input, with Mapbox's message`() {
        val failure =
            failureOf(
                directionsAnswering(
                    HttpStatusCode.UnprocessableEntity,
                    """{"code":"InvalidInput","message":"Coordinate is invalid: 200,0"}""",
                ),
            )

        assertEquals(RoutingFailure.INVALID_INPUT, failure.failure)
        assertContains(failure.message!!, "Coordinate is invalid: 200,0")
    }

    @Test
    fun `a rejected token is an auth failure, not an outage`() {
        val failure =
            failureOf(directionsAnswering(HttpStatusCode.Unauthorized, """{"message":"Not Authorized - Invalid Token"}"""))

        assertEquals(RoutingFailure.UPSTREAM_AUTH, failure.failure)
        assertContains(failure.message!!, "401")
        assertContains(failure.message!!, "Not Authorized - Invalid Token")
    }

    @Test
    fun `a 429 is a rate limit`() {
        val failure = failureOf(directionsAnswering(HttpStatusCode.TooManyRequests, """{"message":"Too Many Requests"}"""))

        assertEquals(RoutingFailure.UPSTREAM_RATE_LIMITED, failure.failure)
    }

    @Test
    fun `an unparseable answer keeps the parser's message`() {
        val failure = failureOf(directionsAnswering(HttpStatusCode.OK, "<html>gateway</html>"))

        assertEquals(RoutingFailure.UPSTREAM_ERROR, failure.failure)
        assertContains(failure.message!!, "parse")
    }

    @Test
    fun `no token is a configuration failure`() {
        val failure = failureOf(MapboxDirections(token = null))

        assertEquals(RoutingFailure.NOT_CONFIGURED, failure.failure)
    }

    @Test
    fun `a transport failure is unreachable and keeps the exception class`() {
        val engine = MockEngine { throw IOException("connection reset") }
        val failure = failureOf(MapboxDirections(token = "pk.test", httpClient = HttpClient(engine)))

        assertEquals(RoutingFailure.UNREACHABLE, failure.failure)
        assertContains(failure.message!!, "IOException")
        assertContains(failure.message!!, "connection reset")
    }
}
