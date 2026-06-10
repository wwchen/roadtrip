package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.notifier.SlackNotifier
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MASK = "••••••••"

fun Route.settingsRoutes(
    settings: SettingsRepo,
    slack: SlackNotifier,
) {
    get("/api/campsite/settings", {
        tags = listOf("campsite-settings")
        summary = "All campsite settings (masked); includes recgov_token expiry info"
    }) {
        val all = settings.all()
        val token = all["recgov_token"].orEmpty()
        val info = RecgovAuth.tokenInfo(token)
        val masked =
            buildJsonObject {
                for ((k, v) in all) {
                    when (k) {
                        "slack_token", "recgov_cookies", "recgov_token", "recgov_refresh_creds" ->
                            if (v.isNotEmpty()) put(k, MASK) else put(k, "")
                        else -> put(k, v)
                    }
                }
                if (token.isNotEmpty()) {
                    info.expires?.let { put("recgov_token_expires", it.toString()) }
                    put("recgov_token_expired", info.expired)
                }
            }
        call.respondText(masked.toString())
    }

    post("/api/campsite/settings", {
        tags = listOf("campsite-settings")
        summary = "Upsert one or more settings keys; preserves masked values when sent"
    }) {
        val body = parseJson(call.receiveText())
        val allowed = setOf("poll_interval", "slack_token", "slack_channel", "slack_enabled", "ridb_api_key")
        val updates = mutableMapOf<String, String>()
        for (key in allowed) {
            val v = body.string(key) ?: continue
            // Skip masked sentinel — UI sends '••••••••' to mean "leave unchanged".
            if (v == MASK) continue
            updates[key] = v
        }
        if (updates.isNotEmpty()) settings.setMany(updates)
        call.respondText("""{"ok":true}""")
    }

    post("/api/campsite/settings/test-slack", {
        tags = listOf("campsite-settings")
        summary = "Send a test message to the configured Slack webhook"
    }) {
        // Accepts optional {slack_token, slack_channel} in the body so the
        // onboarding wizard can prove credentials before persisting them.
        // Empty body falls back to saved settings (existing Settings-modal flow).
        val body = parseJson(call.receiveText())
        val candidateToken = body.string("slack_token")?.takeIf { it.isNotEmpty() && it != MASK }
        val candidateChannel = body.string("slack_channel")?.takeIf { it.isNotEmpty() }
        try {
            slack.sendTest(candidateToken, candidateChannel)
            call.respondText("""{"ok":true}""")
        } catch (e: Exception) {
            call.respondText("""{"error":"${e.message}"}""", status = HttpStatusCode.InternalServerError)
        }
    }
}
