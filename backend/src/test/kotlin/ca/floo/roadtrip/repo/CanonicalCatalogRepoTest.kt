package ca.floo.roadtrip.repo

import ca.floo.roadtrip.service.etl.framework.CampgroundEtlRecord
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlRecord
import ca.floo.roadtrip.service.etl.framework.CatalogVendorRefEtlRecord
import ca.floo.roadtrip.service.etl.framework.PlanetFitnessLocationEtlRecord
import ca.floo.roadtrip.service.etl.framework.TeslaSuperchargerEtlRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CanonicalCatalogRepoTest : SharedDbTest() {
    @BeforeEach
    fun resetCatalog() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `upserts campgrounds through vendor refs and creates lean POI wrapper`() {
        val repo = CanonicalCatalogRepo(ctx)
        val record =
            CampgroundEtlRecord(
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
                    SELECT cg.id, cg.etl_source, cg.name, cg.amenities::text AS amenities,
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
        assertEquals("campflare-campgrounds", row.get("etl_source", String::class.java))
        assertEquals("Upper Pines Campground", row.get("name", String::class.java))
        assertEquals("upper-pines-campground-447", row.get("external_id", String::class.java))
        assertEquals("https://api.campflare.com/v2/campground/upper-pines-campground-447", row.get("source_url", String::class.java))
        assertEquals(-119.565, row.get("lon", Double::class.java))
        assertEquals(37.739, row.get("lat", Double::class.java))
    }

    @Test
    fun `shared campground vendor refs create matches without merging etl source rows`() {
        val repo = CanonicalCatalogRepo(ctx)
        repo.upsertCampgrounds(
            listOf(
                CampgroundEtlRecord(
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
                CampgroundEtlRecord(
                    vendor = "campflare",
                    vendorRefId = "upper-pines-campground-447",
                    name = "Upper Pines Campflare",
                    latitude = 37.739,
                    longitude = -119.565,
                    sourcePayload = json("""{"id":"upper-pines-campground-447"}"""),
                    vendorRefPayload = json("""{"campflare_id":"upper-pines-campground-447"}"""),
                    additionalVendorRefs =
                        listOf(
                            CatalogVendorRefEtlRecord(
                                vendor = "federal-campgrounds",
                                vendorRefId = "recgov-232447",
                                payload = json("""{"recgov_id":"232447"}"""),
                            ),
                        ),
                ),
            ),
            source = "campflare-campgrounds",
        )

        assertEquals(2, tableCount("campgrounds"))
        assertEquals(2, tableCount("vendor_refs"))
        assertEquals(3, tableCount("campground_vendor_refs"))
        assertEquals(1, tableCount("campground_matches"))
        assertEquals(2, tableCount("pois"))
        assertEquals(2, tableCount("poi_campgrounds"))

        val refs =
            ctx
                .fetch(
                    """
                    SELECT cg.etl_source, vr.vendor, vr.external_id, cvr.is_primary
                    FROM campground_vendor_refs cvr
                    JOIN campgrounds cg ON cg.id = cvr.campground_id
                    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                    ORDER BY cg.etl_source, cvr.is_primary DESC, vr.vendor
                    """.trimIndent(),
                ).map {
                    "${it.get("etl_source")}:${it.get("vendor")}:${it.get("external_id")}:${it.get("is_primary")}"
                }

        assertEquals(
            listOf(
                "campflare-campgrounds:campflare:upper-pines-campground-447:true",
                "campflare-campgrounds:federal-campgrounds:recgov-232447:false",
                "federal-campgrounds:federal-campgrounds:recgov-232447:true",
            ),
            refs,
        )
        val heuristic =
            ctx
                .fetchOne("SELECT match_heuristic::text AS h FROM campground_matches")!!
                .get("h", String::class.java)
        val heuristicJson = Json.parseToJsonElement(heuristic).jsonObject
        assertEquals("shared_vendor_ref", heuristicJson["kind"]?.jsonPrimitive?.content)
        assertEquals("recgov-232447", heuristicJson["external_id"]?.jsonPrimitive?.content)

        ctx.execute("REFRESH MATERIALIZED VIEW catalog_match_rows")
        val materializedMatch =
            ctx
                .fetchOne(
                    """
                    SELECT left_etl_source,
                           left_primary_vendor,
                           left_primary_external_id,
                           right_etl_source,
                           right_primary_vendor,
                           right_primary_external_id,
                           match_heuristic->>'external_id' AS matched_ref
                    FROM catalog_match_rows
                    WHERE entity_type = 'campground'
                    """.trimIndent(),
                )

        assertNotNull(materializedMatch)
        assertEquals("federal-campgrounds", materializedMatch.get("left_etl_source", String::class.java))
        assertEquals("federal-campgrounds", materializedMatch.get("left_primary_vendor", String::class.java))
        assertEquals("recgov-232447", materializedMatch.get("left_primary_external_id", String::class.java))
        assertEquals("campflare-campgrounds", materializedMatch.get("right_etl_source", String::class.java))
        assertEquals("campflare", materializedMatch.get("right_primary_vendor", String::class.java))
        assertEquals("upper-pines-campground-447", materializedMatch.get("right_primary_external_id", String::class.java))
        assertEquals("recgov-232447", materializedMatch.get("matched_ref", String::class.java))
    }

    @Test
    fun `upserts campsites by resolving parent campground vendor ref`() {
        val repo = CanonicalCatalogRepo(ctx)
        repo.upsertCampgrounds(
            listOf(
                CampgroundEtlRecord(
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
            repo.upsertCampsites(
                listOf(
                    CampsiteEtlRecord(
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
                    SELECT c.name, c.kind, c.loop_name, c.equipment::text AS equipment,
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
    }

    @Test
    fun `shared campsite vendor refs create matches without merging etl source rows`() {
        val repo = CanonicalCatalogRepo(ctx)
        val campgroundResult =
            repo.upsertCampgrounds(
                listOf(
                    CampgroundEtlRecord(
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
        assertEquals(1, campgroundResult.upsertedCount)
        repo.upsertCampsites(
            listOf(
                CampsiteEtlRecord(
                    vendor = "recgov",
                    vendorRefId = "100",
                    parentVendor = "campflare",
                    parentVendorRefId = "upper-pines-campground-447",
                    name = "Site 100",
                    kind = "standard",
                    sourcePayload = json("""{"site":"100"}"""),
                    vendorRefPayload = json("""{"recgov_id":"100"}"""),
                ),
            ),
            source = "federal-campsites",
        )

        repo.upsertCampsites(
            listOf(
                CampsiteEtlRecord(
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
                            CatalogVendorRefEtlRecord(
                                vendor = "recgov",
                                vendorRefId = "100",
                                payload = json("""{"recgov_id":"100"}"""),
                            ),
                        ),
                ),
            ),
            source = "campflare-campsites",
        )

        assertEquals(2, tableCount("campsites"))
        assertEquals(3, tableCount("vendor_refs"))
        assertEquals(3, tableCount("campsite_vendor_refs"))
        assertEquals(1, tableCount("campsite_matches"))

        val refs =
            ctx
                .fetch(
                    """
                    SELECT c.etl_source, vr.vendor, vr.external_id, cvr.is_primary
                    FROM campsite_vendor_refs cvr
                    JOIN campsites c ON c.id = cvr.campsite_id
                    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                    ORDER BY c.etl_source, cvr.is_primary DESC, vr.vendor
                    """.trimIndent(),
                ).map {
                    "${it.get("etl_source")}:${it.get("vendor")}:${it.get("external_id")}:${it.get("is_primary")}"
                }

        assertEquals(
            listOf(
                "campflare-campsites:campflare:upper-pines-site-100:true",
                "campflare-campsites:recgov:100:false",
                "federal-campsites:recgov:100:true",
            ),
            refs,
        )
        val heuristic =
            ctx
                .fetchOne("SELECT match_heuristic::text AS h FROM campsite_matches")!!
                .get("h", String::class.java)
        val heuristicJson = Json.parseToJsonElement(heuristic).jsonObject
        assertEquals("shared_vendor_ref", heuristicJson["kind"]?.jsonPrimitive?.content)
        assertEquals("100", heuristicJson["external_id"]?.jsonPrimitive?.content)

        ctx.execute("REFRESH MATERIALIZED VIEW catalog_match_rows")
        val materializedMatch =
            ctx
                .fetchOne(
                    """
                    SELECT left_etl_source,
                           left_primary_vendor,
                           left_primary_external_id,
                           right_etl_source,
                           right_primary_vendor,
                           right_primary_external_id,
                           match_heuristic->>'external_id' AS matched_ref
                    FROM catalog_match_rows
                    WHERE entity_type = 'campsite'
                    """.trimIndent(),
                )

        assertNotNull(materializedMatch)
        assertEquals("federal-campsites", materializedMatch.get("left_etl_source", String::class.java))
        assertEquals("recgov", materializedMatch.get("left_primary_vendor", String::class.java))
        assertEquals("100", materializedMatch.get("left_primary_external_id", String::class.java))
        assertEquals("campflare-campsites", materializedMatch.get("right_etl_source", String::class.java))
        assertEquals("campflare", materializedMatch.get("right_primary_vendor", String::class.java))
        assertEquals("upper-pines-site-100", materializedMatch.get("right_primary_external_id", String::class.java))
        assertEquals("100", materializedMatch.get("matched_ref", String::class.java))
    }

    @Test
    fun `upserts Tesla superchargers and Planet Fitness locations through typed POI joins`() {
        val repo = CanonicalCatalogRepo(ctx)

        val tesla =
            repo.upsertTeslaSuperchargers(
                listOf(
                    TeslaSuperchargerEtlRecord(
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
            repo.upsertPlanetFitnessLocations(
                listOf(
                    PlanetFitnessLocationEtlRecord(
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

        val poiTypes =
            ctx
                .fetch("SELECT poi_type FROM pois ORDER BY poi_type")
                .map { it.get("poi_type", String::class.java) }
        assertEquals(listOf("planet_fitness_location", "tesla_supercharger"), poiTypes)
    }

    private fun tableCount(table: String): Int =
        ctx
            .fetchOne("SELECT COUNT(*) AS n FROM $table")!!
            .get("n", Number::class.java)
            .toInt()

    private fun json(value: String) = Json.parseToJsonElement(value)
}
