package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.domain.Campsite
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.models.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.models.domain.CatalogUpsertResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Persistence boundary for canonical campsite catalog reads and writes.
 *
 * Availability services consume [CampsiteAvailabilityTarget], a provider-facing
 * projection that includes vendor refs. The [Campsite] model remains a direct
 * representation of the `campsites` table row.
 */
class CampsiteRepo(
    private val ctx: DSLContext,
) {
    private val importRuns = ImportRunRepo(ctx)
    private val vendorRefs = VendorRefRepo(ctx)

    data class SearchFilters(
        val vendors: List<String> = emptyList(),
        val vendorIds: List<String> = emptyList(),
        val names: List<String> = emptyList(),
        val loops: List<String> = emptyList(),
        val siteTypes: List<String> = emptyList(),
        val rawContainsJson: List<String> = emptyList(),
        val tagsContainsJson: List<String> = emptyList(),
    )

    fun upsertCampsites(
        records: List<CampsiteUpsertCandidate>,
        source: String,
    ): CatalogUpsertResult {
        val runId = importRuns.start(source)
        try {
            val (upserted, skipped) =
                ctx.transactionResult { cfg ->
                    val tx = CampsiteRepo(DSL.using(cfg))
                    tx.bulkUpsertCampsitesTx(records)
                }
            importRuns.complete(runId, records.size)
            return CatalogUpsertResult(
                runId = runId,
                seenCount = records.size,
                upsertedCount = upserted,
                skippedCount = skipped,
            )
        } catch (e: Throwable) {
            importRuns.fail(runId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun bulkUpsertCampsitesTx(records: List<CampsiteUpsertCandidate>): Pair<Int, Int> {
        if (records.isEmpty()) return 0 to 0

        // Records without a declared parent are skipped upfront.
        val withParent = records.filter { it.parentVendor != null && it.parentVendorRefId != null }
        val skippedForMissingParent = records.size - withParent.size

        val parentMap = HashMap<ParentKey, Long>()
        val parentKeys = withParent.map { ParentKey(it.parentVendor!!, it.parentVendorRefId!!) }.distinct()
        parentMap.putAll(loadParentCampgroundMap(parentKeys))

        val withResolvedParent =
            withParent.filter {
                ParentKey(it.parentVendor!!, it.parentVendorRefId!!) in parentMap
            }
        val skippedForUnresolvedParent = withParent.size - withResolvedParent.size
        val totalSkipped = skippedForMissingParent + skippedForUnresolvedParent

        if (withResolvedParent.isEmpty()) return 0 to totalSkipped

        val vendorRefSpecs = mutableListOf<CatalogVendorRefSpec>()
        for (record in withResolvedParent) {
            vendorRefSpecs +=
                CatalogVendorRefSpec(
                    vendor = record.vendor,
                    entityType = CAMPSITE_ENTITY,
                    externalId = record.vendorRefId,
                    externalName = record.name,
                    sourceUrl = record.reservationUrl,
                    payload = record.vendorRefPayload,
                )
            for (additionalRef in record.additionalVendorRefs) {
                vendorRefSpecs +=
                    CatalogVendorRefSpec(
                        vendor = additionalRef.vendor,
                        entityType = CAMPSITE_ENTITY,
                        externalId = additionalRef.vendorRefId,
                        externalName = record.name,
                        sourceUrl = additionalRef.sourceUrl,
                        payload = additionalRef.payload,
                    )
            }
        }
        val vendorRefIds = vendorRefs.bulkUpsertVendorRefs(vendorRefSpecs)

        val campsiteRows =
            withResolvedParent.map { record ->
                val primaryRefId = vendorRefIds.getValue(CatalogVendorRefKey(record.vendor, CAMPSITE_ENTITY, record.vendorRefId))
                val campgroundId = parentMap.getValue(ParentKey(record.parentVendor!!, record.parentVendorRefId!!))
                CampsiteBulkRow(
                    record = record,
                    campgroundId = campgroundId,
                    primaryVendorRefId = primaryRefId,
                )
            }
        val campsiteIdByPrimaryRef = bulkUpsertCampsiteRows(campsiteRows)

        val links = mutableListOf<Pair<Long, Long>>()
        for (record in withResolvedParent) {
            val primaryRefId = vendorRefIds.getValue(CatalogVendorRefKey(record.vendor, CAMPSITE_ENTITY, record.vendorRefId))
            val campsiteId = campsiteIdByPrimaryRef.getValue(primaryRefId)
            links += campsiteId to primaryRefId
            for (additionalRef in record.additionalVendorRefs) {
                links +=
                    campsiteId to
                    vendorRefIds.getValue(CatalogVendorRefKey(additionalRef.vendor, CAMPSITE_ENTITY, additionalRef.vendorRefId))
            }
        }
        bulkUpsertCampsiteVendorLinks(links)

        return campsiteRows.size to totalSkipped
    }

    private fun loadParentCampgroundMap(parentKeys: List<ParentKey>): Map<ParentKey, Long> {
        if (parentKeys.isEmpty()) return emptyMap()
        val result = HashMap<ParentKey, Long>(parentKeys.size)
        for (chunk in parentKeys.chunked(BULK_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(", ") { "(?, ?)" }
            val sql =
                """
                SELECT vr.vendor, vr.external_id, cvr.campground_id
                FROM (VALUES $placeholders) AS pk(vendor, external_id)
                JOIN vendor_refs vr
                  ON vr.vendor = pk.vendor
                 AND vr.entity_type = ?
                 AND vr.external_id = pk.external_id
                 AND vr.deleted_at IS NULL
                JOIN campground_vendor_refs cvr ON cvr.vendor_ref_id = vr.id
                JOIN campgrounds cg ON cg.id = cvr.campground_id AND cg.deleted_at IS NULL
                """.trimIndent()
            val params = mutableListOf<Any?>()
            for (key in chunk) {
                params += key.vendor
                params += key.externalId
            }
            params += CAMPGROUND_ENTITY
            val rows = ctx.fetch(sql, *params.toTypedArray())
            for (row in rows) {
                val key =
                    ParentKey(
                        vendor = row.get("vendor", String::class.java),
                        externalId = row.get("external_id", String::class.java),
                    )
                result[key] = row.get("campground_id", Long::class.java)
            }
        }
        return result
    }

    private fun bulkUpsertCampsiteRows(rows: List<CampsiteBulkRow>): Map<Long, Long> {
        if (rows.isEmpty()) return emptyMap()
        val deduped = rows.distinctBy { it.record.vendor to it.primaryVendorRefId }
        val result = HashMap<Long, Long>(deduped.size)
        for (chunk in deduped.chunked(BULK_CHUNK_SIZE)) {
            val placeholders =
                chunk.joinToString(", ") {
                    "(?, ?, " +
                        "?, ?, ?, ?, ?, ?, ?, " +
                        "?::jsonb, ?, ?::jsonb, ?::jsonb, " +
                        "?, ?, ?, " +
                        "?, ?, ?, " +
                        "?, ?, ?, ?, " +
                        "?, ?, ?::jsonb, ?::jsonb, " +
                        "now(), NULL)"
                }
            val sql =
                """
                INSERT INTO campsites (
                  data_source, primary_vendor_ref_id,
                  campground_id, name, kind, loop_name, latitude, longitude, reservation_url,
                  equipment, kind_listed, schedule, price,
                  firepit, picnic_table, ada_accessible,
                  water_hookups, electric_hookups, sewer_hookups,
                  max_people, max_cars, pull_through, driveway_length,
                  max_rv_length, max_trailer_length, photos, source_payload,
                  updated_at, deleted_at
                )
                VALUES $placeholders
                ON CONFLICT (data_source, primary_vendor_ref_id) WHERE deleted_at IS NULL
                DO UPDATE SET
                  campground_id = EXCLUDED.campground_id,
                  name = EXCLUDED.name,
                  kind = EXCLUDED.kind,
                  loop_name = EXCLUDED.loop_name,
                  latitude = EXCLUDED.latitude,
                  longitude = EXCLUDED.longitude,
                  reservation_url = EXCLUDED.reservation_url,
                  equipment = EXCLUDED.equipment,
                  kind_listed = EXCLUDED.kind_listed,
                  schedule = EXCLUDED.schedule,
                  price = EXCLUDED.price,
                  firepit = EXCLUDED.firepit,
                  picnic_table = EXCLUDED.picnic_table,
                  ada_accessible = EXCLUDED.ada_accessible,
                  water_hookups = EXCLUDED.water_hookups,
                  electric_hookups = EXCLUDED.electric_hookups,
                  sewer_hookups = EXCLUDED.sewer_hookups,
                  max_people = EXCLUDED.max_people,
                  max_cars = EXCLUDED.max_cars,
                  pull_through = EXCLUDED.pull_through,
                  driveway_length = EXCLUDED.driveway_length,
                  max_rv_length = EXCLUDED.max_rv_length,
                  max_trailer_length = EXCLUDED.max_trailer_length,
                  photos = EXCLUDED.photos,
                  source_payload = EXCLUDED.source_payload,
                  updated_at = now(),
                  deleted_at = NULL
                RETURNING id, primary_vendor_ref_id
                """.trimIndent()
            val params = mutableListOf<Any?>()
            for (row in chunk) {
                val record = row.record
                params += record.vendor
                params += row.primaryVendorRefId
                params += row.campgroundId
                params += record.name
                params += record.kind
                params += record.loopName
                params += record.latitude
                params += record.longitude
                params += record.reservationUrl
                params += jsonArrayOrNull(record.equipment)
                params += record.kindListed
                params += jsonObject(record.schedule)
                params += jsonObject(record.price)
                params += record.firepit
                params += record.picnicTable
                params += record.adaAccessible
                params += record.waterHookups
                params += record.electricHookups
                params += record.sewerHookups
                params += record.maxPeople
                params += record.maxCars
                params += record.pullThrough
                params += record.drivewayLength
                params += record.maxRvLength
                params += record.maxTrailerLength
                params += jsonArray(record.photos)
                params += jsonObject(record.sourcePayload)
            }
            val returned = ctx.fetch(sql, *params.toTypedArray())
            for (row in returned) {
                result[row.get("primary_vendor_ref_id", Long::class.java)] =
                    row.get("id", Long::class.java)
            }
        }
        return result
    }

    private fun bulkUpsertCampsiteVendorLinks(links: List<Pair<Long, Long>>) {
        if (links.isEmpty()) return
        val deduped = links.distinct()
        for (chunk in deduped.chunked(BULK_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(", ") { "(?, ?, now())" }
            val sql =
                """
                INSERT INTO campsite_vendor_refs (campsite_id, vendor_ref_id, updated_at)
                VALUES $placeholders
                ON CONFLICT (campsite_id, vendor_ref_id)
                DO UPDATE SET updated_at = now()
                """.trimIndent()
            val params = mutableListOf<Any?>()
            for ((campsiteId, vendorRefId) in chunk) {
                params += campsiteId
                params += vendorRefId
            }
            ctx.execute(sql, *params.toTypedArray())
        }
    }

    fun findById(id: Long): Campsite? =
        ctx
            .fetchOne(
                "$CAMPSITE_SELECT WHERE c.id = ? AND c.deleted_at IS NULL",
                id,
            )?.let(::campsiteFromRecord)

    fun findAll(): List<Campsite> =
        ctx
            .fetch(
                "$CAMPSITE_SELECT WHERE c.deleted_at IS NULL ORDER BY c.id",
            ).map(::campsiteFromRecord)

    fun findByPoi(poiId: Long): List<Campsite> =
        ctx
            .fetch(
                """
                $CAMPSITE_SELECT
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
            ).map(::campsiteFromRecord)

    fun findAvailabilityTargetById(id: Long): CampsiteAvailabilityTarget? =
        ctx
            .fetchOne(
                "$AVAILABILITY_TARGET_SELECT WHERE c.id = ? AND c.deleted_at IS NULL",
                id,
            )?.let(::availabilityTargetFromRecord)

    fun findAvailabilityTargetsByPoi(poiId: Long): List<CampsiteAvailabilityTarget> =
        ctx
            .fetch(
                """
                $AVAILABILITY_TARGET_SELECT
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
            ).map(::availabilityTargetFromRecord)

    fun countSearch(filters: SearchFilters): Int {
        val where = searchWhere(filters)
        return ctx
            .fetchOne(
                """
                SELECT COUNT(*) AS n
                FROM campsites c
                JOIN vendor_refs primary_ref
                  ON primary_ref.id = c.primary_vendor_ref_id
                WHERE ${where.clauses.joinToString(" AND ")}
                """.trimIndent(),
                *where.params.toTypedArray(),
            )!!
            .get("n", Number::class.java)
            .toInt()
    }

    fun search(
        filters: SearchFilters,
        limit: Int,
        offset: Int,
    ): List<Campsite> {
        val where = searchWhere(filters)
        val params =
            where.params +
                listOf(
                    limit.coerceIn(MIN_SEARCH_LIMIT, MAX_SEARCH_LIMIT),
                    offset.coerceAtLeast(0),
                )
        return ctx
            .fetch(
                """
                $CAMPSITE_SELECT
                JOIN vendor_refs primary_ref
                  ON primary_ref.id = c.primary_vendor_ref_id
                WHERE ${where.clauses.joinToString(" AND ")}
                ORDER BY c.name, c.id
                LIMIT ? OFFSET ?
                """.trimIndent(),
                *params.toTypedArray(),
            ).map(::campsiteFromRecord)
    }

    private fun searchWhere(filters: SearchFilters): SearchWhere {
        val clauses = mutableListOf("c.deleted_at IS NULL")
        val params = mutableListOf<Any?>()
        addInClause(clauses, params, "c.data_source", filters.vendors)
        addInClause(clauses, params, "primary_ref.external_id", filters.vendorIds)
        addInClause(clauses, params, "c.loop_name", filters.loops)
        addInClause(clauses, params, "c.kind", filters.siteTypes)
        if (filters.names.isNotEmpty()) {
            clauses +=
                filters.names.joinToString(prefix = "(", postfix = ")", separator = " OR ") {
                    "c.name ILIKE ? ESCAPE '\\'"
                }
            params.addAll(filters.names.map { "%${escapeLikePattern(it)}%" })
        }
        for (rawJson in filters.rawContainsJson) {
            clauses += "c.source_payload @> ?::jsonb"
            params += rawJson
        }
        for (tagsJson in filters.tagsContainsJson) {
            clauses += "($CAMPSITE_TAGS_JSON_SQL) @> ?::jsonb"
            params += tagsJson
        }
        return SearchWhere(clauses, params)
    }

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

    private fun campsiteFromRecord(record: Record): Campsite =
        Campsite(
            id = record.get("id", Long::class.java),
            campgroundId = record.get("campground_id", Long::class.java),
            name = record.get("name", String::class.java),
            kind = record.get("kind", String::class.java),
            loopName = record.get("loop_name", String::class.java),
            latitude = record.get("latitude", Double::class.java),
            longitude = record.get("longitude", Double::class.java),
            reservationUrl = record.get("reservation_url", String::class.java),
            equipment = parseJsonElement(record.get("equipment_text", String::class.java)),
            kindListed = record.get("kind_listed", String::class.java),
            schedule = parseRequiredJsonElement(record.get("schedule_text", String::class.java)),
            price = parseRequiredJsonElement(record.get("price_text", String::class.java)),
            firepit = record.get("firepit", Boolean::class.java),
            picnicTable = record.get("picnic_table", Boolean::class.java),
            adaAccessible = record.get("ada_accessible", Boolean::class.java),
            waterHookups = record.get("water_hookups", Boolean::class.java),
            electricHookups = record.get("electric_hookups", Boolean::class.java),
            sewerHookups = record.get("sewer_hookups", Boolean::class.java),
            maxPeople = record.get("max_people", Int::class.java),
            maxCars = record.get("max_cars", Int::class.java),
            pullThrough = record.get("pull_through", Boolean::class.java),
            drivewayLength = record.get("driveway_length", Int::class.java),
            maxRvLength = record.get("max_rv_length", Int::class.java),
            maxTrailerLength = record.get("max_trailer_length", Double::class.java),
            photos = parseRequiredJsonElement(record.get("photos_text", String::class.java)),
            sourcePayload = parseRequiredJsonElement(record.get("source_payload_text", String::class.java)),
            createdAt = record.instant("created_at"),
            updatedAt = record.instant("updated_at"),
            deletedAt = record.nullableInstant("deleted_at"),
            dataSource = record.get("data_source", String::class.java),
            primaryVendorRefId = record.get("primary_vendor_ref_id", Long::class.java),
        )

    internal fun availabilityTargetFromRecord(r: Record): CampsiteAvailabilityTarget {
        val vendor = r.get("vendor", String::class.java) ?: CANONICAL_VENDOR
        val externalId = r.get("external_id", String::class.java) ?: r.get("id", Long::class.java).toString()
        return CampsiteAvailabilityTarget(
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

    private fun parseRequiredJsonElement(raw: String): JsonElement = Json.parseToJsonElement(raw)

    private fun parseJsonElement(raw: String?): JsonElement? =
        raw
            ?.takeIf { it != "null" }
            ?.let { Json.parseToJsonElement(it) }

    private fun Record.instant(column: String): Instant = get(column, OffsetDateTime::class.java).toInstant()

    private fun Record.nullableInstant(column: String): Instant? = get(column, OffsetDateTime::class.java)?.toInstant()

    private data class ParentKey(
        val vendor: String,
        val externalId: String,
    )

    private data class CampsiteBulkRow(
        val record: CampsiteUpsertCandidate,
        val campgroundId: Long,
        val primaryVendorRefId: Long,
    )

    private data class SearchWhere(
        val clauses: List<String>,
        val params: List<Any?>,
    )

    private fun addInClause(
        clauses: MutableList<String>,
        params: MutableList<Any?>,
        column: String,
        values: List<String>,
    ) {
        if (values.isEmpty()) return
        clauses += values.joinToString(prefix = "$column IN (", postfix = ")") { "?" }
        params.addAll(values)
    }

    private companion object {
        private const val CANONICAL_VENDOR = "canonical"
        private const val MIN_SEARCH_LIMIT = 1
        private const val MAX_SEARCH_LIMIT = 500

        private val CAMPSITE_SELECT =
            """
            SELECT
              c.id,
              c.campground_id,
              c.name,
              c.kind,
              c.loop_name,
              c.latitude,
              c.longitude,
              c.reservation_url,
              c.equipment::text AS equipment_text,
              c.kind_listed,
              c.schedule::text AS schedule_text,
              c.price::text AS price_text,
              c.firepit,
              c.picnic_table,
              c.ada_accessible,
              c.water_hookups,
              c.electric_hookups,
              c.sewer_hookups,
              c.max_people,
              c.max_cars,
              c.pull_through,
              c.driveway_length,
              c.max_rv_length,
              c.max_trailer_length,
              c.photos::text AS photos_text,
              c.source_payload::text AS source_payload_text,
              c.created_at,
              c.updated_at,
              c.deleted_at,
              c.data_source,
              c.primary_vendor_ref_id
            FROM campsites c
            """.trimIndent()

        private val AVAILABILITY_TARGET_SELECT =
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

        private val CAMPSITE_TAGS_JSON_SQL =
            """
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
            )
            """.trimIndent()
    }
}

private fun escapeLikePattern(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
