package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.domain.Campsite
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.jooq.Record

/**
 * Persistence boundary for canonical campsite catalog reads.
 *
 * Availability services still consume the `Campsite` domain model at the
 * provider boundary, so this repo maps canonical `campsites` rows into that
 * internal shape while all persistence stays pointed at the campsite catalog.
 */
class CampsiteRepo(
    private val ctx: DSLContext,
) {
    data class SearchFilters(
        val vendors: List<String> = emptyList(),
        val vendorIds: List<String> = emptyList(),
        val names: List<String> = emptyList(),
        val loops: List<String> = emptyList(),
        val siteTypes: List<String> = emptyList(),
        val rawContainsJson: List<String> = emptyList(),
        val tagsContainsJson: List<String> = emptyList(),
    )

    fun findById(id: Long): Campsite? =
        ctx
            .fetchOne(
                "$BASE_SELECT WHERE c.id = ? AND c.deleted_at IS NULL",
                id,
            )?.let(::fromRecord)

    fun findAll(): List<Campsite> =
        ctx
            .fetch(
                "$BASE_SELECT WHERE c.deleted_at IS NULL ORDER BY c.id",
            ).map(::fromRecord)

    fun findByPoi(poiId: Long): List<Campsite> =
        ctx
            .fetch(
                """
                $BASE_SELECT
                JOIN poi_campgrounds pc
                  ON pc.campground_id = c.campground_id
                JOIN pois p
                  ON p.id = pc.poi_id
                WHERE pc.poi_id = ?
                  AND c.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                ORDER BY c.loop_name NULLS LAST, c.name, c.id
                """.trimIndent(),
                poiId,
            ).map(::fromRecord)

    fun countSearch(filters: SearchFilters): Int = search(filters, limit = 1, offset = 0).size

    fun search(
        filters: SearchFilters,
        limit: Int,
        offset: Int,
    ): List<Campsite> =
        ctx
            .fetch(
                """
                $BASE_SELECT
                WHERE c.deleted_at IS NULL
                ORDER BY c.name, c.id
                LIMIT ? OFFSET ?
                """.trimIndent(),
                limit.coerceIn(1, MAX_SEARCH_LIMIT),
                offset.coerceAtLeast(0),
            ).map(::fromRecord)

    fun poiIdsForCampsite(campsiteId: Long): List<Long> =
        ctx
            .fetch(
                """
                SELECT pc.poi_id
                FROM campsites c
                JOIN poi_campgrounds pc
                  ON pc.campground_id = c.campground_id
                JOIN pois p
                  ON p.id = pc.poi_id
                WHERE c.id = ?
                  AND c.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                ORDER BY pc.poi_id
                """.trimIndent(),
                campsiteId,
            ).map { it.get("poi_id", Long::class.java) }

    fun poiIdsForCampsites(campsiteIds: Collection<Long>): Map<Long, List<Long>> {
        if (campsiteIds.isEmpty()) return emptyMap()
        return ctx
            .fetch(
                """
                SELECT c.id AS campsite_id, pc.poi_id
                FROM campsites c
                JOIN poi_campgrounds pc
                  ON pc.campground_id = c.campground_id
                JOIN pois p
                  ON p.id = pc.poi_id
                WHERE c.id = ANY(?::bigint[])
                  AND c.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                ORDER BY c.id, pc.poi_id
                """.trimIndent(),
                campsiteIds.toTypedArray(),
            ).groupBy(
                { it.get("campsite_id", Long::class.java) },
                { it.get("poi_id", Long::class.java) },
            )
    }

    internal fun fromRecord(r: Record): Campsite {
        val vendor = r.get("vendor", String::class.java) ?: CANONICAL_VENDOR
        val externalId = r.get("external_id", String::class.java) ?: r.get("id", Long::class.java).toString()
        return Campsite(
            id = r.get("id", Long::class.java),
            vendor = vendor,
            vendorId = externalId,
            name = r.get("name", String::class.java),
            loop = r.get("loop_name", String::class.java),
            siteType = r.get("kind", String::class.java),
            raw = parseJsonElement(r.get("source_payload_text", String::class.java)),
            tags = parseJsonElement(r.get("tags_text", String::class.java)),
            providerRef = parseJsonElement(r.get("provider_ref_text", String::class.java)),
        )
    }

    private fun parseJsonElement(raw: String?): JsonElement? =
        raw
            ?.takeIf { it != "null" }
            ?.let { Json.parseToJsonElement(it) }

    private companion object {
        private const val CANONICAL_VENDOR = "canonical"
        private const val MAX_SEARCH_LIMIT = 500

        private val BASE_SELECT =
            """
            SELECT
              c.id,
              c.name,
              c.loop_name,
              c.kind,
              c.source_payload::text AS source_payload_text,
              jsonb_build_object(
                'kind_listed', c.kind_listed,
                'equipment', c.equipment,
                'max_people', c.max_people,
                'max_cars', c.max_cars,
                'driveway_length', c.driveway_length,
                'max_rv_length', c.max_rv_length,
                'max_trailer_length', c.max_trailer_length,
                'water_hookups', c.water_hookups,
                'electric_hookups', c.electric_hookups,
                'sewer_hookups', c.sewer_hookups,
                'firepit', c.firepit,
                'picnic_table', c.picnic_table,
                'ada_accessible', c.ada_accessible,
                'pull_through', c.pull_through
              )::text AS tags_text,
              primary_ref.vendor,
              primary_ref.external_id,
              primary_ref.payload::text AS provider_ref_text
            FROM campsites c
            LEFT JOIN LATERAL (
              SELECT vr.vendor, vr.external_id, vr.payload
              FROM campsite_vendor_refs cvr
              JOIN vendor_refs vr
                ON vr.id = cvr.vendor_ref_id
              WHERE cvr.campsite_id = c.id
                AND vr.entity_type = 'campsite'
                AND vr.deleted_at IS NULL
              ORDER BY
                CASE WHEN ${providerRefShapeSql("vr.payload")} THEN 1 ELSE 0 END DESC,
                cvr.vendor_ref_id ASC
              LIMIT 1
            ) primary_ref ON true
            """.trimIndent()
    }
}
