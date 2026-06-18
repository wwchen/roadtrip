package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.service.etl.framework.JoinerCtx
import ca.floo.roadtrip.service.etl.framework.PoiReservableJoiner
import org.jooq.impl.DSL

/**
 * Links Aspira reservables to their parent campground POI.
 *
 * Two match rules, OR'd:
 *
 *   (A) `pois.source_id = "aspira-{T}-{M}"`
 *       where T = `reservables.provider_ref->>'transactionLocationId'`
 *             M = `reservables.provider_ref->>'mapId'`
 *
 *   (B) `pois.provider_ref->>'resourceLocationId' =
 *        reservables.provider_ref->>'resourceLocationId'`
 *
 * Rule (A) is the original `(txnLoc, mapId)` join, established when
 * AspiraResourcesEtl emitted only resources whose parent leaf was
 * directly visible in `/api/maps`. Rule (B) is the fallback for the
 * inventory-driven ETL: `/api/resourcelocation/resources` returns
 * sites whose `mapIds[]` may point to a sub-leaf that
 * AspiraLeavesWalk doesn't expose, but every site still carries the
 * park's `resourceLocationId` — and POIs do too, in their
 * `provider_ref` JSONB. Without (B), parks with deep-tree leaves
 * (Deception Pass, Fort Worden, ~30% of WA) end up with zero linked
 * reservables.
 *
 * One adapter spans every Aspira tenant; the vendor/source mapping is
 * explicit because `(txnLoc, mapId)` and `resourceLocationId` can both
 * collide across tenant trees. If a fourth tenant lands, add it to
 * [TENANT_PAIRS].
 */
class AspiraPoiReservableJoiner : PoiReservableJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<PoiReservableJoiner.Link> {
        // jsonb_extract_path_text is more forgiving than `->>` against
        // jOOQ's plain-SQL rendering — it accepts an explicit jsonb cast
        // without ambiguity over the operator's right-hand-type.
        val txnLoc = jsonField(RESERVABLES.PROVIDER_REF, PROVIDER_REF_TXN_LOC_KEY)
        val mapId = jsonField(RESERVABLES.PROVIDER_REF, PROVIDER_REF_MAP_ID_KEY)
        val expectedSourceId =
            DSL.concat(DSL.value(POI_SOURCE_ID_PREFIX), txnLoc, DSL.value("-"), mapId)

        // Rule (B): match by resourceLocationId on both sides.
        val poiResLoc = jsonField(POIS.PROVIDER_REF, POI_PROVIDER_REF_RES_LOC_KEY)
        val reservableResLoc = jsonField(RESERVABLES.PROVIDER_REF, PROVIDER_REF_RES_LOC_KEY)
        val resLocMatch =
            poiResLoc
                .isNotNull
                .and(reservableResLoc.isNotNull)
                .and(poiResLoc.eq(reservableResLoc))

        return ctx.ctx
            .selectDistinct(RESERVABLES.ID, POIS.ID)
            .from(RESERVABLES)
            .join(POIS)
            .on(tenantCondition().and(POIS.SOURCE_ID.eq(expectedSourceId).or(resLocMatch)))
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
                OR (
                  -- Neither match rule still holds: stale link.
                  p.source_id IS DISTINCT FROM concat(
                    ?,
                    jsonb_extract_path_text(r.provider_ref::jsonb, ?),
                    '-',
                    jsonb_extract_path_text(r.provider_ref::jsonb, ?)
                  )
                  AND (
                    jsonb_extract_path_text(p.provider_ref::jsonb, ?) IS NULL
                    OR jsonb_extract_path_text(r.provider_ref::jsonb, ?) IS NULL
                    OR jsonb_extract_path_text(p.provider_ref::jsonb, ?) IS DISTINCT FROM
                       jsonb_extract_path_text(r.provider_ref::jsonb, ?)
                  )
                )
              )
            """.trimIndent(),
            POI_SOURCE_ID_PREFIX,
            PROVIDER_REF_TXN_LOC_KEY,
            PROVIDER_REF_MAP_ID_KEY,
            POI_PROVIDER_REF_RES_LOC_KEY,
            PROVIDER_REF_RES_LOC_KEY,
            POI_PROVIDER_REF_RES_LOC_KEY,
            PROVIDER_REF_RES_LOC_KEY,
        )

    private fun tenantCondition() =
        TENANT_PAIRS
            .map { (vendor, source) -> RESERVABLES.VENDOR.eq(vendor).and(POIS.SOURCE.eq(source)) }
            .reduce { acc, condition -> acc.or(condition) }

    private fun jsonField(
        column: org.jooq.Field<*>,
        key: String,
    ) = DSL.field(
        "jsonb_extract_path_text(({0})::jsonb, {1})",
        String::class.java,
        column,
        DSL.inline(key),
    )

    private companion object {
        const val ADAPTER_NAME = "AspiraPoiReservableJoiner"

        // Durable reservables.provider_ref keys AspiraResourcesEtl writes;
        // the joiner's contract with the ETL.
        const val PROVIDER_REF_TXN_LOC_KEY = "transactionLocationId"
        const val PROVIDER_REF_MAP_ID_KEY = "mapId"
        const val PROVIDER_REF_RES_LOC_KEY = "resourceLocationId"

        // POI keying for Aspira pins. AspiraJoinByNameEtl writes
        // pois.source_id = "aspira-{txnLoc}-{mapId}". PoiRepo writes
        // provider_ref = {transactionLocationId, mapId, resourceLocationId}.
        const val POI_SOURCE_ID_PREFIX = "aspira-"
        const val POI_PROVIDER_REF_RES_LOC_KEY = "resourceLocationId"
        val TENANT_PAIRS =
            listOf(
                "aspira_wa" to "aspira-wa-pins",
                "aspira_bc" to "aspira-bc-pins",
                "aspira_pc" to "aspira-pc-pins",
            )
    }
}
