package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.service.etl.framework.JoinerCtx
import ca.floo.roadtrip.service.etl.framework.PoiReservableJoiner
import org.jooq.impl.DSL

class ReserveCaliforniaPoiReservableJoiner : PoiReservableJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<PoiReservableJoiner.Link> {
        val parentPlaceId =
            DSL.field(
                "jsonb_extract_path_text(({0})::jsonb, {1})",
                String::class.java,
                RESERVABLES.RAW,
                DSL.inline(PARENT_PLACE_KEY),
            )
        val poiPlaceId =
            DSL.field(
                "jsonb_extract_path_text(({0})::jsonb, {1})",
                String::class.java,
                POIS.PROVIDER_REF,
                DSL.inline(POI_PLACE_KEY),
            )
        return ctx.ctx
            .select(RESERVABLES.ID, POIS.ID)
            .from(RESERVABLES)
            .join(POIS)
            .on(POIS.SOURCE.eq(POI_SOURCE).and(poiPlaceId.eq(parentPlaceId)))
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
                OR jsonb_extract_path_text(p.provider_ref::jsonb, ?) IS DISTINCT FROM jsonb_extract_path_text(r.raw::jsonb, ?)
              )
            """.trimIndent(),
            VENDOR,
            POI_SOURCE,
            POI_PLACE_KEY,
            PARENT_PLACE_KEY,
        )

    private companion object {
        const val ADAPTER_NAME = "ReserveCaliforniaPoiReservableJoiner"
        const val VENDOR = "reservecalifornia"
        const val POI_SOURCE = "california-state-parks"
        const val PARENT_PLACE_KEY = "_parent_place_id"
        const val POI_PLACE_KEY = "place_id"
    }
}
