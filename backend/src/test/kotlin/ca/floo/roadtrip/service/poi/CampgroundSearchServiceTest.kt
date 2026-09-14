package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.config.CampgroundSearchConfig
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.fixtures.testBookingHorizons
import ca.floo.roadtrip.model.api.campground.CampgroundDetailsRequestDto
import ca.floo.roadtrip.model.api.campground.CampgroundFilterDto
import ca.floo.roadtrip.model.api.campground.CampgroundSearchRequestDto
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampgroundSearchRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.BookingIdentityResolver
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TAHOE =
    """{"type":"Polygon","coordinates":[[[-120.4,38.7],[-119.6,38.7],[-119.6,39.4],[-120.4,39.4],[-120.4,38.7]]]}"""

class CampgroundSearchServiceTest : SharedDbTest() {
    private fun service(config: CampgroundSearchConfig = CampgroundSearchConfig.default) =
        CampgroundSearchService(
            searchRepo = CampgroundSearchRepo(ctx, enabledDataProviders = setOf("recgov", "campflare")),
            campgroundRepo = CampgroundRepo(ctx),
            bookingHorizons = testBookingHorizons(ctx, emptyList()),
            identities = BookingIdentityResolver(shippedTenantRegistry()),
            cta = CampgroundCta(shippedTenantRegistry()),
            config = config,
        )

    private fun boundary(json: String) = Json.parseToJsonElement(json).jsonObject

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `search returns ids inside the boundary that pass the filter`() {
        val tent = ctx.seedCatalogPoi(sourceId = "t", name = "Tent Flat", lon = -120.0, lat = 39.0)
        ctx.seedCampsite(campgroundId = tent.campgroundId, vendorId = "1", kind = CampsiteKind.TENT.wire)
        val rv = ctx.seedCatalogPoi(sourceId = "r", name = "RV Park", lon = -120.1, lat = 39.0)
        ctx.seedCampsite(campgroundId = rv.campgroundId, vendorId = "2", kind = CampsiteKind.RV.wire)
        CampsiteRepo(ctx).refreshSiteSummaries(listOf(tent.campgroundId, rv.campgroundId))

        val response =
            service().search(
                CampgroundSearchRequestDto(boundary = boundary(TAHOE), filter = CampgroundFilterDto(siteType = "tent")),
            )

        assertEquals(listOf(tent.poiId), response.campgroundIds)
        assertEquals(2, response.totalInBoundary)
        assertFalse(response.truncated)
    }

    @Test
    fun `a missing or non-polygon boundary is refused`() {
        val missing = assertFailsWith<CampgroundSearchRequestException> { service().search(CampgroundSearchRequestDto()) }
        assertEquals("bad_boundary", missing.code)

        val point =
            assertFailsWith<CampgroundSearchRequestException> {
                service().search(CampgroundSearchRequestDto(boundary = boundary("""{"type":"Point","coordinates":[0,0]}""")))
            }
        assertEquals("bad_boundary", point.code)

        val nullCoordinates =
            assertFailsWith<CampgroundSearchRequestException> {
                service().search(CampgroundSearchRequestDto(boundary = boundary("""{"type":"Polygon","coordinates":null}""")))
            }
        assertEquals("bad_boundary", nullCoordinates.code)

        val arrayType =
            assertFailsWith<CampgroundSearchRequestException> {
                service().search(
                    CampgroundSearchRequestDto(boundary = boundary("""{"type":["Polygon"],"coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}""")),
                )
            }
        assertEquals("bad_boundary", arrayType.code)
    }

    @Test
    fun `an unknown site type is refused`() {
        val error =
            assertFailsWith<CampgroundSearchRequestException> {
                service().search(
                    CampgroundSearchRequestDto(boundary = boundary(TAHOE), filter = CampgroundFilterDto(siteType = "yurt")),
                )
            }
        assertEquals("bad_request", error.code)
    }

    @Test
    fun `details maps the summary row onto the DTO`() {
        val poi =
            ctx.seedCatalogPoi(
                sourceId = "nb",
                name = "Nevada Beach",
                lon = -119.943,
                lat = 38.976,
                agency = "USDA Forest Service",
                region = "NV",
                amenitiesJson = """[{"key":"toilets","present":true},{"key":"showers","present":false}]""",
            )
        ctx.seedCampsite(campgroundId = poi.campgroundId, vendorId = "1", kind = CampsiteKind.TENT.wire, maxPeople = 8)
        CampsiteRepo(ctx).refreshSiteSummaries(listOf(poi.campgroundId))

        val response = service().details(CampgroundDetailsRequestDto(campgroundIds = listOf(poi.poiId)))

        val summary = response.campgrounds.single()
        assertEquals(poi.poiId, summary.id)
        assertEquals(poi.campgroundId, summary.campgroundId)
        assertEquals("Nevada Beach", summary.name)
        assertEquals("NV", summary.region)
        assertEquals("USDA Forest Service", summary.agency)
        assertEquals(mapOf("tent" to 1), summary.siteCounts)
        assertEquals(1, summary.siteTotal)
        assertEquals(8, summary.maxPeople)
        assertEquals(listOf("toilets", "showers"), summary.amenities.map { it.key })
        assertFalse(summary.availabilitySupported)
    }

    @Test
    fun `details without sites reports zero and no people count`() {
        val poi = ctx.seedCatalogPoi(sourceId = "e", name = "Empty", lon = -120.0, lat = 39.0)

        val summary = service().details(CampgroundDetailsRequestDto(campgroundIds = listOf(poi.poiId))).campgrounds.single()

        assertEquals(0, summary.siteTotal)
        assertTrue(summary.siteCounts.isEmpty())
        assertNull(summary.maxPeople)
    }

    @Test
    fun `details refuses more ids than the cap`() {
        val error =
            assertFailsWith<CampgroundSearchRequestException> {
                service(CampgroundSearchConfig(maxResults = 10, maxDetailIds = 2))
                    .details(CampgroundDetailsRequestDto(campgroundIds = listOf(1, 2, 3)))
            }
        assertEquals("too_many_ids", error.code)
    }

    @Test
    fun `details with no ids is an empty answer, not an error`() {
        assertTrue(service().details(CampgroundDetailsRequestDto()).campgrounds.isEmpty())
    }
}
