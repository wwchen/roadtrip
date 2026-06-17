package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.Reservable
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapCell
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapGroup
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapResponse
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapRow
import ca.floo.roadtrip.models.api.AvailabilityWatchListResponse
import ca.floo.roadtrip.models.api.AvailabilityWatchResponse
import ca.floo.roadtrip.models.api.AvailabilityWatchSchema
import ca.floo.roadtrip.models.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.models.api.ReservableSchema
import ca.floo.roadtrip.repo.AvailabilityHeatmapRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo.Watch
import ca.floo.roadtrip.repo.ReservableRepo
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import java.time.LocalDate

private const val DEFAULT_LIST_LIMIT = 100
private const val MAX_LIST_LIMIT = 500

@OptIn(ExperimentalSerializationApi::class)
private val watchJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

fun Route.availabilityWatchRoutes(
    ctx: DSLContext,
    watchService: ca.floo.roadtrip.service.availability.AvailabilityWatchService,
) {
    val watches = AvailabilityWatchRepo(ctx)
    val reservables = ReservableRepo(ctx)
    val heatmaps = AvailabilityHeatmapRepo(ctx)

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
        val status = call.request.queryParameters["status"]
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
                watches = rows.map { it.toSchema() },
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
        call.respondJson(AvailabilityWatchResponse(watch.toSchema()))
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
        val removedField = removedWatchField(raw)
        if (removedField != null) {
            return@post call.respondError("removed_fields", HttpStatusCode.BadRequest, "$removedField is no longer supported")
        }
        val req =
            try {
                watchJson.decodeFromString<AvailabilityWatchCreateRequest>(raw)
            } catch (e: Exception) {
                return@post call.respondError("invalid_body", HttpStatusCode.BadRequest, e.message)
            }
        val resolved =
            when (val r = resolveCreateScope(req, reservables)) {
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
                    poiId = resolved.poiId,
                    reservableId = resolved.reservableId,
                    reservableFilters = req.reservableFilters,
                    startDate = dateWindow.first,
                    endDate = dateWindow.second,
                    cadenceSec = req.cadenceSec,
                    triggerKinds = req.triggerKinds,
                    triggerConfig = req.triggerConfig,
                    stopWhenTriggered = req.stopWhenTriggered,
                ),
            )
        call.respondJson(AvailabilityWatchResponse(watch.toSchema()), HttpStatusCode.Created)
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
        val removedField = removedWatchField(raw)
        if (removedField != null) {
            return@patch call.respondError("removed_fields", HttpStatusCode.BadRequest, "$removedField is no longer supported")
        }
        val req =
            try {
                watchJson.decodeFromString<AvailabilityWatchUpdateRequest>(raw)
            } catch (e: Exception) {
                return@patch call.respondError("invalid_body", HttpStatusCode.BadRequest, e.message)
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
        val updated =
            try {
                watchService.update(
                    id,
                    AvailabilityWatchRepo.UpdateInput(
                        reservableFilters = req.reservableFilters,
                        startDate = dateWindow?.first,
                        endDate = dateWindow?.second,
                        cadenceSec = req.cadenceSec,
                        triggerKinds = req.triggerKinds,
                        triggerConfig = req.triggerConfig,
                        stopWhenTriggered = req.stopWhenTriggered,
                        status = req.status,
                    ),
                )
            } catch (e: IllegalArgumentException) {
                return@patch call.respondError("invalid_status", HttpStatusCode.BadRequest, e.message)
            }
        if (updated == null) return@patch call.respondError("not_found", HttpStatusCode.NotFound)
        call.respondJson(AvailabilityWatchResponse(updated.toSchema()))
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
        if (watchService.delete(id)) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respondError("not_found", HttpStatusCode.NotFound)
        }
    }

    get("/api/availability/watches/{id}/heatmap", {
        tags = listOf("availability")
        summary = "(child reservable × target_date) heatmap of latest snapshot statuses for a watch"
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

        val children = resolveChildren(watch, reservables)
        val targetDates = datesInWindow(watch.startDate, watch.endDate)
        val cells = heatmaps.loadHeatmap(children.map { it.id }, targetDates)
        val cellsByPair = cells.associateBy { it.reservableId to it.targetDate }

        val targetDateStrings = targetDates.map { it.toString() }
        val rowsByLoop = LinkedHashMap<String?, MutableList<AvailabilityWatchHeatmapRow>>()
        for (r in children.sortedWith(
            compareBy<Reservable, String?>(nullsLast()) {
                it.loop
            }.thenBy { it.name ?: "" }.thenBy { it.rid.vendorId },
        )) {
            val rowCells =
                targetDates.map { d ->
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
                    reservableRid = r.rid.encode(),
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
                targetDates = targetDateStrings,
                groups = groups,
            ),
        )
    }
}

private sealed class ResolveResult {
    data class Ok(
        val poiId: Long?,
        val reservableId: Long?,
    ) : ResolveResult()

    data class Err(
        val error: String,
        val detail: String?,
    ) : ResolveResult()
}

private fun resolveCreateScope(
    req: AvailabilityWatchCreateRequest,
    reservables: ReservableRepo,
): ResolveResult {
    val scopeKeysSet = listOf(req.poiId, req.reservableId, req.reservableRid).count { it != null }
    if (scopeKeysSet != 1) {
        return ResolveResult.Err(
            "invalid_scope",
            "exactly one of poi_id, reservable_id, or reservable_rid must be set",
        )
    }
    if (req.reservableRid != null) {
        val parsed =
            ca.floo.roadtrip.models.ReservableId
                .parse(req.reservableRid)
                ?: return ResolveResult.Err(
                    "invalid_reservable_rid",
                    "could not parse reservable_rid '${req.reservableRid}'",
                )
        val resolvedReservable =
            reservables.findByRid(parsed)
                ?: return ResolveResult.Err(
                    "reservable_not_found",
                    "no reservable with rid ${req.reservableRid}",
                )
        return ResolveResult.Ok(poiId = null, reservableId = resolvedReservable.id)
    }
    return ResolveResult.Ok(poiId = req.poiId, reservableId = req.reservableId)
}

private fun validateCreateBody(req: AvailabilityWatchCreateRequest): Pair<String, String?>? {
    if (req.cadenceSec < 5) return "invalid_cadence" to "cadence_sec must be >= 5"
    if (req.triggerKinds.isEmpty()) return "invalid_triggers" to "trigger_kinds must be non-empty"
    return null
}

private fun removedWatchField(raw: String): String? =
    runCatching { watchJson.parseToJsonElement(raw).jsonObject }
        .getOrNull()
        ?.let { obj -> listOf("target_dates", "min_nights").firstOrNull { it in obj } }

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

private fun Watch.toSchema(): AvailabilityWatchSchema =
    AvailabilityWatchSchema(
        id = id,
        poiId = poiId,
        reservableId = reservableId,
        reservable =
            reservable?.let { r ->
                ReservableSchema(
                    rid = r.rid.encode(),
                    type = r.rid.type.encode(),
                    vendor = r.rid.vendor,
                    vendorId = r.rid.vendorId,
                    name = r.name,
                    loop = r.loop,
                    siteType = r.siteType,
                    poiIds = emptyList(),
                    raw = r.raw,
                )
            },
        reservableFilters = reservableFilters,
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        cadenceSec = cadenceSec,
        triggerKinds = triggerKinds,
        triggerConfig = triggerConfig,
        stopWhenTriggered = stopWhenTriggered,
        status = status,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

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

private fun resolveChildren(
    watch: AvailabilityWatchRepo.Watch,
    reservables: ReservableRepo,
): List<Reservable> {
    if (watch.reservableId != null) {
        val r = reservables.findById(watch.reservableId) ?: return emptyList()
        return listOf(r)
    }
    val poiId = watch.poiId ?: return emptyList()
    val all = reservables.findByPoi(poiId, type = ReservableType.parse("site"))
    val loops = collectStringFilter(watch.reservableFilters, "loop")
    val siteTypes = collectStringFilter(watch.reservableFilters, "site_type")
    return all.filter { r ->
        (loops.isEmpty() || (r.loop != null && loops.contains(r.loop))) &&
            (siteTypes.isEmpty() || (r.siteType != null && siteTypes.contains(r.siteType)))
    }
}

private fun collectStringFilter(
    filters: kotlinx.serialization.json.JsonObject,
    key: String,
): Set<String> {
    val value = filters[key] ?: return emptySet()
    return when (value) {
        is JsonPrimitive -> if (value.isString) setOf(value.content) else emptySet()
        is JsonArray ->
            value
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .toSet()
        else -> emptySet()
    }
}
