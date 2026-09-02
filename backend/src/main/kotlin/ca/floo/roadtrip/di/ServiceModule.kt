package ca.floo.roadtrip.di

import ca.floo.roadtrip.client.companion.CompanionSessionClient
import ca.floo.roadtrip.client.companion.HttpRecGovAtcExecutor
import ca.floo.roadtrip.client.slack.SlackClient
import ca.floo.roadtrip.client.slack.SlackSignatureVerifier
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.config.ReadPathProviderConfig
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.observability.RoadtripMetrics
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.auth.ClaimsDialectRegistry
import ca.floo.roadtrip.service.auth.MagicLinkTokenService
import ca.floo.roadtrip.service.availability.AtcTriggerActionHandler
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.AvailabilityRunService
import ca.floo.roadtrip.service.availability.AvailabilityTriggerKinds
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
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
import ca.floo.roadtrip.service.availability.WatchNotificationTargetResolver
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.WatchTriggerCapabilityValidator
import ca.floo.roadtrip.service.availability.alert.AlertProviderRegistry
import ca.floo.roadtrip.service.availability.alert.InternalPollerAlertProvider
import ca.floo.roadtrip.service.availability.provider.AspiraAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AspiraTenants
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.CampflareAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.RecGovAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.ReserveAmericaAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.ReserveCaliforniaAvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import ca.floo.roadtrip.service.booking.RecGovBookingAdapter
import ca.floo.roadtrip.service.health.ReadinessService
import ca.floo.roadtrip.service.health.ReadinessServiceImpl
import ca.floo.roadtrip.service.notification.common.NotificationFanout
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import ca.floo.roadtrip.service.notification.slack.SlackInteractivityHandler
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import ca.floo.roadtrip.service.poi.CampgroundService
import ca.floo.roadtrip.service.poi.PlanetFitnessLocationService
import ca.floo.roadtrip.service.poi.PoiReader
import ca.floo.roadtrip.service.poi.PoiService
import ca.floo.roadtrip.service.poi.PoisOnRouteService
import ca.floo.roadtrip.service.poi.TeslaSuperchargerService
import ca.floo.roadtrip.service.poi.defaultPoiTypes
import ca.floo.roadtrip.service.ratelimit.VendorRateLimiter
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RouteCorridorService
import ca.floo.roadtrip.service.scheduler.PollerBackfill
import ca.floo.roadtrip.service.scheduler.WatchReaper
import ca.floo.roadtrip.service.scheduler.framework.Scheduler
import ca.floo.roadtrip.service.scheduler.jobs.AvailabilityPollExecutor
import ca.floo.roadtrip.service.security.SecretCipher
import ca.floo.roadtrip.service.settings.CompanionSessionPort
import ca.floo.roadtrip.service.settings.RecGovCredentialService
import ca.floo.roadtrip.service.settings.UserSettingsService
import kotlinx.coroutines.CoroutineScope
import org.jooq.DSLContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import javax.sql.DataSource

val serviceModule =
    module {
        single {
            val config: AppConfig = get()
            // Per-user Slack transport is ALWAYS available (even when config.slack
            // is null) so watch cards with an owner token can be delivered without
            // a global bot token. SlackClient(null) is a valid per-user-only client.
            SlackNotificationService(config.slack, SlackClient(config.slack))
        }
        single { MagicLinkTokenService(watchRepo = get<AvailabilityWatchRepo>()) }
        single { EmailNotificationService(get<AppConfig>().email) }
        single {
            NotificationFanout(
                listOf(get<SlackNotificationService>(), get<EmailNotificationService>()),
            )
        }

        single {
            val config: AppConfig = get()
            val providerLabel: String? =
                config.auth?.provider?.let { ClaimsDialectRegistry.default().displayNameFor(it) }
            // Optional deps are built inline here, NOT registered as `single<T?>`.
            // A Koin `single { }` that produces null throws at resolution
            // ("Single instance created couldn't return value") — which crashed
            // boot on any install without an encryption key / Slack configured.
            // cipher == null => token storage disabled (encryption key absent).
            val cipher: SecretCipher? = config.secrets?.let { SecretCipher(it.encryptionKey) }
            // Per-user Slack transport is ALWAYS available and is independent of the
            // global watch-alert Slack config: authTest(token) and the token-
            // parameterized postMessage carry their own token. Gating this on
            // config.slack made saving a user token 500 on installs with an
            // encryption key but no global bot-token/channel.
            val slackClient = SlackClient(config.slack)
            UserSettingsService(
                userRepo = get<UserRepo>(),
                settingsRepo = get<UserSettingsRepo>(),
                cipher = cipher,
                slackClient = slackClient,
                providerLabel = providerLabel,
                emailService = get<EmailNotificationService>(),
                appRootUrl = config.webApp?.rootUrl,
            )
        }

        single {
            // Same optional-dependency shape as the settings service above: a
            // deployment without an encryption key or without a companion still
            // boots, with storage or the live session degraded rather than absent.
            val config: AppConfig = get()
            val cipher: SecretCipher? = config.secrets?.let { SecretCipher(it.encryptionKey) }
            val companion: CompanionSessionPort? =
                config.booking.recgovAtc
                    .takeIf { it.companionEnabled }
                    ?.let(::CompanionSessionClient)
            RecGovCredentialService(
                settingsRepo = get<UserSettingsRepo>(),
                watchRepo = get<AvailabilityWatchRepo>(),
                cipher = cipher,
                companion = companion,
            )
        }

        single<List<AvailabilityProvider>>(named("availabilityProviders")) {
            val config: AppConfig = get()
            validateReadPathDataProviders(config.readPathProviders, get())
            listOf(
                RecGovAvailabilityProvider(
                    availabilityClient = get(),
                    enabled = config.isProviderEnabled(BookingProvider.RECGOV),
                ),
                CampflareAvailabilityProvider(
                    availabilityClient = get(),
                    enabled = config.isProviderEnabled(BookingProvider.CAMPFLARE),
                ),
                ReserveCaliforniaAvailabilityProvider(
                    availabilityClient = get(),
                    enabled = config.isProviderEnabled(BookingProvider.RESERVECALIFORNIA),
                ),
                AspiraAvailabilityProvider(
                    tenants = AspiraTenants.all().associateBy { it.vendorCode.removePrefix("aspira_") },
                    availabilityClient = get(),
                    enabled = config.isProviderEnabled(BookingProvider.ASPIRA),
                ),
                ReserveAmericaAvailabilityProvider(
                    tenants = ReserveAmericaAvailabilityProvider.tenants,
                    availabilityClient = get(),
                    enabled = config.isProviderEnabled(BookingProvider.RESERVEAMERICA),
                ),
            )
        }

        single {
            CoordinateTimeZones.warmUp()
            AvailabilityDateResolver(poiRepo = get<PoiRepo>())
        }
        single { WatchScopeResolver(get<CampsiteRepo>()) }
        single {
            DbAvailabilityTargetResolver(
                poiRepo = get<PoiRepo>(),
                campsitesRepo = get<CampsiteRepo>(),
                campgroundRepo = get<ca.floo.roadtrip.repo.CampgroundRepo>(),
                availabilityProviders = get(named("availabilityProviders")),
                dateResolver = get<AvailabilityDateResolver>(),
                pollerRepo = get<AvailabilityPollerRepo>(),
                pollerConfig = get<AppConfig>().availability.poller,
            )
        }
        single { AvailabilityPollerMembership(get<WatchScopeResolver>(), get<DbAvailabilityTargetResolver>()) }

        single(named("alertProviders")) { listOf(InternalPollerAlertProvider(get<AvailabilityPollerMembership>())) }
        single { AlertProviderRegistry(get(named("alertProviders"))) }

        single(named("bookingAdapters")) {
            val config: AppConfig = get()
            val atcExecutor =
                config.booking.recgovAtc
                    .takeIf { it.companionEnabled }
                    ?.let(::HttpRecGovAtcExecutor)
            listOfNotNull(
                atcExecutor?.let(::RecGovBookingAdapter),
            )
        }
        single { BookingAdapterRegistry(get(named("bookingAdapters"))) }
        single { AvailabilityBookingTargetResolver(get<BookingAdapterRegistry>()) }

        single {
            val config: AppConfig = get()
            WatchCapabilityService(
                availabilityTargets = get<DbAvailabilityTargetResolver>(),
                bookingTargets = get<AvailabilityBookingTargetResolver>(),
                notificationTriggerKinds = notificationTriggerKinds(emailConfigured = config.email != null),
            )
        }
        single {
            // Resolves owner-scoped Slack channel/token so alert cards only reach a
            // channel the watch's owner controls (no shared-default fallback). The
            // cipher (same instance the settings service uses) decrypts the owner's
            // stored token; null when the encryption key is unconfigured.
            val config: AppConfig = get()
            val cipher: SecretCipher? = config.secrets?.let { SecretCipher(it.encryptionKey) }
            WatchNotificationTargetResolver(
                userSettingsRepo = get<UserSettingsRepo>(),
                userRepo = get<UserRepo>(),
                cipher = cipher,
                magicLinkTokenService = get<MagicLinkTokenService>(),
                appRootUrl = config.webApp?.rootUrl,
            )
        }
        single(named("triggerActionHandlers")) {
            listOf(
                NotifyTriggerActionHandler(
                    notifications = get<NotificationFanout>(),
                    targetResolver = get<WatchNotificationTargetResolver>(),
                    appRootUrl = get<AppConfig>().webApp?.rootUrl,
                ),
                AtcTriggerActionHandler(
                    bookings = get<BookingAdapterRegistry>(),
                    bookingTargets = get<AvailabilityBookingTargetResolver>(),
                    notifications = get<NotificationFanout>(),
                    targetResolver = get<WatchNotificationTargetResolver>(),
                ),
            )
        }
        single { TriggerActionRegistry(get(named("triggerActionHandlers"))) }

        single {
            val config: AppConfig = get()
            WatchAlertDispatcher(
                notifications = get<NotificationFanout>(),
                scopeResolver = get<WatchScopeResolver>(),
                watchRepo = get<AvailabilityWatchRepo>(),
                targetResolver = get<WatchNotificationTargetResolver>(),
                targets = get<DbAvailabilityTargetResolver>(),
                poiRepo = get<PoiServingRepo>(),
                availabilityRepo = get<AvailabilityRepo>(),
                triggerActions = get<TriggerActionRegistry>(),
                grafanaRootUrl = config.grafana?.rootUrl,
                appRootUrl = config.webApp?.rootUrl,
                metrics = get<RoadtripMetrics>(),
            )
        }

        single {
            WatchTriggerCapabilityValidator(
                scopeResolver = get<WatchScopeResolver>(),
                watchCapabilityService = get<WatchCapabilityService>(),
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
        single {
            VendorRateLimiter(get<AppConfig>().vendorRateLimit, get<DataSource>())
        }

        single {
            AvailabilityRunService(
                runRepo = get<AvailabilityRunRepo>(),
                availabilityRepo = get<AvailabilityRepo>(),
                fetchCallRepo = get<AvailabilityFetchCallRepo>(),
                metrics = get<RoadtripMetrics>(),
            )
        }
        single {
            AvailabilityPollExecutor(
                targetResolver = get<DbAvailabilityTargetResolver>(),
                batcher = CatalogAvailabilityBatcher(),
                runService = get<AvailabilityRunService>(),
                limiter = get<VendorRateLimiter>(),
                alertDispatcher = get<WatchAlertDispatcher>(),
                failoverFetcher = get<FailoverAvailabilityFetcher>(),
                metrics = get<RoadtripMetrics>(),
                pollerConfig = get<AppConfig>().availability.poller,
            )
        }
        single(createdAtStart = true) {
            Scheduler(
                schedulableRepo = get<AvailabilityPollerRepo>(),
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
                    campgroundRepo = get<CampgroundRepo>(),
                    dateResolver = get<AvailabilityDateResolver>(),
                ),
                TeslaSuperchargerService(get<TeslaSuperchargerRepo>()),
                PlanetFitnessLocationService(get<PlanetFitnessLocationRepo>()),
            )
        }
        single { RouteCorridorService(get<RouteCorridorRepo>()) }
        single {
            val config: AppConfig = get()
            PoiServingRepo(
                ctx = get<DSLContext>(),
                enabledDataProviders = config.readPathProviders.enabledDataProviders,
            )
        }
        single {
            PoiService(
                poiRepo = get<PoiServingRepo>(),
                detailServices = get(named("poiDetailServices")),
            )
        }
        single<PoiReader> { get<PoiService>() }
        single<ReadinessService> { ReadinessServiceImpl(databaseHealthRepo = get()) }
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

private fun AppConfig.isProviderEnabled(id: ca.floo.roadtrip.model.domain.provider.BookingProvider): Boolean =
    readPathProviders.isAvailabilityProviderEnabled(id.id) &&
        (id != ca.floo.roadtrip.model.domain.provider.BookingProvider.CAMPFLARE || !campflare.apiKey.isNullOrBlank())

internal fun notificationTriggerKinds(emailConfigured: Boolean): List<String> =
    buildList {
        add(AvailabilityTriggerKinds.SLACK_NOTIFY)
        if (emailConfigured) {
            add(AvailabilityTriggerKinds.EMAIL_NOTIFY)
        }
    }

internal fun validateReadPathDataProviders(
    providers: ReadPathProviderConfig,
    registry: PoiRegistry,
) {
    val supported = supportedReadPathDataProviders(registry)
    val unknown = providers.enabledDataProviders - supported
    require(unknown.isEmpty()) {
        "roadtrip.read-path.enabled-data-providers contains unknown provider(s): " +
            "${unknown.sorted()}. Expected one of: ${supported.sorted()}."
    }
}

private fun supportedReadPathDataProviders(registry: PoiRegistry): Set<String> =
    registry.poiData
        .mapNotNull { row -> row.etls.lastOrNull()?.slug }
        .toSet() +
        canonicalCampgroundSourceKeys(registry) +
        defaultPoiTypes.filter { it != CampgroundService.POI_TYPE }

private fun canonicalCampgroundSourceKeys(registry: PoiRegistry): Set<String> =
    buildSet {
        if (registry.campflareSources().isNotEmpty()) add(DataProvider.CAMPFLARE.id)
        if (registry.recgovSources().isNotEmpty()) add(DataProvider.RECGOV.id)
        if (registry.hostBySource().any { (_, host) -> AspiraTenants.byHost(host) != null }) add(DataProvider.ASPIRA.id)
        if (registry.bcParksSources().isNotEmpty()) add(DataProvider.STRAPI.id)
        if (registry.reserveAmericaSources().isNotEmpty()) add(DataProvider.RESERVEAMERICA.id)
        if (registry.reserveCaliforniaSources().isNotEmpty()) add(DataProvider.RESERVECALIFORNIA.id)
    }
