package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.Address
import ca.floo.roadtrip.model.domain.CampgroundContact
import ca.floo.roadtrip.model.domain.CampgroundLink
import ca.floo.roadtrip.model.domain.CampgroundLocation
import ca.floo.roadtrip.model.domain.CampgroundManagement
import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.CampsiteAttribute
import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.CatalogPhoto
import ca.floo.roadtrip.model.domain.PlanetFitnessLocationUpsertCandidate
import ca.floo.roadtrip.model.domain.TeslaSuperchargerUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
                location = CampgroundLocation(37.739, -119.565, address = Address(state = "CA", country = "US")),
                amenities = json("""{"toilets":true,"water":true}"""),
                management = CampgroundManagement("National Park Service"),
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
                    location = CampgroundLocation(37.739, -119.565),
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
                    location = CampgroundLocation(37.739, -119.565),
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
                location = CampgroundLocation(37.739, -119.565),
                sourcePayload = json("""{"FacilityID":"232447"}"""),
            )
        val campflareRecord =
            CampgroundUpsertCandidate(
                dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-campground-447"),
                name = "Upper Pines",
                latitude = 37.739,
                longitude = -119.565,
                location = CampgroundLocation(37.739, -119.565),
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
                    location = CampgroundLocation(37.739, -119.565),
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
                        equipment = listOf("Tent"),
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
    fun `campsite typed columns round-trip through the repo`() {
        CampgroundRepo(ctx).upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    dataProviderRef = DataProviderRef.RecGov(id = "cg-typed-1"),
                    name = "Typed Parent",
                    latitude = 37.739,
                    longitude = -119.565,
                    location = CampgroundLocation(37.739, -119.565),
                ),
            ),
            source = "recgov-campgrounds",
        )

        val campsites = CampsiteRepo(ctx)
        campsites.upsertCampsiteBatch(
            listOf(
                CampsiteUpsertCandidate(
                    dataProviderRef = DataProviderRef.RecGov(id = "cs-typed-1"),
                    parentDataProviderRef = DataProviderRef.RecGov(id = "cg-typed-1"),
                    name = "Site 1",
                    equipment = listOf("Tent", "RV"),
                    photos = listOf(CatalogPhoto(url = "https://example.test/site1.jpg")),
                    attributes =
                        listOf(
                            CampsiteAttribute(name = "Shade", value = "Partial"),
                            CampsiteAttribute(name = "Pets allowed"),
                        ),
                    description = "Walk-in tent site by the water.",
                    minPeople = 2,
                    maxPeople = 6,
                ),
            ),
        )

        val campgroundId = assertNotNull(CampgroundRepo(ctx).findByPoi(poiIdForCampground("cg-typed-1"))).id
        val row = campsites.findByCampground(campgroundId).single()

        assertEquals(listOf("Tent", "RV"), row.equipment)
        assertEquals(listOf(CatalogPhoto("https://example.test/site1.jpg")), row.photos)
        assertEquals(
            listOf(CampsiteAttribute("Shade", "Partial"), CampsiteAttribute("Pets allowed")),
            row.attributes,
        )
        assertEquals("Walk-in tent site by the water.", row.description)
        assertEquals(2, row.minPeople)
    }

    /**
     * `equipment` stays nullable until the old jar is out of rotation, and that
     * jar binds SQL NULL for vendors that never set it. The read path treats an
     * absent column as an absent list rather than throwing.
     */
    @Test
    fun `a NULL equipment column reads as an empty list`() {
        seedCampsites("cg-null-equipment", "cs-null-equipment")
        ctx.execute("UPDATE campsites SET equipment = NULL WHERE data_provider_ref = ?", "cs-null-equipment")

        val row = checkNotNull(CampsiteRepo(ctx).findById(campsiteId("cs-null-equipment")))

        assertEquals(emptyList(), row.equipment)
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
                    location = CampgroundLocation(37.739, -119.565),
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
                    location = CampgroundLocation(37.739, -119.565),
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
                    location = CampgroundLocation(40.0 + i * 0.0001, -120.0 - i * 0.0001),
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
                        location = CampgroundLocation(40.0, -120.0),
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

    /**
     * V55 rewrites stored rows into the canonical keys the typed columns
     * encode. A row this repo just wrote is already canonical, so re-running
     * the migration over it must change nothing — that equality is what makes
     * the strict decoder on the read path safe.
     */
    @Test
    fun `the normalization migration is a no-op on rows this repo wrote`() {
        val repo = CampgroundRepo(ctx)
        repo.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    dataProviderRef = DataProviderRef.Campflare(id = "canonical-1"),
                    name = "Canonical",
                    latitude = 1.0,
                    longitude = 2.0,
                    location = CampgroundLocation(1.0, 2.0, region = "CA", country = "US", elevation = 30.0, address = Address(city = "X")),
                    links = listOf(CampgroundLink("https://a.test/", title = "A"), CampgroundLink("https://b.test/")),
                    photos = listOf(CatalogPhoto("https://p.test/1.jpg")),
                    management = CampgroundManagement("NPS", website = "https://nps.test"),
                    contact = CampgroundContact(phone = "1", email = "e@x.test"),
                ),
            ),
            source = "campflare-campgrounds",
        )

        val before = campgroundColumnsAsText()
        assertEquals(
            listOf(
                """{"region": "CA", "address": {"city": "X"}, "country": "US", "latitude": 1.0, "elevation": 30.0, "longitude": 2.0}""" +
                    """|[{"url": "https://a.test/", "title": "A"}, {"url": "https://b.test/"}]""" +
                    """|[{"url": "https://p.test/1.jpg"}]""" +
                    """|{"agency": "NPS", "website": "https://nps.test"}""" +
                    """|{"email": "e@x.test", "phone": "1"}""",
            ),
            before,
        )

        migrationStatements("V55__normalize_campground_jsonb.sql").forEach(ctx::execute)

        assertEquals(before, campgroundColumnsAsText())
    }

    /**
     * The migration's actual job: a row written before the columns were typed
     * carries Campflare's upstream key names, and the read path decodes
     * strictly now. Blank and untrimmed values become absent, which is what
     * the old read path did on the way out.
     */
    @Test
    fun `the normalization migration rewrites legacy Campflare keys`() {
        val id =
            ctx
                .fetchOne(
                    """
                    INSERT INTO campgrounds (name, data_provider, data_provider_ref, location, links, photos, management, contact)
                    VALUES ('Legacy', 'campflare', 'legacy-1', ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
                    RETURNING id
                    """.trimIndent(),
                    """{"latitude":1,"longitude":2,"region":"  CA  ","directions":"Turn left at the pines.",""" +
                        """"address":{"street1":"1 Rd","state_code":"CA","zipcode":"95389","country_code":"US","full":"1 Rd, CA 95389"}}""",
                    """[{"href":"https://a.test/","label":"A"},{"caption":"no url"}]""",
                    """[{"original_url":"https://p.test/1.jpg","large_url":"https://p.test/big.jpg"},{"caption":"no url"}]""",
                    """{"agency_name":"NPS","agency_id":7,"agency_website":"https://nps.test"}""",
                    """{"primary_phone":"555-0100","primary_email":"","fax":"x"}""",
                )!!
                .get("id", Long::class.java)

        migrationStatements("V55__normalize_campground_jsonb.sql").forEach(ctx::execute)

        val campground = checkNotNull(CampgroundRepo(ctx).findById(id))
        assertEquals(
            CampgroundLocation(
                1.0,
                2.0,
                region = "CA",
                directions = "Turn left at the pines.",
                address = Address("1 Rd", state = "CA", postcode = "95389", country = "US", full = "1 Rd, CA 95389"),
            ),
            campground.location,
        )
        assertEquals(listOf(CampgroundLink("https://a.test/", title = "A")), campground.links)
        assertEquals(listOf(CatalogPhoto("https://p.test/big.jpg")), campground.photos)
        assertEquals(CampgroundManagement("NPS", website = "https://nps.test"), campground.management)
        assertEquals(CampgroundContact(phone = "555-0100"), campground.contact)
    }

    /**
     * The campsite counterpart of the V55 no-op: a row the typed repo just
     * wrote is already canonical, so re-running V56 over it must change
     * nothing.
     */
    @Test
    fun `the campsite migration is a no-op on rows this repo wrote`() {
        CampgroundRepo(ctx).upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    dataProviderRef = DataProviderRef.RecGov(id = "cg-canonical-1"),
                    name = "Canonical Parent",
                    latitude = 1.0,
                    longitude = 2.0,
                    location = CampgroundLocation(1.0, 2.0),
                ),
            ),
            source = "recgov-campgrounds",
        )
        CampsiteRepo(ctx).upsertCampsiteBatch(
            listOf(
                CampsiteUpsertCandidate(
                    dataProviderRef = DataProviderRef.RecGov(id = "cs-canonical-1"),
                    parentDataProviderRef = DataProviderRef.RecGov(id = "cg-canonical-1"),
                    name = "Canonical Site",
                    equipment = listOf("Tent", "RV"),
                    photos = listOf(CatalogPhoto("https://p.test/site.jpg")),
                    attributes = listOf(CampsiteAttribute("Shade", "Partial"), CampsiteAttribute("Pets allowed")),
                    description = "Walk-in tent site by the water.",
                    minPeople = 2,
                ),
            ),
        )

        val before = campsiteColumnsAsText()
        assertEquals(
            listOf(
                """["Tent", "RV"]""" +
                    """|[{"url": "https://p.test/site.jpg"}]""" +
                    """|[{"name": "Shade", "value": "Partial"}, {"name": "Pets allowed"}]""" +
                    """|Walk-in tent site by the water.""" +
                    """|2""",
            ),
            before,
        )

        migrationStatements("V56__typed_campsite_columns.sql").forEach(ctx::execute)

        assertEquals(before, campsiteColumnsAsText())
    }

    /**
     * The campsite migration's whole job: rows written before the columns were
     * typed carry vendor-shaped equipment and photos, and the read path decodes
     * strictly now. It does not backfill — the `make data-import` the deploy
     * runs next rewrites every row, so the three new columns stay empty here
     * even though the payload holds what fills them.
     */
    @Test
    fun `the campsite migration rewrites legacy vendor shapes and backfills nothing`() {
        seedCampsites("cg-legacy-shape", "cs-legacy-recgov")
        ctx.execute(
            """
            UPDATE campsites
            SET equipment = ?::jsonb, photos = ?::jsonb, source_payload = ?::jsonb
            WHERE data_provider_ref = 'cs-legacy-recgov'
            """.trimIndent(),
            """[{"name":"Tent"},"RV"]""",
            """[{"large_url":"https://x/a.jpg"},{"url":"https://x/b.jpg"},{"url":null},"junk"]""",
            """{"attributes":[{"attribute_name":"Fire Pit","attribute_value":"Yes"}],"min_num_people":2,""" +
                """"description":"<p>By the <b>lake</b></p>"}""",
        )

        migrationStatements("V56__typed_campsite_columns.sql").forEach(ctx::execute)

        val row = checkNotNull(CampsiteRepo(ctx).findById(campsiteId("cs-legacy-recgov")))
        assertEquals(listOf("Tent", "RV"), row.equipment)
        assertEquals(listOf(CatalogPhoto("https://x/a.jpg"), CatalogPhoto("https://x/b.jpg")), row.photos)
        assertEquals(emptyList(), row.attributes)
        assertNull(row.description)
        assertNull(row.minPeople)
    }

    /** A parent campground plus one bare campsite per ref, for migration replays to rewrite. */
    private fun seedCampsites(
        campgroundRef: String,
        vararg campsiteRefs: String,
    ) {
        CampgroundRepo(ctx).upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    dataProviderRef = DataProviderRef.RecGov(id = campgroundRef),
                    name = "Legacy Parent",
                    latitude = 1.0,
                    longitude = 2.0,
                    location = CampgroundLocation(1.0, 2.0),
                ),
            ),
            source = "recgov-campgrounds",
        )
        CampsiteRepo(ctx).upsertCampsiteBatch(
            campsiteRefs.map { ref ->
                CampsiteUpsertCandidate(
                    dataProviderRef = DataProviderRef.RecGov(id = ref),
                    parentDataProviderRef = DataProviderRef.RecGov(id = campgroundRef),
                    name = ref,
                )
            },
        )
    }

    private fun campsiteId(dataProviderRef: String): Long =
        ctx
            .fetchOne("SELECT id FROM campsites WHERE data_provider_ref = ?", dataProviderRef)!!
            .get("id", Long::class.java)

    private fun campsiteColumnsAsText(): List<String> =
        ctx
            .fetch(
                """
                SELECT equipment::text AS equipment, photos::text AS photos, attributes::text AS attributes,
                       description, min_people
                FROM campsites ORDER BY id
                """.trimIndent(),
            ).map { row -> row.intoArray().joinToString("|") { it.toString() } }

    private fun campgroundColumnsAsText(): List<String> =
        ctx
            .fetch(
                """
                SELECT location::text AS location, links::text AS links, photos::text AS photos,
                       management::text AS management, contact::text AS contact
                FROM campgrounds ORDER BY id
                """.trimIndent(),
            ).map { row -> row.intoArray().joinToString("|") { it.toString() } }

    /** jOOQ executes one statement per call, so the script is split on its statement terminators. */
    private fun migrationStatements(name: String): List<String> =
        checkNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$name")) { "missing migration $name" }
            .bufferedReader()
            .use { it.readText() }
            .lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

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
