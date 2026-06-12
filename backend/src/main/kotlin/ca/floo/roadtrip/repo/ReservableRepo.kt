package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.ImportRuns.Companion.IMPORT_RUNS
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.db.generated.tables.ReservablePois.Companion.RESERVABLE_POIS
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.models.Reservable
import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persistence for the reservables catalog and its N:M link to POIs.
 *
 * Reservables are upserted in batches by ETL adapters; the request path
 * reads them via `findByPoi` (listing endpoint) or `findByRid`
 * (per-reservable detail). Per-day availability is NOT stored here — that
 * lives in the per-vendor availability cache, recomputed live by the
 * BookingProvider.
 *
 * RFC 0008 §"Data model".
 */
class ReservableRepo(
    private val ctx: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class ImportResult(
        val runId: Long,
        val seenCount: Int,
        val sweptCount: Int,
    )

    /**
     * Insert or update one reservable, keyed on its composite (type, vendor,
     * vendor_id). Returns the row's surrogate `id` for use in N:M linking.
     *
     * Idempotent: re-running with the same [Input] reuses the existing row
     * and refreshes name/loop/site_type/raw to whatever the caller passed.
     */
    fun upsert(
        input: Input,
        source: String = input.rid.vendor,
        runId: Long? = null,
    ): Long {
        val rawJson = input.raw?.let { jsonEncoder.encodeToString(JsonElement.serializer(), it) }
        return ctx
            .resultQuery(
                """
                INSERT INTO reservables (
                  type, vendor, vendor_id, source, name, loop, site_type, raw, last_seen_run_id
                ) VALUES (
                  ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?
                )
                ON CONFLICT (type, vendor, vendor_id)
                DO UPDATE SET
                  source = EXCLUDED.source,
                  name = EXCLUDED.name,
                  loop = EXCLUDED.loop,
                  site_type = EXCLUDED.site_type,
                  raw = EXCLUDED.raw,
                  last_seen_run_id = COALESCE(EXCLUDED.last_seen_run_id, reservables.last_seen_run_id),
                  deleted_at = NULL,
                  updated_at = now()
                RETURNING id
                """.trimIndent(),
                input.rid.type.encode(),
                input.rid.vendor,
                input.rid.vendorId,
                source,
                input.name,
                input.loop,
                input.siteType,
                rawJson,
                runId,
            ).fetchOne()!!
            .get(0, Long::class.java)!!
    }

    /**
     * Upsert a complete source snapshot and soft-delete active rows from the
     * same source that did not appear in this run.
     */
    fun runImport(
        source: String,
        inputs: List<Input>,
    ): ImportResult {
        val runId =
            ctx
                .insertInto(IMPORT_RUNS)
                .set(IMPORT_RUNS.SOURCE, source)
                .set(IMPORT_RUNS.STATUS, "started")
                .set(IMPORT_RUNS.STARTED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .returningResult(IMPORT_RUNS.ID)
                .fetchOne()!!
                .value1()!!
        log.info("import_runs id={} reservables source={} started", runId, source)

        try {
            val existingActive = activeCount(source)
            var seen = 0
            for (input in inputs) {
                upsert(input, source, runId)
                seen++
                if (seen % 1000 == 0) log.info("  upserted {} reservables", seen)
            }
            log.info("staged {} reservables from source={} (existing active={})", seen, source, existingActive)

            if (existingActive > 0 && seen < existingActive / 2) {
                fail(runId, "tripwire: seen=$seen < existing/2=${existingActive / 2}")
                throw UpsertException(
                    "Aborted: seen=$seen < existing/2=${existingActive / 2} for reservable source=$source",
                )
            }

            val swept = sweep(source, runId)
            log.info("swept {} reservables (soft-deleted) from source={}", swept, source)

            ctx
                .update(IMPORT_RUNS)
                .set(IMPORT_RUNS.STATUS, "completed")
                .set(IMPORT_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(IMPORT_RUNS.SEEN_COUNT, seen)
                .where(IMPORT_RUNS.ID.eq(runId))
                .execute()

            return ImportResult(runId = runId, seenCount = seen, sweptCount = swept)
        } catch (e: Exception) {
            if (e !is UpsertException) fail(runId, "unhandled: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /** Find one reservable by its composite identity. */
    fun findByRid(rid: ReservableId): Reservable? =
        ctx
            .selectFrom(RESERVABLES)
            .where(RESERVABLES.TYPE.eq(rid.type.encode()))
            .and(RESERVABLES.VENDOR.eq(rid.vendor))
            .and(RESERVABLES.VENDOR_ID.eq(rid.vendorId))
            .and(RESERVABLE_ACTIVE_CONDITION)
            .fetchOne()
            ?.let(::fromRecord)

    /**
     * Find every reservable linked to [poiId], optionally filtered by type.
     * Used by the listing endpoint (`/api/poi/{id}/reservables`).
     *
     * `type = null` returns all types — useful for catalog audit / admin
     * tooling. The route layer always passes a concrete type.
     */
    fun findByPoi(
        poiId: Long,
        type: ReservableType? = null,
    ): List<Reservable> {
        val typed = if (type == null) null else RESERVABLES.TYPE.eq(type.encode())
        return ctx
            .select(RESERVABLES.fields().toList())
            .from(RESERVABLES)
            .join(RESERVABLE_POIS)
            .on(RESERVABLE_POIS.RESERVABLE_ID.eq(RESERVABLES.ID))
            .where(RESERVABLE_POIS.POI_ID.eq(poiId))
            .and(RESERVABLE_ACTIVE_CONDITION)
            .let { step -> if (typed != null) step.and(typed) else step }
            .fetch { fromRecord(it) }
    }

    /** Catalog count at a POI — drives the `total_at_poi` field on responses. */
    fun countByPoi(
        poiId: Long,
        type: ReservableType,
    ): Int =
        ctx
            .selectCount()
            .from(RESERVABLES)
            .join(RESERVABLE_POIS)
            .on(RESERVABLE_POIS.RESERVABLE_ID.eq(RESERVABLES.ID))
            .where(RESERVABLE_POIS.POI_ID.eq(poiId))
            .and(RESERVABLES.TYPE.eq(type.encode()))
            .and(RESERVABLE_ACTIVE_CONDITION)
            .fetchOne(0, Int::class.java)!!

    /** Active POI ids linked to one reservable, ordered for stable API output. */
    fun poiIdsForReservable(reservableId: Long): List<Long> =
        ctx
            .select(RESERVABLE_POIS.POI_ID)
            .from(RESERVABLE_POIS)
            .join(POIS)
            .on(POIS.ID.eq(RESERVABLE_POIS.POI_ID))
            .where(RESERVABLE_POIS.RESERVABLE_ID.eq(reservableId))
            .and(POIS.DELETED_AT.isNull)
            .orderBy(RESERVABLE_POIS.POI_ID.asc())
            .fetch { it.value1()!! }

    /**
     * Link a reservable to a POI. Idempotent — re-running with the same
     * pair is a no-op. The N:M shape supports park-POI parents in future
     * RFCs; v1 callers create exactly one link per reservable.
     */
    fun linkToPoi(
        reservableId: Long,
        poiId: Long,
    ): Int =
        ctx
            .insertInto(RESERVABLE_POIS)
            .set(RESERVABLE_POIS.RESERVABLE_ID, reservableId)
            .set(RESERVABLE_POIS.POI_ID, poiId)
            .onConflictDoNothing()
            .execute()

    /** Link many reservables to POIs in chunks. Returns newly inserted rows. */
    fun linkToPois(links: List<LinkInput>): Int {
        if (links.isEmpty()) return 0
        return links
            .distinct()
            .chunked(LINK_INSERT_CHUNK_SIZE)
            .sumOf { chunk ->
                val values = chunk.joinToString(", ") { "(?, ?)" }
                val args = chunk.flatMap { listOf<Any?>(it.reservableId, it.poiId) }.toTypedArray()
                ctx.execute(
                    "INSERT INTO reservable_pois (reservable_id, poi_id) VALUES $values ON CONFLICT DO NOTHING",
                    *args,
                )
            }
    }

    /** Remove a reservable→POI link. Idempotent. */
    fun unlinkFromPoi(
        reservableId: Long,
        poiId: Long,
    ) {
        ctx
            .deleteFrom(RESERVABLE_POIS)
            .where(RESERVABLE_POIS.RESERVABLE_ID.eq(reservableId))
            .and(RESERVABLE_POIS.POI_ID.eq(poiId))
            .execute()
    }

    /** Input shape for `upsert`. Type/vendor/vendor_id come from the rid. */
    data class Input(
        val rid: ReservableId,
        val name: String?,
        val loop: String?,
        val siteType: String?,
        val raw: JsonElement?,
    )

    data class LinkInput(
        val reservableId: Long,
        val poiId: Long,
    )

    /**
     * Map a jOOQ record to the model. The DB columns are nullable for the
     * generic-text shape jOOQ generates, but type/vendor/vendor_id are
     * NOT NULL by the migration. Defensive throws here would mean the
     * migration ran wrong; treat them as invariants and let the !! crash
     * loudly if they do.
     */
    private fun fromRecord(r: Record): Reservable {
        val typeStr = r.get(RESERVABLES.TYPE)!!
        val vendor = r.get(RESERVABLES.VENDOR)!!
        val vendorId = r.get(RESERVABLES.VENDOR_ID)!!
        val type =
            ReservableType.parse(typeStr)
                ?: error("reservables.type=$typeStr is not a known ReservableType (row id=${r.get(RESERVABLES.ID)})")
        val rid = ReservableId(type = type, vendor = vendor, vendorId = vendorId)
        val rawJson = r.get(RESERVABLES.RAW)?.data()?.let { Json.parseToJsonElement(it) }
        return Reservable(
            id = r.get(RESERVABLES.ID)!!,
            rid = rid,
            name = r.get(RESERVABLES.NAME),
            loop = r.get(RESERVABLES.LOOP),
            siteType = r.get(RESERVABLES.SITE_TYPE),
            raw = rawJson,
        )
    }

    private fun activeCount(source: String): Int =
        ctx
            .fetchOne(
                "SELECT count(*) FROM reservables WHERE source = ? AND deleted_at IS NULL",
                source,
            )!!
            .get(0, Int::class.java)!!

    private fun sweep(
        source: String,
        runId: Long,
    ): Int =
        ctx.execute(
            """
            UPDATE reservables
            SET deleted_at = now()
            WHERE source = ?
              AND deleted_at IS NULL
              AND (last_seen_run_id <> ? OR last_seen_run_id IS NULL)
            """.trimIndent(),
            source,
            runId,
        )

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

    private companion object {
        // Encoding-only, kept here to avoid coupling the repo to a global Json
        // configuration. Decoding uses the default Json.parseToJsonElement
        // since the input is trusted-from-DB.
        private val jsonEncoder = Json

        private val RESERVABLE_ACTIVE_CONDITION = DSL.condition("reservables.deleted_at IS NULL")
        private const val LINK_INSERT_CHUNK_SIZE = 1000
    }
}
