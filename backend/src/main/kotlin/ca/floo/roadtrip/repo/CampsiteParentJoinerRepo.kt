package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.domain.CampsiteParentLink
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL

/**
 * Persistence boundary for post-import campsite -> campground reconciliation.
 *
 * Vendor joiner adapters decide which matching strategy to invoke; this repo
 * owns the SQL over campsite/campground catalog tables and the durable
 * reparent write.
 */
class CampsiteParentJoinerRepo(
    private val ctx: DSLContext,
) {
    fun discoverAspiraLinks(): List<CampsiteParentLink> =
        ctx
            .fetch(
                ASPIRA_LINKS_SQL,
                ASPIRA_WA_VENDOR,
                ASPIRA_WA_CAMPGROUND_VENDOR,
                ASPIRA_BC_VENDOR,
                ASPIRA_BC_CAMPGROUND_VENDOR,
                ASPIRA_PC_VENDOR,
                ASPIRA_PC_CAMPGROUND_VENDOR,
                ASPIRA_POI_SOURCE_ID_PREFIX,
                ASPIRA_TXN_LOC_KEY,
                ASPIRA_MAP_ID_KEY,
                ASPIRA_RESOURCE_LOCATION_ID_KEY,
                ASPIRA_PARENT_RESOURCE_LOCATION_ID_KEY,
                ASPIRA_WA_VENDOR,
                ASPIRA_BC_VENDOR,
                ASPIRA_PC_VENDOR,
                ASPIRA_RESOURCE_LOCATION_ID_KEY,
                ASPIRA_WA_CAMPGROUND_VENDOR,
                ASPIRA_BC_CAMPGROUND_VENDOR,
                ASPIRA_PC_CAMPGROUND_VENDOR,
            ).map(::parentLink)

    fun discoverReserveAmericaLinks(): List<CampsiteParentLink> =
        ctx
            .fetch(
                RESERVE_AMERICA_LINKS_SQL,
                RESERVE_AMERICA_PARENT_CONTRACT_KEY,
                RESERVE_AMERICA_PARENT_CONTRACT_KEY,
                RESERVE_AMERICA_PARENT_CAMPGROUND_REF_PREFIX,
                RESERVE_AMERICA_PARENT_PARK_KEY,
                RESERVE_AMERICA_PARENT_PARK_KEY,
                RESERVE_AMERICA_VENDOR_PREFIX,
                RESERVE_AMERICA_ALBERTA_CAMPGROUND_VENDOR,
                RESERVE_AMERICA_NEW_YORK_CAMPGROUND_VENDOR,
                RESERVE_AMERICA_PROVIDER_CONTRACT_KEY,
            ).map(::parentLink)

    fun discoverReserveCaliforniaLinks(): List<CampsiteParentLink> =
        ctx
            .fetch(
                RESERVE_CALIFORNIA_LINKS_SQL,
                RESERVE_CALIFORNIA_PARENT_CAMPGROUND_REF_PREFIX,
                RESERVE_CALIFORNIA_POI_PLACE_KEY,
                RESERVE_CALIFORNIA_PARENT_PLACE_KEY,
                RESERVE_CALIFORNIA_VENDOR,
                RESERVE_CALIFORNIA_PARENT_CAMPGROUND_VENDOR,
            ).map(::parentLink)

    fun reparentCampsites(links: List<CampsiteParentLink>): Int {
        if (links.isEmpty()) return 0
        return ctx.transactionResult { cfg ->
            val tx = DSL.using(cfg)
            links.sumOf { link ->
                tx.execute(
                    REPARENT_CAMPSITE_SQL,
                    link.campgroundId,
                    link.campsiteId,
                    link.campgroundId,
                )
            }
        }
    }

    private fun parentLink(record: Record): CampsiteParentLink =
        CampsiteParentLink(
            campsiteId = record.get("campsite_id", Long::class.java),
            campgroundId = record.get("campground_id", Long::class.java),
        )

    private companion object {
        private const val ASPIRA_POI_SOURCE_ID_PREFIX = "aspira-"
        private const val ASPIRA_TXN_LOC_KEY = "transactionLocationId"
        private const val ASPIRA_MAP_ID_KEY = "mapId"
        private const val ASPIRA_RESOURCE_LOCATION_ID_KEY = "resourceLocationId"
        private const val ASPIRA_PARENT_RESOURCE_LOCATION_ID_KEY = "_parent_aspira_resource_loc"
        private const val ASPIRA_WA_VENDOR = "aspira_wa"
        private const val ASPIRA_BC_VENDOR = "aspira_bc"
        private const val ASPIRA_PC_VENDOR = "aspira_pc"
        private const val ASPIRA_WA_CAMPGROUND_VENDOR = "aspira-wa-pins"
        private const val ASPIRA_BC_CAMPGROUND_VENDOR = "aspira-bc-pins"
        private const val ASPIRA_PC_CAMPGROUND_VENDOR = "aspira-pc-pins"

        private const val RESERVE_AMERICA_VENDOR_PREFIX = "reserveamerica_%"
        private const val RESERVE_AMERICA_ALBERTA_CAMPGROUND_VENDOR = "alberta-provincial"
        private const val RESERVE_AMERICA_NEW_YORK_CAMPGROUND_VENDOR = "new-york-state-parks"
        private const val RESERVE_AMERICA_PARENT_CAMPGROUND_REF_PREFIX = "ra-"
        private const val RESERVE_AMERICA_PARENT_CONTRACT_KEY = "_parent_contract_code"
        private const val RESERVE_AMERICA_PARENT_PARK_KEY = "_parent_park_id"
        private const val RESERVE_AMERICA_PROVIDER_CONTRACT_KEY = "contract_code"

        private const val RESERVE_CALIFORNIA_VENDOR = "reservecalifornia"
        private const val RESERVE_CALIFORNIA_PARENT_CAMPGROUND_VENDOR = "california-state-parks"
        private const val RESERVE_CALIFORNIA_PARENT_CAMPGROUND_REF_PREFIX = "rc-"
        private const val RESERVE_CALIFORNIA_PARENT_PLACE_KEY = "_parent_place_id"
        private const val RESERVE_CALIFORNIA_POI_PLACE_KEY = "place_id"

        private const val REPARENT_CAMPSITE_SQL =
            "UPDATE campsites SET campground_id = ? WHERE id = ? AND campground_id <> ?"

        private val ASPIRA_LINKS_SQL =
            """
            WITH site_candidates AS MATERIALIZED (
              SELECT
                c.id AS campsite_id,
                CASE site_ref.vendor
                  WHEN ? THEN ?
                  WHEN ? THEN ?
                  WHEN ? THEN ?
                END AS parent_vendor,
                concat(
                  ?,
                  jsonb_extract_path_text(site_ref.payload, ?),
                  '-',
                  jsonb_extract_path_text(site_ref.payload, ?)
                ) AS parent_external_id,
                COALESCE(
                  jsonb_extract_path_text(site_ref.payload, ?),
                  jsonb_extract_path_text(c.source_payload, ?)
                ) AS parent_resource_location_id
              FROM campsites c
              JOIN vendor_refs site_ref
                ON site_ref.id = c.primary_vendor_ref_id
              WHERE c.deleted_at IS NULL
                AND site_ref.deleted_at IS NULL
                AND site_ref.entity_type = 'campsite'
                AND c.data_source = site_ref.vendor
                AND site_ref.vendor IN (?, ?, ?)
            ),
            parent_candidates AS MATERIALIZED (
              SELECT
                cg.id AS campground_id,
                campground_ref.vendor,
                campground_ref.external_id,
                jsonb_extract_path_text(campground_ref.payload, ?) AS resource_location_id
              FROM campgrounds cg
              JOIN vendor_refs campground_ref
                ON campground_ref.id = cg.primary_vendor_ref_id
              WHERE cg.deleted_at IS NULL
                AND campground_ref.deleted_at IS NULL
                AND campground_ref.entity_type = 'campground'
                AND cg.data_source = campground_ref.vendor
                AND campground_ref.vendor IN (?, ?, ?)
            )
            SELECT DISTINCT c.campsite_id AS campsite_id, cg.campground_id AS campground_id
            FROM site_candidates c
            JOIN parent_candidates cg
              ON cg.vendor = c.parent_vendor
              AND (
                cg.external_id = c.parent_external_id
                OR (
                  cg.resource_location_id IS NOT NULL
                  AND c.parent_resource_location_id IS NOT NULL
                  AND cg.resource_location_id = c.parent_resource_location_id
                )
              )
            WHERE c.parent_vendor IS NOT NULL
            """.trimIndent()

        private val RESERVE_AMERICA_LINKS_SQL =
            """
            WITH site_candidates AS MATERIALIZED (
              SELECT
                c.id AS campsite_id,
                COALESCE(
                  jsonb_extract_path_text(site_ref.payload, ?),
                  jsonb_extract_path_text(c.source_payload, ?)
                ) AS parent_contract_code,
                concat(
                  ?,
                  COALESCE(
                    jsonb_extract_path_text(site_ref.payload, ?),
                    jsonb_extract_path_text(c.source_payload, ?)
                  )
                ) AS parent_external_id
              FROM campsites c
              JOIN vendor_refs site_ref
                ON site_ref.id = c.primary_vendor_ref_id
              WHERE c.deleted_at IS NULL
                AND site_ref.deleted_at IS NULL
                AND site_ref.entity_type = 'campsite'
                AND c.data_source = site_ref.vendor
                AND site_ref.vendor LIKE ?
            )
            SELECT DISTINCT sc.campsite_id AS campsite_id, cg.id AS campground_id
            FROM site_candidates sc
            JOIN vendor_refs campground_ref
              ON campground_ref.vendor IN (?, ?)
             AND campground_ref.entity_type = 'campground'
             AND campground_ref.external_id = sc.parent_external_id
             AND campground_ref.deleted_at IS NULL
             AND jsonb_extract_path_text(campground_ref.payload, ?) = sc.parent_contract_code
            JOIN campgrounds cg
              ON cg.primary_vendor_ref_id = campground_ref.id
             AND cg.data_source = campground_ref.vendor
             AND cg.deleted_at IS NULL
            WHERE sc.parent_contract_code IS NOT NULL
            """.trimIndent()

        private val RESERVE_CALIFORNIA_LINKS_SQL =
            """
            WITH site_candidates AS MATERIALIZED (
              SELECT
                c.id AS campsite_id,
                concat(
                  ?,
                  COALESCE(
                    jsonb_extract_path_text(site_ref.payload, ?),
                    jsonb_extract_path_text(c.source_payload, ?)
                  )
                ) AS parent_external_id
              FROM campsites c
              JOIN vendor_refs site_ref
                ON site_ref.id = c.primary_vendor_ref_id
              WHERE c.deleted_at IS NULL
                AND site_ref.deleted_at IS NULL
                AND site_ref.entity_type = 'campsite'
                AND c.data_source = site_ref.vendor
                AND site_ref.vendor = ?
            )
            SELECT DISTINCT sc.campsite_id AS campsite_id, cg.id AS campground_id
            FROM site_candidates sc
            JOIN vendor_refs campground_ref
              ON campground_ref.vendor = ?
             AND campground_ref.entity_type = 'campground'
             AND campground_ref.external_id = sc.parent_external_id
             AND campground_ref.deleted_at IS NULL
            JOIN campgrounds cg
              ON cg.primary_vendor_ref_id = campground_ref.id
             AND cg.data_source = campground_ref.vendor
             AND cg.deleted_at IS NULL
            """.trimIndent()
    }
}
