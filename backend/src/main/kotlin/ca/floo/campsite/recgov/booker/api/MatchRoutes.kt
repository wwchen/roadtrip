package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.AlertRepo
import ca.floo.campsite.recgov.booker.db.MatchRepo
import ca.floo.campsite.recgov.booker.domain.Match
import ca.floo.campsite.recgov.booker.events.EventBus
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration

fun Route.matchRoutes(
    alerts: AlertRepo,
    matches: MatchRepo,
    bus: EventBus,
    leaseDuration: Duration,
) {
    /** Spike-only: create an alert + match in one shot so the protocol harness exercises the full DB path. */
    post("/api/campsite/spike/synth-match") {
        val body = parseJson(call.receiveText())
        val campgroundId = body.string("campgroundId") ?: "232447"
        val campsiteId = body.string("campsiteId") ?: "1"
        val startDate = body.string("startDate") ?: "2026-04-28"
        val endDate = body.string("endDate") ?: "2026-04-29"
        val alertId =
            alerts.create(
                AlertRepo.CreateInput(
                    campgroundId = campgroundId,
                    campgroundName = "spike-$campgroundId",
                    parentName = null,
                    parentId = null,
                    startDate = startDate,
                    endDate = endDate,
                    minNights = 1,
                    campsiteTypes = emptyList(),
                    equipmentTypes = emptyList(),
                    maxPeople = null,
                    specificSites = emptyList(),
                    notifySlack = false,
                    autoCart = false,
                    stopAfterMatch = false,
                    notes = "spike",
                ),
            )
        val matchId =
            matches.create(
                MatchRepo.CreateInput(
                    alertId = alertId,
                    campgroundId = campgroundId,
                    campsiteId = campsiteId,
                    site = "spike-site",
                    loop = null,
                    campsiteType = null,
                    availableDates = listOf(startDate),
                    firstDate = startDate,
                    nights = 1,
                ),
            ) ?: return@post call.respond(HttpStatusCode.Conflict, """{"error":"duplicate match"}""")
        val m = matches.get(matchId)!!
        bus.publish("match", matchEnvelope(m))
        call.respondText("""{"ok":true,"id":$matchId}""")
    }

    post("/api/campsite/matches/{id}/claim") {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "bad id")
        val body = parseJson(call.receiveText())
        val companion =
            body.string("companion_id")
                ?: return@post call.respond(HttpStatusCode.BadRequest, "missing companion_id")
        val claimed =
            matches.claim(id, companion, leaseDuration)
                ?: return@post call.respond(HttpStatusCode.Conflict, """{"ok":false,"reason":"already_claimed_or_done"}""")
        bus.publish(
            "claimed",
            buildJsonObject {
                put("id", claimed.id)
                put("companionId", claimed.claimedBy ?: "")
                put("leaseExpires", claimed.leaseExpires ?: "")
            }.toString(),
        )
        call.respondText("""{"ok":true,"leaseExpires":"${claimed.leaseExpires}"}""")
    }

    post("/api/campsite/matches/{id}/result") {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "bad id")
        val body = parseJson(call.receiveText())
        val cartAdded =
            body.bool("cart_added")
                ?: return@post call.respond(HttpStatusCode.BadRequest, "missing cart_added")
        val updated =
            matches.result(id, cartAdded)
                ?: return@post call.respond(HttpStatusCode.Conflict, """{"ok":false,"reason":"not_claimed"}""")
        bus.publish(
            "result",
            buildJsonObject {
                put("id", updated.id)
                put("cartAdded", updated.cartAdded ?: false)
                put("companionId", updated.claimedBy ?: "")
            }.toString(),
        )
        call.respondText("""{"ok":true}""")
    }

    get("/api/campsite/matches/{id}") {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "bad id")
        val m = matches.get(id) ?: return@get call.respond(HttpStatusCode.NotFound, "no such match")
        call.respondText(matchEnvelope(m))
    }

    get("/api/campsite/matches") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        val alertId = call.request.queryParameters["alert_id"]?.toLongOrNull()
        val list = matches.list(limit, alertId)
        call.respondText(buildJsonArray(list).toString())
    }

    delete("/api/campsite/matches/{id}") {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "bad id")
        matches.softDelete(id)
        call.respondText("""{"ok":true}""")
    }
}

internal fun matchEnvelope(m: Match): String =
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
        put("foundAt", m.foundAt)
        put("startDate", m.firstDate)
        put("endDate", m.firstDate)
        put("availableDates", JsonArray(m.availableDates.map { JsonPrimitive(it) }))
        put("campgroundName", m.campgroundName ?: "")
        put("notified", m.notified)
        put("claimedBy", m.claimedBy ?: "")
        put("leaseExpires", m.leaseExpires ?: "")
        put("cartAdded", m.cartAdded ?: false)
        put("resultAt", m.resultAt ?: "")
    }.toString()

internal fun buildJsonArray(matches: List<Match>): JsonArray =
    JsonArray(
        matches.map {
            buildJsonObject {
                put("id", it.id)
                put("alert_id", it.alertId)
                put("campground_id", it.campgroundId)
                put("campsite_id", it.campsiteId)
                put("campsite_site", it.campsiteSite ?: "")
                put("campsite_loop", it.campsiteLoop ?: "")
                put("campsite_type", it.campsiteType ?: "")
                put("first_date", it.firstDate)
                put("nights", it.nights)
                put("available_dates", JsonArray(it.availableDates.map { d -> JsonPrimitive(d) }))
                put("found_at", it.foundAt)
                put("notified", it.notified)
                put("cart_added", it.cartAdded ?: false)
                put("campground_name", it.campgroundName ?: "")
                put("alert_start", it.alertStart ?: "")
                put("alert_end", it.alertEnd ?: "")
            }
        },
    )

internal class JsonView(
    val obj: JsonObject,
) {
    fun string(key: String): String? = (obj[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

    fun bool(key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.content?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    fun int(key: String): Int? = (obj[key] as? JsonPrimitive)?.content?.toIntOrNull()

    fun long(key: String): Long? = (obj[key] as? JsonPrimitive)?.content?.toLongOrNull()

    fun array(key: String): JsonArray? = obj[key] as? JsonArray

    fun stringList(key: String): List<String> = (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
}

internal fun parseJson(body: String): JsonView {
    val obj = (Json.parseToJsonElement(body.ifBlank { "{}" }) as? JsonObject) ?: JsonObject(emptyMap())
    return JsonView(obj)
}
