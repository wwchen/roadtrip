package ca.floo.roadtrip.service.etl.vendors.recgov

import ca.floo.roadtrip.service.etl.framework.CampsiteParentJoiner
import ca.floo.roadtrip.service.etl.framework.JoinerCtx

/**
 * Canonicalized rec.gov campsite parent resolver.
 *
 * The old adapter linked `reservables` to `pois`; with the canonical catalog it
 * resolves rec.gov campsite rows to campground rows through vendor refs.
 */
class RecgovCampsiteParentJoiner : CampsiteParentJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<CampsiteParentJoiner.Link> =
        ctx.ctx
            .fetch(
                """
                SELECT c.id AS campsite_id, cg.id AS campground_id
                FROM campsites c
                JOIN campsite_vendor_refs cvr
                  ON cvr.campsite_id = c.id
                JOIN vendor_refs site_ref
                  ON site_ref.id = cvr.vendor_ref_id
                JOIN campground_vendor_refs cgvr
                  ON TRUE
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
                  AND campground_ref.external_id = concat(
                    ?,
                    COALESCE(
                      jsonb_extract_path_text(c.source_payload, ?),
                      jsonb_extract_path_text(site_ref.payload, ?)
                    )
                  )
                """.trimIndent(),
                VENDOR,
                PARENT_CAMPGROUND_VENDOR,
                PARENT_CAMPGROUND_REF_PREFIX,
                PARENT_FACILITY_KEY,
                PARENT_FACILITY_KEY,
            ).map { record ->
                CampsiteParentJoiner.Link(
                    campsiteId = record.get("campsite_id", Long::class.java),
                    campgroundId = record.get("campground_id", Long::class.java),
                )
            }

    private companion object {
        const val ADAPTER_NAME = "RecgovCampsiteParentJoiner"
        const val VENDOR = "recgov"
        const val PARENT_CAMPGROUND_VENDOR = "federal-campgrounds"
        const val PARENT_CAMPGROUND_REF_PREFIX = "recgov-"
        const val PARENT_FACILITY_KEY = "_parent_facility_id"
    }
}
