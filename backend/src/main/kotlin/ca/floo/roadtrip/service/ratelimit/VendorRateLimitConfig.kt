package ca.floo.roadtrip.service.ratelimit

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

// Code-level default bucket. Deliberately conservative: a vendor with no explicit
// override gets a modest steady rate so a misconfiguration errs toward under-fetching
// rather than hammering an upstream we have no negotiated budget for. Overridable
// per-vendor via config/env (see [VendorRateLimitConfig.fromEnv]).
const val DEFAULT_VENDOR_BUCKET_CAPACITY: Long = 60
const val DEFAULT_VENDOR_BUCKET_REFILL_TOKENS: Long = 60
val DEFAULT_VENDOR_BUCKET_REFILL_PERIOD: Duration = Duration.ofMinutes(1)

private const val ENV_PREFIX = "ROADTRIP_VENDOR_RATELIMIT_"

/**
 * Per-vendor bucket config with a code-level default, overridable via config/env.
 * Providers not explicitly configured fall through to the code default
 * ([DEFAULT_VENDOR_BUCKET_CAPACITY] / [DEFAULT_VENDOR_BUCKET_REFILL_TOKENS] /
 * [DEFAULT_VENDOR_BUCKET_REFILL_PERIOD]).
 *
 * The class itself is pure and testable: the constructor takes an already-parsed
 * overrides map — no I/O. [fromEnv] is the wiring seam that reads the process
 * environment into that map at [ca.floo.roadtrip.Main] construction time.
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
         * Reads per-vendor overrides from the environment. A vendor is configured
         * with three keys, e.g. for "aspira":
         *
         *   ROADTRIP_VENDOR_RATELIMIT_ASPIRA_CAPACITY=5
         *   ROADTRIP_VENDOR_RATELIMIT_ASPIRA_REFILL_TOKENS=5
         *   ROADTRIP_VENDOR_RATELIMIT_ASPIRA_REFILL_PERIOD=PT10S   (ISO-8601 or Ns/Nm/Nh)
         *
         * A vendor is only added to the overrides map when its CAPACITY key is
         * present; the other two fall back to the code defaults if omitted, so a
         * single CAPACITY override is a valid minimal config.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): VendorRateLimitConfig {
            val capacitySuffix = "_CAPACITY"
            val vendors =
                env.keys
                    .filter { it.startsWith(ENV_PREFIX) && it.endsWith(capacitySuffix) }
                    .map { it.removePrefix(ENV_PREFIX).removeSuffix(capacitySuffix) }
                    .filter { it.isNotBlank() }
                    .distinct()
            val overrides =
                vendors.associate { rawVendor ->
                    val base = ENV_PREFIX + rawVendor
                    val capacity =
                        env["${base}_CAPACITY"]?.trim()?.toLongOrNull()
                            ?: DEFAULT_VENDOR_BUCKET_CAPACITY
                    val refillTokens =
                        env["${base}_REFILL_TOKENS"]?.trim()?.toLongOrNull()
                            ?: DEFAULT_VENDOR_BUCKET_REFILL_TOKENS
                    val refillPeriod =
                        env["${base}_REFILL_PERIOD"]?.let(::parseDurationOrNull)
                            ?: DEFAULT_VENDOR_BUCKET_REFILL_PERIOD
                    rawVendor.lowercase() to
                        VendorBucketConfig(
                            capacity = capacity,
                            refillTokens = refillTokens,
                            refillPeriod = refillPeriod,
                        )
                }
            return VendorRateLimitConfig(overrides)
        }

        private val SIMPLE_DURATION = Regex("""^(\d+)(ms|s|m|h|d)?$""")

        private fun parseDurationOrNull(raw: String): Duration? {
            val value = raw.trim()
            if (value.isBlank()) return null
            runCatching { Duration.parse(value) }.getOrNull()?.let { return it }
            val match = SIMPLE_DURATION.matchEntire(value.lowercase()) ?: return null
            val amount = match.groupValues[1].toLong()
            return when (match.groupValues[2]) {
                "ms" -> Duration.ofMillis(amount)
                "", "s" -> Duration.ofSeconds(amount)
                "m" -> Duration.ofMinutes(amount)
                "h" -> Duration.ofHours(amount)
                "d" -> Duration.ofDays(amount)
                else -> null
            }
        }
    }
}
