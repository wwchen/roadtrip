package ca.floo.roadtrip.routes

import ca.floo.campsite.recgov.booker.api.DEFAULT_AVAILABILITY_DAYS
import ca.floo.campsite.recgov.booker.api.IpRateLimiter
import ca.floo.campsite.recgov.booker.api.MAX_AVAILABILITY_DAYS
import ca.floo.campsite.recgov.booker.api.availableDatesRecgov
import ca.floo.campsite.recgov.booker.api.fetchAndClassifyRecgov
import ca.floo.campsite.recgov.booker.api.mapRecgovUpstreamError
import ca.floo.campsite.recgov.booker.api.monthsCovering
import ca.floo.campsite.recgov.booker.api.todayUtc
import ca.floo.campsite.recgov.booker.availability.CachedAvailability
import ca.floo.roadtrip.client.AspiraException
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityEmptySchema
import ca.floo.roadtrip.models.api.AvailabilityErrorSchema
import ca.floo.roadtrip.models.api.BulkAvailEntrySchema
import ca.floo.roadtrip.models.api.BulkAvailRequestSchema
import ca.floo.roadtrip.models.api.BulkAvailResponseSchema
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.repo.CampsiteProviderRefRow
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.api.availableDatesAspira
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import ca.floo.roadtrip.service.api.fetchAndClassifyAspira
import ca.floo.roadtrip.service.api.mapAspiraUpstreamError
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.LocalDate

private val log = LoggerFactory.getLogger("CampsiteAvailabilityRoutes")

// Bulk endpoint guardrails. The single-id endpoint already serves the drawer;
// bulk is for the route-planner card list which scores N campgrounds against
// "are there sites for me on Jul 4 for 3 nights?" Cap nights at 14 (any
// realistic trip leg) and ids at 50 (one per visible card row).
private const val MAX_BULK_IDS = 50
private const val MAX_NIGHTS = 14

/**
 * Unified campsite availability endpoint, keyed by pois.id. The backend reads
 * `provider_ref` from the row to decide which provider cache to hit
 * (rec.gov or Aspira NextGen). The FE doesn't need to know which provider
 * a campground is backed by — every POI feature already carries `f.id`.
 *
 * Response shape is provider-stable (see `availabilityResponseDto`); the FE
 * drawer renders both alike.
 *
 * Replaces the legacy `/api/campsite/availability/{recgov_id}` and
 * `/api/campsite/availability-aspira/{tx}/{mapId}` routes — those are gone.
 */
fun Route.campsiteAvailabilityRoutes(
    providerRefs: CampsiteProviderRepo,
    recgovCache: CachedAvailability,
    aspiraCache: CachedAspiraAvailability,
    registry: PoiRegistry,
) {
    val rateLimit = IpRateLimiter(perMinute = 30)
    val aspiraHostBySource = registry.aspiraHostBySource()

    get("/api/campsite/availability/{poi_id}", {
        tags = listOf("campsite-availability")
        summary = "Per-day availability for one campground (cached, provider-dispatched)"
        description =
            "Path key is `pois.id`. Backend reads `provider_ref` from the row to dispatch " +
            "to rec.gov or Aspira NextGen. Response shape is provider-stable; the only " +
            "differences are `provider`, `season` (rec.gov-only), and provider-specific " +
            "extras (`campground_id` for rec.gov; `host`/`map_id` for Aspira)."
        response {
            code(HttpStatusCode.BadRequest) {
                description = "Bad POI id or invalid days."
                body<AvailabilityErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) {
                description = "No campground/provider row exists for that POI id."
                body<AvailabilityErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Rate limited or upstream availability service unavailable."
                body<AvailabilityErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val poiId =
            call.parameters["poi_id"]?.toLongOrNull()
                ?: return@get call.respondAvailabilityError("bad_poi_id", HttpStatusCode.BadRequest)

        val days =
            call.request.queryParameters["days"]?.toIntOrNull()
                ?: DEFAULT_AVAILABILITY_DAYS
        if (days !in 1..MAX_AVAILABILITY_DAYS) {
            call.respondAvailabilityError("bad_days", HttpStatusCode.BadRequest)
            return@get
        }

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondAvailabilityError("ip_throttled", HttpStatusCode.ServiceUnavailable, retryAfterS = 30)
            return@get
        }

        val row = providerRefs.findProviderRef(poiId)
        if (row == null) {
            call.respondAvailabilityError("unknown_campground", HttpStatusCode.NotFound)
            return@get
        }
        val variant = parseProviderRef(row.providerRefJson)
        if (variant == null) {
            // Camis or no provider_ref. The drawer's hasAvailability gate
            // should prevent this from being called for non-bookable rows;
            // returning empty keeps the FE happy even if it slips through.
            call.respondAvailabilityJson(AvailabilityEmptySchema())
            return@get
        }

        val force = call.request.queryParameters["force"] == "1"
        val today = todayUtc()
        val end = today.plusDays((days - 1).toLong())
        val months = monthsCovering(today, end)

        try {
            val response =
                when (variant) {
                    is ProviderVariant.RecGov ->
                        fetchAndClassifyRecgov(recgovCache, variant.recgovId, today, days, months, force)
                    is ProviderVariant.Aspira -> {
                        val host = aspiraHostBySource[row.source]
                        if (host == null) {
                            log.warn("aspira poi {} has source={} with no host mapping", poiId, row.source)
                            call.respondAvailabilityError("unknown_aspira_host", HttpStatusCode.InternalServerError)
                            return@get
                        }
                        fetchAndClassifyAspira(aspiraCache, host, variant.mapId, today, days, force)
                    }
                }
            call.respondAvailabilityJson(response)
        } catch (e: AspiraException) {
            val (status, error) = mapAspiraUpstreamError(e)
            log.info("aspira availability poi={} failed: {}", poiId, e.message)
            call.respondAvailabilityJson(error, status)
        } catch (e: Exception) {
            val (status, error) = mapRecgovUpstreamError(e)
            log.info("recgov availability poi={} failed: {}", poiId, e.message)
            call.respondAvailabilityJson(error, status)
        }
    }

    // POST /api/campsite/availability/bulk
    //
    // Trip-planner endpoint. The FE has a list of campgrounds along the
    // active corridor and wants to know "for these N campgrounds, which
    // dates between [start, start+nights-1] have at least one bookable
    // site?" — so the user can compare and pick. Reuses the same per-month
    // cache the single-id endpoint hits, so a window inside today..today+30
    // costs zero upstream calls.
    //
    // Per-id errors land as a non-200 `status` on that id's entry; the rest
    // of the call still succeeds. Mixed providers in one call are fine —
    // each id is dispatched by its own provider_ref.
    post("/api/campsite/availability/bulk", {
        tags = listOf("campsite-availability")
        summary = "Bulk per-day availability for many campgrounds in a date window (poi-id keyed)"
        description =
            "Body: { ids: number[], start: 'YYYY-MM-DD', nights: 1..$MAX_NIGHTS }. " +
            "Returns one entry per id with an HTTP-style `status` and the dates inside " +
            "the window where at least one site is bookable. Mixed providers OK."
        request {
            body<BulkAvailRequestSchema> {
                mediaTypes(ContentType.Application.Json)
                example("3-night July 4 weekend") {
                    value =
                        BulkAvailRequestSchema(
                            ids = listOf(12345L, 67890L),
                            start = "2026-07-04",
                            nights = 3,
                        )
                }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "One entry per id. status==200 → available_dates is meaningful."
                body<BulkAvailResponseSchema> {
                    mediaTypes(ContentType.Application.Json)
                    example("mixed") {
                        value =
                            BulkAvailResponseSchema(
                                start = "2026-07-04",
                                nights = 3,
                                results =
                                    listOf(
                                        BulkAvailEntrySchema(12345L, 200, listOf("2026-07-04", "2026-07-06")),
                                        BulkAvailEntrySchema(67890L, 200, emptyList()),
                                        BulkAvailEntrySchema(99999L, 503, emptyList()),
                                    ),
                            )
                    }
                }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed body, missing fields, or limits exceeded."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Rate limited."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val req =
            try {
                Json.decodeFromString(BulkAvailRequestSchema.serializer(), call.receiveText())
            } catch (e: Exception) {
                call.respondApiError("bad_request", HttpStatusCode.BadRequest, detail = e.message ?: "parse failed")
                return@post
            }

        if (req.ids.isEmpty() || req.ids.size > MAX_BULK_IDS) {
            call.respondApiError(
                "bad_ids",
                HttpStatusCode.BadRequest,
                detail = "need 1..$MAX_BULK_IDS ids, got ${req.ids.size}",
            )
            return@post
        }
        if (req.nights !in 1..MAX_NIGHTS) {
            call.respondApiError(
                "bad_nights",
                HttpStatusCode.BadRequest,
                detail = "nights must be in 1..$MAX_NIGHTS",
            )
            return@post
        }
        val start =
            try {
                LocalDate.parse(req.start)
            } catch (e: Exception) {
                call.respondApiError("bad_start", HttpStatusCode.BadRequest, detail = "start must be YYYY-MM-DD")
                return@post
            }

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondApiError("ip_throttled", HttpStatusCode.ServiceUnavailable, retryAfterS = 30)
            return@post
        }

        // One DB hit for the whole batch — pull (id, source, provider_ref) for
        // every requested id at once. Missing ids surface as status:404.
        val rowsById = providerRefs.findProviderRefs(req.ids)

        val results =
            coroutineScope {
                req.ids
                    .map { id ->
                        async {
                            fetchOneBulk(
                                id,
                                rowsById[id],
                                aspiraHostBySource,
                                recgovCache,
                                aspiraCache,
                                start,
                                req.nights,
                            )
                        }
                    }.awaitAll()
            }

        call.respondAvailabilityJson(
            BulkAvailResponseSchema(
                start = req.start,
                nights = req.nights,
                results = results,
            ),
        )
    }
}

private suspend fun fetchOneBulk(
    poiId: Long,
    row: CampsiteProviderRefRow?,
    aspiraHostBySource: Map<String, String>,
    recgovCache: CachedAvailability,
    aspiraCache: CachedAspiraAvailability,
    start: LocalDate,
    nights: Int,
): BulkAvailEntrySchema {
    if (row == null) {
        return BulkAvailEntrySchema(id = poiId, status = 404, available_dates = emptyList())
    }
    val variant =
        parseProviderRef(row.providerRefJson)
            ?: return BulkAvailEntrySchema(id = poiId, status = 422, available_dates = emptyList())

    return try {
        val dates =
            when (variant) {
                is ProviderVariant.RecGov ->
                    availableDatesRecgov(recgovCache, variant.recgovId, start, nights)
                is ProviderVariant.Aspira -> {
                    val host = aspiraHostBySource[row.source]
                    if (host == null) {
                        log.warn("aspira poi {} has source={} with no host mapping", poiId, row.source)
                        return BulkAvailEntrySchema(id = poiId, status = 500, available_dates = emptyList())
                    }
                    availableDatesAspira(aspiraCache, host, variant.mapId, start, nights)
                }
            }
        BulkAvailEntrySchema(id = poiId, status = 200, available_dates = dates)
    } catch (e: AspiraException) {
        log.info("bulk availability poi={} aspira upstream: {}", poiId, e.message)
        BulkAvailEntrySchema(id = poiId, status = if (e.httpStatus == 429) 429 else 503, available_dates = emptyList())
    } catch (e: Exception) {
        log.info("bulk availability poi={} failed: {}", poiId, e.message)
        val status = if (e.message?.contains("429") == true) 429 else 503
        BulkAvailEntrySchema(id = poiId, status = status, available_dates = emptyList())
    }
}

/**
 * Sealed variant the dispatch fans out on. Mirrors [ca.floo.roadtrip.models.ProviderRef]
 * but defined here to keep the api package free of the etl import chain.
 */
private sealed class ProviderVariant {
    data class RecGov(
        val recgovId: String,
    ) : ProviderVariant()

    data class Aspira(
        val mapId: Int,
    ) : ProviderVariant()
}

/**
 * Parse a `provider_ref` JSONB payload into the matching [ProviderVariant].
 * The wire format is decided by [Upsert.providerRefToJson]; presence of a
 * field is the discriminator — there's no explicit type tag.
 *
 * Returns null for unknown shapes (Camis, malformed JSON) so the caller
 * can render `state:"empty"` instead of 5xx-ing.
 */
private fun parseProviderRef(json: String): ProviderVariant? {
    val obj =
        try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            return null
        }
    val recgov = obj["recgov_id"]?.jsonPrimitive?.contentOrNull
    if (recgov != null) return ProviderVariant.RecGov(recgov)
    val mapId = obj["mapId"]?.jsonPrimitive?.intOrNull
    if (mapId != null) return ProviderVariant.Aspira(mapId)
    return null
}

private suspend fun ApplicationCall.respondAvailabilityError(
    error: String,
    status: HttpStatusCode,
    retryAfterS: Int? = null,
) {
    respondAvailabilityJson(availabilityErrorDto(error, retryAfterS), status)
}

private suspend fun ApplicationCall.respondApiError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
    retryAfterS: Int? = null,
) {
    respondAvailabilityJson(ApiErrorSchema(error = error, detail = detail, retry_after_s = retryAfterS), status)
}

private suspend inline fun <reified T> ApplicationCall.respondAvailabilityJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(encodeAvailabilityJson(value), ContentType.Application.Json, status)
}
