package ca.floo.roadtrip.route.api.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.model.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.model.api.MAGIC_LINK_TOKEN_PARAM
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.boundedIntQuery
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.intQueryAtLeast
import ca.floo.roadtrip.route.common.longPath
import ca.floo.roadtrip.route.common.optionalLongQuery
import ca.floo.roadtrip.route.common.principal
import ca.floo.roadtrip.route.common.queryParam
import ca.floo.roadtrip.route.common.receiveJsonBody
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.service.availability.AvailabilityWatchController
import ca.floo.roadtrip.service.availability.AvailabilityWatchControllerResult
import ca.floo.roadtrip.service.availability.WatchStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val DEFAULT_LIST_LIMIT = 100
private const val MIN_LIST_LIMIT = 1
private const val MAX_LIST_LIMIT = 500
private const val DEFAULT_LIST_OFFSET = 0
private const val MIN_LIST_OFFSET = 0

private val listLimitRange = MIN_LIST_LIMIT..MAX_LIST_LIMIT

private suspend fun ApplicationCall.requireUser(): Principal.User? {
    val p = principal() as? Principal.User
    if (p == null) respondApiError("unauthenticated", HttpStatusCode.Unauthorized)
    return p
}

internal fun Route.availabilityWatchRoutes(watches: AvailabilityWatchController) {
    route("/api") {
        route("/watches") {
            get {
                val user = call.requireUser() ?: return@get
                val status =
                    call.queryParam("status")?.let {
                        WatchStatus.parse(it)
                            ?: return@get call.respondApiError(
                                "invalid_status",
                                HttpStatusCode.BadRequest,
                                "status must be active, paused, or done",
                            )
                    }
                val poiId = call.optionalLongQuery("poi_id")
                val campsiteId = call.optionalLongQuery("campsite_id")
                val limit = call.boundedIntQuery("limit", DEFAULT_LIST_LIMIT, listLimitRange)
                val offset = call.intQueryAtLeast("offset", DEFAULT_LIST_OFFSET, MIN_LIST_OFFSET)
                call.respondEncodedJson(
                    watches.list(user, status, poiId, campsiteId, limit, offset),
                )
            }.describeApi("watches", "List availability watches")
                .access(RouteAccess.User)

            post {
                val user = call.requireUser() ?: return@post
                val req =
                    when (val body = call.receiveJsonBody<AvailabilityWatchCreateRequest>()) {
                        is RouteBodyResult.Invalid ->
                            return@post call.respondApiError("invalid_body", HttpStatusCode.BadRequest, body.detail)
                        is RouteBodyResult.Valid -> body.value
                    }
                call.respondResult(watches.create(user, req), successStatus = HttpStatusCode.Created)
            }.describeApi("watches", "Create a watch")
                .access(RouteAccess.User)

            route("/{id}") {
                get {
                    val id =
                        call.longPath("id")
                            ?: return@get call.respondApiError("invalid_id", HttpStatusCode.BadRequest)
                    call.respondResult(watches.get(call.principal(), id, call.magicLinkToken()))
                }.describeApi("watches", "Get one watch")
                    .access(RouteAccess.UserOrCapability)

                post("/modify") {
                    val id =
                        call.longPath("id")
                            ?: return@post call.respondApiError("invalid_id", HttpStatusCode.BadRequest)
                    val req =
                        when (val body = call.receiveJsonBody<AvailabilityWatchUpdateRequest>()) {
                            is RouteBodyResult.Invalid ->
                                return@post call.respondApiError("invalid_body", HttpStatusCode.BadRequest, body.detail)
                            is RouteBodyResult.Valid -> body.value
                        }
                    call.respondResult(watches.update(call.principal(), id, req, call.magicLinkToken()))
                }.describeApi("watches", "Modify a watch")
                    .access(RouteAccess.UserOrCapability)

                post("/delete") {
                    val id =
                        call.longPath("id")
                            ?: return@post call.respondApiError("invalid_id", HttpStatusCode.BadRequest)
                    when (val result = watches.delete(call.principal(), id, call.magicLinkToken())) {
                        is AvailabilityWatchControllerResult.Ok -> call.respond(HttpStatusCode.NoContent)
                        else -> call.respondFailure(result)
                    }
                }.describeApi("watches", "Delete a watch")
                    .access(RouteAccess.UserOrCapability)
            }
        }
    }
}

/**
 * The magic-link token, when the caller presented one. A query parameter because
 * a click from a mail client can send neither a header nor a body.
 */
private fun ApplicationCall.magicLinkToken(): String? = queryParam(MAGIC_LINK_TOKEN_PARAM)

/** Renders a controller outcome that carries a body on success. */
private suspend inline fun <reified T> ApplicationCall.respondResult(
    result: AvailabilityWatchControllerResult<T>,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
) {
    when (result) {
        is AvailabilityWatchControllerResult.Ok -> respondEncodedJson(result.value, successStatus)
        else -> respondFailure(result)
    }
}

/**
 * The one place a controller refusal becomes a status code. `Ok` is unreachable
 * but spelled out rather than left to an `else`, so a new result variant fails
 * the compile instead of silently 500ing.
 */
private suspend fun ApplicationCall.respondFailure(result: AvailabilityWatchControllerResult<*>) {
    when (result) {
        is AvailabilityWatchControllerResult.Ok ->
            respondApiError("unexpected_result", HttpStatusCode.InternalServerError)
        is AvailabilityWatchControllerResult.Invalid ->
            respondApiError(result.error, HttpStatusCode.BadRequest, result.detail)
        is AvailabilityWatchControllerResult.Forbidden ->
            respondApiError(result.error, HttpStatusCode.Forbidden, result.detail)
        AvailabilityWatchControllerResult.Unauthenticated ->
            respondApiError("unauthenticated", HttpStatusCode.Unauthorized)
        AvailabilityWatchControllerResult.NotFound ->
            respondApiError("not_found", HttpStatusCode.NotFound)
    }
}
