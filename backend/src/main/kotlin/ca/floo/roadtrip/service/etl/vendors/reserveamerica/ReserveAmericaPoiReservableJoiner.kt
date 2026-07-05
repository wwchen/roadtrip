package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.service.etl.framework.JoinerCtx
import ca.floo.roadtrip.service.etl.framework.PoiReservableJoiner
import org.jooq.impl.DSL

/**
 * Links ReserveAmerica reservables to their parent campground POI by the
 * (contract_code, park_id) pair — the reservable carries them in
 * `raw->>'_parent_contract_code'` / `_parent_park_id`; the POI carries them in
 * `provider_ref->>'contract_code'` / `park_id`. Both keys must match, so a
 * parkId that collides across contracts does not cross-link. Spans both
 * tenants (alberta-provincial, new-york-state-parks); vendor is per-tenant
 * (`reserveamerica_%`).
 */
class ReserveAmericaPoiReservableJoiner : PoiReservableJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<PoiReservableJoiner.Link> {
        fun res(key: String) = DSL.field("jsonb_extract_path_text(({0})::jsonb, {1})", String::class.java, RESERVABLES.RAW, DSL.inline(key))

        fun poi(key: String) =
            DSL.field("jsonb_extract_path_text(({0})::jsonb, {1})", String::class.java, POIS.PROVIDER_REF, DSL.inline(key))

        return ctx.ctx
            .select(RESERVABLES.ID, POIS.ID)
            .from(RESERVABLES)
            .join(POIS)
            .on(
                poi(POI_CONTRACT_KEY)
                    .eq(res(PARENT_CONTRACT_KEY))
                    .and(poi(POI_PARK_KEY).eq(res(PARENT_PARK_KEY))),
            ).where(POIS.SOURCE.`in`(POI_SOURCES))
            .and(RESERVABLES.VENDOR.like(VENDOR_PREFIX))
            .and(DSL.condition("reservables.deleted_at IS NULL"))
            .and(POIS.DELETED_AT.isNull)
            .fetch { record -> PoiReservableJoiner.Link(reservableId = record.value1()!!, poiId = record.value2()!!) }
    }

    override fun sweepStaleLinks(ctx: JoinerCtx): Int =
        ctx.ctx.execute(
            """
            DELETE FROM reservable_pois rp
            USING reservables r, pois p
            WHERE rp.reservable_id = r.id
              AND rp.poi_id = p.id
              AND r.vendor LIKE ?
              AND p.source IN ('alberta-provincial','new-york-state-parks')
              AND (
                r.deleted_at IS NOT NULL
                OR p.deleted_at IS NOT NULL
                OR jsonb_extract_path_text(p.provider_ref::jsonb, ?) IS DISTINCT FROM jsonb_extract_path_text(r.raw::jsonb, ?)
                OR jsonb_extract_path_text(p.provider_ref::jsonb, ?) IS DISTINCT FROM jsonb_extract_path_text(r.raw::jsonb, ?)
              )
            """.trimIndent(),
            VENDOR_PREFIX,
            POI_CONTRACT_KEY,
            PARENT_CONTRACT_KEY,
            POI_PARK_KEY,
            PARENT_PARK_KEY,
        )

    private companion object {
        const val ADAPTER_NAME = "ReserveAmericaPoiReservableJoiner"
        const val VENDOR_PREFIX = "reserveamerica_%"
        val POI_SOURCES = listOf("alberta-provincial", "new-york-state-parks")
        const val POI_CONTRACT_KEY = "contract_code"
        const val POI_PARK_KEY = "park_id"
        const val PARENT_CONTRACT_KEY = "_parent_contract_code"
        const val PARENT_PARK_KEY = "_parent_park_id"
    }
}
