package ca.floo.campsite.recgov.booker.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

private val campsiteEventJson = Json

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

        override fun sseData() = campsiteEventJson.encodeToString(LivenessTickEventDto(at = Instant.now().toString()))
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
     * have changed; companion should re-check via /api/campsite/companion/work/next."
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
            campsiteEventJson.encodeToString(
                PollDoneEventDto(
                    alertId = alertId,
                    success = success,
                    endedAt = endedAt,
                ),
            )
    }

    data class Claimed(
        val matchId: Long,
        val companionId: String,
        val leaseExpires: String,
    ) : CampsiteEvent {
        override fun sseType() = "claimed"

        override fun sseData() =
            campsiteEventJson.encodeToString(
                ClaimedEventDto(
                    id = matchId,
                    companionId = companionId,
                    leaseExpires = leaseExpires,
                ),
            )
    }

    data class Result(
        val matchId: Long,
        val cartAdded: Boolean,
        val companionId: String,
    ) : CampsiteEvent {
        override fun sseType() = "result"

        override fun sseData() =
            campsiteEventJson.encodeToString(
                ResultEventDto(
                    id = matchId,
                    cartAdded = cartAdded,
                    companionId = companionId,
                ),
            )
    }

    data class LeaseExpired(
        val matchId: Long,
    ) : CampsiteEvent {
        override fun sseType() = "lease_expired"

        override fun sseData() = campsiteEventJson.encodeToString(LeaseExpiredEventDto(id = matchId, reason = "lease_expired"))
    }

    data class CompanionOffline(
        val companionId: String,
        val lastSeen: String,
    ) : CampsiteEvent {
        override fun sseType() = "companion_offline"

        override fun sseData() =
            campsiteEventJson.encodeToString(
                CompanionOfflineEventDto(
                    companionId = companionId,
                    lastSeen = lastSeen,
                ),
            )
    }

    data class CompanionOnline(
        val companionId: String,
    ) : CampsiteEvent {
        override fun sseType() = "companion_online"

        override fun sseData() = campsiteEventJson.encodeToString(CompanionOnlineEventDto(companionId = companionId))
    }

    data class TokenRefreshed(
        val expires: String,
    ) : CampsiteEvent {
        override fun sseType() = "token_refreshed"

        override fun sseData() = campsiteEventJson.encodeToString(TokenRefreshedEventDto(expires = expires))
    }

    data class TokenRefreshFailed(
        val reason: String,
    ) : CampsiteEvent {
        override fun sseType() = "token_refresh_failed"

        override fun sseData() = campsiteEventJson.encodeToString(TokenRefreshFailedEventDto(reason = reason))
    }

    data class RecgovDegraded(
        val reason: String,
    ) : CampsiteEvent {
        override fun sseType() = "recgov_degraded"

        override fun sseData() = campsiteEventJson.encodeToString(RecgovDegradedEventDto(reason = reason))
    }

    data class RecgovRecovered(
        val checkedAt: String,
    ) : CampsiteEvent {
        override fun sseType() = "recgov_recovered"

        override fun sseData() = campsiteEventJson.encodeToString(RecgovRecoveredEventDto(checkedAt = checkedAt))
    }
}

@Serializable
private data class LivenessTickEventDto(
    val at: String,
)

@Serializable
private data class PollDoneEventDto(
    val alertId: Long?,
    val success: Boolean,
    val endedAt: String,
)

@Serializable
private data class ClaimedEventDto(
    val id: Long,
    val companionId: String,
    val leaseExpires: String,
)

@Serializable
private data class ResultEventDto(
    val id: Long,
    val cartAdded: Boolean,
    val companionId: String,
)

@Serializable
private data class LeaseExpiredEventDto(
    val id: Long,
    val reason: String,
)

@Serializable
private data class CompanionOfflineEventDto(
    val companionId: String,
    val lastSeen: String,
)

@Serializable
private data class CompanionOnlineEventDto(
    val companionId: String,
)

@Serializable
private data class TokenRefreshedEventDto(
    val expires: String,
)

@Serializable
private data class TokenRefreshFailedEventDto(
    val reason: String,
)

@Serializable
private data class RecgovDegradedEventDto(
    val reason: String,
)

@Serializable
private data class RecgovRecoveredEventDto(
    val checkedAt: String,
)
