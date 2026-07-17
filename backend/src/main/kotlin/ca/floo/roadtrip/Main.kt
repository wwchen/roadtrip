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
import ca.floo.roadtrip.repo.PersistentCache
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.routes.api.pois.IP_RATE_LIMIT_PER_MINUTE
import ca.floo.roadtrip.routes.api.pois.IpRateLimiter
import ca.floo.roadtrip.service.api.AvailabilityLoader
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
import ca.floo.roadtrip.service.availability.TriggerActionHandler
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
import ca.floo.roadtrip.service.etl.framework.importTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.sweepStaleIngestRuns
import ca.floo.roadtrip.service.notification.common.NotificationFanout
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.NotificationService
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import ca.floo.roadtrip.service.notification.slack.SlackInteractivityHandler
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import ca.floo.roadtrip.service.notification.slack.SlackResponseSender
import ca.floo.roadtrip.service.poi.CampgroundService
import ca.floo.roadtrip.service.poi.DEFAULT_POI_TYPES
import ca.floo.roadtrip.service.poi.PlanetFitnessLocationService
import ca.floo.roadtrip.service.poi.PoiDetailService
import ca.floo.roadtrip.service.poi.PoiReader
import ca.floo.roadtrip.service.poi.PoiService
import ca.floo.roadtrip.service.poi.PoisOnRouteService
import ca.floo.roadtrip.service.poi.TeslaSuperchargerService
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
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
import kotlinx.serialization.json.Json
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
import java.time.Clock
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

fun Application.module() {
    val properties = ApplicationProperties.load(baseConfig = environment.config)
    installOptionalShutdownThreadDump(properties)

    installRoadtripInfrastructure(properties)
    installRoadtripPlugins()
    startRoadtripBackgroundServices()
    registerRoadtripRoutes()
}

internal fun Application.installRoadtripInfrastructure(properties: Map<String, String>) {
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

        provide<CoroutineScope> { CoroutineScope(Dispatchers.IO + SupervisorJob()) }.cleanup { scope ->
            scope.cancel()
        }
    }

    val appConfig: AppConfig by dependencies
    val staticDir: File by dependencies
    val poiRegistry: PoiRegistry by dependencies
    val dataSource: DataSource by dependencies
    val ctx: DSLContext by dependencies
    val recGovClient: RecGovAvailabilityClient by dependencies
    val aspiraClient: AspiraAvailabilityClient by dependencies
    val reserveAmericaClient: ReserveAmericaAvailabilityClient by dependencies
    val reserveCaliforniaClient: ReserveCaliforniaAvailabilityClient by dependencies
    val campflareClient: CampflareAvailabilityClient by dependencies
    val schedulerScope: CoroutineScope by dependencies
    installRoadtripKoin(
        appConfig = appConfig,
        staticDir = staticDir,
        poiRegistry = poiRegistry,
        dataSource = dataSource,
        ctx = ctx,
        recGovClient = recGovClient,
        aspiraClient = aspiraClient,
        reserveAmericaClient = reserveAmericaClient,
        reserveCaliforniaClient = reserveCaliforniaClient,
        campflareClient = campflareClient,
        schedulerScope = schedulerScope,
        roadtripConfig = roadtripConfig,
    )
}

private fun Application.startRoadtripBackgroundServices() {
    CoordinateTimeZones.warmUp()
    val koin = getKoin()
    val pollerBackfill = koin.get<PollerBackfill>()
    val schedulerScope = koin.get<CoroutineScope>()
    val scheduler = koin.get<Scheduler<AvailabilityPollerRepo.Poller>>()
    val watchReaper = koin.get<WatchReaper>()

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
internal fun Application.installRoadtripKoin(
    appConfig: AppConfig,
    staticDir: File,
    poiRegistry: PoiRegistry,
    dataSource: DataSource,
    ctx: DSLContext,
    recGovClient: RecGovAvailabilityClient,
    aspiraClient: AspiraAvailabilityClient,
    reserveAmericaClient: ReserveAmericaAvailabilityClient,
    reserveCaliforniaClient: ReserveCaliforniaAvailabilityClient,
    campflareClient: CampflareAvailabilityClient,
    schedulerScope: CoroutineScope,
    roadtripConfig: ConfigSection,
) {
    install(KoinPlugin) {
        slf4jLogger()
        bridge {
            koinToKtor()
        }
        modules(
            roadtripKoinModule(
                appConfig = appConfig,
                staticDir = staticDir,
                poiRegistry = poiRegistry,
                dataSource = dataSource,
                ctx = ctx,
                recGovClient = recGovClient,
                aspiraClient = aspiraClient,
                reserveAmericaClient = reserveAmericaClient,
                reserveCaliforniaClient = reserveCaliforniaClient,
                campflareClient = campflareClient,
                schedulerScope = schedulerScope,
                roadtripConfig = roadtripConfig,
            ),
        )
    }
}

internal fun roadtripKoinModule(
    appConfig: AppConfig,
    staticDir: File,
    poiRegistry: PoiRegistry,
    dataSource: DataSource,
    ctx: DSLContext,
    recGovClient: RecGovAvailabilityClient,
    aspiraClient: AspiraAvailabilityClient,
    reserveAmericaClient: ReserveAmericaAvailabilityClient,
    reserveCaliforniaClient: ReserveCaliforniaAvailabilityClient,
    campflareClient: CampflareAvailabilityClient,
    schedulerScope: CoroutineScope,
    roadtripConfig: ConfigSection,
): Module {
    val aspiraTenants = aspiraTenantsFor(poiRegistry)
    val reserveAmericaTenants = reserveAmericaTenantsFor(poiRegistry)
    return module {
        single { appConfig }
        single { staticDir }
        single { poiRegistry }
        single<DataSource> { dataSource }
        single { ctx }
        single<RecGovAvailabilityClient> { recGovClient }
        single<AspiraAvailabilityClient> { aspiraClient }
        single<ReserveAmericaAvailabilityClient> { reserveAmericaClient }
        single<ReserveCaliforniaAvailabilityClient> { reserveCaliforniaClient }
        single<CampflareAvailabilityClient> { campflareClient }
        single<CoroutineScope> { schedulerScope }
        single<Clock> { Clock.systemUTC() }
        single<Json> { Json }
        single { CampgroundCta.Default }

        singleOf(::ApiCacheRepo) bind PersistentCache::class
        singleOf(::CampgroundRepo)
        singleOf(::CampsiteRepo)
        singleOf(::CampsiteProviderRepo)
        singleOf(::AvailabilityRepo)
        singleOf(::AvailabilityPollerRepo)
        singleOf(::AvailabilityRunRepo)
        singleOf(::AvailabilityFetchCallRepo)
        singleOf(::AvailabilityWatchRepo)
        singleOf(::TeslaSuperchargerRepo)
        singleOf(::PlanetFitnessLocationRepo)
        singleOf(::PoiServingRepo)
        singleOf(::RouteCorridorRepo)
        singleOf(::AdminIngestReadRepo)
        singleOf(::CanonicalViewRepo)

        singleOf(::AvailabilityDateResolver)
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
        singleOf(::DbAvailabilityTargetResolver) bind AvailabilityTargetResolver::class

        singleOf(::WatchScopeResolver)
        singleOf(::AvailabilityPollerMembership)
        singleOf(::InternalPollerAlertProvider) bind AlertProvider::class
        single {
            val providers = getAll<AlertProvider>()
            AlertProviderRegistry(providers)
        }
        single {
            WatchCapabilityService(
                availabilityTargets = get(),
                bookingTargets = get(),
                notificationTriggerKinds = notificationTriggerKinds(get()),
            )
        }
        singleOf(::WatchTriggerCapabilityValidator) bind WatchCapabilityValidator::class
        singleOf(::AvailabilityWatchService)
        singleOf(::AvailabilityWatchApiMapper)
        single {
            ProviderCooldownTracker(cooldown = get<AppConfig>().availability.providerCooldown)
        }
        singleOf(::FailoverAvailabilityFetcher)
        singleOf(::CatalogAvailabilityBatcher)
        singleOf(::AvailabilityLoader)
        singleOf(::CampsiteAvailabilityComposer)
        singleOf(::CampsiteCatalogService)
        singleOf(::CampsiteAvailabilityService)
        singleOf(::CampgroundAvailabilitySupport)

        if (appConfig.booking.recgovAtc.companionEnabled) {
            mainLog.info("Rec.gov ATC companion executor enabled at {}", appConfig.booking.recgovAtc.companionBaseUrl)
            single<RecGovAtcExecutor> { HttpRecGovAtcExecutor(get<AppConfig>().booking.recgovAtc) }
            singleOf(::RecGovBookingProvider) bind BookingProvider::class
        }
        single {
            val providers = getAll<BookingProvider>()
            BookingProviderRegistry(providers)
        }
        singleOf(::AvailabilityBookingTargetResolver)

        single { SlackNotificationService(get<AppConfig>().slack) } bind NotificationService::class
        single<SlackResponseSender> { get<SlackNotificationService>() }
        single { EmailNotificationService(get<AppConfig>().email) } bind NotificationService::class
        single<NotificationSender> { NotificationFanout(getAll<NotificationService>()) }
        single<TriggerActionHandler> {
            NotifyTriggerActionHandler(
                notifications = get(),
                appRootUrl = get<AppConfig>().webApp?.rootUrl,
            )
        }
        singleOf(::AtcTriggerActionHandler) bind TriggerActionHandler::class
        single {
            TriggerActionRegistry(getAll<TriggerActionHandler>())
        }
        single {
            val config = get<AppConfig>()
            WatchAlertDispatcher(
                notifications = get(),
                scopeResolver = get(),
                watches = get(),
                targets = get(),
                pois = get(),
                availability = get(),
                triggerActions = get(),
                grafanaRootUrl = config.grafana?.rootUrl,
                appRootUrl = config.webApp?.rootUrl,
            )
        }
        single<SlackInteractivityHandler.Watches> {
            slackInteractivityWatches(
                watches = get(),
                watchService = get(),
                watchAlertDispatcher = get(),
            )
        }
        singleOf(::SlackInteractivityHandler)
        configureSlackInteractivity(appConfig)

        single<MapboxDirections> { MapboxDirections(token = mapboxToken(roadtripConfig)) }
        single<MapboxGeocoder> { MapboxGeocoder(token = mapboxToken(roadtripConfig)) }
        single {
            RouteCache(
                directions = get(),
                ttl = get<AppConfig>().cache.ttlFor(ApiCacheEntity.ROUTE),
                persistentCache = get<ApiCacheRepo>(),
            )
        }
        singleOf(::CampgroundService) bind PoiDetailService::class
        singleOf(::TeslaSuperchargerService) bind PoiDetailService::class
        singleOf(::PlanetFitnessLocationService) bind PoiDetailService::class
        single {
            PoiService(
                poiRepo = get(),
                detailServices = getAll<PoiDetailService>(),
            )
        }
        single<PoiReader> {
            ReadPathProviderPoiReader(
                delegate = get<PoiService>(),
                detailServices = getAll<PoiDetailService>(),
                providers = get<AppConfig>().readPathProviders,
            )
        }
        singleOf(::RouteCorridorService)
        singleOf(::PoisOnRouteService)

        single {
            val staticDir = get<File>()
            EtlOrchestrator(
                ctx = get(),
                rawDir = staticDir.resolveConfiguredPath(RAW_DATA_DIR),
                poiRegistry = get(),
                canonicalViews = get(),
            )
        }
        single {
            val ctx = get<DSLContext>()
            val registry = get<PoiRegistry>()
            sweepStaleIngestRuns(ctx)
            IngestController(
                ctx = ctx,
                etl = get(),
                importTargets = importTargetsFromRegistry(registry),
            )
        }
        single<IpRateLimiter> { IpRateLimiter(perMinute = IP_RATE_LIMIT_PER_MINUTE) }
        single {
            VendorRateLimiter(
                config = get<AppConfig>().vendorRateLimit,
                dataSource = get(),
            )
        }
        singleOf(::AvailabilityPollExecutor)
        single<Scheduler<AvailabilityPollerRepo.Poller>> {
            val executor = get<AvailabilityPollExecutor>()
            Scheduler(
                repo = get<AvailabilityPollerRepo>(),
                handler = executor::handle,
                name = SCHEDULER_NAME_AVAILABILITY,
            )
        }
        singleOf(::PollerBackfill)
        singleOf(::WatchReaper)
    }
}

private fun notificationTriggerKinds(appConfig: AppConfig): List<String> =
    buildList {
        add(AvailabilityTriggerKinds.SLACK_NOTIFY)
        if (appConfig.email?.defaultTo?.isNotEmpty() == true) add(AvailabilityTriggerKinds.EMAIL_NOTIFY)
    }

private fun Module.configureSlackInteractivity(appConfig: AppConfig) {
    val signingSecret = appConfig.slack?.signingSecret
    if (signingSecret != null) {
        mainLog.info(
            "Slack interactivity ENABLED: signing secret set ({} chars), POST /api/slack/interactivity is live",
            signingSecret.length,
        )
        single<SlackSignatureVerifier> { SlackSignatureVerifier(signingSecret) }
        singleOf(::SlackInteractivityWiring)
        return
    }

    val reason =
        when {
            appConfig.slack == null ->
                "Slack is disabled (no roadtrip.slack.bot-token / roadtrip.slack.default-channel)"
            else ->
                "roadtrip.slack.signing-secret is not set; outbound Slack works, but the interactivity endpoint stays unregistered"
        }
    mainLog.info("Slack interactivity DISABLED: {}", reason)
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

private fun mapboxToken(roadtripConfig: ConfigSection): String? = roadtripConfig.section("mapbox").value(MAPBOX_TOKEN_KEY)

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
