package ca.floo.roadtrip.di

import ca.floo.roadtrip.client.companion.HttpRecGovAtcExecutor
import ca.floo.roadtrip.client.slack.SlackSignatureVerifier
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.config.ReadPathProviderConfig
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.service.availability.AtcTriggerActionHandler
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.AvailabilityRunService
import ca.floo.roadtrip.service.availability.AvailabilityTriggerKinds
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.CampgroundAvailabilitySupport
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.CoordinateTimeZones
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.DispatchingWatchLifecycleNotifications
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.NotifyTriggerActionHandler
import ca.floo.roadtrip.service.availability.ProviderCooldownTracker
import ca.floo.roadtrip.service.availability.TriggerActionRegistry
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.WatchTriggerCapabilityValidator
import ca.floo.roadtrip.service.availability.alert.AlertProviderRegistry
import ca.floo.roadtrip.service.availability.alert.InternalPollerAlertProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderClients
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.booking.RecGovBookingProvider
import ca.floo.roadtrip.service.notification.common.NotificationFanout
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import ca.floo.roadtrip.service.notification.slack.SlackInteractivityHandler
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import ca.floo.roadtrip.service.poi.CampgroundService
import ca.floo.roadtrip.service.poi.DEFAULT_POI_TYPES
import ca.floo.roadtrip.service.poi.PlanetFitnessLocationService
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
import kotlinx.coroutines.CoroutineScope
import org.jooq.DSLContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import javax.sql.DataSource

val serviceModule =
    module {
        single {
            val config: AppConfig = get()
            SlackNotificationService(config.slack)
        }
        single {
            val config: AppConfig = get()
            EmailNotificationService(config.email)
        }
        single {
            NotificationFanout(
                listOf(get<SlackNotificationService>(), get<EmailNotificationService>()),
            )
        }

        single {
            AvailabilityProviderClients(
                recgovClient = get(),
                aspiraClient = get(),
                reserveAmericaClient = get(),
                reserveCaliforniaClient = get(),
                campflareClient = get(),
            )
        }

        single {
            val config: AppConfig = get()
            validateReadPathDataSources(config.readPathProviders, get())
            AvailabilityProviderRegistry.fromPoiRegistry(
                registry = get(),
                clients = get(),
                isProviderEnabled = { id -> config.isProviderEnabled(id) },
            )
        }

        single {
            CoordinateTimeZones.warmUp()
            AvailabilityDateResolver()
        }
        single { WatchScopeResolver(get<CampsiteRepo>()) }
        single {
            DbAvailabilityTargetResolver(
                providerRefs = get<CampsiteProviderRepo>(),
                campsitesRepo = get<CampsiteRepo>(),
                availabilityProviders = get<AvailabilityProviderRegistry>(),
                dateResolver = get<AvailabilityDateResolver>(),
                pollers = get<AvailabilityPollerRepo>(),
            )
        }
        single { AvailabilityPollerMembership(get<WatchScopeResolver>(), get<DbAvailabilityTargetResolver>()) }

        single(named("alertProviders")) { listOf(InternalPollerAlertProvider(get<AvailabilityPollerMembership>())) }
        single { AlertProviderRegistry(get(named("alertProviders"))) }

        single(named("bookingProviders")) {
            val config: AppConfig = get()
            val atcExecutor =
                config.booking.recgovAtc
                    .takeIf { it.companionEnabled }
                    ?.let(::HttpRecGovAtcExecutor)
            listOfNotNull(
                atcExecutor?.let(::RecGovBookingProvider),
            )
        }
        single { BookingProviderRegistry(get(named("bookingProviders"))) }
        single { AvailabilityBookingTargetResolver(get<BookingProviderRegistry>()) }

        single {
            val config: AppConfig = get()
            WatchCapabilityService(
                availabilityTargets = get<DbAvailabilityTargetResolver>(),
                bookingTargets = get<AvailabilityBookingTargetResolver>(),
                notificationTriggerKinds = notificationTriggerKinds(emailConfigured = config.email != null),
            )
        }
        single(named("triggerActionHandlers")) {
            listOf(
                NotifyTriggerActionHandler(
                    notifications = get<NotificationFanout>(),
                    appRootUrl = get<AppConfig>().webApp?.rootUrl,
                ),
                AtcTriggerActionHandler(
                    bookings = get<BookingProviderRegistry>(),
                    bookingTargets = get<AvailabilityBookingTargetResolver>(),
                    notifications = get<NotificationFanout>(),
                ),
            )
        }
        single { TriggerActionRegistry(get(named("triggerActionHandlers"))) }

        single {
            val config: AppConfig = get()
            WatchAlertDispatcher(
                notifications = get<NotificationFanout>(),
                scopeResolver = get<WatchScopeResolver>(),
                watches = get<AvailabilityWatchRepo>(),
                targets = get<DbAvailabilityTargetResolver>(),
                pois = get<PoiServingRepo>(),
                availability = get<AvailabilityRepo>(),
                triggerActions = get<TriggerActionRegistry>(),
                grafanaRootUrl = config.grafana?.rootUrl,
                appRootUrl = config.webApp?.rootUrl,
            )
        }

        single {
            WatchTriggerCapabilityValidator(
                scopeResolver = get<WatchScopeResolver>(),
                capabilities = get<WatchCapabilityService>(),
            )
        }
        single {
            AvailabilityWatchService(
                ctx = get<DSLContext>(),
                alertProviders = get<AlertProviderRegistry>(),
                capabilityValidator = get<WatchTriggerCapabilityValidator>(),
                lifecycleNotifications =
                    DispatchingWatchLifecycleNotifications(
                        dispatcher = get<WatchAlertDispatcher>(),
                        scope = get<CoroutineScope>(),
                    ),
            )
        }

        single {
            val config: AppConfig = get()
            ProviderCooldownTracker(cooldown = config.availability.providerCooldown)
        }
        single { FailoverAvailabilityFetcher(cooldowns = get<ProviderCooldownTracker>()) }
        single { CampgroundAvailabilitySupport(providerRefs = get<CampsiteProviderRepo>(), availabilityProviders = get()) }
        single {
            VendorRateLimiter(get<AppConfig>().vendorRateLimit, get<DataSource>())
        }

        single {
            AvailabilityRunService(
                runs = get<AvailabilityRunRepo>(),
                availability = get<AvailabilityRepo>(),
                fetchCalls = get<AvailabilityFetchCallRepo>(),
            )
        }
        single {
            AvailabilityPollExecutor(
                pollers = get<AvailabilityPollerRepo>(),
                campsitesRepo = get<CampsiteRepo>(),
                batcher = CatalogAvailabilityBatcher(),
                availability = get<AvailabilityRepo>(),
                runs = get<AvailabilityRunRepo>(),
                dateResolver = get<AvailabilityDateResolver>(),
                targets = get<DbAvailabilityTargetResolver>(),
                fetchCalls = get<AvailabilityFetchCallRepo>(),
                limiter = get<VendorRateLimiter>(),
                alertDispatcher = get<WatchAlertDispatcher>(),
                failoverFetcher = get<FailoverAvailabilityFetcher>(),
            )
        }
        single(createdAtStart = true) {
            Scheduler(
                repo = get<AvailabilityPollerRepo>(),
                handler = get<AvailabilityPollExecutor>()::handle,
                name = "availability",
            ).also { it.start(get<CoroutineScope>()) }
        }
        single(createdAtStart = true) {
            WatchReaper(get<AvailabilityPollerRepo>()).also { it.start(get<CoroutineScope>()) }
        }
        single(createdAtStart = true) {
            PollerBackfill(get<DSLContext>(), get<AvailabilityPollerMembership>()).also { it.run() }
        }

        single(named("poiDetailServices")) {
            listOf(
                CampgroundService(
                    repo = get<CampgroundRepo>(),
                    dateResolver = get<AvailabilityDateResolver>(),
                    availabilitySupport = get<CampgroundAvailabilitySupport>(),
                ),
                TeslaSuperchargerService(get<TeslaSuperchargerRepo>()),
                PlanetFitnessLocationService(get<PlanetFitnessLocationRepo>()),
            )
        }
        single { RouteCorridorService(get<RouteCorridorRepo>()) }
        single {
            PoiService(
                poiRepo = get<PoiServingRepo>(),
                detailServices = get(named("poiDetailServices")),
            )
        }
        single<PoiReader> {
            ReadPathProviderPoiReader(
                delegate = get<PoiService>(),
                detailServices = get(named("poiDetailServices")),
                providers = get<AppConfig>().readPathProviders,
            )
        }
        single {
            PoisOnRouteService(
                routeCache = get<RouteCache>(),
                routeCorridorService = get<RouteCorridorService>(),
                poiService = get<PoiReader>(),
            )
        }
    }

fun slackInteractivityModule(signingSecret: String) =
    module {
        single {
            SlackInteractivityWiring(
                verifier = SlackSignatureVerifier(signingSecret),
                handler =
                    SlackInteractivityHandler(
                        watches =
                            SlackInteractivityWatchesPort(
                                watchService = get<AvailabilityWatchService>(),
                                alertDispatcher = get<WatchAlertDispatcher>(),
                            ),
                        slack = get<SlackNotificationService>(),
                    ),
            )
        }
    }

private fun AppConfig.isProviderEnabled(id: AvailabilityProviderId): Boolean =
    readPathProviders.isAvailabilityProviderEnabled(id.name.lowercase()) &&
        (id != AvailabilityProviderId.CAMPFLARE || !campflare.apiKey.isNullOrBlank())

internal fun notificationTriggerKinds(emailConfigured: Boolean): List<String> =
    buildList {
        add(AvailabilityTriggerKinds.SLACK_NOTIFY)
        if (emailConfigured) {
            add(AvailabilityTriggerKinds.EMAIL_NOTIFY)
        }
    }

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
