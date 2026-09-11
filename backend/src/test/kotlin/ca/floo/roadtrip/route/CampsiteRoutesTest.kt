package ca.floo.roadtrip.route

import ca.floo.roadtrip.client.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.fixtures.testCampsiteCatalogService
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.metadata.registry.TenantRegistry
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.route.api.pois.campsiteRoutes
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.BookingHorizonResolver
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityController
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityService
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.ProviderCooldownTracker
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.CampflareAvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import ca.floo.roadtrip.service.ratelimit.IpRateLimiter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UNKNOWN_POI_ID = 999_999L
private const val DEFAULT_WINDOW_DAYS = 7

// The serving fake's horizon.
private const val TEST_BOOKING_HORIZON_DAYS = 180L

// The aliased Campflare site's own reservation page: a rec.gov campsite, which
// is what makes the template's host and the row's booking_system comparable.
private const val ALIASED_CAMPSITE_RESERVATION_URL = "https://www.recreation.gov/camping/campsites/10174516"

/** `tenant:transactionLocationId:mapId:resourceLocationId` on the BC Parks tenant. */
private const val BC_PARKS_CAMPGROUND_REF = "bc:1:-2147483470:null"

// Every nullable campsite column the recgov ETL leaves unwritten, in the wire
// names `CampsiteDto` serves them under.
private val unwrittenCampsiteFields =
    listOf(
        "description",
        "min_people",
        "firepit",
        "picnic_table",
        "ada_accessible",
        "water_hookups",
        "electric_hookups",
        "sewer_hookups",
        "max_people",
        "max_cars",
        "pull_through",
        "driveway_length",
        "max_rv_length",
        "max_trailer_length",
    )

// Columns of the `campsites` row that no client renders: the raw source
// payload, the bookkeeping timestamps, the vendor blobs and the coordinates.
private val neverOnTheWire =
    listOf(
        "source_payload",
        "schedule",
        "price",
        "created_at",
        "updated_at",
        "deleted_at",
        "booking_provider_ref",
        "latitude",
        "longitude",
    )

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
            catalogService = testCampsiteCatalogService(ctx, campsitesRepo, targets),
            availabilityService =
                CampsiteAvailabilityService(
                    availabilityProviders = providers,
                    dateResolver = dateResolver,
                    failoverFetcher = FailoverAvailabilityFetcher(cooldowns = ProviderCooldownTracker(cooldown = Duration.ofMinutes(1))),
                    bookingHorizons = BookingHorizonResolver(providers, dateResolver),
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

    /** POI 8149's shape: a Campflare row rec.gov also sells, campground and site alike. */
    private fun seedAliasedCampflarePoiWithCampsite(): Long {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "icicle-group-campground-8149",
                name = "Icicle Group Campground",
                lon = -120.78,
                lat = 47.55,
                source = "campflare",
                bookingProvider = "campflare",
                bookingProviderRef = "icicle-group-campground-8149",
                bookingAliasesJson = """[{"provider":"recgov","ref":"234784"}]""",
            )
        ctx.seedCampsite(
            campgroundId = fixture.catalogId,
            vendor = "campflare",
            vendorId = "campflare-site-10",
            reservationUrl = ALIASED_CAMPSITE_RESERVATION_URL,
            bookingProvider = "campflare",
            bookingProviderRef = "campflare-site-10",
            bookingAliasesJson = """[{"provider":"recgov","ref":"10174516"}]""",
        )
        return fixture.poiId
    }

    /** A Campflare row no other vendor sells: the site a person books on is Campflare's own. */
    private fun seedCampflareOnlyPoiWithCampsite(): Long {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "white-wolf-campground-567",
                name = "White Wolf",
                lon = -119.65,
                lat = 37.87,
                source = "campflare",
                bookingProvider = "campflare",
                bookingProviderRef = "white-wolf-campground-567",
            )
        ctx.seedCampsite(
            campgroundId = fixture.catalogId,
            vendor = "campflare",
            vendorId = "campflare-site-11",
            bookingProvider = "campflare",
            bookingProviderRef = "campflare-site-11",
        )
        return fixture.poiId
    }

    /** One vendor, one tenant, no alias: the plain case the aliased one is read against. */
    private fun seedBcParksPoiWithCampsite(): Long {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "1:-2147483470",
                name = "Alouette Lake",
                lon = -122.48,
                lat = 49.29,
                source = "aspira",
                bookingProvider = "aspira",
                bookingProviderRef = BC_PARKS_CAMPGROUND_REF,
            )
        ctx.seedCampsite(
            campgroundId = fixture.catalogId,
            vendor = "aspira",
            vendorId = "bc:9001",
            bookingProvider = "aspira",
            bookingProviderRef = "9001",
        )
        return fixture.poiId
    }

    /** Campflare serves the aliased row; the fake Aspira adapter serves the BC one. */
    private fun tenantProviders(): List<AvailabilityProvider> =
        listOf(
            CampflareAvailabilityProvider(
                CampflareAvailabilityClient { _, _, _ -> error("Campflare availability client should not be called") },
                enabled = true,
                configured = true,
            ),
            FakeAvailabilityProvider(BookingProvider.ASPIRA),
        )

    @Test
    fun `campsite rows carry the booking site name, aliased and plain`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest(providers = tenantProviders()) } }
            val aliasedPoiId = seedAliasedCampflarePoiWithCampsite()
            val bcPoiId = seedBcParksPoiWithCampsite()

            val body = client.get("/api/pois/$aliasedPoiId/campsites").bodyAsText()
            val rows = Json.parseToJsonElement(body).jsonObject["campsites"]!!.jsonArray
            assertEquals(
                "Recreation.gov",
                rows
                    .single()
                    .jsonObject["booking_system"]
                    ?.jsonPrimitive
                    ?.content,
            )

            val plain = Json.parseToJsonElement(client.get("/api/pois/$bcPoiId/campsites").bodyAsText()).jsonObject
            assertEquals(
                "BC Parks",
                plain["campsites"]!!
                    .jsonArray
                    .single()
                    .jsonObject["booking_system"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    /**
     * No enabled availability provider claims the campground, so the row has no
     * resolved target at all. The registry still knows who sells it, so the row
     * must read the same here as where rec.gov is wired up.
     */
    @Test
    fun `campsite rows keep their booking site when no availability provider is enabled`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest(providers = emptyList()) } }
            val aliasedPoiId = seedAliasedCampflarePoiWithCampsite()

            val body = client.get("/api/pois/$aliasedPoiId/campsites").bodyAsText()
            val row =
                Json
                    .parseToJsonElement(body)
                    .jsonObject["campsites"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("Recreation.gov", row["booking_system"]?.jsonPrimitive?.content)
        }

    /** The campsite branch of the same rule: no vendor sells it but the one serving it. */
    @Test
    fun `a Campflare-only row names Campflare`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest(providers = tenantProviders()) } }
            val poiId = seedCampflareOnlyPoiWithCampsite()

            val body = client.get("/api/pois/$poiId/campsites").bodyAsText()
            val row =
                Json
                    .parseToJsonElement(body)
                    .jsonObject["campsites"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("Campflare", row["booking_system"]?.jsonPrimitive?.content)
        }

    /**
     * The reservation template comes from the *serving* availability provider
     * (CampsiteCatalogService → targets.resolve(campsite).provider), while
     * booking_system comes from the identity resolver. On an aliased Campflare
     * row served by Campflare those are different objects, so pin that they
     * still name the same vendor: CampflareAvailabilityProvider builds its
     * template with RecGovBookingUrl, and the row sells on rec.gov.
     */
    @Test
    fun `an aliased Campflare row's template host and booking_system agree`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest(providers = tenantProviders()) } }
            val aliasedPoiId = seedAliasedCampflarePoiWithCampsite()

            val body = Json.parseToJsonElement(client.get("/api/pois/$aliasedPoiId/campsites").bodyAsText()).jsonObject
            val row = body["campsites"]!!.jsonArray.single().jsonObject
            val template =
                body["reservation_url_templates"]!!
                    .jsonObject
                    .values
                    .single()
                    .jsonPrimitive
                    .content
            // The template still carries its window placeholders, which are not
            // legal URI characters; the host is everything before the query.
            val host = TenantRegistry.normalizeHost(URI(template.substringBefore('?')).host)
            assertEquals("recreation.gov", host)
            assertEquals("Recreation.gov", row["booking_system"]?.jsonPrimitive?.content)
            assertEquals(
                "Recreation.gov",
                shippedTenantRegistry().tenantByHost(host)?.displayName,
            )
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

    // The recgov ETL never writes these columns. Reading them with the JVM
    // primitive class turned every absent fact into "no firepit", "sleeps 0",
    // "at null island" — served as if the source had said so.
    @Test
    fun `GET campsites serves unwritten facts as null, not zero or false`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest() } }
            val (poiId, _) = seedRecgovPoiWithCampsite()

            val resp = client.get("/api/pois/$poiId/campsites")

            assertEquals(HttpStatusCode.OK, resp.status)
            val campsite =
                Json
                    .parseToJsonElement(resp.bodyAsText())
                    .jsonObject["campsites"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            // `explicitNulls = false` on the wire, so an absent fact is an absent key.
            val fabricated =
                unwrittenCampsiteFields.filter { field ->
                    val value = campsite[field]
                    value != null && value != JsonNull
                }
            assertEquals(emptyList(), fabricated, "facts the recgov source never asserted")
        }

    @Test
    fun `GET campsites keeps the row-only columns off the wire`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest() } }
            val (poiId, _) = seedRecgovPoiWithCampsite()

            val resp = client.get("/api/pois/$poiId/campsites")

            assertEquals(HttpStatusCode.OK, resp.status)
            val leaked =
                Json
                    .parseToJsonElement(resp.bodyAsText())
                    .jsonObject["campsites"]!!
                    .jsonArray
                    .flatMap { campsite -> neverOnTheWire.filter(campsite.jsonObject::containsKey) }
            assertEquals(emptyList(), leaked, "columns the API must not serve")
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
    fun `GET availability returns the fused week with watch capabilities`() =
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
            // The picker's ceiling is the serving provider's booking horizon.
            assertEquals(
                startDate.plusDays(TEST_BOOKING_HORIZON_DAYS).toString(),
                body["latest_date"]!!.jsonPrimitive.content,
            )
            assertEquals("success", body["state"]!!.jsonPrimitive.content)
            assertEquals(false, body["cache"]!!.jsonObject["hit"]!!.jsonPrimitive.boolean)

            // Watch capabilities are computed from the same campsite set.
            val capabilities = body["watch_capabilities"]!!.jsonObject
            assertEquals(
                listOf("slack_notify", "email_notify"),
                capabilities["trigger_kinds"]!!.jsonArray.map { it.jsonPrimitive.content },
            )

            val days = body["days"]!!.jsonArray
            assertEquals(DEFAULT_WINDOW_DAYS, days.size)
            assertEquals(
                Json.parseToJsonElement(
                    """
                    {"date":"$startDate","status":"available","watchable":false,
                     "cells":{"$campsiteId":{"status":"available","watchable":false}}}
                    """.trimIndent(),
                ),
                days.first(),
            )
            assertTrue(days.all { it.jsonObject["status"]!!.jsonPrimitive.content == "available" })
        }

    @Test
    fun `GET availability marks a reserved day watchable on a polling provider`() =
        testApplication {
            application {
                routeTestApplication { campsiteRoutesUnderTest(listOf(ServingRecgovProvider(AvailabilityStatus.RESERVED))) }
            }
            val (poiId, campsiteId) = seedRecgovPoiWithCampsite()

            val resp = client.get("/api/pois/$poiId/campsites/availability")

            assertEquals(HttpStatusCode.OK, resp.status)
            val day =
                Json
                    .parseToJsonElement(resp.bodyAsText())
                    .jsonObject["days"]!!
                    .jsonArray
                    .first()
                    .jsonObject
            assertEquals(true, day["watchable"]!!.jsonPrimitive.boolean)
            assertEquals(
                true,
                day["cells"]!!
                    .jsonObject["$campsiteId"]!!
                    .jsonObject["watchable"]!!
                    .jsonPrimitive.boolean,
            )
        }

    @Test
    fun `GET availability with a site_type filter matching nothing returns an empty window`() =
        testApplication {
            // No provider registered, so the campground has none to resolve either.
            application { routeTestApplication { campsiteRoutesUnderTest(providers = emptyList()) } }
            val (poiId, _) = seedRecgovPoiWithCampsite()

            val resp = client.get("/api/pois/$poiId/campsites/availability?site_type=rv")

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(poiId, body["poi_id"]!!.jsonPrimitive.long)
            assertEquals("empty", body["state"]!!.jsonPrimitive.content)
            assertTrue(body["days"]!!.jsonArray.isEmpty())
            assertNull(body["cache"])
            val startDate = LocalDate.parse(body["start_date"]!!.jsonPrimitive.content)
            val endDate = LocalDate.parse(body["end_date"]!!.jsonPrimitive.content)
            assertEquals(DEFAULT_WINDOW_DAYS.toLong(), ChronoUnit.DAYS.between(startDate, endDate))
            // No provider resolves for the campground, so there is no horizon to
            // state: the fallback bounds the window, it is not a ceiling to publish.
            assertNull(body["latest_date"])
        }

    @Test
    fun `GET campsites rejects a site_type outside the wire vocabulary`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest() } }
            val (poiId, _) = seedRecgovPoiWithCampsite()

            val resp = client.get("/api/pois/$poiId/campsites?site_type=STANDARD%20NONELECTRIC")

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_request", body["error"]!!.jsonPrimitive.content)
            assertTrue(body["detail"]!!.jsonPrimitive.content.contains("STANDARD NONELECTRIC"))
        }

    @Test
    fun `GET availability rejects a site_type outside the wire vocabulary`() =
        testApplication {
            application { routeTestApplication { campsiteRoutesUnderTest() } }
            val (poiId, _) = seedRecgovPoiWithCampsite()

            val resp = client.get("/api/pois/$poiId/campsites/availability?site_type=tent,walk-in")

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_request", body["error"]!!.jsonPrimitive.content)
            assertTrue(body["detail"]!!.jsonPrimitive.content.contains("walk-in"))
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
private class ServingRecgovProvider(
    private val status: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
) : AvailabilityProvider {
    override val id: BookingProvider = BookingProvider.RECGOV
    override val capabilities =
        AvailabilityProviderCapabilities(
            supportsInternalPolling = true,
            bookingHorizonDays = TEST_BOOKING_HORIZON_DAYS.toInt(),
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
                            status = status,
                        )
                    }
                },
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
        )
    }
}
