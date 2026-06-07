package ca.floo.roadtrip.etl.registry

import ca.floo.roadtrip.db.generated.tables.BookingProvider.Companion.BOOKING_PROVIDER
import ca.floo.roadtrip.db.generated.tables.GoverningBody.Companion.GOVERNING_BODY
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

// Apply the YAML registry to the DB at boot:
//   1. UPSERT governing_body rows by slug (insert if new, update name/kind/country if changed)
//   2. UPSERT booking_provider rows by (vendor, host)
//   3. Refuse to boot if a row is in DB but missing from YAML AND is still
//      FK'd by a non-deleted POI — the deleter must either re-add it to
//      YAML or first soft-delete the dependent POIs.
//   4. Soft-delete (or just skip — we don't soft-delete dim rows today,
//      they're small and stable; orphan check from #3 is what protects us).
//
// Idempotent: re-running is a no-op when YAML and DB agree.
class PoiRegistrySync(
    private val ctx: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Apply [registry] to the DB. Throws [OrphanedReferenceException] if any
     * POI still references a slug/FK the YAML has dropped.
     */
    fun apply(registry: PoiRegistry) {
        // 1. Verify no POI references a slug we're about to delete from DB.
        val orphans = findOrphans(registry)
        if (orphans.isNotEmpty()) {
            throw OrphanedReferenceException(orphans)
        }

        // 2. UPSERT governing_body rows.
        var gbInserted = 0
        var gbUpdated = 0
        for (gb in registry.governingBodies) {
            val before =
                ctx
                    .fetchOne(GOVERNING_BODY, GOVERNING_BODY.SLUG.eq(gb.slug))
            ctx
                .insertInto(GOVERNING_BODY)
                .set(GOVERNING_BODY.SLUG, gb.slug)
                .set(GOVERNING_BODY.NAME, gb.name)
                .set(GOVERNING_BODY.KIND, gb.kind)
                .set(GOVERNING_BODY.COUNTRY, gb.country)
                .onConflict(GOVERNING_BODY.SLUG)
                .doUpdate()
                .set(GOVERNING_BODY.NAME, DSL.excluded(GOVERNING_BODY.NAME))
                .set(GOVERNING_BODY.KIND, DSL.excluded(GOVERNING_BODY.KIND))
                .set(GOVERNING_BODY.COUNTRY, DSL.excluded(GOVERNING_BODY.COUNTRY))
                .execute()
            if (before == null) gbInserted++ else gbUpdated++
        }

        // 3. UPSERT booking_provider rows by (vendor, host).
        var bpInserted = 0
        var bpUpdated = 0
        for (bp in registry.bookingProviders) {
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
                .set(BOOKING_PROVIDER.ADAPTER_CLASS, bp.adapterClass)
                .onConflict(BOOKING_PROVIDER.VENDOR, BOOKING_PROVIDER.HOST)
                .doUpdate()
                .set(BOOKING_PROVIDER.NAME, DSL.excluded(BOOKING_PROVIDER.NAME))
                .set(BOOKING_PROVIDER.ADAPTER_CLASS, DSL.excluded(BOOKING_PROVIDER.ADAPTER_CLASS))
                .execute()
            if (before == null) bpInserted++ else bpUpdated++
        }

        log.info(
            "poi-registry sync: governing_body inserted={} updated={}, booking_provider inserted={} updated={}",
            gbInserted,
            gbUpdated,
            bpInserted,
            bpUpdated,
        )
    }

    /**
     * Find any POI rows still referencing a slug/FK that the YAML has
     * dropped. Returns the set of `(slug | (vendor, host))` orphans we'd
     * have to remove from the DB before applying the registry.
     */
    private fun findOrphans(registry: PoiRegistry): List<Orphan> {
        val out = mutableListOf<Orphan>()

        val yamlGbSlugs = registry.governingBodies.map { it.slug }.toSet()
        val dbGbWithRefs =
            ctx
                .select(GOVERNING_BODY.SLUG, DSL.count())
                .from(GOVERNING_BODY)
                .join(POIS)
                .on(POIS.GOVERNING_BODY_ID.eq(GOVERNING_BODY.ID))
                .where(POIS.DELETED_AT.isNull)
                .groupBy(GOVERNING_BODY.SLUG)
                .fetch()
        for (r in dbGbWithRefs) {
            val slug = r.value1()!!
            val n = r.value2()!!
            if (slug !in yamlGbSlugs) {
                out += Orphan.GoverningBody(slug, n)
            }
        }

        val yamlBp = registry.bookingProviders.map { it.vendor to it.host }.toSet()
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

        data class GoverningBody(
            val slug: String,
            override val rowsReferencing: Int,
        ) : Orphan()

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
                is PoiRegistrySync.Orphan.GoverningBody ->
                    append("governing_body slug='")
                        .append(o.slug)
                        .append("' is FK'd by ")
                        .append(o.rowsReferencing)
                        .append(" POI(s)")
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
