package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.RefLinkRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.route.routeTestApplication
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.BulkAvailabilityController
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityController
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityService
import ca.floo.roadtrip.service.availability.CampsiteCatalogService
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.PoiAvailabilitySlice
import ca.floo.roadtrip.service.availability.PoiAvailabilitySliceLookup
import ca.floo.roadtrip.service.availability.ProviderCooldownTracker
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import ca.floo.roadtrip.service.ratelimit.IpRateLimiter
import ca.floo.roadtrip.service.ref.DbRefResolver
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

private const val TEST_MAX_POIS = 5
private const val TEST_FAN_OUT_CONCURRENCY = 4
private const val TEST_PER_POI_TIMEOUT_SEC = 5L
private const val TEST_IP_RATE_LIMIT_PER_MINUTE = 10
private const val RATE_LIMITED_POI_ID = 100L
private const val UNKNOWN_CAMPSITE_POI_ID = 999_999L

private val windowStart: LocalDate = LocalDate.of(2026, 9, 1)
private val windowEnd: LocalDate = LocalDate.of(2026, 9, 8)
private val observedAt: Instant = Instant.parse("2026-08-20T00:00:00Z")

class BulkAvailabilityRoutesTest {
    @Test
    fun `rejects a request with more poi ids than the configured cap`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1,2,3,4,5,6]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("too_many_pois", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects an unparseable date`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"not-a-date"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_date_window", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects a non-positive min_nights`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"min_nights":0}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_min_nights", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects an empty poi_ids list`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_request", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `returns 200 with per-poi entries when one poi fails`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1,$RATE_LIMITED_POI_ID]}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val pois = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["pois"]!!.jsonArray
            assertEquals(2, pois.size)
            assertEquals("rate_limited", pois[1].jsonObject["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `throttles by ip`() =
        testApplication {
            application {
                routeTestApplication {
                    // One token, frozen clock: the second request must be denied.
                    bulkAvailabilityRoutesUnderTest(rateLimit = IpRateLimiter(perMinute = 1, nowMs = { 0L }))
                }
            }

            val first =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1]}""")
                }
            assertEquals(HttpStatusCode.OK, first.status)

            val second =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1]}""")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, second.status)
            val body = Json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertEquals("ip_throttled", body["error"]!!.jsonPrimitive.content)
        }

    private fun Route.bulkAvailabilityRoutesUnderTest(rateLimit: IpRateLimiter? = null) {
        val config =
            BulkAvailabilityConfig(
                maxPois = TEST_MAX_POIS,
                fanOutConcurrency = TEST_FAN_OUT_CONCURRENCY,
                perPoiTimeout = Duration.ofSeconds(TEST_PER_POI_TIMEOUT_SEC),
                tolerance = Duration.ZERO,
                ipRateLimitPerMinute = TEST_IP_RATE_LIMIT_PER_MINUTE,
            )
        val controller = BulkAvailabilityController(sliceLookup = FakePoiAvailabilitySliceLookup(), config = config)
        if (rateLimit != null) {
            bulkAvailabilityRoutes(controller, config, rateLimit)
        } else {
            bulkAvailabilityRoutes(controller, config)
        }
    }
}

/**
 * Registering `POST /api/pois/availability/bulk` sits right next to the
 * pre-existing `GET /api/pois/{id}/campsites/availability` under `/api/pois`.
 * Ktor must keep routing a numeric POI id through the `{id}` parameter node
 * rather than losing it to the new literal `availability` segment.
 */
class BulkAvailabilityRouteCollisionTest : SharedDbTest() {
    @Test
    fun `the pre-existing detail endpoint still routes correctly once bulk is registered`() =
        testApplication {
            application {
                routeTestApplication {
                    campsiteRoutes(campsiteAvailabilityController())
                    bulkAvailabilityRoutes(
                        BulkAvailabilityController(FakePoiAvailabilitySliceLookup(), BulkAvailabilityConfig.default),
                        BulkAvailabilityConfig.default,
                    )
                }
            }

            val resp = client.get("/api/pois/$UNKNOWN_CAMPSITE_POI_ID/campsites/availability")

            // A route-collision would surface as Ktor's bare 404 (no JSON body)
            // or a 405/400 from the bulk route's parser. A structured
            // `not_found` body proves the {id} detail handler ran.
            assertEquals(HttpStatusCode.NotFound, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("not_found", body["error"]!!.jsonPrimitive.content)
        }

    private fun campsiteAvailabilityController(): CampsiteAvailabilityController {
        val campsitesRepo = CampsiteRepo(ctx)
        val campgroundRepo = CampgroundRepo(ctx)
        val dateResolver = AvailabilityDateResolver(PoiRepo(ctx))
        val providers: List<AvailabilityProvider> = emptyList()
        val targets =
            DbAvailabilityTargetResolver(
                poiRepo = PoiRepo(ctx),
                campsitesRepo = campsitesRepo,
                campgroundRepo = campgroundRepo,
                availabilityProviders = providers,
                dateResolver = dateResolver,
                pollerRepo = AvailabilityPollerRepo(ctx),
            )
        return CampsiteAvailabilityController(
            campgroundRepo = campgroundRepo,
            campsitesRepo = campsitesRepo,
            catalogService = CampsiteCatalogService(DbRefResolver(RefLinkRepo(ctx)), campsitesRepo, targets),
            availabilityService =
                CampsiteAvailabilityService(
                    availabilityProviders = providers,
                    dateResolver = dateResolver,
                    failoverFetcher = FailoverAvailabilityFetcher(cooldowns = ProviderCooldownTracker(cooldown = Duration.ofMinutes(1))),
                    availabilityRepo = AvailabilityRepo(ctx),
                ),
            dateResolver = dateResolver,
            watchCapabilityService =
                WatchCapabilityService(
                    availabilityTargets = targets,
                    bookingTargets = AvailabilityBookingTargetResolver(BookingAdapterRegistry(emptyList())),
                ),
        )
    }
}

/**
 * Stands in for [CampsiteAvailabilityController.poiAvailabilitySlice] without
 * a database. Every POI resolves to one campsite with a single bookable
 * night, except [RATE_LIMITED_POI_ID], which reports an upstream failure.
 */
private class FakePoiAvailabilitySliceLookup : PoiAvailabilitySliceLookup {
    override suspend fun poiAvailabilitySlice(
        poiId: Long,
        siteTypes: List<String>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        freshAtOrAfter: Instant?,
    ): PoiAvailabilitySlice {
        if (poiId == RATE_LIMITED_POI_ID) throw AvailabilityProviderError.RateLimited()
        val campsite = campsiteFixture(id = poiId * 1000, campgroundId = poiId)
        val observations =
            listOf(CampsiteDayObservation(campsite.id, windowStart, observedAt, AvailabilityStatus.AVAILABLE))
        return PoiAvailabilitySlice(
            poiId = poiId,
            startDate = windowStart,
            endDate = windowEnd,
            allCampsites = listOf(campsite),
            campsites = listOf(campsite),
            batch =
                AvailabilityObservationBatch(
                    provider = "recgov",
                    startDate = windowStart,
                    endDate = windowEnd,
                    observations = observations,
                    cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
                ),
        )
    }
}
