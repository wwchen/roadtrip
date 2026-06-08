package ca.floo.roadtrip.etl

import ca.floo.roadtrip.db.generated.tables.ImportRuns.Companion.IMPORT_RUNS
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import org.jooq.DSLContext
import org.jooq.Geometry
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Mark-and-sweep upsert into the v2 `pois` table. Same shape as the
// legacy Importer.kt, generalized over the new sealed Poi types and
// the v2 columns (provider_ref JSONB; booking_provider_id was dropped
// in V8 since the dispatch path it was meant to power never landed).
//
// Sweep is scoped to the union of source names this run wrote — a
// campground-merge run wipes only campground sources, never Tesla
// (RFC decision #16).
//
// Implemented via the jOOQ DSL (not raw SQL) so adding a column to
// `pois` becomes a compile-time obligation: the generated `POIS`
// table type changes, and any forgotten `set()` here surfaces as a
// type mismatch at the next build, not a silent column drop at
// runtime. The one bit of raw SQL is `ST_SetSRID(ST_GeomFromGeoJSON
// (?), 4326)` for the PostGIS geometry constructor; jOOQ OSS doesn't
// have a typed builder for that.
class Upsert(
    private val ctx: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Result(
        val runId: Long,
        val seenCount: Int,
        val sweptCount: Int,
    )

    /**
     * Upsert [pois] into the v2 schema, then mark-and-sweep across [sources].
     * The tripwire (seen < 0.5 × prior_active) aborts before sweep so a
     * partial fetch can't wipe the table.
     */
    fun run(
        sources: Set<String>,
        pois: List<Poi>,
    ): Result {
        require(sources.isNotEmpty()) { "must specify at least one source for sweep scope" }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        // Use the sorted source list as the import_runs.source label.
        // Sweep scope is `sources` (multi-source merges share one run).
        val runLabel = sources.sorted().joinToString(",")
        val runId =
            ctx
                .insertInto(IMPORT_RUNS)
                .set(IMPORT_RUNS.SOURCE, runLabel)
                .set(IMPORT_RUNS.STATUS, "started")
                .set(IMPORT_RUNS.STARTED_AT, now)
                .returningResult(IMPORT_RUNS.ID)
                .fetchOne()!!
                .value1()!!
        log.info("import_runs id={} sources={} started", runId, runLabel)

        try {
            val existingActive =
                ctx.fetchCount(
                    POIS,
                    POIS.SOURCE.`in`(sources).and(POIS.DELETED_AT.isNull),
                )

            var seen = 0
            for (poi in pois) {
                upsertOne(poi, runId)
                seen++
                if (seen % 1000 == 0) log.info("  upserted {} rows", seen)
            }
            log.info("staged {} rows from sources={} (existing active={})", seen, runLabel, existingActive)

            // Tripwire: a fetch that silently truncates upstream would
            // otherwise sweep the whole set. 0.5 is conservative.
            if (existingActive > 0 && seen < existingActive / 2) {
                fail(runId, "tripwire: seen=$seen < existing/2=${existingActive / 2}")
                throw UpsertException(
                    "Aborted: seen=$seen < existing/2=${existingActive / 2} for sources=$runLabel",
                )
            }

            val swept = sweep(sources, runId)
            log.info("swept {} rows (soft-deleted) from sources={}", swept, runLabel)

            ctx
                .update(IMPORT_RUNS)
                .set(IMPORT_RUNS.STATUS, "completed")
                .set(IMPORT_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(IMPORT_RUNS.SEEN_COUNT, seen)
                .where(IMPORT_RUNS.ID.eq(runId))
                .execute()

            return Result(runId, seen, swept)
        } catch (e: Exception) {
            if (e !is UpsertException) fail(runId, "unhandled: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun upsertOne(
        poi: Poi,
        runId: Long,
    ) {
        val fetchedAtTs = OffsetDateTime.ofInstant(poi.fetchedAt, ZoneOffset.UTC)
        val providerRefJson = providerRefJsonFor(poi)
        val addressJson = poi.address?.let { JSONB.valueOf(addressToJson(it)) }
        val propertiesJson = JSONB.valueOf(poi.propertiesJson().toString())

        // PostGIS geometry constructor — jOOQ OSS has no typed builder for
        // ST_GeomFromGeoJSON, so this stays as a parameterized DSL field.
        // SRID 4326 matches the column declaration.
        val geomField =
            DSL.field<Geometry>(
                "ST_SetSRID(ST_GeomFromGeoJSON({0}), 4326)",
                SQLDataType.GEOMETRY,
                DSL.value(poi.geomGeoJson),
            )

        ctx
            .insertInto(POIS)
            .set(POIS.SOURCE, poi.source)
            .set(POIS.SOURCE_ID, poi.sourceId)
            .set(POIS.CATEGORY, poi.categorySql())
            .set(POIS.NAME, poi.name)
            .set(POIS.GEOM, geomField)
            .set(POIS.REGION, poi.region)
            .set(POIS.COUNTRY, poi.country)
            // unit_name is transitional; ETL never populates it (parent-of
            // is derived via ST_Within at query time per RFC decision #18).
            .set(POIS.UNIT_NAME, null as String?)
            .set(POIS.PHONE, poi.phone)
            .set(POIS.ADDRESS, addressJson)
            .set(POIS.INFO_URL, poi.infoUrl)
            .set(POIS.PROVIDER_REF, providerRefJson)
            .set(POIS.PROPERTIES, propertiesJson)
            .set(POIS.FETCHED_AT, fetchedAtTs)
            .set(POIS.LAST_VERIFIED, poi.lastVerified)
            .set(POIS.LAST_SEEN_RUN_ID, runId)
            .onConflict(POIS.SOURCE, POIS.SOURCE_ID)
            .doUpdate()
            // EXCLUDED.* refers to the row that would have been inserted.
            // jOOQ's onDuplicateKeyUpdate idiom uses DSL.excluded(field).
            .set(POIS.CATEGORY, DSL.excluded(POIS.CATEGORY))
            .set(POIS.NAME, DSL.excluded(POIS.NAME))
            .set(POIS.GEOM, DSL.excluded(POIS.GEOM))
            .set(POIS.REGION, DSL.excluded(POIS.REGION))
            .set(POIS.COUNTRY, DSL.excluded(POIS.COUNTRY))
            .set(POIS.UNIT_NAME, DSL.excluded(POIS.UNIT_NAME))
            .set(POIS.PHONE, DSL.excluded(POIS.PHONE))
            .set(POIS.ADDRESS, DSL.excluded(POIS.ADDRESS))
            .set(POIS.INFO_URL, DSL.excluded(POIS.INFO_URL))
            .set(POIS.PROVIDER_REF, DSL.excluded(POIS.PROVIDER_REF))
            .set(POIS.PROPERTIES, DSL.excluded(POIS.PROPERTIES))
            .set(POIS.FETCHED_AT, DSL.excluded(POIS.FETCHED_AT))
            .set(POIS.LAST_VERIFIED, DSL.excluded(POIS.LAST_VERIFIED))
            .set(POIS.LAST_SEEN_RUN_ID, DSL.excluded(POIS.LAST_SEEN_RUN_ID))
            // Resurrection: a previously deleted source_id reappears with deleted_at=NULL.
            .set(POIS.DELETED_AT, null as OffsetDateTime?)
            .set(POIS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    private fun sweep(
        sources: Set<String>,
        runId: Long,
    ): Int =
        ctx
            .update(POIS)
            .set(POIS.DELETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(POIS.SOURCE.`in`(sources))
            .and(POIS.DELETED_AT.isNull)
            .and(POIS.LAST_SEEN_RUN_ID.ne(runId).or(POIS.LAST_SEEN_RUN_ID.isNull))
            .execute()

    private fun fail(
        runId: Long,
        notes: String,
    ) {
        ctx
            .update(IMPORT_RUNS)
            .set(IMPORT_RUNS.STATUS, "failed")
            .set(IMPORT_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(IMPORT_RUNS.NOTES, notes)
            .where(IMPORT_RUNS.ID.eq(runId))
            .execute()
    }

    /** provider_ref JSONB. Only Campground variants carry it; null otherwise. */
    private fun providerRefJsonFor(poi: Poi): JSONB? =
        when (poi) {
            is Poi.Campground -> poi.providerRef?.let { JSONB.valueOf(providerRefToJson(it)) }
            else -> null
        }

    private fun providerRefToJson(ref: ProviderRef): String =
        when (ref) {
            is ProviderRef.RecGov ->
                """{"recgov_id":"${ref.recgovId.replace("\"", "\\\"")}"}"""
            is ProviderRef.Aspira ->
                """{"transactionLocationId":${ref.transactionLocationId},"mapId":${ref.mapId},"resourceLocationId":${ref.resourceLocationId ?: "null"}}"""
            is ProviderRef.Camis ->
                """{"facility_id":"${ref.facilityId.replace("\"", "\\\"")}"}"""
        }

    private fun addressToJson(a: Address): String =
        buildString {
            append('{')
            val parts = mutableListOf<String>()
            a.street?.let { parts += """"street":"${it.replace("\"", "\\\"")}"""" }
            a.city?.let { parts += """"city":"${it.replace("\"", "\\\"")}"""" }
            a.state?.let { parts += """"state":"${it.replace("\"", "\\\"")}"""" }
            a.postcode?.let { parts += """"postcode":"${it.replace("\"", "\\\"")}"""" }
            a.country?.let { parts += """"country":"${it.replace("\"", "\\\"")}"""" }
            append(parts.joinToString(","))
            append('}')
        }
}

class UpsertException(
    message: String,
) : RuntimeException(message)
