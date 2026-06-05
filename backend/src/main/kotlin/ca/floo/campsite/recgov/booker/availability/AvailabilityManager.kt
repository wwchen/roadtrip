package ca.floo.campsite.recgov.booker.availability

import ca.floo.campsite.recgov.booker.db.AlertRepo
import ca.floo.campsite.recgov.booker.db.MatchRepo
import ca.floo.campsite.recgov.booker.domain.Alert
import ca.floo.campsite.recgov.booker.domain.Match
import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.EventBus
import ca.floo.campsite.recgov.booker.matching.Matcher
import ca.floo.campsite.recgov.booker.notifier.SlackNotifier
import ca.floo.campsite.recgov.booker.poller.AvailabilityClient
import ca.floo.campsite.recgov.booker.poller.Campsite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Replaces [ca.floo.campsite.recgov.booker.poller.Poller]. Subscribes to:
 *   - [CampsiteEvent.PollDue] (per-alert, fired by the scheduler)
 *   - [CampsiteEvent.UserPolledNow] (UI "check now" / alert create)
 *   - [CampsiteEvent.UserViewedAlert] (debounced soft-poll; UI opening detail)
 *
 * Per-alert [Mutex] (with `tryLock`) ensures a scheduled poll and a
 * user-triggered poll for the same alert can't run concurrently. A
 * [debounce] window suppresses redundant polls — a "check now" right after
 * a scheduled poll is a no-op.
 *
 * Outbound rec.gov requests still funnel through [AvailabilityClient]'s
 * single 1.5s mutex, so the global rate limiter is unchanged.
 */
class AvailabilityManager(
    private val getAlert: (Long) -> Alert?,
    private val listActiveAlerts: () -> List<Alert>,
    private val checkAlertNow: suspend (Alert) -> CheckResult,
    private val bus: EventBus,
    private val scope: CoroutineScope,
    private val debounce: Duration = Duration.ofSeconds(30),
) {
    /** Result returned by [checkAlertNow]. Lets the manager publish PollDone with an accurate success flag. */
    data class CheckResult(
        val newMatchEnvelopes: List<String>,
        val success: Boolean,
        val error: String? = null,
    )

    private val log = LoggerFactory.getLogger(AvailabilityManager::class.java)

    private val perAlertMutex = ConcurrentHashMap<Long, Mutex>()
    private val lastCheckedAt = ConcurrentHashMap<Long, Instant>()

    fun start() {
        scope.launch {
            bus.typedEvents.collect { env ->
                when (val ev = env.event) {
                    is CampsiteEvent.PollDue -> tryPollAlert(ev.alertId, force = false)
                    is CampsiteEvent.UserPolledNow ->
                        if (ev.alertId == null) pollAllActive() else tryPollAlert(ev.alertId, force = true)
                    is CampsiteEvent.UserViewedAlert -> tryPollAlert(ev.alertId, force = false)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun pollAllActive() {
        val active = listActiveAlerts()
        log.info("AvailabilityManager: user-triggered poll-all fired for {} active alert(s)", active.size)
        bus.publish(CampsiteEvent.PollStart)
        for (alert in active) tryPollAlert(alert.id, force = true)
        bus.publish(CampsiteEvent.PollDone(alertId = null, success = true, endedAt = Instant.now().toString()))
    }

    /**
     * Acquire the per-alert mutex (tryLock) and run the poll. When [force]
     * is false, also enforce the debounce window — recently-polled alerts
     * are skipped silently.
     */
    private suspend fun tryPollAlert(
        alertId: Long,
        force: Boolean,
    ) {
        val mutex = perAlertMutex.computeIfAbsent(alertId) { Mutex() }
        if (!mutex.tryLock()) {
            log.debug("AvailabilityManager: alert {} already polling — skipping", alertId)
            return
        }
        try {
            if (!force) {
                val last = lastCheckedAt[alertId]
                if (last != null && Duration.between(last, Instant.now()) < debounce) {
                    log.debug("AvailabilityManager: alert {} polled recently — debouncing", alertId)
                    return
                }
            }
            val alert =
                getAlert(alertId) ?: run {
                    log.debug("AvailabilityManager: alert {} no longer exists, skipping", alertId)
                    return
                }
            if (alert.status != "active") {
                log.debug("AvailabilityManager: alert {} status='{}', skipping", alertId, alert.status)
                return
            }
            runOne(alert)
        } finally {
            lastCheckedAt[alertId] = Instant.now()
            mutex.unlock()
        }
    }

    private suspend fun runOne(alert: Alert) {
        bus.publish(CampsiteEvent.PollStart)
        val result = checkAlertNow(alert)
        if (result.newMatchEnvelopes.isNotEmpty()) {
            log.info(
                "AvailabilityManager: MATCH alert {} \"{}\" — {} site(s)",
                alert.id,
                alert.campgroundName,
                result.newMatchEnvelopes.size,
            )
            for (json in result.newMatchEnvelopes) bus.publish(CampsiteEvent.MatchFound(matchJson = json))
        }
        if (!result.success) {
            log.error("AvailabilityManager: alert {} \"{}\" failed: {}", alert.id, alert.campgroundName, result.error)
        }
        bus.publish(
            CampsiteEvent.PollDone(alertId = alert.id, success = result.success, endedAt = Instant.now().toString()),
        )
    }

    companion object {
        /**
         * Production wiring: builds an [AvailabilityManager] backed by the
         * real repos and [AvailabilityClient]. The check body mirrors the
         * legacy [ca.floo.campsite.recgov.booker.poller.Poller].
         */
        fun production(
            alerts: AlertRepo,
            matches: MatchRepo,
            client: AvailabilityClient,
            bus: EventBus,
            scope: CoroutineScope,
            slack: SlackNotifier? = null,
            debounce: Duration = Duration.ofSeconds(30),
        ): AvailabilityManager =
            AvailabilityManager(
                getAlert = alerts::get,
                listActiveAlerts = alerts::listActive,
                checkAlertNow = { alert ->
                    runCatching {
                        val newMatches = runProductionCheck(alert, alerts, matches, client)
                        if (newMatches.isNotEmpty()) {
                            slack?.notifyBatch(alert, newMatches)
                            newMatches.forEach { matches.markNotified(it.id) }
                            if (alert.stopAfterMatch) alerts.patch(alert.id, mapOf("status" to "done"))
                            alerts.markLastMatch(alert.id)
                        }
                        CheckResult(newMatches.map { matchEnvelopeOf(it) }, success = true)
                    }.getOrElse { CheckResult(emptyList(), success = false, error = it.message) }
                },
                bus = bus,
                scope = scope,
                debounce = debounce,
            )

        private suspend fun runProductionCheck(
            alert: Alert,
            alerts: AlertRepo,
            matches: MatchRepo,
            client: AvailabilityClient,
        ): List<Match> {
            val months = Matcher.monthsInRange(alert.startDate, alert.endDate)
            val merged = mutableMapOf<String, Campsite>()
            for (month in months) {
                val campsites = client.fetchMonth(alert.campgroundId, month)
                for ((id, cs) in campsites) {
                    val existing = merged[id]
                    merged[id] = existing?.copy(availabilities = existing.availabilities + cs.availabilities) ?: cs
                }
            }
            val newMatches = mutableListOf<Match>()
            for ((_, cs) in merged) {
                val passes =
                    Matcher.passesCampsiteFilters(
                        campsiteType = cs.campsiteType,
                        site = cs.site,
                        maxNumPeople = cs.maxNumPeople,
                        equipmentTypes = cs.equipmentTypes,
                        alertCampsiteTypes = alert.campsiteTypes,
                        alertEquipmentTypes = alert.equipmentTypes,
                        alertSpecificSites = alert.specificSites,
                        alertMaxPeople = alert.maxPeople,
                    )
                if (!passes) continue
                val windows = Matcher.findConsecutiveWindows(cs.availabilities, alert.startDate, alert.endDate, alert.minNights)
                for (dates in windows) {
                    val id =
                        matches.create(
                            MatchRepo.CreateInput(
                                alertId = alert.id,
                                campgroundId = alert.campgroundId,
                                campsiteId = cs.id,
                                site = cs.site,
                                loop = cs.loop,
                                campsiteType = cs.campsiteType,
                                availableDates = dates,
                                firstDate = dates.first(),
                                nights = dates.size,
                            ),
                        )
                    if (id != null) matches.get(id)?.let { newMatches += it }
                }
            }
            alerts.markChecked(alert.id)
            return newMatches
        }

        private fun matchEnvelopeOf(m: Match): String =
            buildJsonObject {
                put("id", m.id)
                put("alertId", m.alertId)
                put("campgroundId", m.campgroundId)
                put("campsiteId", m.campsiteId)
                put("site", m.campsiteSite ?: "")
                put("loop", m.campsiteLoop ?: "")
                put("campsiteType", m.campsiteType ?: "")
                put("firstDate", m.firstDate)
                put("nights", m.nights)
                put("availableDates", JsonArray(m.availableDates.map { JsonPrimitive(it) }))
                put("foundAt", m.foundAt)
                put("campgroundName", m.campgroundName ?: "")
            }.toString()
    }
}
