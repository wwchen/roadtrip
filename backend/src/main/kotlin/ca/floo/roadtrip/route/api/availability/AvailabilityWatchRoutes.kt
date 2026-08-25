package ca.floo.roadtrip.route.api.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.model.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.model.domain.auth.WatchCredential
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
import ca.floo.roadtrip.service.auth.WatchAccessTokenService
import ca.floo.roadtrip.service.availability.AvailabilityWatchController
import ca.floo.roadtrip.service.availability.AvailabilityWatchControllerResult
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.notification.common.WATCH_TOKEN_PARAM
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

private const val DEFAULT_LIST_LIMIT = 100
private const val MIN_LIST_LIMIT = 1
private const val MAX_LIST_LIMIT = 500
private const val DEFAULT_LIST_OFFSET = 0
private const val MIN_LIST_OFFSET = 0

private val listLimitRange = MIN_LIST_LIMIT..MAX_LIST_LIMIT

@OptIn(ExperimentalSerializationApi::class)
private val watchJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

private suspend fun ApplicationCall.requireUser(): Principal.User? {
    val p = principal() as? Principal.User
    if (p == null) respondApiError("unauthenticated", HttpStatusCode.Unauthorized)
    return p
}

/**
 * The credential for a single-watch route: the session if there is one, else the
 * magic-link token from the query string.
 *
 * Session first, so a signed-in owner following their own alert link keeps
 * acting as themselves — a stale or foreign token in the URL can then only
 * narrow what they could already do, never widen it. Answers `401` when neither
 * is present, which is also what a dead or expired link gets: from the outside
 * an expired link and no link are the same request.
 */
private suspend fun ApplicationCall.watchCredential(watchAccessTokenService: WatchAccessTokenService): WatchCredential? {
    (principal() as? Principal.User)?.let { return WatchCredential.Session(it) }
    val token = queryParam(WATCH_TOKEN_PARAM)
    val credential = token?.let(watchAccessTokenService::resolve)
    if (credential == null) respondApiError("unauthenticated", HttpStatusCode.Unauthorized)
    return credential
}

internal fun Route.availabilityWatchRoutes(
    watches: AvailabilityWatchController,
    watchAccessTokenService: WatchAccessTokenService,
) {
    route("/api") {
        route("/watches") {
            get {
                val user = call.requireUser() ?: return@get
                val status =
                    call.queryParam("status")?.let {
                        WatchStatus.parse(it)
                            ?: return@get call.respondError(
                                "invalid_status",
                                HttpStatusCode.BadRequest,
                                "status must be active, paused, or done",
                            )
                    }
                val poiId = call.optionalLongQuery("poi_id")
                val campsiteId = call.optionalLongQuery("campsite_id")
                val limit = call.boundedIntQuery("limit", DEFAULT_LIST_LIMIT, listLimitRange)
                val offset = call.intQueryAtLeast("offset", DEFAULT_LIST_OFFSET, MIN_LIST_OFFSET)
                call.respondJson(
                    watches.list(user, status, poiId, campsiteId, limit, offset),
                )
            }.describeApi("watches", "List availability watches")
                .access(RouteAccess.User)

            post {
                val user = call.requireUser() ?: return@post
                val req =
                    when (val body = call.receiveJsonBody<AvailabilityWatchCreateRequest>()) {
                        is RouteBodyResult.Invalid ->
                            return@post call.respondError("invalid_body", HttpStatusCode.BadRequest, body.detail)
                        is RouteBodyResult.Valid -> body.value
                    }
                when (val result = watches.create(user, req)) {
                    is AvailabilityWatchControllerResult.Invalid ->
                        call.respondError(result.error, HttpStatusCode.BadRequest, result.detail)
                    is AvailabilityWatchControllerResult.NotFound ->
                        call.respondError("not_found", HttpStatusCode.NotFound)
                    is AvailabilityWatchControllerResult.Ok ->
                        call.respondJson(result.value, HttpStatusCode.Created)
                }
            }.describeApi("watches", "Create a watch")
                .access(RouteAccess.User)

            route("/{id}") {
                get {
                    val credential = call.watchCredential(watchAccessTokenService) ?: return@get
                    val id =
                        call.longPath("id")
                            ?: return@get call.respondError("invalid_id", HttpStatusCode.BadRequest)
                    val watch =
                        watches.get(credential, id)
                            ?: return@get call.respondError("not_found", HttpStatusCode.NotFound)
                    call.respondJson(watch)
                }.describeApi("watches", "Get one watch")
                    .access(RouteAccess.UserOrCapability)

                post("/modify") {
                    val credential = call.watchCredential(watchAccessTokenService) ?: return@post
                    val id =
                        call.longPath("id")
                            ?: return@post call.respondError("invalid_id", HttpStatusCode.BadRequest)
                    val req =
                        when (val body = call.receiveJsonBody<AvailabilityWatchUpdateRequest>()) {
                            is RouteBodyResult.Invalid ->
                                return@post call.respondError("invalid_body", HttpStatusCode.BadRequest, body.detail)
                            is RouteBodyResult.Valid -> body.value
                        }
                    when (val result = watches.update(credential, id, req)) {
                        is AvailabilityWatchControllerResult.Invalid ->
                            call.respondError(result.error, HttpStatusCode.BadRequest, result.detail)
                        is AvailabilityWatchControllerResult.NotFound ->
                            call.respondError("not_found", HttpStatusCode.NotFound)
                        is AvailabilityWatchControllerResult.Ok ->
                            call.respondJson(result.value)
                    }
                }.describeApi("watches", "Modify a watch")
                    .access(RouteAccess.UserOrCapability)

                post("/delete") {
                    val credential = call.watchCredential(watchAccessTokenService) ?: return@post
                    val id =
                        call.longPath("id")
                            ?: return@post call.respondError("invalid_id", HttpStatusCode.BadRequest)
                    if (watches.delete(credential, id)) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respondError("not_found", HttpStatusCode.NotFound)
                    }
                }.describeApi("watches", "Delete a watch")
                    .access(RouteAccess.UserOrCapability)
            }
        }
    }
}

private suspend inline fun <reified T> ApplicationCall.respondJson(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondEncodedJson(watchJson, body, status)

private suspend fun ApplicationCall.respondError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) = respondApiError(error = error, status = status, detail = detail)
