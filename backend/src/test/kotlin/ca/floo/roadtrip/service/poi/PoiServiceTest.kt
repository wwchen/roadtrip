package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.domain.PlanetFitnessLocationUpsertCandidate
import ca.floo.roadtrip.model.domain.poi.Bbox
import ca.floo.roadtrip.model.domain.poi.CampgroundPoiDetail
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCatalogPoi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PoiServiceTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `detail row surfaces primary campground vendor ref`() {
        val poiId =
            seedPoi(
                providerRefJson = """{"transactionLocationId":-2147483647,"mapId":-2147483026,"resourceLocationId":-2147483640}""",
                propertiesJson = """{"upstream":{"booking_cta_provider_ref":null}}""",
            )

        val feature = poiService().poiDetail(poiId)
        val row = campgroundDetailRow(poiId)

        assertNotNull(feature)
        val publicRef = feature.campgroundDetail().providerRef!!.jsonObject
        assertEquals("-2147483026", publicRef["mapId"]!!.jsonPrimitive.content)
        assertEquals("-2147483647", publicRef["transactionLocationId"]!!.jsonPrimitive.content)
        assertEquals("-2147483640", publicRef["resourceLocationId"]!!.jsonPrimitive.content)
        assertNull(row.ctaProviderRefJson)
    }

    @Test
    fun `detail row ignores old materialized Aspira CTA ref from source payload`() {
        val poiId =
            seedPoi(
                providerRefJson = """{"transactionLocationId":-2147483647,"mapId":-2147483026,"resourceLocationId":-2147483640}""",
                propertiesJson =
                    """
                    {"upstream":{"booking_cta_provider_ref":{
                      "transactionLocationId":-2147483647,
                      "mapId":-2147483645,
                      "resourceLocationId":-2147483640
                    }}}
                    """.trimIndent(),
            )

        val row = campgroundDetailRow(poiId)

        assertNull(row.ctaProviderRefJson)
    }

    @Test
    fun `detail row projects first campground link as info URL`() {
        val link = "https://www.fs.usda.gov/recarea/tahoe/recarea/?recid=80728"
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "lake-of-the-woods-campground-192",
                name = "Lake Of The Woods Campground",
                lon = -120.391227722,
                lat = 39.503097534,
                source = "campflare",
                subcategory = null,
                agency = "USDA Forest Service",
                region = null,
                country = null,
                providerRefJson = """{"campflare_id":"lake-of-the-woods-campground-192"}""",
                bookingProvider = "campflare",
                bookingProviderRef = "lake-of-the-woods-campground-192",
            )
        ctx.execute(
            "UPDATE campgrounds SET links = ?::jsonb WHERE id = ?",
            """[{"url":"$link","title":"Lake of the Woods"}]""",
            fixture.catalogId,
        )

        val feature = poiService().poiDetail(fixture.poiId)

        assertNotNull(feature)
        assertEquals(link, feature.campgroundDetail().infoUrl)
    }

    @Test
    fun `detail row exposes sources and vendor refs via canonical view`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "232869",
                name = "Cold Creek",
                lon = -120.3147222,
                lat = 39.5427778,
                source = "recgov",
                subcategory = "established",
                agency = "USDA Forest Service",
                region = "CA",
                country = "US",
                providerRefJson = """{"recgov_id":"232869"}""",
                bookingProvider = "recgov",
                bookingProviderRef = "232869",
            )
        ctx.execute(
            "UPDATE campgrounds SET reservation_url = ? WHERE id = ?",
            "https://www.recreation.gov/camping/campgrounds/232869",
            fixture.catalogId,
        )

        val feature = poiService().poiDetail(fixture.poiId)

        assertNotNull(feature)
        assertEquals("recgov", feature.properties.source)
        assertEquals("232869", feature.properties.sourceId)
        val detail = feature.campgroundDetail()
        assertEquals("https://www.recreation.gov/camping/campgrounds/232869", detail.reserveUrl)
        val publicRef = detail.providerRef!!.jsonObject
        assertEquals("232869", publicRef["recgov_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("recgov"), detail.sources)
    }

    @Test
    fun `detail provider ref follows linked campground provider candidate ordering`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "upper-pines-campflare",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "campflare",
                providerRefJson = """{"campflare_id":"upper-pines-campground-447"}""",
                bookingProvider = "campflare",
                bookingProviderRef = "upper-pines-campground-447",
            )

        val row = campgroundDetailRow(fixture.poiId)
        val publicRef =
            Json
                .parseToJsonElement(row.providerRefJson!!)
                .jsonObject
        assertEquals("upper-pines-campground-447", publicRef["campflare_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `low zoom default poi request suppresses campgrounds`() {
        ctx.seedCatalogPoi(sourceId = "cg-1", name = "Camp", lon = -123.0, lat = 49.0, poiType = "campground")
        ctx.seedCatalogPoi(sourceId = "tesla-1", name = "Tesla", lon = -123.05, lat = 49.05, poiType = "tesla_supercharger")
        ctx.seedCatalogPoi(
            sourceId = "pf-1",
            name = "Planet Fitness",
            lon = -123.1,
            lat = 49.1,
            poiType = "planet_fitness_location",
        )

        val categories =
            poiService()
                .pois(
                    bbox = vancouverBbox,
                    zoom = CampgroundService.MIN_POI_ZOOM - 1,
                    categories = null,
                ).features
                .map { it.properties.category }
                .toSet()

        assertEquals(setOf("tesla_supercharger", "planet_fitness_location"), categories)
    }

    @Test
    fun `low zoom campground-only request returns no pois`() {
        ctx.seedCatalogPoi(sourceId = "cg-1", name = "Camp", lon = -123.0, lat = 49.0, poiType = "campground")
        ctx.seedCatalogPoi(sourceId = "tesla-1", name = "Tesla", lon = -123.05, lat = 49.05, poiType = "tesla_supercharger")

        val features =
            poiService()
                .pois(
                    bbox = vancouverBbox,
                    zoom = CampgroundService.MIN_POI_ZOOM - 1,
                    categories = listOf("campground"),
                ).features

        assertEquals(emptyList(), features)
    }

    // A recreation.gov campground's fees, stay limit and directions live in the
    // RIDB record. The FE used to synthesise `upstream` from three Campflare keys,
    // so those pins rendered an empty provenance table.
    @Test
    fun `campground detail serves the source record as upstream`() {
        // No providerRefJson: seedCampground binds `providerRefJson ?: sourcePayloadJson`
        // into source_payload, so passing one would displace the record under test.
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "232869",
                name = "Cold Creek",
                lon = -120.31,
                lat = 39.54,
                source = "recgov",
                propertiesJson = """{"RECAREA":{"RecAreaName":"Lassen"},"StayLimit":"14 days"}""",
            )

        val detail = poiService().poiDetail(fixture.poiId)!!.properties.detail

        assertEquals(
            "14 days",
            detail.upstream!!
                .jsonObject["StayLimit"]!!
                .jsonPrimitive.content,
        )
        // The same blob used to go out as `raw` as well; nothing read it once the
        // FE stopped promoting out of it.
        assertNull(detail.raw)
    }

    @Test
    fun `campground detail reads description and photo from canonical columns`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "232869",
                name = "Cold Creek",
                lon = -120.31,
                lat = 39.54,
                source = "recgov",
            )
        ctx.execute(
            """
            UPDATE campgrounds
            SET medium_description = ?, photos = ?::jsonb
            WHERE id = ?
            """.trimIndent(),
            "Camp among redwoods.",
            """[{"url":"https://example.test/large.jpg"}]""",
            fixture.catalogId,
        )

        val detail = poiService().poiDetail(fixture.poiId)!!.properties.detail

        assertEquals("Camp among redwoods.", detail.description)
        assertEquals("https://example.test/large.jpg", detail.photoUrl)
    }

    // The three fields that are JSONB key lookups, not whole-column reads — a
    // typo'd key (wrong column, or the wrong key inside the right column)
    // compiles, passes every other gate, and serves null forever.
    @Test
    fun `campground detail extracts email, elevation and last_verified from nested JSONB`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "232869",
                name = "Cold Creek",
                lon = -120.31,
                lat = 39.54,
                source = "recgov",
            )
        ctx.execute(
            """
            UPDATE campgrounds
            SET contact = ?::jsonb, location = ?::jsonb, metadata = ?::jsonb
            WHERE id = ?
            """.trimIndent(),
            """{"email":"lavo_info@nps.gov"}""",
            """{"elevation":1798}""",
            """{"last_updated":"2026-06-01"}""",
            fixture.catalogId,
        )

        val detail = poiService().poiDetail(fixture.poiId)!!.properties.detail

        assertEquals("lavo_info@nps.gov", detail.email)
        assertEquals(1798.0, detail.elevation)
        assertEquals("2026-06-01", detail.lastVerified)
    }

    // The remaining table rows are whole-column reads: a wrong RHS (e.g.
    // `cellCoverage = campground.amenities`) still compiles and still passes a
    // type-blind test, so this asserts each field against a distinct seeded value.
    @Test
    fun `campground detail serves its own columns as named schema fields`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "232869",
                name = "Cold Creek",
                lon = -120.31,
                lat = 39.54,
                source = "recgov",
            )
        ctx.execute(
            """
            UPDATE campgrounds
            SET status = ?, status_description = ?, kind = ?,
                price = ?::jsonb, default_campsite_schedule = ?::jsonb, amenities = ?::jsonb,
                cell_service = ?::jsonb, max_rv_length = ?, max_trailer_length = ?,
                has_pull_through_sites = ?, big_rig_friendly = ?,
                links = ?::jsonb, alerts = ?::jsonb, connections = ?::jsonb,
                metadata = ?::jsonb, management = ?::jsonb, contact = ?::jsonb
            WHERE id = ?
            """.trimIndent(),
            "Open",
            "Open seasonally",
            "federal",
            """{"minimum":26,"maximum":36}""",
            """{"check_in_time":"14:00"}""",
            """{"showers":true}""",
            """{"level":"weak"}""",
            32.0,
            28.0,
            true,
            false,
            """[{"url":"https://example.test"}]""",
            """[{"message":"road closed"}]""",
            """{"power":"30/50 amp"}""",
            """{"last_updated":"2026-06-01"}""",
            """{"agency":"NPS"}""",
            """{"email":"a@b.test"}""",
            fixture.catalogId,
        )

        val detail = poiService().poiDetail(fixture.poiId)!!.properties.detail

        assertEquals("Open", detail.status)
        assertEquals("Open seasonally", detail.statusDescription)
        assertEquals("federal", detail.kind)
        assertEquals(
            "26",
            detail.price!!
                .jsonObject["minimum"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "14:00",
            detail.schedule!!
                .jsonObject["check_in_time"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "true",
            detail.amenities!!
                .jsonObject["showers"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "weak",
            detail.cellCoverage!!
                .jsonObject["level"]!!
                .jsonPrimitive.content,
        )
        assertEquals(32.0, detail.maxRvLength)
        assertEquals(28.0, detail.maxTrailerLength)
        assertEquals(true, detail.hasPullThroughSites)
        assertEquals(false, detail.bigRigFriendly)
        assertEquals(
            "https://example.test",
            detail.links!!
                .jsonArray[0]
                .jsonObject["url"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "road closed",
            detail.alerts!!
                .jsonArray[0]
                .jsonObject["message"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "30/50 amp",
            detail.connections!!
                .jsonObject["power"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "2026-06-01",
            detail.metadata!!
                .jsonObject["last_updated"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "NPS",
            detail.management!!
                .jsonObject["agency"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "a@b.test",
            detail.contact!!
                .jsonObject["email"]!!
                .jsonPrimitive.content,
        )
    }

    // Campflare's upstream keys (original_url, primary_phone, primary_email)
    // are mapped to the canonical ones by its ETL now, and V55 rewrote the
    // rows that predate that. Every stored row therefore looks like this one.
    @Test
    fun `campground detail reads photo, phone and email from the canonical columns`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "campflare-447",
                name = "Upper Pines",
                lon = -119.565,
                lat = 37.739,
                source = "campflare",
            )
        ctx.execute(
            """
            UPDATE campgrounds
            SET photos = ?::jsonb, contact = ?::jsonb
            WHERE id = ?
            """.trimIndent(),
            """[{"url":"https://cdn.example/p.jpg"}]""",
            """{"phone":"555-0100","email":"info@example.test"}""",
            fixture.catalogId,
        )

        val detail = poiService().poiDetail(fixture.poiId)!!.properties.detail

        assertEquals("https://cdn.example/p.jpg", detail.photoUrl)
        assertEquals("555-0100", detail.phone)
        assertEquals("info@example.test", detail.email)
    }

    // Mirrors the charger NULL-coercion pin below: the recgov ETL never writes
    // these four columns (only Campflare's does), so a recgov row has them
    // NULL and they must come back null, not the primitive-class zero value
    // (0.0 / false).
    @Test
    fun `campground with no rig-size data serves null, not zero or false`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "no-rig-data",
                name = "Somewhere",
                lon = -119.565,
                lat = 37.739,
            )

        val detail = poiService().poiDetail(fixture.poiId)!!.properties.detail

        assertNull(detail.maxRvLength)
        assertNull(detail.maxTrailerLength)
        assertNull(detail.hasPullThroughSites)
        assertNull(detail.bigRigFriendly)
    }

    // Charger fields are whole-column reads too: a wrong RHS (e.g.
    // `trailerFriendly = supercharger.twentyFourSeven`) still compiles and still
    // passes a type-blind test, so this asserts each field against a distinct
    // seeded value. `hardware_counts` is deliberately absent — no ETL ever
    // fills it, so it is not served.
    @Test
    fun `charger detail serves its own columns as named schema fields`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "redding-ca",
                name = "Redding, CA",
                lon = -122.3917,
                lat = 40.5865,
                poiType = "tesla_supercharger",
            )
        ctx.execute(
            """
            UPDATE tesla_superchargers
            SET site_status = ?, time_zone = ?, amenities = ?::jsonb,
                stall_count = ?, max_power_kw = ?, pricebooks = ?::jsonb,
                availability_profile = ?::jsonb,
                open_to_non_teslas = NULL, trailer_friendly = ?, twenty_four_seven = ?,
                index_payload = ?::jsonb, detail_payload = ?::jsonb
            WHERE id = ?
            """.trimIndent(),
            "CONSTRUCTION",
            "America/Los_Angeles",
            """["AMENITIES_WIFI"]""",
            12,
            250,
            """[{"feeType":"CHARGING"}]""",
            """{"availabilityProfile":{"weekday":"busy"}}""",
            // A literal NULL above, then true, false — three booleans, three
            // distinct states, so a swapped assignment between any pair fails.
            true,
            false,
            """{"supercharger_function":{"site_status":"INDEX_ONLY_STATUS"}}""",
            """{"commonSiteName":"Downtown Redding"}""",
            fixture.catalogId,
        )

        val detail = poiService().poiDetail(fixture.poiId)!!.properties.detail

        assertEquals("CONSTRUCTION", detail.status)
        assertEquals("America/Los_Angeles", detail.timeZone)
        assertEquals(
            "AMENITIES_WIFI",
            detail.amenities!!
                .jsonArray[0]
                .jsonPrimitive.content,
        )
        assertEquals(12, detail.stallCount)
        assertEquals(250, detail.powerKilowatt)
        assertEquals(
            "CHARGING",
            detail.pricebooks!!
                .jsonArray[0]
                .jsonObject["feeType"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "busy",
            detail.availabilityProfile!!
                .jsonObject["availabilityProfile"]!!
                .jsonObject["weekday"]!!
                .jsonPrimitive.content,
        )
        assertNull(detail.openToNonTeslas)
        assertEquals(true, detail.trailerFriendly)
        assertEquals(false, detail.twentyFourSeven)
        assertEquals(
            "INDEX_ONLY_STATUS",
            detail.upstream!!
                .jsonObject["index"]!!
                .jsonObject["supercharger_function"]!!
                .jsonObject["site_status"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "Downtown Redding",
            detail.upstream
                .jsonObject["detail"]!!
                .jsonObject["commonSiteName"]!!
                .jsonPrimitive.content,
        )
    }

    // `stall_count`/`max_power_kw` read via `Int::class.java` (a JVM primitive)
    // instead of `Int::class.javaObjectType`, so jOOQ silently turned a NULL
    // hardware spec into 0 rather than surfacing "unknown". Neither column is
    // written by `seedCatalogPoi`, so they are SQL NULL here without an UPDATE.
    @Test
    fun `charger with unknown hardware specs serves null, not zero`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "no-hardware-specs",
                name = "Somewhere",
                lon = -122.3917,
                lat = 40.5865,
                poiType = "tesla_supercharger",
            )

        val detail = poiService().poiDetail(fixture.poiId)!!.properties.detail

        assertNull(detail.stallCount)
        assertNull(detail.powerKilowatt)
    }

    // The bug this pins: a gym's hours lived only in `payload.tags`, and every
    // reader goes through `to_jsonb(planet_fitness_locations)`, which carries
    // columns. Hours reached no caller, so the drawer's chip was dead on every
    // gym in production.
    @Test
    fun `gym detail carries hours, brand and the upstream tag table`() {
        val poiId = seedGym()

        val detail = poiService().poiDetail(poiId)!!.properties.detail

        assertEquals("Mo-Su 05:00-22:00", detail.openingHours)
        assertEquals("Planet Fitness", detail.brand)
        assertEquals(
            "Mo-Su 05:00-22:00",
            detail.upstream!!
                .jsonObject["opening_hours"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `a gym the source tagged nothing about sends no hours and no upstream table`() {
        val poiId = seedGym(openingHours = null, tagsJson = null)

        val detail = poiService().poiDetail(poiId)!!.properties.detail

        assertNull(detail.openingHours)
        assertNull(detail.upstream, "an empty tag map must drop the table, not render it blank")
        // The table is single-brand by construction, so this never goes missing.
        assertEquals("Planet Fitness", detail.brand)
    }

    private fun seedGym(
        openingHours: String? = "Mo-Su 05:00-22:00",
        tagsJson: String? = """"tags":{"brand":"Planet Fitness","opening_hours":"Mo-Su 05:00-22:00"},""",
    ): Long {
        PlanetFitnessLocationRepo(ctx).upsertPlanetFitnessLocationBatch(
            listOf(
                PlanetFitnessLocationUpsertCandidate(
                    locationId = GYM_LOCATION_ID,
                    name = "Planet Fitness",
                    latitude = 49.1,
                    longitude = -123.1,
                    country = "US",
                    openingHours = openingHours,
                    payload = Json.parseToJsonElement("""{${tagsJson ?: ""}"type":"node","id":448794721}"""),
                ),
            ),
        )
        return ctx
            .fetchOne(
                """
                SELECT ppf.poi_id
                FROM poi_planet_fitness_locations ppf
                JOIN planet_fitness_locations pfl ON pfl.id = ppf.planet_fitness_location_id
                WHERE pfl.location_id = ?
                """.trimIndent(),
                GYM_LOCATION_ID,
            )!!
            .get("poi_id", Long::class.java)
    }

    private fun seedPoi(
        providerRefJson: String,
        propertiesJson: String = "{}",
    ): Long =
        ctx
            .seedCatalogPoi(
                sourceId = "-2147483647:-2147483026",
                name = "Lake Louise Campground",
                lon = -116.18,
                lat = 51.42,
                source = SOURCE,
                subcategory = "federal",
                agency = "Parks Canada",
                region = "AB",
                country = "CA",
                providerRefJson = providerRefJson,
                propertiesJson = propertiesJson,
                bookingProvider = SOURCE,
                bookingProviderRef = "pc:-2147483647:-2147483026:-2147483640",
            ).poiId

    private fun poiService(): PoiService =
        PoiService(
            poiRepo = PoiServingRepo(ctx, enabledDataProviders = setOf(SOURCE, "campflare", "recgov")),
            detailServices =
                listOf(
                    CampgroundService(
                        campgroundRepo = CampgroundRepo(ctx),
                        dateResolver =
                            ca.floo.roadtrip.service.availability
                                .AvailabilityDateResolver(PoiRepo(ctx)),
                    ),
                    TeslaSuperchargerService(TeslaSuperchargerRepo(ctx)),
                    PlanetFitnessLocationService(PlanetFitnessLocationRepo(ctx)),
                ),
        )

    private fun campgroundDetailRow(poiId: Long): CampgroundPoiDetail = CampgroundRepo(ctx).findPoiDetailByPoi(poiId)!!

    private fun PoiDetailFeatureSchema.campgroundDetail(): PoiCategoryDetailSchema = properties.detail

    private companion object {
        const val SOURCE = "aspira"
        const val GYM_LOCATION_ID = "node-448794721"
        val vancouverBbox = Bbox(west = -125.0, south = 47.0, east = -120.0, north = 51.0)
    }
}
