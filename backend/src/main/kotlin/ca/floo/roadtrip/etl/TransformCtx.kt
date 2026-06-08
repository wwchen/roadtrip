package ca.floo.roadtrip.etl

import ca.floo.roadtrip.db.generated.tables.BookingProvider.Companion.BOOKING_PROVIDER
import ca.floo.roadtrip.db.generated.tables.GoverningBody.Companion.GOVERNING_BODY
import org.jooq.DSLContext
import java.io.File

// Read-only dimension-table lookups the transform stage uses. Resolves
// slug → governing_body.id and (vendor, host) → booking_provider.id.
//
// Cached in-memory at construction time. The seed (V6) is the contract
// — PoiDimensionSeedTest locks it in. If a transformer asks for an
// unknown slug, that's a programming bug (the seed is authoritative).
class TransformCtx private constructor(
    private val governingBodyBySlug: Map<String, Long>,
    private val bookingProviderByVendorHost: Map<Pair<String, String?>, Long>,
    val rawDir: File,
) {
    fun governingBodyId(slug: String): Long =
        governingBodyBySlug[slug]
            ?: error("unknown governing_body slug=$slug — add a row to V6 seed migration")

    // Aspira gets 3 rows (one per host). Pass host to disambiguate.
    // For single-host vendors (RecGov, Camis), pass the canonical host
    // (or null, but the seed has them set explicitly).
    fun bookingProviderId(
        vendor: String,
        host: String?,
    ): Long =
        bookingProviderByVendorHost[vendor to host]
            ?: error(
                "unknown booking_provider (vendor=$vendor, host=$host) — " +
                    "add a row to V6 seed migration",
            )

    companion object {
        fun load(
            ctx: DSLContext,
            rawDir: File,
        ): TransformCtx {
            val gb =
                ctx
                    .select(GOVERNING_BODY.SLUG, GOVERNING_BODY.ID)
                    .from(GOVERNING_BODY)
                    .fetch()
                    .associate { it.value1()!! to it.value2()!! }
            val bp =
                ctx
                    .select(BOOKING_PROVIDER.VENDOR, BOOKING_PROVIDER.HOST, BOOKING_PROVIDER.ID)
                    .from(BOOKING_PROVIDER)
                    .fetch()
                    .associate { (it.value1()!! to it.value2()) to it.value3()!! }
            return TransformCtx(gb, bp, rawDir)
        }
    }
}
