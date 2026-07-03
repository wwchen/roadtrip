package ca.floo.roadtrip.config

/**
 * Slack alerting config: a bot token and the default channel watch alerts post
 * to. Both come from the environment ([TOKEN_ENV] / [CHANNEL_ENV]).
 *
 * [fromEnv] returns null when either is absent/blank — a first-class "Slack
 * disabled" state, not an error. With Slack disabled the poller runs
 * identically; the alert path simply no-ops (see [ca.floo.roadtrip.service.availability.WatchAlertDispatcher]).
 * This is the home for the token the product README documents (`xoxb-…` bot
 * token + channel), rehomed to env config from the deleted `campsite_settings`
 * table.
 */
data class SlackConfig(
    val botToken: String,
    val defaultChannel: String,
) {
    companion object {
        const val TOKEN_ENV = "SLACK_BOT_TOKEN"
        const val CHANNEL_ENV = "SLACK_ALERT_CHANNEL"

        fun fromEnv(env: Map<String, String> = System.getenv()): SlackConfig? {
            val token = env[TOKEN_ENV]?.trim().orEmpty()
            val channel = env[CHANNEL_ENV]?.trim().orEmpty()
            if (token.isEmpty() || channel.isEmpty()) return null
            return SlackConfig(botToken = token, defaultChannel = channel)
        }
    }
}
