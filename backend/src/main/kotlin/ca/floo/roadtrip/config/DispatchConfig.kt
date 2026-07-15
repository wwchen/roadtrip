package ca.floo.roadtrip.config

import java.time.Duration

data class DispatchConfig(
    val pendingTtl: Duration,
    val maxClaimWait: Duration,
    val minClaimWait: Duration,
    val defaultLease: Duration,
    val minLease: Duration,
    val maxLease: Duration,
    val companionToken: String,
    val testEndpointEnabled: Boolean,
) {
    init {
        require(pendingTtl.isPositive()) { "dispatch pendingTtl must be positive" }
        require(maxClaimWait.isPositive()) { "dispatch maxClaimWait must be positive" }
        require(minClaimWait.isPositive()) { "dispatch minClaimWait must be positive" }
        require(defaultLease.isPositive()) { "dispatch defaultLease must be positive" }
        require(minLease.isPositive()) { "dispatch minLease must be positive" }
        require(maxLease.isPositive()) { "dispatch maxLease must be positive" }
        require(companionToken.isNotBlank()) { "dispatch companionToken must be non-blank" }
        require(!minClaimWait.isGreaterThan(maxClaimWait)) { "dispatch minClaimWait must be <= maxClaimWait" }
        require(!minLease.isGreaterThan(defaultLease)) { "dispatch minLease must be <= defaultLease" }
        require(!defaultLease.isGreaterThan(maxLease)) { "dispatch defaultLease must be <= maxLease" }
    }

    companion object {
        fun fromConfig(config: ConfigSection): DispatchConfig =
            DispatchConfig(
                pendingTtl = config.requiredDuration("pending-ttl"),
                maxClaimWait = config.requiredDuration("max-claim-wait"),
                minClaimWait = config.requiredDuration("min-claim-wait"),
                defaultLease = config.requiredDuration("default-lease"),
                minLease = config.requiredDuration("min-lease"),
                maxLease = config.requiredDuration("max-lease"),
                companionToken = config.requiredValue("companion-token"),
                testEndpointEnabled = config.requiredBoolean("test-endpoint-enabled"),
            )
    }
}

private fun Duration.isPositive(): Boolean = !isZero && !isNegative

private fun Duration.isGreaterThan(other: Duration): Boolean = compareTo(other) > 0

private fun ConfigSection.requiredValue(name: String): String = value(name) ?: throw IllegalArgumentException("${key(name)} is required")

private fun ConfigSection.requiredBoolean(name: String): Boolean =
    when (val raw = requiredValue(name).lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("${key(name)} must be true or false")
    }
