package ca.floo.roadtrip.importer

import ca.floo.roadtrip.db.generated.tables.ImportRuns.Companion.IMPORT_RUNS
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ImportException(message: String) : RuntimeException(message)

data class ImportResult(
    val runId: Long,
    val seenCount: Int,
    val sweptCount: Int,
)

// Mark-and-sweep import for a single Source.
//   1. Open import_runs row (status='started').
//   2. UPSERT every staged row, stamping last_seen_run_id with this run.
//   3. Tripwire: if seen < 0.5 * existing-active, abort before sweep so a
//      partial fetch can't wipe the table.
//   4. Sweep: soft-delete any active row from this source whose
//      last_seen_run_id != current run.
//   5. Mark run completed.
class Importer(private val ctx: DSLContext) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(source: Source): ImportResult {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val runId = ctx.insertInto(IMPORT_RUNS)
            .set(IMPORT_RUNS.SOURCE, source.name)
            .set(IMPORT_RUNS.STATUS, "started")
            .set(IMPORT_RUNS.STARTED_AT, now)
            .returningResult(IMPORT_RUNS.ID)
            .fetchOne()!!.value1()!!
        log.info("import_runs id={} source={} started", runId, source.name)

        try {
            val existingActive = ctx.fetchCount(
                POIS,
                POIS.SOURCE.eq(source.name).and(POIS.DELETED_AT.isNull)
            )

            var seen = 0
            for (poi in source.staged()) {
                upsert(source.name, poi, runId)
                seen++
                if (seen % 1000 == 0) log.info("  upserted {} rows", seen)
            }
            log.info("staged {} rows from source={} (existing active={})", seen, source.name, existingActive)

            // Tripwire: if a fetch silently truncates upstream we'd otherwise
            // sweep the whole table. 0.5 is conservative; tune per source.
            if (existingActive > 0 && seen < existingActive / 2) {
                fail(runId, "tripwire: seen=$seen < existing/2=${existingActive / 2}")
                throw ImportException(
                    "Aborted: seen=$seen < existing/2=${existingActive / 2} for source=${source.name}"
                )
            }

            val swept = sweep(source.name, runId)
            log.info("swept {} rows (soft-deleted) from source={}", swept, source.name)

            ctx.update(IMPORT_RUNS)
                .set(IMPORT_RUNS.STATUS, "completed")
                .set(IMPORT_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(IMPORT_RUNS.SEEN_COUNT, seen)
                .where(IMPORT_RUNS.ID.eq(runId))
                .execute()

            return ImportResult(runId, seen, swept)
        } catch (e: Exception) {
            if (e !is ImportException) fail(runId, "unhandled: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun upsert(source: String, poi: StagedPoi, runId: Long) {
        // jOOQ has no PostGIS bindings, so geom goes through ST_GeomFromGeoJSON
        // wrapped in ST_SetSRID. Polygon/MultiPolygon work the same as Point.
        val fetchedAtTs = OffsetDateTime.ofInstant(poi.fetchedAt, ZoneOffset.UTC)

        ctx.execute(
            """
            INSERT INTO pois (
              source, source_id, category, name, geom, region, unit_name,
              properties, reserve_url, fetched_at, last_seen_run_id, deleted_at
            )
            VALUES (?, ?, ?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), ?, ?, ?::jsonb, ?, ?::timestamptz, ?, NULL)
            ON CONFLICT (source, source_id) DO UPDATE SET
              category         = EXCLUDED.category,
              name             = EXCLUDED.name,
              geom             = EXCLUDED.geom,
              region           = EXCLUDED.region,
              unit_name        = EXCLUDED.unit_name,
              properties       = EXCLUDED.properties,
              reserve_url      = EXCLUDED.reserve_url,
              fetched_at       = EXCLUDED.fetched_at,
              last_seen_run_id = EXCLUDED.last_seen_run_id,
              deleted_at       = NULL,
              updated_at       = NOW()
            """.trimIndent(),
            source, poi.sourceId, poi.category.sql, poi.name, poi.geomGeoJson,
            poi.region, poi.unitName, poi.properties.toString(),
            poi.reserveUrl, fetchedAtTs, runId,
        )
    }

    private fun sweep(source: String, runId: Long): Int {
        return ctx.update(POIS)
            .set(POIS.DELETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(POIS.SOURCE.eq(source))
            .and(POIS.DELETED_AT.isNull)
            .and(POIS.LAST_SEEN_RUN_ID.ne(runId).or(POIS.LAST_SEEN_RUN_ID.isNull))
            .execute()
    }

    private fun fail(runId: Long, notes: String) {
        ctx.update(IMPORT_RUNS)
            .set(IMPORT_RUNS.STATUS, "failed")
            .set(IMPORT_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(IMPORT_RUNS.NOTES, notes)
            .where(IMPORT_RUNS.ID.eq(runId))
            .execute()
    }
}

// Standalone entry: `gradle importer --args="uscampgrounds"` or `--args="all"`.
// Known source names: uscampgrounds, alberta-provincial, parks-canada,
// state-parks, national-parks, osm-pf. "all" expands to every known source.
fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("Importer")
    val dataDir = System.getenv("ROADTRIP_DATA_DIR")?.let(::File) ?: File("data")
    val requested = args.toList().ifEmpty { listOf("uscampgrounds") }
    val sources = if (requested == listOf("all")) {
        listOf("uscampgrounds", "alberta-provincial", "parks-canada", "state-parks", "national-parks", "osm-pf")
    } else requested

    val ds = dataSourceFor(DbConfig.fromEnv())
    migrate(ds)
    val ctx = dsl(ds)
    val importer = Importer(ctx)

    for (s in sources) {
        val src: Source = sourceFor(s, dataDir)
        val result = importer.run(src)
        log.info("DONE source={} runId={} seen={} swept={}", src.name, result.runId, result.seenCount, result.sweptCount)
    }
    ds.close()
}

private fun sourceFor(name: String, dataDir: File): Source = when (name) {
    "uscampgrounds" -> UsCampgroundsSource(required(File(dataDir, "campgrounds.geojson")))
    "alberta-provincial" -> AlbertaProvincialSource(required(File(dataDir, "alberta-provincial.json")))
    "parks-canada" -> ParksCanadaSource(listOf(
        required(File(dataDir, "parks-canada-bc.json")),
        required(File(dataDir, "parks-canada-ab.json")),
    ))
    "state-parks" -> ParksGeoJsonSource(required(File(dataDir, "state-parks.geojson")), "state-parks", Category.STATE_PARK)
    "national-parks" -> ParksGeoJsonSource(required(File(dataDir, "national-parks.geojson")), "national-parks", Category.NATIONAL_PARK)
    "osm-pf" -> PlanetFitnessSource(required(File(dataDir, "planet-fitness.geojson")))
    else -> error("unknown source: $name")
}

private fun required(f: File): File {
    require(f.exists()) { "missing data file: ${f.absolutePath}" }
    return f
}
