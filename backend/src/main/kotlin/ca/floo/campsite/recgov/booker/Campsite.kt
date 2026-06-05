package ca.floo.campsite.recgov.booker

import ca.floo.campsite.recgov.booker.api.alertRoutes
import ca.floo.campsite.recgov.booker.api.campgroundSearchRoutes
import ca.floo.campsite.recgov.booker.api.companionRoutes
import ca.floo.campsite.recgov.booker.api.eventsRoutes
import ca.floo.campsite.recgov.booker.api.matchRoutes
import ca.floo.campsite.recgov.booker.api.pollRoutes
import ca.floo.campsite.recgov.booker.api.settingsRoutes
import ca.floo.campsite.recgov.booker.api.statusRoutes
import ca.floo.campsite.recgov.booker.db.AlertRepo
import ca.floo.campsite.recgov.booker.db.MatchRepo
import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.events.CompanionRegistry
import ca.floo.campsite.recgov.booker.events.EventBus
import ca.floo.campsite.recgov.booker.notifier.SlackNotifier
import ca.floo.campsite.recgov.booker.poller.AvailabilityClient
import ca.floo.campsite.recgov.booker.poller.Poller
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.sse.SSE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jooq.DSLContext
import java.time.Duration

// Campsite subsystem wired into the merged roadtrip Ktor app. Owns its own
// poller, EventBus, and CompanionRegistry; shares the host's DataSource +
// jOOQ DSLContext (one Hikari pool, one schema).

class CampsiteServices(
    val alerts: AlertRepo,
    val matches: MatchRepo,
    val settings: SettingsRepo,
    val bus: EventBus,
    val companions: CompanionRegistry,
    val slack: SlackNotifier,
    val poller: Poller,
    val availability: AvailabilityClient,
    val leaseDuration: Duration,
)

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
fun Application.campsiteModule(ctx: DSLContext): CampsiteServices {
    val bus = EventBus()
    val leaseSec = System.getenv("CAMPSITE_LEASE_SEC")?.toLongOrNull()
    val offlineSec = System.getenv("CAMPSITE_OFFLINE_SEC")?.toLongOrNull()
    val leaseDuration = if (leaseSec != null) Duration.ofSeconds(leaseSec) else Duration.ofMinutes(5)
    val offlineThreshold = if (offlineSec != null) Duration.ofSeconds(offlineSec) else Duration.ofSeconds(90)

    val alerts = AlertRepo(ctx)
    val matches = MatchRepo(ctx)
    val settings = SettingsRepo(ctx)
    settings.seedDefaults(System.getenv())
    val companions = CompanionRegistry(offlineThreshold)
    val slack = SlackNotifier(settings)
    val availability = AvailabilityClient()
    val poller = Poller(alerts, matches, settings, bus, client = availability, slack = slack)

    install(SSE)

    poller.start()

    // Background: sweep expired leases (CLAIMED → unclaimed if no result by lease).
    GlobalScope.launch(Dispatchers.Default) {
        while (true) {
            for (m in matches.sweepExpiredLeases()) {
                bus.publish("lease_expired", """{"id":${m.id},"reason":"lease_expired"}""")
            }
            delay(5_000)
        }
    }

    // Background: detect companion offline.
    GlobalScope.launch(Dispatchers.Default) {
        while (true) {
            for (e in companions.sweepOffline()) {
                bus.publish("companion_offline", """{"companionId":"${e.id}","lastSeen":"${e.lastSeen}"}""")
            }
            delay(5_000)
        }
    }

    // Liveness ticks so the SSE stream visibly works without depending on real activity.
    GlobalScope.launch(Dispatchers.Default) {
        while (true) {
            delay(10_000)
            bus.publish("tick", """{"at":"${java.time.Instant.now()}"}""")
        }
    }

    return CampsiteServices(alerts, matches, settings, bus, companions, slack, poller, availability, leaseDuration)
}

fun Route.campsiteRoutes(s: CampsiteServices) {
    eventsRoutes(s.bus)
    alertRoutes(s.alerts, s.poller)
    matchRoutes(s.alerts, s.matches, s.bus, s.availability, s.settings, s.leaseDuration)
    settingsRoutes(s.settings, s.slack)
    statusRoutes(s.settings)
    campgroundSearchRoutes()
    pollRoutes(s.poller)
    companionRoutes(s.companions, s.bus)
}
