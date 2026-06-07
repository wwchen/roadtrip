package ca.floo.roadtrip.etl

import ca.floo.roadtrip.db.generated.tables.ImportRuns.Companion.IMPORT_RUNS
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Mark-and-sweep upsert into the v2 `pois` table. Same shape as the
// legacy Importer.kt, generalized over the new sealed Poi types and
// the v2 columns (provider_ref, governing_body_id, etc.).
//
// Sweep is scoped to the union of source names this run wrote — a
// campground-merge run wipes only campground sources, never Tesla
// (RFC decision #16).
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
        // Use the first source as the import_runs.source label. The sweep
        // scope is `sources` (multi-source merges share one run).
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
        // booking_provider_id + provider_ref both come from the per-type
        // variant. Read them via type-narrowed access; the route uses the
        // FK to dispatch the adapter (PR 4) and the JSONB carries the IDs.
        val bookingProviderId: Long? =
            when (poi) {
                is Poi.Campground -> if (poi.providerRef != null) bookingProviderIdFor(poi.providerRef) else null
                else -> null
            }
        val providerRefJson: String? =
            when (poi) {
                is Poi.Campground -> poi.providerRef?.let { providerRefToJson(it) }
                else -> null
            }
        // Address column is JSONB; serialize as JSON or null.
        val addressJson: String? = poi.address?.let { addressToJson(it) }

        ctx.execute(
            """
            INSERT INTO pois (
              source, source_id, category, name, geom,
              region, country, unit_name, phone, address, info_url,
              governing_body_id, booking_provider_id, provider_ref,
              properties, fetched_at, last_verified,
              last_seen_run_id, deleted_at
            )
            VALUES (
              ?, ?, ?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326),
              ?, ?, ?, ?, ?::jsonb, ?,
              ?, ?, ?::jsonb,
              ?::jsonb, ?::timestamptz, ?,
              ?, NULL
            )
            ON CONFLICT (source, source_id) DO UPDATE SET
              category            = EXCLUDED.category,
              name                = EXCLUDED.name,
              geom                = EXCLUDED.geom,
              region              = EXCLUDED.region,
              country             = EXCLUDED.country,
              unit_name           = EXCLUDED.unit_name,
              phone               = EXCLUDED.phone,
              address             = EXCLUDED.address,
              info_url            = EXCLUDED.info_url,
              governing_body_id   = EXCLUDED.governing_body_id,
              booking_provider_id = EXCLUDED.booking_provider_id,
              provider_ref        = EXCLUDED.provider_ref,
              properties          = EXCLUDED.properties,
              fetched_at          = EXCLUDED.fetched_at,
              last_verified       = EXCLUDED.last_verified,
              last_seen_run_id    = EXCLUDED.last_seen_run_id,
              deleted_at          = NULL,
              updated_at          = NOW()
            """.trimIndent(),
            poi.source,
            poi.sourceId,
            poi.categorySql(),
            poi.name,
            poi.geomGeoJson,
            poi.region,
            poi.country,
            null, // unit_name — transitional, ETL doesn't populate (RFC: derived via ST_Within at query time)
            poi.phone,
            addressJson,
            poi.infoUrl,
            poi.governingBodyId,
            bookingProviderId,
            providerRefJson,
            poi.propertiesJson().toString(),
            fetchedAtTs,
            poi.lastVerified,
            runId,
        )
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

    // The per-row booking_provider FK is implicit: the campground's
    // ProviderRef variant + (for Aspira) its host need to map back to
    // a booking_provider row. PR 4 introduces a richer adapter-loaded
    // dispatch; for now we punt to the Camis-style fallback (no adapter
    // means no booking_provider).
    //
    // TODO PR 4: this needs to be threaded through TransformCtx so per-
    // source ETLs can select the correct (vendor, host) at transform
    // time. For now, ProviderRef.RecGov → recgov, Aspira → null until
    // we resolve host (host comes from booking_provider, not the ref —
    // RFC decision #23). PlanetFitness/Park don't have providers.
    private fun bookingProviderIdFor(ref: ProviderRef): Long? = null

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
