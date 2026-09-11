package ca.floo.roadtrip.di

import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.client.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.client.mapbox.MapboxDirections
import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.client.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.model.metadata.registry.TenantRegistry
import ca.floo.roadtrip.observability.RoadtripMetrics
import ca.floo.roadtrip.repo.ImportRunRepo
import ca.floo.roadtrip.repo.IngestRunRepo
import ca.floo.roadtrip.repo.JooqUnitOfWork
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.Repos
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.UnitOfWork
import ca.floo.roadtrip.repo.UserIdentityRepo
import ca.floo.roadtrip.repo.UserSessionRepo
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.booking.BookingAdapter
import ca.floo.roadtrip.service.booking.RecentAtcFires
import ca.floo.roadtrip.service.geocode.GeocodeService
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RoutePlanService
import ca.floo.roadtrip.service.settings.RecGovCredentialService
import ca.floo.roadtrip.service.settings.UserSettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

private const val POI_REGISTRY_RESOURCE = "poi-registry.yaml"
private const val UNUSED_CLIENT = "no availability fetch happens while resolving the graph"

/**
 * The pieces `repoModule` and `serviceModule` expect `infraModule` to bring:
 * the geocoder and the route cache the route/geocode services take, plus the
 * registries, metrics and scope the watch graph reaches for. Stubbed rather
 * than real so resolving the graph opens no pool and reaches no vendor.
 */
private fun infraStubs(): Module =
    module {
        single { PoiRegistry.loadResource(POI_REGISTRY_RESOURCE) }
        single { TenantRegistry.from(get<PoiRegistry>()) }
        single<RoadtripMetrics> { RoadtripMetrics.NoOp }
        single { CoroutineScope(Dispatchers.Unconfined + SupervisorJob()) }
        single { MapboxGeocoder(token = null) }
        single { RouteCache(MapboxDirections(token = null)) }
        single<RecGovAvailabilityClient> {
            object : RecGovAvailabilityClient {
                override suspend fun fetchMonth(
                    campgroundId: String,
                    monthStart: String,
                ) = error(UNUSED_CLIENT)
            }
        }
        single<AspiraAvailabilityClient> {
            object : AspiraAvailabilityClient {
                override suspend fun fetch(
                    host: String,
                    mapId: Int,
                    startDate: LocalDate,
                    endDate: LocalDate,
                ) = error(UNUSED_CLIENT)

                override suspend fun fetchOccupancy(
                    host: String,
                    resourceLocationId: Int,
                    startDate: LocalDate,
                    endDate: LocalDate,
                ) = error(UNUSED_CLIENT)
            }
        }
        single<ReserveAmericaAvailabilityClient> {
            ReserveAmericaAvailabilityClient { _, _, _, _, _ -> error(UNUSED_CLIENT) }
        }
        single<ReserveCaliforniaAvailabilityClient> {
            object : ReserveCaliforniaAvailabilityClient {
                override suspend fun fetchGrid(
                    facilityId: Long,
                    startDate: LocalDate,
                    endDate: LocalDate,
                    minDate: LocalDate,
                    maxDate: LocalDate,
                ) = error(UNUSED_CLIENT)
            }
        }
        single<CampflareAvailabilityClient> {
            CampflareAvailabilityClient { _, _, _ -> error(UNUSED_CLIENT) }
        }
    }

/**
 * Boot-time DI regression guard.
 *
 * `UserSettingsService`'s optional deps (the AES-GCM cipher and the per-user
 * Slack client) are null when no encryption key / Slack is configured — the
 * default for a fresh install, and `RecGovCredentialService` has the same shape
 * with its cipher and its companion client. They must be built INLINE in the service's
 * definition, never registered as `single<T?>`: a Koin `single { }` that
 * produces null throws at resolution ("Single instance created couldn't return
 * value"), which crashed application boot. This test resolves the service from
 * the real [serviceModule] with that exact null-yielding config.
 */
class ServiceModuleWiringTest : SharedDbTest() {
    @Test
    fun `the settings services resolve when no encryption key, Slack or companion is configured`() {
        // auth/secrets/slack keys omitted => those sections resolve to null: the exact
        // config that crashed boot. Only the two globally-required durations are supplied.
        val config =
            AppConfig.fromProperties(
                mapOf(
                    "roadtrip.availability.force-pull-cooldown" to "5m",
                    "roadtrip.availability.provider-cooldown" to "5m",
                ),
            )

        // createEagerInstances = false: resolve only UserSettingsService's own graph,
        // not the module's eager schedulers (which need a CoroutineScope etc.).
        val app =
            koinApplication(createEagerInstances = false) {
                modules(
                    repoModule,
                    serviceModule,
                    module {
                        single { config }
                        // The repos only capture the DSLContext at construction; a real
                        // one from the shared test container keeps the graph honest.
                        single<DSLContext> { ctx }
                    },
                )
            }

        try {
            assertNotNull(
                app.koin.get<UserSettingsService>(),
                "UserSettingsService must resolve with a null cipher and null Slack client",
            )
            assertNotNull(
                app.koin.get<RecGovCredentialService>(),
                "RecGovCredentialService must resolve with a null cipher and no companion",
            )
        } finally {
            app.close()
        }
    }

    @Test
    fun `the unit of work, its repo handles and the services that take them all resolve`() {
        // Every single named here is one this branch added or moved, and none
        // of them is on the path of the two cases above: loading both real
        // modules proves nothing unless something resolves them.
        val config =
            AppConfig.fromProperties(
                mapOf(
                    "roadtrip.availability.force-pull-cooldown" to "5m",
                    "roadtrip.availability.provider-cooldown" to "5m",
                ),
            )

        val app =
            koinApplication(createEagerInstances = false) {
                modules(
                    repoModule,
                    serviceModule,
                    module {
                        single { config }
                        single<DSLContext> { ctx }
                    },
                    infraStubs(),
                )
            }

        try {
            assertNotNull(app.koin.get<UnitOfWork>(), "the port every transacting service takes")
            assertNotNull(app.koin.get<Repos>(), "the autocommit bundle the ETL sinks take")
            assertNotNull(app.koin.get<JooqUnitOfWork>())
            assertNotNull(app.koin.get<RoutePlanService>())
            assertNotNull(app.koin.get<GeocodeService>())
            assertNotNull(app.koin.get<AvailabilityWatchService>())
            assertNotNull(app.koin.get<UserIdentityRepo>())
            assertNotNull(app.koin.get<UserSessionRepo>())
            assertNotNull(app.koin.get<ImportRunRepo>())
            assertNotNull(app.koin.get<IngestRunRepo>())
            assertNotNull(app.koin.get<PoiServingRepo>(), "moved from serviceModule to repoModule")

            assertSame(
                app.koin.get<Repos>(),
                app.koin.get<JooqUnitOfWork>().autocommit,
                "the Repos single must BE the unit of work's autocommit bundle, not a second one",
            )
        } finally {
            app.close()
        }
    }

    @Test
    fun `every companion caller shares one client`() {
        // Three callers each built their own HttpClient — three selector threads
        // and three pools to one service — with the enabled-check copy-pasted
        // beside each. One single, resolved by all of them.
        val config =
            AppConfig.fromProperties(
                mapOf(
                    "roadtrip.availability.force-pull-cooldown" to "5m",
                    "roadtrip.availability.provider-cooldown" to "5m",
                    "roadtrip.booking.recgov-atc.companion-base-url" to "http://companion.invalid:8770",
                ),
            )

        val app =
            koinApplication(createEagerInstances = false) {
                modules(
                    repoModule,
                    serviceModule,
                    module {
                        single { config }
                        single<DSLContext> { ctx }
                    },
                )
            }

        try {
            val channel = app.koin.get<CompanionChannel>()
            assertNotNull(channel.session, "a configured companion must yield a session client")
            assertNotNull(channel.atc, "the same client is the ATC transport — there is no second one")
            assertSame(channel, app.koin.get<CompanionChannel>(), "the channel must be a single, not per-resolution")

            // The booking adapters exist only with a companion configured, so
            // this is the one place the ATC graph gets built end to end.
            assertEquals(
                1,
                app.koin.get<List<BookingAdapter>>(named("bookingAdapters")).size,
                "a configured companion must yield the rec.gov booking adapter",
            )
            assertSame(
                app.koin.get<RecentAtcFires>(),
                app.koin.get<RecentAtcFires>(),
                "the adapter that records a fire and the sweep that reads it must share one view",
            )
        } finally {
            app.close()
        }
    }
}
