package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.CatalogUpsertResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL

class CampsiteRepo(
    private val ctx: DSLContext,
) {
    private val importRunRepo = ImportRunRepo(ctx)

    data class SearchFilters(
        val vendors: List<String> = emptyList(),
        val vendorIds: List<String> = emptyList(),
        val names: List<String> = emptyList(),
        val loops: List<String> = emptyList(),
        val siteTypes: List<String> = emptyList(),
        val rawContainsJson: List<String> = emptyList(),
    )

    fun upsertCampsites(
        records: List<CampsiteUpsertCandidate>,
        source: String,
    ): CatalogUpsertResult {
        val runId = importRunRepo.start(source)
        try {
            val (upserted, skipped) = upsertCampsiteBatch(records)
            importRunRepo.complete(runId, records.size)
            return CatalogUpsertResult(
                runId = runId,
                seenCount = records.size,
                upsertedCount = upserted,
                skippedCount = skipped,
            )
        } catch (e: Throwable) {
            importRunRepo.fail(runId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    fun upsertCampsiteBatch(records: List<CampsiteUpsertCandidate>): Pair<Int, Int> {
        requireCatalogBatchWithinLimit("campsite upsert", records.size)
        return ctx.transactionResult { cfg ->
            val tx = CampsiteRepo(DSL.using(cfg))
            tx.bulkUpsertCampsitesTx(records)
        }
    }

    private fun bulkUpsertCampsitesTx(records: List<CampsiteUpsertCandidate>): Pair<Int, Int> {
        if (records.isEmpty()) return 0 to 0

        val withParent = records.filter { it.parentDataProviderRef != null }
        val skippedForMissingParent = records.size - withParent.size

        val parentMap = HashMap<ParentKey, Long>()
        val parentKeys =
            withParent
                .map {
                    ParentKey(
                        it.parentDataProviderRef!!.provider.id,
                        it.parentDataProviderRef!!.serialize(),
                    )
                }.distinct()
        parentMap.putAll(loadParentCampgroundMap(parentKeys))

        val withResolvedParent =
            withParent.filter {
                ParentKey(it.parentDataProviderRef!!.provider.id, it.parentDataProviderRef!!.serialize()) in parentMap
            }
        val skippedForUnresolvedParent = withParent.size - withResolvedParent.size
        val totalSkipped = skippedForMissingParent + skippedForUnresolvedParent

        if (withResolvedParent.isEmpty()) return 0 to totalSkipped

        val campsiteRows =
            withResolvedParent.map { record ->
                val campgroundId =
                    parentMap.getValue(
                        ParentKey(record.parentDataProviderRef!!.provider.id, record.parentDataProviderRef!!.serialize()),
                    )
                CampsiteBulkRow(record = record, campgroundId = campgroundId)
            }
        bulkUpsertCampsiteRows(campsiteRows)

        return campsiteRows.size to totalSkipped
    }

    private fun loadParentCampgroundMap(parentKeys: List<ParentKey>): Map<ParentKey, Long> {
        if (parentKeys.isEmpty()) return emptyMap()
        val result = HashMap<ParentKey, Long>(parentKeys.size)
        for (chunk in parentKeys.chunked(BULK_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(", ") { "(?, ?)" }
            val sql =
                """
                SELECT cg.data_provider, cg.data_provider_ref, cg.id AS campground_id
                FROM (VALUES $placeholders) AS pk(data_provider, data_provider_ref)
                JOIN campgrounds cg
                  ON cg.data_provider = pk.data_provider
                 AND cg.data_provider_ref = pk.data_provider_ref
                 AND cg.deleted_at IS NULL
                """.trimIndent()
            val params = mutableListOf<Any?>()
            for (key in chunk) {
                params += key.dataProvider
                params += key.dataProviderRef
            }
            val rows = ctx.fetch(sql, *params.toTypedArray())
            for (row in rows) {
                val key =
                    ParentKey(
                        dataProvider = row.get("data_provider", String::class.java),
                        dataProviderRef = row.get("data_provider_ref", String::class.java),
                    )
                result[key] = row.get("campground_id", Long::class.java)
            }
        }
        return result
    }

    private fun bulkUpsertCampsiteRows(rows: List<CampsiteBulkRow>) {
        if (rows.isEmpty()) return
        val deduped = rows.distinctBy { it.record.dataProviderRef.provider.id to it.record.dataProviderRef.serialize() }
        for (chunk in deduped.chunked(BULK_CHUNK_SIZE)) {
            val placeholders =
                chunk.joinToString(", ") {
                    "(?, ?, ?, ?, " +
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
                  data_provider, data_provider_ref, booking_provider, booking_provider_ref,
                  campground_id, name, kind, loop_name, latitude, longitude, reservation_url,
                  equipment, kind_listed, schedule, price,
                  firepit, picnic_table, ada_accessible,
                  water_hookups, electric_hookups, sewer_hookups,
                  max_people, max_cars, pull_through, driveway_length,
                  max_rv_length, max_trailer_length, photos, source_payload,
                  updated_at, deleted_at
                )
                VALUES $placeholders
                ON CONFLICT (data_provider, data_provider_ref) WHERE deleted_at IS NULL
                DO UPDATE SET
                  booking_provider = EXCLUDED.booking_provider,
                  booking_provider_ref = EXCLUDED.booking_provider_ref,
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
                """.trimIndent()
            val params = mutableListOf<Any?>()
            for (row in chunk) {
                val record = row.record
                params += record.dataProviderRef.provider.id
                params += record.dataProviderRef.serialize()
                params += record.bookingProvider?.id
                params += record.bookingProviderRef
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
            ctx.execute(sql, *params.toTypedArray())
        }
    }

    fun findById(id: Long): Campsite? =
        ctx
            .fetchOne(
                "$campsiteSelect WHERE c.id = ? AND c.deleted_at IS NULL",
                id,
            )?.let(::campsiteFromRecord)

    fun findByCampground(campgroundId: Long): List<Campsite> =
        ctx
            .fetch(
                """
                $campsiteSelect
                WHERE c.campground_id = ?
                  AND c.deleted_at IS NULL
                ORDER BY c.loop_name NULLS LAST, c.name, c.id
                """.trimIndent(),
                campgroundId,
            ).map(::campsiteFromRecord)

    @Deprecated("Use findByCampground; load campground from CampgroundRepo first")
    fun findByPoi(poiId: Long): List<Campsite> =
        ctx
            .fetch(
                """
                $campsiteSelect
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
                $campsiteSelect
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
        addInClause(clauses, params, "c.data_provider", filters.vendors)
        addInClause(clauses, params, "c.data_provider_ref", filters.vendorIds)
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

    private fun campsiteFromRecord(record: Record): Campsite {
        val dataProvider = record.get("data_provider", String::class.java)
        val dataProviderRefStr = record.get("data_provider_ref", String::class.java)

        return Campsite(
            id = record.get("id", Long::class.java),
            campgroundId = record.get("campground_id", Long::class.java),
            name = record.get("name", String::class.java),
            kind = record.get("kind", String::class.java),
            loopName = record.get("loop_name", String::class.java),
            // `Double`/`Boolean`/`Int::class.java` resolve to the JVM *primitive*
            // class, so jOOQ coerces a NULL column to 0.0/false/0 instead of null.
            // Every nullable column below takes `.javaObjectType` to preserve null.
            latitude = record.get("latitude", Double::class.javaObjectType),
            longitude = record.get("longitude", Double::class.javaObjectType),
            reservationUrl = record.get("reservation_url", String::class.java),
            equipment = parseNullableJsonElement(record.get("equipment_text", String::class.java)),
            kindListed = record.get("kind_listed", String::class.java),
            schedule = parseJsonElement(record.get("schedule_text", String::class.java)),
            price = parseJsonElement(record.get("price_text", String::class.java)),
            firepit = record.get("firepit", Boolean::class.javaObjectType),
            picnicTable = record.get("picnic_table", Boolean::class.javaObjectType),
            adaAccessible = record.get("ada_accessible", Boolean::class.javaObjectType),
            waterHookups = record.get("water_hookups", Boolean::class.javaObjectType),
            electricHookups = record.get("electric_hookups", Boolean::class.javaObjectType),
            sewerHookups = record.get("sewer_hookups", Boolean::class.javaObjectType),
            maxPeople = record.get("max_people", Int::class.javaObjectType),
            maxCars = record.get("max_cars", Int::class.javaObjectType),
            pullThrough = record.get("pull_through", Boolean::class.javaObjectType),
            drivewayLength = record.get("driveway_length", Int::class.javaObjectType),
            maxRvLength = record.get("max_rv_length", Int::class.javaObjectType),
            maxTrailerLength = record.get("max_trailer_length", Double::class.javaObjectType),
            photos = parseJsonElement(record.get("photos_text", String::class.java)),
            sourcePayload = parseJsonElement(record.get("source_payload_text", String::class.java)),
            createdAt = record.instant("created_at"),
            updatedAt = record.instant("updated_at"),
            deletedAt = record.nullableInstant("deleted_at"),
            dataProvider = dataProvider,
            dataProviderRefValue = dataProviderRefStr,
            bookingProvider = record.get("booking_provider", String::class.java),
            bookingProviderRef = record.get("booking_provider_ref", String::class.java),
        )
    }

    private fun parseNullableJsonElement(raw: String?): JsonElement? =
        raw
            ?.takeIf { it != "null" }
            ?.let { Json.parseToJsonElement(it) }

    private data class ParentKey(
        val dataProvider: String,
        val dataProviderRef: String,
    )

    private data class CampsiteBulkRow(
        val record: CampsiteUpsertCandidate,
        val campgroundId: Long,
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
        private const val MIN_SEARCH_LIMIT = 1
        private const val MAX_SEARCH_LIMIT = 500

        private val campsiteSelect =
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
              c.data_provider,
              c.data_provider_ref,
              c.booking_provider,
              c.booking_provider_ref
            FROM campsites c
            """.trimIndent()
    }
}

private fun escapeLikePattern(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
