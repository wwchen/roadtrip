package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.notifier.SlackNotifier
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
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
        call.respondJsonElement(masked)
    }

    post("/api/campsite/settings", {
        tags = listOf("campsite-settings")
        summary = "Upsert one or more settings keys; preserves masked values when sent"
    }) {
        val body = call.receiveCampsiteJson<SettingsUpdateRequestDto>()
        val updates = mutableMapOf<String, String>()
        updates.putIfUnmasked("poll_interval", body.pollInterval)
        updates.putIfUnmasked("slack_token", body.slackToken)
        updates.putIfUnmasked("slack_channel", body.slackChannel)
        updates.putIfUnmasked("slack_enabled", body.slackEnabled)
        updates.putIfUnmasked("ridb_api_key", body.ridbApiKey)
        if (updates.isNotEmpty()) settings.setMany(updates)
        call.respondJson(OkDto())
    }

    post("/api/campsite/settings/test-slack", {
        tags = listOf("campsite-settings")
        summary = "Send a test message to the configured Slack webhook"
    }) {
        // Accepts optional {slack_token, slack_channel} in the body so the
        // onboarding wizard can prove credentials before persisting them.
        // Empty body falls back to saved settings (existing Settings-modal flow).
        val body = call.receiveCampsiteJson<SettingsTestSlackRequestDto>()
        val candidateToken = body.slackToken?.takeIf { it.isNotEmpty() && it != MASK }
        val candidateChannel = body.slackChannel?.takeIf { it.isNotEmpty() }
        try {
            slack.sendTest(candidateToken, candidateChannel)
            call.respondJson(OkDto())
        } catch (e: Exception) {
            call.respondJson(ErrorDto(e.message ?: "Slack test failed"), status = HttpStatusCode.InternalServerError)
        }
    }
}

private fun MutableMap<String, String>.putIfUnmasked(
    key: String,
    value: String?,
) {
    // Skip masked sentinel — UI sends it to mean "leave unchanged".
    if (value != null && value != MASK) this[key] = value
}
