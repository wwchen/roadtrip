package ca.floo.roadtrip.config

import java.time.Duration

data class AvailabilityConfig(
    val forcePullCooldown: Duration,
    val providerCooldown: Duration,
) {
    companion object {
        fun fromConfig(config: ConfigSection): AvailabilityConfig =
            AvailabilityConfig(
                forcePullCooldown = config.requiredDuration("force-pull-cooldown"),
                providerCooldown = config.requiredDuration("provider-cooldown"),
            )
    }
}
