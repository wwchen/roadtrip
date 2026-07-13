package ca.floo.roadtrip.config

import java.time.Duration

/**
 * Token-bucket parameters for one vendor (availability provider).
 *
 * - [capacity]: max tokens the bucket holds (the burst ceiling).
 * - [refillTokens]: tokens added each [refillPeriod].
 * - [refillPeriod]: the interval over which [refillTokens] are added.
 */
data class VendorBucketConfig(
    val capacity: Long,
    val refillTokens: Long,
    val refillPeriod: Duration,
) {
    init {
        require(capacity > 0) { "vendor bucket capacity must be positive" }
        require(refillTokens > 0) { "vendor bucket refillTokens must be positive" }
        require(!refillPeriod.isZero && !refillPeriod.isNegative) { "vendor bucket refillPeriod must be positive" }
    }
}
