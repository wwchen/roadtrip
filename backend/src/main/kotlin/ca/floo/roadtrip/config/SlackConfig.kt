package ca.floo.roadtrip.config

/**
 * Slack alerting config: a bot token, the default channel watch alerts post to,
 * and (optionally) the signing secret for verifying inbound interactivity
 * requests. Token + channel come from the environment ([TOKEN_ENV] /
 * [CHANNEL_ENV]); the signing secret ([SIGNING_SECRET_ENV]) is only needed if
 * the Slack app is configured with an interactivity Request URL — outgoing
 * notifications work without it.
 *
 * [fromEnv] returns null when token or channel is absent/blank — a first-class
 * "Slack disabled" state, not an error. With Slack disabled the poller runs
 * identically; the alert path simply no-ops (see [ca.floo.roadtrip.service.availability.WatchAlertDispatcher]).
 * A missing [signingSecret] leaves outbound sends working but disables the
 * interactivity endpoint (it rejects every request as unverifiable).
 */
data class SlackConfig(
    val botToken: String,
    val defaultChannel: String,
    val signingSecret: String? = null,
) {
    companion object {
        const val TOKEN_ENV = "SLACK_BOT_TOKEN"
        const val CHANNEL_ENV = "SLACK_ALERT_CHANNEL"
        const val SIGNING_SECRET_ENV = "SLACK_SIGNING_SECRET"

        fun fromEnv(env: Map<String, String> = System.getenv()): SlackConfig? {
            val token = env[TOKEN_ENV]?.trim().orEmpty()
            val channel = env[CHANNEL_ENV]?.trim().orEmpty()
            if (token.isEmpty() || channel.isEmpty()) return null
            val signingSecret = env[SIGNING_SECRET_ENV]?.trim()?.takeIf { it.isNotEmpty() }
            return SlackConfig(botToken = token, defaultChannel = channel, signingSecret = signingSecret)
        }
    }
}
