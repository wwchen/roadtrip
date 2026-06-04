package ca.floo.campsite.recgov.booker.events

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-companion liveness from heartbeat POSTs.
 *
 * `offlineThreshold` = 90s per PLAN.md. A heartbeat every 30s gives 3
 * misses before the backend declares the companion offline and emits
 * `companion_offline`. The bus emits the event once on the FALSE→TRUE
 * transition so reconnects don't re-spam.
 */
class CompanionRegistry(
    val offlineThreshold: Duration = Duration.ofSeconds(90),
) {
    data class Entry(
        val id: String,
        var lastSeen: Instant,
        var offline: Boolean,
    )

    private val companions = ConcurrentHashMap<String, Entry>()

    fun heartbeat(
        id: String,
        now: Instant = Instant.now(),
    ): Boolean {
        // Returns true if this heartbeat brought a previously-offline companion back online.
        val entry =
            companions.compute(id) { _, prev ->
                (prev ?: Entry(id, now, offline = false)).also {
                    it.lastSeen = now
                }
            }!!
        val cameBack = entry.offline
        entry.offline = false
        return cameBack
    }

    fun status(): List<Entry> = companions.values.toList()

    /** Detects fresh offline transitions and returns them. Idempotent for already-offline entries. */
    fun sweepOffline(now: Instant = Instant.now()): List<Entry> {
        val newlyOffline = mutableListOf<Entry>()
        for (e in companions.values) {
            val silentFor = Duration.between(e.lastSeen, now)
            if (silentFor > offlineThreshold && !e.offline) {
                e.offline = true
                newlyOffline += e
            }
        }
        return newlyOffline
    }
}
