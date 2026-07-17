package ca.floo.roadtrip.config

private const val RESEND_API_KEY_KEY = "resend-api-key"
private const val FROM_KEY = "from"

/**
 * Email alerting config for availability watches. Resend is the transport, but
 * callers only see an email notification service. Missing API key or sender
 * disables email delivery. Email recipients live on each notification target.
 */
data class EmailConfig(
    val resendApiKey: String,
    val from: String,
) {
    companion object {
        fun fromConfig(config: ConfigSection): EmailConfig? {
            val apiKey = config.value(RESEND_API_KEY_KEY).orEmpty()
            val from = config.value(FROM_KEY).orEmpty()
            if (apiKey.isEmpty() || from.isEmpty()) return null
            return EmailConfig(
                resendApiKey = apiKey,
                from = from,
            )
        }
    }
}
