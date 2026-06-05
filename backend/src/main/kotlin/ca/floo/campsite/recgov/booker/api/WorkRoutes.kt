package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.MatchRepo
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Companion-facing planner endpoint. The companion calls this on every
 * wakeup signal (match/result/lease_expired SSE event, or the safety-net
 * 30s interval) to ask "is there anything I should ATC right now?"
 *
 * The backend's [MatchRepo.nextWorkItem] is the single place that decides.
 * It encodes every orchestration rule:
 *
 *   - one ATC at a time per alert (in-flight check)
 *   - only auto_cart alerts
 *   - only active alerts
 *   - oldest match first
 *
 * Returns `{"match": {...}}` when there's work, `{"match": null}` otherwise.
 *
 * Read-only — the companion must still call /claim to atomically lock the
 * match before running ATC. Two companions hitting this endpoint at the same
 * time can both see the same match here; only one will win the claim.
 */
fun Route.workRoutes(matches: MatchRepo) {
    get("/api/campsite/work/next") {
        val pick = matches.nextWorkItem()
        if (pick == null) {
            call.respondText("""{"match":null}""")
        } else {
            call.respondText("""{"match":${matchEnvelope(pick)}}""")
        }
    }
}
