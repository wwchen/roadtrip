package ca.floo.roadtrip.route.api.availability

import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.model.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.route.common.describeApi
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
private const val MAX_LIST_LIMIT = 500

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
                val poiId = call.request.queryParameters["poi_id"]?.toLongOrNull()
                val campsiteId = call.request.queryParameters["campsite_id"]?.toLongOrNull()
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT)
                val offset =
                    call.request.queryParameters["offset"]
                        ?.toIntOrNull()
                        ?.coerceAtLeast(0) ?: 0
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
                        call.parameters["id"]?.toLongOrNull()
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
                        call.parameters["id"]?.toLongOrNull()
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
                        call.parameters["id"]?.toLongOrNull()
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
) {
    val payload = ApiErrorSchema(error = error, detail = detail)
    respondText(watchJson.encodeToString(payload), ContentType.Application.Json, status)
}
