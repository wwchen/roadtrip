package ca.floo.roadtrip.config

import java.time.Duration

private val DEFAULT_DISPATCH_PENDING_TTL: Duration = Duration.ofSeconds(30)
private val DEFAULT_DISPATCH_MAX_CLAIM_WAIT: Duration = Duration.ofSeconds(30)
private val DEFAULT_DISPATCH_MIN_CLAIM_WAIT: Duration = Duration.ofMillis(1)
private val DEFAULT_DISPATCH_LEASE: Duration = Duration.ofSeconds(30)
private val DEFAULT_DISPATCH_MIN_LEASE: Duration = Duration.ofSeconds(1)
private val DEFAULT_DISPATCH_MAX_LEASE: Duration = Duration.ofSeconds(120)

data class DispatchConfig(
    val pendingTtl: Duration = DEFAULT_DISPATCH_PENDING_TTL,
    val maxClaimWait: Duration = DEFAULT_DISPATCH_MAX_CLAIM_WAIT,
    val minClaimWait: Duration = DEFAULT_DISPATCH_MIN_CLAIM_WAIT,
    val defaultLease: Duration = DEFAULT_DISPATCH_LEASE,
    val minLease: Duration = DEFAULT_DISPATCH_MIN_LEASE,
    val maxLease: Duration = DEFAULT_DISPATCH_MAX_LEASE,
) {
    init {
        require(pendingTtl.isPositive()) { "dispatch pendingTtl must be positive" }
        require(maxClaimWait.isPositive()) { "dispatch maxClaimWait must be positive" }
        require(minClaimWait.isPositive()) { "dispatch minClaimWait must be positive" }
        require(defaultLease.isPositive()) { "dispatch defaultLease must be positive" }
        require(minLease.isPositive()) { "dispatch minLease must be positive" }
        require(maxLease.isPositive()) { "dispatch maxLease must be positive" }
        require(!minClaimWait.isGreaterThan(maxClaimWait)) { "dispatch minClaimWait must be <= maxClaimWait" }
        require(!minLease.isGreaterThan(defaultLease)) { "dispatch minLease must be <= defaultLease" }
        require(!defaultLease.isGreaterThan(maxLease)) { "dispatch defaultLease must be <= maxLease" }
    }

    companion object {
        fun fromConfig(config: ConfigSection): DispatchConfig =
            DispatchConfig(
                pendingTtl = config.duration("pending-ttl", DEFAULT_DISPATCH_PENDING_TTL),
                maxClaimWait = config.duration("max-claim-wait", DEFAULT_DISPATCH_MAX_CLAIM_WAIT),
                minClaimWait = config.duration("min-claim-wait", DEFAULT_DISPATCH_MIN_CLAIM_WAIT),
                defaultLease = config.duration("default-lease", DEFAULT_DISPATCH_LEASE),
                minLease = config.duration("min-lease", DEFAULT_DISPATCH_MIN_LEASE),
                maxLease = config.duration("max-lease", DEFAULT_DISPATCH_MAX_LEASE),
            )
    }
}

private fun Duration.isPositive(): Boolean = !isZero && !isNegative

private fun Duration.isGreaterThan(other: Duration): Boolean = compareTo(other) > 0
