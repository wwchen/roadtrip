package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.service.etl.framework.JoinerCtx
import ca.floo.roadtrip.service.etl.framework.PoiReservableJoiner

/**
 * Canonicalized Aspira campsite parent resolver.
 *
 * Preserves the old tenant-specific matching rules, but targets
 * `campsites`/`campgrounds` vendor refs instead of the removed
 * `reservables`/`reservable_pois` tables.
 */
class AspiraPoiReservableJoiner : PoiReservableJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<PoiReservableJoiner.Link> =
        ctx.ctx
            .fetch(
                """
                SELECT DISTINCT c.id AS campsite_id, cg.id AS campground_id
                FROM campsites c
                JOIN campsite_vendor_refs cvr
                  ON cvr.campsite_id = c.id
                JOIN vendor_refs site_ref
                  ON site_ref.id = cvr.vendor_ref_id
                JOIN campground_vendor_refs cgvr
                  ON cgvr.is_primary
                JOIN vendor_refs campground_ref
                  ON campground_ref.id = cgvr.vendor_ref_id
                JOIN campgrounds cg
                  ON cg.id = cgvr.campground_id
                WHERE c.deleted_at IS NULL
                  AND cg.deleted_at IS NULL
                  AND site_ref.deleted_at IS NULL
                  AND campground_ref.deleted_at IS NULL
                  AND site_ref.entity_type = 'campsite'
                  AND campground_ref.entity_type = 'campground'
                  AND (
                    (site_ref.vendor = ? AND campground_ref.vendor = ?)
                    OR (site_ref.vendor = ? AND campground_ref.vendor = ?)
                    OR (site_ref.vendor = ? AND campground_ref.vendor = ?)
                  )
                  AND (
                    campground_ref.external_id = concat(
                      ?,
                      jsonb_extract_path_text(site_ref.payload, ?),
                      '-',
                      jsonb_extract_path_text(site_ref.payload, ?)
                    )
                    OR (
                      jsonb_extract_path_text(campground_ref.payload, ?) IS NOT NULL
                      AND COALESCE(
                        jsonb_extract_path_text(site_ref.payload, ?),
                        jsonb_extract_path_text(c.source_payload, ?)
                      ) IS NOT NULL
                      AND jsonb_extract_path_text(campground_ref.payload, ?) =
                        COALESCE(
                          jsonb_extract_path_text(site_ref.payload, ?),
                          jsonb_extract_path_text(c.source_payload, ?)
                        )
                    )
                  )
                """.trimIndent(),
                ASPIRA_WA_VENDOR,
                ASPIRA_WA_CAMPGROUND_VENDOR,
                ASPIRA_BC_VENDOR,
                ASPIRA_BC_CAMPGROUND_VENDOR,
                ASPIRA_PC_VENDOR,
                ASPIRA_PC_CAMPGROUND_VENDOR,
                POI_SOURCE_ID_PREFIX,
                TXN_LOC_KEY,
                MAP_ID_KEY,
                RESOURCE_LOCATION_ID_KEY,
                RESOURCE_LOCATION_ID_KEY,
                PARENT_RESOURCE_LOCATION_ID_KEY,
                RESOURCE_LOCATION_ID_KEY,
                RESOURCE_LOCATION_ID_KEY,
                PARENT_RESOURCE_LOCATION_ID_KEY,
            ).map { record ->
                PoiReservableJoiner.Link(
                    campsiteId = record.get("campsite_id", Long::class.java),
                    campgroundId = record.get("campground_id", Long::class.java),
                )
            }

    private companion object {
        const val ADAPTER_NAME = "AspiraPoiReservableJoiner"
        const val POI_SOURCE_ID_PREFIX = "aspira-"
        const val TXN_LOC_KEY = "transactionLocationId"
        const val MAP_ID_KEY = "mapId"
        const val RESOURCE_LOCATION_ID_KEY = "resourceLocationId"
        const val PARENT_RESOURCE_LOCATION_ID_KEY = "_parent_aspira_resource_loc"

        const val ASPIRA_WA_VENDOR = "aspira_wa"
        const val ASPIRA_BC_VENDOR = "aspira_bc"
        const val ASPIRA_PC_VENDOR = "aspira_pc"
        const val ASPIRA_WA_CAMPGROUND_VENDOR = "aspira-wa-pins"
        const val ASPIRA_BC_CAMPGROUND_VENDOR = "aspira-bc-pins"
        const val ASPIRA_PC_CAMPGROUND_VENDOR = "aspira-pc-pins"
    }
}
