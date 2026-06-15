package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.Reservable
import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.PoiReservablesResponseSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityMonitorCreateRequestSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityMonitorListResponseSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityMonitorResponseSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityMonitorSchema
import ca.floo.roadtrip.models.api.ReservableDetailResponseSchema
import ca.floo.roadtrip.models.api.ReservableSchema
import ca.floo.roadtrip.models.api.ReservablesResponseSchema
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.ReservableAvailabilityMonitorRepo
import ca.floo.roadtrip.repo.ReservableRepo
import io.github.smiley4.ktorswaggerui.dsl.routing.get
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
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext

@OptIn(ExperimentalSerializationApi::class)
private val reservableRoutesJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

fun Route.reservableRoutes(ctx: DSLContext) {
    val reservables = ReservableRepo(ctx)
    val pois = PoiServingRepo(ctx)
    val monitors = ReservableAvailabilityMonitorRepo(ctx)

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

        call.respondReservableJson(
            ReservablesResponseSchema(
                total = reservables.countSearch(filters),
                limit = limit,
                offset = offset,
                reservables = reservables.search(filters, limit, offset).map { it.toSchema() },
            ),
        )
    }

    get("/api/reservables/availability/monitors", {
        tags = listOf("reservable")
        summary = "List reservable availability monitors"
        description = "Lists all persisted reservable availability monitor registrations."
        response {
            code(HttpStatusCode.OK) {
                description = "Monitor registrations."
                body<ReservableAvailabilityMonitorListResponseSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        call.respondReservableJson(
            ReservableAvailabilityMonitorListResponseSchema(
                monitors = monitors.list().map { it.toSchema() },
            ),
        )
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

        call.respondReservableJson(
            ReservableDetailResponseSchema(
                reservable = row.toSchema(),
                poiIds = reservables.poiIdsForReservable(row.id),
            ),
        )
    }

    post("/api/reservable/{rid}/availability/monitor", {
        tags = listOf("reservable")
        summary = "Create a reservable availability monitor"
        description =
            "Persists a monitor registration for one reservable. `cadence` is " +
            "seconds and must be at least 5. `trigger_action` is the action " +
            "label the future monitor worker should perform when availability " +
            "matches; `stop_when_triggered` defaults to true."
        request {
            pathParameter<String>("rid") { description = "{type}:{vendor}:{vendor_id}" }
            body<ReservableAvailabilityMonitorCreateRequestSchema> {
                mediaTypes(ContentType.Application.Json)
            }
        }
        response {
            code(HttpStatusCode.Created) {
                description = "Created monitor registration."
                body<ReservableAvailabilityMonitorResponseSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed reservable id, JSON body, cadence, or trigger_action."
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
                ?: return@post call.respondReservableError("bad_rid", HttpStatusCode.BadRequest)
        val row =
            reservables.findByRid(rid)
                ?: return@post call.respondReservableError("not_found", HttpStatusCode.NotFound)
        val input =
            try {
                reservableRoutesJson.decodeFromString<ReservableAvailabilityMonitorCreateRequestSchema>(
                    call.receiveText().ifBlank { "{}" },
                )
            } catch (e: Exception) {
                return@post call.respondReservableError(
                    "bad_json",
                    HttpStatusCode.BadRequest,
                    e.message,
                )
            }
        if (input.cadence < 5) {
            return@post call.respondReservableError(
                "bad_cadence",
                HttpStatusCode.BadRequest,
                "cadence must be at least 5 seconds",
            )
        }
        val triggerAction = input.triggerAction.trim()
        if (triggerAction.isEmpty()) {
            return@post call.respondReservableError(
                "bad_trigger_action",
                HttpStatusCode.BadRequest,
                "trigger_action must not be blank",
            )
        }

        call.respondReservableJson(
            ReservableAvailabilityMonitorResponseSchema(
                monitor =
                    monitors
                        .create(
                            row.id,
                            ReservableAvailabilityMonitorRepo.CreateInput(
                                cadenceSec = input.cadence,
                                triggerAction = triggerAction,
                                stopWhenTriggered = input.stopWhenTriggered,
                            ),
                        ).toSchema(),
            ),
            HttpStatusCode.Created,
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
                reservables = rows.map { it.toSchema() },
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

private fun ApplicationCall.queryValues(vararg names: String): List<String> =
    names
        .flatMap { name -> request.queryParameters.getAll(name).orEmpty() }
        .flatMap { value -> value.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

private fun Reservable.toSchema(): ReservableSchema =
    ReservableSchema(
        rid = rid.encode(),
        type = rid.type.encode(),
        vendor = rid.vendor,
        vendorId = rid.vendorId,
        name = name,
        loop = loop,
        siteType = siteType,
        raw = raw,
    )

private fun ReservableAvailabilityMonitorRepo.Monitor.toSchema(): ReservableAvailabilityMonitorSchema =
    ReservableAvailabilityMonitorSchema(
        id = id,
        reservable = reservable.toSchema(),
        cadence = cadenceSec,
        triggerAction = triggerAction,
        stopWhenTriggered = stopWhenTriggered,
        status = status,
        lastCheckedAt = lastCheckedAt?.toString(),
        lastTriggeredAt = lastTriggeredAt?.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
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
