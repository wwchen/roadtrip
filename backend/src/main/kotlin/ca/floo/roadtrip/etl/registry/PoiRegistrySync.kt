package ca.floo.roadtrip.etl.registry

import ca.floo.roadtrip.db.generated.tables.BookingProvider.Companion.BOOKING_PROVIDER
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

// Apply the YAML registry to the DB at boot:
//   1. UPSERT booking_provider rows by (vendor, host) — derived from the
//      deduped data_provider blocks across every data_source.
//   2. Refuse to boot if a booking_provider is in DB but missing from YAML
//      AND is still FK'd by a non-deleted POI — the deleter must either
//      re-add the (vendor, host) to YAML or first soft-delete the dependent
//      POIs.
//
// Idempotent: re-running is a no-op when YAML and DB agree.
class PoiRegistrySync(
    private val ctx: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Apply [registry] to the DB. Throws [OrphanedReferenceException] if any
     * POI still references a (vendor, host) the YAML has dropped.
     */
    fun apply(registry: PoiRegistry) {
        val providers = registry.bookingProviders()

        // 1. Verify no POI references a booking_provider we're about to
        //    delete from DB.
        val orphans = findOrphans(providers)
        if (orphans.isNotEmpty()) {
            throw OrphanedReferenceException(orphans)
        }

        // 2. UPSERT booking_provider rows by (vendor, host).
        var bpInserted = 0
        var bpUpdated = 0
        for (bp in providers) {
            val before =
                ctx
                    .selectFrom(BOOKING_PROVIDER)
                    .where(BOOKING_PROVIDER.VENDOR.eq(bp.vendor))
                    .and(if (bp.host == null) BOOKING_PROVIDER.HOST.isNull else BOOKING_PROVIDER.HOST.eq(bp.host))
                    .fetchOne()
            ctx
                .insertInto(BOOKING_PROVIDER)
                .set(BOOKING_PROVIDER.VENDOR, bp.vendor)
                .set(BOOKING_PROVIDER.HOST, bp.host)
                .set(BOOKING_PROVIDER.NAME, bp.name)
                .set(BOOKING_PROVIDER.ADAPTER_CLASS, bp.adapter)
                .onConflict(BOOKING_PROVIDER.VENDOR, BOOKING_PROVIDER.HOST)
                .doUpdate()
                .set(BOOKING_PROVIDER.NAME, DSL.excluded(BOOKING_PROVIDER.NAME))
                .set(BOOKING_PROVIDER.ADAPTER_CLASS, DSL.excluded(BOOKING_PROVIDER.ADAPTER_CLASS))
                .execute()
            if (before == null) bpInserted++ else bpUpdated++
        }

        log.info(
            "poi-registry sync: booking_provider inserted={} updated={}",
            bpInserted,
            bpUpdated,
        )
    }

    /**
     * Find any POI rows still referencing a booking_provider that the
     * YAML has dropped. Returns the set of (vendor, host) orphans we'd
     * have to remove from the DB before applying the registry.
     */
    private fun findOrphans(providers: List<DataProvider>): List<Orphan> {
        val out = mutableListOf<Orphan>()

        val yamlBp = providers.map { it.vendor to it.host }.toSet()
        val dbBpWithRefs =
            ctx
                .select(BOOKING_PROVIDER.VENDOR, BOOKING_PROVIDER.HOST, DSL.count())
                .from(BOOKING_PROVIDER)
                .join(POIS)
                .on(POIS.BOOKING_PROVIDER_ID.eq(BOOKING_PROVIDER.ID))
                .where(POIS.DELETED_AT.isNull)
                .groupBy(BOOKING_PROVIDER.VENDOR, BOOKING_PROVIDER.HOST)
                .fetch()
        for (r in dbBpWithRefs) {
            val vendor = r.value1()!!
            val host = r.value2()
            val n = r.value3()!!
            if ((vendor to host) !in yamlBp) {
                out += Orphan.BookingProvider(vendor, host, n)
            }
        }

        return out
    }

    sealed class Orphan {
        abstract val rowsReferencing: Int

        data class BookingProvider(
            val vendor: String,
            val host: String?,
            override val rowsReferencing: Int,
        ) : Orphan()
    }
}

class OrphanedReferenceException(
    val orphans: List<PoiRegistrySync.Orphan>,
) : RuntimeException(buildOrphanMessage(orphans))

private fun buildOrphanMessage(orphans: List<PoiRegistrySync.Orphan>): String =
    buildString {
        append("Cannot apply poi-registry: ").append(orphans.size).append(" orphaned reference(s) in pois:\n")
        for (o in orphans) {
            append("  - ")
            when (o) {
                is PoiRegistrySync.Orphan.BookingProvider ->
                    append("booking_provider (vendor='")
                        .append(o.vendor)
                        .append("', host='")
                        .append(o.host ?: "null")
                        .append("') is FK'd by ")
                        .append(o.rowsReferencing)
                        .append(" POI(s)")
            }
            append('\n')
        }
        append(
            "Either re-add the entry to config/poi-registry.yaml, or first " +
                "soft-delete the dependent POI rows (UPDATE pois SET deleted_at = NOW() WHERE …).",
        )
    }
