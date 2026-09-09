package ca.floo.roadtrip.config

import ca.floo.roadtrip.model.domain.provider.BookingProvider

class ReadPathProviderConfig(
    val enabledDataProviders: Set<String>,
    val enabledAvailabilityProviders: Set<String>,
) {
    fun isDataProviderEnabled(provider: String): Boolean = enabledDataProviders.contains(provider)

    fun isAvailabilityProviderEnabled(provider: String): Boolean = enabledAvailabilityProviders.contains(provider.trim().lowercase())

    companion object {
        private const val ENABLED_DATA_PROVIDERS_KEY = "enabled-data-providers"
        private const val ENABLED_AVAILABILITY_PROVIDERS_KEY = "enabled-availability-providers"
        private val availabilityProviderIds: Set<String> = BookingProvider.entries.map { it.id }.toSet()

        fun fromConfig(config: ConfigSection): ReadPathProviderConfig =
            ReadPathProviderConfig(
                enabledDataProviders = config.csvSet(ENABLED_DATA_PROVIDERS_KEY),
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
