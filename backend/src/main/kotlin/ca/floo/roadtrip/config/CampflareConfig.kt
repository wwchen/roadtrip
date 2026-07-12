package ca.floo.roadtrip.config

data class CampflareConfig(
    val apiKey: String?,
    val apiBaseUrl: String,
) {
    companion object {
        private const val DEFAULT_API_BASE_URL = "https://api.campflare.com/v2"

        fun fromProperties(properties: Map<String, String>): CampflareConfig =
            fromConfig(ConfigSection(properties).section("roadtrip.campflare"))

        fun fromConfig(config: ConfigSection): CampflareConfig =
            CampflareConfig(
                apiKey =
                    config.value("api-key")
                        ?: config.value("token"),
                apiBaseUrl = config.valueOrDefault("api-base-url", DEFAULT_API_BASE_URL),
            )
    }
}
