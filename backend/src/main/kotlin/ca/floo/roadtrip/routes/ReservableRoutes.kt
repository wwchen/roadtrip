package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.Reservable
import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.PoiReservablesResponseSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityLogListResponseSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityLogSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityPollerCreateRequestSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityPollerListResponseSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityPollerPatchRequestSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityPollerResponseSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityPollerSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityQueryRequestSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityRunListResponseSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityRunSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityScopeSchema
import ca.floo.roadtrip.models.api.ReservableDetailResponseSchema
import ca.floo.roadtrip.models.api.ReservableSchema
import ca.floo.roadtrip.models.api.ReservablesResponseSchema
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.ReservableAvailabilityLogRepo
import ca.floo.roadtrip.repo.ReservableAvailabilityPollerRepo
import ca.floo.roadtrip.repo.ReservableAvailabilityRunRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.api.BadAvailabilityIntent
import ca.floo.roadtrip.service.api.ReservableAvailabilityIntentService
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.patch
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import java.time.LocalDate

@OptIn(ExperimentalSerializationApi::class)
private val reservableRoutesJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

fun Route.reservableRoutes(
    ctx: DSLContext,
    bookingProviders: BookingProviderRegistry? = null,
    providerRefs: CampsiteProviderRepo? = null,
    availabilityLogs: ReservableAvailabilityLogRepo? = null,
) {
    val reservables = ReservableRepo(ctx)
    val pois = PoiServingRepo(ctx)
    val pollers = ReservableAvailabilityPollerRepo(ctx)
    val runs = ReservableAvailabilityRunRepo(ctx)
    val logs = availabilityLogs ?: ReservableAvailabilityLogRepo(ctx)
    val intentService =
        if (bookingProviders != null && providerRefs != null) {
            ReservableAvailabilityIntentService(
                providerRefs = providerRefs,
                bookingProviders = bookingProviders,
                reservables = reservables,
                pois = pois,
                availabilityLogs = logs,
                runs = runs,
            )
        } else {
            null
        }

    get("/api/reservables", {
        tags = listOf("reservable")
        summary = "Search reservables"
        description =
            "Search active reservables across ReservableSchema fields. Multiple " +
            "values for one field are ORed; separate fields are ANDed. Values " +
            "can be repeated or comma-separated, e.g. `?type=site&vendor=recgov" +
            "&vendor_id=330257,330258`."
        request {
            queryParameter<String>("rid") { description = "Composite id `{type}:{vendor}:{vendor_id}`." }
            queryParameter<String>("type") { description = "Reservable type, e.g. `site`." }
            queryParameter<String>("vendor") { description = "Vendor id, e.g. `recgov` or `aspira_pc`." }
            queryParameter<String>("vendor_id") { description = "Vendor-native reservable id." }
            queryParameter<String>("name") { description = "Exact reservable display name." }
            queryParameter<String>("loop") { description = "Exact loop value." }
            queryParameter<String>("site_type") { description = "Exact site type value." }
            queryParameter<String>("raw") { description = "JSON object contained by the raw JSONB payload." }
            queryParameter<Int>("limit") { description = "Page size, default 100, max 500." }
            queryParameter<Int>("offset") { description = "Page offset, default 0." }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "Matching reservables plus total before pagination."
                body<ReservablesResponseSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed filter, limit, offset, rid, type, or raw JSON."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val filters =
            try {
                call.reservableSearchFilters()
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }
        val limit =
            try {
                call.intQuery("limit", default = 100, min = 1, max = 500)
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }
        val offset =
            try {
                call.intQuery("offset", default = 0, min = 0, max = Int.MAX_VALUE)
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }

        val rows = reservables.search(filters, limit, offset)
        val poiIdsByReservable = reservables.poiIdsForReservables(rows.map { it.id })

        call.respondReservableJson(
            ReservablesResponseSchema(
                total = reservables.countSearch(filters),
                limit = limit,
                offset = offset,
                reservables = rows.map { it.toSchema(poiIdsByReservable[it.id].orEmpty()) },
            ),
        )
    }

    post("/api/reservables/availability/query", {
        tags = listOf("reservable")
        summary = "Execute an intent-based reservable availability query"
        description =
            "Translates a POI or reservable intent into concrete reservable availability fetches. " +
            "Each fetch appends reservable_availability_log rows."
        response {
            code(HttpStatusCode.OK) {
                description = "Derived availability results plus run/log counts."
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed scope, dates, filters, days, or min_nights."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val service =
            intentService
                ?: return@post call.respondReservableError("availability_query_unavailable", HttpStatusCode.NotImplemented)
        val input =
            try {
                reservableRoutesJson.decodeFromString<ReservableAvailabilityQueryRequestSchema>(
                    call.receiveText().ifBlank { "{}" },
                )
            } catch (e: Exception) {
                return@post call.respondReservableError("bad_json", HttpStatusCode.BadRequest, e.message)
            }
        try {
            call.respondReservableJson(service.execute(input).response)
        } catch (e: BadAvailabilityIntent) {
            call.respondReservableError(
                e.error,
                if (e.error == "not_found") HttpStatusCode.NotFound else HttpStatusCode.BadRequest,
                e.message,
            )
        }
    }

    get("/api/reservables/availability/pollers", {
        tags = listOf("reservable")
        summary = "List reservable availability pollers"
        description = "Lists persisted intent-based availability poller jobs."
    }) {
        val limit =
            try {
                call.intQuery("limit", default = 100, min = 1, max = 500)
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }
        val offset =
            try {
                call.intQuery("offset", default = 0, min = 0, max = Int.MAX_VALUE)
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }
        call.respondReservableJson(
            ReservableAvailabilityPollerListResponseSchema(
                pollers = pollers.list(limit, offset).map { it.toSchema() },
            ),
        )
    }

    get("/api/reservables/availability/pollers/{id}", {
        tags = listOf("reservable")
        summary = "Fetch one reservable availability poller"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondReservableError("bad_id", HttpStatusCode.BadRequest)
        val poller = pollers.get(id) ?: return@get call.respondReservableError("not_found", HttpStatusCode.NotFound)
        call.respondReservableJson(ReservableAvailabilityPollerResponseSchema(poller = poller.toSchema()))
    }

    post("/api/reservables/availability/pollers", {
        tags = listOf("reservable")
        summary = "Create an intent-based reservable availability poller"
        description =
            "Stores one poller intent and runs the first poll immediately so " +
            "reservable_availability_log has rows for the created job."
    }) {
        val input =
            try {
                reservableRoutesJson.decodeFromString<ReservableAvailabilityPollerCreateRequestSchema>(
                    call.receiveText().ifBlank { "{}" },
                )
            } catch (e: Exception) {
                return@post call.respondReservableError("bad_json", HttpStatusCode.BadRequest, e.message)
            }
        createAvailabilityPoller(
            call = call,
            input = input,
            reservables = reservables,
            pollers = pollers,
            runs = runs,
            intentService = intentService,
        )
    }

    patch("/api/reservables/availability/pollers/{id}", {
        tags = listOf("reservable")
        summary = "Patch one reservable availability poller"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@patch call.respondReservableError("bad_id", HttpStatusCode.BadRequest)
        val input =
            try {
                reservableRoutesJson.decodeFromString<ReservableAvailabilityPollerPatchRequestSchema>(
                    call.receiveText().ifBlank { "{}" },
                )
            } catch (e: Exception) {
                return@patch call.respondReservableError("bad_json", HttpStatusCode.BadRequest, e.message)
            }
        val targetDates =
            try {
                input.targetDates?.map(LocalDate::parse)
            } catch (e: Exception) {
                return@patch call.respondReservableError("bad_target_dates", HttpStatusCode.BadRequest, "target_dates must be YYYY-MM-DD")
            }
        if (input.status != null && input.status !in setOf("active", "paused", "done")) {
            return@patch call.respondReservableError("bad_status", HttpStatusCode.BadRequest)
        }
        val validation =
            validatePollerInput(
                cadence = input.cadence ?: 5,
                minNights = 1,
                targetDates = targetDates ?: listOf(LocalDate.now()),
                triggerActions = input.triggerActions,
                requireActions = false,
            )
        if (validation != null) return@patch call.respondReservableError(validation.first, HttpStatusCode.BadRequest, validation.second)
        val updated =
            pollers.patch(
                id,
                ReservableAvailabilityPollerRepo.PatchInput(
                    status = input.status,
                    cadenceSec = input.cadence,
                    targetDates = targetDates,
                    triggerActions = input.triggerActions,
                    stopWhenTriggered = input.stopWhenTriggered,
                ),
            ) ?: return@patch call.respondReservableError("not_found", HttpStatusCode.NotFound)
        call.respondReservableJson(ReservableAvailabilityPollerResponseSchema(poller = updated.toSchema()))
    }

    delete("/api/reservables/availability/pollers/{id}", {
        tags = listOf("reservable")
        summary = "Delete one reservable availability poller"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respondReservableError("bad_id", HttpStatusCode.BadRequest)
        if (!pollers.delete(id)) return@delete call.respondReservableError("not_found", HttpStatusCode.NotFound)
        call.respondReservableJson(mapOf("ok" to true))
    }

    get("/api/reservables/availability/logs", {
        tags = listOf("reservable")
        summary = "List reservable availability log rows"
    }) {
        val limit =
            try {
                call.intQuery("limit", default = 100, min = 1, max = 500)
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }
        val filters =
            try {
                ReservableAvailabilityLogRepo.LogFilters(
                    runId = call.longQuery("run_id"),
                    pollerId = call.longQuery("poller_id"),
                    rid = call.request.queryParameters["rid"],
                    targetDate = call.dateQuery("target_date"),
                    limit = limit,
                )
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }
        call.respondReservableJson(
            ReservableAvailabilityLogListResponseSchema(
                logs = logs.list(filters).map { it.toSchema() },
            ),
        )
    }

    get("/api/reservables/availability/runs", {
        tags = listOf("reservable")
        summary = "List reservable availability query and poller runs"
    }) {
        val limit =
            try {
                call.intQuery("limit", default = 100, min = 1, max = 500)
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }
        val offset =
            try {
                call.intQuery("offset", default = 0, min = 0, max = Int.MAX_VALUE)
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }
        val pollerId =
            try {
                call.longQuery("poller_id")
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }
        call.respondReservableJson(
            ReservableAvailabilityRunListResponseSchema(
                runs = runs.list(limit, offset, pollerId).map { it.toSchema() },
            ),
        )
    }

    get("/api/reservables/availability/runs/{id}", {
        tags = listOf("reservable")
        summary = "Fetch one reservable availability run"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondReservableError("bad_id", HttpStatusCode.BadRequest)
        val run = runs.get(id) ?: return@get call.respondReservableError("not_found", HttpStatusCode.NotFound)
        call.respondReservableJson(run.toSchema())
    }

    get("/api/reservable/{rid}", {
        tags = listOf("reservable")
        summary = "Single reservable catalog detail"
        description =
            "Returns one reservable by composite id, e.g. site:recgov:330257. " +
            "The response includes active POI ids linked through reservable_pois."
        request {
            pathParameter<String>("rid") { description = "{type}:{vendor}:{vendor_id}" }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "Reservable detail plus linked POI ids."
                body<ReservableDetailResponseSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed composite reservable id."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) {
                description = "No reservable with that composite id."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val rid =
            call.parameters["rid"]
                ?.let(ReservableId::parse)
                ?: return@get call.respondReservableError("bad_rid", HttpStatusCode.BadRequest)
        val row =
            reservables.findByRid(rid)
                ?: return@get call.respondReservableError("not_found", HttpStatusCode.NotFound)

        val poiIds = reservables.poiIdsForReservable(row.id)

        call.respondReservableJson(
            ReservableDetailResponseSchema(
                reservable = row.toSchema(poiIds),
                poiIds = poiIds,
            ),
        )
    }

    post("/api/reservable/{rid}/availability/poller", {
        tags = listOf("reservable")
        summary = "Create a concrete-reservable availability poller"
        description =
            "Concrete-RID shortcut for POST /api/reservables/availability/pollers."
    }) {
        val rid =
            call.parameters["rid"]
                ?.let(ReservableId::parse)
                ?: return@post call.respondReservableError("bad_rid", HttpStatusCode.BadRequest)
        val body = call.receiveText().ifBlank { "{}" }
        val base =
            try {
                reservableRoutesJson.decodeFromString<ReservableAvailabilityPollerCreateRequestSchema>(body)
            } catch (e: Exception) {
                return@post call.respondReservableError("bad_json", HttpStatusCode.BadRequest, e.message)
            }
        val rewritten =
            base.copy(
                scope = ReservableAvailabilityScopeSchema(rid = rid.encode()),
            )
        createAvailabilityPoller(
            call = call,
            input = rewritten,
            reservables = reservables,
            pollers = pollers,
            runs = runs,
            intentService = intentService,
        )
    }

    get("/api/poi/{id}/reservables", {
        tags = listOf("reservable")
        summary = "Reservables linked to a POI"
        description =
            "Lists reservables at one active POI. `type` defaults to `site`; " +
            "future reservable types can be added without changing the response envelope."
        request {
            pathParameter<Long>("id") { description = "pois.id primary key" }
            queryParameter<String>("type") { description = "Reservable type, defaults to site." }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "Reservables linked to the POI, plus total_at_poi."
                body<PoiReservablesResponseSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed POI id or unknown reservable type."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) {
                description = "No active POI with that id."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val poiId =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondReservableError("bad_id", HttpStatusCode.BadRequest)
        val type =
            parseReservableType(call.request.queryParameters["type"])
                ?: return@get call.respondReservableError("bad_type", HttpStatusCode.BadRequest)

        pois.fetchPoiById(poiId)
            ?: return@get call.respondReservableError("not_found", HttpStatusCode.NotFound)

        val rows = reservables.findByPoi(poiId, type)
        call.respondReservableJson(
            PoiReservablesResponseSchema(
                poiId = poiId,
                type = type.encode(),
                totalAtPoi = reservables.countByPoi(poiId, type),
                reservables = rows.map { it.toSchema(listOf(poiId)) },
            ),
        )
    }
}

private class BadReservableQuery(
    val error: String,
    val detail: String? = null,
) : IllegalArgumentException(detail)

private fun parseReservableType(raw: String?): ReservableType? =
    if (raw.isNullOrBlank()) {
        ReservableType.SITE
    } else {
        ReservableType.parse(raw.trim())
    }

private fun ApplicationCall.reservableSearchFilters(): ReservableRepo.SearchFilters =
    ReservableRepo.SearchFilters(
        rids =
            queryValues("rid")
                .map { raw -> ReservableId.parse(raw) ?: throw BadReservableQuery("bad_rid", raw) },
        types =
            queryValues("type")
                .map { raw -> ReservableType.parse(raw) ?: throw BadReservableQuery("bad_type", raw) },
        vendors = queryValues("vendor"),
        vendorIds = queryValues("vendor_id", "vendorId"),
        names = queryValues("name"),
        loops = queryValues("loop"),
        siteTypes = queryValues("site_type", "siteType"),
        rawContainsJson =
            queryValues("raw")
                .map { raw ->
                    try {
                        val parsed = reservableRoutesJson.parseToJsonElement(raw)
                        reservableRoutesJson.encodeToString(JsonElement.serializer(), parsed)
                    } catch (e: Exception) {
                        throw BadReservableQuery("bad_raw", e.message)
                    }
                },
    )

private fun ApplicationCall.intQuery(
    name: String,
    default: Int,
    min: Int,
    max: Int,
): Int {
    val raw = request.queryParameters[name] ?: return default
    val value = raw.toIntOrNull() ?: throw BadReservableQuery("bad_$name", "$name must be an integer")
    if (value < min || value > max) {
        throw BadReservableQuery("bad_$name", "$name must be between $min and $max")
    }
    return value
}

private fun ApplicationCall.longQuery(name: String): Long? {
    val raw = request.queryParameters[name] ?: return null
    return raw.toLongOrNull() ?: throw BadReservableQuery("bad_$name", "$name must be an integer")
}

private fun ApplicationCall.dateQuery(name: String): LocalDate? {
    val raw = request.queryParameters[name] ?: return null
    return runCatching { LocalDate.parse(raw) }
        .getOrElse { throw BadReservableQuery("bad_$name", "$name must be YYYY-MM-DD") }
}

private fun ApplicationCall.queryValues(vararg names: String): List<String> =
    names
        .flatMap { name -> request.queryParameters.getAll(name).orEmpty() }
        .flatMap { value -> value.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

private fun Reservable.toSchema(poiIds: List<Long> = emptyList()): ReservableSchema =
    ReservableSchema(
        rid = rid.encode(),
        type = rid.type.encode(),
        vendor = rid.vendor,
        vendorId = rid.vendorId,
        name = name,
        loop = loop,
        siteType = siteType,
        poiIds = poiIds,
        raw = raw,
    )

private suspend fun createAvailabilityPoller(
    call: ApplicationCall,
    input: ReservableAvailabilityPollerCreateRequestSchema,
    reservables: ReservableRepo,
    pollers: ReservableAvailabilityPollerRepo,
    runs: ReservableAvailabilityRunRepo,
    intentService: ReservableAvailabilityIntentService?,
) {
    val service =
        intentService
            ?: return call.respondReservableError("availability_query_unavailable", HttpStatusCode.NotImplemented)
    val targetDates =
        try {
            input.targetDates.map(LocalDate::parse)
        } catch (e: Exception) {
            return call.respondReservableError(
                "bad_target_dates",
                HttpStatusCode.BadRequest,
                "target_dates must be YYYY-MM-DD",
            )
        }
    val scope =
        try {
            input.scope.toPollerScope(reservables)
        } catch (e: BadReservableQuery) {
            return call.respondReservableError(
                e.error,
                if (e.error == "not_found") HttpStatusCode.NotFound else HttpStatusCode.BadRequest,
                e.detail,
            )
        }
    val validation = validatePollerInput(input.cadence, input.minNights, targetDates, input.triggerActions)
    if (validation != null) return call.respondReservableError(validation.first, HttpStatusCode.BadRequest, validation.second)

    val poller =
        pollers.create(
            ReservableAvailabilityPollerRepo.CreateInput(
                scope = scope,
                reservableFilters = service.filtersToJson(input.reservableFilters),
                targetDates = targetDates,
                minNights = input.minNights,
                cadenceSec = input.cadence,
                triggerActions = input.triggerActions,
                stopWhenTriggered = input.stopWhenTriggered,
            ),
        )
    val initial =
        try {
            val query =
                service.pollerIntent(
                    scope = poller.scope.toApiScope(),
                    filters = service.filtersFromJson(poller.reservableFilters),
                    targetDates = poller.targetDates,
                    minNights = poller.minNights,
                    force = input.force,
                )
            service.execute(query, sourceKind = "poller", pollerId = poller.id).run
        } catch (e: BadAvailabilityIntent) {
            val failedRun =
                runs.start(
                    sourceKind = "poller",
                    pollerId = poller.id,
                    intentPayload = buildJsonObject { put("error", e.error) },
                )
            runs.fail(
                failedRun.id,
                e.message ?: e.error,
            )
        }

    call.respondReservableJson(
        ReservableAvailabilityPollerResponseSchema(
            poller = pollers.get(poller.id)!!.toSchema(),
            initialRun = initial.toSchema(),
        ),
        HttpStatusCode.Created,
    )
}

private fun validatePollerInput(
    cadence: Int,
    minNights: Int,
    targetDates: List<LocalDate>,
    triggerActions: JsonArray?,
    requireActions: Boolean = true,
): Pair<String, String?>? =
    when {
        cadence < 5 -> "bad_cadence" to "cadence must be at least 5 seconds"
        minNights !in 1..31 -> "bad_min_nights" to "min_nights must be between 1 and 31"
        targetDates.isEmpty() -> "bad_target_dates" to "target_dates must not be empty"
        requireActions && (triggerActions == null || triggerActions.isEmpty()) ->
            "bad_trigger_actions" to "trigger_actions must not be empty"
        triggerActions != null && triggerActions.isEmpty() ->
            "bad_trigger_actions" to "trigger_actions must not be empty"
        else -> null
    }

private fun ReservableAvailabilityScopeSchema.toPollerScope(reservables: ReservableRepo): ReservableAvailabilityPollerRepo.Scope {
    val hasPoi = poiId != null
    val hasRid = !rid.isNullOrBlank()
    if (hasPoi == hasRid) throw BadReservableQuery("bad_scope", "exactly one of scope.poi_id or scope.rid is required")
    poiId?.let {
        if (it <= 0) throw BadReservableQuery("bad_poi_id", "scope.poi_id must be positive")
        return ReservableAvailabilityPollerRepo.Scope(poiId = it)
    }

    val parsed = ReservableId.parse(rid!!) ?: throw BadReservableQuery("bad_rid", rid)
    val row = reservables.findByRid(parsed) ?: throw BadReservableQuery("not_found", rid)
    return ReservableAvailabilityPollerRepo.Scope(reservableId = row.id, reservableRid = parsed)
}

private fun ReservableAvailabilityPollerRepo.Scope.toApiScope(): ReservableAvailabilityScopeSchema =
    poiId
        ?.let { ReservableAvailabilityScopeSchema(poiId = it) }
        ?: ReservableAvailabilityScopeSchema(rid = requireNotNull(reservableRid).encode())

private fun ReservableAvailabilityPollerRepo.Poller.toSchema(): ReservableAvailabilityPollerSchema =
    ReservableAvailabilityPollerSchema(
        id = id,
        scope = scope.toApiScope(),
        reservableFilters = reservableFilters,
        targetDates = targetDates.map { it.toString() },
        minNights = minNights,
        cadence = cadenceSec,
        triggerActions = triggerActions,
        stopWhenTriggered = stopWhenTriggered,
        status = status,
        lastCheckedAt = lastCheckedAt?.toString(),
        lastTriggeredAt = lastTriggeredAt?.toString(),
        nextPollAfter = nextPollAfter.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun ReservableAvailabilityRunRepo.Run.toSchema(): ReservableAvailabilityRunSchema =
    ReservableAvailabilityRunSchema(
        id = id,
        sourceKind = sourceKind,
        pollerId = pollerId,
        status = status,
        candidateCount = candidateCount,
        logCount = logCount,
        error = error,
        startedAt = startedAt.toString(),
        completedAt = completedAt?.toString(),
    )

private fun ReservableAvailabilityLogRepo.LogRow.toSchema(): ReservableAvailabilityLogSchema =
    ReservableAvailabilityLogSchema(
        id = id,
        runId = runId,
        reservableRid = reservableRid,
        observedAt = observedAt.toString(),
        targetDate = targetDate.toString(),
        status = status,
        available = available,
        dayPayload = dayPayload,
    )

private suspend fun ApplicationCall.respondReservableError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    respondReservableJson(ApiErrorSchema(error = error, detail = detail), status)
}

private suspend inline fun <reified T> ApplicationCall.respondReservableJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(reservableRoutesJson.encodeToString(value), ContentType.Application.Json, status)
}
