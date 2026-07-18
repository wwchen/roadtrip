package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.AspiraCampgroundParentCandidate
import ca.floo.roadtrip.model.domain.AspiraCampsiteParentCandidate
import ca.floo.roadtrip.model.domain.CampsiteParentLink
import ca.floo.roadtrip.model.domain.ReserveAmericaCampgroundParentCandidate
import ca.floo.roadtrip.model.domain.ReserveAmericaCampsiteParentCandidate
import ca.floo.roadtrip.model.domain.ReserveCaliforniaCampgroundParentCandidate
import ca.floo.roadtrip.model.domain.ReserveCaliforniaCampsiteParentCandidate
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL

class CampsiteParentJoinerRepo(
    private val ctx: DSLContext,
) {
    fun fetchAspiraCampsiteParentCandidates(): List<AspiraCampsiteParentCandidate> =
        ctx
            .fetch(
                aspiraCampsiteParentCandidatesSql,
                ASPIRA_TXN_LOC_KEY,
                ASPIRA_MAP_ID_KEY,
                ASPIRA_RESOURCE_LOCATION_ID_KEY,
                ASPIRA_PARENT_RESOURCE_LOCATION_ID_KEY,
                ASPIRA_WA_VENDOR,
                ASPIRA_BC_VENDOR,
                ASPIRA_PC_VENDOR,
            ).map(::aspiraCampsiteParentCandidate)

    fun fetchAspiraCampgroundParentCandidates(): List<AspiraCampgroundParentCandidate> =
        ctx
            .fetch(
                aspiraCampgroundParentCandidatesSql,
                ASPIRA_RESOURCE_LOCATION_ID_KEY,
                ASPIRA_WA_CAMPGROUND_VENDOR,
                ASPIRA_BC_CAMPGROUND_VENDOR,
                ASPIRA_PC_CAMPGROUND_VENDOR,
            ).map(::aspiraCampgroundParentCandidate)

    fun fetchReserveAmericaCampsiteParentCandidates(): List<ReserveAmericaCampsiteParentCandidate> =
        ctx
            .fetch(
                reserveAmericaCampsiteParentCandidatesSql,
                RESERVE_AMERICA_PARENT_CONTRACT_KEY,
                RESERVE_AMERICA_PARENT_CONTRACT_KEY,
                RESERVE_AMERICA_PARENT_PARK_KEY,
                RESERVE_AMERICA_PARENT_PARK_KEY,
                RESERVE_AMERICA_VENDOR_PREFIX,
            ).map(::reserveAmericaCampsiteParentCandidate)

    fun fetchReserveAmericaCampgroundParentCandidates(): List<ReserveAmericaCampgroundParentCandidate> =
        ctx
            .fetch(
                reserveAmericaCampgroundParentCandidatesSql,
                RESERVE_AMERICA_PROVIDER_CONTRACT_KEY,
                RESERVE_AMERICA_ALBERTA_CAMPGROUND_VENDOR,
                RESERVE_AMERICA_NEW_YORK_CAMPGROUND_VENDOR,
            ).map(::reserveAmericaCampgroundParentCandidate)

    fun fetchReserveCaliforniaCampsiteParentCandidates(): List<ReserveCaliforniaCampsiteParentCandidate> =
        ctx
            .fetch(
                reserveCaliforniaCampsiteParentCandidatesSql,
                RESERVE_CALIFORNIA_POI_PLACE_KEY,
                RESERVE_CALIFORNIA_PARENT_PLACE_KEY,
                RESERVE_CALIFORNIA_VENDOR,
            ).map(::reserveCaliforniaCampsiteParentCandidate)

    fun fetchReserveCaliforniaCampgroundParentCandidates(): List<ReserveCaliforniaCampgroundParentCandidate> =
        ctx
            .fetch(
                reserveCaliforniaCampgroundParentCandidatesSql,
                RESERVE_CALIFORNIA_PARENT_CAMPGROUND_VENDOR,
            ).map(::reserveCaliforniaCampgroundParentCandidate)

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

    private fun aspiraCampsiteParentCandidate(record: Record): AspiraCampsiteParentCandidate =
        AspiraCampsiteParentCandidate(
            campsiteId = record.get("campsite_id", Long::class.java),
            vendor = record.get("vendor", String::class.java),
            transactionLocationId = record.get("transaction_location_id", String::class.java),
            mapId = record.get("map_id", String::class.java),
            vendorRefResourceLocationId = record.get("vendor_ref_resource_location_id", String::class.java),
            sourceParentResourceLocationId = record.get("source_parent_resource_location_id", String::class.java),
        )

    private fun aspiraCampgroundParentCandidate(record: Record): AspiraCampgroundParentCandidate =
        AspiraCampgroundParentCandidate(
            campgroundId = record.get("campground_id", Long::class.java),
            vendor = record.get("vendor", String::class.java),
            externalId = record.get("external_id", String::class.java),
            resourceLocationId = record.get("resource_location_id", String::class.java),
        )

    private fun reserveAmericaCampsiteParentCandidate(record: Record): ReserveAmericaCampsiteParentCandidate =
        ReserveAmericaCampsiteParentCandidate(
            campsiteId = record.get("campsite_id", Long::class.java),
            vendorRefParentContractCode = record.get("vendor_ref_parent_contract_code", String::class.java),
            sourceParentContractCode = record.get("source_parent_contract_code", String::class.java),
            vendorRefParentParkId = record.get("vendor_ref_parent_park_id", String::class.java),
            sourceParentParkId = record.get("source_parent_park_id", String::class.java),
        )

    private fun reserveAmericaCampgroundParentCandidate(record: Record): ReserveAmericaCampgroundParentCandidate =
        ReserveAmericaCampgroundParentCandidate(
            campgroundId = record.get("campground_id", Long::class.java),
            externalId = record.get("external_id", String::class.java),
            contractCode = record.get("contract_code", String::class.java),
        )

    private fun reserveCaliforniaCampsiteParentCandidate(record: Record): ReserveCaliforniaCampsiteParentCandidate =
        ReserveCaliforniaCampsiteParentCandidate(
            campsiteId = record.get("campsite_id", Long::class.java),
            vendorRefPlaceId = record.get("vendor_ref_place_id", String::class.java),
            sourceParentPlaceId = record.get("source_parent_place_id", String::class.java),
        )

    private fun reserveCaliforniaCampgroundParentCandidate(record: Record): ReserveCaliforniaCampgroundParentCandidate =
        ReserveCaliforniaCampgroundParentCandidate(
            campgroundId = record.get("campground_id", Long::class.java),
            externalId = record.get("external_id", String::class.java),
        )

    private companion object {
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
        private const val RESERVE_AMERICA_PARENT_CONTRACT_KEY = "_parent_contract_code"
        private const val RESERVE_AMERICA_PARENT_PARK_KEY = "_parent_park_id"
        private const val RESERVE_AMERICA_PROVIDER_CONTRACT_KEY = "contract_code"

        private const val RESERVE_CALIFORNIA_VENDOR = "reservecalifornia"
        private const val RESERVE_CALIFORNIA_PARENT_CAMPGROUND_VENDOR = "california-state-parks"
        private const val RESERVE_CALIFORNIA_PARENT_PLACE_KEY = "_parent_place_id"
        private const val RESERVE_CALIFORNIA_POI_PLACE_KEY = "place_id"

        private const val REPARENT_CAMPSITE_SQL =
            "UPDATE campsites SET campground_id = ? WHERE id = ? AND campground_id <> ?"

        private val aspiraCampsiteParentCandidatesSql =
            """
            SELECT
              c.id AS campsite_id,
              c.data_provider AS vendor,
              jsonb_extract_path_text(c.source_payload, ?) AS transaction_location_id,
              jsonb_extract_path_text(c.source_payload, ?) AS map_id,
              jsonb_extract_path_text(c.source_payload, ?) AS vendor_ref_resource_location_id,
              jsonb_extract_path_text(c.source_payload, ?) AS source_parent_resource_location_id
            FROM campsites c
            WHERE c.deleted_at IS NULL
              AND c.data_provider IN (?, ?, ?)
            """.trimIndent()

        private val aspiraCampgroundParentCandidatesSql =
            """
            SELECT
              cg.id AS campground_id,
              cg.data_provider AS vendor,
              cg.data_provider_ref AS external_id,
              jsonb_extract_path_text(cg.source_payload, ?) AS resource_location_id
            FROM campgrounds cg
            WHERE cg.deleted_at IS NULL
              AND cg.data_provider IN (?, ?, ?)
            """.trimIndent()

        private val reserveAmericaCampsiteParentCandidatesSql =
            """
            SELECT
              c.id AS campsite_id,
              jsonb_extract_path_text(c.source_payload, ?) AS vendor_ref_parent_contract_code,
              jsonb_extract_path_text(c.source_payload, ?) AS source_parent_contract_code,
              jsonb_extract_path_text(c.source_payload, ?) AS vendor_ref_parent_park_id,
              jsonb_extract_path_text(c.source_payload, ?) AS source_parent_park_id
            FROM campsites c
            WHERE c.deleted_at IS NULL
              AND c.data_provider LIKE ?
            """.trimIndent()

        private val reserveAmericaCampgroundParentCandidatesSql =
            """
            SELECT
              cg.id AS campground_id,
              cg.data_provider_ref AS external_id,
              jsonb_extract_path_text(cg.source_payload, ?) AS contract_code
            FROM campgrounds cg
            WHERE cg.deleted_at IS NULL
              AND cg.data_provider IN (?, ?)
            """.trimIndent()

        private val reserveCaliforniaCampsiteParentCandidatesSql =
            """
            SELECT
              c.id AS campsite_id,
              jsonb_extract_path_text(c.source_payload, ?) AS vendor_ref_place_id,
              jsonb_extract_path_text(c.source_payload, ?) AS source_parent_place_id
            FROM campsites c
            WHERE c.deleted_at IS NULL
              AND c.data_provider = ?
            """.trimIndent()

        private val reserveCaliforniaCampgroundParentCandidatesSql =
            """
            SELECT
              cg.id AS campground_id,
              cg.data_provider_ref AS external_id
            FROM campgrounds cg
            WHERE cg.deleted_at IS NULL
              AND cg.data_provider = ?
            """.trimIndent()
    }
}
