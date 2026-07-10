package ca.floo.roadtrip.repo

import ca.floo.roadtrip.service.etl.framework.CampgroundEtlRecord
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlRecord
import ca.floo.roadtrip.service.etl.framework.PlanetFitnessLocationEtlRecord
import ca.floo.roadtrip.service.etl.framework.TeslaSuperchargerEtlRecord
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.jooq.impl.DSL

class CanonicalCatalogRepo(
    private val ctx: DSLContext,
) {
    private val importRuns = ImportRunRepo(ctx)

    data class Result(
        val runId: Long,
        val seenCount: Int,
        val upsertedCount: Int,
        val skippedCount: Int = 0,
        val sweptCount: Int = 0,
    )

    // -----------------------------------------------------------------------
    // Public entry points
    // -----------------------------------------------------------------------

    fun upsertCampgrounds(
        records: List<CampgroundEtlRecord>,
        source: String,
    ): Result {
        val runId = importRuns.start(source)
        try {
            val upserted =
                ctx.transactionResult { cfg ->
                    val tx = CanonicalCatalogRepo(DSL.using(cfg))
                    tx.bulkUpsertCampgroundsTx(records)
                }
            importRuns.complete(runId, records.size)
            return Result(runId = runId, seenCount = records.size, upsertedCount = upserted)
        } catch (e: Throwable) {
            importRuns.fail(runId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    fun upsertCampsites(
        records: List<CampsiteEtlRecord>,
        source: String,
    ): Result {
        val runId = importRuns.start(source)
        try {
            val (upserted, skipped) =
                ctx.transactionResult { cfg ->
                    val tx = CanonicalCatalogRepo(DSL.using(cfg))
                    tx.bulkUpsertCampsitesTx(records)
                }
            importRuns.complete(runId, records.size)
            return Result(
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

    fun upsertTeslaSuperchargers(
        records: List<TeslaSuperchargerEtlRecord>,
        source: String,
    ): Result {
        val runId = importRuns.start(source)
        try {
            val upserted =
                ctx.transactionResult { cfg ->
                    val tx = CanonicalCatalogRepo(DSL.using(cfg))
                    records.sumOf { record -> if (tx.upsertTeslaSupercharger(record)) 1 else 0 }
                }
            importRuns.complete(runId, records.size)
            return Result(runId = runId, seenCount = records.size, upsertedCount = upserted)
        } catch (e: Throwable) {
            importRuns.fail(runId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    fun upsertPlanetFitnessLocations(
        records: List<PlanetFitnessLocationEtlRecord>,
        source: String,
    ): Result {
        val runId = importRuns.start(source)
        try {
            val upserted =
                ctx.transactionResult { cfg ->
                    val tx = CanonicalCatalogRepo(DSL.using(cfg))
                    records.sumOf { record -> if (tx.upsertPlanetFitnessLocation(record)) 1 else 0 }
                }
            importRuns.complete(runId, records.size)
            return Result(runId = runId, seenCount = records.size, upsertedCount = upserted)
        } catch (e: Throwable) {
            importRuns.fail(runId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    // -----------------------------------------------------------------------
    // Bulk campground / campsite pipeline (runs inside caller's transaction)
    //
    // Per canonical import row the pipeline does at most 5 constant-cost
    // stages regardless of batch size:
    //   1. Bulk upsert every referenced vendor_ref (primary + additional).
    //   2. (Campsites only) Preload the parent-campground map for the batch.
    //   3. Bulk upsert the canonical rows keyed on (data_source, primary_vendor_ref_id).
    //   4. Bulk upsert the campground_vendor_refs / campsite_vendor_refs
    //      link table, including additionalVendorRefs.
    //   5. (Campgrounds only) Bulk upsert the lean POI wrappers.
    // Every stage chunks its VALUES clause to keep Postgres protocol
    // parameter counts well under the 65 535 limit; total statement count
    // for a 300 k-record import is ~5 × ceil(N / BULK_CHUNK_SIZE), not O(N).
    // -----------------------------------------------------------------------

    private fun bulkUpsertCampgroundsTx(records: List<CampgroundEtlRecord>): Int {
        if (records.isEmpty()) return 0

        val vendorRefSpecs = mutableListOf<VendorRefSpec>()
        for (r in records) {
            vendorRefSpecs +=
                VendorRefSpec(
                    vendor = r.vendor,
                    entityType = CAMPGROUND_ENTITY,
                    externalId = r.vendorRefId,
                    externalName = r.name,
                    sourceUrl = r.sourceUrl,
                    payload = r.vendorRefPayload,
                )
            for (add in r.additionalVendorRefs) {
                vendorRefSpecs +=
                    VendorRefSpec(
                        vendor = add.vendor,
                        entityType = CAMPGROUND_ENTITY,
                        externalId = add.vendorRefId,
                        externalName = r.name,
                        sourceUrl = add.sourceUrl,
                        payload = add.payload,
                    )
            }
        }
        val vendorRefIds = bulkUpsertVendorRefs(vendorRefSpecs)

        val campgroundRows =
            records.map { r ->
                CampgroundBulkRow(
                    record = r,
                    primaryVendorRefId = vendorRefIds.getValue(VendorRefKey(r.vendor, CAMPGROUND_ENTITY, r.vendorRefId)),
                )
            }
        val campgroundIdByPrimaryRef = bulkUpsertCampgroundRows(campgroundRows)

        val links = mutableListOf<Pair<Long, Long>>()
        for (r in records) {
            val primaryRefId = vendorRefIds.getValue(VendorRefKey(r.vendor, CAMPGROUND_ENTITY, r.vendorRefId))
            val campgroundId = campgroundIdByPrimaryRef.getValue(primaryRefId)
            links += campgroundId to primaryRefId
            for (add in r.additionalVendorRefs) {
                links += campgroundId to vendorRefIds.getValue(VendorRefKey(add.vendor, CAMPGROUND_ENTITY, add.vendorRefId))
            }
        }
        bulkUpsertCampgroundVendorLinks(links)

        val poiRows =
            records.map { r ->
                val primaryRefId = vendorRefIds.getValue(VendorRefKey(r.vendor, CAMPGROUND_ENTITY, r.vendorRefId))
                CampgroundPoiRow(
                    campgroundId = campgroundIdByPrimaryRef.getValue(primaryRefId),
                    longitude = r.longitude,
                    latitude = r.latitude,
                )
            }
        bulkUpsertCampgroundPois(poiRows)

        return records.size
    }

    private fun bulkUpsertCampsitesTx(records: List<CampsiteEtlRecord>): Pair<Int, Int> {
        if (records.isEmpty()) return 0 to 0

        // Records without a declared parent are skipped upfront — mirrors the
        // per-record guard in the previous implementation.
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

        val vendorRefSpecs = mutableListOf<VendorRefSpec>()
        for (r in withResolvedParent) {
            vendorRefSpecs +=
                VendorRefSpec(
                    vendor = r.vendor,
                    entityType = CAMPSITE_ENTITY,
                    externalId = r.vendorRefId,
                    externalName = r.name,
                    sourceUrl = r.reservationUrl,
                    payload = r.vendorRefPayload,
                )
            for (add in r.additionalVendorRefs) {
                vendorRefSpecs +=
                    VendorRefSpec(
                        vendor = add.vendor,
                        entityType = CAMPSITE_ENTITY,
                        externalId = add.vendorRefId,
                        externalName = r.name,
                        sourceUrl = add.sourceUrl,
                        payload = add.payload,
                    )
            }
        }
        val vendorRefIds = bulkUpsertVendorRefs(vendorRefSpecs)

        val campsiteRows =
            withResolvedParent.map { r ->
                val primaryRefId = vendorRefIds.getValue(VendorRefKey(r.vendor, CAMPSITE_ENTITY, r.vendorRefId))
                val campgroundId = parentMap.getValue(ParentKey(r.parentVendor!!, r.parentVendorRefId!!))
                CampsiteBulkRow(
                    record = r,
                    campgroundId = campgroundId,
                    primaryVendorRefId = primaryRefId,
                )
            }
        val campsiteIdByPrimaryRef = bulkUpsertCampsiteRows(campsiteRows)

        val links = mutableListOf<Pair<Long, Long>>()
        for (r in withResolvedParent) {
            val primaryRefId = vendorRefIds.getValue(VendorRefKey(r.vendor, CAMPSITE_ENTITY, r.vendorRefId))
            val campsiteId = campsiteIdByPrimaryRef.getValue(primaryRefId)
            links += campsiteId to primaryRefId
            for (add in r.additionalVendorRefs) {
                links += campsiteId to vendorRefIds.getValue(VendorRefKey(add.vendor, CAMPSITE_ENTITY, add.vendorRefId))
            }
        }
        bulkUpsertCampsiteVendorLinks(links)

        return campsiteRows.size to totalSkipped
    }

    // -----------------------------------------------------------------------
    // Bulk helpers (all take chunk-friendly inputs, all return typed maps)
    // -----------------------------------------------------------------------

    private data class VendorRefKey(
        val vendor: String,
        val entityType: String,
        val externalId: String,
    )

    private data class VendorRefSpec(
        val vendor: String,
        val entityType: String,
        val externalId: String,
        val externalName: String?,
        val sourceUrl: String?,
        val payload: JsonElement?,
    )

    private data class ParentKey(
        val vendor: String,
        val externalId: String,
    )

    private data class CampgroundBulkRow(
        val record: CampgroundEtlRecord,
        val primaryVendorRefId: Long,
    )

    private data class CampsiteBulkRow(
        val record: CampsiteEtlRecord,
        val campgroundId: Long,
        val primaryVendorRefId: Long,
    )

    private data class CampgroundPoiRow(
        val campgroundId: Long,
        val longitude: Double,
        val latitude: Double,
    )

    /**
     * Upsert every distinct vendor_ref natural key in one pass. Returns a
     * map of (vendor, entity_type, external_id) → id populated from both
     * newly inserted and previously existing rows.
     */
    private fun bulkUpsertVendorRefs(specs: List<VendorRefSpec>): Map<VendorRefKey, Long> {
        if (specs.isEmpty()) return emptyMap()
        val deduped = specs.distinctBy { VendorRefKey(it.vendor, it.entityType, it.externalId) }
        val result = HashMap<VendorRefKey, Long>(deduped.size)
        for (chunk in deduped.chunked(BULK_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(", ") { "(?, ?, ?, ?, ?, ?::jsonb, now(), NULL)" }
            val sql =
                """
                INSERT INTO vendor_refs
                  (vendor, entity_type, external_id, external_name, source_url, payload, updated_at, deleted_at)
                VALUES $placeholders
                ON CONFLICT (vendor, entity_type, external_id) WHERE deleted_at IS NULL
                DO UPDATE SET
                  external_name = EXCLUDED.external_name,
                  source_url    = EXCLUDED.source_url,
                  payload       = EXCLUDED.payload,
                  updated_at    = now(),
                  deleted_at    = NULL
                RETURNING id, vendor, entity_type, external_id
                """.trimIndent()
            val params = mutableListOf<Any?>()
            for (s in chunk) {
                params += s.vendor
                params += s.entityType
                params += s.externalId
                params += s.externalName
                params += s.sourceUrl
                params += jsonObject(s.payload)
            }
            val rows = ctx.fetch(sql, *params.toTypedArray())
            for (row in rows) {
                val key =
                    VendorRefKey(
                        vendor = row.get("vendor", String::class.java),
                        entityType = row.get("entity_type", String::class.java),
                        externalId = row.get("external_id", String::class.java),
                    )
                result[key] = row.get("id", Long::class.java)
            }
        }
        return result
    }

    /**
     * Preload the campground_id for every (parentVendor, parentExternalId)
     * pair the batch cares about. The vendor_ref join already scopes to the
     * correct campground (vendor+entity_type+external_id is unique).
     */
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
            for (k in chunk) {
                params += k.vendor
                params += k.externalId
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

    /**
     * Bulk upsert canonical campgrounds keyed on (data_source,
     * primary_vendor_ref_id). Returns a map of primary_vendor_ref_id →
     * campground.id — used by [bulkUpsertCampgroundsTx] to resolve link and
     * POI writes without another lookup.
     */
    private fun bulkUpsertCampgroundRows(rows: List<CampgroundBulkRow>): Map<Long, Long> {
        if (rows.isEmpty()) return emptyMap()
        val deduped = rows.distinctBy { it.record.vendor to it.primaryVendorRefId }
        val result = HashMap<Long, Long>(deduped.size)
        for (chunk in deduped.chunked(BULK_CHUNK_SIZE)) {
            val placeholders =
                chunk.joinToString(", ") {
                    "(?, ?, " +
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
                  data_source, primary_vendor_ref_id,
                  name, status, status_description, kind,
                  short_description, medium_description, long_description,
                  location, default_campsite_schedule, amenities,
                  max_rv_length, max_trailer_length, has_pull_through_sites, big_rig_friendly,
                  reservation_url, links, photos, alerts, price, cell_service,
                  management, contact, connections, metadata, source_payload,
                  updated_at, deleted_at
                )
                VALUES $placeholders
                ON CONFLICT (data_source, primary_vendor_ref_id) WHERE deleted_at IS NULL
                DO UPDATE SET
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
                RETURNING id, primary_vendor_ref_id
                """.trimIndent()
            val params = mutableListOf<Any?>()
            for (r in chunk) {
                val rec = r.record
                params += rec.vendor
                params += r.primaryVendorRefId
                params += rec.name
                params += rec.status
                params += rec.statusDescription
                params += rec.kind
                params += rec.shortDescription
                params += rec.mediumDescription
                params += rec.longDescription
                params += jsonObject(rec.location)
                params += jsonObject(rec.defaultCampsiteSchedule)
                params += jsonObject(rec.amenities)
                params += rec.maxRvLength
                params += rec.maxTrailerLength
                params += rec.hasPullThroughSites
                params += rec.bigRigFriendly
                params += rec.reservationUrl
                params += jsonArray(rec.links)
                params += jsonArray(rec.photos)
                params += jsonArray(rec.alerts)
                params += jsonObject(rec.price)
                params += jsonObject(rec.cellService)
                params += jsonObject(rec.management)
                params += jsonObject(rec.contact)
                params += jsonObject(rec.connections)
                params += jsonObject(rec.metadata)
                params += jsonObject(rec.sourcePayload)
            }
            val returned = ctx.fetch(sql, *params.toTypedArray())
            for (row in returned) {
                result[row.get("primary_vendor_ref_id", Long::class.java)] =
                    row.get("id", Long::class.java)
            }
        }
        return result
    }

    /**
     * Bulk upsert canonical campsites keyed on (data_source,
     * primary_vendor_ref_id). Returns a map of primary_vendor_ref_id →
     * campsite.id — used to correlate campsite_vendor_refs writes.
     */
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
            for (r in chunk) {
                val rec = r.record
                params += rec.vendor
                params += r.primaryVendorRefId
                params += r.campgroundId
                params += rec.name
                params += rec.kind
                params += rec.loopName
                params += rec.latitude
                params += rec.longitude
                params += rec.reservationUrl
                params += jsonArrayOrNull(rec.equipment)
                params += rec.kindListed
                params += jsonObject(rec.schedule)
                params += jsonObject(rec.price)
                params += rec.firepit
                params += rec.picnicTable
                params += rec.adaAccessible
                params += rec.waterHookups
                params += rec.electricHookups
                params += rec.sewerHookups
                params += rec.maxPeople
                params += rec.maxCars
                params += rec.pullThrough
                params += rec.drivewayLength
                params += rec.maxRvLength
                params += rec.maxTrailerLength
                params += jsonArray(rec.photos)
                params += jsonObject(rec.sourcePayload)
            }
            val returned = ctx.fetch(sql, *params.toTypedArray())
            for (row in returned) {
                result[row.get("primary_vendor_ref_id", Long::class.java)] =
                    row.get("id", Long::class.java)
            }
        }
        return result
    }

    private fun bulkUpsertCampgroundVendorLinks(links: List<Pair<Long, Long>>) {
        bulkUpsertVendorLinkTable("campground_vendor_refs", "campground_id", links)
    }

    private fun bulkUpsertCampsiteVendorLinks(links: List<Pair<Long, Long>>) {
        bulkUpsertVendorLinkTable("campsite_vendor_refs", "campsite_id", links)
    }

    private fun bulkUpsertVendorLinkTable(
        table: String,
        entityIdColumn: String,
        links: List<Pair<Long, Long>>,
    ) {
        if (links.isEmpty()) return
        val deduped = links.distinct()
        for (chunk in deduped.chunked(BULK_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(", ") { "(?, ?, now())" }
            val sql =
                """
                INSERT INTO $table ($entityIdColumn, vendor_ref_id, updated_at)
                VALUES $placeholders
                ON CONFLICT ($entityIdColumn, vendor_ref_id)
                DO UPDATE SET
                  updated_at = now()
                """.trimIndent()
            val params = mutableListOf<Any?>()
            for ((entityId, vendorRefId) in chunk) {
                params += entityId
                params += vendorRefId
            }
            ctx.execute(sql, *params.toTypedArray())
        }
    }

    /**
     * Bulk equivalent of the previous per-campground POI wrapper upsert.
     *
     * poi_campgrounds has UNIQUE(campground_id) so each campground has at
     * most one POI row. Existing POIs get a geometry refresh; new
     * campgrounds get a fresh POI + link. INSERT..VALUES..RETURNING preserves
     * insertion order in Postgres, so we can zip returned poi_ids back to
     * the input campground_ids without a correlating column.
     */
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
            for (chunk in existingRows.chunked(BULK_CHUNK_SIZE)) {
                val placeholders = chunk.joinToString(", ") { "(?::bigint, ?::float8, ?::float8)" }
                val sql =
                    """
                    UPDATE pois AS p
                       SET geom = ST_SetSRID(ST_MakePoint(v.lon, v.lat), 4326),
                           updated_at = now(),
                           deleted_at = NULL
                      FROM (VALUES $placeholders) AS v(id, lon, lat)
                     WHERE p.id = v.id
                    """.trimIndent()
                val params = mutableListOf<Any?>()
                for (r in chunk) {
                    params += existingPoiByCampground.getValue(r.campgroundId)
                    params += r.longitude
                    params += r.latitude
                }
                ctx.execute(sql, *params.toTypedArray())
            }
        }

        if (newRows.isNotEmpty()) {
            val newPoiIds = mutableListOf<Long>()
            for (chunk in newRows.chunked(BULK_CHUNK_SIZE)) {
                val placeholders =
                    chunk.joinToString(", ") { "(?, ST_SetSRID(ST_MakePoint(?::float8, ?::float8), 4326))" }
                val sql =
                    """
                    INSERT INTO pois (poi_type, geom)
                    VALUES $placeholders
                    RETURNING id
                    """.trimIndent()
                val params = mutableListOf<Any?>()
                for (r in chunk) {
                    params += CAMPGROUND_POI_TYPE
                    params += r.longitude
                    params += r.latitude
                }
                val returned = ctx.fetch(sql, *params.toTypedArray())
                for (row in returned) newPoiIds += row.get("id", Long::class.java)
            }
            // Postgres preserves VALUES order in RETURNING, so newPoiIds aligns 1:1 with newRows.
            check(newPoiIds.size == newRows.size) {
                "bulkUpsertCampgroundPois: inserted ${newPoiIds.size} pois for ${newRows.size} new campgrounds"
            }
            val linkPairs = newRows.zip(newPoiIds) { row, poiId -> poiId to row.campgroundId }
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

    // -----------------------------------------------------------------------
    // Tesla / Planet Fitness paths (unchanged — batch sizes are small enough
    // that per-record round-trips finish in seconds; not worth the extra
    // schema surface a bulk key would require).
    // -----------------------------------------------------------------------

    private fun upsertTeslaSupercharger(record: TeslaSuperchargerEtlRecord): Boolean {
        val superchargerId =
            teslaSuperchargerIdForLocationSlug(record.locationSlug)
                ?.also { updateTeslaSupercharger(it, record) }
                ?: insertTeslaSupercharger(record)
        upsertTeslaSuperchargerPoi(superchargerId, record.longitude, record.latitude)
        return true
    }

    private fun upsertPlanetFitnessLocation(record: PlanetFitnessLocationEtlRecord): Boolean {
        val locationId =
            planetFitnessLocationIdForLocationId(record.locationId)
                ?.also { updatePlanetFitnessLocation(it, record) }
                ?: insertPlanetFitnessLocation(record)
        upsertPlanetFitnessLocationPoi(locationId, record.longitude, record.latitude)
        return true
    }

    private fun teslaSuperchargerIdForLocationSlug(locationSlug: String): Long? =
        ctx
            .fetchOne(
                "SELECT id FROM tesla_superchargers WHERE location_slug = ?",
                locationSlug,
            )?.get("id", Long::class.java)

    private fun planetFitnessLocationIdForLocationId(locationId: String): Long? =
        ctx
            .fetchOne(
                "SELECT id FROM planet_fitness_locations WHERE location_id = ?",
                locationId,
            )?.get("id", Long::class.java)

    private fun insertTeslaSupercharger(record: TeslaSuperchargerEtlRecord): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO tesla_superchargers (
                  location_slug, location_guid, common_site_name, site_status, access_type,
                  open_to_public, open_to_non_teslas, trailer_friendly, twenty_four_seven,
                  stall_count, max_power_kw, address, region, country, time_zone,
                  amenities, hardware_counts, pricebooks, availability_profile, info_url,
                  index_payload, detail_payload
                ) VALUES (
                  ?, ?, ?, ?, ?,
                  ?, ?, ?, ?,
                  ?, ?, ?::jsonb, ?, ?, ?,
                  ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?,
                  ?::jsonb, ?::jsonb
                )
                RETURNING id
                """.trimIndent(),
                record.locationSlug,
                record.locationGuid,
                record.commonSiteName,
                record.siteStatus,
                record.accessType,
                record.openToPublic,
                record.openToNonTeslas,
                record.trailerFriendly,
                record.twentyFourSeven,
                record.stallCount,
                record.maxPowerKw,
                jsonObject(record.address),
                record.region,
                record.country,
                record.timeZone,
                jsonArray(record.amenities),
                jsonObject(record.hardwareCounts),
                jsonArray(record.pricebooks),
                jsonObject(record.availabilityProfile),
                record.infoUrl,
                jsonObject(record.indexPayload),
                jsonObject(record.detailPayload),
            )!!
            .get("id", Long::class.java)

    private fun updateTeslaSupercharger(
        superchargerId: Long,
        record: TeslaSuperchargerEtlRecord,
    ) {
        ctx.execute(
            """
            UPDATE tesla_superchargers
            SET location_guid = ?,
                common_site_name = ?,
                site_status = ?,
                access_type = ?,
                open_to_public = ?,
                open_to_non_teslas = ?,
                trailer_friendly = ?,
                twenty_four_seven = ?,
                stall_count = ?,
                max_power_kw = ?,
                address = ?::jsonb,
                region = ?,
                country = ?,
                time_zone = ?,
                amenities = ?::jsonb,
                hardware_counts = ?::jsonb,
                pricebooks = ?::jsonb,
                availability_profile = ?::jsonb,
                info_url = ?,
                index_payload = ?::jsonb,
                detail_payload = ?::jsonb,
                updated_at = now(),
                deleted_at = NULL
            WHERE id = ?
            """.trimIndent(),
            record.locationGuid,
            record.commonSiteName,
            record.siteStatus,
            record.accessType,
            record.openToPublic,
            record.openToNonTeslas,
            record.trailerFriendly,
            record.twentyFourSeven,
            record.stallCount,
            record.maxPowerKw,
            jsonObject(record.address),
            record.region,
            record.country,
            record.timeZone,
            jsonArray(record.amenities),
            jsonObject(record.hardwareCounts),
            jsonArray(record.pricebooks),
            jsonObject(record.availabilityProfile),
            record.infoUrl,
            jsonObject(record.indexPayload),
            jsonObject(record.detailPayload),
            superchargerId,
        )
    }

    private fun upsertTeslaSuperchargerPoi(
        superchargerId: Long,
        longitude: Double,
        latitude: Double,
    ) {
        val existingPoiId =
            ctx
                .fetchOne(
                    "SELECT poi_id FROM poi_tesla_superchargers WHERE tesla_supercharger_id = ?",
                    superchargerId,
                )?.get("poi_id", Long::class.java)
        if (existingPoiId == null) {
            val poiId = insertPoi(TESLA_SUPERCHARGER_POI_TYPE, longitude, latitude)
            ctx.execute(
                "INSERT INTO poi_tesla_superchargers (poi_id, tesla_supercharger_id) VALUES (?, ?)",
                poiId,
                superchargerId,
            )
        } else {
            updatePoiGeometry(existingPoiId, longitude, latitude)
        }
    }

    private fun insertPlanetFitnessLocation(record: PlanetFitnessLocationEtlRecord): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO planet_fitness_locations (
                  location_id, name, address, region, country, phone, info_url, amenities, payload
                ) VALUES (
                  ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?::jsonb
                )
                RETURNING id
                """.trimIndent(),
                record.locationId,
                record.name,
                jsonObject(record.address),
                record.region,
                record.country,
                record.phone,
                record.infoUrl,
                jsonArray(record.amenities),
                jsonObject(record.payload),
            )!!
            .get("id", Long::class.java)

    private fun updatePlanetFitnessLocation(
        locationId: Long,
        record: PlanetFitnessLocationEtlRecord,
    ) {
        ctx.execute(
            """
            UPDATE planet_fitness_locations
            SET name = ?,
                address = ?::jsonb,
                region = ?,
                country = ?,
                phone = ?,
                info_url = ?,
                amenities = ?::jsonb,
                payload = ?::jsonb,
                updated_at = now(),
                deleted_at = NULL
            WHERE id = ?
            """.trimIndent(),
            record.name,
            jsonObject(record.address),
            record.region,
            record.country,
            record.phone,
            record.infoUrl,
            jsonArray(record.amenities),
            jsonObject(record.payload),
            locationId,
        )
    }

    private fun upsertPlanetFitnessLocationPoi(
        locationId: Long,
        longitude: Double,
        latitude: Double,
    ) {
        val existingPoiId =
            ctx
                .fetchOne(
                    "SELECT poi_id FROM poi_planet_fitness_locations WHERE planet_fitness_location_id = ?",
                    locationId,
                )?.get("poi_id", Long::class.java)
        if (existingPoiId == null) {
            val poiId = insertPoi(PLANET_FITNESS_LOCATION_POI_TYPE, longitude, latitude)
            ctx.execute(
                "INSERT INTO poi_planet_fitness_locations (poi_id, planet_fitness_location_id) VALUES (?, ?)",
                poiId,
                locationId,
            )
        } else {
            updatePoiGeometry(existingPoiId, longitude, latitude)
        }
    }

    private fun insertPoi(
        poiType: String,
        longitude: Double,
        latitude: Double,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (poi_type, geom)
                VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                RETURNING id
                """.trimIndent(),
                poiType,
                longitude,
                latitude,
            )!!
            .get("id", Long::class.java)

    private fun updatePoiGeometry(
        poiId: Long,
        longitude: Double,
        latitude: Double,
    ) {
        ctx.execute(
            """
            UPDATE pois
            SET geom = ST_SetSRID(ST_MakePoint(?, ?), 4326),
                updated_at = now(),
                deleted_at = NULL
            WHERE id = ?
            """.trimIndent(),
            longitude,
            latitude,
            poiId,
        )
    }

    private fun jsonObject(value: JsonElement?): String = value?.toString() ?: EMPTY_JSON_OBJECT

    private fun jsonArray(value: JsonElement?): String = value?.toString() ?: EMPTY_JSON_ARRAY

    private fun jsonArrayOrNull(value: JsonElement?): String? = value?.toString()

    companion object {
        private const val CAMPGROUND_ENTITY = "campground"
        private const val CAMPSITE_ENTITY = "campsite"

        // pois.poi_type values. Kept distinct from the *_ENTITY vendor_ref
        // discriminators — the two namespaces happen to collide today for
        // campgrounds ("campground" appears in both) but they mean different
        // things and can diverge if we ever add a POI wrapper type that isn't
        // a canonical entity type (e.g. multi-entity wrappers).
        private const val CAMPGROUND_POI_TYPE = "campground"
        private const val TESLA_SUPERCHARGER_POI_TYPE = "tesla_supercharger"
        private const val PLANET_FITNESS_LOCATION_POI_TYPE = "planet_fitness_location"
        private const val EMPTY_JSON_OBJECT = "{}"
        private const val EMPTY_JSON_ARRAY = "[]"

        // Rows per multi-VALUES bulk statement. 500 keeps parameter counts
        // (at most ~30 params/row for campgrounds/campsites) well under the
        // Postgres protocol limit of 65 535, while amortizing round-trip
        // overhead across enough rows that a 300 k-record import lands in
        // ~600 statements per stage rather than 300 k.
        private const val BULK_CHUNK_SIZE = 500
    }
}
