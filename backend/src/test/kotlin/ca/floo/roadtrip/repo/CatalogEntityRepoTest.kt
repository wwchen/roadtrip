package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.PlanetFitnessLocationUpsertCandidate
import ca.floo.roadtrip.model.domain.TeslaSuperchargerUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CatalogEntityRepoTest : SharedDbTest() {
    @BeforeEach
    fun resetCatalog() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `upserts campgrounds through vendor refs and creates lean POI wrapper`() {
        val repo = CampgroundRepo(ctx)
        val record =
            CampgroundUpsertCandidate(
                dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-campground-447"),
                name = "Upper Pines",
                status = "open",
                kind = "established",
                latitude = 37.739,
                longitude = -119.565,
                location = json("""{"latitude":37.739,"longitude":-119.565,"address":{"state_code":"CA","country_code":"US"}}"""),
                amenities = json("""{"toilets":true,"water":true}"""),
                management = json("""{"agency_name":"National Park Service"}"""),
                connections = json("""{"ridb_facility_id":"232447"}"""),
                metadata = json("""{"last_updated":"2026-07-01T00:00:00Z"}"""),
                sourceUrl = "https://campflare.com/campground/upper-pines-campground-447",
                sourcePayload = json("""{"id":"upper-pines-campground-447","name":"Upper Pines"}"""),
            )

        val first = repo.upsertCampgrounds(listOf(record), source = "campflare-campgrounds")
        val second = repo.upsertCampgrounds(listOf(record.copy(name = "Upper Pines Campground")), source = "campflare-campgrounds")

        assertEquals(1, first.seenCount)
        assertEquals(1, first.upsertedCount)
        assertEquals(1, second.upsertedCount)
        assertEquals(1, tableCount("campgrounds"))
        assertEquals(1, tableCount("pois"))
        assertEquals(1, tableCount("poi_campgrounds"))

        val row =
            ctx
                .fetchOne(
                    """
                    SELECT cg.id, cg.name, cg.data_provider, cg.data_provider_ref, cg.amenities::text AS amenities,
                           ST_X(p.geom::geometry) AS lon, ST_Y(p.geom::geometry) AS lat
                    FROM campgrounds cg
                    JOIN poi_campgrounds pc ON pc.campground_id = cg.id
                    JOIN pois p ON p.id = pc.poi_id
                    """.trimIndent(),
                )

        assertNotNull(row)
        assertEquals("Upper Pines Campground", row.get("name", String::class.java))
        assertEquals("campflare", row.get("data_provider", String::class.java))
        assertEquals("upper-pines-campground-447", row.get("data_provider_ref", String::class.java))
        assertEquals(-119.565, row.get("lon", Double::class.java))
        assertEquals(37.739, row.get("lat", Double::class.java))

        val campgroundId = row.get("id", Long::class.java)
        val poiId =
            ctx
                .fetchOne("SELECT poi_id FROM poi_campgrounds WHERE campground_id = ?", campgroundId)!!
                .get("poi_id", Long::class.java)
        val campground = repo.findById(campgroundId)
        assertNotNull(campground)
        assertEquals("Upper Pines Campground", campground.name)
        assertEquals("campflare", campground.dataProviderRef.provider.id)
        assertEquals("upper-pines-campground-447", campground.dataProviderRef.serialize())
        assertEquals(json("""{"id":"upper-pines-campground-447","name":"Upper Pines"}"""), campground.sourcePayload)
        assertEquals(row.get("id", Long::class.java), campground.id)
        assertEquals(campgroundId, repo.findByPoi(poiId)?.id)
        assertEquals(
            listOf(campgroundId),
            repo
                .search(
                    CampgroundRepo.SearchFilters(
                        vendors = listOf("campflare"),
                        names = listOf("Upper Pines"),
                    ),
                    limit = 10,
                    offset = 0,
                ).map { it.id },
        )
    }

    @Test
    fun `different vendors for the same real-world campground land in distinct per-vendor rows`() {
        val repo = CampgroundRepo(ctx)
        repo.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    dataProviderRef = DataProviderRef.RecGov(id = "232447"),
                    name = "Upper Pines",
                    latitude = 37.739,
                    longitude = -119.565,
                    sourcePayload = json("""{"FacilityID":"232447"}"""),
                ),
            ),
            source = "recgov-campgrounds",
        )

        repo.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-campground-447"),
                    name = "Upper Pines Campflare",
                    latitude = 37.739,
                    longitude = -119.565,
                    sourcePayload = json("""{"id":"upper-pines-campground-447"}"""),
                ),
            ),
            source = "campflare-campgrounds",
        )

        assertEquals(2, tableCount("campgrounds"))

        val rows =
            ctx
                .fetch(
                    """
                    SELECT cg.data_provider, cg.data_provider_ref
                    FROM campgrounds cg
                    ORDER BY cg.data_provider
                    """.trimIndent(),
                ).map {
                    "${it.get("data_provider")}:${it.get("data_provider_ref")}"
                }

        assertEquals(
            listOf(
                "campflare:upper-pines-campground-447",
                "recgov:232447",
            ),
            rows,
        )

        val recgovName =
            ctx
                .fetchOne(
                    "SELECT name FROM campgrounds WHERE data_provider = 'recgov'",
                )!!
                .get("name", String::class.java)
        assertEquals("Upper Pines", recgovName)
    }

    @Test
    fun `updates respect per-provider identity`() {
        val repo = CampgroundRepo(ctx)
        val recgovRecord =
            CampgroundUpsertCandidate(
                dataProviderRef = DataProviderRef.RecGov(id = "232447"),
                name = "Upper Pines",
                latitude = 37.739,
                longitude = -119.565,
                sourcePayload = json("""{"FacilityID":"232447"}"""),
            )
        val campflareRecord =
            CampgroundUpsertCandidate(
                dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-campground-447"),
                name = "Upper Pines",
                latitude = 37.739,
                longitude = -119.565,
                sourcePayload = json("""{"id":"upper-pines-campground-447"}"""),
            )

        repo.upsertCampgrounds(listOf(recgovRecord), source = "recgov-campgrounds")
        repo.upsertCampgrounds(listOf(campflareRecord), source = "campflare-campgrounds")

        assertEquals(2, tableCount("campgrounds"))

        val rows =
            ctx
                .fetch(
                    """
                    SELECT cg.data_provider, cg.data_provider_ref
                    FROM campgrounds cg
                    ORDER BY cg.data_provider
                    """.trimIndent(),
                ).map {
                    "${it.get("data_provider")}:${it.get("data_provider_ref")}"
                }

        assertEquals(
            listOf(
                "campflare:upper-pines-campground-447",
                "recgov:232447",
            ),
            rows,
        )

        repo.upsertCampgrounds(
            listOf(recgovRecord.copy(name = "Upper Pines (recgov update)")),
            source = "recgov-campgrounds",
        )
        assertEquals(2, tableCount("campgrounds"))

        val names =
            ctx
                .fetch(
                    """
                    SELECT cg.data_provider, cg.name
                    FROM campgrounds cg
                    ORDER BY cg.data_provider
                    """.trimIndent(),
                ).map { "${it.get("data_provider")}|${it.get("name")}" }

        assertEquals(
            listOf(
                "campflare|Upper Pines",
                "recgov|Upper Pines (recgov update)",
            ),
            names,
        )
    }

    @Test
    fun `upserts campsites by resolving parent campground vendor ref`() {
        val campgrounds = CampgroundRepo(ctx)
        val campsites = CampsiteRepo(ctx)
        campgrounds.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-campground-447"),
                    name = "Upper Pines",
                    latitude = 37.739,
                    longitude = -119.565,
                    location = json("""{"latitude":37.739,"longitude":-119.565}"""),
                    sourcePayload = json("""{"id":"upper-pines-campground-447"}"""),
                ),
            ),
            source = "campflare-campgrounds",
        )

        val result =
            campsites.upsertCampsites(
                listOf(
                    CampsiteUpsertCandidate(
                        dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-site-001"),
                        parentDataProviderRef = DataProviderRef.Campflare(id = "upper-pines-campground-447"),
                        name = "Site 001",
                        kind = "tent-only",
                        loopName = "A",
                        latitude = 37.738,
                        longitude = -119.566,
                        reservationUrl = "https://example.test/site/001",
                        equipment = json("""[{"name":"Tent"}]"""),
                        maxPeople = 6,
                        sourcePayload = json("""{"id":"upper-pines-site-001","campground_id":"upper-pines-campground-447"}"""),
                    ),
                ),
                source = "campflare-campsites",
            )

        assertEquals(1, result.seenCount)
        assertEquals(1, result.upsertedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(1, tableCount("campsites"))

        val row =
            ctx
                .fetchOne(
                    """
                    SELECT c.id, c.name, c.kind, c.loop_name, c.data_provider, c.data_provider_ref,
                           cg.data_provider_ref AS parent_ref
                    FROM campsites c
                    JOIN campgrounds cg ON cg.id = c.campground_id
                    """.trimIndent(),
                )

        assertNotNull(row)
        assertEquals("Site 001", row.get("name", String::class.java))
        assertEquals("tent-only", row.get("kind", String::class.java))
        assertEquals("A", row.get("loop_name", String::class.java))
        assertEquals("campflare", row.get("data_provider", String::class.java))
        assertEquals("upper-pines-site-001", row.get("data_provider_ref", String::class.java))
        assertEquals("upper-pines-campground-447", row.get("parent_ref", String::class.java))

        val campsiteId = row.get("id", Long::class.java)
        val persisted = campsites.findById(campsiteId)
        assertNotNull(persisted)
        assertEquals(campsiteId, persisted.id)
        assertEquals("Site 001", persisted.name)
        assertEquals("campflare", persisted.dataProviderRef.provider.id)
        assertEquals("upper-pines-site-001", persisted.dataProviderRef.serialize())
        assertEquals(json("""{"id":"upper-pines-site-001","campground_id":"upper-pines-campground-447"}"""), persisted.sourcePayload)
        assertEquals(campsiteId, campsites.findByPoi(poiIdForCampground("upper-pines-campground-447")).single().id)

        val availabilityTarget = campsites.findById(campsiteId)
        assertNotNull(availabilityTarget)
        assertEquals(campsiteId, availabilityTarget.id)
        assertEquals("campflare", availabilityTarget.dataProviderRef.provider.id)
        assertEquals("upper-pines-site-001", availabilityTarget.dataProviderRef.serialize())
        assertEquals("Site 001", availabilityTarget.name)
    }

    @Test
    fun `per-vendor campsite identity maintains separate rows for each provider`() {
        val campgrounds = CampgroundRepo(ctx)
        val campsites = CampsiteRepo(ctx)
        campgrounds.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-campground-447"),
                    name = "Upper Pines",
                    latitude = 37.739,
                    longitude = -119.565,
                    sourcePayload = json("""{"id":"upper-pines-campground-447"}"""),
                ),
            ),
            source = "campflare-campgrounds",
        )
        campgrounds.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    dataProviderRef = DataProviderRef.RecGov(id = "232447"),
                    name = "Upper Pines",
                    latitude = 37.739,
                    longitude = -119.565,
                    sourcePayload = json("""{"FacilityID":"232447"}"""),
                ),
            ),
            source = "recgov-campgrounds",
        )
        campsites.upsertCampsites(
            listOf(
                CampsiteUpsertCandidate(
                    dataProviderRef = DataProviderRef.RecGov(id = "100"),
                    parentDataProviderRef = DataProviderRef.RecGov(id = "232447"),
                    name = "Site 100",
                    kind = "standard",
                    sourcePayload = json("""{"site":"100"}"""),
                ),
            ),
            source = "recgov-campsites-catalog",
        )

        campsites.upsertCampsites(
            listOf(
                CampsiteUpsertCandidate(
                    dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-site-100"),
                    parentDataProviderRef = DataProviderRef.Campflare(id = "upper-pines-campground-447"),
                    name = "Campflare Site 100",
                    kind = "standard",
                    sourcePayload = json("""{"id":"upper-pines-site-100"}"""),
                ),
            ),
            source = "campflare-campsites",
        )

        assertEquals(2, tableCount("campsites"))

        val links =
            ctx
                .fetch(
                    """
                    SELECT cs.data_provider, cs.data_provider_ref
                    FROM campsites cs
                    ORDER BY cs.data_provider, cs.data_provider_ref
                    """.trimIndent(),
                ).map {
                    "${it.get("data_provider")}:${it.get("data_provider_ref")}"
                }

        assertEquals(
            listOf(
                "campflare:upper-pines-site-100",
                "recgov:100",
            ),
            links,
        )
    }

    @Test
    fun `upserts Tesla superchargers and Planet Fitness locations through typed POI joins`() {
        val teslaRepo = TeslaSuperchargerRepo(ctx)
        val planetFitnessRepo = PlanetFitnessLocationRepo(ctx)

        val tesla =
            teslaRepo.upsertTeslaSuperchargers(
                listOf(
                    TeslaSuperchargerUpsertCandidate(
                        locationSlug = "vancouver-bc-1",
                        commonSiteName = "Vancouver, BC",
                        latitude = 49.2827,
                        longitude = -123.1207,
                        siteStatus = "open",
                        accessType = "public",
                        openToNonTeslas = true,
                        stallCount = 12,
                        maxPowerKw = 250,
                        address = json("""{"city":"Vancouver","country":"CA"}"""),
                        region = "BC",
                        country = "CA",
                        pricebooks = json("""[{"feeType":"CHARGING"}]"""),
                        infoUrl = "https://www.tesla.com/findus?location=vancouver-bc-1",
                        indexPayload = json("""{"location_url_slug":"vancouver-bc-1"}"""),
                        detailPayload = json("""{"name":"Vancouver, BC"}"""),
                    ),
                ),
                source = "tesla-superchargers",
            )
        val planetFitness =
            planetFitnessRepo.upsertPlanetFitnessLocations(
                listOf(
                    PlanetFitnessLocationUpsertCandidate(
                        locationId = "node-123",
                        name = "Planet Fitness Vancouver",
                        latitude = 49.25,
                        longitude = -123.1,
                        address = json("""{"city":"Vancouver","country":"US"}"""),
                        region = "WA",
                        country = "US",
                        phone = "555-0100",
                        infoUrl = "https://example.test/pf",
                        payload = json("""{"id":123}"""),
                    ),
                ),
                source = "planet-fitness",
            )

        assertEquals(1, tesla.upsertedCount)
        assertEquals(1, planetFitness.upsertedCount)
        assertEquals(1, tableCount("tesla_superchargers"))
        assertEquals(1, tableCount("planet_fitness_locations"))
        assertEquals(2, tableCount("pois"))
        assertEquals(1, tableCount("poi_tesla_superchargers"))
        assertEquals(1, tableCount("poi_planet_fitness_locations"))

        val teslaRow = teslaRepo.findByLocationSlug("vancouver-bc-1")
        assertNotNull(teslaRow)
        assertEquals("Vancouver, BC", teslaRow.commonSiteName)
        assertEquals("CA", teslaRow.country)
        assertEquals(json("""{"location_url_slug":"vancouver-bc-1"}"""), teslaRow.indexPayload)
        assertEquals(teslaRow.id, teslaRepo.findById(teslaRow.id)?.id)
        assertEquals(teslaRow.id, teslaRepo.findByLocationSlug("vancouver-bc-1")?.id)
        val teslaPoiId =
            ctx
                .fetchOne("SELECT poi_id FROM poi_tesla_superchargers WHERE tesla_supercharger_id = ?", teslaRow.id)!!
                .get("poi_id", Long::class.java)
        assertEquals(teslaRow.id, teslaRepo.findByPoi(teslaPoiId)?.id)
        assertEquals(teslaRow.id, teslaRepo.findPoiDetailByPoi(teslaPoiId)?.supercharger?.id)

        val planetFitnessRow = planetFitnessRepo.findByLocationId("node-123")
        assertNotNull(planetFitnessRow)
        assertEquals("Planet Fitness Vancouver", planetFitnessRow.name)
        assertEquals("555-0100", planetFitnessRow.phone)
        assertEquals(json("""{"id":123}"""), planetFitnessRow.payload)
        assertEquals(planetFitnessRow.id, planetFitnessRepo.findById(planetFitnessRow.id)?.id)
        assertEquals(planetFitnessRow.id, planetFitnessRepo.findByLocationId("node-123")?.id)
        val planetFitnessPoiId =
            ctx
                .fetchOne(
                    "SELECT poi_id FROM poi_planet_fitness_locations WHERE planet_fitness_location_id = ?",
                    planetFitnessRow.id,
                )!!
                .get("poi_id", Long::class.java)
        assertEquals(planetFitnessRow.id, planetFitnessRepo.findByPoi(planetFitnessPoiId)?.id)
        assertEquals(planetFitnessRow.id, planetFitnessRepo.findPoiDetailByPoi(planetFitnessPoiId)?.location?.id)

        val poiTypes =
            ctx
                .fetch("SELECT poi_type FROM pois ORDER BY poi_type")
                .map { it.get("poi_type", String::class.java) }
        assertEquals(listOf("planet_fitness_location", "tesla_supercharger"), poiTypes)
    }

    @Test
    fun `bulk upsert handles a batch spanning multiple chunks in a single pass`() {
        val campgrounds = CampgroundRepo(ctx)
        val campsites = CampsiteRepo(ctx)
        val batchSize = MAX_CATALOG_UPSERT_BATCH_SIZE

        val campgroundRecords =
            (0 until batchSize).map { i ->
                CampgroundUpsertCandidate(
                    dataProviderRef = DataProviderRef.Campflare(id = "bulk-cg-$i"),
                    name = "Bulk Campground $i",
                    latitude = 40.0 + i * 0.0001,
                    longitude = -120.0 - i * 0.0001,
                    sourcePayload = json("""{"id":"bulk-cg-$i"}"""),
                )
            }
        val campgroundResult =
            campgrounds.upsertCampgrounds(campgroundRecords, source = "campflare-campgrounds")

        assertEquals(batchSize, campgroundResult.seenCount)
        assertEquals(batchSize, campgroundResult.upsertedCount)
        assertEquals(batchSize, tableCount("campgrounds"))
        assertEquals(batchSize, tableCount("pois"))
        assertEquals(batchSize, tableCount("poi_campgrounds"))

        val campsiteRecords =
            (0 until batchSize).map { i ->
                CampsiteUpsertCandidate(
                    dataProviderRef = DataProviderRef.Campflare(id = "bulk-cs-$i"),
                    parentDataProviderRef = DataProviderRef.Campflare(id = "bulk-cg-$i"),
                    name = "Bulk Campsite $i",
                    kind = "standard",
                    sourcePayload = json("""{"id":"bulk-cs-$i"}"""),
                )
            }
        val campsiteResult =
            campsites.upsertCampsites(campsiteRecords, source = "campflare-campsites")

        assertEquals(batchSize, campsiteResult.seenCount)
        assertEquals(batchSize, campsiteResult.upsertedCount)
        assertEquals(0, campsiteResult.skippedCount)
        assertEquals(batchSize, tableCount("campsites"))

        val rerun =
            campsites.upsertCampsites(
                campsiteRecords.map { it.copy(name = "${it.name} (v2)") },
                source = "campflare-campsites",
            )
        assertEquals(batchSize, rerun.upsertedCount)
        assertEquals(batchSize, tableCount("campsites"))
        val renamed =
            ctx
                .fetchOne("SELECT COUNT(*) AS n FROM campsites WHERE name LIKE '% (v2)'")!!
                .get("n", Number::class.java)
                .toInt()
        assertEquals(batchSize, renamed)
    }

    @Test
    fun `entity repos reject oversized upsert batches`() {
        val batchSize = MAX_CATALOG_UPSERT_BATCH_SIZE + 1

        assertFailsWith<IllegalArgumentException> {
            CampgroundRepo(ctx).upsertCampgroundBatch(
                (0 until batchSize).map { i ->
                    CampgroundUpsertCandidate(
                        dataProviderRef = DataProviderRef.Campflare(id = "too-many-cg-$i"),
                        name = "Too Many Campground $i",
                        latitude = 40.0,
                        longitude = -120.0,
                    )
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CampsiteRepo(ctx).upsertCampsiteBatch(
                (0 until batchSize).map { i ->
                    CampsiteUpsertCandidate(
                        dataProviderRef = DataProviderRef.Campflare(id = "too-many-cs-$i"),
                        parentDataProviderRef = null,
                        name = "Too Many Campsite $i",
                    )
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TeslaSuperchargerRepo(ctx).upsertTeslaSuperchargerBatch(
                (0 until batchSize).map { i ->
                    TeslaSuperchargerUpsertCandidate(
                        locationSlug = "too-many-tesla-$i",
                        commonSiteName = "Too Many Tesla $i",
                        latitude = 40.0,
                        longitude = -120.0,
                    )
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PlanetFitnessLocationRepo(ctx).upsertPlanetFitnessLocationBatch(
                (0 until batchSize).map { i ->
                    PlanetFitnessLocationUpsertCandidate(
                        locationId = "too-many-pf-$i",
                        name = "Too Many Planet Fitness $i",
                        latitude = 40.0,
                        longitude = -120.0,
                    )
                },
            )
        }
    }

    private fun tableCount(table: String): Int =
        ctx
            .fetchOne("SELECT COUNT(*) AS n FROM $table")!!
            .get("n", Number::class.java)
            .toInt()

    private fun poiIdForCampground(dataProviderRef: String): Long =
        ctx
            .fetchOne(
                """
                SELECT pc.poi_id
                FROM poi_campgrounds pc
                JOIN campgrounds cg ON cg.id = pc.campground_id
                WHERE cg.data_provider_ref = ?
                """.trimIndent(),
                dataProviderRef,
            )!!
            .get("poi_id", Long::class.java)

    private fun json(value: String) = Json.parseToJsonElement(value)
}
