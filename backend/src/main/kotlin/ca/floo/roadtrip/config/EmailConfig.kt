package ca.floo.roadtrip.config

private const val RESEND_API_KEY_KEY = "resend-api-key"
private const val FROM_KEY = "from"
private const val DEFAULT_TO_KEY = "default-to"

/**
 * Email alerting config for availability watches. Resend is the transport, but
 * callers only see an email notification service. Missing API key or sender
 * disables email delivery; default recipients are only required for watch
 * alerts that do not pass an explicit recipient list.
 */
data class EmailConfig(
    val resendApiKey: String,
    val from: String,
    val defaultTo: List<String>,
) {
    companion object {
        fun fromConfig(config: ConfigSection): EmailConfig? {
            val apiKey = config.value(RESEND_API_KEY_KEY).orEmpty()
            val from = config.value(FROM_KEY).orEmpty()
            val defaultTo =
                config
                    .value(DEFAULT_TO_KEY)
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
            if (apiKey.isEmpty() || from.isEmpty()) return null
            return EmailConfig(
                resendApiKey = apiKey,
                from = from,
                defaultTo = defaultTo,
            )
        }
    }
}
