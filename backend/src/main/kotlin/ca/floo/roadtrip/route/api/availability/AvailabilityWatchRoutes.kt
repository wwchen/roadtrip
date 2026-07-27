package ca.floo.roadtrip.route.api.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.model.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.boundedIntQuery
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.intQueryAtLeast
import ca.floo.roadtrip.route.common.longPath
import ca.floo.roadtrip.route.common.optionalLongQuery
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

internal fun Route.availabilityWatchRoutes(watches: AvailabilityWatchController) {
    route("/api") {
        route("/watches") {
            get {
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
                    watches.list(status, poiId, campsiteId, limit, offset),
                )
            }.describeApi("watches", "List availability watches")
                .access(RouteAccess.Anonymous)

            post {
                val req =
                    when (val body = call.receiveJsonBody<AvailabilityWatchCreateRequest>()) {
                        is RouteBodyResult.Invalid ->
                            return@post call.respondError("invalid_body", HttpStatusCode.BadRequest, body.detail)
                        is RouteBodyResult.Valid -> body.value
                    }
                when (val result = watches.create(req)) {
                    is AvailabilityWatchControllerResult.Invalid ->
                        call.respondError(result.error, HttpStatusCode.BadRequest, result.detail)
                    is AvailabilityWatchControllerResult.NotFound ->
                        call.respondError("not_found", HttpStatusCode.NotFound)
                    is AvailabilityWatchControllerResult.Ok ->
                        call.respondJson(result.value, HttpStatusCode.Created)
                }
            }.describeApi("watches", "Create a watch")
                .access(RouteAccess.Anonymous)

            route("/{id}") {
                get {
                    val id =
                        call.longPath("id")
                            ?: return@get call.respondError("invalid_id", HttpStatusCode.BadRequest)
                    val watch =
                        watches.get(id)
                            ?: return@get call.respondError("not_found", HttpStatusCode.NotFound)
                    call.respondJson(watch)
                }.describeApi("watches", "Get one watch")
                    .access(RouteAccess.Anonymous)

                post("/modify") {
                    val id =
                        call.longPath("id")
                            ?: return@post call.respondError("invalid_id", HttpStatusCode.BadRequest)
                    val req =
                        when (val body = call.receiveJsonBody<AvailabilityWatchUpdateRequest>()) {
                            is RouteBodyResult.Invalid ->
                                return@post call.respondError("invalid_body", HttpStatusCode.BadRequest, body.detail)
                            is RouteBodyResult.Valid -> body.value
                        }
                    when (val result = watches.update(id, req)) {
                        is AvailabilityWatchControllerResult.Invalid ->
                            call.respondError(result.error, HttpStatusCode.BadRequest, result.detail)
                        is AvailabilityWatchControllerResult.NotFound ->
                            call.respondError("not_found", HttpStatusCode.NotFound)
                        is AvailabilityWatchControllerResult.Ok ->
                            call.respondJson(result.value)
                    }
                }.describeApi("watches", "Modify a watch")
                    .access(RouteAccess.Anonymous)

                post("/delete") {
                    val id =
                        call.longPath("id")
                            ?: return@post call.respondError("invalid_id", HttpStatusCode.BadRequest)
                    if (watches.delete(id)) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respondError("not_found", HttpStatusCode.NotFound)
                    }
                }.describeApi("watches", "Delete a watch")
                    .access(RouteAccess.Anonymous)
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
