package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapCell
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapGroup
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapResponse
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapRow
import ca.floo.roadtrip.models.api.AvailabilityWatchListResponse
import ca.floo.roadtrip.models.api.AvailabilityWatchResponse
import ca.floo.roadtrip.models.api.AvailabilityWatchSchema
import ca.floo.roadtrip.models.api.AvailabilityWatchTargetSchema
import ca.floo.roadtrip.models.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.models.api.ReservableSchema
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo.Watch
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.availability.WatchScopeResolver
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
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.LocalDate

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
    ctx: DSLContext,
    watchService: ca.floo.roadtrip.service.availability.AvailabilityWatchService,
    alertDispatcher: ca.floo.roadtrip.service.availability.WatchAlertDispatcher,
    notifyScope: CoroutineScope,
) {
    val watches = AvailabilityWatchRepo(ctx)
    val reservablesRepo = ReservableRepo(ctx)
    val availability = AvailabilityRepo(ctx)
    val scopeResolver = WatchScopeResolver(reservablesRepo)

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
            queryParameter<Long>("reservable_id") { description = "Filter to watches scoped to this reservable." }
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
        val reservableId = call.request.queryParameters["reservable_id"]?.toLongOrNull()
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT)
        val offset =
            call.request.queryParameters["offset"]
                ?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
        val rows = watches.list(status, poiId, reservableId, limit, offset)
        val total = watches.count(status, poiId, reservableId)
        call.respondJson(
            AvailabilityWatchListResponse(
                total = total,
                limit = limit,
                offset = offset,
                watches = rows.map { it.toSchema(reservablesRepo) },
            ),
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
        call.respondJson(AvailabilityWatchResponse(watch.toSchema(reservablesRepo)))
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
        val resolved =
            when (val r = resolveCreateScope(req, reservablesRepo)) {
                is ResolveResult.Err -> return@post call.respondError(r.error, HttpStatusCode.BadRequest, r.detail)
                is ResolveResult.Ok -> r
            }
        val err = validateCreateBody(req)
        if (err != null) return@post call.respondError(err.first, HttpStatusCode.BadRequest, err.second)
        val dateWindow =
            parseDateWindow(req.startDate, req.endDate)
                ?: return@post call.respondError("invalid_date_window", HttpStatusCode.BadRequest, "end_date must be after start_date")
        val watch =
            watchService.create(
                AvailabilityWatchRepo.CreateInput(
                    targets = resolved.targets,
                    reservableFilters = req.reservableFilters,
                    startDate = dateWindow.first,
                    endDate = dateWindow.second,
                    cadenceSec = req.cadenceSec,
                    triggerKinds = req.triggerKinds,
                    triggerConfig = req.triggerConfig,
                    stopWhenTriggered = req.stopWhenTriggered,
                ),
            )
        scheduleInitialNotify(watch)
        call.respondJson(AvailabilityWatchResponse(watch.toSchema(reservablesRepo)), HttpStatusCode.Created)
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
        val err = validateUpdateBody(req)
        if (err != null) return@patch call.respondError(err.first, HttpStatusCode.BadRequest, err.second)
        val status =
            req.status?.let {
                WatchStatus.parse(it)
                    ?: return@patch call.respondError("invalid_status", HttpStatusCode.BadRequest, "status must be active, paused, or done")
            }
        val dateWindow =
            when {
                (req.startDate == null) xor (req.endDate == null) ->
                    return@patch call.respondError(
                        "invalid_date_window",
                        HttpStatusCode.BadRequest,
                        "start_date and end_date must be updated together",
                    )
                req.startDate != null && req.endDate != null ->
                    parseDateWindow(req.startDate, req.endDate)
                        ?: return@patch call.respondError(
                            "invalid_date_window",
                            HttpStatusCode.BadRequest,
                            "end_date must be after start_date",
                        )
                else -> null
            }
        val updateTargets =
            when (val r = resolveUpdateScope(req)) {
                is ResolveResult.Err -> return@patch call.respondError(r.error, HttpStatusCode.BadRequest, r.detail)
                is ResolveResult.Ok -> r.targets
                null -> null
            }
        val updated =
            watchService.update(
                id,
                AvailabilityWatchRepo.UpdateInput(
                    targets = updateTargets,
                    reservableFilters = req.reservableFilters,
                    startDate = dateWindow?.first,
                    endDate = dateWindow?.second,
                    cadenceSec = req.cadenceSec,
                    triggerKinds = req.triggerKinds,
                    triggerConfig = req.triggerConfig,
                    stopWhenTriggered = req.stopWhenTriggered,
                    status = status,
                ),
            )
        if (updated == null) return@patch call.respondError("not_found", HttpStatusCode.NotFound)
        scheduleInitialNotify(updated)
        call.respondJson(AvailabilityWatchResponse(updated.toSchema(reservablesRepo)))
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

    get("/api/availability/watches/{id}/heatmap", {
        tags = listOf("availability")
        summary = "(child reservable × date) heatmap of latest snapshot statuses for a watch"
        request {
            pathParameter<Long>("id") { description = "Watch id." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityWatchHeatmapResponse> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondError("invalid_id", HttpStatusCode.BadRequest)
        val watch =
            watches.findById(id)
                ?: return@get call.respondError("not_found", HttpStatusCode.NotFound)

        val children = scopeResolver.resolve(watch)
        val dates = datesInWindow(watch.startDate, watch.endDate)
        val cells = availability.readCurrent(children.map { it.id }, dates)
        val cellsByPair = cells.associateBy { it.reservableId to it.targetDate }

        val dateStrings = dates.map { it.toString() }
        val rowsByLoop = LinkedHashMap<String?, MutableList<AvailabilityWatchHeatmapRow>>()
        for (r in children.sortedWith(
            compareBy<Reservable, String?>(nullsLast()) {
                it.loop
            }.thenBy { it.name ?: "" }.thenBy { it.identity.vendorId },
        )) {
            val rowCells =
                dates.map { d ->
                    val cell = cellsByPair[r.id to d]
                    AvailabilityWatchHeatmapCell(
                        targetDate = d.toString(),
                        status = cell?.status,
                        available = cell?.available,
                        observedAt = cell?.observedAt?.toString(),
                    )
                }
            val key = r.loop?.takeIf { it.isNotBlank() }
            rowsByLoop.getOrPut(key) { mutableListOf() } +=
                AvailabilityWatchHeatmapRow(
                    reservableId = r.id,
                    name = r.name,
                    cells = rowCells,
                )
        }

        val groups =
            rowsByLoop.entries.map { (loop, rows) ->
                AvailabilityWatchHeatmapGroup(loop = loop, rows = rows)
            }
        call.respondJson(
            AvailabilityWatchHeatmapResponse(
                watchId = watch.id,
                dates = dateStrings,
                groups = groups,
            ),
        )
    }
}

private sealed class ResolveResult {
    data class Ok(
        val targets: List<AvailabilityWatchTargetRepo.TargetInput>,
    ) : ResolveResult()

    data class Err(
        val error: String,
        val detail: String?,
    ) : ResolveResult()
}

/**
 * Validates a `targets` array: it must be non-empty, and each target must
 * set exactly one of `poi_id`/`reservable_id`. Shared by create and update so
 * both reject malformed target sets with a clean 400 `invalid_scope` instead
 * of letting bad input reach the service layer.
 */
private fun validateTargets(targets: List<AvailabilityWatchTargetSchema>): ResolveResult {
    if (targets.isEmpty()) return ResolveResult.Err("invalid_scope", "targets must be non-empty")
    val resolved = mutableListOf<AvailabilityWatchTargetRepo.TargetInput>()
    for (t in targets) {
        if ((t.poiId == null) == (t.reservableId == null)) {
            return ResolveResult.Err("invalid_scope", "each target must set exactly one of poi_id/reservable_id")
        }
        resolved += AvailabilityWatchTargetRepo.TargetInput(poiId = t.poiId, reservableId = t.reservableId)
    }
    return ResolveResult.Ok(resolved)
}

/**
 * Builds the target list for create/update from either the preferred
 * `targets` array or the legacy single-scope fields (`poi_id`,
 * `reservable_id`) — exactly one of the two shapes must be
 * present. Legacy fields are sugar for a one-element `targets` list so the
 * existing calendar UI keeps working unmodified.
 */
private fun resolveCreateScope(
    req: AvailabilityWatchCreateRequest,
    reservablesRepo: ReservableRepo,
): ResolveResult {
    val legacyKeysSet = listOf(req.poiId, req.reservableId).count { it != null }
    val hasTargets = req.targets != null
    if (hasTargets && legacyKeysSet > 0) {
        return ResolveResult.Err("invalid_scope", "specify either targets or poi_id/reservable_id, not both")
    }
    if (hasTargets) {
        return validateTargets(req.targets!!)
    }
    if (legacyKeysSet != 1) {
        return ResolveResult.Err("invalid_scope", "exactly one of targets, poi_id, or reservable_id must be set")
    }
    return ResolveResult.Ok(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = req.poiId, reservableId = req.reservableId)))
}

/**
 * Same targets validation as [resolveCreateScope], for PATCH. A request with
 * no `targets` field means "leave the target set untouched" (returns null,
 * distinct from an empty list). When `targets` is present, it goes through
 * the same [validateTargets] check as create, so malformed target sets
 * return `Err` (400 `invalid_scope`) instead of reaching the service layer.
 */
private fun resolveUpdateScope(req: AvailabilityWatchUpdateRequest): ResolveResult? {
    if (req.targets != null) {
        return validateTargets(req.targets)
    }
    return null
}

private fun validateCreateBody(req: AvailabilityWatchCreateRequest): Pair<String, String?>? {
    // NULL cadence is valid: "no watch-level override, fall through". Only a
    // present-but-sub-5 value is rejected (mirrors the DB CHECK).
    if (req.cadenceSec != null && req.cadenceSec < 5) return "invalid_cadence" to "cadence_sec must be >= 5"
    if (req.triggerKinds.isEmpty()) return "invalid_triggers" to "trigger_kinds must be non-empty"
    return null
}

private fun validateUpdateBody(req: AvailabilityWatchUpdateRequest): Pair<String, String?>? {
    if (req.cadenceSec != null && req.cadenceSec < 5) return "invalid_cadence" to "cadence_sec must be >= 5"
    if (req.triggerKinds != null && req.triggerKinds.isEmpty()) return "invalid_triggers" to "trigger_kinds must be non-empty"
    return null
}

private fun parseDateWindow(
    startDate: String,
    endDate: String,
): Pair<LocalDate, LocalDate>? =
    runCatching {
        val start = LocalDate.parse(startDate)
        val end = LocalDate.parse(endDate)
        if (!end.isAfter(start)) return null
        start to end
    }.getOrNull()

private fun datesInWindow(
    startDate: LocalDate,
    endDate: LocalDate,
): List<LocalDate> = generateSequence(startDate) { d -> d.plusDays(1).takeIf { it.isBefore(endDate) } }.toList()

private fun Watch.toSchema(reservablesRepo: ReservableRepo): AvailabilityWatchSchema {
    val firstTarget = targets.firstOrNull()
    val singleReservable =
        firstTarget
            ?.reservableId
            ?.takeIf { targets.size == 1 }
            ?.let { reservablesRepo.findById(it) }
            ?.let { r ->
                ReservableSchema(
                    id = r.id,
                    type = r.identity.type.encode(),
                    vendor = r.identity.vendor,
                    vendorId = r.identity.vendorId,
                    name = r.name,
                    loop = r.loop,
                    siteType = r.siteType,
                    poiIds = emptyList(),
                    raw = r.raw,
                )
            }
    return AvailabilityWatchSchema(
        id = id,
        targets = targets.map { AvailabilityWatchTargetSchema(poiId = it.poiId, reservableId = it.reservableId) },
        poiId = firstTarget?.poiId,
        reservableId = firstTarget?.reservableId,
        reservable = singleReservable,
        reservableFilters = reservableFilters,
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        cadenceSec = cadenceSec,
        triggerKinds = triggerKinds,
        triggerConfig = triggerConfig,
        stopWhenTriggered = stopWhenTriggered,
        status = status.wireValue,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        lastRunAt = lastRun?.completedAt?.toString(),
        lastRunStatus = lastRun?.status,
        lastRunError = lastRun?.error,
    )
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
