package ca.floo.roadtrip.route.api.campgrounds

import ca.floo.roadtrip.config.CampgroundSearchConfig
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.fixtures.testBookingHorizons
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampgroundSearchRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.route.routeTestApplication
import ca.floo.roadtrip.service.availability.BookingIdentityResolver
import ca.floo.roadtrip.service.poi.CampgroundSearchService
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private const val TAHOE =
    """{"type":"Polygon","coordinates":[[[-120.4,38.7],[-119.6,38.7],[-119.6,39.4],[-120.4,39.4],[-120.4,38.7]]]}"""

class CampgroundRoutesTest : SharedDbTest() {
    private fun service(config: CampgroundSearchConfig = CampgroundSearchConfig.default) =
        CampgroundSearchService(
            searchRepo = CampgroundSearchRepo(ctx, enabledDataProviders = setOf("recgov", "campflare")),
            campgroundRepo = CampgroundRepo(ctx),
            bookingHorizons = testBookingHorizons(ctx, emptyList()),
            identities = BookingIdentityResolver(shippedTenantRegistry()),
            cta = CampgroundCta(shippedTenantRegistry()),
            config = config,
        )

    private fun error(body: String) =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]!!
            .jsonPrimitive.content

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `search answers ids, the boundary total and the truncation flag`() =
        testApplication {
            val tent = ctx.seedCatalogPoi(sourceId = "t", name = "Tent Flat", lon = -120.0, lat = 39.0)
            ctx.seedCampsite(campgroundId = tent.campgroundId, vendorId = "1", kind = CampsiteKind.TENT.wire)
            CampsiteRepo(ctx).refreshSiteSummaries(listOf(tent.campgroundId))
            application { routeTestApplication { campgroundRoutes(service(), CampgroundSearchConfig.default) } }

            val resp =
                client.post("/api/campgrounds/search") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"boundary":$TAHOE,"filter":{"site_type":"tent"}}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(listOf(tent.poiId), body["campground_ids"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() })
            assertEquals(1, body["total_in_boundary"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, body["total_matching"]!!.jsonPrimitive.content.toInt())
            assertEquals("false", body["truncated"]!!.jsonPrimitive.content)
        }

    @Test
    fun `search with a structurally invalid boundary is a 400 bad_boundary`() =
        testApplication {
            application { routeTestApplication { campgroundRoutes(service(), CampgroundSearchConfig.default) } }
            val nonArrayCoordinates = """{"type":"Polygon","coordinates":"nope"}"""

            val resp =
                client.post("/api/campgrounds/search") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"boundary":$nonArrayCoordinates}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("bad_boundary", error(resp.bodyAsText()))
        }

    @Test
    fun `search without a boundary is a 400 naming the boundary`() =
        testApplication {
            application { routeTestApplication { campgroundRoutes(service(), CampgroundSearchConfig.default) } }

            val resp =
                client.post("/api/campgrounds/search") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"filter":{}}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("bad_boundary", error(resp.bodyAsText()))
        }

    @Test
    fun `search with an unparseable body is a 400`() =
        testApplication {
            application { routeTestApplication { campgroundRoutes(service(), CampgroundSearchConfig.default) } }

            val resp =
                client.post("/api/campgrounds/search") {
                    contentType(ContentType.Application.Json)
                    setBody("not json")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("bad_request", error(resp.bodyAsText()))
        }

    @Test
    fun `details answers one summary per known id`() =
        testApplication {
            val poi = ctx.seedCatalogPoi(sourceId = "nb", name = "Nevada Beach", lon = -119.943, lat = 38.976, agency = "USFS")
            ctx.seedCampsite(campgroundId = poi.campgroundId, vendorId = "1", kind = CampsiteKind.TENT.wire, maxPeople = 6)
            CampsiteRepo(ctx).refreshSiteSummaries(listOf(poi.campgroundId))
            application { routeTestApplication { campgroundRoutes(service(), CampgroundSearchConfig.default) } }

            val resp =
                client.post("/api/campgrounds/details") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"campground_ids":[${poi.poiId},999999]}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val campgrounds = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["campgrounds"]!!.jsonArray
            assertEquals(1, campgrounds.size)
            val summary = campgrounds.single().jsonObject
            assertEquals("Nevada Beach", summary["name"]!!.jsonPrimitive.content)
            assertEquals("USFS", summary["agency"]!!.jsonPrimitive.content)
            assertEquals(6, summary["max_people"]!!.jsonPrimitive.content.toInt())
            assertEquals(
                1,
                summary["site_counts"]!!
                    .jsonObject["tent"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun `details over the cap is a 400 too_many_ids`() =
        testApplication {
            val config = CampgroundSearchConfig(maxResults = 10, maxDetailIds = 1)
            application { routeTestApplication { campgroundRoutes(service(config), config) } }

            val resp =
                client.post("/api/campgrounds/details") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"campground_ids":[1,2]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("too_many_ids", error(resp.bodyAsText()))
        }
}
