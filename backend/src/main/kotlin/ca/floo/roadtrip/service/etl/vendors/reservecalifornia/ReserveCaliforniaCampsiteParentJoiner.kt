package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.service.etl.framework.CampsiteParentJoiner
import ca.floo.roadtrip.service.etl.framework.JoinerCtx

/**
 * Canonicalized ReserveCalifornia campsite parent resolver.
 */
class ReserveCaliforniaCampsiteParentJoiner : CampsiteParentJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<CampsiteParentJoiner.Link> =
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
                  AND site_ref.vendor = ?
                  AND campground_ref.entity_type = 'campground'
                  AND campground_ref.vendor = ?
                  AND jsonb_extract_path_text(campground_ref.payload, ?) =
                    COALESCE(
                      jsonb_extract_path_text(site_ref.payload, ?),
                      jsonb_extract_path_text(c.source_payload, ?)
                    )
                """.trimIndent(),
                VENDOR,
                PARENT_CAMPGROUND_VENDOR,
                POI_PLACE_KEY,
                PARENT_PLACE_KEY,
                PARENT_PLACE_KEY,
            ).map { record ->
                CampsiteParentJoiner.Link(
                    campsiteId = record.get("campsite_id", Long::class.java),
                    campgroundId = record.get("campground_id", Long::class.java),
                )
            }

    private companion object {
        const val ADAPTER_NAME = "ReserveCaliforniaCampsiteParentJoiner"
        const val VENDOR = "reservecalifornia"
        const val PARENT_CAMPGROUND_VENDOR = "california-state-parks"
        const val PARENT_PLACE_KEY = "_parent_place_id"
        const val POI_PLACE_KEY = "place_id"
    }
}
