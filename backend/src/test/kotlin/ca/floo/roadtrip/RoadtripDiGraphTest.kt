package ca.floo.roadtrip

import ca.floo.roadtrip.clients.aspira.AspiraAvailability
import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraOccupancy
import ca.floo.roadtrip.clients.campflare.CampflareAvailabilityClient
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
import ca.floo.roadtrip.models.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.models.metadata.registry.EtlEntry
import ca.floo.roadtrip.models.metadata.registry.Fetcher
import ca.floo.roadtrip.models.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityService
import ca.floo.roadtrip.service.availability.CampsiteCatalogService
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.koin.ktor.ext.getKoin
import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.sql.Connection
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource
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
private const val TEST_STATIC_DIR_PREFIX = "roadtrip-di-test"
private const val TEST_ASPIRA_HOST = "reservation.pc.gc.ca"
private const val TEST_RESERVEAMERICA_HOST = "reserveamerica.test.invalid"
private const val TEST_RESERVEAMERICA_CONTRACT = "RA"
private const val TEST_RESERVEAMERICA_BOOKING_HORIZON_DAYS = "180"

class RoadtripDiGraphTest : SharedDbTest() {
    @Test
    fun `production dependency graph resolves through Ktor DI and closes resources`() {
        val closeTracker = CloseTracker()
        lateinit var schedulerScope: CoroutineScope

        testApplication {
            application {
                installTestOverrides(closeTracker)
                module()

                val koin = getKoin()
                val catalogService = koin.get<CampsiteCatalogService>()
                val availabilityService = koin.get<CampsiteAvailabilityService>()
                val resolvedSchedulerScope = koin.get<CoroutineScope>()

                schedulerScope = resolvedSchedulerScope
                assertNotNull(catalogService)
                assertNotNull(availabilityService)
            }

            assertEquals(HttpStatusCode.OK, client.get(TEST_HEALTH_PATH).status)
        }

        assertTrue(schedulerScope.coroutineContext[Job]?.isCancelled == true)
        assertEquals(PROVIDER_CLIENT_COUNT, closeTracker.closeCount)
    }

    @Test
    fun `test applications can override dependencies before loading modules`() =
        testApplication {
            val closeTracker = CloseTracker()
            val overrideConfig =
                testAppConfig(
                    readPathProviders =
                        ReadPathProviderConfig(
                            enabledDataSources = setOf(TEST_OVERRIDE_DATA_SOURCE),
                            enabledAvailabilityProviders = emptySet(),
                        ),
                )

            application {
                installTestOverrides(closeTracker, appConfig = overrideConfig)
                module()

                val appConfig: AppConfig by dependencies
                assertSame(overrideConfig, appConfig)
            }

            assertEquals(HttpStatusCode.OK, client.get(TEST_HEALTH_PATH).status)
        }

    private fun Application.installTestOverrides(
        closeTracker: CloseTracker,
        appConfig: AppConfig = testAppConfig(),
    ) {
        dependencies.provide<AppConfig> { appConfig }
        dependencies.provide<DataSource> { NonClosingDataSource(ds) }
        dependencies.provide<File> { Files.createTempDirectory(TEST_STATIC_DIR_PREFIX).toFile() }
        dependencies.provide<PoiRegistry> { fullProviderRegistry() }
        dependencies.provide<RecGovAvailabilityClient> { closeTracker.recGovClient() }
        dependencies.provide<AspiraAvailabilityClient> { closeTracker.aspiraClient() }
        dependencies.provide<ReserveAmericaAvailabilityClient> { closeTracker.reserveAmericaClient() }
        dependencies.provide<ReserveCaliforniaAvailabilityClient> { closeTracker.reserveCaliforniaClient() }
        dependencies.provide<CampflareAvailabilityClient> { closeTracker.campflareClient() }
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

    private fun fullProviderRegistry(): PoiRegistry =
        PoiRegistry(
            dataSources =
                listOf(
                    dataSource("recgov-source"),
                    dataSource("campflare-source"),
                    dataSource("aspira-source"),
                    dataSource("reserveamerica-source"),
                    dataSource("reservecalifornia-source"),
                ),
            poiData =
                listOf(
                    poiData(
                        name = "RecGov",
                        etl =
                            EtlEntry(
                                slug = "federal-campgrounds",
                                adapter = "RecGovCampgroundsEtl",
                                inputs = listOf("recgov-source"),
                            ),
                    ),
                    poiData(
                        name = "Campflare",
                        etl =
                            EtlEntry(
                                slug = "campflare-campgrounds",
                                adapter = "CampflareCampgroundsEtl",
                                inputs = listOf("campflare-source"),
                            ),
                    ),
                    poiData(
                        name = "Aspira",
                        etl =
                            EtlEntry(
                                slug = "aspira-pc-pins",
                                adapter = "AspiraJoinByNameEtl",
                                inputs = listOf("aspira-source"),
                                args = mapOf("host" to TEST_ASPIRA_HOST),
                            ),
                    ),
                    poiData(
                        name = "ReserveAmerica",
                        etl =
                            EtlEntry(
                                slug = "alberta-provincial",
                                adapter = "ReserveAmericaEtl",
                                inputs = listOf("reserveamerica-source"),
                                args =
                                    mapOf(
                                        "host" to TEST_RESERVEAMERICA_HOST,
                                        "contract" to TEST_RESERVEAMERICA_CONTRACT,
                                        "booking_horizon_days" to TEST_RESERVEAMERICA_BOOKING_HORIZON_DAYS,
                                    ),
                            ),
                    ),
                    poiData(
                        name = "ReserveCalifornia",
                        etl =
                            EtlEntry(
                                slug = "california-state-parks",
                                adapter = "ReserveCaliforniaEtl",
                                inputs = listOf("reservecalifornia-source"),
                            ),
                    ),
                ),
        )

    private fun dataSource(slug: String): DataSourceEntry =
        DataSourceEntry(
            slug = slug,
            name = slug,
            fetcher =
                Fetcher(
                    executor = "noop",
                    filename = "$slug.json",
                    outputDirPrefix = slug,
                ),
        )

    private fun poiData(
        name: String,
        etl: EtlEntry,
    ): PoiDataEntry =
        PoiDataEntry(
            name = name,
            category = "campground",
            etls = listOf(etl),
        )

    private class CloseTracker {
        private val closed = AtomicInteger()

        val closeCount: Int get() = closed.get()

        private fun closeOne() {
            closed.incrementAndGet()
        }

        fun recGovClient(): RecGovAvailabilityClient =
            object : RecGovAvailabilityClient {
                override suspend fun fetchMonth(
                    campgroundId: String,
                    monthStart: String,
                ): Map<String, Campsite> = unexpectedAvailabilityFetch()

                override fun close() = closeOne()
            }

        fun aspiraClient(): AspiraAvailabilityClient =
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

        fun reserveAmericaClient(): ReserveAmericaAvailabilityClient =
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

        fun reserveCaliforniaClient(): ReserveCaliforniaAvailabilityClient =
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

        fun campflareClient(): CampflareAvailabilityClient =
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

    private class NonClosingDataSource(
        private val delegate: DataSource,
    ) : DataSource {
        override fun getConnection(): Connection = delegate.connection

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = delegate.getConnection(username, password)

        override fun getLogWriter(): PrintWriter? = delegate.logWriter

        override fun setLogWriter(out: PrintWriter?) {
            delegate.logWriter = out
        }

        override fun setLoginTimeout(seconds: Int) {
            delegate.loginTimeout = seconds
        }

        override fun getLoginTimeout(): Int = delegate.loginTimeout

        override fun getParentLogger(): Logger = delegate.parentLogger

        override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)

        override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)
    }
}
