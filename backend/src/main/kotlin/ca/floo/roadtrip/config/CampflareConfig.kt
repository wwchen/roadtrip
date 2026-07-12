package ca.floo.roadtrip.config

data class CampflareConfig(
    val apiKey: String?,
    val apiBaseUrl: String,
) {
    companion object {
        private const val DEFAULT_API_BASE_URL = "https://api.campflare.com/v2"

        fun fromEnv(env: Map<String, String> = System.getenv()): CampflareConfig =
            CampflareConfig(
                apiKey =
                    env["CAMPFLARE_API_KEY"]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: env["CAMPFLARE_TOKEN"]
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() },
                apiBaseUrl =
                    env["CAMPFLARE_API_BASE"]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: DEFAULT_API_BASE_URL,
            )
    }
}
