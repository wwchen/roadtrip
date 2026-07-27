package ca.floo.roadtrip.config

data class CampflareConfig(
    val apiKey: String?,
    val apiBaseUrl: String,
) {
    companion object {
        private const val DEFAULT_API_BASE_URL = "https://api.campflare.com/v2"

        fun fromConfig(config: ConfigSection): CampflareConfig =
            CampflareConfig(
                apiKey = config.value("api-key"),
                apiBaseUrl = config.valueOrDefault("api-base-url", DEFAULT_API_BASE_URL),
            )
    }
}
