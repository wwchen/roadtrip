package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.registry.CampsiteDataEntry
import ca.floo.roadtrip.model.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.model.metadata.registry.EtlEntry
import ca.floo.roadtrip.model.metadata.registry.Fetcher
import ca.floo.roadtrip.model.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.MAX_CATALOG_UPSERT_BATCH_SIZE
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class EtlOrchestratorCampflareTest : SharedDbTest() {
    @TempDir
    lateinit var staticDir: File

    private val rawDir: File
        get() = staticDir.resolve("data/raw")

    @BeforeEach
    fun reset() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `imports campflare campgrounds and campsites into canonical catalog`() {
        writeRaw(
            slug = "campflare-campgrounds-export",
            payload =
                """
                [
                  {
                    "id":"cg-1",
                    "name":"Camp One",
                    "kind":"established",
                    "location":{"latitude":37.1,"longitude":-119.1},
                    "metadata":{"last_updated":"2026-07-08T00:00:00Z"}
                  }
                ]
                """.trimIndent(),
        )
        writeRaw(
            slug = "campflare-campsites-export",
            payload =
                """
                [
                  {
                    "id":"site-1",
                    "campground_id":"cg-1",
                    "name":"Site 1",
                    "kind":"standard",
                    "loop_name":"A"
                  }
                ]
                """.trimIndent(),
        )

        val orchestrator =
            EtlOrchestrator(
                ctx = ctx,
                rawDir = rawDir,
                poiRegistry = registry(),
                staticDir = staticDir,
            )

        val campgrounds = orchestrator.runPoiData("Campflare Campgrounds")
        val campsites = orchestrator.runCampsiteData("Campflare Campsites")

        assertEquals(1, campgrounds.upsertResult.upsertedCount)
        assertEquals(1, campsites.upserted)
        assertEquals(1, tableCount("campgrounds"))
        assertEquals(1, tableCount("campsites"))
        assertEquals(1, tableCount("pois"))
        assertEquals(1, tableCount("poi_campgrounds"))
    }

    @Test
    fun `counts parse transform and repo skipped rows without failing import`() {
        writeRaw(
            slug = "campflare-campgrounds-export",
            payload =
                """
                [
                  12,
                  {"id":"missing-location","name":"Missing Location"},
                  {"id":"cg-1","name":"Camp One","location":{"latitude":37.1,"longitude":-119.1}}
                ]
                """.trimIndent(),
        )
        writeRaw(
            slug = "campflare-campsites-export",
            payload =
                """
                [
                  {"id":"site-1","campground_id":"cg-1","name":"Site 1"},
                  {"id":"site-2","campground_id":"missing-parent","name":"Site 2"}
                ]
                """.trimIndent(),
        )

        val orchestrator =
            EtlOrchestrator(
                ctx = ctx,
                rawDir = rawDir,
                poiRegistry = registry(),
                staticDir = staticDir,
            )

        val campgrounds = orchestrator.runPoiData("Campflare Campgrounds")
        val campsites = orchestrator.runCampsiteData("Campflare Campsites")

        assertEquals(3, campgrounds.parsed)
        assertEquals(1, campgrounds.transformed)
        assertEquals(1, campgrounds.upsertResult.upsertedCount)
        assertEquals(2, campgrounds.upsertResult.skippedCount)
        assertEquals(2, campsites.parsed)
        assertEquals(1, campsites.upserted)
        assertEquals(1, campsites.skipped)
        assertEquals(1, tableCount("campgrounds"))
        assertEquals(1, tableCount("campsites"))
    }

    @Test
    fun `flushes terminal candidates across multiple repo batches`() {
        val count = MAX_CATALOG_UPSERT_BATCH_SIZE + 1
        writeRaw(
            slug = "campflare-campgrounds-export",
            payload = campgroundsPayload(count),
        )
        writeRaw(
            slug = "campflare-campsites-export",
            payload = campsitesPayload(count),
        )

        val orchestrator =
            EtlOrchestrator(
                ctx = ctx,
                rawDir = rawDir,
                poiRegistry = registry(),
                staticDir = staticDir,
            )

        val campgrounds = orchestrator.runPoiData("Campflare Campgrounds")
        val campsites = orchestrator.runCampsiteData("Campflare Campsites")

        assertEquals(count, campgrounds.upsertResult.upsertedCount)
        assertEquals(count, campsites.upserted)
        assertEquals(0, campsites.skipped)
        assertEquals(count, tableCount("campgrounds"))
        assertEquals(count, tableCount("campsites"))
    }

    private fun writeRaw(
        slug: String,
        payload: String,
    ) {
        val dir = rawDir.resolve(slug).resolve("2026-07-08T00-00-00Z")
        dir.mkdirs()
        dir.resolve("part-000001.json").writeText(
            """
            {
              "fetcher":"fetch_campflare_dump",
              "fetcher_version":"1",
              "fetched_at":"2026-07-08T00:00:00Z",
              "request":{"url":"https://api.campflare.com/dumps/test/$slug.json.gz","method":"GET","headers":{}},
              "response":{"status":200,"headers":{}},
              "poller_run_id":null,
              "payload":$payload,
              "part":"part-000001"
            }
            """.trimIndent(),
        )
    }

    private fun campgroundsPayload(count: Int): String =
        (0 until count).joinToString(prefix = "[", postfix = "]") { i ->
            """
            {"id":"cg-$i","name":"Camp $i","location":{"latitude":37.1,"longitude":-119.1}}
            """.trimIndent()
        }

    private fun campsitesPayload(count: Int): String =
        (0 until count).joinToString(prefix = "[", postfix = "]") { i ->
            """
            {"id":"site-$i","campground_id":"cg-$i","name":"Site $i"}
            """.trimIndent()
        }

    private fun registry(): PoiRegistry =
        PoiRegistry(
            dataSources =
                listOf(
                    dataSource("campflare-campgrounds-export"),
                    dataSource("campflare-campsites-export"),
                ),
            poiData =
                listOf(
                    PoiDataEntry(
                        name = "Campflare Campgrounds",
                        category = "campground",
                        etls =
                            listOf(
                                EtlEntry(
                                    slug = "campflare-campgrounds",
                                    adapter = "CampflareCampgroundsEtl",
                                    inputs = listOf("campflare-campgrounds-export"),
                                ),
                            ),
                    ),
                ),
            campsiteData =
                listOf(
                    CampsiteDataEntry(
                        name = "Campflare Campsites",
                        etls =
                            listOf(
                                EtlEntry(
                                    slug = "campflare-campsites",
                                    adapter = "CampflareCampsitesEtl",
                                    inputs = listOf("campflare-campsites-export"),
                                ),
                            ),
                    ),
                ),
        )

    private fun dataSource(slug: String): DataSourceEntry =
        DataSourceEntry(
            slug = slug,
            name = slug,
            fetcher =
                Fetcher(
                    executor = "python3",
                    filename = "scripts/fetch_campflare_dump.py",
                    outputDirPrefix = "data/raw/$slug",
                ),
        )

    private fun tableCount(table: String): Int =
        ctx
            .fetchOne("SELECT COUNT(*) AS n FROM $table")!!
            .get("n", Number::class.java)
            .toInt()
}
