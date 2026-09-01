package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.model.api.AddToCartRequestDto
import ca.floo.roadtrip.model.api.AddToCartResponseDto
import ca.floo.roadtrip.model.api.BookingActionStatus
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.decodeTextJsonBody
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.principal
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.roadtripApiJson
import ca.floo.roadtrip.service.booking.AddToCartOutcome
import ca.floo.roadtrip.service.booking.BookingActionCodes
import ca.floo.roadtrip.service.booking.BookingActionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.LocalDate

private const val API_BOOKING = "/api/booking"
private const val ADD_TO_CART_PATH = "/add-to-cart"
private const val TAG_BOOKING = "booking"

private const val ERROR_INVALID_BODY = "invalid_body"
private const val ERROR_BAD_DATE = "bad_date_window"

/**
 * Codes that mean "try again shortly", not "this cannot work".
 *
 * They map to 409 rather than 502 because nothing upstream is broken — another
 * operation holds the profile, or the browser pool is full. The caller's own
 * retry is the fix.
 */
private val transientConflictCodes =
    setOf(
        RecGovSessionCodes.PROFILE_BUSY,
        RecGovSessionCodes.BROWSER_CAP_REACHED,
        BookingActionCodes.NOT_AVAILABLE,
        // The browser reached rec.gov and rec.gov declined: almost always
        // somebody else took the site between the grid read and the click.
        // A conflict, not a broken service.
        BookingActionCodes.CART_NOT_ADDED,
        BookingActionCodes.CONFIRMATION_DISABLED,
        // Rec.gov never offered the booking. Still a conflict rather than a
        // broken service — the site's own availability moved, or it is not
        // bookable online — and the caller is the one who acts next.
        BookingActionCodes.DATES_NOT_OFFERED,
        BookingActionCodes.NO_RESERVE_BUTTON,
    )

/**
 * Codes the caller can act on themselves.
 *
 * `recgov_session_expired` belongs here, not in the 502s: nothing upstream is
 * broken, the user simply has to log in again in Settings — exactly like
 * `credentials_required`.
 */
private val callerActionableCodes =
    setOf(
        RecGovSessionCodes.SESSION_EXPIRED,
        // The companion's three other ways of saying the same thing, which reach
        // the fire path verbatim. Answering 502 for these claimed an upstream
        // broke when the user simply has to sign in again.
        RecGovSessionCodes.SPA_LOGGED_OUT,
        RecGovSessionCodes.REFRESH_FAILED,
        RecGovSessionCodes.COMPANION_LOGIN_FAILED,
    )

/**
 * Direct add-to-cart, from the availability grid.
 *
 * The HTTP shell over [BookingActionPort]: parse, call, map the sealed
 * outcome onto a status. It is a **synchronous** request that can legitimately
 * take tens of seconds — a real browser drives recreation.gov behind it — and
 * the caller is watching a spinner, which is why nothing is notified here.
 *
 * Each gate gets a distinct status so the frontend can say what actually
 * blocked the hold: 422 the scope cannot be booked at all, 403 the caller has
 * no credentials, 409 the site is gone or the profile is busy, 502 the booking
 * service failed. Only a real hold is a 200.
 */
internal fun Route.bookingRoutes(service: BookingActionPort) {
    route(API_BOOKING) {
        post(ADD_TO_CART_PATH) {
            val user = call.requireBookingUser() ?: return@post
            val req =
                when (val body = call.decodeTextJsonBody<AddToCartRequestDto>(roadtripApiJson)) {
                    is RouteBodyResult.Invalid ->
                        return@post call.respondApiError(ERROR_INVALID_BODY, HttpStatusCode.BadRequest)
                    is RouteBodyResult.Valid -> body.value
                }
            val window =
                runCatching { LocalDate.parse(req.startDate) to LocalDate.parse(req.endDate) }
                    .getOrElse { return@post call.respondApiError(ERROR_BAD_DATE, HttpStatusCode.BadRequest) }

            val outcome =
                service.addToCart(
                    caller = user.userId,
                    campsiteId = req.campsiteId,
                    startDate = window.first,
                    endDate = window.second,
                )
            call.respondOutcome(outcome)
        }.describeApi(
            tag = TAG_BOOKING,
            summary = "Hold one campsite in the caller's rec.gov cart",
            description =
                "Drives a real browser, so it can take tens of seconds. Requires rec.gov " +
                    "credentials saved in Settings; the hold lands in the caller's own cart and " +
                    "stops there — it never checks out.",
        ).access(RouteAccess.User)
    }
}

private suspend fun ApplicationCall.respondOutcome(outcome: AddToCartOutcome) =
    when (outcome) {
        is AddToCartOutcome.Held ->
            respondEncodedJson(AddToCartResponseDto(status = BookingActionStatus.COMPLETED, cartUrl = outcome.cartUrl))
        is AddToCartOutcome.Refused -> respondApiError(outcome.code, refusalStatus(outcome.code))
        is AddToCartOutcome.Failed ->
            respondApiError(
                error = outcome.code,
                status = failureStatus(outcome.code),
                detail = outcome.detail,
            )
    }

private fun failureStatus(code: String): HttpStatusCode =
    when (code) {
        in callerActionableCodes -> HttpStatusCode.Forbidden
        in transientConflictCodes -> HttpStatusCode.Conflict
        else -> HttpStatusCode.BadGateway
    }

private fun refusalStatus(code: String): HttpStatusCode =
    when (code) {
        BookingActionCodes.CREDENTIALS_REQUIRED -> HttpStatusCode.Forbidden
        BookingActionCodes.NOT_AVAILABLE -> HttpStatusCode.Conflict
        BookingActionCodes.INVALID_WINDOW -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.UnprocessableEntity
    }

private suspend fun ApplicationCall.requireBookingUser(): Principal.User? {
    val p = principal() as? Principal.User
    if (p == null) respondApiError("unauthenticated", HttpStatusCode.Unauthorized)
    return p
}
