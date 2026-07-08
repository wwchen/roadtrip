package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.models.domain.ReservableType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.jooq.Record

/**
 * Temporary read adapter over the canonical `campsites` catalog.
 *
 * The old `reservables` and `reservable_pois` tables are gone. Existing
 * availability services still consume the `Reservable` domain model until the
 * next slice renames that service surface to `Campsite`; this adapter keeps
 * those internal reads pointed at `campsites`, `campsite_vendor_refs`, and
 * `poi_campgrounds` without recreating the retired tables or public RID API.
 */
class ReservableRepo(
    private val ctx: DSLContext,
) {
    data class ImportResult(
        val runId: Long,
        val seenCount: Int,
        val sweptCount: Int,
    )

    data class Input(
        val rid: ReservableId,
        val name: String?,
        val loop: String?,
        val siteType: String?,
        val raw: JsonElement?,
        val tags: JsonElement? = null,
        val providerRef: JsonElement? = null,
    )

    data class LinkInput(
        val reservableId: Long,
        val poiId: Long,
    )

    data class SearchFilters(
        val rids: List<ReservableId> = emptyList(),
        val types: List<ReservableType> = emptyList(),
        val vendors: List<String> = emptyList(),
        val vendorIds: List<String> = emptyList(),
        val names: List<String> = emptyList(),
        val loops: List<String> = emptyList(),
        val siteTypes: List<String> = emptyList(),
        val rawContainsJson: List<String> = emptyList(),
        val tagsContainsJson: List<String> = emptyList(),
    )

    fun findById(id: Long): Reservable? =
        ctx
            .fetchOne(
                "$BASE_SELECT WHERE c.id = ? AND c.deleted_at IS NULL",
                id,
            )?.let(::fromRecord)

    fun findByRid(rid: ReservableId): Reservable? =
        ctx
            .fetchOne(
                """
                $BASE_SELECT
                JOIN campsite_vendor_refs cvr_lookup
                  ON cvr_lookup.campsite_id = c.id
                JOIN vendor_refs vr_lookup
                  ON vr_lookup.id = cvr_lookup.vendor_ref_id
                WHERE vr_lookup.entity_type = 'campsite'
                  AND vr_lookup.vendor = ?
                  AND vr_lookup.external_id = ?
                  AND vr_lookup.deleted_at IS NULL
                  AND c.deleted_at IS NULL
                """.trimIndent(),
                rid.vendor,
                rid.vendorId,
            )?.let(::fromRecord)

    fun findAll(): List<Reservable> =
        ctx
            .fetch(
                "$BASE_SELECT WHERE c.deleted_at IS NULL ORDER BY c.id",
            ).map(::fromRecord)

    fun findByPoi(
        poiId: Long,
        type: ReservableType? = null,
    ): List<Reservable> {
        if (type != null && type != ReservableType.SITE) return emptyList()
        return ctx
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
    }

    fun countSearch(filters: SearchFilters): Int = search(filters, limit = 1, offset = 0).size

    fun search(
        filters: SearchFilters,
        limit: Int,
        offset: Int,
    ): List<Reservable> {
        val rid = filters.rids.singleOrNull()
        if (rid != null) return findByRid(rid)?.let(::listOf).orEmpty()
        return ctx
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
    }

    fun poiIdsForReservable(campsiteId: Long): List<Long> =
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

    fun poiIdsForReservables(campsiteIds: Collection<Long>): Map<Long, List<Long>> {
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

    fun upsert(
        input: Input,
        source: String = input.rid.vendor,
        runId: Long? = null,
    ): Long = unsupportedWrite("upsert")

    fun runImport(
        source: String,
        inputs: List<Input>,
    ): ImportResult = unsupportedWrite("runImport")

    fun linkToPoi(
        reservableId: Long,
        poiId: Long,
    ): Int = unsupportedWrite("linkToPoi")

    fun linkToPois(inputs: List<LinkInput>): Int = unsupportedWrite("linkToPois")

    fun unlinkFromPoi(
        reservableId: Long,
        poiId: Long,
    ): Int = unsupportedWrite("unlinkFromPoi")

    internal fun fromRecord(r: Record): Reservable {
        val vendor = r.get("vendor", String::class.java) ?: CANONICAL_VENDOR
        val externalId = r.get("external_id", String::class.java) ?: r.get("id", Long::class.java).toString()
        return Reservable(
            id = r.get("id", Long::class.java),
            rid = ReservableId(ReservableType.SITE, vendor, externalId),
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

    private fun unsupportedWrite(operation: String): Nothing =
        throw UnsupportedOperationException(
            "ReservableRepo.$operation is retired with the reservables table; write canonical campsites instead",
        )

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
              ORDER BY cvr.is_primary DESC, cvr.vendor_ref_id ASC
              LIMIT 1
            ) primary_ref ON true
            """.trimIndent()
    }
}
