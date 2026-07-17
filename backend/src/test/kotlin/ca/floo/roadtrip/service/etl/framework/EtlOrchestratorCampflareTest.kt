package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.registry.CampsiteDataEntry
import ca.floo.roadtrip.model.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.model.metadata.registry.EtlEntry
import ca.floo.roadtrip.model.metadata.registry.Fetcher
import ca.floo.roadtrip.model.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class EtlOrchestratorCampflareTest : SharedDbTest() {
    @TempDir
    lateinit var rawDir: File

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

        val orchestrator = EtlOrchestrator(ctx, rawDir, registry())

        val campgrounds = orchestrator.runPoiData("Campflare Campgrounds")
        val campsites = orchestrator.runCampsiteData("Campflare Campsites")

        assertEquals(1, campgrounds.upsertResult.upsertedCount)
        assertEquals(1, campsites.upserted)
        assertEquals(1, tableCount("campgrounds"))
        assertEquals(1, tableCount("campsites"))
        assertEquals(2, tableCount("vendor_refs"))
        assertEquals(1, tableCount("pois"))
        assertEquals(1, tableCount("poi_campgrounds"))
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
