package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.RefLinkRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.route.api.pois.campsiteRoutes
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityController
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityService
import ca.floo.roadtrip.service.availability.CampsiteCatalogService
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.ProviderCooldownTracker
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import ca.floo.roadtrip.service.ratelimit.IpRateLimiter
import ca.floo.roadtrip.service.ref.DbRefResolver
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val UNKNOWN_POI_ID = 999_999L
private const val DEFAULT_WINDOW_DAYS = 7

class CampsiteRoutesTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun controller(providers: List<AvailabilityProvider>): CampsiteAvailabilityController {
        val campsitesRepo = CampsiteRepo(ctx)
        val campgroundRepo = CampgroundRepo(ctx)
        val dateResolver = AvailabilityDateResolver(PoiRepo(ctx))
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

    private fun Route.campsiteRoutesUnderTest(
        providers: List<AvailabilityProvider> = listOf(ServingRecgovProvider()),
        rateLimit: IpRateLimiter? = null,
    ) {
        if (rateLimit != null) {
            campsiteRoutes(controller(providers), rateLimit)
        } else {
            campsiteRoutes(controller(providers))
        }
    }

    private fun seedRecgovPoiWithCampsite(sourceId: String = "route-cs-poi"): Pair<Long, Long> {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = sourceId,
                name = "Route Campsite CG",
                lon = -119.56,
                lat = 37.74,
                providerRefJson = """{"recgov_id": "232447"}""",
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )
        val campsiteId = ctx.seedCampsite(campgroundId = fixture.catalogId, vendorId = "route-cs-100")
        return fixture.poiId to campsiteId
    }

    @Test
    fun `GET campsites lists the campsites linked to the POI`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest() } }
            val (poiId, campsiteId) = seedRecgovPoiWithCampsite()

            val resp = client.get("/api/pois/$poiId/campsites")

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(poiId, body["poi_id"]!!.jsonPrimitive.long)
            val campsites = body["campsites"]!!.jsonArray
            assertEquals(
                campsiteId,
                campsites
                    .single()
                    .jsonObject["id"]!!
                    .jsonPrimitive.long,
            )
        }

    @Test
    fun `GET campsites returns 404 for an unknown POI`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest() } }

            val resp = client.get("/api/pois/$UNKNOWN_POI_ID/campsites")

            assertEquals(HttpStatusCode.NotFound, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("not_found", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET availability returns one envelope per campsite with watch capabilities`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest() } }
            val (poiId, campsiteId) = seedRecgovPoiWithCampsite()

            val resp = client.get("/api/pois/$poiId/campsites/availability")

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(poiId, body["poi_id"]!!.jsonPrimitive.long)

            val startDate = LocalDate.parse(body["start_date"]!!.jsonPrimitive.content)
            val endDate = LocalDate.parse(body["end_date"]!!.jsonPrimitive.content)
            assertEquals(DEFAULT_WINDOW_DAYS.toLong(), ChronoUnit.DAYS.between(startDate, endDate))

            // Watch capabilities are computed from the same campsite set.
            val capabilities = body["watch_capabilities"]!!.jsonObject
            assertEquals(
                listOf("slack_notify", "email_notify"),
                capabilities["trigger_kinds"]!!.jsonArray.map { it.jsonPrimitive.content },
            )

            val envelope = body["campsites"]!!.jsonArray.single().jsonObject
            assertEquals("recgov", envelope["provider"]!!.jsonPrimitive.content)
            assertEquals(campsiteId, envelope["campsite_id"]!!.jsonPrimitive.long)
            assertEquals("success", envelope["state"]!!.jsonPrimitive.content)
            val days = envelope["availability"]!!.jsonArray
            assertEquals(DEFAULT_WINDOW_DAYS, days.size)
            assertTrue(days.all { it.jsonObject["status"]!!.jsonPrimitive.content == "available" })
            assertEquals(false, envelope["cache"]!!.jsonObject["hit"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `GET availability with a site_type filter matching nothing returns an empty window`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest() } }
            val (poiId, _) = seedRecgovPoiWithCampsite()

            val resp = client.get("/api/pois/$poiId/campsites/availability?site_type=rv")

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(poiId, body["poi_id"]!!.jsonPrimitive.long)
            assertTrue(body["campsites"]!!.jsonArray.isEmpty())
            val startDate = LocalDate.parse(body["start_date"]!!.jsonPrimitive.content)
            val endDate = LocalDate.parse(body["end_date"]!!.jsonPrimitive.content)
            assertEquals(DEFAULT_WINDOW_DAYS.toLong(), ChronoUnit.DAYS.between(startDate, endDate))
        }

    @Test
    fun `GET availability returns 404 for an unknown POI`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest() } }

            val resp = client.get("/api/pois/$UNKNOWN_POI_ID/campsites/availability")

            assertEquals(HttpStatusCode.NotFound, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("not_found", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET availability rejects a malformed date with 400`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest() } }
            val (poiId, _) = seedRecgovPoiWithCampsite()

            val resp = client.get("/api/pois/$poiId/campsites/availability?start_date=not-a-date")

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_date_window", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET availability is throttled per IP once the budget is spent`() =
        testApplication {
            application {
                routeTestApplication {
                    // One token, frozen clock: the second request must be denied.
                    campsiteRoutesUnderTest(rateLimit = IpRateLimiter(perMinute = 1, nowMs = { 0L }))
                }
            }
            val (poiId, _) = seedRecgovPoiWithCampsite()

            val first = client.get("/api/pois/$poiId/campsites/availability")
            assertEquals(HttpStatusCode.OK, first.status)

            val second = client.get("/api/pois/$poiId/campsites/availability")
            assertEquals(HttpStatusCode.ServiceUnavailable, second.status)
            val body = Json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertEquals("ip_throttled", body["error"]!!.jsonPrimitive.content)
        }
}

/**
 * Recgov adapter fake for the read slice: reports every campsite AVAILABLE for
 * every day of whatever window it is asked for, so the route test controls the
 * whole pipeline without any upstream call.
 */
private class ServingRecgovProvider : AvailabilityProvider {
    override val id: BookingProvider = BookingProvider.RECGOV
    override val capabilities =
        AvailabilityProviderCapabilities(
            supportsInternalPolling = true,
            bookingHorizonDays = 180,
            maxPollWindowDays = 60,
        )

    override fun isEnabled(): Boolean = true

    override suspend fun availability(
        campground: Campground,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch = throw UnsupportedOperationException("catalogAvailability is the read-slice entry point")

    override suspend fun catalogAvailability(
        campground: Campground,
        campsites: List<Campsite>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val observedAt = Instant.now()
        val days = ChronoUnit.DAYS.between(startDate, endDate).toInt()
        return AvailabilityObservationBatch(
            provider = id.id,
            startDate = startDate,
            endDate = endDate,
            observations =
                campsites.flatMap { campsite ->
                    (0 until days).map { offset ->
                        CampsiteDayObservation(
                            campsiteId = campsite.id,
                            date = startDate.plusDays(offset.toLong()),
                            observedAt = observedAt,
                            status = AvailabilityStatus.AVAILABLE,
                        )
                    }
                },
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
        )
    }
}
