package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CatalogUpsertResult
import ca.floo.roadtrip.model.domain.PlanetFitnessLocation
import ca.floo.roadtrip.model.domain.PlanetFitnessLocationUpsertCandidate
import ca.floo.roadtrip.model.domain.poi.PlanetFitnessLocationPoiDetail
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL

/**
 * Persistence boundary for Planet Fitness location catalog reads and writes.
 */
class PlanetFitnessLocationRepo(
    private val ctx: DSLContext,
) {
    private val importRunRepo = ImportRunRepo(ctx)
    private val poiRepo = PoiRepo(ctx)

    fun upsertPlanetFitnessLocations(
        records: List<PlanetFitnessLocationUpsertCandidate>,
        source: String,
    ): CatalogUpsertResult {
        val runId = importRunRepo.start(source)
        try {
            val upserted = upsertPlanetFitnessLocationBatch(records)
            importRunRepo.complete(runId, records.size)
            return CatalogUpsertResult(runId = runId, seenCount = records.size, upsertedCount = upserted)
        } catch (e: Throwable) {
            importRunRepo.fail(runId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    fun upsertPlanetFitnessLocationBatch(records: List<PlanetFitnessLocationUpsertCandidate>): Int {
        requireCatalogBatchWithinLimit("planet fitness location upsert", records.size)
        return ctx.transactionResult { cfg ->
            val tx = PlanetFitnessLocationRepo(DSL.using(cfg))
            records.sumOf { record -> if (tx.upsertPlanetFitnessLocation(record)) 1 else 0 }
        }
    }

    fun findById(id: Long): PlanetFitnessLocation? =
        ctx
            .fetchOne(
                "$baseSelect WHERE pfl.id = ? AND pfl.deleted_at IS NULL",
                id,
            )?.let(::fromRecord)

    fun findByLocationId(locationId: String): PlanetFitnessLocation? =
        ctx
            .fetchOne(
                "$baseSelect WHERE pfl.location_id = ? AND pfl.deleted_at IS NULL",
                locationId,
            )?.let(::fromRecord)

    fun findByPoi(poiId: Long): PlanetFitnessLocation? =
        ctx
            .fetchOne(
                """
                $baseSelect
                JOIN poi_planet_fitness_locations ppf
                  ON ppf.planet_fitness_location_id = pfl.id
                JOIN pois p
                  ON p.id = ppf.poi_id
                WHERE ppf.poi_id = ?
                  AND pfl.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            )?.let(::fromRecord)

    fun findPoiDetailByPoi(poiId: Long): PlanetFitnessLocationPoiDetail? {
        val record =
            ctx.fetchOne(
                """
                SELECT
                  $baseSelectColumns,
                  to_jsonb(pfl)::text AS properties_text
                FROM planet_fitness_locations pfl
                JOIN poi_planet_fitness_locations ppf
                  ON ppf.planet_fitness_location_id = pfl.id
                JOIN pois p
                  ON p.id = ppf.poi_id
                WHERE ppf.poi_id = ?
                  AND pfl.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            ) ?: return null
        return PlanetFitnessLocationPoiDetail(
            location = fromRecord(record),
            propertiesJson = record.get("properties_text", String::class.java),
        )
    }

    private fun fromRecord(record: Record): PlanetFitnessLocation =
        PlanetFitnessLocation(
            id = record.get("id", Long::class.java),
            locationId = record.get("location_id", String::class.java),
            name = record.get("name", String::class.java),
            address = parseJsonElement(record.get("address_text", String::class.java)),
            region = record.get("region", String::class.java),
            country = record.get("country", String::class.java),
            phone = record.get("phone", String::class.java),
            infoUrl = record.get("info_url", String::class.java),
            openingHours = record.get("opening_hours", String::class.java),
            amenities = parseJsonElement(record.get("amenities_text", String::class.java)),
            payload = parseJsonElement(record.get("payload_text", String::class.java)),
            createdAt = record.instant("created_at"),
            updatedAt = record.instant("updated_at"),
            deletedAt = record.nullableInstant("deleted_at"),
        )

    private fun upsertPlanetFitnessLocation(record: PlanetFitnessLocationUpsertCandidate): Boolean {
        val locationId =
            planetFitnessLocationIdForLocationId(record.locationId)
                ?.also { updatePlanetFitnessLocation(it, record) }
                ?: insertPlanetFitnessLocation(record)
        upsertPlanetFitnessLocationPoi(locationId, record.longitude, record.latitude)
        return true
    }

    private fun planetFitnessLocationIdForLocationId(locationId: String): Long? =
        ctx
            .fetchOne(
                "SELECT id FROM planet_fitness_locations WHERE location_id = ?",
                locationId,
            )?.get("id", Long::class.java)

    private fun insertPlanetFitnessLocation(record: PlanetFitnessLocationUpsertCandidate): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO planet_fitness_locations (
                  location_id, name, address, region, country, phone, info_url,
                  opening_hours, amenities, payload
                ) VALUES (
                  ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb
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
                record.openingHours,
                jsonArray(record.amenities),
                jsonObject(record.payload),
            )!!
            .get("id", Long::class.java)

    private fun updatePlanetFitnessLocation(
        locationId: Long,
        record: PlanetFitnessLocationUpsertCandidate,
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
                opening_hours = ?,
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
            record.openingHours,
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
            val poiId = poiRepo.insertPoi(PLANET_FITNESS_LOCATION_POI_TYPE, longitude, latitude)
            ctx.execute(
                "INSERT INTO poi_planet_fitness_locations (poi_id, planet_fitness_location_id) VALUES (?, ?)",
                poiId,
                locationId,
            )
        } else {
            poiRepo.updatePoiGeometry(existingPoiId, longitude, latitude)
        }
    }

    private companion object {
        private const val PLANET_FITNESS_LOCATION_POI_TYPE = "planet_fitness_location"

        private val baseSelectColumns =
            """
            pfl.id,
            pfl.location_id,
            pfl.name,
            pfl.address::text AS address_text,
            pfl.region,
            pfl.country,
            pfl.phone,
            pfl.info_url,
            pfl.opening_hours,
            pfl.amenities::text AS amenities_text,
            pfl.payload::text AS payload_text,
            pfl.created_at,
            pfl.updated_at,
            pfl.deleted_at
            """.trimIndent()

        private val baseSelect =
            """
            SELECT
              $baseSelectColumns
            FROM planet_fitness_locations pfl
            """.trimIndent()
    }
}
