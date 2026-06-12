package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.ReservablePois.Companion.RESERVABLE_POIS
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.models.Reservable
import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record

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
    /**
     * Insert or update one reservable, keyed on its composite (type, vendor,
     * vendor_id). Returns the row's surrogate `id` for use in N:M linking.
     *
     * Idempotent: re-running with the same [Input] reuses the existing row
     * and refreshes name/loop/site_type/raw to whatever the caller passed.
     */
    fun upsert(input: Input): Long =
        ctx
            .insertInto(RESERVABLES)
            .set(RESERVABLES.TYPE, input.rid.type.encode())
            .set(RESERVABLES.VENDOR, input.rid.vendor)
            .set(RESERVABLES.VENDOR_ID, input.rid.vendorId)
            .set(RESERVABLES.NAME, input.name)
            .set(RESERVABLES.LOOP, input.loop)
            .set(RESERVABLES.SITE_TYPE, input.siteType)
            .set(RESERVABLES.RAW, input.raw?.let { JSONB.valueOf(jsonEncoder.encodeToString(JsonElement.serializer(), it)) })
            .onConflict(RESERVABLES.TYPE, RESERVABLES.VENDOR, RESERVABLES.VENDOR_ID)
            .doUpdate()
            .set(RESERVABLES.NAME, input.name)
            .set(RESERVABLES.LOOP, input.loop)
            .set(RESERVABLES.SITE_TYPE, input.siteType)
            .set(RESERVABLES.RAW, input.raw?.let { JSONB.valueOf(jsonEncoder.encodeToString(JsonElement.serializer(), it)) })
            .set(RESERVABLES.UPDATED_AT, ctx.currentOffsetDateTime())
            .returningResult(RESERVABLES.ID)
            .fetchOne()!!
            .value1()!!

    /** Find one reservable by its composite identity. */
    fun findByRid(rid: ReservableId): Reservable? =
        ctx
            .selectFrom(RESERVABLES)
            .where(RESERVABLES.TYPE.eq(rid.type.encode()))
            .and(RESERVABLES.VENDOR.eq(rid.vendor))
            .and(RESERVABLES.VENDOR_ID.eq(rid.vendorId))
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
            .fetchOne(0, Int::class.java)!!

    /**
     * Link a reservable to a POI. Idempotent — re-running with the same
     * pair is a no-op. The N:M shape supports park-POI parents in future
     * RFCs; v1 callers create exactly one link per reservable.
     */
    fun linkToPoi(
        reservableId: Long,
        poiId: Long,
    ) {
        ctx
            .insertInto(RESERVABLE_POIS)
            .set(RESERVABLE_POIS.RESERVABLE_ID, reservableId)
            .set(RESERVABLE_POIS.POI_ID, poiId)
            .onConflictDoNothing()
            .execute()
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

    private companion object {
        // Encoding-only, kept here to avoid coupling the repo to a global Json
        // configuration. Decoding uses the default Json.parseToJsonElement
        // since the input is trusted-from-DB.
        private val jsonEncoder = Json
    }
}

/** Convenience: jOOQ's `currentOffsetDateTime()` is verbose; mirror it as an extension. */
private fun DSLContext.currentOffsetDateTime() =
    org.jooq.impl.DSL
        .currentOffsetDateTime()
