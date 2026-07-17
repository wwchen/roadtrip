package ca.floo.roadtrip.route.api.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.model.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.route.common.boundedIntQuery
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.intQueryAtLeast
import ca.floo.roadtrip.route.common.longPath
import ca.floo.roadtrip.route.common.optionalLongQuery
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.service.availability.AvailabilityWatchApiMapper
import ca.floo.roadtrip.service.availability.AvailabilityWatchRequestMapper
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.AvailabilityWatchValidationException
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.availability.WatchRequestMapping
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.WatchStatus
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jooq.DSLContext

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

internal fun Route.availabilityWatchRoutes(
    ctx: DSLContext,
    watchService: AvailabilityWatchService,
    watchCapabilities: WatchCapabilityService? = null,
) {
    val watches = AvailabilityWatchRepo(ctx)
    val campsitesRepo = CampsiteRepo(ctx)
    val scopeResolver = WatchScopeResolver(campsitesRepo)
    val watchMapper = AvailabilityWatchApiMapper(campsitesRepo, scopeResolver, watchCapabilities)

    route("/api") {
        route("/watches") {
            get {
                val status =
                    call.request.queryParameters["status"]?.let {
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
                val rows = watches.list(status, poiId, campsiteId, limit, offset)
                val total = watches.count(status, poiId, campsiteId)
                call.respondJson(
                    watchMapper.listResponse(rows, total, limit, offset),
                )
            }.describeApi("availability", "List availability watches")

            post {
                val raw = call.receiveText()
                val req =
                    try {
                        watchJson.decodeFromString<AvailabilityWatchCreateRequest>(raw)
                    } catch (e: Exception) {
                        return@post call.respondError("invalid_body", HttpStatusCode.BadRequest, e.message)
                    }
                val parsed =
                    when (val mapped = AvailabilityWatchRequestMapper.parseCreate(req)) {
                        is WatchRequestMapping.Invalid ->
                            return@post call.respondError(
                                mapped.error,
                                HttpStatusCode.BadRequest,
                                mapped.detail,
                            )
                        is WatchRequestMapping.Valid -> mapped.value
                    }
                val watch =
                    try {
                        watchService.create(
                            targets = parsed.targets,
                            campsiteFilters = req.campsiteFilters,
                            startDate = parsed.dateWindow.startDate,
                            endDate = parsed.dateWindow.endDate,
                            cadenceSec = req.cadenceSec,
                            triggerKinds = req.triggerKinds,
                            triggerConfig = req.triggerConfig,
                            stopWhenTriggered = req.stopWhenTriggered,
                        )
                    } catch (e: AvailabilityWatchValidationException) {
                        return@post call.respondError(e.error, HttpStatusCode.BadRequest, e.message)
                    }
                call.respondJson(watchMapper.response(watch), HttpStatusCode.Created)
            }.describeApi("availability", "Create a watch")

            route("/{id}") {
                get {
                    val id =
                        call.longPath("id")
                            ?: return@get call.respondError("invalid_id", HttpStatusCode.BadRequest)
                    val watch =
                        watches.findById(id)
                            ?: return@get call.respondError("not_found", HttpStatusCode.NotFound)
                    call.respondJson(
                        watchMapper.response(watch, includeCapabilities = true),
                    )
                }.describeApi("availability", "Get one watch")

                post("/modify") {
                    val id =
                        call.longPath("id")
                            ?: return@post call.respondError("invalid_id", HttpStatusCode.BadRequest)
                    val raw = call.receiveText()
                    val req =
                        try {
                            watchJson.decodeFromString<AvailabilityWatchUpdateRequest>(raw)
                        } catch (e: Exception) {
                            return@post call.respondError("invalid_body", HttpStatusCode.BadRequest, e.message)
                        }
                    val parsed =
                        when (val mapped = AvailabilityWatchRequestMapper.parseUpdate(req)) {
                            is WatchRequestMapping.Invalid ->
                                return@post call.respondError(
                                    mapped.error,
                                    HttpStatusCode.BadRequest,
                                    mapped.detail,
                                )
                            is WatchRequestMapping.Valid -> mapped.value
                        }
                    val updated =
                        try {
                            watchService.update(
                                id,
                                targets = parsed.targets,
                                campsiteFilters = req.campsiteFilters,
                                startDate = parsed.dateWindow?.startDate,
                                endDate = parsed.dateWindow?.endDate,
                                cadenceSec = req.cadenceSec,
                                triggerKinds = req.triggerKinds,
                                triggerConfig = req.triggerConfig,
                                stopWhenTriggered = req.stopWhenTriggered,
                                status = parsed.status,
                            )
                        } catch (e: AvailabilityWatchValidationException) {
                            return@post call.respondError(e.error, HttpStatusCode.BadRequest, e.message)
                        }
                    if (updated == null) return@post call.respondError("not_found", HttpStatusCode.NotFound)
                    call.respondJson(watchMapper.response(updated))
                }.describeApi("availability", "Modify a watch")

                post("/delete") {
                    val id =
                        call.longPath("id")
                            ?: return@post call.respondError("invalid_id", HttpStatusCode.BadRequest)
                    if (watchService.delete(id)) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respondError("not_found", HttpStatusCode.NotFound)
                    }
                }.describeApi("availability", "Delete a watch")
            }
        }
    }
}

private suspend inline fun <reified T> ApplicationCall.respondJson(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(watchJson.encodeToString(body), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) = respondApiError(error = error, status = status, detail = detail)
