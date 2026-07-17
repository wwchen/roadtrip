package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.models.api.AvailabilityWatchListResponse
import ca.floo.roadtrip.models.api.AvailabilityWatchResponse
import ca.floo.roadtrip.models.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo.Watch
import ca.floo.roadtrip.service.availability.AvailabilityWatchApiMapper
import ca.floo.roadtrip.service.availability.AvailabilityWatchRequestMapper
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.AvailabilityWatchValidationException
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchInitialNotificationPolicy
import ca.floo.roadtrip.service.availability.WatchRequestMapping
import ca.floo.roadtrip.service.availability.WatchStatus
import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.patch
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private const val DEFAULT_LIST_LIMIT = 100
private const val MAX_LIST_LIMIT = 500

// Logger anchor for these top-level route functions (the file has no class of
// its own); keeps the category class-derived rather than a hardcoded string.
private object AvailabilityWatchRoutes

private val log = LoggerFactory.getLogger(AvailabilityWatchRoutes::class.java)

@OptIn(ExperimentalSerializationApi::class)
private val watchJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

internal fun Route.availabilityWatchRoutes(
    watches: AvailabilityWatchRepo,
    watchMapper: AvailabilityWatchApiMapper,
    watchService: AvailabilityWatchService,
    alertDispatcher: WatchAlertDispatcher,
    notifyScope: CoroutineScope,
) {
    // The "first message": on create/update, post the current window state to
    // Slack so an already-open site isn't stranded behind the edge-triggered
    // poller. Fire-and-forget, outside the mutation's transaction — it must
    // never block or fail the HTTP response. All gating (Slack configured,
    // slack_notify kind, active status) lives in dispatchInitial.
    fun scheduleInitialNotify(watch: Watch) {
        notifyScope.launch {
            runCatching { alertDispatcher.dispatchInitial(watch) }
                .onFailure { log.warn("initial Slack notify for watch {} failed", watch.id, it) }
        }
    }

    // The terminal "watch stopped" message on delete. Fire-and-forget on the
    // captured pre-delete watch (its scope is still resolvable), symmetric with
    // scheduleInitialNotify: it must never block or fail the HTTP response.
    fun scheduleStoppedNotify(watch: Watch) {
        notifyScope.launch {
            runCatching { alertDispatcher.dispatchStopped(watch) }
                .onFailure { log.warn("stopped Slack notify for watch {} failed", watch.id, it) }
        }
    }

    get("/api/availability/watches", {
        tags = listOf("availability")
        summary = "List availability watches"
        request {
            queryParameter<String>("status") { description = "active | paused | done" }
            queryParameter<Long>("poi_id") { description = "Filter to watches scoped to this POI." }
            queryParameter<Long>("campsite_id") { description = "Filter to watches scoped to this campsite." }
            queryParameter<Int>("limit") { description = "Page size, default 100, max 500." }
            queryParameter<Int>("offset") { description = "Page offset, default 0." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityWatchListResponse> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val status =
            call.request.queryParameters["status"]?.let {
                WatchStatus.parse(it)
                    ?: return@get call.respondError("invalid_status", HttpStatusCode.BadRequest, "status must be active, paused, or done")
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
    }

    get("/api/availability/watches/{id}", {
        tags = listOf("availability")
        summary = "Get one watch"
        request {
            pathParameter<Long>("id") { description = "Watch id." }
        }
        response {
            code(HttpStatusCode.OK) { body<AvailabilityWatchResponse> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondError("invalid_id", HttpStatusCode.BadRequest)
        val watch =
            watches.findById(id)
                ?: return@get call.respondError("not_found", HttpStatusCode.NotFound)
        call.respondJson(
            watchMapper.response(watch, includeCapabilities = true),
        )
    }

    post("/api/availability/watches", {
        tags = listOf("availability")
        summary = "Create a watch"
        request { body<AvailabilityWatchCreateRequest> { mediaTypes(ContentType.Application.Json) } }
        response {
            code(HttpStatusCode.Created) { body<AvailabilityWatchResponse> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val raw = call.receiveText()
        val req =
            try {
                watchJson.decodeFromString<AvailabilityWatchCreateRequest>(raw)
            } catch (e: Exception) {
                return@post call.respondError("invalid_body", HttpStatusCode.BadRequest, e.message)
            }
        val parsed =
            when (val mapped = AvailabilityWatchRequestMapper.parseCreate(req)) {
                is WatchRequestMapping.Invalid -> return@post call.respondError(mapped.error, HttpStatusCode.BadRequest, mapped.detail)
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
        scheduleInitialNotify(watch)
        call.respondJson(watchMapper.response(watch), HttpStatusCode.Created)
    }

    patch("/api/availability/watches/{id}", {
        tags = listOf("availability")
        summary = "Update a watch"
        request {
            pathParameter<Long>("id") { description = "Watch id." }
            body<AvailabilityWatchUpdateRequest> { mediaTypes(ContentType.Application.Json) }
        }
        response {
            code(HttpStatusCode.OK) { body<AvailabilityWatchResponse> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@patch call.respondError("invalid_id", HttpStatusCode.BadRequest)
        val raw = call.receiveText()
        val req =
            try {
                watchJson.decodeFromString<AvailabilityWatchUpdateRequest>(raw)
            } catch (e: Exception) {
                return@patch call.respondError("invalid_body", HttpStatusCode.BadRequest, e.message)
            }
        val parsed =
            when (val mapped = AvailabilityWatchRequestMapper.parseUpdate(req)) {
                is WatchRequestMapping.Invalid -> return@patch call.respondError(mapped.error, HttpStatusCode.BadRequest, mapped.detail)
                is WatchRequestMapping.Valid -> mapped.value
            }
        val previous =
            watches.findById(id)
                ?: return@patch call.respondError("not_found", HttpStatusCode.NotFound)
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
                return@patch call.respondError(e.error, HttpStatusCode.BadRequest, e.message)
            }
        if (updated == null) return@patch call.respondError("not_found", HttpStatusCode.NotFound)
        if (WatchInitialNotificationPolicy.shouldDispatchAfterUpdate(previous, updated)) scheduleInitialNotify(updated)
        call.respondJson(watchMapper.response(updated))
    }

    delete("/api/availability/watches/{id}", {
        tags = listOf("availability")
        summary = "Delete a watch"
        request {
            pathParameter<Long>("id") { description = "Watch id." }
        }
        response {
            code(HttpStatusCode.NoContent) { description = "Deleted." }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respondError("invalid_id", HttpStatusCode.BadRequest)
        // Capture the watch before deletion so the goodbye notification can
        // still resolve its scope; the row (and its poller links) are gone after.
        val watch = watches.findById(id)
        if (watchService.delete(id)) {
            watch?.let { scheduleStoppedNotify(it) }
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respondError("not_found", HttpStatusCode.NotFound)
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
