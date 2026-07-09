package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.metadata.registry.CampsiteDataEntry
import ca.floo.roadtrip.models.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.models.metadata.registry.EtlEntry
import ca.floo.roadtrip.models.metadata.registry.Fetcher
import ca.floo.roadtrip.models.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCampsite
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

    @Test
    fun `campflare etl leaves catalog match materialized view current`() {
        val federalCampgroundId =
            ctx.seedCampground(
                name = "Camp One Federal",
                source = "federal-campgrounds",
                sourceId = "recgov-232447",
            )
        ctx.seedCampsite(
            campgroundId = federalCampgroundId,
            vendor = "recgov",
            source = "federal-campsites",
            vendorId = "001",
            name = "Site 001 Federal",
        )
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
                    "connections":{"ridb_facility_id":"232447"}
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
                    "reservation_url":"https://www.recreation.gov/camping/campgrounds/232447/campsites/001"
                  }
                ]
                """.trimIndent(),
        )

        val orchestrator = EtlOrchestrator(ctx, rawDir, registry())

        orchestrator.runPoiData("Campflare Campgrounds")
        assertEquals(1, materializedMatchCount("campground"))

        orchestrator.runCampsiteData("Campflare Campsites")
        assertEquals(1, materializedMatchCount("campsite"))
        assertEquals(2, tableCount("catalog_match_rows"))
        assertEquals(
            listOf(
                "campground:federal-campgrounds->campflare-campgrounds:recgov-232447",
                "campsite:federal-campsites->campflare-campsites:001",
            ),
            ctx
                .fetch(
                    """
                    SELECT entity_type,
                           left_etl_source,
                           right_etl_source,
                           match_heuristic->>'external_id' AS matched_ref
                    FROM catalog_match_rows
                    ORDER BY entity_type
                    """.trimIndent(),
                ).map {
                    "${it.get("entity_type")}:${it.get("left_etl_source")}->${it.get("right_etl_source")}:${it.get("matched_ref")}"
                },
        )
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

    private fun materializedMatchCount(entityType: String): Int =
        ctx
            .fetchOne("SELECT COUNT(*) AS n FROM catalog_match_rows WHERE entity_type = ?", entityType)!!
            .get("n", Number::class.java)
            .toInt()
}
