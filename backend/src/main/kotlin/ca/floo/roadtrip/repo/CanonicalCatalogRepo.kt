package ca.floo.roadtrip.repo

import ca.floo.roadtrip.service.etl.framework.CampgroundEtlRecord
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlRecord
import ca.floo.roadtrip.service.etl.framework.PlanetFitnessLocationEtlRecord
import ca.floo.roadtrip.service.etl.framework.TeslaSuperchargerEtlRecord
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
                    records.sumOf { record -> if (tx.upsertCampground(record, source)) 1 else 0 }
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
                        if (tx.upsertCampsite(record, source)) {
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

    private fun upsertCampground(
        record: CampgroundEtlRecord,
        etlSource: String,
    ): Boolean {
        val vendorRefId =
            upsertVendorRef(
                vendor = record.vendor,
                entityType = CAMPGROUND_ENTITY,
                externalId = record.vendorRefId,
                externalName = record.name,
                sourceUrl = record.sourceUrl,
                payload = record.vendorRefPayload,
            )
        val additionalVendorRefIds =
            record.additionalVendorRefs.map { ref ->
                upsertVendorRef(
                    vendor = ref.vendor,
                    entityType = CAMPGROUND_ENTITY,
                    externalId = ref.vendorRefId,
                    externalName = record.name,
                    sourceUrl = ref.sourceUrl,
                    payload = ref.payload,
                )
            }
        val campgroundId = campgroundIdForPrimaryVendorRef(vendorRefId)
        val persistedCampgroundId =
            if (campgroundId == null) {
                insertCampground(record, etlSource)
            } else {
                updateCampground(campgroundId, record, etlSource)
                campgroundId
            }
        linkCampgroundVendorRef(persistedCampgroundId, vendorRefId, primary = true)
        recordCampgroundMatchesForVendorRef(
            campgroundId = persistedCampgroundId,
            vendorRefId = vendorRefId,
            vendor = record.vendor,
            externalId = record.vendorRefId,
            etlSource = etlSource,
            refRole = PRIMARY_REF_ROLE,
        )
        for ((idx, secondaryVendorRefId) in additionalVendorRefIds.withIndex()) {
            val ref = record.additionalVendorRefs[idx]
            linkCampgroundVendorRef(persistedCampgroundId, secondaryVendorRefId, primary = false)
            recordCampgroundMatchesForVendorRef(
                campgroundId = persistedCampgroundId,
                vendorRefId = secondaryVendorRefId,
                vendor = ref.vendor,
                externalId = ref.vendorRefId,
                etlSource = etlSource,
                refRole = ADDITIONAL_REF_ROLE,
            )
        }
        upsertCampgroundPoi(persistedCampgroundId, record.longitude, record.latitude)
        return true
    }

    private fun upsertCampsite(
        record: CampsiteEtlRecord,
        etlSource: String,
    ): Boolean {
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
        val additionalVendorRefIds =
            record.additionalVendorRefs.map { ref ->
                upsertVendorRef(
                    vendor = ref.vendor,
                    entityType = CAMPSITE_ENTITY,
                    externalId = ref.vendorRefId,
                    externalName = record.name,
                    sourceUrl = ref.sourceUrl,
                    payload = ref.payload,
                )
            }
        val campsiteId = campsiteIdForPrimaryVendorRef(vendorRefId)
        val persistedCampsiteId =
            if (campsiteId == null) {
                insertCampsite(campgroundId, record, etlSource)
            } else {
                updateCampsite(campsiteId, campgroundId, record, etlSource)
                campsiteId
            }
        linkCampsiteVendorRef(persistedCampsiteId, vendorRefId, primary = true)
        recordCampsiteMatchesForVendorRef(
            campsiteId = persistedCampsiteId,
            vendorRefId = vendorRefId,
            vendor = record.vendor,
            externalId = record.vendorRefId,
            etlSource = etlSource,
            refRole = PRIMARY_REF_ROLE,
        )
        for ((idx, secondaryVendorRefId) in additionalVendorRefIds.withIndex()) {
            val ref = record.additionalVendorRefs[idx]
            linkCampsiteVendorRef(persistedCampsiteId, secondaryVendorRefId, primary = false)
            recordCampsiteMatchesForVendorRef(
                campsiteId = persistedCampsiteId,
                vendorRefId = secondaryVendorRefId,
                vendor = ref.vendor,
                externalId = ref.vendorRefId,
                etlSource = etlSource,
                refRole = ADDITIONAL_REF_ROLE,
            )
        }
        return true
    }

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

    private fun campgroundIdForPrimaryVendorRef(vendorRefId: Long): Long? =
        ctx
            .fetchOne(
                "SELECT campground_id FROM campground_vendor_refs WHERE vendor_ref_id = ? AND is_primary",
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
                  AND cvr.is_primary
                """.trimIndent(),
                vendor,
                CAMPGROUND_ENTITY,
                externalId,
            )?.get("campground_id", Long::class.java)

    private fun campsiteIdForPrimaryVendorRef(vendorRefId: Long): Long? =
        ctx
            .fetchOne(
                "SELECT campsite_id FROM campsite_vendor_refs WHERE vendor_ref_id = ? AND is_primary",
                vendorRefId,
            )?.get("campsite_id", Long::class.java)

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

    private fun insertCampground(
        record: CampgroundEtlRecord,
        etlSource: String,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO campgrounds (
                  etl_source, name, status, status_description, kind,
                  short_description, medium_description, long_description,
                  location, default_campsite_schedule, amenities,
                  max_rv_length, max_trailer_length, has_pull_through_sites, big_rig_friendly,
                  reservation_url, links, photos, alerts, price, cell_service,
                  management, contact, connections, metadata, source_payload
                ) VALUES (
                  ?, ?, ?, ?, ?,
                  ?, ?, ?,
                  ?::jsonb, ?::jsonb, ?::jsonb,
                  ?, ?, ?, ?,
                  ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                  ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb
                )
                RETURNING id
                """.trimIndent(),
                etlSource,
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
        etlSource: String,
    ) {
        ctx
            .execute(
                """
                UPDATE campgrounds
                SET etl_source = ?,
                    name = ?,
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
                etlSource,
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
        primary: Boolean,
    ) {
        if (primary) {
            ctx.execute(
                "UPDATE campground_vendor_refs SET is_primary = false, updated_at = now() WHERE campground_id = ? AND vendor_ref_id <> ?",
                campgroundId,
                vendorRefId,
            )
        }
        ctx.execute(
            """
            INSERT INTO campground_vendor_refs (campground_id, vendor_ref_id, is_primary, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (campground_id, vendor_ref_id)
            DO UPDATE SET
              is_primary = CASE WHEN ? THEN true ELSE campground_vendor_refs.is_primary END,
              updated_at = now()
            """.trimIndent(),
            campgroundId,
            vendorRefId,
            primary,
            primary,
        )
    }

    private fun recordCampgroundMatchesForVendorRef(
        campgroundId: Long,
        vendorRefId: Long,
        vendor: String,
        externalId: String,
        etlSource: String,
        refRole: String,
    ) {
        val matchedIds =
            ctx
                .fetch(
                    """
                    SELECT campground_id
                    FROM campground_vendor_refs
                    WHERE vendor_ref_id = ?
                      AND campground_id <> ?
                    ORDER BY campground_id
                    """.trimIndent(),
                    vendorRefId,
                    campgroundId,
                ).map { it.get("campground_id", Long::class.java) }

        for (matchedId in matchedIds) {
            val (left, right) = orderedPair(campgroundId, matchedId)
            ctx.execute(
                """
                INSERT INTO campground_matches (
                  campground_id, matched_campground_id, match_heuristic, updated_at
                ) VALUES (
                  ?, ?, ?::jsonb, now()
                )
                ON CONFLICT (campground_id, matched_campground_id)
                DO UPDATE SET
                  match_heuristic = EXCLUDED.match_heuristic,
                  updated_at = now()
                """.trimIndent(),
                left,
                right,
                matchHeuristic(CAMPGROUND_ENTITY, vendor, externalId, etlSource, refRole),
            )
        }
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
            val poiId = insertPoi(CAMPGROUND_POI_TYPE, longitude, latitude)
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

    private fun insertCampsite(
        campgroundId: Long,
        record: CampsiteEtlRecord,
        etlSource: String,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO campsites (
                  etl_source, campground_id, name, kind, loop_name, latitude, longitude, reservation_url,
                  equipment, kind_listed, schedule, price,
                  firepit, picnic_table, ada_accessible,
                  water_hookups, electric_hookups, sewer_hookups,
                  max_people, max_cars, pull_through, driveway_length,
                  max_rv_length, max_trailer_length, photos, source_payload
                ) VALUES (
                  ?, ?, ?, ?, ?, ?, ?, ?,
                  ?::jsonb, ?, ?::jsonb, ?::jsonb,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?, ?,
                  ?, ?, ?::jsonb, ?::jsonb
                )
                RETURNING id
                """.trimIndent(),
                etlSource,
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
        etlSource: String,
    ) {
        ctx.execute(
            """
            UPDATE campsites
            SET etl_source = ?,
                campground_id = ?,
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
            etlSource,
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
        primary: Boolean,
    ) {
        if (primary) {
            ctx.execute(
                "UPDATE campsite_vendor_refs SET is_primary = false, updated_at = now() WHERE campsite_id = ? AND vendor_ref_id <> ?",
                campsiteId,
                vendorRefId,
            )
        }
        ctx.execute(
            """
            INSERT INTO campsite_vendor_refs (campsite_id, vendor_ref_id, is_primary, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (campsite_id, vendor_ref_id)
            DO UPDATE SET
              is_primary = CASE WHEN ? THEN true ELSE campsite_vendor_refs.is_primary END,
              updated_at = now()
            """.trimIndent(),
            campsiteId,
            vendorRefId,
            primary,
            primary,
        )
    }

    private fun recordCampsiteMatchesForVendorRef(
        campsiteId: Long,
        vendorRefId: Long,
        vendor: String,
        externalId: String,
        etlSource: String,
        refRole: String,
    ) {
        val matchedIds =
            ctx
                .fetch(
                    """
                    SELECT campsite_id
                    FROM campsite_vendor_refs
                    WHERE vendor_ref_id = ?
                      AND campsite_id <> ?
                    ORDER BY campsite_id
                    """.trimIndent(),
                    vendorRefId,
                    campsiteId,
                ).map { it.get("campsite_id", Long::class.java) }

        for (matchedId in matchedIds) {
            val (left, right) = orderedPair(campsiteId, matchedId)
            ctx.execute(
                """
                INSERT INTO campsite_matches (
                  campsite_id, matched_campsite_id, match_heuristic, updated_at
                ) VALUES (
                  ?, ?, ?::jsonb, now()
                )
                ON CONFLICT (campsite_id, matched_campsite_id)
                DO UPDATE SET
                  match_heuristic = EXCLUDED.match_heuristic,
                  updated_at = now()
                """.trimIndent(),
                left,
                right,
                matchHeuristic(CAMPSITE_ENTITY, vendor, externalId, etlSource, refRole),
            )
        }
    }

    private fun orderedPair(
        a: Long,
        b: Long,
    ): Pair<Long, Long> = if (a < b) a to b else b to a

    private fun matchHeuristic(
        entityType: String,
        vendor: String,
        externalId: String,
        etlSource: String,
        refRole: String,
    ): String =
        buildJsonObject {
            put("kind", "shared_vendor_ref")
            put("entity_type", entityType)
            put("vendor", vendor)
            put("external_id", externalId)
            put("etl_source", etlSource)
            put("ref_role", refRole)
        }.toString()

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
        private const val PRIMARY_REF_ROLE = "primary"
        private const val ADDITIONAL_REF_ROLE = "additional"
    }
}
