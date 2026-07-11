package ca.floo.roadtrip.repo

import ca.floo.roadtrip.service.etl.framework.CampgroundUpsertCandidate
import ca.floo.roadtrip.service.etl.framework.CampsiteUpsertCandidate
import ca.floo.roadtrip.service.etl.framework.CatalogVendorRefUpsertCandidate
import ca.floo.roadtrip.service.etl.framework.PlanetFitnessLocationUpsertCandidate
import ca.floo.roadtrip.service.etl.framework.TeslaSuperchargerUpsertCandidate
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
                vendor = "campflare",
                vendorRefId = "upper-pines-campground-447",
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
                sourceUrl = "https://api.campflare.com/v2/campground/upper-pines-campground-447",
                sourcePayload = json("""{"id":"upper-pines-campground-447","name":"Upper Pines"}"""),
                vendorRefPayload = json("""{"connections":{"ridb_facility_id":"232447"}}"""),
            )

        val first = repo.upsertCampgrounds(listOf(record), source = "campflare-campgrounds")
        val second = repo.upsertCampgrounds(listOf(record.copy(name = "Upper Pines Campground")), source = "campflare-campgrounds")

        assertEquals(1, first.seenCount)
        assertEquals(1, first.upsertedCount)
        assertEquals(1, second.upsertedCount)
        assertEquals(1, tableCount("campgrounds"))
        assertEquals(1, tableCount("vendor_refs"))
        assertEquals(1, tableCount("campground_vendor_refs"))
        assertEquals(1, tableCount("pois"))
        assertEquals(1, tableCount("poi_campgrounds"))

        val row =
            ctx
                .fetchOne(
                    """
                    SELECT cg.id, cg.name, cg.amenities::text AS amenities,
                           vr.external_id, vr.source_url, vr.payload::text AS ref_payload,
                           ST_X(p.geom::geometry) AS lon, ST_Y(p.geom::geometry) AS lat
                    FROM campgrounds cg
                    JOIN campground_vendor_refs cvr ON cvr.campground_id = cg.id
                    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                    JOIN poi_campgrounds pc ON pc.campground_id = cg.id
                    JOIN pois p ON p.id = pc.poi_id
                    """.trimIndent(),
                )

        assertNotNull(row)
        assertEquals("Upper Pines Campground", row.get("name", String::class.java))
        assertEquals("upper-pines-campground-447", row.get("external_id", String::class.java))
        assertEquals("https://api.campflare.com/v2/campground/upper-pines-campground-447", row.get("source_url", String::class.java))
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
        assertEquals("campflare", campground.dataSource)
        assertEquals(json("""{"id":"upper-pines-campground-447","name":"Upper Pines"}"""), campground.sourcePayload)
        assertEquals(row.get("id", Long::class.java), campground.id)
        assertEquals(
            ctx
                .fetchOne("SELECT primary_vendor_ref_id FROM campgrounds WHERE id = ?", campgroundId)!!
                .get("primary_vendor_ref_id", Long::class.java),
            campground.primaryVendorRefId,
        )
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
    fun `additional vendor refs on a Campflare import link to the Campflare row, not merge into recgov's`() {
        // Per-vendor identity: Campflare's additionalVendorRefs (pointing at
        // recgov's id) must NOT pull Campflare's data onto recgov's row. Both
        // rows exist; the shared recgov vendor_ref lives on both link tables
        // and becomes matcher input.
        val repo = CampgroundRepo(ctx)
        repo.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    vendor = "federal-campgrounds",
                    vendorRefId = "recgov-232447",
                    name = "Upper Pines",
                    latitude = 37.739,
                    longitude = -119.565,
                    sourcePayload = json("""{"FacilityID":"232447"}"""),
                    vendorRefPayload = json("""{"recgov_id":"232447"}"""),
                ),
            ),
            source = "federal-campgrounds",
        )

        repo.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    vendor = "campflare",
                    vendorRefId = "upper-pines-campground-447",
                    name = "Upper Pines Campflare",
                    latitude = 37.739,
                    longitude = -119.565,
                    sourcePayload = json("""{"id":"upper-pines-campground-447"}"""),
                    vendorRefPayload = json("""{"campflare_id":"upper-pines-campground-447"}"""),
                    additionalVendorRefs =
                        listOf(
                            CatalogVendorRefUpsertCandidate(
                                vendor = "federal-campgrounds",
                                vendorRefId = "recgov-232447",
                                payload = json("""{"recgov_id":"232447"}"""),
                            ),
                        ),
                ),
            ),
            source = "campflare-campgrounds",
        )

        // Two campground rows (one per vendor); two vendor_refs (the recgov
        // ref is reused, not duplicated); three link rows (recgov→recgov_vr,
        // campflare→campflare_vr, campflare→recgov_vr as matcher input).
        assertEquals(2, tableCount("campgrounds"))
        assertEquals(2, tableCount("vendor_refs"))
        assertEquals(3, tableCount("campground_vendor_refs"))

        val links =
            ctx
                .fetch(
                    """
                    SELECT cg.data_source, vr.vendor AS ref_vendor, vr.external_id
                    FROM campground_vendor_refs cvr
                    JOIN campgrounds cg ON cg.id = cvr.campground_id
                    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                    ORDER BY cg.data_source, vr.vendor
                    """.trimIndent(),
                ).map {
                    "${it.get("data_source")}|${it.get("ref_vendor")}:${it.get("external_id")}"
                }

        assertEquals(
            listOf(
                "campflare|campflare:upper-pines-campground-447",
                "campflare|federal-campgrounds:recgov-232447",
                "federal-campgrounds|federal-campgrounds:recgov-232447",
            ),
            links,
        )

        // Recgov's row is untouched by Campflare's import.
        val recgovName =
            ctx
                .fetchOne(
                    "SELECT name FROM campgrounds WHERE data_source = 'federal-campgrounds'",
                )!!
                .get("name", String::class.java)
        assertEquals("Upper Pines", recgovName)
    }

    @Test
    fun `different vendors for the same real-world campground land in distinct per-vendor rows`() {
        val repo = CampgroundRepo(ctx)
        val recgovRecord =
            CampgroundUpsertCandidate(
                vendor = "recgov",
                vendorRefId = "232447",
                name = "Upper Pines",
                latitude = 37.739,
                longitude = -119.565,
                sourcePayload = json("""{"FacilityID":"232447"}"""),
                vendorRefPayload = json("""{"recgov_id":"232447"}"""),
            )
        val campflareRecord =
            CampgroundUpsertCandidate(
                vendor = "campflare",
                vendorRefId = "upper-pines-campground-447",
                name = "Upper Pines",
                latitude = 37.739,
                longitude = -119.565,
                sourcePayload = json("""{"id":"upper-pines-campground-447"}"""),
                vendorRefPayload = json("""{"campflare_id":"upper-pines-campground-447"}"""),
            )

        repo.upsertCampgrounds(listOf(recgovRecord), source = "recgov-campgrounds")
        repo.upsertCampgrounds(listOf(campflareRecord), source = "campflare-campgrounds")

        // Two campground rows, each tagged with its own data_source.
        assertEquals(2, tableCount("campgrounds"))
        assertEquals(2, tableCount("vendor_refs"))
        assertEquals(2, tableCount("campground_vendor_refs"))

        val rows =
            ctx
                .fetch(
                    """
                    SELECT cg.data_source, vr.vendor AS ref_vendor, vr.external_id
                    FROM campgrounds cg
                    JOIN campground_vendor_refs cvr ON cvr.campground_id = cg.id
                    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                    ORDER BY cg.data_source
                    """.trimIndent(),
                ).map {
                    "${it.get("data_source")}|${it.get("ref_vendor")}:${it.get("external_id")}"
                }

        // Each canonical row has exactly one vendor_ref pointing at its own vendor.
        assertEquals(
            listOf(
                "campflare|campflare:upper-pines-campground-447",
                "recgov|recgov:232447",
            ),
            rows,
        )

        // Re-running either input updates only its own row.
        repo.upsertCampgrounds(
            listOf(recgovRecord.copy(name = "Upper Pines (recgov update)")),
            source = "recgov-campgrounds",
        )
        assertEquals(2, tableCount("campgrounds"))

        val names =
            ctx
                .fetch(
                    """
                    SELECT cg.data_source, cg.name
                    FROM campgrounds cg
                    ORDER BY cg.data_source
                    """.trimIndent(),
                ).map { "${it.get("data_source")}|${it.get("name")}" }

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
                    vendor = "campflare",
                    vendorRefId = "upper-pines-campground-447",
                    name = "Upper Pines",
                    latitude = 37.739,
                    longitude = -119.565,
                    location = json("""{"latitude":37.739,"longitude":-119.565}"""),
                    sourcePayload = json("""{"id":"upper-pines-campground-447"}"""),
                    vendorRefPayload = json("""{"id":"upper-pines-campground-447"}"""),
                ),
            ),
            source = "campflare-campgrounds",
        )

        val result =
            campsites.upsertCampsites(
                listOf(
                    CampsiteUpsertCandidate(
                        vendor = "campflare",
                        vendorRefId = "upper-pines-site-001",
                        parentVendor = "campflare",
                        parentVendorRefId = "upper-pines-campground-447",
                        name = "Site 001",
                        kind = "tent-only",
                        loopName = "A",
                        latitude = 37.738,
                        longitude = -119.566,
                        reservationUrl = "https://example.test/site/001",
                        equipment = json("""[{"name":"Tent"}]"""),
                        maxPeople = 6,
                        sourcePayload = json("""{"id":"upper-pines-site-001","campground_id":"upper-pines-campground-447"}"""),
                        vendorRefPayload = json("""{"campground_id":"upper-pines-campground-447"}"""),
                    ),
                ),
                source = "campflare-campsites",
            )

        assertEquals(1, result.seenCount)
        assertEquals(1, result.upsertedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(1, tableCount("campsites"))
        assertEquals(2, tableCount("vendor_refs"))
        assertEquals(1, tableCount("campsite_vendor_refs"))

        val row =
            ctx
                .fetchOne(
                    """
                    SELECT c.id, c.primary_vendor_ref_id, c.name, c.kind, c.loop_name, c.equipment::text AS equipment,
                           vr.external_id, parent_ref.external_id AS parent_external_id
                    FROM campsites c
                    JOIN campsite_vendor_refs cvr ON cvr.campsite_id = c.id
                    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                    JOIN campgrounds cg ON cg.id = c.campground_id
                    JOIN campground_vendor_refs cgvr ON cgvr.campground_id = cg.id
                    JOIN vendor_refs parent_ref ON parent_ref.id = cgvr.vendor_ref_id
                    """.trimIndent(),
                )

        assertNotNull(row)
        assertEquals("Site 001", row.get("name", String::class.java))
        assertEquals("tent-only", row.get("kind", String::class.java))
        assertEquals("A", row.get("loop_name", String::class.java))
        assertEquals("upper-pines-site-001", row.get("external_id", String::class.java))
        assertEquals("upper-pines-campground-447", row.get("parent_external_id", String::class.java))

        val campsiteId = row.get("id", Long::class.java)
        val persisted = campsites.findById(campsiteId)
        assertNotNull(persisted)
        assertEquals(campsiteId, persisted.id)
        assertEquals("Site 001", persisted.name)
        assertEquals("campflare", persisted.dataSource)
        assertEquals(row.get("primary_vendor_ref_id", Long::class.java), persisted.primaryVendorRefId)
        assertEquals(json("""{"id":"upper-pines-site-001","campground_id":"upper-pines-campground-447"}"""), persisted.sourcePayload)
        assertEquals(campsiteId, campsites.findByPoi(poiIdForCampground("upper-pines-campground-447")).single().id)

        val availabilityTarget = campsites.findAvailabilityTargetById(campsiteId)
        assertNotNull(availabilityTarget)
        assertEquals(campsiteId, availabilityTarget.id)
        assertEquals("campflare", availabilityTarget.vendor)
        assertEquals("upper-pines-site-001", availabilityTarget.vendorId)
        assertEquals("Site 001", availabilityTarget.name)
    }

    @Test
    fun `additional campsite vendor refs on a Campflare import link to the Campflare row, not merge into recgov's`() {
        // Per-vendor identity, campsite edition: Campflare's site with an
        // additionalRef pointing at the recgov site must NOT overwrite recgov's
        // site row; both rows exist and share the recgov vendor_ref as matcher
        // input.
        val campgrounds = CampgroundRepo(ctx)
        val campsites = CampsiteRepo(ctx)
        campgrounds.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    vendor = "campflare",
                    vendorRefId = "upper-pines-campground-447",
                    name = "Upper Pines",
                    latitude = 37.739,
                    longitude = -119.565,
                    sourcePayload = json("""{"id":"upper-pines-campground-447"}"""),
                    vendorRefPayload = json("""{"campflare_id":"upper-pines-campground-447"}"""),
                ),
            ),
            source = "campflare-campgrounds",
        )
        campgrounds.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    vendor = "recgov",
                    vendorRefId = "232447",
                    name = "Upper Pines",
                    latitude = 37.739,
                    longitude = -119.565,
                    sourcePayload = json("""{"FacilityID":"232447"}"""),
                    vendorRefPayload = json("""{"recgov_id":"232447"}"""),
                ),
            ),
            source = "federal-campgrounds",
        )
        campsites.upsertCampsites(
            listOf(
                CampsiteUpsertCandidate(
                    vendor = "recgov",
                    vendorRefId = "100",
                    parentVendor = "recgov",
                    parentVendorRefId = "232447",
                    name = "Site 100",
                    kind = "standard",
                    sourcePayload = json("""{"site":"100"}"""),
                    vendorRefPayload = json("""{"recgov_id":"100"}"""),
                ),
            ),
            source = "federal-campsites",
        )

        campsites.upsertCampsites(
            listOf(
                CampsiteUpsertCandidate(
                    vendor = "campflare",
                    vendorRefId = "upper-pines-site-100",
                    parentVendor = "campflare",
                    parentVendorRefId = "upper-pines-campground-447",
                    name = "Campflare Site 100",
                    kind = "standard",
                    sourcePayload = json("""{"id":"upper-pines-site-100"}"""),
                    vendorRefPayload = json("""{"campflare_id":"upper-pines-site-100"}"""),
                    additionalVendorRefs =
                        listOf(
                            CatalogVendorRefUpsertCandidate(
                                vendor = "recgov",
                                vendorRefId = "100",
                                payload = json("""{"recgov_id":"100"}"""),
                            ),
                        ),
                ),
            ),
            source = "campflare-campsites",
        )

        // Two campsite rows (one per vendor); two campsite vendor_refs (the
        // recgov site ref is reused, not duplicated); three link rows.
        assertEquals(2, tableCount("campsites"))
        val campsiteRefCount =
            ctx
                .fetchOne(
                    "SELECT COUNT(*) AS n FROM vendor_refs WHERE entity_type = 'campsite'",
                )!!
                .get("n", Number::class.java)
                .toInt()
        assertEquals(2, campsiteRefCount)
        assertEquals(3, tableCount("campsite_vendor_refs"))

        val links =
            ctx
                .fetch(
                    """
                    SELECT cs.data_source, vr.vendor AS ref_vendor, vr.external_id
                    FROM campsite_vendor_refs cvr
                    JOIN campsites cs ON cs.id = cvr.campsite_id
                    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                    ORDER BY cs.data_source, vr.vendor
                    """.trimIndent(),
                ).map {
                    "${it.get("data_source")}|${it.get("ref_vendor")}:${it.get("external_id")}"
                }

        assertEquals(
            listOf(
                "campflare|campflare:upper-pines-site-100",
                "campflare|recgov:100",
                "recgov|recgov:100",
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
        assertEquals(listOf(teslaRow.id), teslaRepo.findAll().map { it.id })
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
        assertEquals(listOf(planetFitnessRow.id), planetFitnessRepo.findAll().map { it.id })
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
        // Locks in the bulk-pipeline contract: one repo call persists N
        // rows across every stage (vendor_refs, canonical rows, link table,
        // POI wrappers) regardless of chunk boundaries. Sizing well past
        // the internal BULK_CHUNK_SIZE (500) so at least one stage crosses
        // multiple chunks; a regression that reintroduces the O(N)
        // per-record loop would still pass correctness but shows up here
        // as an execution-time smell against future benchmarking.
        val campgrounds = CampgroundRepo(ctx)
        val campsites = CampsiteRepo(ctx)
        val batchSize = 1_500

        val campgroundRecords =
            (0 until batchSize).map { i ->
                CampgroundUpsertCandidate(
                    vendor = "campflare",
                    vendorRefId = "bulk-cg-$i",
                    name = "Bulk Campground $i",
                    latitude = 40.0 + i * 0.0001,
                    longitude = -120.0 - i * 0.0001,
                    sourcePayload = json("""{"id":"bulk-cg-$i"}"""),
                    vendorRefPayload = json("""{"campflare_id":"bulk-cg-$i"}"""),
                )
            }
        val campgroundResult =
            campgrounds.upsertCampgrounds(campgroundRecords, source = "campflare-campgrounds")

        assertEquals(batchSize, campgroundResult.seenCount)
        assertEquals(batchSize, campgroundResult.upsertedCount)
        assertEquals(batchSize, tableCount("campgrounds"))
        assertEquals(batchSize, tableCount("vendor_refs"))
        assertEquals(batchSize, tableCount("campground_vendor_refs"))
        assertEquals(batchSize, tableCount("pois"))
        assertEquals(batchSize, tableCount("poi_campgrounds"))

        val campsiteRecords =
            (0 until batchSize).map { i ->
                CampsiteUpsertCandidate(
                    vendor = "campflare",
                    vendorRefId = "bulk-cs-$i",
                    parentVendor = "campflare",
                    parentVendorRefId = "bulk-cg-$i",
                    name = "Bulk Campsite $i",
                    kind = "standard",
                    sourcePayload = json("""{"id":"bulk-cs-$i"}"""),
                    vendorRefPayload = json("""{"campflare_id":"bulk-cs-$i"}"""),
                )
            }
        val campsiteResult =
            campsites.upsertCampsites(campsiteRecords, source = "campflare-campsites")

        assertEquals(batchSize, campsiteResult.seenCount)
        assertEquals(batchSize, campsiteResult.upsertedCount)
        assertEquals(0, campsiteResult.skippedCount)
        assertEquals(batchSize, tableCount("campsites"))
        assertEquals(batchSize + batchSize, tableCount("vendor_refs"))
        assertEquals(batchSize, tableCount("campsite_vendor_refs"))

        // Re-running with a mutated payload should update in place, not
        // duplicate — sanity check for the ON CONFLICT branch across chunks.
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

    private fun tableCount(table: String): Int =
        ctx
            .fetchOne("SELECT COUNT(*) AS n FROM $table")!!
            .get("n", Number::class.java)
            .toInt()

    private fun poiIdForCampground(vendorRefId: String): Long =
        ctx
            .fetchOne(
                """
                SELECT pc.poi_id
                FROM poi_campgrounds pc
                JOIN campground_vendor_refs cvr ON cvr.campground_id = pc.campground_id
                JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                WHERE vr.external_id = ?
                """.trimIndent(),
                vendorRefId,
            )!!
            .get("poi_id", Long::class.java)

    private fun json(value: String) = Json.parseToJsonElement(value)
}
