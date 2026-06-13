package ca.floo.roadtrip.service.etl.aspira

import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.service.etl.JoinerCtx
import ca.floo.roadtrip.service.etl.PoiReservableJoiner
import org.jooq.impl.DSL

/**
 * Links Aspira reservables to their parent campground POI.
 *
 * Match rule:
 *   reservables.raw->>'_parent_aspira_txn_loc' = T
 *   reservables.raw->>'_parent_aspira_map_id'  = M
 *      ⇄ pois.source_id = "aspira-{T}-{M}"
 *   AND reservables.vendor maps to pois.source for the same tenant
 *
 * One adapter spans every Aspira tenant. The vendor/source mapping is
 * explicit because `(txnLoc, mapId)` can collide across tenant trees.
 * If a fourth tenant lands, add it to [TENANT_PAIRS].
 *
 * One SQL round-trip: JOIN on the constructed `aspira-{T}-{M}` source_id
 * pulled from the reservable's raw JSONB.
 */
class AspiraPoiReservableJoiner : PoiReservableJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<PoiReservableJoiner.Link> {
        // jsonb_extract_path_text is more forgiving than `->>` against
        // jOOQ's plain-SQL rendering — it accepts an explicit jsonb cast
        // without ambiguity over the operator's right-hand-type.
        val txnLoc =
            DSL.field(
                "jsonb_extract_path_text(({0})::jsonb, {1})",
                String::class.java,
                RESERVABLES.RAW,
                DSL.inline(PARENT_TXN_LOC_KEY),
            )
        val mapId =
            DSL.field(
                "jsonb_extract_path_text(({0})::jsonb, {1})",
                String::class.java,
                RESERVABLES.RAW,
                DSL.inline(PARENT_MAP_ID_KEY),
            )
        val expectedSourceId =
            DSL.concat(DSL.value(POI_SOURCE_ID_PREFIX), txnLoc, DSL.value("-"), mapId)

        return ctx.ctx
            .select(RESERVABLES.ID, POIS.ID)
            .from(RESERVABLES)
            .join(POIS)
            .on(tenantCondition().and(POIS.SOURCE_ID.eq(expectedSourceId)))
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
              AND r.vendor IN ('aspira_wa', 'aspira_bc', 'aspira_pc')
              AND p.source IN ('aspira-wa-pins', 'aspira-bc-pins', 'aspira-pc-pins')
              AND (
                r.deleted_at IS NOT NULL
                OR p.deleted_at IS NOT NULL
                OR NOT (
                  (r.vendor = 'aspira_wa' AND p.source = 'aspira-wa-pins')
                  OR (r.vendor = 'aspira_bc' AND p.source = 'aspira-bc-pins')
                  OR (r.vendor = 'aspira_pc' AND p.source = 'aspira-pc-pins')
                )
                OR p.source_id IS DISTINCT FROM concat(
                  ?,
                  jsonb_extract_path_text(r.raw::jsonb, ?),
                  '-',
                  jsonb_extract_path_text(r.raw::jsonb, ?)
                )
              )
            """.trimIndent(),
            POI_SOURCE_ID_PREFIX,
            PARENT_TXN_LOC_KEY,
            PARENT_MAP_ID_KEY,
        )

    private fun tenantCondition() =
        TENANT_PAIRS
            .map { (vendor, source) -> RESERVABLES.VENDOR.eq(vendor).and(POIS.SOURCE.eq(source)) }
            .reduce { acc, condition -> acc.or(condition) }

    private companion object {
        const val ADAPTER_NAME = "AspiraPoiReservableJoiner"

        // Synthetic JSONB keys AspiraResourcesEtl writes; the joiner's
        // contract with the ETL.
        const val PARENT_TXN_LOC_KEY = "_parent_aspira_txn_loc"
        const val PARENT_MAP_ID_KEY = "_parent_aspira_map_id"

        // POI keying for Aspira pins. AspiraJoinByNameEtl writes
        // pois.source_id = "aspira-{txnLoc}-{mapId}".
        const val POI_SOURCE_ID_PREFIX = "aspira-"
        val TENANT_PAIRS =
            listOf(
                "aspira_wa" to "aspira-wa-pins",
                "aspira_bc" to "aspira-bc-pins",
                "aspira_pc" to "aspira-pc-pins",
            )
    }
}
