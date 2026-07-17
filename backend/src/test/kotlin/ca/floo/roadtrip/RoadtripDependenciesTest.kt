package ca.floo.roadtrip

import ca.floo.roadtrip.clients.aspira.AspiraAvailability
import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraOccupancy
import ca.floo.roadtrip.clients.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.clients.mapbox.MapboxDirections
import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.clients.recgov.Campsite
import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.config.ApiCacheConfig
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.config.AvailabilityConfig
import ca.floo.roadtrip.config.BookingConfig
import ca.floo.roadtrip.config.CampflareConfig
import ca.floo.roadtrip.config.ReadPathProviderConfig
import ca.floo.roadtrip.config.RecGovAtcConfig
import ca.floo.roadtrip.config.VendorRateLimitConfig
import ca.floo.roadtrip.models.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.models.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityService
import ca.floo.roadtrip.service.availability.CampsiteCatalogService
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderClients
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.routing.RouteCache
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Job
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val PROVIDER_CLIENT_COUNT = 5
private const val TEST_CAMPFLARE_API_BASE = "https://campflare.test.invalid"
private const val TEST_DURATION_SECONDS = 1L
private const val TEST_HEALTH_PATH = "/api/health"
private const val TEST_OVERRIDE_DATA_SOURCE = "override-source"
private const val TEST_RAW_DATA_DIR = "data/raw"
private const val TEST_STATIC_DIR_PREFIX = "roadtrip-di-test"

class RoadtripDependenciesTest : SharedDbTest() {
    @Test
    fun `production dependency graph resolves through Ktor DI and closes runtime`() {
        val closeTracker = CloseTracker()
        val boot = testBootContext(closeTracker)
        lateinit var runtime: RoadtripRuntime

        testApplication {
            application {
                installRoadtripDependencies(boot)
                module()

                val resolvedRuntime: RoadtripRuntime by dependencies
                val catalogService: CampsiteCatalogService by dependencies
                val availabilityService: CampsiteAvailabilityService by dependencies

                runtime = resolvedRuntime
                assertNotNull(catalogService)
                assertNotNull(availabilityService)
            }

            assertEquals(HttpStatusCode.OK, client.get(TEST_HEALTH_PATH).status)
        }

        assertTrue(runtime.schedulerScope.coroutineContext[Job]?.isCancelled == true)
        assertEquals(PROVIDER_CLIENT_COUNT, closeTracker.closeCount)
    }

    @Test
    fun `test applications can override dependencies before loading modules`() =
        testApplication {
            val closeTracker = CloseTracker()
            val boot = testBootContext(closeTracker)
            val overrideConfig =
                testAppConfig(
                    readPathProviders =
                        ReadPathProviderConfig(
                            enabledDataSources = setOf(TEST_OVERRIDE_DATA_SOURCE),
                            enabledAvailabilityProviders = emptySet(),
                        ),
                )

            application {
                dependencies.provide<AppConfig> { overrideConfig }
                installRoadtripDependencies(boot)
                module()

                val appConfig: AppConfig by dependencies
                assertSame(overrideConfig, appConfig)
            }

            assertEquals(HttpStatusCode.OK, client.get(TEST_HEALTH_PATH).status)
        }

    private fun testBootContext(closeTracker: CloseTracker): RoadtripBootContext {
        val poiRegistry = PoiRegistry(dataSources = emptyList(), poiData = emptyList())
        val staticDir = Files.createTempDirectory(TEST_STATIC_DIR_PREFIX).toFile()
        return RoadtripBootContext(
            properties = emptyMap(),
            appConfig = testAppConfig(),
            dataSource = ds,
            ctx = ctx,
            availabilityProviderClients = closeTracker.clients(),
            staticDir = staticDir,
            mapboxGeocoder = MapboxGeocoder(token = null),
            routeCache = RouteCache(MapboxDirections(token = null)),
            poiRegistry = poiRegistry,
            ingestController =
                IngestController(
                    ctx = ctx,
                    etl = EtlOrchestrator(ctx = ctx, rawDir = staticDir.resolve(TEST_RAW_DATA_DIR), poiRegistry = poiRegistry),
                    fetchTargets = emptyMap(),
                    importTargets = emptyMap(),
                    workingDir = staticDir,
                ),
        )
    }

    private fun testAppConfig(
        readPathProviders: ReadPathProviderConfig =
            ReadPathProviderConfig(
                enabledDataSources = emptySet(),
                enabledAvailabilityProviders = emptySet(),
            ),
    ): AppConfig {
        val duration = Duration.ofSeconds(TEST_DURATION_SECONDS)
        return AppConfig(
            availability =
                AvailabilityConfig(
                    forcePullCooldown = duration,
                    providerCooldown = duration,
                ),
            booking =
                BookingConfig(
                    recgovAtc =
                        RecGovAtcConfig(
                            companionBaseUrl = null,
                            companionTimeout = duration,
                        ),
                ),
            cache = ApiCacheConfig(emptyMap()),
            campflare =
                CampflareConfig(
                    apiKey = null,
                    apiBaseUrl = TEST_CAMPFLARE_API_BASE,
                ),
            email = null,
            readPathProviders = readPathProviders,
            slack = null,
            grafana = null,
            webApp = null,
            vendorRateLimit = VendorRateLimitConfig(),
        )
    }

    private class CloseTracker {
        private val closed = AtomicInteger()

        val closeCount: Int get() = closed.get()

        fun clients(): AvailabilityProviderClients =
            AvailabilityProviderClients(
                recgovClient = trackedRecGovClient(),
                aspiraClient = trackedAspiraClient(),
                reserveAmericaClient = trackedReserveAmericaClient(),
                reserveCaliforniaClient = trackedReserveCaliforniaClient(),
                campflareClient = trackedCampflareClient(),
            )

        private fun closeOne() {
            closed.incrementAndGet()
        }

        private fun trackedRecGovClient(): RecGovAvailabilityClient =
            object : RecGovAvailabilityClient {
                override suspend fun fetchMonth(
                    campgroundId: String,
                    monthStart: String,
                ): Map<String, Campsite> = unexpectedAvailabilityFetch()

                override fun close() = closeOne()
            }

        private fun trackedAspiraClient(): AspiraAvailabilityClient =
            object : AspiraAvailabilityClient {
                override suspend fun fetch(
                    host: String,
                    mapId: Int,
                    startDate: LocalDate,
                    endDate: LocalDate,
                ): AspiraAvailability = unexpectedAvailabilityFetch()

                override suspend fun fetchOccupancy(
                    host: String,
                    resourceLocationId: Int,
                    startDate: LocalDate,
                    endDate: LocalDate,
                ): AspiraOccupancy = unexpectedAvailabilityFetch()

                override fun close() = closeOne()
            }

        private fun trackedReserveAmericaClient(): ReserveAmericaAvailabilityClient =
            object : ReserveAmericaAvailabilityClient {
                override suspend fun fetch(
                    host: String,
                    contractCode: String,
                    parkId: String,
                    startDate: LocalDate,
                    endDate: LocalDate,
                ): ReserveAmericaAvailability = unexpectedAvailabilityFetch()

                override fun close() = closeOne()
            }

        private fun trackedReserveCaliforniaClient(): ReserveCaliforniaAvailabilityClient =
            object : ReserveCaliforniaAvailabilityClient {
                override suspend fun fetchGrid(
                    facilityId: Long,
                    startDate: LocalDate,
                    endDate: LocalDate,
                    minDate: LocalDate,
                    maxDate: LocalDate,
                ): ReserveCaliforniaGridAvailability = unexpectedAvailabilityFetch()

                override fun close() = closeOne()
            }

        private fun trackedCampflareClient(): CampflareAvailabilityClient =
            object : CampflareAvailabilityClient {
                override suspend fun fetchAvailability(
                    campgroundIds: List<String>,
                    startDate: LocalDate,
                    endDate: LocalDate,
                ): CampflareAvailability = unexpectedAvailabilityFetch()

                override fun close() = closeOne()
            }

        private fun unexpectedAvailabilityFetch(): Nothing = error("DI lifecycle tests must not call availability clients")
    }
}
