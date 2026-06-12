package ca.floo.roadtrip.service.etl

import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.models.ValidationResult
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.migrate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Validates that the orchestrator dispatches by YAML section:
 *   - reservable_data rows go through runReservableData → ReservableRepo
 *   - poi_reservable_joiner rows go through runJoiner → linkToPoi
 *
 * Uses synthesized YAML + fake adapters so the test exercises only the
 * dispatch shape, not vendor-specific fetchers. PR 3 adds end-to-end
 * tests with the real recgov + aspira adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SectionDispatchTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext
    private lateinit var rawDir: File
    private lateinit var registry: PoiRegistry
    private lateinit var reservables: ReservableRepo

    @BeforeAll
    fun setUp() {
        pg = PostgreSQLContainer(DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
        pg
            .withDatabaseName("roadtrip")
            .withUsername("test")
            .withPassword("test")
            .start()
        val cfg =
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                username = pg.username
                password = pg.password
            }
        ds = HikariDataSource(cfg)
        migrate(ds)
        ctx = DSL.using(ds, SQLDialect.POSTGRES)
        reservables = ReservableRepo(ctx)
        rawDir = Files.createTempDirectory("orch-section-").toFile()
        registry = synthesizeRegistry(rawDir)
    }

    @AfterAll
    fun tearDown() {
        ds.close()
        pg.stop()
        rawDir.deleteRecursively()
    }

    @BeforeEach
    fun reset() {
        ctx.execute("TRUNCATE reservable_pois, reservables RESTART IDENTITY CASCADE")
        ctx.execute("TRUNCATE pois RESTART IDENTITY CASCADE")
    }

    @Test
    fun `runReservableData upserts via ReservableRepo and skips Pois Upsert`() {
        val rid = ReservableId(ReservableType.SITE, "recgov", "330257")
        val fakeEtl =
            FakeReservableEtl(
                slug = "fake-reservable-terminal",
                output =
                    ReservableEtlOutput(
                        reservables =
                            listOf(
                                ReservableRepo.Input(
                                    rid = rid,
                                    name = "FS1-20",
                                    loop = "Loop A",
                                    siteType = "STANDARD",
                                    raw = null,
                                ),
                            ),
                    ),
            )
        val orch =
            EtlOrchestrator(
                ctx = ctx,
                rawDir = rawDir,
                poiRegistry = registry,
                etlRegistry = mapOf(fakeEtl.etlSlug to fakeEtl),
                joinerRegistry = emptyMap(),
            )
        val stats = orch.runReservableData("Fake Reservable Source")
        assertEquals("fake-reservable-terminal", stats.terminalEtlSlug)
        assertEquals(1, stats.parsed)
        assertEquals(1, stats.upserted)
        assertNotNull(reservables.findByRid(rid))
    }

    @Test
    fun `runJoiner links reservables to POIs via the adapter`() {
        // Pre-populate one reservable + one POI; the fake joiner reports
        // the pair. Orchestrator turns that into a reservable_pois row.
        val rid = ReservableId(ReservableType.SITE, "recgov", "330257")
        val reservableId =
            reservables.upsert(ReservableRepo.Input(rid = rid, name = "FS1-20", loop = null, siteType = null, raw = null))
        val poiId = insertCampgroundPoi(source = "fake-cg", sourceId = "232447", name = "Upper Pines")

        val fakeJoiner =
            FakeJoiner(
                adapter = "FakeJoinerAdapter",
                links = listOf(PoiReservableJoiner.Link(reservableId = reservableId, poiId = poiId)),
            )
        val orch =
            EtlOrchestrator(
                ctx = ctx,
                rawDir = rawDir,
                poiRegistry = registry,
                etlRegistry = emptyMap(),
                joinerRegistry = mapOf(fakeJoiner.adapter to fakeJoiner),
            )
        val stats = orch.runJoiner("Fake Joiner")
        assertEquals(1, stats.linksDiscovered)
        assertEquals(1, stats.linksInserted)
        assertEquals(1, reservables.countByPoi(poiId, ReservableType.SITE))
    }

    @Test
    fun `runJoiner is idempotent — re-running creates no duplicate links`() {
        val rid = ReservableId(ReservableType.SITE, "recgov", "330257")
        val reservableId =
            reservables.upsert(ReservableRepo.Input(rid = rid, name = "FS1-20", loop = null, siteType = null, raw = null))
        val poiId = insertCampgroundPoi(source = "fake-cg", sourceId = "232447", name = "Upper Pines")

        val fakeJoiner =
            FakeJoiner(
                adapter = "FakeJoinerAdapter",
                links = listOf(PoiReservableJoiner.Link(reservableId = reservableId, poiId = poiId)),
            )
        val orch =
            EtlOrchestrator(
                ctx = ctx,
                rawDir = rawDir,
                poiRegistry = registry,
                etlRegistry = emptyMap(),
                joinerRegistry = mapOf(fakeJoiner.adapter to fakeJoiner),
            )
        orch.runJoiner("Fake Joiner")
        orch.runJoiner("Fake Joiner")
        assertEquals(1, reservables.countByPoi(poiId, ReservableType.SITE))
    }

    private fun insertCampgroundPoi(
        source: String,
        sourceId: String,
        name: String,
    ): Long =
        ctx
            .resultQuery(
                """
                INSERT INTO pois (source, source_id, category, name, geom, fetched_at)
                VALUES (?, ?, 'campground', ?, ST_SetSRID(ST_MakePoint(-119.5, 37.7), 4326), now())
                RETURNING id
                """.trimIndent(),
                source,
                sourceId,
                name,
            ).fetchOne()!!
            .get(0, Long::class.java)!!

    /**
     * Synthesize a registry that has one reservable_data row using the
     * fake terminal etl + one poi_reservable_joiner row using the fake
     * joiner adapter. Empty data_source captures keep input resolution
     * happy without any real upstream data.
     */
    private fun synthesizeRegistry(rawDir: File): PoiRegistry {
        val sourceDir = File(rawDir, "fake-reservable-source")
        sourceDir.mkdirs()
        File(sourceDir, "2026-01-01T00-00-00Z.json").writeText(
            """
            {
              "fetcher": "synth",
              "fetcher_version": "1",
              "fetched_at": "2026-01-01T00:00:00Z",
              "request":  { "url": "synth://fake", "method": "GET" },
              "response": { "status": 200 },
              "payload":  {}
            }
            """.trimIndent(),
        )

        val yaml =
            """
            data_sources:
              - slug: fake-reservable-source
                name: Fake reservable source
                fetcher:
                  executor: python3
                  filename: scripts/noop.py
                  args: { slug: fake-reservable-source }
                  output_dir_prefix: data/raw/fake-reservable-source
            poi_data: []
            reservable_data:
              - name: Fake Reservable Source
                etls:
                  - slug: fake-reservable-terminal
                    adapter: FakeReservableEtl
                    inputs: [fake-reservable-source]
            poi_reservable_joiner:
              - name: Fake Joiner
                adapter: FakeJoinerAdapter
            """.trimIndent()
        val tmp = Files.createTempFile("synth-section-registry-", ".yaml").toFile()
        tmp.writeText(yaml)
        return PoiRegistry.load(tmp).also { tmp.deleteOnExit() }
    }
}

/** Test-only ReservableTerminalEtl that ignores its input and emits a fixed payload. */
private class FakeReservableEtl(
    private val slug: String,
    private val output: ReservableEtlOutput,
) : SourceEtl<Any, ReservableEtlOutput> {
    override val etlSlug: String get() = slug

    override fun parse(inputs: InputBundle): Any = Unit

    override fun validate(dto: Any): ValidationResult<Any> = ValidationResult.Ok(dto)

    override fun transform(
        dto: Any,
        ctx: TransformCtx,
    ): ReservableEtlOutput = output
}

/** Test-only joiner adapter that returns a fixed list of links. */
private class FakeJoiner(
    override val adapter: String,
    private val links: List<PoiReservableJoiner.Link>,
) : PoiReservableJoiner {
    override fun discoverLinks(ctx: JoinerCtx): List<PoiReservableJoiner.Link> = links
}
