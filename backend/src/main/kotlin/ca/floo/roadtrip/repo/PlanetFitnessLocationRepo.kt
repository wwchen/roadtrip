package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.domain.CatalogUpsertResult
import ca.floo.roadtrip.models.domain.PlanetFitnessLocation
import ca.floo.roadtrip.models.domain.PlanetFitnessLocationPoiDetail
import ca.floo.roadtrip.service.etl.framework.PlanetFitnessLocationUpsertCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Persistence boundary for Planet Fitness location catalog reads and writes.
 */
class PlanetFitnessLocationRepo(
    private val ctx: DSLContext,
) {
    private val importRuns = ImportRunRepo(ctx)
    private val pois = PoiCatalogRepo(ctx)

    fun upsertPlanetFitnessLocations(
        records: List<PlanetFitnessLocationUpsertCandidate>,
        source: String,
    ): CatalogUpsertResult {
        val runId = importRuns.start(source)
        try {
            val upserted =
                ctx.transactionResult { cfg ->
                    val tx = PlanetFitnessLocationRepo(DSL.using(cfg))
                    records.sumOf { record -> if (tx.upsertPlanetFitnessLocation(record)) 1 else 0 }
                }
            importRuns.complete(runId, records.size)
            return CatalogUpsertResult(runId = runId, seenCount = records.size, upsertedCount = upserted)
        } catch (e: Throwable) {
            importRuns.fail(runId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    fun findById(id: Long): PlanetFitnessLocation? =
        ctx
            .fetchOne(
                "$BASE_SELECT WHERE pfl.id = ? AND pfl.deleted_at IS NULL",
                id,
            )?.let(::fromRecord)

    fun findByLocationId(locationId: String): PlanetFitnessLocation? =
        ctx
            .fetchOne(
                "$BASE_SELECT WHERE pfl.location_id = ? AND pfl.deleted_at IS NULL",
                locationId,
            )?.let(::fromRecord)

    fun findByPoi(poiId: Long): PlanetFitnessLocation? =
        ctx
            .fetchOne(
                """
                $BASE_SELECT
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
                  $BASE_SELECT_COLUMNS,
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

    fun findAll(): List<PlanetFitnessLocation> =
        ctx
            .fetch(
                "$BASE_SELECT WHERE pfl.deleted_at IS NULL ORDER BY pfl.name, pfl.id",
            ).map(::fromRecord)

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
            amenities = parseJsonElement(record.get("amenities_text", String::class.java)),
            payload = parseJsonElement(record.get("payload_text", String::class.java)),
            createdAt = record.instant("created_at"),
            updatedAt = record.instant("updated_at"),
            deletedAt = record.nullableInstant("deleted_at"),
        )

    private fun parseJsonElement(raw: String): JsonElement = Json.parseToJsonElement(raw)

    private fun Record.instant(column: String): Instant = get(column, OffsetDateTime::class.java).toInstant()

    private fun Record.nullableInstant(column: String): Instant? = get(column, OffsetDateTime::class.java)?.toInstant()

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
            val poiId = pois.insertPoi(PLANET_FITNESS_LOCATION_POI_TYPE, longitude, latitude)
            ctx.execute(
                "INSERT INTO poi_planet_fitness_locations (poi_id, planet_fitness_location_id) VALUES (?, ?)",
                poiId,
                locationId,
            )
        } else {
            pois.updatePoiGeometry(existingPoiId, longitude, latitude)
        }
    }

    private companion object {
        private const val PLANET_FITNESS_LOCATION_POI_TYPE = "planet_fitness_location"

        private val BASE_SELECT_COLUMNS =
            """
            pfl.id,
            pfl.location_id,
            pfl.name,
            pfl.address::text AS address_text,
            pfl.region,
            pfl.country,
            pfl.phone,
            pfl.info_url,
            pfl.amenities::text AS amenities_text,
            pfl.payload::text AS payload_text,
            pfl.created_at,
            pfl.updated_at,
            pfl.deleted_at
            """.trimIndent()

        private val BASE_SELECT =
            """
            SELECT
              $BASE_SELECT_COLUMNS
            FROM planet_fitness_locations pfl
            """.trimIndent()
    }
}
