package ca.floo.roadtrip.config

import java.time.Duration

data class BookingConfig(
    val recgovAtc: RecGovAtcConfig,
) {
    companion object {
        fun fromConfig(config: ConfigSection): BookingConfig =
            BookingConfig(
                recgovAtc = RecGovAtcConfig.fromConfig(config.section("recgov-atc")),
            )
    }
}

data class RecGovAtcConfig(
    val companionBaseUrl: String?,
    val companionTimeout: Duration,
) {
    init {
        require(companionTimeout.isPositive()) { "recgov ATC companionTimeout must be positive" }
    }

    val companionEnabled: Boolean get() = companionBaseUrl != null

    companion object {
        private val DEFAULT_COMPANION_TIMEOUT: Duration = Duration.ofSeconds(180)

        fun fromConfig(config: ConfigSection): RecGovAtcConfig =
            RecGovAtcConfig(
                companionBaseUrl = config.value("companion-base-url")?.trimEnd('/'),
                companionTimeout = config.duration("companion-timeout", DEFAULT_COMPANION_TIMEOUT),
            )
    }
}

private fun Duration.isPositive(): Boolean = !isZero && !isNegative
