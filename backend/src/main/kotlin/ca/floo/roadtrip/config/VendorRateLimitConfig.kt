package ca.floo.roadtrip.config

import java.time.Duration

// Code-level default bucket. Deliberately conservative: a vendor with no explicit
// override gets a modest steady rate so a misconfiguration errs toward under-fetching
// rather than hammering an upstream we have no negotiated budget for. Overridable
// per-vendor via config (see [VendorRateLimitConfig.fromConfig]).
const val DEFAULT_VENDOR_BUCKET_CAPACITY: Long = 60
const val DEFAULT_VENDOR_BUCKET_REFILL_TOKENS: Long = 60
val DEFAULT_VENDOR_BUCKET_REFILL_PERIOD: Duration = Duration.ofMinutes(1)

private const val CAPACITY_SUFFIX = ".capacity"
private const val REFILL_TOKENS_SUFFIX = ".refill-tokens"
private const val REFILL_PERIOD_SUFFIX = ".refill-period"

/**
 * Per-vendor bucket config with a code-level default, overridable via config.
 * Providers not explicitly configured fall through to the code default
 * ([DEFAULT_VENDOR_BUCKET_CAPACITY] / [DEFAULT_VENDOR_BUCKET_REFILL_TOKENS] /
 * [DEFAULT_VENDOR_BUCKET_REFILL_PERIOD]).
 *
 * The class itself is pure and testable: the constructor takes an already-parsed
 * overrides map — no I/O. [fromConfig] reads the scoped application config
 * section into that map.
 */
class VendorRateLimitConfig(
    overrides: Map<String, VendorBucketConfig> = emptyMap(),
) {
    // Normalize vendor keys so lookup is case-insensitive and matches the
    // lowercase provider ids the executor passes (e.g. "recgov", "aspira").
    private val overrides: Map<String, VendorBucketConfig> = overrides.mapKeys { it.key.lowercase() }

    fun forVendor(provider: String): VendorBucketConfig =
        overrides[provider.lowercase()]
            ?: VendorBucketConfig(
                capacity = DEFAULT_VENDOR_BUCKET_CAPACITY,
                refillTokens = DEFAULT_VENDOR_BUCKET_REFILL_TOKENS,
                refillPeriod = DEFAULT_VENDOR_BUCKET_REFILL_PERIOD,
            )

    companion object {
        /**
         * Reads per-vendor overrides from application properties. A vendor is
         * configured with three keys, e.g. for "aspira":
         *
         *   roadtrip.vendor-rate-limit.aspira.capacity=5
         *   roadtrip.vendor-rate-limit.aspira.refill-tokens=5
         *   roadtrip.vendor-rate-limit.aspira.refill-period=10s
         *
         * A vendor is only added to the overrides map when its CAPACITY key is
         * present; the other two fall back to the code defaults if omitted, so a
         * single CAPACITY override is a valid minimal config.
         */
        fun fromConfig(config: ConfigSection): VendorRateLimitConfig {
            val vendors =
                config
                    .absoluteKeys()
                    .mapNotNull(config::relativeKey)
                    .mapNotNull(::vendorFromCapacityKey)
                    .filter { it.isNotBlank() }
                    .distinct()
            val overrides =
                vendors.associate { vendor ->
                    val capacity =
                        config
                            .value(capacityKey(vendor))
                            ?.toLongOrNull()
                            ?: DEFAULT_VENDOR_BUCKET_CAPACITY
                    val refillTokens =
                        config
                            .value(refillTokensKey(vendor))
                            ?.toLongOrNull()
                            ?: DEFAULT_VENDOR_BUCKET_REFILL_TOKENS
                    val refillPeriod =
                        parseDuration(
                            raw = config.value(refillPeriodKey(vendor)),
                            default = DEFAULT_VENDOR_BUCKET_REFILL_PERIOD,
                            key = config.key(refillPeriodKey(vendor)),
                        )
                    vendor to
                        VendorBucketConfig(
                            capacity = capacity,
                            refillTokens = refillTokens,
                            refillPeriod = refillPeriod,
                        )
                }
            return VendorRateLimitConfig(overrides)
        }

        private fun vendorFromCapacityKey(key: String): String? =
            when {
                key.endsWith(CAPACITY_SUFFIX) -> key.removeSuffix(CAPACITY_SUFFIX).lowercase()
                else -> null
            }

        private fun capacityKey(vendor: String): String = "$vendor$CAPACITY_SUFFIX"

        private fun refillTokensKey(vendor: String): String = "$vendor$REFILL_TOKENS_SUFFIX"

        private fun refillPeriodKey(vendor: String): String = "$vendor$REFILL_PERIOD_SUFFIX"
    }
}
