package ca.floo.roadtrip.etl

import ca.floo.roadtrip.db.generated.tables.BookingProvider.Companion.BOOKING_PROVIDER
import ca.floo.roadtrip.etl.registry.PoiRegistry
import org.jooq.DSLContext
import java.io.File

// Read-only dimension-table lookups the transform stage uses. Resolves
// (vendor, host) → booking_provider.id, and per-source legend bucket
// from the YAML registry.
//
// Cached in-memory at construction time. The YAML registry (and its
// PoiRegistrySync seed at boot) is the contract — if a transformer asks
// for an unknown (vendor, host), that's a programming bug.
class TransformCtx private constructor(
    private val bookingProviderByVendorHost: Map<Pair<String, String?>, Long>,
    private val legendBucketBySlug: Map<String, String?>,
    val rawDir: File,
) {
    // Aspira gets multiple rows (one per host). Pass host to disambiguate.
    // For single-host vendors (RecGov), pass the canonical host.
    fun bookingProviderId(
        vendor: String,
        host: String?,
    ): Long =
        bookingProviderByVendorHost[vendor to host]
            ?: error(
                "unknown booking_provider (vendor=$vendor, host=$host) — " +
                    "add a data_provider block to config/poi-registry.yaml",
            )

    /**
     * Static FE-rendering bucket for a campground source. Reads from
     * data_source.legend_bucket in YAML. Null when the source omits it
     * (per-row stamping case, or non-campground source).
     */
    fun legendBucketFor(slug: String): String? = legendBucketBySlug[slug]

    companion object {
        fun load(
            ctx: DSLContext,
            rawDir: File,
            registry: PoiRegistry,
        ): TransformCtx {
            val bp =
                ctx
                    .select(BOOKING_PROVIDER.VENDOR, BOOKING_PROVIDER.HOST, BOOKING_PROVIDER.ID)
                    .from(BOOKING_PROVIDER)
                    .fetch()
                    .associate { (it.value1()!! to it.value2()) to it.value3()!! }
            val buckets = registry.dataSources.associate { it.slug to it.legendBucket }
            return TransformCtx(bp, buckets, rawDir)
        }
    }
}
