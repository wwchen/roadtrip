package ca.floo.roadtrip.route.internal

import ca.floo.roadtrip.model.api.BookingRelayAtcRequest
import ca.floo.roadtrip.model.api.BookingRelayLoginRequest
import ca.floo.roadtrip.model.api.BookingRelayMfaRequest
import ca.floo.roadtrip.model.api.BookingRelayProfileRequest
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.BAD_REQUEST_ERROR
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.receiveJsonBody
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.service.booking.BookingRelayController
import ca.floo.roadtrip.service.booking.RelayAnswer
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException

const val BOOKING_RELAY_PREFIX = "/api/internal/booking"
private const val BEARER_PREFIX = "Bearer "
private const val UNAUTHORIZED_ERROR = "unauthorized"
private const val TAG_BOOKING_RELAY = "booking"
private const val RELAY_DESCRIPTION =
    "Internal, reached only through the egress host with a bearer token. Relays one call to the rec.gov " +
        "companion for the profile named in the body, bounded by the relay timeout; holds nothing per user."

internal fun Route.bookingRelayRoutes(
    controller: BookingRelayController,
    token: String?,
) {
    val expected = token ?: return
    relay("health", "Rec.gov session health for one profile", expected) { call ->
        call.withBody<BookingRelayProfileRequest> { controller.health(it.profileId) }
    }
    relay("refresh", "Refresh one profile's rec.gov session from its cookies", expected) { call ->
        call.withBody<BookingRelayProfileRequest> { controller.refresh(it.profileId) }
    }
    relay("verify", "Dry-run check of one profile's rec.gov cart", expected) { call ->
        call.withBody<BookingRelayProfileRequest> { controller.verify(it.profileId) }
    }
    relay("signout", "Sign one profile out of rec.gov and destroy it", expected) { call ->
        call.withBody<BookingRelayProfileRequest> { controller.signout(it.profileId) }
    }
    relay("login", "Sign one profile in to rec.gov with the credentials in the body", expected) { call ->
        call.withBody<BookingRelayLoginRequest> { controller.login(it) }
    }
    relay("login/mfa", "Complete one profile's rec.gov MFA challenge", expected) { call ->
        call.withBody<BookingRelayMfaRequest> { controller.completeMfa(it) }
    }
    relay("atc", "Hold one rec.gov site in one profile's cart", expected) { call ->
        call.withBody<BookingRelayAtcRequest> { request ->
            val arrival = request.startDate.toLocalDateOrNull()
            val checkout = request.endDate.toLocalDateOrNull()
            if (arrival == null || checkout == null) null else controller.atc(request, arrival, checkout)
        }
    }
}

private fun Route.relay(
    segment: String,
    summary: String,
    token: String,
    serve: suspend RoutingContext.(ApplicationCall) -> Unit,
) {
    post("$BOOKING_RELAY_PREFIX/$segment") {
        if (!call.bearerMatches(token)) {
            call.respondApiError(UNAUTHORIZED_ERROR, HttpStatusCode.Unauthorized)
            return@post
        }
        serve(call)
    }.describeApi(TAG_BOOKING_RELAY, summary, RELAY_DESCRIPTION)
        .access(RouteAccess.Anonymous)
}

private suspend inline fun <reified T> ApplicationCall.withBody(answer: (T) -> RelayAnswer?) {
    val request =
        when (val body = receiveJsonBody<T>()) {
            is RouteBodyResult.Invalid -> return respondApiError(BAD_REQUEST_ERROR, HttpStatusCode.BadRequest)
            is RouteBodyResult.Valid -> body.value
        }
    when (val result = answer(request)) {
        null -> respondApiError(BAD_REQUEST_ERROR, HttpStatusCode.BadRequest)
        is RelayAnswer.Answered -> respondEncodedJson(result.body)
        is RelayAnswer.TimedOut -> respondEncodedJson(result.body, HttpStatusCode.GatewayTimeout)
    }
}

private fun ApplicationCall.bearerMatches(token: String): Boolean {
    val header = request.header(HttpHeaders.Authorization) ?: return false
    if (!header.startsWith(BEARER_PREFIX)) return false
    return MessageDigest.isEqual(header.substring(BEARER_PREFIX.length).toByteArray(), token.toByteArray())
}

private fun String.toLocalDateOrNull(): LocalDate? =
    try {
        LocalDate.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }
