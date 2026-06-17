package ca.floo.roadtrip.routes

import ca.floo.roadtrip.client.AspiraSearchDefaults
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
            queryParameter<String>("tags") { description = "JSON object contained by the normalized tags JSONB payload." }
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
            queryParameter<String>("site_type") { description = "Optional exact site type filter. Repeat or comma-separate for OR." }
            queryParameter<String>("start_date") { description = "Optional arrival date for per-site reservation_url links." }
            queryParameter<String>("end_date") { description = "Optional exclusive departure date for per-site reservation_url links." }
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
        val linkOptions =
            try {
                call.reservationUrlOptions()
            } catch (e: BadReservableQuery) {
                return@get call.respondReservableError(e.error, HttpStatusCode.BadRequest, e.detail)
            }
        val siteTypes = call.queryValues("site_type", "siteType")

        val poi =
            pois.fetchPoiById(poiId)
                ?: return@get call.respondReservableError("not_found", HttpStatusCode.NotFound)
        val providerRef = poi.providerRefJson?.let { ProviderRefParser.parse(it) }

        val rows =
            reservables
                .findByPoi(poiId, type)
                .filterBySiteTypes(siteTypes)
        call.respondReservableJson(
            PoiReservablesResponseSchema(
                poiId = poiId,
                type = type.encode(),
                totalAtPoi = rows.size,
                reservables =
                    rows.map {
                        it.toSchema(
                            poiIds = listOf(poiId),
                            reservationUrl = it.reservationUrl(providerRef, linkOptions),
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

internal fun parseReservableType(raw: String?): ReservableType? =
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
        tagsContainsJson =
            queryValues("tags")
                .map { raw ->
                    try {
                        val parsed = reservableRoutesJson.parseToJsonElement(raw)
                        reservableRoutesJson.encodeToString(JsonElement.serializer(), parsed)
                    } catch (e: Exception) {
                        throw BadReservableQuery("bad_tags", e.message)
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

internal data class ReservationUrlOptions(
    val startDate: LocalDate?,
    val endDate: LocalDate?,
)

private fun ApplicationCall.reservationUrlOptions(): ReservationUrlOptions {
    if (listOf("start", "min_nights", "minNights").any { request.queryParameters[it] != null }) {
        throw BadReservableQuery("bad_date_window", "use start_date and end_date")
    }
    val startDate = request.queryParameters["start_date"]?.takeIf { it.isNotBlank() }
    val endDate = request.queryParameters["end_date"]?.takeIf { it.isNotBlank() }
    if ((startDate == null) != (endDate == null)) {
        throw BadReservableQuery("bad_date_window", "use start_date and end_date")
    }
    if (startDate == null || endDate == null) {
        return ReservationUrlOptions(startDate = null, endDate = null)
    }
    val parsedStart =
        runCatching { LocalDate.parse(startDate) }
            .getOrElse { throw BadReservableQuery("bad_date_window", "use start_date and end_date") }
    val parsedEnd =
        runCatching { LocalDate.parse(endDate) }
            .getOrElse { throw BadReservableQuery("bad_date_window", "use start_date and end_date") }
    if (!parsedEnd.isAfter(parsedStart)) {
        throw BadReservableQuery("bad_date_window", "use start_date and end_date")
    }
    return ReservationUrlOptions(startDate = parsedStart, endDate = parsedEnd)
}

private fun ApplicationCall.queryValues(vararg names: String): List<String> =
    names
        .flatMap { name -> request.queryParameters.getAll(name).orEmpty() }
        .flatMap { value -> value.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

internal fun Reservable.toSchema(
    poiIds: List<Long> = emptyList(),
    reservationUrl: String? = null,
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
        tags = tags,
        raw = raw,
    )

internal fun Reservable.reservationUrl(
    providerRef: ProviderRef?,
    options: ReservationUrlOptions,
): String? =
    when {
        rid.vendor == "recgov" -> recgovReservationUrl(options)
        rid.vendor.startsWith("aspira_") -> aspiraReservationUrl(providerRef, options)
        else -> null
    }

private fun Reservable.recgovReservationUrl(options: ReservationUrlOptions): String {
    val base = "https://www.recreation.gov/camping/campsites/${urlEncode(rid.vendorId)}"
    val startDate = options.startDate ?: return base
    val endDate = options.endDate ?: return base
    return "$base?${queryString("startDate" to startDate.toString(), "endDate" to endDate.toString())}"
}

private fun Reservable.aspiraReservationUrl(
    providerRef: ProviderRef?,
    options: ReservationUrlOptions,
): String? {
    val startDate = options.startDate ?: return null
    val endDate = options.endDate ?: return null
    val parentRef = providerRef as? ProviderRef.Aspira
    val host = aspiraHostForVendor(rid.vendor) ?: return null
    val transactionLocationId =
        aspiraProviderRefLong("transactionLocationId")
            ?: parentRef?.transactionLocationId
            ?: return null
    val mapId =
        aspiraProviderRefLong("mapId")
            ?: parentRef?.mapId
            ?: return null
    val resourceLocationId =
        aspiraProviderRefLong("resourceLocationId")
            ?: parentRef?.resourceLocationId
    return aspiraReservationUrl(
        host = host,
        transactionLocationId = transactionLocationId,
        mapId = mapId,
        resourceLocationId = resourceLocationId,
        startDate = startDate,
        endDate = endDate,
    )
}

private fun Reservable.aspiraProviderRefLong(key: String): Long? =
    ((providerRef as? JsonObject)?.get(key))?.jsonPrimitive?.contentOrNull?.toLongOrNull()

internal fun List<Reservable>.filterBySiteTypes(siteTypes: Collection<String>): List<Reservable> {
    if (siteTypes.isEmpty()) return this
    val allowed = siteTypes.toSet()
    return filter { it.siteType != null && it.siteType in allowed }
}

private fun aspiraHostForVendor(vendor: String): String? =
    when (vendor) {
        "aspira_pc" -> "reservation.pc.gc.ca"
        "aspira_bc" -> "camping.bcparks.ca"
        "aspira_wa" -> "washington.goingtocamp.com"
        else -> null
    }

private fun aspiraReservationUrl(
    host: String,
    transactionLocationId: Long,
    mapId: Long,
    resourceLocationId: Long?,
    startDate: LocalDate,
    endDate: LocalDate,
): String {
    val nights = ChronoUnit.DAYS.between(startDate, endDate).toInt()
    val params =
        mutableListOf(
            "transactionLocationId" to transactionLocationId.toString(),
            "mapId" to mapId.toString(),
            "searchTabGroupId" to AspiraSearchDefaults.SEARCH_TAB_GROUP_ID.toString(),
            "bookingCategoryId" to AspiraSearchDefaults.BOOKING_CATEGORY_ID.toString(),
            "startDate" to startDate.toString(),
            "endDate" to endDate.toString(),
            "nights" to nights.toString(),
            "isReserving" to "true",
            "equipmentId" to AspiraSearchDefaults.ANY_EQUIPMENT_CATEGORY_ID.toString(),
            "subEquipmentId" to AspiraSearchDefaults.ANY_SUB_EQUIPMENT_CATEGORY_ID.toString(),
            "peopleCapacityCategoryCounts" to AspiraSearchDefaults.deeplinkPeopleCapacityCategoryCounts(),
            "searchTime" to "${startDate}T00:00:00.000",
            "flexibleSearch" to AspiraSearchDefaults.flexibleSearch(startDate),
            "view" to "list",
        )
    if (resourceLocationId != null) {
        params += "resourceLocationId" to resourceLocationId.toString()
    }
    return "https://$host/create-booking/results?${queryString(*params.toTypedArray())}"
}

private fun queryString(vararg params: Pair<String, String>): String =
    params.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

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
