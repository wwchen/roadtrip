package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CatalogUpsertResult
import ca.floo.roadtrip.model.domain.TeslaSupercharger
import ca.floo.roadtrip.model.domain.TeslaSuperchargerUpsertCandidate
import ca.floo.roadtrip.model.domain.poi.TeslaSuperchargerPoiDetail
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL

/**
 * Persistence boundary for Tesla Supercharger catalog reads and writes.
 */
class TeslaSuperchargerRepo(
    private val ctx: DSLContext,
) {
    private val importRunRepo = ImportRunRepo(ctx)
    private val poiRepo = PoiRepo(ctx)

    fun upsertTeslaSuperchargers(
        records: List<TeslaSuperchargerUpsertCandidate>,
        source: String,
    ): CatalogUpsertResult {
        val runId = importRunRepo.start(source)
        try {
            val upserted = upsertTeslaSuperchargerBatch(records)
            importRunRepo.complete(runId, records.size)
            return CatalogUpsertResult(runId = runId, seenCount = records.size, upsertedCount = upserted)
        } catch (e: Throwable) {
            importRunRepo.fail(runId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    fun upsertTeslaSuperchargerBatch(records: List<TeslaSuperchargerUpsertCandidate>): Int {
        requireCatalogBatchWithinLimit("tesla supercharger upsert", records.size)
        return ctx.transactionResult { cfg ->
            val tx = TeslaSuperchargerRepo(DSL.using(cfg))
            records.sumOf { record -> if (tx.upsertTeslaSupercharger(record)) 1 else 0 }
        }
    }

    fun findById(id: Long): TeslaSupercharger? =
        ctx
            .fetchOne(
                "$baseSelect WHERE ts.id = ? AND ts.deleted_at IS NULL",
                id,
            )?.let(::fromRecord)

    fun findByLocationSlug(locationSlug: String): TeslaSupercharger? =
        ctx
            .fetchOne(
                "$baseSelect WHERE ts.location_slug = ? AND ts.deleted_at IS NULL",
                locationSlug,
            )?.let(::fromRecord)

    fun findByPoi(poiId: Long): TeslaSupercharger? =
        ctx
            .fetchOne(
                """
                $baseSelect
                JOIN poi_tesla_superchargers pts
                  ON pts.tesla_supercharger_id = ts.id
                JOIN pois p
                  ON p.id = pts.poi_id
                WHERE pts.poi_id = ?
                  AND ts.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            )?.let(::fromRecord)

    fun findPoiDetailByPoi(poiId: Long): TeslaSuperchargerPoiDetail? {
        val record =
            ctx.fetchOne(
                """
                SELECT
                  $baseSelectColumns,
                  to_jsonb(ts)::text AS properties_text
                FROM tesla_superchargers ts
                JOIN poi_tesla_superchargers pts
                  ON pts.tesla_supercharger_id = ts.id
                JOIN pois p
                  ON p.id = pts.poi_id
                WHERE pts.poi_id = ?
                  AND ts.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            ) ?: return null
        return TeslaSuperchargerPoiDetail(
            supercharger = fromRecord(record),
            propertiesJson = record.get("properties_text", String::class.java),
        )
    }

    private fun fromRecord(record: Record): TeslaSupercharger =
        TeslaSupercharger(
            id = record.get("id", Long::class.java),
            locationSlug = record.get("location_slug", String::class.java),
            locationGuid = record.get("location_guid", String::class.java),
            commonSiteName = record.get("common_site_name", String::class.java),
            siteStatus = record.get("site_status", String::class.java),
            accessType = record.get("access_type", String::class.java),
            openToPublic = record.get("open_to_public", Boolean::class.java),
            openToNonTeslas = record.get("open_to_non_teslas", Boolean::class.javaObjectType),
            trailerFriendly = record.get("trailer_friendly", Boolean::class.javaObjectType),
            twentyFourSeven = record.get("twenty_four_seven", Boolean::class.javaObjectType),
            stallCount = record.get("stall_count", Int::class.javaObjectType),
            maxPowerKw = record.get("max_power_kw", Int::class.javaObjectType),
            address = parseJsonElement(record.get("address_text", String::class.java)),
            region = record.get("region", String::class.java),
            country = record.get("country", String::class.java),
            timeZone = record.get("time_zone", String::class.java),
            amenities = parseJsonElement(record.get("amenities_text", String::class.java)),
            hardwareCounts = parseJsonElement(record.get("hardware_counts_text", String::class.java)),
            pricebooks = parseJsonElement(record.get("pricebooks_text", String::class.java)),
            availabilityProfile = parseJsonElement(record.get("availability_profile_text", String::class.java)),
            infoUrl = record.get("info_url", String::class.java),
            indexPayload = parseJsonElement(record.get("index_payload_text", String::class.java)),
            detailPayload = parseJsonElement(record.get("detail_payload_text", String::class.java)),
            createdAt = record.instant("created_at"),
            updatedAt = record.instant("updated_at"),
            deletedAt = record.nullableInstant("deleted_at"),
        )

    private fun upsertTeslaSupercharger(record: TeslaSuperchargerUpsertCandidate): Boolean {
        val superchargerId =
            teslaSuperchargerIdForLocationSlug(record.locationSlug)
                ?.also { updateTeslaSupercharger(it, record) }
                ?: insertTeslaSupercharger(record)
        upsertTeslaSuperchargerPoi(superchargerId, record.longitude, record.latitude)
        return true
    }

    private fun teslaSuperchargerIdForLocationSlug(locationSlug: String): Long? =
        ctx
            .fetchOne(
                "SELECT id FROM tesla_superchargers WHERE location_slug = ?",
                locationSlug,
            )?.get("id", Long::class.java)

    private fun insertTeslaSupercharger(record: TeslaSuperchargerUpsertCandidate): Long =
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
        record: TeslaSuperchargerUpsertCandidate,
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
            val poiId = poiRepo.insertPoi(TESLA_SUPERCHARGER_POI_TYPE, longitude, latitude)
            ctx.execute(
                "INSERT INTO poi_tesla_superchargers (poi_id, tesla_supercharger_id) VALUES (?, ?)",
                poiId,
                superchargerId,
            )
        } else {
            poiRepo.updatePoiGeometry(existingPoiId, longitude, latitude)
        }
    }

    private companion object {
        private const val TESLA_SUPERCHARGER_POI_TYPE = "tesla_supercharger"

        private val baseSelectColumns =
            """
            ts.id,
            ts.location_slug,
            ts.location_guid,
            ts.common_site_name,
            ts.site_status,
            ts.access_type,
            ts.open_to_public,
            ts.open_to_non_teslas,
            ts.trailer_friendly,
            ts.twenty_four_seven,
            ts.stall_count,
            ts.max_power_kw,
            ts.address::text AS address_text,
            ts.region,
            ts.country,
            ts.time_zone,
            ts.amenities::text AS amenities_text,
            ts.hardware_counts::text AS hardware_counts_text,
            ts.pricebooks::text AS pricebooks_text,
            ts.availability_profile::text AS availability_profile_text,
            ts.info_url,
            ts.index_payload::text AS index_payload_text,
            ts.detail_payload::text AS detail_payload_text,
            ts.created_at,
            ts.updated_at,
            ts.deleted_at
            """.trimIndent()

        private val baseSelect =
            """
            SELECT
              $baseSelectColumns
            FROM tesla_superchargers ts
            """.trimIndent()
    }
}
