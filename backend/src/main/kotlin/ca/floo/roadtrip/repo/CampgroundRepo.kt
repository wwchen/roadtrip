package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.CatalogUpsertResult
import ca.floo.roadtrip.model.domain.poi.CampgroundPoiDetail
import ca.floo.roadtrip.model.domain.poi.PoiGeometryUpdate
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.BookingProviderRefLegacyJson
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime

class CampgroundRepo(
    private val ctx: DSLContext,
) {
    private val importRunRepo = ImportRunRepo(ctx)
    private val poiRepo = PoiRepo(ctx)

    data class SearchFilters(
        val vendors: List<String> = emptyList(),
        val vendorIds: List<String> = emptyList(),
        val names: List<String> = emptyList(),
        val kinds: List<String> = emptyList(),
    )

    fun upsertCampgrounds(
        records: List<CampgroundUpsertCandidate>,
        source: String,
    ): CatalogUpsertResult {
        val runId = importRunRepo.start(source)
        try {
            val upserted = upsertCampgroundBatch(records)
            importRunRepo.complete(runId, records.size)
            return CatalogUpsertResult(runId = runId, seenCount = records.size, upsertedCount = upserted)
        } catch (e: Throwable) {
            importRunRepo.fail(runId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    fun upsertCampgroundBatch(records: List<CampgroundUpsertCandidate>): Int {
        requireCatalogBatchWithinLimit("campground upsert", records.size)
        return ctx.transactionResult { cfg ->
            val tx = CampgroundRepo(DSL.using(cfg))
            tx.bulkUpsertCampgroundsTx(records)
        }
    }

    fun findById(id: Long): Campground? =
        ctx
            .fetchOne(
                "$baseSelect WHERE cg.id = ? AND cg.deleted_at IS NULL",
                id,
            )?.let(::fromRecord)

    /**
     * Point lookup on the catalog's natural key. `(data_provider,
     * data_provider_ref)` is the upsert conflict key, so at most one live row
     * matches. Used by Planning Mode to resolve authored template stays.
     */
    fun findByDataProviderRef(
        provider: String,
        ref: String,
    ): Campground? =
        ctx
            .fetchOne(
                "$baseSelect WHERE cg.data_provider = ? AND cg.data_provider_ref = ? AND cg.deleted_at IS NULL",
                provider,
                ref,
            )?.let(::fromRecord)

    fun findAll(): List<Campground> =
        ctx
            .fetch(
                "$baseSelect WHERE cg.deleted_at IS NULL ORDER BY cg.id",
            ).map(::fromRecord)

    fun findByPoi(poiId: Long): Campground? =
        ctx
            .fetchOne(
                """
                $baseSelect
                JOIN poi_campgrounds pc
                  ON pc.campground_id = cg.id
                JOIN pois p
                  ON p.id = pc.poi_id
                WHERE pc.poi_id = ?
                  AND cg.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            )?.let(::fromRecord)

    fun findPoiDetailByPoi(poiId: Long): CampgroundPoiDetail? {
        val record =
            ctx.fetchOne(
                """
                SELECT
                  $baseSelectColumns,
                  cg.data_provider AS detail_source,
                  cg.data_provider_ref AS detail_source_id,
                  NULLIF(cg.source_payload->'booking_cta_provider_ref', 'null'::jsonb)::text
                    AS cta_provider_ref_text,
                  COALESCE(cg.source_payload::text, '{}') AS properties_text,
                  ARRAY[cg.data_provider]::TEXT[] AS member_sources
                FROM poi_campgrounds pc
                JOIN pois p
                  ON p.id = pc.poi_id
                JOIN campgrounds cg
                  ON cg.id = pc.campground_id
                WHERE pc.poi_id = ?
                  AND cg.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            ) ?: return null
        val campground = fromRecord(record)
        return CampgroundPoiDetail(
            campground = campground,
            source = record.get("detail_source", String::class.java),
            sourceId = record.get("detail_source_id", String::class.java),
            providerRefJson = bookingProviderRefJson(campground),
            ctaProviderRefJson = record.get("cta_provider_ref_text", String::class.java),
            propertiesJson = record.get("properties_text", String::class.java),
            memberSources = memberSourcesOf(record.get("member_sources")),
        )
    }

    fun search(
        filters: SearchFilters,
        limit: Int,
        offset: Int,
    ): List<Campground> {
        val clauses = mutableListOf("cg.deleted_at IS NULL")
        val params = mutableListOf<Any?>()
        addInClause(clauses, params, "cg.data_provider", filters.vendors)
        addInClause(clauses, params, "cg.data_provider_ref", filters.vendorIds)
        addInClause(clauses, params, "cg.kind", filters.kinds)
        if (filters.names.isNotEmpty()) {
            clauses +=
                filters.names.joinToString(prefix = "(", postfix = ")", separator = " OR ") {
                    "cg.name ILIKE ? ESCAPE '\\'"
                }
            params.addAll(filters.names.map { "%${escapeLikePattern(it)}%" })
        }
        params += limit.coerceIn(MIN_SEARCH_LIMIT, MAX_SEARCH_LIMIT)
        params += offset.coerceAtLeast(0)

        return ctx
            .fetch(
                """
                $baseSelect
                WHERE ${clauses.joinToString(" AND ")}
                ORDER BY cg.name, cg.id
                LIMIT ? OFFSET ?
                """.trimIndent(),
                *params.toTypedArray(),
            ).map(::fromRecord)
    }

    private fun fromRecord(record: Record): Campground {
        val dataProvider = DataProvider.fromId(record.get("data_provider", String::class.java))
        val dataProviderRefStr = record.get("data_provider_ref", String::class.java)
        val dataProviderRef =
            DataProviderRef.parse(dataProvider, dataProviderRefStr)
                ?: error("Failed to parse DataProviderRef for provider=$dataProvider ref=$dataProviderRefStr")

        return Campground(
            id = record.get("id", Long::class.java),
            name = record.get("name", String::class.java),
            status = record.get("status", String::class.java),
            statusDescription = record.get("status_description", String::class.java),
            kind = record.get("kind", String::class.java),
            shortDescription = record.get("short_description", String::class.java),
            mediumDescription = record.get("medium_description", String::class.java),
            longDescription = record.get("long_description", String::class.java),
            location = parseJsonElement(record.get("location_text", String::class.java)),
            defaultCampsiteSchedule = parseJsonElement(record.get("default_campsite_schedule_text", String::class.java)),
            amenities = parseJsonElement(record.get("amenities_text", String::class.java)),
            maxRvLength = record.get("max_rv_length", Double::class.java),
            maxTrailerLength = record.get("max_trailer_length", Double::class.java),
            hasPullThroughSites = record.get("has_pull_through_sites", Boolean::class.java),
            bigRigFriendly = record.get("big_rig_friendly", Boolean::class.java),
            reservationUrl = record.get("reservation_url", String::class.java),
            links = parseJsonElement(record.get("links_text", String::class.java)),
            photos = parseJsonElement(record.get("photos_text", String::class.java)),
            alerts = parseJsonElement(record.get("alerts_text", String::class.java)),
            price = parseJsonElement(record.get("price_text", String::class.java)),
            cellService = parseJsonElement(record.get("cell_service_text", String::class.java)),
            management = parseJsonElement(record.get("management_text", String::class.java)),
            contact = parseJsonElement(record.get("contact_text", String::class.java)),
            connections = parseJsonElement(record.get("connections_text", String::class.java)),
            metadata = parseJsonElement(record.get("metadata_text", String::class.java)),
            sourcePayload = parseJsonElement(record.get("source_payload_text", String::class.java)),
            createdAt = record.instant("created_at"),
            updatedAt = record.instant("updated_at"),
            deletedAt = record.nullableInstant("deleted_at"),
            dataProviderRef = dataProviderRef,
            bookingProvider = record.get("booking_provider", String::class.java),
            bookingProviderRef = record.get("booking_provider_ref", String::class.java),
        )
    }

    private fun parseJsonElement(raw: String): JsonElement = Json.parseToJsonElement(raw)

    private fun bookingProviderRefJson(campground: Campground): String? {
        val provider = campground.bookingProvider?.let(BookingProvider::fromIdOrNull) ?: return null
        val ref = BookingProviderRef.parse(provider, campground.bookingProviderRef ?: return null) ?: return null
        return BookingProviderRefLegacyJson.toLegacyJson(ref)
    }

    private fun Record.instant(column: String): Instant = get(column, OffsetDateTime::class.java).toInstant()

    private fun Record.nullableInstant(column: String): Instant? = get(column, OffsetDateTime::class.java)?.toInstant()

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

    private fun bulkUpsertCampgroundsTx(records: List<CampgroundUpsertCandidate>): Int {
        if (records.isEmpty()) return 0

        val campgroundIdByProviderRef = bulkUpsertCampgroundRows(records)

        val poiRows =
            records.map { record ->
                CampgroundPoiRow(
                    campgroundId =
                        campgroundIdByProviderRef.getValue(
                            record.dataProviderRef.provider.id to record.dataProviderRef.serialize(),
                        ),
                    longitude = record.longitude,
                    latitude = record.latitude,
                )
            }
        bulkUpsertCampgroundPois(poiRows)

        return records.size
    }

    private fun bulkUpsertCampgroundRows(records: List<CampgroundUpsertCandidate>): Map<Pair<String, String>, Long> {
        if (records.isEmpty()) return emptyMap()
        val deduped = records.distinctBy { it.dataProviderRef.provider.id to it.dataProviderRef.serialize() }
        val result = HashMap<Pair<String, String>, Long>(deduped.size)
        for (chunk in deduped.chunked(BULK_CHUNK_SIZE)) {
            val placeholders =
                chunk.joinToString(", ") {
                    "(?, ?, ?, ?, " +
                        "?, ?, ?, ?, " +
                        "?, ?, ?, " +
                        "?::jsonb, ?::jsonb, ?::jsonb, " +
                        "?, ?, ?, ?, " +
                        "?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, " +
                        "?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, " +
                        "now(), NULL)"
                }
            val sql =
                """
                INSERT INTO campgrounds (
                  data_provider, data_provider_ref, booking_provider, booking_provider_ref,
                  name, status, status_description, kind,
                  short_description, medium_description, long_description,
                  location, default_campsite_schedule, amenities,
                  max_rv_length, max_trailer_length, has_pull_through_sites, big_rig_friendly,
                  reservation_url, links, photos, alerts, price, cell_service,
                  management, contact, connections, metadata, source_payload,
                  updated_at, deleted_at
                )
                VALUES $placeholders
                ON CONFLICT (data_provider, data_provider_ref) WHERE deleted_at IS NULL
                DO UPDATE SET
                  booking_provider = EXCLUDED.booking_provider,
                  booking_provider_ref = EXCLUDED.booking_provider_ref,
                  name = EXCLUDED.name,
                  status = EXCLUDED.status,
                  status_description = EXCLUDED.status_description,
                  kind = EXCLUDED.kind,
                  short_description = EXCLUDED.short_description,
                  medium_description = EXCLUDED.medium_description,
                  long_description = EXCLUDED.long_description,
                  location = EXCLUDED.location,
                  default_campsite_schedule = EXCLUDED.default_campsite_schedule,
                  amenities = EXCLUDED.amenities,
                  max_rv_length = EXCLUDED.max_rv_length,
                  max_trailer_length = EXCLUDED.max_trailer_length,
                  has_pull_through_sites = EXCLUDED.has_pull_through_sites,
                  big_rig_friendly = EXCLUDED.big_rig_friendly,
                  reservation_url = EXCLUDED.reservation_url,
                  links = EXCLUDED.links,
                  photos = EXCLUDED.photos,
                  alerts = EXCLUDED.alerts,
                  price = EXCLUDED.price,
                  cell_service = EXCLUDED.cell_service,
                  management = EXCLUDED.management,
                  contact = EXCLUDED.contact,
                  connections = EXCLUDED.connections,
                  metadata = EXCLUDED.metadata,
                  source_payload = EXCLUDED.source_payload,
                  updated_at = now(),
                  deleted_at = NULL
                RETURNING id, data_provider, data_provider_ref
                """.trimIndent()
            val params = mutableListOf<Any?>()
            for (record in chunk) {
                params += record.dataProviderRef.provider.id
                params += record.dataProviderRef.serialize()
                params += record.bookingProvider?.id
                params += record.bookingProviderRef
                params += record.name
                params += record.status
                params += record.statusDescription
                params += record.kind
                params += record.shortDescription
                params += record.mediumDescription
                params += record.longDescription
                params += jsonObject(record.location)
                params += jsonObject(record.defaultCampsiteSchedule)
                params += jsonObject(record.amenities)
                params += record.maxRvLength
                params += record.maxTrailerLength
                params += record.hasPullThroughSites
                params += record.bigRigFriendly
                params += record.reservationUrl
                params += jsonArray(record.links)
                params += jsonArray(record.photos)
                params += jsonArray(record.alerts)
                params += jsonObject(record.price)
                params += jsonObject(record.cellService)
                params += jsonObject(record.management)
                params += jsonObject(record.contact)
                params += jsonObject(record.connections)
                params += jsonObject(record.metadata)
                params += jsonObject(record.sourcePayload)
            }
            val returned = ctx.fetch(sql, *params.toTypedArray())
            for (row in returned) {
                val key =
                    row.get("data_provider", String::class.java) to
                        row.get("data_provider_ref", String::class.java)
                result[key] = row.get("id", Long::class.java)
            }
        }
        return result
    }

    private fun bulkUpsertCampgroundPois(rows: List<CampgroundPoiRow>) {
        if (rows.isEmpty()) return
        val deduped = rows.distinctBy { it.campgroundId }

        val existingPoiByCampground = HashMap<Long, Long>(deduped.size)
        for (chunk in deduped.chunked(BULK_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(", ") { "?" }
            val sql =
                "SELECT poi_id, campground_id FROM poi_campgrounds WHERE campground_id IN ($placeholders)"
            val params = chunk.map { it.campgroundId as Any? }.toTypedArray()
            val fetched = ctx.fetch(sql, *params)
            for (row in fetched) {
                existingPoiByCampground[row.get("campground_id", Long::class.java)] =
                    row.get("poi_id", Long::class.java)
            }
        }

        val (existingRows, newRows) = deduped.partition { it.campgroundId in existingPoiByCampground }

        if (existingRows.isNotEmpty()) {
            poiRepo.bulkUpdatePoiGeometry(
                existingRows.map { row ->
                    PoiGeometryUpdate(
                        poiId = existingPoiByCampground.getValue(row.campgroundId),
                        longitude = row.longitude,
                        latitude = row.latitude,
                    )
                },
            )
        }

        if (newRows.isNotEmpty()) {
            val linkPairs =
                newRows.map { row ->
                    val poiId = poiRepo.insertPoi(CAMPGROUND_POI_TYPE, row.longitude, row.latitude)
                    poiId to row.campgroundId
                }
            for (chunk in linkPairs.chunked(BULK_CHUNK_SIZE)) {
                val placeholders = chunk.joinToString(", ") { "(?, ?, now())" }
                val sql =
                    """
                    INSERT INTO poi_campgrounds (poi_id, campground_id, updated_at)
                    VALUES $placeholders
                    ON CONFLICT (campground_id) DO NOTHING
                    """.trimIndent()
                val params = mutableListOf<Any?>()
                for ((poiId, campgroundId) in chunk) {
                    params += poiId
                    params += campgroundId
                }
                ctx.execute(sql, *params.toTypedArray())
            }
        }
    }

    private data class CampgroundPoiRow(
        val campgroundId: Long,
        val longitude: Double,
        val latitude: Double,
    )

    private companion object {
        private const val CAMPGROUND_POI_TYPE = "campground"
        private const val MIN_SEARCH_LIMIT = 1
        private const val MAX_SEARCH_LIMIT = 500

        private val baseSelectColumns =
            """
            cg.id,
            cg.name,
            cg.status,
            cg.status_description,
            cg.kind,
            cg.short_description,
            cg.medium_description,
            cg.long_description,
            cg.location::text AS location_text,
            cg.default_campsite_schedule::text AS default_campsite_schedule_text,
            cg.amenities::text AS amenities_text,
            cg.max_rv_length,
            cg.max_trailer_length,
            cg.has_pull_through_sites,
            cg.big_rig_friendly,
            cg.reservation_url,
            cg.links::text AS links_text,
            cg.photos::text AS photos_text,
            cg.alerts::text AS alerts_text,
            cg.price::text AS price_text,
            cg.cell_service::text AS cell_service_text,
            cg.management::text AS management_text,
            cg.contact::text AS contact_text,
            cg.connections::text AS connections_text,
            cg.metadata::text AS metadata_text,
            cg.source_payload::text AS source_payload_text,
            cg.created_at,
            cg.updated_at,
            cg.deleted_at,
            cg.data_provider,
            cg.data_provider_ref,
            cg.booking_provider,
            cg.booking_provider_ref
            """.trimIndent()

        private val baseSelect =
            """
            SELECT
              $baseSelectColumns
            FROM campgrounds cg
            """.trimIndent()
    }
}

private fun memberSourcesOf(value: Any?): List<String> =
    when (value) {
        null -> emptyList()
        is Array<*> -> value.mapNotNull { it?.toString() }
        is java.sql.Array -> memberSourcesOf(value.array)
        is Collection<*> -> value.mapNotNull { it?.toString() }
        else -> emptyList()
    }

private fun escapeLikePattern(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
