package ca.floo.roadtrip.service.ratelimit

import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.jdbc.BucketTableSettings
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper
import io.github.bucket4j.distributed.jdbc.SQLProxyConfiguration
import io.github.bucket4j.postgresql.PostgreSQLSelectForUpdateBasedProxyManager
import javax.sql.DataSource

// Bucket4j-owned state table (created by Flyway in V32); keyed by vendor name.
private const val BUCKET_TABLE = "vendor_rate_limit_bucket"
private const val BUCKET_ID_COLUMN = "id"
private const val BUCKET_STATE_COLUMN = "state"

/**
 * Durable (Postgres-backed) per-vendor token bucket -- the fetch governor's
 * gate. A restart does not reset the budget: bucket state lives in Bucket4j's
 * Postgres table ([BUCKET_TABLE]), not in process memory.
 *
 * Keyed by provider name via [PrimaryKeyMapper.STRING] -- a stable string key,
 * so there is no `hashCode()`-collision risk across provider names.
 *
 * The Bucket4j types stay inside this wrapper: callers (the executor) see only
 * [tryAcquire], keeping the dependency swappable and the abstraction non-leaky.
 */
open class VendorRateLimiter(
    private val config: VendorRateLimitConfig,
    dataSource: DataSource,
) {
    private val proxyManager: PostgreSQLSelectForUpdateBasedProxyManager<String> =
        PostgreSQLSelectForUpdateBasedProxyManager(
            SQLProxyConfiguration
                .builder()
                .withTableSettings(
                    BucketTableSettings.customSettings(BUCKET_TABLE, BUCKET_ID_COLUMN, BUCKET_STATE_COLUMN),
                ).withPrimaryKeyMapper(PrimaryKeyMapper.STRING)
                .build(dataSource),
        )

    /**
     * Attempts to consume [tokens] from [provider]'s bucket. Returns true if
     * acquired (the caller may proceed with exactly that many upstream calls);
     * false if insufficient tokens are available right now (no partial
     * consumption on failure). Non-blocking -- never waits for refill.
     */
    open fun tryAcquire(
        provider: String,
        tokens: Long,
    ): Boolean {
        val bucketConfig = config.forVendor(provider)
        val bucket = proxyManager.builder().build(provider, bucketConfiguration(bucketConfig))
        return bucket.tryConsume(tokens)
    }

    private fun bucketConfiguration(bucket: VendorBucketConfig): BucketConfiguration =
        BucketConfiguration
            .builder()
            .addLimit { limit ->
                limit
                    .capacity(bucket.capacity)
                    .refillIntervally(bucket.refillTokens, bucket.refillPeriod)
            }.build()
}
