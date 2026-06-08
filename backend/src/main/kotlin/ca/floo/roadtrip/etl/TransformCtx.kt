package ca.floo.roadtrip.etl

import ca.floo.roadtrip.db.generated.tables.BookingProvider.Companion.BOOKING_PROVIDER
import org.jooq.DSLContext
import java.io.File

// Read-only dimension-table lookups the transform stage uses. Resolves
// (vendor, host) → booking_provider.id.
//
// Cached in-memory at construction time. The YAML registry (and its
// PoiRegistrySync seed at boot) is the contract — if a transformer asks
// for an unknown (vendor, host), that's a programming bug.
class TransformCtx private constructor(
    private val bookingProviderByVendorHost: Map<Pair<String, String?>, Long>,
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

    companion object {
        fun load(
            ctx: DSLContext,
            rawDir: File,
        ): TransformCtx {
            val bp =
                ctx
                    .select(BOOKING_PROVIDER.VENDOR, BOOKING_PROVIDER.HOST, BOOKING_PROVIDER.ID)
                    .from(BOOKING_PROVIDER)
                    .fetch()
                    .associate { (it.value1()!! to it.value2()) to it.value3()!! }
            return TransformCtx(bp, rawDir)
        }
    }
}
