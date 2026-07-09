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
import ca.floo.roadtrip.repo.CatalogMatchRepo
import ca.floo.roadtrip.repo.DbConfig
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.dataSourceFor
import ca.floo.roadtrip.repo.dsl
import ca.floo.roadtrip.repo.migrate
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.CoordinateTimeZones
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.ProviderCooldownTracker
import ca.floo.roadtrip.service.availability.SlackNotifyHandler
import ca.floo.roadtrip.service.availability.TriggerActionRegistry
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.alert.AlertProviderRegistry
import ca.floo.roadtrip.service.availability.alert.InternalPollerAlertProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderClients
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistryFactory
import ca.floo.roadtrip.service.catalog.CatalogMatcherService
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.etl.framework.fetchTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.importTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.sweepStaleIngestRuns
import ca.floo.roadtrip.service.notification.SlackInteractivityHandler
import ca.floo.roadtrip.service.notification.SlackNotificationServiceImpl
import ca.floo.roadtrip.service.notification.WatchStatusNotice
import ca.floo.roadtrip.service.ratelimit.VendorRateLimitConfig
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
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("ca.floo.roadtrip.RoadtripRuntime")

/** Pair of the signature verifier + the handler; wired only when the Slack
 *  app is configured with a signing secret. Null on a Slack-disabled install
 *  so the interactivity endpoint is absent (404) rather than answering 401. */
internal data class SlackInteractivityWiring(
    val verifier: SlackSignatureVerifier,
    val handler: SlackInteractivityHandler,
)

internal data class RoadtripBootContext(
    val appConfig: AppConfig,
    val dataSource: DataSource,
    val ctx: DSLContext,
    val availabilityProviderClients: AvailabilityProviderClients,
    val staticDir: File,
    val mapboxGeocoder: MapboxGeocoder,
    val routeCache: RouteCache,
    val poiRegistry: PoiRegistry,
    val ingestController: IngestController,
)

internal class RoadtripRuntime(
    val boot: RoadtripBootContext,
    val availabilityProviderRegistry: AvailabilityProviderRegistry,
    val campsiteProviders: CampsiteProviderRepo,
    val availabilityDateResolver: AvailabilityDateResolver,
    val availabilityWatchService: AvailabilityWatchService,
    val watchAlertDispatcher: WatchAlertDispatcher,
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

internal fun createRoadtripBootContext(): RoadtripBootContext {
    val appConfig = AppConfig.fromEnv()
    val ds = dataSourceFor(DbConfig.fromEnv())
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

    val staticDir = File(System.getenv("ROADTRIP_STATIC_DIR") ?: ".")
    val mapboxToken = System.getenv("MAPBOX_TOKEN")
    val mapboxGeocoder = MapboxGeocoder(token = mapboxToken)
    val routeCache =
        RouteCache(
            MapboxDirections(token = mapboxToken),
            ttl = appConfig.cache.ttlFor(ApiCacheEntity.ROUTE),
            persistentCache = ApiCacheRepo(ctx),
        )

    val poiRegistry = PoiRegistry.load(File(staticDir, "config/poi-registry.yaml"))

    sweepStaleIngestRuns(ctx)
    val catalogMatcher =
        CatalogMatcherService(
            matches = CatalogMatchRepo(ctx),
            config = CatalogMatcherService.MatcherConfig.fromEnv(),
        )
    val canonicalViews = CanonicalViewRepo(ctx)
    val ingestController =
        IngestController(
            ctx = ctx,
            etl =
                EtlOrchestrator(
                    ctx = ctx,
                    rawDir = File(staticDir, "data/raw"),
                    poiRegistry = poiRegistry,
                    matcher = catalogMatcher,
                    canonicalViews = canonicalViews,
                ),
            fetchTargets = fetchTargetsFromRegistry(poiRegistry, staticDir),
            importTargets = importTargetsFromRegistry(poiRegistry),
            workingDir = staticDir,
        )

    return RoadtripBootContext(
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
        AvailabilityProviderRegistryFactory.build(
            registry = boot.poiRegistry,
            clients = boot.availabilityProviderClients,
            campflareApiKeyConfigured = boot.appConfig.campflare.apiKey != null,
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
    val pollerMembership = AvailabilityPollerMembership(WatchScopeResolver(campsitesRepo), availabilityTargets)
    val alertProviders = AlertProviderRegistry(listOf(InternalPollerAlertProvider(pollerMembership)))
    val availabilityWatchService = AvailabilityWatchService(boot.ctx, alertProviders)

    val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val availabilityPollers = AvailabilityPollerRepo(boot.ctx)
    PollerBackfill(boot.ctx, pollerMembership).run()

    val slackNotifications = SlackNotificationServiceImpl(boot.appConfig.slack)
    val triggerActions =
        TriggerActionRegistry(
            listOf(
                SlackNotifyHandler(
                    slack = slackNotifications,
                    appRootUrl = boot.appConfig.webApp?.rootUrl,
                ),
            ),
        )
    val watchAlertDispatcher =
        WatchAlertDispatcher(
            slack = slackNotifications,
            scopeResolver = WatchScopeResolver(campsitesRepo),
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
                    boot.appConfig.slack == null -> "Slack is disabled (no SLACK_BOT_TOKEN / SLACK_ALERT_CHANNEL)"
                    else -> "SLACK_SIGNING_SECRET is not set — outbound Slack works, but the interactivity endpoint stays unregistered"
                }
            log.info("Slack interactivity DISABLED: {}", reason)
            null
        }
    // One in-process cooldown tracker shared by both the poller and the live
    // path — a cooldown observed in either surface should demote the same
    // provider in the other. Env-configurable via
    // AVAILABILITY_PROVIDER_COOLDOWN_SECONDS.
    val providerCooldowns = ProviderCooldownTracker.fromEnv()
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
                limiter = VendorRateLimiter(VendorRateLimitConfig.fromEnv(), boot.dataSource),
                alertDispatcher = watchAlertDispatcher,
                failoverFetcher = sharedFailoverFetcher,
                campsiteProviderRepo = campsiteProviders,
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
        schedulerScope = schedulerScope,
        slackInteractivity = slackInteractivity,
        failoverFetcher = sharedFailoverFetcher,
        slackNotifications = slackNotifications,
    )
}
