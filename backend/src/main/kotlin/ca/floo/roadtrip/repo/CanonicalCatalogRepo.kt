package ca.floo.roadtrip.repo

import ca.floo.roadtrip.service.etl.framework.CampgroundEtlRecord
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlRecord
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

    fun upsertCampgrounds(
        records: List<CampgroundEtlRecord>,
        source: String,
    ): Result {
        val runId = importRuns.start(source)
        try {
            val upserted =
                ctx.transactionResult { cfg ->
                    val tx = CanonicalCatalogRepo(DSL.using(cfg))
                    records.sumOf { record -> if (tx.upsertCampground(record)) 1 else 0 }
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
                    var upserted = 0
                    var skipped = 0
                    for (record in records) {
                        if (tx.upsertCampsite(record)) {
                            upserted += 1
                        } else {
                            skipped += 1
                        }
                    }
                    upserted to skipped
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

    private fun upsertCampground(record: CampgroundEtlRecord): Boolean {
        val vendorRefId =
            upsertVendorRef(
                vendor = record.vendor,
                entityType = CAMPGROUND_ENTITY,
                externalId = record.vendorRefId,
                externalName = record.name,
                sourceUrl = record.sourceUrl,
                payload = record.vendorRefPayload,
            )
        val campgroundId = campgroundIdForVendorRef(vendorRefId)
        val persistedCampgroundId =
            if (campgroundId == null) {
                insertCampground(record)
            } else {
                updateCampground(campgroundId, record)
                campgroundId
            }
        linkCampgroundVendorRef(persistedCampgroundId, vendorRefId)
        upsertCampgroundPoi(persistedCampgroundId, record.longitude, record.latitude)
        return true
    }

    private fun upsertCampsite(record: CampsiteEtlRecord): Boolean {
        val parentVendor = record.parentVendor ?: return false
        val parentExternalId = record.parentVendorRefId ?: return false
        val campgroundId =
            campgroundIdForVendor(parentVendor, parentExternalId)
                ?: return false
        val vendorRefId =
            upsertVendorRef(
                vendor = record.vendor,
                entityType = CAMPSITE_ENTITY,
                externalId = record.vendorRefId,
                externalName = record.name,
                sourceUrl = record.reservationUrl,
                payload = record.vendorRefPayload,
            )
        val campsiteId = campsiteIdForVendorRef(vendorRefId)
        val persistedCampsiteId =
            if (campsiteId == null) {
                insertCampsite(campgroundId, record)
            } else {
                updateCampsite(campsiteId, campgroundId, record)
                campsiteId
            }
        linkCampsiteVendorRef(persistedCampsiteId, vendorRefId)
        return true
    }

    private fun upsertVendorRef(
        vendor: String,
        entityType: String,
        externalId: String,
        externalName: String?,
        sourceUrl: String?,
        payload: JsonElement?,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO vendor_refs (
                  vendor, entity_type, external_id, external_name, source_url, payload, updated_at, deleted_at
                ) VALUES (
                  ?, ?, ?, ?, ?, ?::jsonb, now(), NULL
                )
                ON CONFLICT (vendor, entity_type, external_id) WHERE deleted_at IS NULL
                DO UPDATE SET
                  external_name = EXCLUDED.external_name,
                  source_url = EXCLUDED.source_url,
                  payload = EXCLUDED.payload,
                  updated_at = now(),
                  deleted_at = NULL
                RETURNING id
                """.trimIndent(),
                vendor,
                entityType,
                externalId,
                externalName,
                sourceUrl,
                jsonObject(payload),
            )!!
            .get("id", Long::class.java)

    private fun campgroundIdForVendorRef(vendorRefId: Long): Long? =
        ctx
            .fetchOne(
                "SELECT campground_id FROM campground_vendor_refs WHERE vendor_ref_id = ?",
                vendorRefId,
            )?.get("campground_id", Long::class.java)

    private fun campgroundIdForVendor(
        vendor: String,
        externalId: String,
    ): Long? =
        ctx
            .fetchOne(
                """
                SELECT cvr.campground_id
                FROM vendor_refs vr
                JOIN campground_vendor_refs cvr ON cvr.vendor_ref_id = vr.id
                WHERE vr.vendor = ?
                  AND vr.entity_type = ?
                  AND vr.external_id = ?
                  AND vr.deleted_at IS NULL
                """.trimIndent(),
                vendor,
                CAMPGROUND_ENTITY,
                externalId,
            )?.get("campground_id", Long::class.java)

    private fun campsiteIdForVendorRef(vendorRefId: Long): Long? =
        ctx
            .fetchOne(
                "SELECT campsite_id FROM campsite_vendor_refs WHERE vendor_ref_id = ?",
                vendorRefId,
            )?.get("campsite_id", Long::class.java)

    private fun insertCampground(record: CampgroundEtlRecord): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO campgrounds (
                  name, status, status_description, kind,
                  short_description, medium_description, long_description,
                  location, default_campsite_schedule, amenities,
                  max_rv_length, max_trailer_length, has_pull_through_sites, big_rig_friendly,
                  reservation_url, links, photos, alerts, price, cell_service,
                  management, contact, connections, metadata, source_payload
                ) VALUES (
                  ?, ?, ?, ?,
                  ?, ?, ?,
                  ?::jsonb, ?::jsonb, ?::jsonb,
                  ?, ?, ?, ?,
                  ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                  ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb
                )
                RETURNING id
                """.trimIndent(),
                record.name,
                record.status,
                record.statusDescription,
                record.kind,
                record.shortDescription,
                record.mediumDescription,
                record.longDescription,
                jsonObject(record.location),
                jsonObject(record.defaultCampsiteSchedule),
                jsonObject(record.amenities),
                record.maxRvLength,
                record.maxTrailerLength,
                record.hasPullThroughSites,
                record.bigRigFriendly,
                record.reservationUrl,
                jsonArray(record.links),
                jsonArray(record.photos),
                jsonArray(record.alerts),
                jsonObject(record.price),
                jsonObject(record.cellService),
                jsonObject(record.management),
                jsonObject(record.contact),
                jsonObject(record.connections),
                jsonObject(record.metadata),
                jsonObject(record.sourcePayload),
            )!!
            .get("id", Long::class.java)

    private fun updateCampground(
        campgroundId: Long,
        record: CampgroundEtlRecord,
    ) {
        ctx
            .execute(
                """
                UPDATE campgrounds
                SET name = ?,
                    status = ?,
                    status_description = ?,
                    kind = ?,
                    short_description = ?,
                    medium_description = ?,
                    long_description = ?,
                    location = ?::jsonb,
                    default_campsite_schedule = ?::jsonb,
                    amenities = ?::jsonb,
                    max_rv_length = ?,
                    max_trailer_length = ?,
                    has_pull_through_sites = ?,
                    big_rig_friendly = ?,
                    reservation_url = ?,
                    links = ?::jsonb,
                    photos = ?::jsonb,
                    alerts = ?::jsonb,
                    price = ?::jsonb,
                    cell_service = ?::jsonb,
                    management = ?::jsonb,
                    contact = ?::jsonb,
                    connections = ?::jsonb,
                    metadata = ?::jsonb,
                    source_payload = ?::jsonb,
                    updated_at = now(),
                    deleted_at = NULL
                WHERE id = ?
                """.trimIndent(),
                record.name,
                record.status,
                record.statusDescription,
                record.kind,
                record.shortDescription,
                record.mediumDescription,
                record.longDescription,
                jsonObject(record.location),
                jsonObject(record.defaultCampsiteSchedule),
                jsonObject(record.amenities),
                record.maxRvLength,
                record.maxTrailerLength,
                record.hasPullThroughSites,
                record.bigRigFriendly,
                record.reservationUrl,
                jsonArray(record.links),
                jsonArray(record.photos),
                jsonArray(record.alerts),
                jsonObject(record.price),
                jsonObject(record.cellService),
                jsonObject(record.management),
                jsonObject(record.contact),
                jsonObject(record.connections),
                jsonObject(record.metadata),
                jsonObject(record.sourcePayload),
                campgroundId,
            )
    }

    private fun linkCampgroundVendorRef(
        campgroundId: Long,
        vendorRefId: Long,
    ) {
        ctx.execute(
            "UPDATE campground_vendor_refs SET is_primary = false, updated_at = now() WHERE campground_id = ? AND vendor_ref_id <> ?",
            campgroundId,
            vendorRefId,
        )
        ctx.execute(
            """
            INSERT INTO campground_vendor_refs (campground_id, vendor_ref_id, is_primary, updated_at)
            VALUES (?, ?, true, now())
            ON CONFLICT (campground_id, vendor_ref_id)
            DO UPDATE SET is_primary = true, updated_at = now()
            """.trimIndent(),
            campgroundId,
            vendorRefId,
        )
    }

    private fun upsertCampgroundPoi(
        campgroundId: Long,
        longitude: Double,
        latitude: Double,
    ) {
        val existingPoiId =
            ctx
                .fetchOne(
                    "SELECT poi_id FROM poi_campgrounds WHERE campground_id = ?",
                    campgroundId,
                )?.get("poi_id", Long::class.java)
        if (existingPoiId == null) {
            val poiId =
                ctx
                    .fetchOne(
                        """
                        INSERT INTO pois (poi_type, geom)
                        VALUES ('campground', ST_SetSRID(ST_MakePoint(?, ?), 4326))
                        RETURNING id
                        """.trimIndent(),
                        longitude,
                        latitude,
                    )!!
                    .get("id", Long::class.java)
            ctx.execute(
                "INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (?, ?)",
                poiId,
                campgroundId,
            )
        } else {
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
                existingPoiId,
            )
        }
    }

    private fun insertCampsite(
        campgroundId: Long,
        record: CampsiteEtlRecord,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO campsites (
                  campground_id, name, kind, loop_name, latitude, longitude, reservation_url,
                  equipment, kind_listed, schedule, price,
                  firepit, picnic_table, ada_accessible,
                  water_hookups, electric_hookups, sewer_hookups,
                  max_people, max_cars, pull_through, driveway_length,
                  max_rv_length, max_trailer_length, photos, source_payload
                ) VALUES (
                  ?, ?, ?, ?, ?, ?, ?,
                  ?::jsonb, ?, ?::jsonb, ?::jsonb,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?, ?,
                  ?, ?, ?::jsonb, ?::jsonb
                )
                RETURNING id
                """.trimIndent(),
                campgroundId,
                record.name,
                record.kind,
                record.loopName,
                record.latitude,
                record.longitude,
                record.reservationUrl,
                jsonArrayOrNull(record.equipment),
                record.kindListed,
                jsonObject(record.schedule),
                jsonObject(record.price),
                record.firepit,
                record.picnicTable,
                record.adaAccessible,
                record.waterHookups,
                record.electricHookups,
                record.sewerHookups,
                record.maxPeople,
                record.maxCars,
                record.pullThrough,
                record.drivewayLength,
                record.maxRvLength,
                record.maxTrailerLength,
                jsonArray(record.photos),
                jsonObject(record.sourcePayload),
            )!!
            .get("id", Long::class.java)

    private fun updateCampsite(
        campsiteId: Long,
        campgroundId: Long,
        record: CampsiteEtlRecord,
    ) {
        ctx.execute(
            """
            UPDATE campsites
            SET campground_id = ?,
                name = ?,
                kind = ?,
                loop_name = ?,
                latitude = ?,
                longitude = ?,
                reservation_url = ?,
                equipment = ?::jsonb,
                kind_listed = ?,
                schedule = ?::jsonb,
                price = ?::jsonb,
                firepit = ?,
                picnic_table = ?,
                ada_accessible = ?,
                water_hookups = ?,
                electric_hookups = ?,
                sewer_hookups = ?,
                max_people = ?,
                max_cars = ?,
                pull_through = ?,
                driveway_length = ?,
                max_rv_length = ?,
                max_trailer_length = ?,
                photos = ?::jsonb,
                source_payload = ?::jsonb,
                updated_at = now(),
                deleted_at = NULL
            WHERE id = ?
            """.trimIndent(),
            campgroundId,
            record.name,
            record.kind,
            record.loopName,
            record.latitude,
            record.longitude,
            record.reservationUrl,
            jsonArrayOrNull(record.equipment),
            record.kindListed,
            jsonObject(record.schedule),
            jsonObject(record.price),
            record.firepit,
            record.picnicTable,
            record.adaAccessible,
            record.waterHookups,
            record.electricHookups,
            record.sewerHookups,
            record.maxPeople,
            record.maxCars,
            record.pullThrough,
            record.drivewayLength,
            record.maxRvLength,
            record.maxTrailerLength,
            jsonArray(record.photos),
            jsonObject(record.sourcePayload),
            campsiteId,
        )
    }

    private fun linkCampsiteVendorRef(
        campsiteId: Long,
        vendorRefId: Long,
    ) {
        ctx.execute(
            "UPDATE campsite_vendor_refs SET is_primary = false, updated_at = now() WHERE campsite_id = ? AND vendor_ref_id <> ?",
            campsiteId,
            vendorRefId,
        )
        ctx.execute(
            """
            INSERT INTO campsite_vendor_refs (campsite_id, vendor_ref_id, is_primary, updated_at)
            VALUES (?, ?, true, now())
            ON CONFLICT (campsite_id, vendor_ref_id)
            DO UPDATE SET is_primary = true, updated_at = now()
            """.trimIndent(),
            campsiteId,
            vendorRefId,
        )
    }

    private fun jsonObject(value: JsonElement?): String = value?.toString() ?: EMPTY_JSON_OBJECT

    private fun jsonArray(value: JsonElement?): String = value?.toString() ?: EMPTY_JSON_ARRAY

    private fun jsonArrayOrNull(value: JsonElement?): String? = value?.toString()

    companion object {
        private const val CAMPGROUND_ENTITY = "campground"
        private const val CAMPSITE_ENTITY = "campsite"
        private const val EMPTY_JSON_OBJECT = "{}"
        private const val EMPTY_JSON_ARRAY = "[]"
    }
}
