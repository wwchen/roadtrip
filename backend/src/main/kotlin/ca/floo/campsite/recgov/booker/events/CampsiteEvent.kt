package ca.floo.campsite.recgov.booker.events

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Typed event hierarchy for the campsite subsystem. Subscribers (TokenManager,
 * AvailabilityManager, StatusMonitor, scheduler-driven sweeps) match on these
 * types; routes and the scheduler publish them.
 *
 * Two channels share this type:
 *   - SSE wire: events whose `sseType` is non-null are emitted to the
 *     /api/campsite/events stream. The wire format keeps the historical
 *     names ("match", "claimed", "tick", ...) so the frontend doesn't have
 *     to change.
 *   - Internal bus: everything is delivered to in-process Flow subscribers
 *     for backend wiring.
 */
sealed interface CampsiteEvent {
    /** SSE event name; null means this event is internal-only. */
    fun sseType(): String? = null

    /** SSE data payload as a JSON string. Defaults to "{}" for events that don't carry a wire payload. */
    fun sseData(): String = "{}"

    // ----- Scheduler-fired -----
    data class PollDue(
        val alertId: Long,
    ) : CampsiteEvent

    data object TokenRefreshDue : CampsiteEvent

    data object LeaseSweepDue : CampsiteEvent

    data object CompanionSweepDue : CampsiteEvent

    data object LivenessTick : CampsiteEvent {
        override fun sseType() = "tick"

        override fun sseData() = """{"at":"${java.time.Instant.now()}"}"""
    }

    // ----- User-initiated -----
    data class UserPolledNow(
        val alertId: Long?,
    ) : CampsiteEvent

    data class UserViewedAlert(
        val alertId: Long,
    ) : CampsiteEvent

    data object UserRefreshToken : CampsiteEvent

    /**
     * Internal-only signal: "the set of pickable matches for this alert may
     * have changed; companion should re-check via /api/campsite/work/next."
     * No SSE projection — this is a backend → companion hint that piggybacks
     * on the existing match/result/lease_expired wire events for wakeup.
     *
     * Published when the set of in-flight ATCs changes (a result comes in,
     * a lease sweep releases a stuck claim).
     */
    data class WorkMaybeAvailable(
        val alertId: Long,
    ) : CampsiteEvent

    // ----- Outcomes (most go on the wire for the frontend / companion) -----
    data class MatchFound(
        val matchJson: String,
    ) : CampsiteEvent {
        override fun sseType() = "match"

        override fun sseData() = matchJson
    }

    data object PollStart : CampsiteEvent {
        override fun sseType() = "poll_start"
    }

    data class PollDone(
        val alertId: Long?,
        val success: Boolean,
        val endedAt: String,
    ) : CampsiteEvent {
        override fun sseType() = "poll_done"

        override fun sseData() =
            buildJsonObject {
                if (alertId != null) put("alertId", alertId) else put("alertId", JsonPrimitive(null as String?))
                put("success", success)
                put("endedAt", endedAt)
            }.toString()
    }

    data class Claimed(
        val matchId: Long,
        val companionId: String,
        val leaseExpires: String,
    ) : CampsiteEvent {
        override fun sseType() = "claimed"

        override fun sseData() =
            buildJsonObject {
                put("id", matchId)
                put("companionId", companionId)
                put("leaseExpires", leaseExpires)
            }.toString()
    }

    data class Result(
        val matchId: Long,
        val cartAdded: Boolean,
        val companionId: String,
    ) : CampsiteEvent {
        override fun sseType() = "result"

        override fun sseData() =
            buildJsonObject {
                put("id", matchId)
                put("cartAdded", cartAdded)
                put("companionId", companionId)
            }.toString()
    }

    data class LeaseExpired(
        val matchId: Long,
    ) : CampsiteEvent {
        override fun sseType() = "lease_expired"

        override fun sseData() = """{"id":$matchId,"reason":"lease_expired"}"""
    }

    data class CompanionOffline(
        val companionId: String,
        val lastSeen: String,
    ) : CampsiteEvent {
        override fun sseType() = "companion_offline"

        override fun sseData() = """{"companionId":"$companionId","lastSeen":"$lastSeen"}"""
    }

    data class CompanionOnline(
        val companionId: String,
    ) : CampsiteEvent {
        override fun sseType() = "companion_online"

        override fun sseData() = """{"companionId":"$companionId"}"""
    }

    data class TokenRefreshed(
        val expires: String,
    ) : CampsiteEvent {
        override fun sseType() = "token_refreshed"

        override fun sseData() = """{"expires":"$expires"}"""
    }

    data class TokenRefreshFailed(
        val reason: String,
    ) : CampsiteEvent {
        override fun sseType() = "token_refresh_failed"

        override fun sseData() =
            buildJsonObject {
                put("reason", reason)
            }.toString()
    }

    data class RecgovDegraded(
        val reason: String,
    ) : CampsiteEvent {
        override fun sseType() = "recgov_degraded"

        override fun sseData() =
            buildJsonObject {
                put("reason", reason)
            }.toString()
    }

    data class RecgovRecovered(
        val checkedAt: String,
    ) : CampsiteEvent {
        override fun sseType() = "recgov_recovered"

        override fun sseData() = """{"checkedAt":"$checkedAt"}"""
    }
}
