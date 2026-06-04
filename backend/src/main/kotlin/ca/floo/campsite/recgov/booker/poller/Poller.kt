package ca.floo.campsite.recgov.booker.poller

import ca.floo.campsite.recgov.booker.db.AlertRepo
import ca.floo.campsite.recgov.booker.db.MatchRepo
import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.domain.Alert
import ca.floo.campsite.recgov.booker.domain.Match
import ca.floo.campsite.recgov.booker.events.EventBus
import ca.floo.campsite.recgov.booker.matching.Matcher
import ca.floo.campsite.recgov.booker.notifier.SlackNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * Coroutine-driven scheduled poller. Runs every `pollIntervalSec` seconds; reuses
 * a single AvailabilityClient (which holds the 1.5s throttle). The loop guards
 * itself with a mutex so overlapping ticks are skipped, matching legacy behavior.
 */
class Poller(
    private val alerts: AlertRepo,
    private val matches: MatchRepo,
    private val settings: SettingsRepo,
    private val bus: EventBus,
    private val client: AvailabilityClient = AvailabilityClient(),
    private val slack: SlackNotifier? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private val log = LoggerFactory.getLogger(Poller::class.java)
    private val pollMutex = Mutex()
    @Volatile private var lastPollEndAt: OffsetDateTime? = null
    private var loopJob: Job? = null

    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            // Initial 2s warmup matches legacy server's setTimeout(runPoll, 2000).
            delay(2_000)
            while (true) {
                val intervalSec = (settings.get("poll_interval")?.toLongOrNull() ?: 60L).coerceAtLeast(5)
                runOnce()
                delay(intervalSec.seconds)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    suspend fun triggerNow() {
        scope.launch { runOnce() }
    }

    fun lastPollEndAt(): OffsetDateTime? = lastPollEndAt

    private suspend fun runOnce() {
        if (!pollMutex.tryLock()) {
            log.info("Poll cycle still running — skipping")
            return
        }
        try {
            bus.publish("poll_start", "{}")
            val active = alerts.listActive()
            if (active.isEmpty()) {
                lastPollEndAt = OffsetDateTime.now()
                bus.publish("poll_done", """{"nextAt":null}""")
                return
            }
            log.info("Polling {} active alert(s)...", active.size)
            for (alert in active) {
                try {
                    val newMatches = checkAlert(alert)
                    if (newMatches.isNotEmpty()) {
                        log.info("MATCH: Alert {} \"{}\" — {} site(s)", alert.id, alert.campgroundName, newMatches.size)
                        for (m in newMatches) {
                            bus.publish("match", matchEnvelope(m))
                        }
                        slack?.notifyBatch(alert, newMatches)
                        newMatches.forEach { matches.markNotified(it.id) }
                        if (alert.stopAfterMatch) {
                            alerts.patch(alert.id, mapOf("status" to "done"))
                            log.info("Alert {} marked done", alert.id)
                        }
                        alerts.markLastMatch(alert.id)
                    }
                } catch (e: Exception) {
                    log.error("Error checking alert {} \"{}\": {}", alert.id, alert.campgroundName, e.message)
                }
            }
        } finally {
            lastPollEndAt = OffsetDateTime.now()
            pollMutex.unlock()
            bus.publish("poll_done", """{"endedAt":"${lastPollEndAt}"}""")
        }
    }

    private suspend fun checkAlert(alert: Alert): List<Match> {
        val months = Matcher.monthsInRange(alert.startDate, alert.endDate)
        val merged = mutableMapOf<String, Campsite>()
        for (month in months) {
            try {
                val campsites = client.fetchMonth(alert.campgroundId, month)
                for ((id, cs) in campsites) {
                    val existing = merged[id]
                    if (existing == null) {
                        merged[id] = cs
                    } else {
                        merged[id] = existing.copy(availabilities = existing.availabilities + cs.availabilities)
                    }
                }
            } catch (e: Exception) {
                log.error("Failed to fetch availability | Alert {} \"{}\" | {}", alert.id, alert.campgroundName, e.message)
            }
        }
        val newMatches = mutableListOf<Match>()
        for ((_, cs) in merged) {
            val passes = Matcher.passesCampsiteFilters(
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
                val id = matches.create(MatchRepo.CreateInput(
                    alertId = alert.id,
                    campgroundId = alert.campgroundId,
                    campsiteId = cs.id,
                    site = cs.site,
                    loop = cs.loop,
                    campsiteType = cs.campsiteType,
                    availableDates = dates,
                    firstDate = dates.first(),
                    nights = dates.size,
                ))
                if (id != null) {
                    matches.get(id)?.let { newMatches += it }
                }
            }
        }
        alerts.markChecked(alert.id)
        return newMatches
    }

    private fun matchEnvelope(m: Match): String = buildJsonObject {
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
