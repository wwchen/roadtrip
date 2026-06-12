package ca.floo.roadtrip.service.etl.recgov

import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.service.etl.JoinerCtx
import ca.floo.roadtrip.service.etl.PoiReservableJoiner
import org.jooq.impl.DSL

/**
 * Links rec.gov reservables to their parent federal-campgrounds POI.
 *
 * Match rule:
 *   reservables.raw->>'_parent_facility_id'   (RFC 0008 PR 3a injects this)
 *      ⇄ pois.source_id = "recgov-{FacilityID}"
 *   AND pois.source = federal-campgrounds
 *
 * The synthetic `_parent_facility_id` is the joiner's only handshake
 * with the ETL — by design, the ETL doesn't know how POIs are keyed.
 * If a future ETL stops emitting that field, this joiner finds zero
 * matches and the reservable_pois table just doesn't get populated;
 * we don't want it failing loud across an unrelated keying change.
 *
 * One SQL round-trip: a JOIN between `reservables` (filtered to
 * vendor='recgov') and `pois` (filtered to source='federal-campgrounds')
 * on the synthetic-keyed JSONB extraction. Postgres's `->>` operator
 * applies after the index filter.
 */
class RecgovPoiReservableJoiner : PoiReservableJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<PoiReservableJoiner.Link> {
        // jsonb_extract_path_text is more forgiving than `->>` against
        // jOOQ's plain-SQL rendering — it accepts an explicit jsonb cast
        // without ambiguity over the operator's right-hand-type.
        val parentFacilityId =
            DSL.field(
                "jsonb_extract_path_text(({0})::jsonb, {1})",
                String::class.java,
                RESERVABLES.RAW,
                DSL.inline(PARENT_FACILITY_KEY),
            )
        val expectedSourceId =
            DSL.concat(DSL.value(POI_SOURCE_ID_PREFIX), parentFacilityId)

        return ctx.ctx
            .select(RESERVABLES.ID, POIS.ID)
            .from(RESERVABLES)
            .join(POIS)
            .on(POIS.SOURCE.eq(POI_SOURCE).and(POIS.SOURCE_ID.eq(expectedSourceId)))
            .where(RESERVABLES.VENDOR.eq(VENDOR))
            .and(DSL.condition("reservables.deleted_at IS NULL"))
            .and(POIS.DELETED_AT.isNull)
            .fetch { record ->
                PoiReservableJoiner.Link(
                    reservableId = record.value1()!!,
                    poiId = record.value2()!!,
                )
            }
    }

    override fun sweepStaleLinks(ctx: JoinerCtx): Int =
        ctx.ctx.execute(
            """
            DELETE FROM reservable_pois rp
            USING reservables r, pois p
            WHERE rp.reservable_id = r.id
              AND rp.poi_id = p.id
              AND r.vendor = ?
              AND p.source = ?
              AND (
                r.deleted_at IS NOT NULL
                OR p.deleted_at IS NOT NULL
                OR p.source_id IS DISTINCT FROM concat(?, jsonb_extract_path_text(r.raw::jsonb, ?))
              )
            """.trimIndent(),
            VENDOR,
            POI_SOURCE,
            POI_SOURCE_ID_PREFIX,
            PARENT_FACILITY_KEY,
        )

    private companion object {
        const val ADAPTER_NAME = "RecgovPoiReservableJoiner"

        // Synthetic JSONB key the rec.gov reservable ETL writes; the
        // joiner's only contract with the ETL.
        const val PARENT_FACILITY_KEY = "_parent_facility_id"

        // POI keying for federal campgrounds.
        const val POI_SOURCE = "federal-campgrounds"
        const val POI_SOURCE_ID_PREFIX = "recgov-"

        // Reservable side filter — bound by RecGovCampsitesEtl's
        // ReservableId(vendor=this).
        const val VENDOR = "recgov"
    }
}
