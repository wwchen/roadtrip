package ca.floo.roadtrip.repo

import ca.floo.roadtrip.service.etl.framework.CampgroundEtlRecord
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlRecord
import ca.floo.roadtrip.service.etl.framework.CatalogVendorRefEtlRecord
import ca.floo.roadtrip.service.etl.framework.PlanetFitnessLocationEtlRecord
import ca.floo.roadtrip.service.etl.framework.TeslaSuperchargerEtlRecord
import kotlinx.serialization.json.Json
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
    }

    @Test
    fun `additional campground vendor refs attach to an existing canonical row`() {
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

        assertEquals(1, tableCount("campgrounds"))
        assertEquals(2, tableCount("vendor_refs"))
        assertEquals(2, tableCount("campground_vendor_refs"))

        val refs =
            ctx
                .fetch(
                    """
                    SELECT vr.vendor, vr.external_id
                    FROM campground_vendor_refs cvr
                    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                    ORDER BY vr.vendor
                    """.trimIndent(),
                ).map { "${it.get("vendor")}:${it.get("external_id")}" }

        assertEquals(
            listOf("campflare:upper-pines-campground-447", "federal-campgrounds:recgov-232447"),
            refs,
        )
    }

    @Test
    fun `different vendors for the same real-world campground land in distinct per-vendor rows`() {
        val repo = CanonicalCatalogRepo(ctx)
        val recgovRecord =
            CampgroundEtlRecord(
                vendor = "recgov",
                vendorRefId = "232447",
                name = "Upper Pines",
                latitude = 37.739,
                longitude = -119.565,
                sourcePayload = json("""{"FacilityID":"232447"}"""),
                vendorRefPayload = json("""{"recgov_id":"232447"}"""),
            )
        val campflareRecord =
            CampgroundEtlRecord(
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

        // Two campground rows, each tagged with its own etl_source.
        assertEquals(2, tableCount("campgrounds"))
        assertEquals(2, tableCount("vendor_refs"))
        assertEquals(2, tableCount("campground_vendor_refs"))

        val rows =
            ctx
                .fetch(
                    """
                    SELECT cg.etl_source, vr.vendor AS ref_vendor, vr.external_id
                    FROM campgrounds cg
                    JOIN campground_vendor_refs cvr ON cvr.campground_id = cg.id
                    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                    ORDER BY cg.etl_source
                    """.trimIndent(),
                ).map {
                    "${it.get("etl_source")}|${it.get("ref_vendor")}:${it.get("external_id")}"
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
                    SELECT cg.etl_source, cg.name
                    FROM campgrounds cg
                    ORDER BY cg.etl_source
                    """.trimIndent(),
                ).map { "${it.get("etl_source")}|${it.get("name")}" }

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
    fun `additional campsite vendor refs attach to an existing canonical row`() {
        val repo = CanonicalCatalogRepo(ctx)
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

        assertEquals(1, tableCount("campsites"))
        assertEquals(3, tableCount("vendor_refs"))
        assertEquals(2, tableCount("campsite_vendor_refs"))

        val refs =
            ctx
                .fetch(
                    """
                    SELECT vr.vendor, vr.external_id
                    FROM campsite_vendor_refs cvr
                    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                    ORDER BY vr.vendor
                    """.trimIndent(),
                ).map { "${it.get("vendor")}:${it.get("external_id")}" }

        assertEquals(listOf("campflare:upper-pines-site-100", "recgov:100"), refs)
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
