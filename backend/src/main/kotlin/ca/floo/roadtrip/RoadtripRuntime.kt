package ca.floo.roadtrip

import ca.floo.roadtrip.clients.aspira.HttpAspiraAvailabilityClient
import ca.floo.roadtrip.clients.campflare.HttpCampflareAvailabilityClient
import ca.floo.roadtrip.clients.mapbox.MapboxDirections
import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.clients.recgov.HttpRecgovAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.HttpReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.HttpReserveCaliforniaAvailabilityClient
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
import ca.floo.roadtrip.repo.ApiCacheRepo
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.CanonicalViewRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.service.availability.AtcTriggerActionHandler
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.CoordinateTimeZones
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.DispatchCreateInput
import ca.floo.roadtrip.service.availability.DispatchEnqueuer
import ca.floo.roadtrip.service.availability.DispatchService
import ca.floo.roadtrip.service.availability.DispatchTestEventService
import ca.floo.roadtrip.service.availability.DispatchWaiterRegistry
import ca.floo.roadtrip.service.availability.DispatchWatchCompletion
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.InMemoryDispatchStore
import ca.floo.roadtrip.service.availability.ProviderCooldownTracker
import ca.floo.roadtrip.service.availability.SlackNotifyHandler
import ca.floo.roadtrip.service.availability.TriggerActionRegistry
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchBookingCapabilityService
import ca.floo.roadtrip.service.availability.WatchBookingCapabilityValidator
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.availability.alert.AlertProviderRegistry
import ca.floo.roadtrip.service.availability.alert.InternalPollerAlertProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderClients
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.booking.adapters.RecGovBookingProvider
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.etl.framework.fetchTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.importTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.sweepStaleIngestRuns
import ca.floo.roadtrip.service.notification.SlackInteractivityHandler
import ca.floo.roadtrip.service.notification.SlackNotificationServiceImpl
import ca.floo.roadtrip.service.notification.WatchStatusNotice
import ca.floo.roadtrip.service.poi.CampgroundService
import ca.floo.roadtrip.service.poi.DEFAULT_POI_TYPES
import ca.floo.roadtrip.service.ratelimit.VendorRateLimiter
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.scheduler.PollerBackfill
import ca.floo.roadtrip.service.scheduler.WatchReaper
import ca.floo.roadtrip.service.scheduler.framework.Scheduler
import ca.floo.roadtrip.service.scheduler.jobs.AvailabilityPollExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("ca.floo.roadtrip.RoadtripRuntime")
private const val STATIC_DIR_KEY = "static-dir"
private const val DEFAULT_STATIC_DIR = "."
private const val POI_REGISTRY_RESOURCE_KEY = "resource"
private const val POI_REGISTRY_PATH_KEY = "path"
private const val MAPBOX_TOKEN_KEY = "token"
private const val DEFAULT_POI_REGISTRY_RESOURCE = "poi-registry.yaml"
private const val RAW_DATA_DIR = "data/raw"

internal class RoadtripRuntime(
    val boot: RoadtripBootContext,
    val availabilityProviderRegistry: AvailabilityProviderRegistry,
    val campsiteProviders: CampsiteProviderRepo,
    val availabilityDateResolver: AvailabilityDateResolver,
    val availabilityWatchService: AvailabilityWatchService,
    val watchAlertDispatcher: WatchAlertDispatcher,
    val dispatchService: DispatchService,
    val dispatchTestEventService: DispatchTestEventService,
    val bookingProviderRegistry: BookingProviderRegistry,
    val watchBookingCapabilities: WatchBookingCapabilityService,
    val schedulerScope: CoroutineScope,
    val slackInteractivity: SlackInteractivityWiring?,
    val failoverFetcher: FailoverAvailabilityFetcher,
    private val slackNotifications: SlackNotificationServiceImpl,
) {
    val appConfig: AppConfig get() = boot.appConfig
    val ctx: DSLContext get() = boot.ctx
    val staticDir: File get() = boot.staticDir
    val mapboxGeocoder: MapboxGeocoder get() = boot.mapboxGeocoder
    val routeCache: RouteCache get() = boot.routeCache
    val poiRegistry: PoiRegistry get() = boot.poiRegistry
    val ingestController: IngestController get() = boot.ingestController

    fun close() {
        schedulerScope.cancel()
        boot.availabilityProviderClients.close()
        slackNotifications.close()
    }
}

internal fun createRoadtripBootContext(properties: Map<String, String> = ApplicationProperties.load()): RoadtripBootContext {
    val appConfig = AppConfig.fromProperties(properties)
    val rootConfig = ConfigSection(properties)
    val roadtripConfig = rootConfig.section("roadtrip")
    val staticDir = File(roadtripConfig.valueOrDefault(STATIC_DIR_KEY, DEFAULT_STATIC_DIR))
    val poiRegistry = loadPoiRegistry(roadtripConfig.section("poi-registry"), staticDir)
    validateReadPathDataSources(appConfig.readPathProviders, poiRegistry)

    val ds = dataSourceFor(DbConfig.fromConfig(roadtripConfig.section("db")))
    migrate(ds)
    val ctx = dsl(ds)
    val availabilityProviderClients =
        AvailabilityProviderClients(
            recgovClient = HttpRecgovAvailabilityClient(),
            aspiraClient = HttpAspiraAvailabilityClient(),
            reserveAmericaClient = HttpReserveAmericaAvailabilityClient(),
            reserveCaliforniaClient = HttpReserveCaliforniaAvailabilityClient(),
            campflareClient =
                HttpCampflareAvailabilityClient(
                    apiBaseUrl = appConfig.campflare.apiBaseUrl,
                    apiKey = appConfig.campflare.apiKey,
                ),
        )

    val mapboxToken = roadtripConfig.section("mapbox").value(MAPBOX_TOKEN_KEY)
    val mapboxGeocoder = MapboxGeocoder(token = mapboxToken)
    val routeCache =
        RouteCache(
            MapboxDirections(token = mapboxToken),
            ttl = appConfig.cache.ttlFor(ApiCacheEntity.ROUTE),
            persistentCache = ApiCacheRepo(ctx),
        )
    sweepStaleIngestRuns(ctx)
    val canonicalViews = CanonicalViewRepo(ctx)
    val ingestController =
        IngestController(
            ctx = ctx,
            etl =
                EtlOrchestrator(
                    ctx = ctx,
                    rawDir = staticDir.resolveConfiguredPath(RAW_DATA_DIR),
                    poiRegistry = poiRegistry,
                    canonicalViews = canonicalViews,
                ),
            fetchTargets = fetchTargetsFromRegistry(poiRegistry, staticDir),
            importTargets = importTargetsFromRegistry(poiRegistry),
            workingDir = staticDir,
        )

    return RoadtripBootContext(
        properties = properties,
        appConfig = appConfig,
        dataSource = ds,
        ctx = ctx,
        availabilityProviderClients = availabilityProviderClients,
        staticDir = staticDir,
        mapboxGeocoder = mapboxGeocoder,
        routeCache = routeCache,
        poiRegistry = poiRegistry,
        ingestController = ingestController,
    )
}

internal fun startRoadtripRuntime(boot: RoadtripBootContext): RoadtripRuntime {
    val availabilityProviderRegistry =
        AvailabilityProviderRegistry.fromPoiRegistry(
            registry = boot.poiRegistry,
            clients = boot.availabilityProviderClients,
            isProviderEnabled = { id -> boot.appConfig.isProviderEnabled(id) },
        )

    val campsitesRepo = CampsiteRepo(boot.ctx)
    val campsiteProviders = CampsiteProviderRepo(boot.ctx)
    val availability = AvailabilityRepo(boot.ctx)
    val availabilityDateResolver = AvailabilityDateResolver()
    CoordinateTimeZones.warmUp()
    val availabilityTargets =
        DbAvailabilityTargetResolver(
            providerRefs = campsiteProviders,
            campsitesRepo = campsitesRepo,
            availabilityProviders = availabilityProviderRegistry,
            dateResolver = availabilityDateResolver,
        )
    val watchScopeResolver = WatchScopeResolver(campsitesRepo)
    val pollerMembership = AvailabilityPollerMembership(watchScopeResolver, availabilityTargets)
    val alertProviders = AlertProviderRegistry(listOf(InternalPollerAlertProvider(pollerMembership)))

    val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val availabilityPollers = AvailabilityPollerRepo(boot.ctx)
    PollerBackfill(boot.ctx, pollerMembership).run()

    val slackNotifications = SlackNotificationServiceImpl(boot.appConfig.slack)
    val dispatchEnqueuer = DeferredDispatchEnqueuer()
    val bookingProviderRegistry = BookingProviderRegistry(listOf(RecGovBookingProvider(dispatchEnqueuer)))
    val bookingTargets = AvailabilityBookingTargetResolver(bookingProviderRegistry)
    val watchBookingCapabilities = WatchBookingCapabilityService(availabilityTargets, bookingTargets)
    val availabilityWatchService =
        AvailabilityWatchService(
            ctx = boot.ctx,
            alertProviders = alertProviders,
            capabilityValidator =
                WatchBookingCapabilityValidator(
                    scopeResolver = watchScopeResolver,
                    capabilities = watchBookingCapabilities,
                ),
        )
    val dispatchService =
        DispatchService(
            store = InMemoryDispatchStore(),
            waiters = DispatchWaiterRegistry(),
            slack = slackNotifications,
            watchCompletion =
                DispatchWatchCompletion { watchId ->
                    availabilityWatchService.update(watchId, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.DONE)) != null
                },
        )
    val dispatchTestEventService = DispatchTestEventService(dispatchService)
    dispatchEnqueuer.delegate = dispatchService
    val triggerActions =
        TriggerActionRegistry(
            listOf(
                SlackNotifyHandler(
                    slack = slackNotifications,
                    appRootUrl = boot.appConfig.webApp?.rootUrl,
                ),
                AtcTriggerActionHandler(
                    bookings = bookingProviderRegistry,
                    bookingTargets = bookingTargets,
                    slack = slackNotifications,
                ),
            ),
        )
    val watchAlertDispatcher =
        WatchAlertDispatcher(
            slack = slackNotifications,
            scopeResolver = watchScopeResolver,
            watches = AvailabilityWatchRepo(boot.ctx),
            targets = availabilityTargets,
            pois = PoiServingRepo(boot.ctx),
            availability = availability,
            triggerActions = triggerActions,
            grafanaRootUrl = boot.appConfig.grafana?.rootUrl,
            appRootUrl = boot.appConfig.webApp?.rootUrl,
        )
    // Interactivity wiring only exists when the Slack app is configured with a
    // signing secret — no secret means we can't verify inbound requests, so
    // the endpoint stays unregistered rather than accepting unverifiable input.
    val slackInteractivity =
        boot.appConfig.slack?.signingSecret?.let { secret ->
            val watchRepo = AvailabilityWatchRepo(boot.ctx)
            val watchesPort =
                object : SlackInteractivityHandler.Watches {
                    override fun setStatus(
                        id: Long,
                        status: ca.floo.roadtrip.service.availability.WatchStatus,
                    ) = availabilityWatchService.update(id, AvailabilityWatchRepo.UpdateInput(status = status))

                    override fun snapshotAndDelete(id: Long): AvailabilityWatchRepo.Watch? {
                        // Snapshot pre-delete so the goodbye card can still resolve
                        // POI names / dates — mirrors the HTTP delete route which
                        // captures the row before the FK cascade drops its links.
                        val snapshot = watchRepo.findById(id) ?: return null
                        return if (availabilityWatchService.delete(id)) snapshot else null
                    }

                    override fun buildStatusNotice(
                        watch: AvailabilityWatchRepo.Watch,
                        state: WatchStatusNotice.State,
                    ) = watchAlertDispatcher.statusNoticeForWatch(watch, state)
                }
            log.info(
                "Slack interactivity ENABLED: signing secret set ({} chars), POST /api/slack/interactivity is live",
                secret.length,
            )
            SlackInteractivityWiring(
                verifier = SlackSignatureVerifier(secret),
                handler = SlackInteractivityHandler(watches = watchesPort, slack = slackNotifications),
            )
        } ?: run {
            val reason =
                when {
                    boot.appConfig.slack == null ->
                        "Slack is disabled (no roadtrip.slack.bot-token / roadtrip.slack.default-channel)"
                    else ->
                        "roadtrip.slack.signing-secret is not set — outbound Slack works, but the interactivity endpoint stays unregistered"
                }
            log.info("Slack interactivity DISABLED: {}", reason)
            null
        }
    // One in-process cooldown tracker shared by both the poller and the live
    // path — a cooldown observed in either surface should demote the same
    // provider in the other. Configurable via roadtrip.availability.provider-cooldown.
    val providerCooldowns =
        ProviderCooldownTracker(cooldown = boot.appConfig.availability.providerCooldown)
    val sharedFailoverFetcher = FailoverAvailabilityFetcher(cooldowns = providerCooldowns)
    Scheduler(
        repo = availabilityPollers,
        handler =
            AvailabilityPollExecutor(
                pollers = availabilityPollers,
                campsitesRepo = campsitesRepo,
                batcher = CatalogAvailabilityBatcher(),
                availability = availability,
                runs = AvailabilityRunRepo(boot.ctx),
                dateResolver = availabilityDateResolver,
                targets = availabilityTargets,
                fetchCalls = AvailabilityFetchCallRepo(boot.ctx),
                limiter =
                    VendorRateLimiter(
                        boot.appConfig.vendorRateLimit,
                        boot.dataSource,
                    ),
                alertDispatcher = watchAlertDispatcher,
                failoverFetcher = sharedFailoverFetcher,
            )::handle,
        name = "availability",
    ).start(schedulerScope)
    WatchReaper(availabilityPollers).start(schedulerScope)

    return RoadtripRuntime(
        boot = boot,
        availabilityProviderRegistry = availabilityProviderRegistry,
        campsiteProviders = campsiteProviders,
        availabilityDateResolver = availabilityDateResolver,
        availabilityWatchService = availabilityWatchService,
        watchAlertDispatcher = watchAlertDispatcher,
        dispatchService = dispatchService,
        dispatchTestEventService = dispatchTestEventService,
        bookingProviderRegistry = bookingProviderRegistry,
        watchBookingCapabilities = watchBookingCapabilities,
        schedulerScope = schedulerScope,
        slackInteractivity = slackInteractivity,
        failoverFetcher = sharedFailoverFetcher,
        slackNotifications = slackNotifications,
    )
}

private class DeferredDispatchEnqueuer : DispatchEnqueuer {
    var delegate: DispatchEnqueuer? = null

    override suspend fun enqueue(input: DispatchCreateInput) =
        checkNotNull(delegate) { "dispatch enqueuer used before runtime wiring completed" }
            .enqueue(input)
}

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
