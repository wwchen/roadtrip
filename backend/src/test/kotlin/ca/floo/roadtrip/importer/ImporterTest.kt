package ca.floo.roadtrip.importer

import ca.floo.roadtrip.db.generated.tables.ImportRuns.Companion.IMPORT_RUNS
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImporterTest {

    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext

    @BeforeAll
    fun startPg() {
        // Same Docker Desktop 29 hotfix as the build's generateJooq task.
        System.setProperty("api.version", "1.44")
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg = PostgreSQLContainer<Nothing>(image).apply {
            withDatabaseName("roadtrip_test")
            withUsername("test")
            withPassword("test")
        }
        pg.start()

        val cfg = HikariConfig().apply {
            jdbcUrl = pg.jdbcUrl
            username = pg.username
            password = pg.password
            maximumPoolSize = 2
        }
        ds = HikariDataSource(cfg)
        migrate(ds)
        ctx = DSL.using(ds, SQLDialect.POSTGRES)
    }

    @AfterAll
    fun stopPg() {
        ds.close()
        pg.stop()
    }

    @BeforeEach
    fun reset() {
        ctx.deleteFrom(POIS).execute()
        ctx.deleteFrom(IMPORT_RUNS).execute()
    }

    @Test
    fun `first run inserts all rows and marks run completed`() {
        val src = FakeSource("uscampgrounds", listOf(
            staged("alli-ak-aaaaaaaa", "Allison Point", -146.343, 61.086),
            staged("auk-ak-bbbbbbbb", "Auk Village", -134.737, 58.381),
        ))
        val result = Importer(ctx).run(src)

        assertEquals(2, result.seenCount)
        assertEquals(0, result.sweptCount)
        assertEquals(2, ctx.fetchCount(POIS, POIS.DELETED_AT.isNull))
        val run = ctx.selectFrom(IMPORT_RUNS).where(IMPORT_RUNS.ID.eq(result.runId)).fetchOne()!!
        assertEquals("completed", run.status)
        assertEquals(2, run.seenCount)
    }

    @Test
    fun `re-running is idempotent`() {
        val src = FakeSource("uscampgrounds", listOf(
            staged("alli-ak-aaaaaaaa", "Allison Point", -146.343, 61.086),
            staged("auk-ak-bbbbbbbb", "Auk Village", -134.737, 58.381),
        ))
        Importer(ctx).run(src)
        val firstCount = ctx.fetchCount(POIS)

        Importer(ctx).run(src)
        val secondCount = ctx.fetchCount(POIS)

        // No duplicates introduced; UPSERT updates in place.
        assertEquals(firstCount, secondCount)
        assertEquals(2, ctx.fetchCount(POIS, POIS.DELETED_AT.isNull))
    }

    @Test
    fun `removed source rows are soft-deleted on next run`() {
        val first = FakeSource("uscampgrounds", listOf(
            staged("alli-ak-aaaaaaaa", "Allison Point", -146.343, 61.086),
            staged("auk-ak-bbbbbbbb", "Auk Village", -134.737, 58.381),
            staged("anch-ak-cccccccc", "Anchor River", -151.865, 59.768),
        ))
        Importer(ctx).run(first)

        val second = FakeSource("uscampgrounds", listOf(
            staged("alli-ak-aaaaaaaa", "Allison Point", -146.343, 61.086),
            staged("auk-ak-bbbbbbbb", "Auk Village", -134.737, 58.381),
            // anch dropped
        ))
        val result = Importer(ctx).run(second)

        assertEquals(2, result.seenCount)
        assertEquals(1, result.sweptCount)
        assertEquals(2, ctx.fetchCount(POIS, POIS.DELETED_AT.isNull))
        // Soft-deleted row is still present with deleted_at set.
        assertEquals(3, ctx.fetchCount(POIS))
        val deletedAt = ctx.select(POIS.DELETED_AT)
            .from(POIS)
            .where(POIS.SOURCE_ID.eq("anch-ak-cccccccc"))
            .fetchOne()!!.value1()
        assertNotNull(deletedAt, "swept row must have deleted_at set")
    }

    @Test
    fun `re-adding a previously deleted source_id resurrects it (deleted_at cleared)`() {
        val first = FakeSource("uscampgrounds", listOf(
            staged("alli-ak-aaaaaaaa", "Allison Point", -146.343, 61.086),
            staged("auk-ak-bbbbbbbb", "Auk Village", -134.737, 58.381),
        ))
        Importer(ctx).run(first)

        Importer(ctx).run(FakeSource("uscampgrounds", listOf(
            staged("alli-ak-aaaaaaaa", "Allison Point", -146.343, 61.086),
        )))
        // auk now soft-deleted
        val deletedBefore = ctx.select(POIS.DELETED_AT).from(POIS)
            .where(POIS.SOURCE_ID.eq("auk-ak-bbbbbbbb")).fetchOne()!!.value1()
        assertNotNull(deletedBefore)

        Importer(ctx).run(first)
        val deletedAfter = ctx.select(POIS.DELETED_AT).from(POIS)
            .where(POIS.SOURCE_ID.eq("auk-ak-bbbbbbbb")).fetchOne()!!.value1()
        assertNull(deletedAfter, "resurrected row must have deleted_at cleared")
    }

    @Test
    fun `tripwire aborts when seen drops below half existing-active`() {
        // Seed 10 active rows.
        val many = (1..10).map { i ->
            staged("c$i-ak-${"%08x".format(i)}", "Site $i", -150.0 + i, 60.0 + i.toDouble() * 0.01)
        }
        Importer(ctx).run(FakeSource("uscampgrounds", many))
        assertEquals(10, ctx.fetchCount(POIS, POIS.DELETED_AT.isNull))

        // Drop to 4 — below 10/2 = 5. Importer must abort.
        val tiny = many.take(4)
        try {
            Importer(ctx).run(FakeSource("uscampgrounds", tiny))
            fail("Expected ImportException")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("seen=4"))
        }

        // Original rows must still be active (no sweep happened).
        assertEquals(10, ctx.fetchCount(POIS, POIS.DELETED_AT.isNull))
        // Latest run is marked failed.
        val latest = ctx.selectFrom(IMPORT_RUNS)
            .orderBy(IMPORT_RUNS.ID.desc()).limit(1).fetchOne()!!
        assertEquals("failed", latest.status)
    }

    @Test
    fun `tripwire allows shrinking when above half`() {
        // Seed 10, drop to 6 (60%) — above 50% floor, must succeed.
        val many = (1..10).map { i ->
            staged("c$i-ak-${"%08x".format(i)}", "Site $i", -150.0 + i, 60.0 + i.toDouble() * 0.01)
        }
        Importer(ctx).run(FakeSource("uscampgrounds", many))

        val result = Importer(ctx).run(FakeSource("uscampgrounds", many.take(6)))
        assertEquals(6, result.seenCount)
        assertEquals(4, result.sweptCount)
        assertEquals(6, ctx.fetchCount(POIS, POIS.DELETED_AT.isNull))
    }

    @Test
    fun `sweep on one source does not touch rows from a different source`() {
        // Hard-fail safeguard: each source's mark-and-sweep must be scoped by
        // SOURCE column. If a source lacks a row in its next run, only its own
        // rows should soft-delete — never another source's.
        Importer(ctx).run(FakeSource("alpha", listOf(
            staged("a-1", "Alpha One", -123.0, 49.0),
            staged("a-2", "Alpha Two", -123.1, 49.1),
        )))
        Importer(ctx).run(FakeSource("beta", listOf(
            staged("b-1", "Beta One", -122.0, 50.0),
            staged("b-2", "Beta Two", -122.1, 50.1),
        )))
        assertEquals(4, ctx.fetchCount(POIS, POIS.DELETED_AT.isNull))

        // Re-run alpha with one row dropped. beta must remain untouched.
        Importer(ctx).run(FakeSource("alpha", listOf(
            staged("a-1", "Alpha One", -123.0, 49.0),
        )))

        val activeAlpha = ctx.fetchCount(POIS, POIS.SOURCE.eq("alpha").and(POIS.DELETED_AT.isNull))
        val activeBeta = ctx.fetchCount(POIS, POIS.SOURCE.eq("beta").and(POIS.DELETED_AT.isNull))
        assertEquals(1, activeAlpha)
        assertEquals(2, activeBeta, "beta rows must remain active after alpha sweep")
    }

    @Test
    fun `properties JSONB round-trips and is replaced on re-upsert`() {
        // The properties column carries source-specific fields like
        // last_verified, season, amenities. The webapp's flattenPoi() reads
        // them via raw.*, so the JSON shape must round-trip byte-for-byte.
        val first = StagedPoi(
            sourceId = "props-1",
            category = Category.CAMPGROUND,
            name = "Tunnel Mountain Village I",
            geomGeoJson = pointGeoJson(-115.547, 51.1812),
            region = "AB",
            unitName = "Banff",
            properties = buildJsonObject {
                put("season", "mid-May to early October")
                put("last_verified", "2026-06-03")
                put("reservable", true)
            },
            reserveUrl = "https://reservation.pc.gc.ca",
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
        )
        Importer(ctx).run(FakeSource("uscampgrounds", listOf(first)))

        val storedJson = ctx.select(POIS.PROPERTIES).from(POIS)
            .where(POIS.SOURCE_ID.eq("props-1"))
            .fetchOne()!!.value1()!!.data()
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(storedJson).jsonObject
        assertEquals("mid-May to early October", parsed["season"]!!.jsonPrimitive.content)
        assertEquals("2026-06-03", parsed["last_verified"]!!.jsonPrimitive.content)
        assertEquals(true, parsed["reservable"]!!.jsonPrimitive.boolean)

        // Re-import with a corrected last_verified — the JSONB column must be
        // fully replaced (the source is authoritative; we don't deep-merge).
        val second = first.copy(
            properties = buildJsonObject {
                put("season", "year-round")
                put("last_verified", "2026-07-01")
                put("reservable", true)
            }
        )
        Importer(ctx).run(FakeSource("uscampgrounds", listOf(second)))

        val updatedJson = ctx.select(POIS.PROPERTIES).from(POIS)
            .where(POIS.SOURCE_ID.eq("props-1"))
            .fetchOne()!!.value1()!!.data()
        val updated = kotlinx.serialization.json.Json.parseToJsonElement(updatedJson).jsonObject
        assertEquals("year-round", updated["season"]!!.jsonPrimitive.content)
        assertEquals("2026-07-01", updated["last_verified"]!!.jsonPrimitive.content)
    }

    @Test
    fun `bbox query returns only points inside the envelope`() {
        // Use raw SQL to validate GIST + ST_MakeEnvelope path end-to-end.
        Importer(ctx).run(FakeSource("uscampgrounds", listOf(
            staged("inside-ca-aaaaaaaa", "Inside", -123.0, 49.0),     // Vancouver-ish
            staged("outside-fl-bbbbbbbb", "Outside", -80.0, 25.0),    // Miami-ish
        )))

        val rows = ctx.fetch(
            "SELECT name FROM pois WHERE deleted_at IS NULL AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)",
            -125.0, 47.0, -120.0, 51.0,
        )
        assertEquals(1, rows.size)
        assertEquals("Inside", rows[0].get("name"))
    }

    private fun staged(sourceId: String, name: String, lon: Double, lat: Double) = StagedPoi(
        sourceId = sourceId,
        category = Category.CAMPGROUND,
        name = name,
        geomGeoJson = pointGeoJson(lon, lat),
        region = "AK",
        unitName = null,
        properties = buildJsonObject { put("test", true) },
        reserveUrl = null,
        fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
    )

    private class FakeSource(
        override val name: String,
        private val rows: List<StagedPoi>,
    ) : Source {
        override fun staged(): Sequence<StagedPoi> = rows.asSequence()
    }
}
