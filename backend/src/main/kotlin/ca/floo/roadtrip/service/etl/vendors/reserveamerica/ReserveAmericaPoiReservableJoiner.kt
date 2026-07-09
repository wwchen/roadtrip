package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.service.etl.framework.JoinerCtx
import ca.floo.roadtrip.service.etl.framework.PoiReservableJoiner

/**
 * Canonicalized ReserveAmerica campsite parent resolver.
 *
 * Matches per-site vendor refs to parent campground vendor refs by
 * `(contract_code, park_id)`, using the same tenant keys as the availability
 * adapter.
 */
class ReserveAmericaPoiReservableJoiner : PoiReservableJoiner {
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
                  AND site_ref.vendor LIKE ?
                  AND campground_ref.entity_type = 'campground'
                  AND campground_ref.vendor IN (?, ?)
                  AND COALESCE(
                    jsonb_extract_path_text(site_ref.payload, ?),
                    jsonb_extract_path_text(c.source_payload, ?)
                  ) = jsonb_extract_path_text(campground_ref.payload, ?)
                  AND (
                    campground_ref.external_id =
                      concat(
                        ?,
                        COALESCE(
                          jsonb_extract_path_text(site_ref.payload, ?),
                          jsonb_extract_path_text(c.source_payload, ?)
                        )
                      )
                    OR COALESCE(
                      jsonb_extract_path_text(site_ref.payload, ?),
                      jsonb_extract_path_text(c.source_payload, ?)
                    ) = jsonb_extract_path_text(campground_ref.payload, ?)
                  )
                """.trimIndent(),
                VENDOR_PREFIX,
                ALBERTA_CAMPGROUND_VENDOR,
                NEW_YORK_CAMPGROUND_VENDOR,
                PARENT_CONTRACT_KEY,
                PARENT_CONTRACT_KEY,
                PROVIDER_CONTRACT_KEY,
                PARENT_CAMPGROUND_REF_PREFIX,
                PARENT_PARK_KEY,
                PARENT_PARK_KEY,
                PARENT_PARK_KEY,
                PARENT_PARK_KEY,
                PROVIDER_PARK_KEY,
            ).map { record ->
                PoiReservableJoiner.Link(
                    campsiteId = record.get("campsite_id", Long::class.java),
                    campgroundId = record.get("campground_id", Long::class.java),
                )
            }

    private companion object {
        const val ADAPTER_NAME = "ReserveAmericaPoiReservableJoiner"
        const val VENDOR_PREFIX = "reserveamerica_%"
        const val ALBERTA_CAMPGROUND_VENDOR = "alberta-provincial"
        const val NEW_YORK_CAMPGROUND_VENDOR = "new-york-state-parks"
        const val PARENT_CAMPGROUND_REF_PREFIX = "ra-"
        const val PARENT_CONTRACT_KEY = "_parent_contract_code"
        const val PARENT_PARK_KEY = "_parent_park_id"
        const val PROVIDER_CONTRACT_KEY = "contract_code"
        const val PROVIDER_PARK_KEY = "park_id"
    }
}
