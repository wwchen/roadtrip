package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.events.CompanionRegistry
import ca.floo.campsite.recgov.booker.events.EventBus
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val jsonStringRe = Regex("\"([^\"]*)\"\\s*:\\s*\"([^\"]*)\"")

private fun parseField(
    body: String,
    key: String,
): String? =
    jsonStringRe
        .findAll(body)
        .firstOrNull { it.groupValues[1] == key }
        ?.groupValues
        ?.get(2)

fun Route.companionRoutes(
    companions: CompanionRegistry,
    bus: EventBus,
    settings: SettingsRepo,
) {
    post("/api/campsite/companion/heartbeat") {
        val body = call.receiveText()
        val id =
            parseField(body, "companion_id")
                ?: return@post call.respond(HttpStatusCode.BadRequest, "missing companion_id")
        val cameBack = companions.heartbeat(id)
        if (cameBack) {
            bus.publish("companion_online", """{"companionId":"$id"}""")
        }
        call.respondText("""{"ok":true}""")
    }

    get("/api/campsite/companion/status") {
        val list =
            companions.status().joinToString(",") {
                """{"id":"${it.id}","lastSeen":"${it.lastSeen}","offline":${it.offline}}"""
            }
        call.respondText("""{"companions":[$list]}""")
    }

    // Returns the rec.gov auth bits (unmasked) so the companion can drive
    // Playwright with the same token the UI is showing as ✓ valid. The
    // companion shares a host with the backend in dev (Tilt) and prod
    // (cloudflared tunnel), so this endpoint MUST stay localhost-only —
    // restrict via reverse proxy / firewall in any future remote-companion
    // setup. Safe to call when nothing is saved: returns empty strings.
    get("/api/campsite/companion/recgov") {
        val token = settings.get("recgov_token").orEmpty()
        val creds = settings.get("recgov_refresh_creds").orEmpty()
        val info = RecgovAuth.tokenInfo(token)
        val resp =
            buildJsonObject {
                put("recgov_token", token)
                put("recgov_refresh_creds", creds)
                info.expires?.let { put("recgov_token_expires", it.toString()) }
                put("recgov_token_expired", info.expired)
            }
        call.respondText(resp.toString())
    }
}
