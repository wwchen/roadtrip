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
import ca.floo.roadtrip.models.api.BulkAvailEntrySchema
import ca.floo.roadtrip.models.api.BulkAvailRequestSchema
import ca.floo.roadtrip.models.api.BulkAvailResponseSchema
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.service.api.availableDatesAspira
import ca.floo.roadtrip.service.api.fetchAndClassifyAspira
import ca.floo.roadtrip.service.api.mapAspiraUpstreamError
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
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
 * Response shape is provider-stable (see [renderAvailabilityJson]); the FE
 * drawer renders both alike.
 *
 * Replaces the legacy `/api/campsite/availability/{recgov_id}` and
 * `/api/campsite/availability-aspira/{tx}/{mapId}` routes — those are gone.
 */
fun Route.campsiteAvailabilityRoutes(
    ctx: DSLContext,
    recgovCache: CachedAvailability,
    aspiraCache: CachedAspiraAvailability,
    registry: PoiRegistry,
) {
    val rateLimit = IpRateLimiter(perMinute = 30)
    val aspiraHostBySource = registry.aspiraHostBySource()

    get("/api/campsite/availability/{poi_id}", {
        tags = listOf("campsite")
        summary = "Per-day availability for one campground (cached, provider-dispatched)"
        description =
            "Path key is `pois.id`. Backend reads `provider_ref` from the row to dispatch " +
            "to rec.gov or Aspira NextGen. Response shape is provider-stable; the only " +
            "differences are `provider`, `season` (rec.gov-only), and provider-specific " +
            "extras (`campground_id` for rec.gov; `host`/`map_id` for Aspira)."
    }) {
        val poiId =
            call.parameters["poi_id"]?.toLongOrNull()
                ?: return@get call.respondText(
                    """{"state":"error","error":"bad_poi_id"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )

        val days =
            call.request.queryParameters["days"]?.toIntOrNull()
                ?: DEFAULT_AVAILABILITY_DAYS
        if (days !in 1..MAX_AVAILABILITY_DAYS) {
            call.respondText(
                """{"state":"error","error":"bad_days"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondText(
                """{"state":"error","error":"ip_throttled","retry_after_s":30}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }

        val row = lookupProviderRef(ctx, poiId)
        if (row == null) {
            call.respondText(
                """{"state":"error","error":"unknown_campground"}""",
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
            return@get
        }
        val (source, providerRefJson) = row
        val variant = parseProviderRef(providerRefJson)
        if (variant == null) {
            // Camis or no provider_ref. The drawer's hasAvailability gate
            // should prevent this from being called for non-bookable rows;
            // returning empty keeps the FE happy even if it slips through.
            call.respondText(
                """{"provider":"none","state":"empty","summary":"No availability provider"}""",
                ContentType.Application.Json,
            )
            return@get
        }

        val force = call.request.queryParameters["force"] == "1"
        val today = todayUtc()
        val end = today.plusDays((days - 1).toLong())
        val months = monthsCovering(today, end)

        try {
            val body =
                when (variant) {
                    is ProviderVariant.RecGov ->
                        fetchAndClassifyRecgov(recgovCache, variant.recgovId, today, days, months, force)
                    is ProviderVariant.Aspira -> {
                        val host = aspiraHostBySource[source]
                        if (host == null) {
                            log.warn("aspira poi {} has source={} with no host mapping", poiId, source)
                            call.respondText(
                                """{"state":"error","error":"unknown_aspira_host"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.InternalServerError,
                            )
                            return@get
                        }
                        fetchAndClassifyAspira(aspiraCache, host, variant.mapId, today, days, force)
                    }
                }
            call.respondText(body, ContentType.Application.Json)
        } catch (e: AspiraException) {
            val (status, errBody) = mapAspiraUpstreamError(e)
            log.info("aspira availability poi={} failed: {}", poiId, e.message)
            call.respondText(errBody, ContentType.Application.Json, status)
        } catch (e: Exception) {
            val (status, errBody) = mapRecgovUpstreamError(e)
            log.info("recgov availability poi={} failed: {}", poiId, e.message)
            call.respondText(errBody, ContentType.Application.Json, status)
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
        tags = listOf("campsite")
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
            }
        }
    }) {
        val req =
            try {
                Json.decodeFromString(BulkAvailRequestSchema.serializer(), call.receiveText())
            } catch (e: Exception) {
                call.respondText(
                    """{"error":"bad_request","detail":"${escapeJsonInline(e.message ?: "parse failed")}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

        if (req.ids.isEmpty() || req.ids.size > MAX_BULK_IDS) {
            call.respondText(
                """{"error":"bad_ids","detail":"need 1..$MAX_BULK_IDS ids, got ${req.ids.size}"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        if (req.nights !in 1..MAX_NIGHTS) {
            call.respondText(
                """{"error":"bad_nights","detail":"nights must be in 1..$MAX_NIGHTS"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        val start =
            try {
                LocalDate.parse(req.start)
            } catch (e: Exception) {
                call.respondText(
                    """{"error":"bad_start","detail":"start must be YYYY-MM-DD"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondText(
                """{"error":"ip_throttled","retry_after_s":30}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@post
        }

        // One DB hit for the whole batch — pull (id, source, provider_ref) for
        // every requested id at once. Missing ids surface as status:404.
        val rowsById = lookupProviderRefs(ctx, req.ids)

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

        call.respondText(
            Json.encodeToString(
                BulkAvailResponseSchema(
                    start = req.start,
                    nights = req.nights,
                    results = results,
                ),
            ),
            ContentType.Application.Json,
        )
    }
}

private suspend fun fetchOneBulk(
    poiId: Long,
    row: Pair<String, String>?,
    aspiraHostBySource: Map<String, String>,
    recgovCache: CachedAvailability,
    aspiraCache: CachedAspiraAvailability,
    start: LocalDate,
    nights: Int,
): BulkAvailEntrySchema {
    if (row == null) {
        return BulkAvailEntrySchema(id = poiId, status = 404, available_dates = emptyList())
    }
    val (source, providerRefJson) = row
    val variant =
        parseProviderRef(providerRefJson)
            ?: return BulkAvailEntrySchema(id = poiId, status = 422, available_dates = emptyList())

    return try {
        val dates =
            when (variant) {
                is ProviderVariant.RecGov ->
                    availableDatesRecgov(recgovCache, variant.recgovId, start, nights)
                is ProviderVariant.Aspira -> {
                    val host = aspiraHostBySource[source]
                    if (host == null) {
                        log.warn("aspira poi {} has source={} with no host mapping", poiId, source)
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

/** (source, provider_ref::text) for a single poi_id, or null when not found / not a campground. */
private fun lookupProviderRef(
    ctx: DSLContext,
    poiId: Long,
): Pair<String, String>? {
    val rec =
        ctx
            .fetchOne(
                """
                SELECT source, provider_ref::text AS pref
                FROM pois
                WHERE id = ?
                  AND deleted_at IS NULL
                  AND category = 'campground'
                """.trimIndent(),
                poiId,
            ) ?: return null
    val source = rec.get("source") as String
    val pref = rec.get("pref") as String? ?: return null
    return source to pref
}

/** Same as [lookupProviderRef] but for a batch — one DB round-trip. */
private fun lookupProviderRefs(
    ctx: DSLContext,
    ids: List<Long>,
): Map<Long, Pair<String, String>> {
    if (ids.isEmpty()) return emptyMap()
    // Build (?,?,?) placeholders. jOOQ's `inline` would be simpler but we
    // keep parameterized for safety.
    val placeholders = ids.joinToString(",") { "?" }
    val sql =
        """
        SELECT id, source, provider_ref::text AS pref
        FROM pois
        WHERE id IN ($placeholders)
          AND deleted_at IS NULL
          AND category = 'campground'
          AND provider_ref IS NOT NULL
        """.trimIndent()
    val out = mutableMapOf<Long, Pair<String, String>>()
    for (r in ctx.fetch(sql, *ids.toTypedArray())) {
        val id = (r.get("id") as Number).toLong()
        val source = r.get("source") as String
        val pref = r.get("pref") as String? ?: continue
        out[id] = source to pref
    }
    return out
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

private fun escapeJsonInline(s: String): String =
    s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
