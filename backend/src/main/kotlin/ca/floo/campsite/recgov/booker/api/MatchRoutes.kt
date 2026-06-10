package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.MatchRepo
import ca.floo.campsite.recgov.booker.poller.AvailabilityClient
import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import org.slf4j.LoggerFactory
import java.time.LocalDate

private val matchRoutesLog = LoggerFactory.getLogger("MatchRoutes")

fun Route.matchRoutes(
    matches: MatchRepo,
    availability: AvailabilityClient,
) {
    get("/api/campsite/matches/{id}", {
        tags = listOf("campsite-matches")
        summary = "Get one match by id"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondJson(ErrorDto("bad id"), status = HttpStatusCode.BadRequest)
        val m =
            matches.get(id)
                ?: return@get call.respondJson(ErrorDto("no such match"), status = HttpStatusCode.NotFound)
        call.respondJson(matchEnvelopeDto(m))
    }

    get("/api/campsite/matches", {
        tags = listOf("campsite-matches")
        summary = "List all matches (across all alerts)"
    }) {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        val alertId = call.request.queryParameters["alert_id"]?.toLongOrNull()
        val list = matches.list(limit, alertId)
        call.respondJson(matchListDtos(list))
    }

    delete("/api/campsite/matches/{id}", {
        tags = listOf("campsite-matches")
        summary = "Delete a match (drops it from history)"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respondJson(ErrorDto("bad id"), status = HttpStatusCode.BadRequest)
        matches.softDelete(id)
        call.respondJson(OkDto())
    }

    // Re-checks rec.gov for the specific campsite/date in a Match. Used by the
    // UI to colour each match card after a poll cycle. Synchronous: hits
    // recreation.gov via the shared throttled AvailabilityClient.
    get("/api/campsite/matches/{id}/availability", {
        tags = listOf("campsite-availability")
        summary = "Live availability snapshot for a match's campground (rec.gov, no cache)"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondJson(ErrorDto("bad id"), status = HttpStatusCode.BadRequest)
        val m =
            matches.get(id)
                ?: return@get call.respondJson(ErrorDto("no such match"), status = HttpStatusCode.NotFound)

        if (m.campsiteId.isBlank() || m.campsiteId == "0") {
            call.respondJson(MatchAvailabilityDto(status = "unqueryable"))
            return@get
        }

        val months = m.availableDates.map { LocalDate.parse(it).withDayOfMonth(1).toString() }.toSet()
        // rec.gov keys availabilities by full ISO timestamp ("2026-06-05T00:00:00Z");
        // m.availableDates are plain YYYY-MM-DD. Re-key on the date prefix so lookups land.
        val avail = mutableMapOf<String, String>()
        for (month in months) {
            try {
                val campsites = availability.fetchMonth(m.campgroundId, month)
                campsites[m.campsiteId]?.availabilities?.forEach { (k, v) ->
                    avail[k.substring(0, 10)] = v
                }
            } catch (e: Exception) {
                matchRoutesLog.info("availability fetch failed for match {} ({}): {}", id, month, e.message)
                call.respondJson(MatchAvailabilityDto(status = "unqueryable"))
                return@get
            }
        }
        val available = setOf("Available", "Open")
        val stillAvailable = m.availableDates.all { avail[it] in available }
        val status = if (stillAvailable) "available" else "unavailable"
        call.respondJson(MatchAvailabilityDto(status = status))
    }
}
