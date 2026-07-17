package ca.floo.roadtrip

import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.HttpAspiraAvailabilityClient
import ca.floo.roadtrip.clients.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.clients.campflare.HttpCampflareAvailabilityClient
import ca.floo.roadtrip.clients.companion.HttpRecGovAtcExecutor
import ca.floo.roadtrip.clients.mapbox.MapboxDirections
import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.clients.recgov.HttpRecgovAvailabilityClient
import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.HttpReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.HttpReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.clients.slack.SlackSignatureVerifier
import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.config.ApplicationProperties
import ca.floo.roadtrip.config.ConfigSection
import ca.floo.roadtrip.config.DbConfig
import ca.floo.roadtrip.config.ReadPathProviderConfig
import ca.floo.roadtrip.db.dataSourceFor
import ca.floo.roadtrip.db.dsl
import ca.floo.roadtrip.db.migrate
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.AdminIngestReadRepo
import ca.floo.roadtrip.repo.ApiCacheRepo
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.CanonicalViewRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.routes.IP_RATE_LIMIT_PER_MINUTE
import ca.floo.roadtrip.routes.IpRateLimiter
import ca.floo.roadtrip.service.availability.AtcTriggerActionHandler
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.AvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityTriggerKinds
import ca.floo.roadtrip.service.availability.AvailabilityWatchApiMapper
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.CampgroundAvailabilitySupport
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityComposer
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityService
import ca.floo.roadtrip.service.availability.CampsiteCatalogService
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.CoordinateTimeZones
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.NotifyTriggerActionHandler
import ca.floo.roadtrip.service.availability.ProviderCooldownTracker
import ca.floo.roadtrip.service.availability.TriggerActionRegistry
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.availability.WatchCapabilityValidator
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.availability.WatchTriggerCapabilityValidator
import ca.floo.roadtrip.service.availability.alert.AlertProvider
import ca.floo.roadtrip.service.availability.alert.AlertProviderRegistry
import ca.floo.roadtrip.service.availability.alert.InternalPollerAlertProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderBinding
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraTenant
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraTenants
import ca.floo.roadtrip.service.availability.provider.adapters.campflare.CampflareAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.RecGovAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaTenant
import ca.floo.roadtrip.service.availability.provider.adapters.reservecalifornia.ReserveCaliforniaAvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.booking.RecGovAtcExecutor
import ca.floo.roadtrip.service.booking.adapters.RecGovBookingProvider
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.etl.framework.fetchTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.importTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.sweepStaleIngestRuns
import ca.floo.roadtrip.service.notification.common.NotificationFanout
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.NotificationService
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import ca.floo.roadtrip.service.notification.slack.SlackInteractivityHandler
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import ca.floo.roadtrip.service.poi.CampgroundService
import ca.floo.roadtrip.service.poi.DEFAULT_POI_TYPES
import ca.floo.roadtrip.service.poi.PlanetFitnessLocationService
import ca.floo.roadtrip.service.poi.PoiDetailService
import ca.floo.roadtrip.service.poi.PoiReader
import ca.floo.roadtrip.service.poi.PoiService
import ca.floo.roadtrip.service.poi.PoisOnRouteService
import ca.floo.roadtrip.service.poi.TeslaSuperchargerService
import ca.floo.roadtrip.service.ratelimit.VendorRateLimiter
import ca.floo.roadtrip.service.readpath.ReadPathProviderPoiReader
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RouteCorridorService
import ca.floo.roadtrip.service.scheduler.PollerBackfill
import ca.floo.roadtrip.service.scheduler.WatchReaper
import ca.floo.roadtrip.service.scheduler.framework.Scheduler
import ca.floo.roadtrip.service.scheduler.jobs.AvailabilityPollExecutor
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.di.DependencyKey
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jooq.DSLContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory
import java.io.File
import javax.sql.DataSource
import org.koin.ktor.plugin.Koin as KoinPlugin

private val mainLog = LoggerFactory.getLogger("ca.floo.roadtrip.Main")

private const val LOG_SHUTDOWN_THREADS_KEY = "log-shutdown-threads"
private const val ENV_FLAG_TRUE = "true"
private const val STATIC_DIR_KEY = "static-dir"
private const val DEFAULT_STATIC_DIR = "."
private const val POI_REGISTRY_RESOURCE_KEY = "resource"
private const val POI_REGISTRY_PATH_KEY = "path"
private const val MAPBOX_TOKEN_KEY = "token"
private const val DEFAULT_POI_REGISTRY_RESOURCE = "poi-registry.yaml"
private const val RAW_DATA_DIR = "data/raw"
private const val SCHEDULER_NAME_AVAILABILITY = "availability"
private const val CAMPFLARE_VENDOR = "campflare"
private const val RECGOV_VENDOR = "recgov"
private const val KOIN_AVAILABILITY_PROVIDER_QUALIFIER_PREFIX = "availability-provider"
private const val KOIN_BOOKING_PROVIDER_QUALIFIER_PREFIX = "booking-provider"

fun main(args: Array<String>): Unit = EngineMain.main(args)

private fun installOptionalShutdownThreadDump(properties: Map<String, String>) {
    val diagnosticsConfig = ConfigSection(properties).section("roadtrip.diagnostics")
    if (diagnosticsConfig.value(LOG_SHUTDOWN_THREADS_KEY)?.equals(ENV_FLAG_TRUE, ignoreCase = true) != true) {
        return
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            mainLog.info("JVM shutdown hook fired")
            Thread.getAllStackTraces().forEach { (thread, stack) ->
                if (stack.isNotEmpty()) {
                    mainLog.info(
                        "thread={} state={} stack={}",
                        thread.name,
                        thread.state,
                        stack.joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" },
                    )
                }
            }
        },
    )
}

internal fun includeInRoadtripOpenApi(path: List<String>): Boolean =
    (path.firstOrNull() == "api" && path.getOrNull(1) != "docs") ||
        path.firstOrNull() == "test"

fun Application.module() {
    val properties = ApplicationProperties.load(baseConfig = environment.config)
    installOptionalShutdownThreadDump(properties)

    installRoadtripDependencies(properties)
    installRoadtripPlugins()
    startRoadtripBackgroundServices()
    registerRoadtripRoutes()
}

internal fun Application.installRoadtripDependencies(properties: Map<String, String>) {
    val roadtripConfig = ConfigSection(properties).section("roadtrip")
    dependencies {
        if (!containsDependency<AppConfig>()) {
            provide<AppConfig> { AppConfig.fromProperties(properties) }
        }
        if (!containsDependency<File>()) {
            provide<File> { staticDir(roadtripConfig) }
        }
        if (!containsDependency<PoiRegistry>()) {
            provide<PoiRegistry> { poiRegistry(appConfig = resolve(), staticDir = resolve(), roadtripConfig = roadtripConfig) }
        }
        if (!containsDependency<DataSource>()) {
            provide<DataSource> { roadtripDataSource(roadtripConfig) }.cleanup { dataSource ->
                (dataSource as? AutoCloseable)?.close()
            }
        }
        provide(::roadtripDslContext)

        provide(::apiCacheRepo)
        provide(::CampgroundRepo)
        provide(::CampsiteRepo)
        provide(::CampsiteProviderRepo)
        provide(::AvailabilityRepo)
        provide(::AvailabilityPollerRepo)
        provide(::AvailabilityRunRepo)
        provide(::AvailabilityFetchCallRepo)
        provide(::AvailabilityWatchRepo)
        provide(::TeslaSuperchargerRepo)
        provide(::PlanetFitnessLocationRepo)
        provide(::PoiServingRepo)
        provide(::RouteCorridorRepo)
        provide(::AdminIngestReadRepo)
        provide(::CanonicalViewRepo)

        provide(::AvailabilityDateResolver)
        if (!containsDependency<RecGovAvailabilityClient>()) {
            provide(::recGovAvailabilityClient)
        }
        if (!containsDependency<AspiraAvailabilityClient>()) {
            provide(::aspiraAvailabilityClient)
        }
        if (!containsDependency<ReserveAmericaAvailabilityClient>()) {
            provide(::reserveAmericaAvailabilityClient)
        }
        if (!containsDependency<ReserveCaliforniaAvailabilityClient>()) {
            provide(::reserveCaliforniaAvailabilityClient)
        }
        if (!containsDependency<CampflareAvailabilityClient>()) {
            provide(::campflareAvailabilityClient)
        }
    }

    val appConfig: AppConfig by dependencies
    val poiRegistry: PoiRegistry by dependencies
    installRoadtripProviderKoin(appConfig = appConfig, poiRegistry = poiRegistry)
    val koin = getKoin()

    dependencies {
        provide<AvailabilityProviderRegistry> { koin.get() }
        provide(::availabilityTargetResolver)
        provide(::WatchScopeResolver)
        provide(::AvailabilityPollerMembership)
        provide<AlertProviderRegistry> { koin.get() }
        provide<BookingProviderRegistry> { koin.get() }
        provide(::AvailabilityBookingTargetResolver)
        provide(::watchCapabilityService)
        provide(::watchCapabilityValidator)
        provide(::availabilityWatchService)
        provide(::availabilityWatchApiMapper)
        provide(::providerCooldownTracker)
        provide(::failoverAvailabilityFetcher)
        provide(::CatalogAvailabilityBatcher)
        provide(::campsiteAvailabilityComposer)
        provide(::CampsiteCatalogService)
        provide(::CampsiteAvailabilityService)
        provide(::CampgroundAvailabilitySupport)

        provide(::slackNotificationService)
        provide(::emailNotificationService)
        provide(::notificationServices)
        provide(::NotificationFanout)
        provide<NotificationSender> { resolve<NotificationFanout>() }
        provide(::notifyTriggerActionHandler)
        provide(::AtcTriggerActionHandler)
        provide(::triggerActionRegistry)
        provide(::watchAlertDispatcher)
        provide<SlackSignatureVerifier?> { slackSignatureVerifier(resolve()) }
        provide(::slackInteractivityWatches)
        provide(::slackInteractivityHandler)
        provide(::slackInteractivityWiring)

        provide<MapboxDirections> { MapboxDirections(token = mapboxToken(roadtripConfig)) }
        provide<MapboxGeocoder> { MapboxGeocoder(token = mapboxToken(roadtripConfig)) }
        provide(::routeCache)
        provide(::campgroundService)
        provide(::TeslaSuperchargerService)
        provide(::PlanetFitnessLocationService)
        provide(::poiDetailServices)
        provide(::poiService)
        provide(::poiReader)
        provide(::RouteCorridorService)
        provide(::poisOnRouteService)

        provide(::etlOrchestrator)
        provide(::ingestController)
        provide<IpRateLimiter> { IpRateLimiter(perMinute = IP_RATE_LIMIT_PER_MINUTE) }

        provide<CoroutineScope> { CoroutineScope(Dispatchers.IO + SupervisorJob()) }.cleanup { scope ->
            scope.cancel()
        }
        provide(::vendorRateLimiter)
        provide(::availabilityPollExecutor)
        provide(::availabilityScheduler)
        provide(::PollerBackfill)
        provide(::watchReaper)
    }
}

private fun Application.startRoadtripBackgroundServices() {
    CoordinateTimeZones.warmUp()
    val pollerBackfill: PollerBackfill by dependencies
    val schedulerScope: CoroutineScope by dependencies
    val scheduler: Scheduler<AvailabilityPollerRepo.Poller> by dependencies
    val watchReaper: WatchReaper by dependencies

    pollerBackfill.run()
    scheduler.start(schedulerScope)
    watchReaper.start(schedulerScope)
}

private fun staticDir(roadtripConfig: ConfigSection): File = File(roadtripConfig.valueOrDefault(STATIC_DIR_KEY, DEFAULT_STATIC_DIR))

private fun roadtripDataSource(roadtripConfig: ConfigSection): DataSource {
    val dataSource = dataSourceFor(DbConfig.fromConfig(roadtripConfig.section("db")))
    migrate(dataSource)
    return dataSource
}

internal fun roadtripDslContext(dataSource: DataSource): DSLContext = dsl(dataSource)

internal fun apiCacheRepo(ctx: DSLContext): ApiCacheRepo = ApiCacheRepo(ctx)

private fun poiRegistry(
    appConfig: AppConfig,
    staticDir: File,
    roadtripConfig: ConfigSection,
): PoiRegistry =
    loadPoiRegistry(roadtripConfig.section("poi-registry"), staticDir)
        .also { validateReadPathDataSources(appConfig.readPathProviders, it) }

internal fun recGovAvailabilityClient(): RecGovAvailabilityClient = HttpRecgovAvailabilityClient()

internal fun aspiraAvailabilityClient(): AspiraAvailabilityClient = HttpAspiraAvailabilityClient()

internal fun reserveAmericaAvailabilityClient(): ReserveAmericaAvailabilityClient = HttpReserveAmericaAvailabilityClient()

internal fun reserveCaliforniaAvailabilityClient(): ReserveCaliforniaAvailabilityClient = HttpReserveCaliforniaAvailabilityClient()

internal fun campflareAvailabilityClient(appConfig: AppConfig): CampflareAvailabilityClient =
    HttpCampflareAvailabilityClient(
        apiBaseUrl = appConfig.campflare.apiBaseUrl,
        apiKey = appConfig.campflare.apiKey,
    )

@OptIn(KoinExperimentalAPI::class)
internal fun Application.installRoadtripProviderKoin(
    appConfig: AppConfig,
    poiRegistry: PoiRegistry,
) {
    install(KoinPlugin) {
        slf4jLogger()
        bridge {
            koinToKtor()
        }
        modules(roadtripProviderKoinModule(appConfig = appConfig, poiRegistry = poiRegistry))
    }
}

internal fun roadtripProviderKoinModule(
    appConfig: AppConfig,
    poiRegistry: PoiRegistry,
): Module {
    val aspiraTenants = aspiraTenantsFor(poiRegistry)
    val reserveAmericaTenants = reserveAmericaTenantsFor(poiRegistry)
    return module {
        singleOf(::recGovAvailabilityProvider) bind AvailabilityProvider::class
        singleOf(::campflareAvailabilityProvider) bind AvailabilityProvider::class
        aspiraTenants.forEach { tenant ->
            single<AvailabilityProvider>(named("$KOIN_AVAILABILITY_PROVIDER_QUALIFIER_PREFIX:aspira:${tenant.host}")) {
                aspiraAvailabilityProvider(tenant = tenant, client = get(), appConfig = get())
            }
        }
        reserveAmericaTenants.forEach { tenant ->
            single<AvailabilityProvider>(named("$KOIN_AVAILABILITY_PROVIDER_QUALIFIER_PREFIX:reserveamerica:${tenant.source}")) {
                reserveAmericaAvailabilityProvider(tenant = tenant, client = get(), appConfig = get())
            }
        }
        singleOf(::reserveCaliforniaAvailabilityProvider) bind AvailabilityProvider::class
        single {
            val providers = getAll<AvailabilityProvider>()
            AvailabilityProviderRegistry.fromBindings(availabilityProviderBindings(poiRegistry, providers))
        }

        singleOf(::InternalPollerAlertProvider) bind AlertProvider::class
        single {
            val providers = getAll<AlertProvider>()
            AlertProviderRegistry(providers)
        }

        if (appConfig.booking.recgovAtc.companionEnabled) {
            mainLog.info("Rec.gov ATC companion executor enabled at {}", appConfig.booking.recgovAtc.companionBaseUrl)
            single<RecGovAtcExecutor> { HttpRecGovAtcExecutor(appConfig.booking.recgovAtc) }
            singleOf(::RecGovBookingProvider) bind BookingProvider::class
        }
        single {
            val providers = getAll<BookingProvider>()
            BookingProviderRegistry(providers)
        }
    }
}

internal fun recGovAvailabilityProvider(
    client: RecGovAvailabilityClient,
    appConfig: AppConfig,
): RecGovAvailabilityProvider =
    RecGovAvailabilityProvider(
        client = client,
        enabled = appConfig.isProviderEnabled(AvailabilityProviderId.RECGOV),
    )

internal fun campflareAvailabilityProvider(
    client: CampflareAvailabilityClient,
    appConfig: AppConfig,
): CampflareAvailabilityProvider =
    CampflareAvailabilityProvider(
        client = client,
        enabled = appConfig.isProviderEnabled(AvailabilityProviderId.CAMPFLARE),
    )

internal fun aspiraAvailabilityProvider(
    tenant: AspiraTenant,
    client: AspiraAvailabilityClient,
    appConfig: AppConfig,
): AspiraAvailabilityProvider =
    AspiraAvailabilityProvider(
        tenant = tenant,
        client = client,
        enabled = appConfig.isProviderEnabled(AvailabilityProviderId.ASPIRA),
    )

private fun aspiraTenantsFor(poiRegistry: PoiRegistry): List<AspiraTenant> {
    val hostBySource = poiRegistry.aspiraHostBySource()
    validateAspiraHosts(hostBySource)
    return hostBySource
        .values
        .distinct()
        .map { host ->
            AspiraTenants.byHost(host)
                ?: error(
                    "Aspira host '$host' has no AspiraTenant config row; " +
                        "add it to AspiraTenants.kt.",
                )
        }
}

internal fun reserveAmericaAvailabilityProvider(
    tenant: ReserveAmericaTenant,
    client: ReserveAmericaAvailabilityClient,
    appConfig: AppConfig,
): ReserveAmericaAvailabilityProvider =
    ReserveAmericaAvailabilityProvider(
        tenant = tenant,
        client = client,
        enabled = appConfig.isProviderEnabled(AvailabilityProviderId.RESERVEAMERICA),
    )

private fun reserveAmericaTenantsFor(poiRegistry: PoiRegistry): List<ReserveAmericaTenant> =
    poiRegistry.reserveAmericaSources().map { config ->
        ReserveAmericaTenant(
            source = config.source,
            host = config.host,
            contractCode = config.contractCode,
            bookingHorizonDays = config.bookingHorizonDays,
        )
    }

internal fun reserveCaliforniaAvailabilityProvider(
    client: ReserveCaliforniaAvailabilityClient,
    appConfig: AppConfig,
): ReserveCaliforniaAvailabilityProvider =
    ReserveCaliforniaAvailabilityProvider(
        client = client,
        enabled = appConfig.isProviderEnabled(AvailabilityProviderId.RESERVECALIFORNIA),
    )

internal fun availabilityProviderBindings(
    poiRegistry: PoiRegistry,
    providers: List<AvailabilityProvider>,
): List<AvailabilityProviderBinding> =
    buildList {
        val recgov = providers.singleProvider<RecGovAvailabilityProvider>(AvailabilityProviderId.RECGOV)
        val campflare = providers.singleProvider<CampflareAvailabilityProvider>(AvailabilityProviderId.CAMPFLARE)
        val reserveCalifornia = providers.singleProvider<ReserveCaliforniaAvailabilityProvider>(AvailabilityProviderId.RESERVECALIFORNIA)

        addSourceBindings(provider = recgov, sources = listOf(RECGOV_VENDOR) + poiRegistry.recgovSources())
        addSourceBindings(provider = campflare, sources = listOf(CAMPFLARE_VENDOR) + poiRegistry.campflareSources())

        val aspiraByHost = providers.filterIsInstance<AspiraAvailabilityProvider>().associateBy { it.tenant.host }
        for ((source, host) in poiRegistry.aspiraHostBySource()) {
            add(AvailabilityProviderBinding(source = source, provider = aspiraByHost.getValue(host)))
        }

        val reserveAmericaBySource = providers.filterIsInstance<ReserveAmericaAvailabilityProvider>().associateBy { it.tenant.source }
        for (config in poiRegistry.reserveAmericaSources()) {
            add(AvailabilityProviderBinding(source = config.source, provider = reserveAmericaBySource.getValue(config.source)))
        }

        addSourceBindings(provider = reserveCalifornia, sources = poiRegistry.reserveCaliforniaSources())
    }

private fun MutableList<AvailabilityProviderBinding>.addSourceBindings(
    provider: AvailabilityProvider,
    sources: Iterable<String>,
) {
    sources.distinct().forEach { source ->
        add(AvailabilityProviderBinding(source = source, provider = provider))
    }
}

private inline fun <reified T : AvailabilityProvider> List<AvailabilityProvider>.singleProvider(id: AvailabilityProviderId): T =
    filterIsInstance<T>()
        .singleOrNull()
        ?: error("expected exactly one ${id.name.lowercase()} availability provider, found ${count { it.id == id }}")

/**
 * Boot-time gate: every Aspira host the YAML declares must have a tenant config
 * row. Catches forgotten entries loudly instead of letting a request silently
 * route to a missing adapter at the first user click.
 */
private fun validateAspiraHosts(hostBySource: Map<String, String>) {
    val yamlHosts = hostBySource.values.toSet()
    val configHosts = AspiraTenants.knownHosts()
    val missingFromConfig = yamlHosts - configHosts
    if (missingFromConfig.isNotEmpty()) {
        error(
            "Aspira hosts declared in POI registry but missing from AspiraTenants: " +
                "$missingFromConfig. Add a tenant row in AspiraTenants.kt.",
        )
    }
    // Reverse direction is informational, not fatal: a tenant row with no YAML
    // source is harmless because DI builds only the configured adapters.
}

internal fun availabilityTargetResolver(
    providerRefs: CampsiteProviderRepo,
    campsitesRepo: CampsiteRepo,
    availabilityProviders: AvailabilityProviderRegistry,
    dateResolver: AvailabilityDateResolver,
): AvailabilityTargetResolver =
    DbAvailabilityTargetResolver(
        providerRefs = providerRefs,
        campsitesRepo = campsitesRepo,
        availabilityProviders = availabilityProviders,
        dateResolver = dateResolver,
    )

internal fun watchCapabilityService(
    appConfig: AppConfig,
    availabilityTargets: AvailabilityTargetResolver,
    bookingTargets: AvailabilityBookingTargetResolver,
): WatchCapabilityService =
    WatchCapabilityService(
        availabilityTargets = availabilityTargets,
        bookingTargets = bookingTargets,
        notificationTriggerKinds =
            buildList {
                add(AvailabilityTriggerKinds.SLACK_NOTIFY)
                if (appConfig.email?.defaultTo?.isNotEmpty() == true) add(AvailabilityTriggerKinds.EMAIL_NOTIFY)
            },
    )

internal fun watchCapabilityValidator(
    scopeResolver: WatchScopeResolver,
    capabilities: WatchCapabilityService,
): WatchCapabilityValidator =
    WatchTriggerCapabilityValidator(
        scopeResolver = scopeResolver,
        capabilities = capabilities,
    )

internal fun availabilityWatchService(
    ctx: DSLContext,
    alertProviders: AlertProviderRegistry,
    capabilityValidator: WatchCapabilityValidator,
): AvailabilityWatchService =
    AvailabilityWatchService(
        ctx = ctx,
        alertProviders = alertProviders,
        capabilityValidator = capabilityValidator,
    )

internal fun availabilityWatchApiMapper(
    campsitesRepo: CampsiteRepo,
    scopeResolver: WatchScopeResolver,
    capabilities: WatchCapabilityService,
): AvailabilityWatchApiMapper =
    AvailabilityWatchApiMapper(
        campsites = campsitesRepo,
        scopeResolver = scopeResolver,
        capabilities = capabilities,
    )

internal fun providerCooldownTracker(appConfig: AppConfig): ProviderCooldownTracker =
    ProviderCooldownTracker(cooldown = appConfig.availability.providerCooldown)

internal fun failoverAvailabilityFetcher(cooldowns: ProviderCooldownTracker): FailoverAvailabilityFetcher =
    FailoverAvailabilityFetcher(cooldowns = cooldowns)

internal fun campsiteAvailabilityComposer(
    targets: AvailabilityTargetResolver,
    dateResolver: AvailabilityDateResolver,
    availability: AvailabilityRepo,
    failoverFetcher: FailoverAvailabilityFetcher,
): CampsiteAvailabilityComposer =
    CampsiteAvailabilityComposer(
        targets = targets,
        dateResolver = dateResolver,
        availability = availability,
        failoverFetcher = failoverFetcher,
    )

internal fun slackNotificationService(appConfig: AppConfig): SlackNotificationService = SlackNotificationService(appConfig.slack)

internal fun emailNotificationService(appConfig: AppConfig): EmailNotificationService = EmailNotificationService(appConfig.email)

internal fun notificationServices(
    slack: SlackNotificationService,
    email: EmailNotificationService,
): List<NotificationService> = listOf(slack, email)

internal fun notifyTriggerActionHandler(
    notifications: NotificationSender,
    appConfig: AppConfig,
): NotifyTriggerActionHandler =
    NotifyTriggerActionHandler(
        notifications = notifications,
        appRootUrl = appConfig.webApp?.rootUrl,
    )

internal fun triggerActionRegistry(
    notify: NotifyTriggerActionHandler,
    atc: AtcTriggerActionHandler,
): TriggerActionRegistry = TriggerActionRegistry(listOf(notify, atc))

internal fun watchAlertDispatcher(
    notifications: NotificationSender,
    scopeResolver: WatchScopeResolver,
    watches: AvailabilityWatchRepo,
    targets: AvailabilityTargetResolver,
    pois: PoiServingRepo,
    availability: AvailabilityRepo,
    triggerActions: TriggerActionRegistry,
    appConfig: AppConfig,
): WatchAlertDispatcher =
    WatchAlertDispatcher(
        notifications = notifications,
        scopeResolver = scopeResolver,
        watches = watches,
        targets = targets,
        pois = pois,
        availability = availability,
        triggerActions = triggerActions,
        grafanaRootUrl = appConfig.grafana?.rootUrl,
        appRootUrl = appConfig.webApp?.rootUrl,
    )

private fun slackSignatureVerifier(appConfig: AppConfig): SlackSignatureVerifier? =
    appConfig.slack?.signingSecret?.let { secret ->
        mainLog.info(
            "Slack interactivity ENABLED: signing secret set ({} chars), POST /api/slack/interactivity is live",
            secret.length,
        )
        SlackSignatureVerifier(secret)
    } ?: run {
        val reason =
            when {
                appConfig.slack == null ->
                    "Slack is disabled (no roadtrip.slack.bot-token / roadtrip.slack.default-channel)"
                else ->
                    "roadtrip.slack.signing-secret is not set; outbound Slack works, but the interactivity endpoint stays unregistered"
            }
        mainLog.info("Slack interactivity DISABLED: {}", reason)
        null
    }

internal fun slackInteractivityWatches(
    watches: AvailabilityWatchRepo,
    watchService: AvailabilityWatchService,
    watchAlertDispatcher: WatchAlertDispatcher,
): SlackInteractivityHandler.Watches =
    object : SlackInteractivityHandler.Watches {
        override fun setStatus(
            id: Long,
            status: WatchStatus,
        ) = watchService.update(id = id, status = status)

        override fun snapshotAndDelete(id: Long): AvailabilityWatchRepo.Watch? {
            val snapshot = watches.findById(id) ?: return null
            return if (watchService.delete(id)) snapshot else null
        }

        override fun buildStatusNotice(
            watch: AvailabilityWatchRepo.Watch,
            state: WatchStatusNotice.State,
        ) = watchAlertDispatcher.statusNoticeForWatch(watch, state)
    }

internal fun slackInteractivityHandler(
    watches: SlackInteractivityHandler.Watches,
    slack: SlackNotificationService,
): SlackInteractivityHandler = SlackInteractivityHandler(watches = watches, slack = slack)

internal fun slackInteractivityWiring(
    verifier: SlackSignatureVerifier?,
    handler: SlackInteractivityHandler,
): SlackInteractivityWiring? = verifier?.let { SlackInteractivityWiring(verifier = it, handler = handler) }

private fun mapboxToken(roadtripConfig: ConfigSection): String? = roadtripConfig.section("mapbox").value(MAPBOX_TOKEN_KEY)

internal fun routeCache(
    appConfig: AppConfig,
    directions: MapboxDirections,
    apiCache: ApiCacheRepo,
): RouteCache =
    RouteCache(
        directions = directions,
        ttl = appConfig.cache.ttlFor(ApiCacheEntity.ROUTE),
        persistentCache = apiCache,
    )

internal fun campgroundService(
    repo: CampgroundRepo,
    dateResolver: AvailabilityDateResolver,
    availabilitySupport: CampgroundAvailabilitySupport,
): CampgroundService =
    CampgroundService(
        repo = repo,
        dateResolver = dateResolver,
        availabilitySupport = availabilitySupport,
    )

internal fun poiDetailServices(
    campgroundService: CampgroundService,
    teslaSuperchargerService: TeslaSuperchargerService,
    planetFitnessLocationService: PlanetFitnessLocationService,
): List<PoiDetailService> =
    listOf(
        campgroundService,
        teslaSuperchargerService,
        planetFitnessLocationService,
    )

internal fun poiService(
    poiRepo: PoiServingRepo,
    detailServices: List<PoiDetailService>,
): PoiService =
    PoiService(
        poiRepo = poiRepo,
        detailServices = detailServices,
    )

internal fun poiReader(
    appConfig: AppConfig,
    poiService: PoiService,
    detailServices: List<PoiDetailService>,
): PoiReader =
    ReadPathProviderPoiReader(
        delegate = poiService,
        detailServices = detailServices,
        providers = appConfig.readPathProviders,
    )

internal fun poisOnRouteService(
    routeCache: RouteCache,
    routeCorridorService: RouteCorridorService,
    poiService: PoiReader,
): PoisOnRouteService =
    PoisOnRouteService(
        routeCache = routeCache,
        routeCorridorService = routeCorridorService,
        poiService = poiService,
    )

internal fun etlOrchestrator(
    ctx: DSLContext,
    staticDir: File,
    poiRegistry: PoiRegistry,
    canonicalViews: CanonicalViewRepo,
): EtlOrchestrator =
    EtlOrchestrator(
        ctx = ctx,
        rawDir = staticDir.resolveConfiguredPath(RAW_DATA_DIR),
        poiRegistry = poiRegistry,
        canonicalViews = canonicalViews,
    )

internal fun ingestController(
    ctx: DSLContext,
    etl: EtlOrchestrator,
    poiRegistry: PoiRegistry,
    staticDir: File,
): IngestController {
    sweepStaleIngestRuns(ctx)
    return IngestController(
        ctx = ctx,
        etl = etl,
        fetchTargets = fetchTargetsFromRegistry(poiRegistry, staticDir),
        importTargets = importTargetsFromRegistry(poiRegistry),
        workingDir = staticDir,
    )
}

internal fun vendorRateLimiter(
    appConfig: AppConfig,
    dataSource: DataSource,
): VendorRateLimiter =
    VendorRateLimiter(
        config = appConfig.vendorRateLimit,
        dataSource = dataSource,
    )

internal fun availabilityPollExecutor(
    pollers: AvailabilityPollerRepo,
    campsitesRepo: CampsiteRepo,
    batcher: CatalogAvailabilityBatcher,
    availability: AvailabilityRepo,
    runs: AvailabilityRunRepo,
    dateResolver: AvailabilityDateResolver,
    targets: AvailabilityTargetResolver,
    fetchCalls: AvailabilityFetchCallRepo,
    limiter: VendorRateLimiter,
    alertDispatcher: WatchAlertDispatcher,
    failoverFetcher: FailoverAvailabilityFetcher,
): AvailabilityPollExecutor =
    AvailabilityPollExecutor(
        pollers = pollers,
        campsitesRepo = campsitesRepo,
        batcher = batcher,
        availability = availability,
        runs = runs,
        dateResolver = dateResolver,
        targets = targets,
        fetchCalls = fetchCalls,
        limiter = limiter,
        alertDispatcher = alertDispatcher,
        failoverFetcher = failoverFetcher,
    )

internal fun availabilityScheduler(
    pollers: AvailabilityPollerRepo,
    executor: AvailabilityPollExecutor,
): Scheduler<AvailabilityPollerRepo.Poller> =
    Scheduler(
        repo = pollers,
        handler = executor::handle,
        name = SCHEDULER_NAME_AVAILABILITY,
    )

internal fun watchReaper(pollers: AvailabilityPollerRepo): WatchReaper = WatchReaper(pollers)

private fun AppConfig.isProviderEnabled(id: AvailabilityProviderId): Boolean =
    readPathProviders.isAvailabilityProviderEnabled(id.name.lowercase()) &&
        (id != AvailabilityProviderId.CAMPFLARE || !campflare.apiKey.isNullOrBlank())

internal fun validateReadPathDataSources(
    providers: ReadPathProviderConfig,
    registry: PoiRegistry,
) {
    val supported = supportedReadPathDataSources(registry)
    val unknown = providers.enabledDataSources - supported
    require(unknown.isEmpty()) {
        "roadtrip.read-path.enabled-data-sources contains unknown source(s): " +
            "${unknown.sorted()}. Expected one of: ${supported.sorted()}."
    }
}

private fun supportedReadPathDataSources(registry: PoiRegistry): Set<String> =
    registry.poiData
        .mapNotNull { row -> row.etls.lastOrNull()?.slug }
        .toSet() +
        canonicalCampgroundSourceKeys(registry) +
        DEFAULT_POI_TYPES.filter { it != CampgroundService.POI_TYPE }

private fun canonicalCampgroundSourceKeys(registry: PoiRegistry): Set<String> =
    buildSet {
        if (registry.campflareSources().isNotEmpty()) add("campflare")
        if (registry.recgovSources().isNotEmpty()) add("recgov")
    }

private fun loadPoiRegistry(
    config: ConfigSection,
    staticDir: File,
): PoiRegistry {
    val pathOverride = config.value(POI_REGISTRY_PATH_KEY)
    if (pathOverride != null) {
        return PoiRegistry.load(staticDir.resolveConfiguredPath(pathOverride))
    }
    return PoiRegistry.loadResource(
        config.valueOrDefault(
            POI_REGISTRY_RESOURCE_KEY,
            DEFAULT_POI_REGISTRY_RESOURCE,
        ),
    )
}

private fun File.resolveConfiguredPath(path: String): File {
    val configured = File(path)
    return if (configured.isAbsolute) configured else File(this, path)
}

private inline fun <reified T> DependencyRegistry.containsDependency(): Boolean = contains(DependencyKey<T>())
