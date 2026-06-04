package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.notifier.SlackNotifier
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Route.settingsRoutes(settings: SettingsRepo, slack: SlackNotifier) {
    get("/api/campsite/settings") {
        val all = settings.all()
        val masked = buildJsonObject {
            for ((k, v) in all) {
                when (k) {
                    "slack_token", "recgov_cookies", "recgov_token" ->
                        if (v.isNotEmpty()) put(k, "••••••••") else put(k, "")
                    else -> put(k, v)
                }
            }
        }
        call.respondText(masked.toString())
    }

    post("/api/campsite/settings") {
        val body = parseJson(call.receiveText())
        val allowed = setOf("poll_interval", "slack_token", "slack_channel", "ridb_api_key")
        val updates = mutableMapOf<String, String>()
        for (key in allowed) {
            val v = body.string(key) ?: continue
            // Skip masked sentinel — UI sends '••••••••' to mean "leave unchanged".
            if (v == "••••••••") continue
            updates[key] = v
        }
        if (updates.isNotEmpty()) settings.setMany(updates)
        call.respondText("""{"ok":true}""")
    }

    post("/api/campsite/settings/test-slack") {
        try {
            slack.sendTest()
            call.respondText("""{"ok":true}""")
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, """{"error":"${e.message}"}""")
        }
    }
}
