package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.models.Reservable
import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.PoiReservablesResponseSchema
import ca.floo.roadtrip.models.api.ReservableDetailResponseSchema
import ca.floo.roadtrip.models.api.ReservableSchema
import ca.floo.roadtrip.models.api.ReservablesResponseSchema
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.booking.ProviderRefParser
import ca.floo.roadtrip.service.booking.ReservationStay
import ca.floo.roadtrip.service.booking.aspiraHostForVendor
import ca.floo.roadtrip.service.booking.aspiraReservableUrl
import ca.floo.roadtrip.service.booking.recgovCampsiteUrl
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import java.time.LocalDate
import java.time.format.DateTimeParseException

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

    get("/api/poi/{id}/reservables", {
        tags = listOf("reservable")
        summary = "Reservables linked to a POI"
        description =
            "Lists reservables at one active POI. `type` defaults to `site`; " +
            "future reservable types can be added without changing the response envelope."
        request {
            pathParameter<Long>("id") { description = "pois.id primary key" }
            queryParameter<String>("type") { description = "Reservable type, defaults to site." }
            queryParameter<String>("start") { description = "Optional arrival date for dated booking links, YYYY-MM-DD." }
            queryParameter<Int>("min_nights") { description = "Optional stay length for dated booking links, 1..31." }
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

        val poi =
            pois.fetchPoiById(poiId)
                ?: return@get call.respondReservableError("not_found", HttpStatusCode.NotFound)
        val stay =
            try {
                call.reservationStayOrNull()
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }
        val parentRef = poi.providerRefJson?.let(ProviderRefParser::parse)

        val rows = reservables.findByPoi(poiId, type)
        call.respondReservableJson(
            PoiReservablesResponseSchema(
                poiId = poiId,
                type = type.encode(),
                totalAtPoi = reservables.countByPoi(poiId, type),
                reservables =
                    rows.map {
                        it.toSchema(
                            poiIds = listOf(poiId),
                            reservationUrl = it.reservationUrlFor(stay = stay, parentRef = parentRef),
                        )
                    },
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

private fun ApplicationCall.reservationStayOrNull(): ReservationStay? {
    val rawStart = request.queryParameters["start"]?.takeIf { it.isNotBlank() } ?: return null
    val start =
        try {
            LocalDate.parse(rawStart)
        } catch (e: DateTimeParseException) {
            throw BadReservableQuery("bad_start", "start must be YYYY-MM-DD")
        }
    val nights = minNightsQuery()
    return ReservationStay(start = start, nights = nights)
}

private fun ApplicationCall.minNightsQuery(): Int {
    val raw = request.queryParameters["min_nights"] ?: request.queryParameters["minNights"] ?: return 1
    val value = raw.toIntOrNull() ?: throw BadReservableQuery("bad_min_nights", "min_nights must be an integer")
    if (value !in 1..31) {
        throw BadReservableQuery("bad_min_nights", "min_nights must be between 1 and 31")
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

private fun Reservable.toSchema(
    poiIds: List<Long> = emptyList(),
    reservationUrl: String? = this.reservationUrl,
): ReservableSchema =
    ReservableSchema(
        rid = rid.encode(),
        type = rid.type.encode(),
        vendor = rid.vendor,
        vendorId = rid.vendorId,
        name = name,
        loop = loop,
        siteType = siteType,
        reservationUrl = reservationUrl,
        poiIds = poiIds,
        providerRef = providerRef,
        raw = raw,
    )

private fun Reservable.reservationUrlFor(
    stay: ReservationStay?,
    parentRef: ProviderRef?,
): String? {
    if (stay == null) return reservationUrl
    return when (rid.vendor) {
        "recgov" -> recgovCampsiteUrl(rid.vendorId, stay)
        "aspira_pc", "aspira_bc", "aspira_wa" -> aspiraReservationUrlFor(stay, parentRef) ?: reservationUrl
        else -> reservationUrl
    }
}

private fun Reservable.aspiraReservationUrlFor(
    stay: ReservationStay,
    parentRef: ProviderRef?,
): String? {
    val parentAspira = parentRef as? ProviderRef.Aspira
    val transactionLocationId =
        aspiraProviderRefLong("transactionLocationId")
            ?: parentAspira?.transactionLocationId
            ?: return null
    val mapId =
        aspiraProviderRefLong("mapId")
            ?: parentAspira?.mapId
            ?: return null
    val resourceLocationId =
        aspiraProviderRefLong("resourceLocationId")
            ?: parentAspira?.resourceLocationId
    val host = aspiraHostForVendor(rid.vendor) ?: return null
    return aspiraReservableUrl(
        host = host,
        transactionLocationId = transactionLocationId,
        mapId = mapId,
        resourceLocationId = resourceLocationId,
        stay = stay,
    )
}

private fun Reservable.aspiraProviderRefLong(key: String): Long? =
    runCatching {
        providerRef
            ?.jsonObject
            ?.get(key)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull()
    }.getOrNull()

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
