package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.AlertRepo
import ca.floo.campsite.recgov.booker.db.MatchRepo
import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.EventBus
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import org.slf4j.LoggerFactory
import java.time.Duration

private val companionWorkRoutesLog = LoggerFactory.getLogger("CompanionWorkRoutes")

fun Route.companionWorkRoutes(
    alerts: AlertRepo,
    matches: MatchRepo,
    bus: EventBus,
    leaseDuration: Duration,
) {
    get("/api/campsite/companion/work/next", {
        tags = listOf("campsite-companion")
        summary = "Companion planner: returns the next match to ATC, or {match:null}"
    }) {
        val pick = matches.nextWorkItem()
        call.respondJson(WorkNextDto(match = pick?.let(::matchEnvelopeDto)))
    }

    post("/api/campsite/companion/matches/{id}/claim", {
        tags = listOf("campsite-companion")
        summary = "Atomically claim a match for companion ATC work"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondJson(ErrorDto("bad id"), status = HttpStatusCode.BadRequest)
        val body = call.receiveCampsiteJson<CompanionClaimRequestDto>()
        val companion =
            body.companionId
                ?: return@post call.respondJson(ErrorDto("missing companion_id"), status = HttpStatusCode.BadRequest)
        val claimed =
            matches.claim(id, companion, leaseDuration)
                ?: return@post call.respondJson(
                    ConflictDto(reason = "already_claimed_or_done"),
                    status = HttpStatusCode.Conflict,
                )
        bus.publish(
            CampsiteEvent.Claimed(
                matchId = claimed.id,
                companionId = claimed.claimedBy ?: "",
                leaseExpires = claimed.leaseExpires ?: "",
            ),
        )
        call.respondJson(ClaimDto(leaseExpires = claimed.leaseExpires ?: ""))
    }

    post("/api/campsite/companion/matches/{id}/result", {
        tags = listOf("campsite-companion")
        summary = "Companion reports the outcome of an ATC attempt"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondJson(ErrorDto("bad id"), status = HttpStatusCode.BadRequest)
        val body = call.receiveCampsiteJson<CompanionResultRequestDto>()
        val cartAdded =
            body.cartAdded
                ?: return@post call.respondJson(ErrorDto("missing cart_added"), status = HttpStatusCode.BadRequest)
        val updated =
            matches.result(id, cartAdded)
                ?: return@post call.respondJson(
                    ConflictDto(reason = "not_claimed"),
                    status = HttpStatusCode.Conflict,
                )
        bus.publish(
            CampsiteEvent.Result(
                matchId = updated.id,
                cartAdded = updated.cartAdded ?: false,
                companionId = updated.claimedBy ?: "",
            ),
        )

        val alert = alerts.get(updated.alertId)
        if (cartAdded && alert?.stopAfterMatch == true && alert.status == "active") {
            alerts.patch(alert.id, mapOf("status" to "done"))
            companionWorkRoutesLog.info("Alert {} marked done after successful ATC of match {}", alert.id, id)
        }
        bus.publish(CampsiteEvent.WorkMaybeAvailable(alertId = updated.alertId))

        call.respondJson(OkDto())
    }
}
