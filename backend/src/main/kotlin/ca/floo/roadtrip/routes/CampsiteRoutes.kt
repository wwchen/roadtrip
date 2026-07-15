package ca.floo.roadtrip.routes

import ca.floo.roadtrip.exceptions.AspiraException
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityErrorDto
import ca.floo.roadtrip.models.api.PoiCampsitesAvailabilityResponseDto
import ca.floo.roadtrip.models.api.PoiCampsitesResponseSchema
import ca.floo.roadtrip.models.availability.AvailabilityProviderError
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityServiceError
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityComposer
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityService
import ca.floo.roadtrip.service.availability.CampsiteCatalogService
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.WatchBookingCapabilityService
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("CampsiteRoutes")

private const val IP_RATE_LIMIT_PER_MINUTE = 30

internal fun Route.campsiteRoutes(
    ctx: DSLContext,
    availabilityProviders: AvailabilityProviderRegistry,
    dateResolver: AvailabilityDateResolver = AvailabilityDateResolver(),
    failoverFetcher: FailoverAvailabilityFetcher,
    watchBookingCapabilities: WatchBookingCapabilityService,
) {
    val campsitesRepo = CampsiteRepo(ctx)
    val providerRefs = CampsiteProviderRepo(ctx)
    val targets =
        DbAvailabilityTargetResolver(
            providerRefs = providerRefs,
            campsitesRepo = campsitesRepo,
            availabilityProviders = availabilityProviders,
            dateResolver = dateResolver,
        )
    val catalogService = CampsiteCatalogService(providerRefs, campsitesRepo)
    val availabilityService =
        CampsiteAvailabilityService(
            providerRefs = providerRefs,
            campsitesRepo = campsitesRepo,
            composer =
                CampsiteAvailabilityComposer(
                    targets = targets,
                    dateResolver = dateResolver,
                    availability = AvailabilityRepo(ctx),
                    failoverFetcher = failoverFetcher,
                ),
            dateResolver = dateResolver,
            watchBookingCapabilities = watchBookingCapabilities,
        )
    val rateLimit = IpRateLimiter(perMinute = IP_RATE_LIMIT_PER_MINUTE)

    get("/api/pois/{id}/campsites", {
        tags = listOf("campsite", "poi")
        summary = "Canonical campsites linked to a campground POI"
        description =
            "Lists active canonical campsite rows linked to a campground POI. " +
            "`site_type` optionally filters exact campsite kinds."
        request {
            pathParameter<Long>("id") { description = "pois.id primary key" }
            queryParameter<String>("site_type") { description = "Exact site type filter. Repeat or comma-separate for OR." }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "Campsites linked to the POI."
                body<PoiCampsitesResponseSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed POI id."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) {
                description = "No active campground POI with that id."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val poiId =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondCampsiteError("bad_id", HttpStatusCode.BadRequest)
        try {
            call.respondCampsiteJson(
                catalogService.campsitesForPoi(
                    poiId = poiId,
                    siteTypes = call.queryValues("site_type", "siteType"),
                ),
            )
        } catch (e: AvailabilityServiceError.NotFound) {
            call.respondCampsiteError(e.error, HttpStatusCode.NotFound)
        }
    }

    get("/api/pois/{poi_id}/campsites/availability", {
        tags = listOf("availability", "campsite")
        summary = "Per-campsite availability for one campground POI"
        description =
            "Path key is `pois.id`. Returns one availability envelope per canonical " +
            "campsite linked to this POI. The frontend fuses the per-campsite streams " +
            "into the campground week grid."
        request {
            pathParameter<Long>("poi_id") { description = "pois.id primary key" }
            queryParameter<String>("start_date") { description = "YYYY-MM-DD; default is today's local date." }
            queryParameter<String>("end_date") { description = "Exclusive YYYY-MM-DD; default is start_date + 7 days." }
            queryParameter<String>("site_type") { description = "Exact site type filter. Repeat or comma-separate for OR." }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "`campsites` is empty when none are linked or none match the filter."
                body<PoiCampsitesAvailabilityResponseDto> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Bad POI id or invalid date window."
                body<AvailabilityErrorDto> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) {
                description = "No active campground POI with that id."
                body<AvailabilityErrorDto> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Rate limited or upstream availability service unavailable."
                body<AvailabilityErrorDto> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val poiId =
            call.parameters["poi_id"]?.toLongOrNull()
                ?: return@get call.respondAvailabilityError("bad_poi_id", HttpStatusCode.BadRequest)

        if (!rateLimit.allow(call.request.origin.remoteHost)) {
            call.respondAvailabilityError("ip_throttled", HttpStatusCode.ServiceUnavailable)
            return@get
        }

        val startDate =
            try {
                call.optionalDateQuery("start_date")
            } catch (e: Exception) {
                call.respondAvailabilityError("bad_date_window", HttpStatusCode.BadRequest)
                return@get
            }
        val endDate =
            try {
                call.optionalDateQuery("end_date")
            } catch (e: Exception) {
                call.respondAvailabilityError("bad_date_window", HttpStatusCode.BadRequest)
                return@get
            }

        try {
            call.respondAvailabilityJson(
                availabilityService.poiCampsitesAvailability(
                    poiId = poiId,
                    startDate = startDate,
                    endDate = endDate,
                    siteTypes = call.queryValues("site_type", "siteType"),
                ),
            )
        } catch (e: AvailabilityServiceError) {
            call.respondServiceAvailabilityError(e)
        } catch (e: AvailabilityProviderError) {
            val (status, error) = mapProviderError(e)
            log.info("poi campsites availability poi={} failed: {}", poiId, e.message)
            call.respondAvailabilityJson(error, status)
        }
    }
}

private class IpRateLimiter(
    private val perMinute: Int,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Bucket(
        var tokens: Double,
        var lastRefillMs: Long,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val refillPerMs = perMinute / 60_000.0

    fun allow(ip: String): Boolean {
        val now = nowMs()
        val bucket =
            buckets.compute(ip) { _, existing ->
                val b = existing ?: Bucket(perMinute.toDouble(), now)
                val delta = now - b.lastRefillMs
                b.tokens = (b.tokens + delta * refillPerMs).coerceAtMost(perMinute.toDouble())
                b.lastRefillMs = now
                b
            }!!
        return synchronized(bucket) {
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }
}

internal fun mapProviderError(e: AvailabilityProviderError): Pair<HttpStatusCode, AvailabilityErrorDto> {
    val upstream = upstreamHttpStatus(e)
    return when (e) {
        is AvailabilityProviderError.RateLimited ->
            HttpStatusCode.ServiceUnavailable to availabilityErrorDto("rate_limited", upstreamStatus = upstream)
        is AvailabilityProviderError.UpstreamBlocked ->
            HttpStatusCode.ServiceUnavailable to availabilityErrorDto("upstream_blocked", upstreamStatus = upstream)
        is AvailabilityProviderError.UpstreamUnavailable ->
            HttpStatusCode.ServiceUnavailable to availabilityErrorDto("upstream_5xx", upstreamStatus = upstream)
        is AvailabilityProviderError.Unsupported ->
            HttpStatusCode.NotImplemented to availabilityErrorDto("unsupported")
        is AvailabilityProviderError.WrongRefType ->
            HttpStatusCode.InternalServerError to availabilityErrorDto("provider_misconfigured")
    }
}

internal fun upstreamHttpStatus(e: AvailabilityProviderError): Int? {
    var t: Throwable? = e.cause
    while (t != null) {
        if (t is AspiraException) return t.httpStatus
        t = t.cause
    }
    return null
}

private suspend fun ApplicationCall.respondCampsiteError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    respondCampsiteJson(ApiErrorSchema(error = error, detail = detail), status)
}

private suspend inline fun <reified T> ApplicationCall.respondCampsiteJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(encodeAvailabilityJson(value), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.respondAvailabilityError(
    error: String,
    status: HttpStatusCode,
) {
    respondAvailabilityJson(availabilityErrorDto(error), status)
}

private suspend fun ApplicationCall.respondServiceAvailabilityError(e: AvailabilityServiceError) {
    val status =
        when (e) {
            is AvailabilityServiceError.BadDateWindow -> HttpStatusCode.BadRequest
            AvailabilityServiceError.NotFound -> HttpStatusCode.NotFound
            AvailabilityServiceError.UnknownCampground -> HttpStatusCode.NotFound
        }
    val body =
        when (e) {
            is AvailabilityServiceError.BadDateWindow -> availabilityErrorDto(e)
            else -> availabilityErrorDto(e.error)
        }
    respondAvailabilityJson(body, status)
}

private suspend inline fun <reified T> ApplicationCall.respondAvailabilityJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(encodeAvailabilityJson(value), ContentType.Application.Json, status)
}

private fun availabilityErrorDto(e: AvailabilityServiceError.BadDateWindow): AvailabilityErrorDto =
    when (e) {
        is AvailabilityServiceError.BadDateWindow.StartBeforeEarliest ->
            availabilityErrorDto(
                error = e.error,
                earliestDate = e.earliestDate.toString(),
                timeZone = e.timeZone.id,
            )
        AvailabilityServiceError.BadDateWindow.EndBeforeStart ->
            availabilityErrorDto(error = e.error)
        is AvailabilityServiceError.BadDateWindow.WindowTooLong ->
            availabilityErrorDto(error = e.error, maxDays = e.maxDays)
        is AvailabilityServiceError.BadDateWindow.BeyondBookingHorizon ->
            availabilityErrorDto(error = e.error, latestDate = e.latestDate.toString())
        AvailabilityServiceError.BadDateWindow.Invalid ->
            availabilityErrorDto(error = e.error)
    }
