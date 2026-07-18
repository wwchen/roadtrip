package ca.floo.roadtrip.config

class ReadPathProviderConfig(
    val enabledDataSources: Set<String>,
    val enabledAvailabilityProviders: Set<String>,
) {
    fun isDataSourceEnabled(source: String): Boolean = enabledDataSources.contains(source)

    fun isAvailabilityProviderEnabled(provider: String): Boolean = enabledAvailabilityProviders.contains(provider.trim().lowercase())

    companion object {
        private const val ENABLED_DATA_SOURCES_KEY = "enabled-data-sources"
        private const val ENABLED_AVAILABILITY_PROVIDERS_KEY = "enabled-availability-providers"
        private val availabilityProviderIds =
            setOf(
                "aspira",
                "campflare",
                "recgov",
                "reserveamerica",
                "reservecalifornia",
            )

        fun fromConfig(config: ConfigSection): ReadPathProviderConfig =
            ReadPathProviderConfig(
                enabledDataSources = config.csvSet(ENABLED_DATA_SOURCES_KEY),
                enabledAvailabilityProviders =
                    parseAvailabilityProviderAllowList(
                        raw = config.csvSet(ENABLED_AVAILABILITY_PROVIDERS_KEY),
                        key = config.key(ENABLED_AVAILABILITY_PROVIDERS_KEY),
                    ),
            )

        private fun parseAvailabilityProviderAllowList(
            raw: Set<String>,
            key: String,
        ): Set<String> {
            val normalized = raw.map { it.lowercase() }.toSet()
            val unknown = normalized - availabilityProviderIds
            require(unknown.isEmpty()) {
                "$key contains unknown provider(s): " +
                    "${unknown.sorted()}. Expected one of: ${availabilityProviderIds.sorted()}."
            }
            return normalized
        }
    }
}
