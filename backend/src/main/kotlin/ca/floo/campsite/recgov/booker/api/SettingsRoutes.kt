package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.notifier.SlackNotifier
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

private const val MASK = "••••••••"
private val SENSITIVE_SETTING_KEYS = setOf("slack_token", "recgov_cookies", "recgov_token", "recgov_refresh_creds")

fun Route.settingsRoutes(
    settings: SettingsRepo,
    slack: SlackNotifier,
) {
    get("/api/campsite/settings", {
        tags = listOf("campsite-settings")
        summary = "All campsite settings (masked); includes recgov_token expiry info"
    }) {
        call.respondJson(maskedSettingsResponse(settings.all()))
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

private fun maskedSettingsResponse(all: Map<String, String>): Map<String, SettingsValue> {
    val token = all["recgov_token"].orEmpty()
    val info = RecgovAuth.tokenInfo(token)
    val response = linkedMapOf<String, SettingsValue>()
    for ((key, value) in all) {
        response[key] =
            if (key in SENSITIVE_SETTING_KEYS) {
                SettingsValue.Text(if (value.isNotEmpty()) MASK else "")
            } else {
                SettingsValue.Text(value)
            }
    }
    if (token.isNotEmpty()) {
        info.expires?.let { response["recgov_token_expires"] = SettingsValue.Text(it.toString()) }
        response["recgov_token_expired"] = SettingsValue.Bool(info.expired)
    }
    return response
}

@Serializable(with = SettingsValueSerializer::class)
private sealed interface SettingsValue {
    data class Text(
        val value: String,
    ) : SettingsValue

    data class Bool(
        val value: Boolean,
    ) : SettingsValue
}

private object SettingsValueSerializer : KSerializer<SettingsValue> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("SettingsValue")

    override fun serialize(
        encoder: Encoder,
        value: SettingsValue,
    ) {
        when (value) {
            is SettingsValue.Text -> encoder.encodeString(value.value)
            is SettingsValue.Bool -> encoder.encodeBoolean(value.value)
        }
    }

    override fun deserialize(decoder: Decoder): SettingsValue = throw SerializationException("SettingsValue is response-only")
}

private fun MutableMap<String, String>.putIfUnmasked(
    key: String,
    value: String?,
) {
    // Skip masked sentinel — UI sends it to mean "leave unchanged".
    if (value != null && value != MASK) this[key] = value
}
