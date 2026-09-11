package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.fixtures.testCampgroundService
import ca.floo.roadtrip.model.api.BookingRefDto
import ca.floo.roadtrip.model.api.poi.CarrierSignalDto
import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.api.poi.PriceDto
import ca.floo.roadtrip.model.api.poi.RatingDto
import ca.floo.roadtrip.model.api.poi.ScheduleDto
import ca.floo.roadtrip.model.domain.PlanetFitnessLocationUpsertCandidate
import ca.floo.roadtrip.model.domain.bookingRef
import ca.floo.roadtrip.model.domain.poi.Bbox
import ca.floo.roadtrip.model.domain.poi.CampgroundPoiDetail
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
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
        val poiId = seedPoi()

        val feature = poiService().poiDetail(poiId)
        val row = campgroundDetailRow(poiId)

        assertNotNull(feature)
        val ref = feature.campgroundDetail().bookingRef!!
        assertEquals("aspira", ref.provider)
        assertEquals("pc:-2147483647:-2147483026:-2147483640", ref.ref)
        assertEquals(aspiraBookingRef, row.campground.bookingRef())
    }

    @Test
    fun `detail row publishes the serving provider's horizon as latest_date`() {
        val poiId = seedPoi()
        val provider = FakeAvailabilityProvider(id = BookingProvider.ASPIRA, bookingHorizonDays = TEST_BOOKING_HORIZON_DAYS)

        val detail = poiService(listOf(provider)).poiDetail(poiId)!!.campgroundDetail()

        val earliest = LocalDate.parse(detail.earliestDate!!)
        assertEquals(earliest.plusDays(TEST_BOOKING_HORIZON_DAYS.toLong()).toString(), detail.latestDate)
        // No registered provider claims the pin: no ceiling rather than a wrong one.
        assertNull(poiService().poiDetail(poiId)!!.campgroundDetail().latestDate)
    }

    @Test
    fun `detail row ignores a stale CTA ref left in the source payload`() {
        val poiId =
            seedPoi(
                sourcePayloadJson =
                    """
                    {"booking_cta_provider_ref":{
                      "transactionLocationId":-2147483647,
                      "mapId":-2147483645,
                      "resourceLocationId":-2147483640
                    }}
                    """.trimIndent(),
            )

        val row = campgroundDetailRow(poiId)

        assertEquals(aspiraBookingRef, row.campground.bookingRef())
    }

    @Test
    fun `a booking provider without a ref does not advertise availability support`() {
        val poiId =
            ctx
                .seedCatalogPoi(
                    sourceId = "-2147483647:-2147483027",
                    name = "Refless Campground",
                    lon = -116.19,
                    lat = 51.43,
                    source = SOURCE,
                    bookingProvider = SOURCE,
                    bookingProviderRef = null,
                ).poiId

        val detail = poiService().poiDetail(poiId)!!.campgroundDetail()

        assertNull(detail.bookingRef)
        assertNull(detail.availabilitySupported)
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
        assertEquals(BookingRefDto("recgov", "232869"), detail.bookingRef)
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
        assertEquals(BookingProviderRef.Campflare(campgroundId = "upper-pines-campground-447"), row.campground.bookingRef())
    }

    @Test
    fun `an aliased Campflare pin books through rec_gov with no booking adapters registered`() {
        // Campflare serves the aliased row while rec.gov sells it: booking
        // follows the registry's `sells`, not whichever adapters this process
        // happens to have wired.
        val fixture = seedAliasedCampflarePoi()
        ctx.execute(
            "UPDATE campgrounds SET reservation_url = ? WHERE id = ?",
            "https://www.recreation.gov/camping/campgrounds/234784",
            fixture.catalogId,
        )
        val campflare = FakeAvailabilityProvider(id = BookingProvider.CAMPFLARE)

        val detail =
            poiService(availabilityProviders = listOf(campflare))
                .poiDetail(fixture.poiId)!!
                .campgroundDetail()

        // The row's primary is Campflare, but rec.gov books it through the alias.
        assertEquals(BookingRefDto(BookingProvider.RECGOV.id, "234784"), detail.bookingRef)
        assertEquals(true, detail.availabilitySupported)
        assertEquals("Recreation.gov", detail.bookingSystem)
        assertEquals("Reserve on Recreation.gov", detail.cta?.first()?.label)
        // Availability still comes from whoever serves it.
        assertEquals(BookingProvider.CAMPFLARE.id, detail.availabilityProvider)
        // The row itself still declares Campflare — the booking vendor is
        // resolved above the repo, not stamped into the column.
        assertEquals(
            BookingProviderRef.Campflare(campgroundId = "icicle-group-campground-8149"),
            campgroundDetailRow(fixture.poiId).campground.bookingRef(),
        )
    }

    @Test
    fun `a rec_gov primary campground books through rec_gov`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "232869-recgov",
                name = "Kalaloch Campground",
                lon = -124.37,
                lat = 47.61,
                source = "recgov",
                providerRefJson = """{"recgov_id":"232869"}""",
                bookingProvider = BookingProvider.RECGOV.id,
                bookingProviderRef = "232869",
            )
        ctx.execute(
            "UPDATE campgrounds SET reservation_url = ? WHERE id = ?",
            "https://www.recreation.gov/camping/campgrounds/232869",
            fixture.catalogId,
        )
        val recgov = FakeAvailabilityProvider(id = BookingProvider.RECGOV)

        val detail =
            poiService(availabilityProviders = listOf(recgov))
                .poiDetail(fixture.poiId)!!
                .campgroundDetail()

        assertEquals(BookingRefDto(BookingProvider.RECGOV.id, "232869"), detail.bookingRef)
        assertEquals(BookingProvider.RECGOV.id, detail.availabilityProvider)
        assertEquals("Recreation.gov", detail.bookingSystem)
        assertEquals("Reserve on Recreation.gov", detail.cta?.single()?.label)
    }

    @Test
    fun `a Campflare-only pin stays Campflare`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "cranberry-lake-wsp",
                name = "Cranberry Lake",
                lon = -122.64,
                lat = 48.39,
                source = "campflare",
                providerRefJson = """{"campflare_id":"cranberry-lake-wsp"}""",
                bookingProvider = BookingProvider.CAMPFLARE.id,
                bookingProviderRef = "cranberry-lake-wsp",
            )
        val campflare = FakeAvailabilityProvider(id = BookingProvider.CAMPFLARE)

        // Campflare sells nothing, and the row names no other vendor, so the
        // serving provider's claim is the identity.
        val detail =
            poiService(availabilityProviders = listOf(campflare))
                .poiDetail(fixture.poiId)!!
                .campgroundDetail()

        assertEquals(BookingRefDto(BookingProvider.CAMPFLARE.id, "cranberry-lake-wsp"), detail.bookingRef)
        assertEquals(BookingProvider.CAMPFLARE.id, detail.availabilityProvider)
        assertEquals("Campflare", detail.bookingSystem)
        assertEquals("View on Campflare", detail.cta?.single()?.label)
    }

    @Test
    fun `booking_system names the aspira tenant`() {
        // Two multi-tenant vendors: the drawer names the agency a person books
        // with, not the platform behind it.
        val bcParks =
            ctx.seedCatalogPoi(
                sourceId = "1:-2147483470",
                name = "Alice Lake Campground",
                lon = -123.12,
                lat = 49.78,
                source = SOURCE,
                region = "BC",
                country = "CA",
                bookingProvider = BookingProvider.ASPIRA.id,
                bookingProviderRef = "bc:1:-2147483470:-2147483400",
            )
        val newYork =
            ctx.seedCatalogPoi(
                sourceId = "117",
                name = "Kenneth L. Wilson",
                lon = -74.21,
                lat = 42.02,
                source = DataProvider.RESERVEAMERICA.id,
                region = "NY",
                country = "US",
                bookingProvider = BookingProvider.RESERVEAMERICA.id,
                bookingProviderRef = "NY:117",
            )

        assertEquals("BC Parks", poiService().poiDetail(bcParks.poiId)!!.campgroundDetail().bookingSystem)
        assertEquals("New York State Parks", poiService().poiDetail(newYork.poiId)!!.campgroundDetail().bookingSystem)
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
            SET status = ?, status_description = ?, kind = ?, parent_name = ?,
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
            "Lassen Volcanic National Park",
            """{"minimum":26,"maximum":36,"currency":"USD"}""",
            """{"check_in":"14:00","check_out":"11:00"}""",
            """[{"key":"showers","present":true},{"key":"water","present":false}]""",
            """[{"carrier":"verizon","average":1.5,"count":9}]""",
            32.0,
            28.0,
            true,
            false,
            """[{"url":"https://example.test"}]""",
            """[{"body":"road closed"}]""",
            """{"power":"30/50 amp"}""",
            """{"last_updated":"2026-06-01","activities":["Hiking"],"rating":{"average":4.3,"count":87}}""",
            """{"agency":"NPS"}""",
            """{"email":"a@b.test"}""",
            fixture.catalogId,
        )

        val detail = poiService().poiDetail(fixture.poiId)!!.properties.detail

        assertEquals("Open", detail.status)
        assertEquals("Open seasonally", detail.statusDescription)
        assertEquals("federal", detail.kind)
        assertEquals("Lassen Volcanic National Park", detail.parentName)
        assertEquals(PriceDto(minimum = 26.0, maximum = 36.0, currency = "USD"), detail.price)
        assertEquals(ScheduleDto(checkIn = "14:00", checkOut = "11:00"), detail.schedule)
        // The negative label is applied backend-side, so `water` reads "No water".
        assertEquals(listOf("showers" to "Showers", "water" to "No water"), detail.amenities.map { it.key to it.label })
        assertEquals(
            CarrierSignalDto(carrier = "verizon", label = "Verizon", average = 1.5, count = 9),
            detail.cellCoverage.single(),
        )
        assertEquals(listOf("Hiking"), detail.activities)
        assertEquals(RatingDto(average = 4.3, count = 87), detail.rating)
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
        assertEquals("road closed", detail.alerts.single().body)
        assertEquals(
            "30/50 amp",
            detail.connections!!
                .jsonObject["power"]!!
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

    // Several vendors set the parent to the campground itself, which renders as
    // "Cold Creek, in Cold Creek". A parent is only a parent when it differs.
    @Test
    fun `campground detail drops a parent name that is the campground's own`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "232870",
                name = "Cold Creek",
                lon = -120.31,
                lat = 39.54,
                source = "recgov",
            )
        ctx.execute("UPDATE campgrounds SET parent_name = ? WHERE id = ?", "  cold creek ", fixture.catalogId)

        assertNull(
            poiService()
                .poiDetail(fixture.poiId)!!
                .properties.detail.parentName,
        )

        ctx.execute("UPDATE campgrounds SET parent_name = ? WHERE id = ?", "Cold Creek Recreation Area", fixture.catalogId)

        assertEquals(
            "Cold Creek Recreation Area",
            poiService()
                .poiDetail(fixture.poiId)!!
                .properties.detail.parentName,
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
        assertEquals(emptyList(), detail.amenities)
        assertEquals(listOf("AMENITIES_WIFI"), detail.chargerAmenities)
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

    // The amenities column is whatever the vendor sent, so a non-string element
    // must be skipped rather than crash the whole detail read.
    @Test
    fun `charger amenities keep the strings and skip everything else`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "mixed-amenities",
                name = "Mixed",
                lon = -122.3917,
                lat = 40.5865,
                poiType = "tesla_supercharger",
            )
        ctx.execute(
            "UPDATE tesla_superchargers SET amenities = ?::jsonb WHERE id = ?",
            """["AMENITIES_WIFI",{"name":"restroom"},null]""",
            fixture.catalogId,
        )

        val detail = poiService().poiDetail(fixture.poiId)!!.properties.detail

        assertEquals(listOf("AMENITIES_WIFI"), detail.chargerAmenities)
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

    private fun seedPoi(sourcePayloadJson: String = "{}"): Long =
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
                propertiesJson = sourcePayloadJson,
                bookingProvider = SOURCE,
                bookingProviderRef = "pc:-2147483647:-2147483026:-2147483640",
            ).poiId

    private fun poiService(availabilityProviders: List<AvailabilityProvider> = emptyList()): PoiService =
        PoiService(
            poiRepo =
                PoiServingRepo(
                    ctx,
                    enabledDataProviders =
                        setOf(SOURCE, DataProvider.CAMPFLARE.id, DataProvider.RECGOV.id, DataProvider.RESERVEAMERICA.id),
                ),
            detailServices =
                listOf(
                    testCampgroundService(ctx, availabilityProviders),
                    TeslaSuperchargerService(TeslaSuperchargerRepo(ctx)),
                    PlanetFitnessLocationService(PlanetFitnessLocationRepo(ctx)),
                ),
        )

    /** POI 8149's shape: a Campflare row rec.gov also sells, under facility 234784. */
    private fun seedAliasedCampflarePoi() =
        ctx.seedCatalogPoi(
            sourceId = "icicle-group-campground-8149",
            name = "Icicle Group Campground",
            lon = -120.78,
            lat = 47.55,
            source = "campflare",
            providerRefJson = """{"campflare_id":"icicle-group-campground-8149"}""",
            bookingProvider = BookingProvider.CAMPFLARE.id,
            bookingProviderRef = "icicle-group-campground-8149",
            bookingAliasesJson = """[{"provider":"${BookingProvider.RECGOV.id}","ref":"234784"}]""",
        )

    private fun campgroundDetailRow(poiId: Long): CampgroundPoiDetail = CampgroundRepo(ctx).findPoiDetailByPoi(poiId)!!

    private fun PoiDetailFeatureSchema.campgroundDetail(): PoiCategoryDetailSchema = properties.detail

    private companion object {
        const val SOURCE = "aspira"
        const val TEST_BOOKING_HORIZON_DAYS = 183
        const val GYM_LOCATION_ID = "node-448794721"
        val vancouverBbox = Bbox(west = -125.0, south = 47.0, east = -120.0, north = 51.0)
        val aspiraBookingRef =
            BookingProviderRef.Aspira(
                tenant = "pc",
                transactionLocationId = -2147483647,
                mapId = -2147483026,
                resourceLocationId = -2147483640,
            )
    }
}
