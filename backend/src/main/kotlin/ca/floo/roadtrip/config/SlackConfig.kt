package ca.floo.roadtrip.config

/**
 * Slack alerting config: a bot token, the default channel watch alerts post to,
 * and (optionally) the signing secret for verifying inbound interactivity
 * requests. Property values can point at process env placeholders so secrets do
 * not live in the file.
 *
 * [fromConfig] returns null when token or channel is absent/blank — a first-class
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
        fun fromConfig(config: ConfigSection): SlackConfig? {
            val token = config.value("bot-token").orEmpty()
            val channel = config.value("default-channel").orEmpty()
            if (token.isEmpty() || channel.isEmpty()) return null
            val signingSecret = config.value("signing-secret")
            return SlackConfig(botToken = token, defaultChannel = channel, signingSecret = signingSecret)
        }
    }
}
