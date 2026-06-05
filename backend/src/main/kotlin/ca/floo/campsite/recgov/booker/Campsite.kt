package ca.floo.campsite.recgov.booker

import ca.floo.campsite.recgov.booker.api.alertRoutes
import ca.floo.campsite.recgov.booker.api.campgroundSearchRoutes
import ca.floo.campsite.recgov.booker.api.companionRoutes
import ca.floo.campsite.recgov.booker.api.eventsRoutes
import ca.floo.campsite.recgov.booker.api.extendCartHold
import ca.floo.campsite.recgov.booker.api.matchRoutes
import ca.floo.campsite.recgov.booker.api.pollRoutes
import ca.floo.campsite.recgov.booker.api.recgovTokenRoutes
import ca.floo.campsite.recgov.booker.api.settingsRoutes
import ca.floo.campsite.recgov.booker.api.statusRoutes
import ca.floo.campsite.recgov.booker.auth.TokenManager
import ca.floo.campsite.recgov.booker.db.AlertRepo
import ca.floo.campsite.recgov.booker.db.MatchRepo
import ca.floo.campsite.recgov.booker.db.ScheduleRepo
import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.CompanionRegistry
import ca.floo.campsite.recgov.booker.events.EventBus
import ca.floo.campsite.recgov.booker.notifier.SlackNotifier
import ca.floo.campsite.recgov.booker.poller.AvailabilityClient
import ca.floo.campsite.recgov.booker.poller.Poller
import ca.floo.campsite.recgov.booker.scheduler.Scheduler
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.jooq.DSLContext
import java.time.Duration

// Campsite subsystem wired into the merged roadtrip Ktor app. Owns its own
// poller, EventBus, scheduler, and CompanionRegistry; shares the host's
// DataSource + jOOQ DSLContext (one Hikari pool, one schema).

class CampsiteServices(
    val alerts: AlertRepo,
    val matches: MatchRepo,
    val settings: SettingsRepo,
    val schedules: ScheduleRepo,
    val bus: EventBus,
    val companions: CompanionRegistry,
    val slack: SlackNotifier,
    val poller: Poller,
    val scheduler: Scheduler,
    val availability: AvailabilityClient,
    val tokenManager: TokenManager,
    val leaseDuration: Duration,
)

fun Application.campsiteModule(ctx: DSLContext): CampsiteServices {
    val bus = EventBus()
    val leaseSec = System.getenv("CAMPSITE_LEASE_SEC")?.toLongOrNull()
    val offlineSec = System.getenv("CAMPSITE_OFFLINE_SEC")?.toLongOrNull()
    val leaseDuration = if (leaseSec != null) Duration.ofSeconds(leaseSec) else Duration.ofMinutes(5)
    val offlineThreshold = if (offlineSec != null) Duration.ofSeconds(offlineSec) else Duration.ofSeconds(90)

    val alerts = AlertRepo(ctx)
    val matches = MatchRepo(ctx)
    val settings = SettingsRepo(ctx)
    val schedules = ScheduleRepo(ctx)
    settings.seedDefaults(System.getenv())
    val companions = CompanionRegistry(offlineThreshold)
    val slack = SlackNotifier(settings)
    val availability = AvailabilityClient()
    val poller = Poller(alerts, matches, settings, bus, client = availability, slack = slack)

    // Single supervisor scope for all in-process background work (scheduler
    // jobs, sweep subscribers, liveness ticks). Lives for the lifetime of
    // the application — Ktor doesn't give us a graceful-shutdown hook here,
    // but cancellation propagates if the JVM exits.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val scheduler = Scheduler(schedules::listEnabled, alerts::listActiveCadences, bus, scope)
    val tokenManager = TokenManager(settings, bus, scope)

    install(SSE)

    poller.start()
    scheduler.start()
    tokenManager.start()

    // Subscribe to scheduler-fired events. Each handler used to be a
    // dedicated `GlobalScope.launch` loop with its own delay() — now all
    // cadence comes from the schedules table.
    scope.launch {
        bus.typedEvents.collect { env ->
            when (env.event) {
                is CampsiteEvent.LeaseSweepDue ->
                    matches.sweepExpiredLeases().forEach {
                        bus.publish(CampsiteEvent.LeaseExpired(matchId = it.id))
                    }
                is CampsiteEvent.CompanionSweepDue ->
                    companions.sweepOffline().forEach {
                        bus.publish(CampsiteEvent.CompanionOffline(companionId = it.id, lastSeen = it.lastSeen.toString()))
                    }
                else -> Unit
            }
        }
    }

    // Background: keep the rec.gov cart hold alive. The cart is one-per-account,
    // so a single PATCH every 5 min extends whatever the operator has in it.
    // No-op if no token is stored. Plain HTTP works because the cart-expiration
    // endpoint only checks Bearer + r1s-fingerprint cookie — not Akamai TLS
    // fingerprinting (which is what makes ATC need a real Chromium).
    // TODO: migrate to a scheduler-fired CartExtendDue event in a follow-up.
    scope.launch {
        while (true) {
            delay(5 * 60_000)
            val token = settings.get("recgov_token").orEmpty()
            if (token.isEmpty()) continue
            extendCartHold(token)
        }
    }

    return CampsiteServices(
        alerts,
        matches,
        settings,
        schedules,
        bus,
        companions,
        slack,
        poller,
        scheduler,
        availability,
        tokenManager,
        leaseDuration,
    )
}

fun Route.campsiteRoutes(s: CampsiteServices) {
    eventsRoutes(s.bus)
    alertRoutes(s.alerts, s.poller, s.scheduler)
    matchRoutes(s.alerts, s.matches, s.bus, s.availability, s.settings, s.leaseDuration, s.tokenManager)
    settingsRoutes(s.settings, s.slack, s.tokenManager)
    statusRoutes(s.settings, s.tokenManager)
    recgovTokenRoutes(s.tokenManager)
    campgroundSearchRoutes()
    pollRoutes(s.poller)
    companionRoutes(s.companions, s.bus, s.settings)
}
