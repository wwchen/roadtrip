package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.client.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.route.common.roadtripApiJson
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.CampflareAvailabilityProvider
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CampsiteCatalogServiceTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `reservation URL template comes from resolved availability provider`() {
        val poiId =
            ctx
                .seedCatalogPoi(
                    sourceId = "p1",
                    name = "Upper Pines",
                    lon = -119.56,
                    lat = 37.74,
                    providerRefJson = """{"recgov_id": "232447"}""",
                    bookingProvider = "recgov",
                    bookingProviderRef = "232447",
                ).poiId
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = campgroundIdFor(poiId),
                vendor = "recgov",
                vendorId = "site-100",
                name = "Site 100",
            )
        val refResolver =
            ca.floo.roadtrip.service.ref
                .DbRefResolver(ctx)
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                refResolver = refResolver,
                ctx = ctx,
                campsitesRepo = campsitesRepo,
                availabilityProviders = listOf(TemplateProvider),
                dateResolver = AvailabilityDateResolver(ctx),
                pollerRepo = AvailabilityPollerRepo(ctx),
            )
        val service = CampsiteCatalogService(refResolver, campsitesRepo, targets)

        val response = service.campsitesForPoi(poiId, siteTypes = emptyList())

        assertEquals(campsiteId, response.campsites.single().id)
        assertEquals(
            "recgov",
            response.campsites
                .single()
                .dataProviderRef.provider.id,
        )
        assertEquals(
            "site-100",
            response.campsites
                .single()
                .dataProviderRef
                .serialize(),
        )
        assertEquals("site", response.campsites.single().kind)
        assertEquals(
            "provider-template://232447/recgov/site-100",
            response.reservationUrlTemplates.getValue(campsiteId),
        )
        val json = roadtripApiJson.encodeToJsonElement(response).jsonObject
        val campsiteJson =
            json
                .getValue("campsites")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("recgov", campsiteJson.getValue("data_provider").jsonPrimitive.content)
        assertEquals("site-100", campsiteJson.getValue("data_provider_ref").jsonPrimitive.content)
        assertEquals("site", campsiteJson.getValue("kind").jsonPrimitive.content)
        assertFalse(campsiteJson.containsKey("dataProviderRef"))
        assertEquals(
            "provider-template://232447/recgov/site-100",
            json
                .getValue("reservation_url_templates")
                .jsonObject
                .getValue(campsiteId.toString())
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `campflare campsite reservation URL is exposed as reservation URL template`() {
        val poi =
            ctx.seedCatalogPoi(
                sourceId = "white-wolf-campground-567",
                name = "White Wolf",
                lon = -119.65,
                lat = 37.87,
                source = "campflare",
                providerRefJson = """{"campflare_id":"white-wolf-campground-567"}""",
                bookingProvider = "campflare",
                bookingProviderRef = "white-wolf-campground-567",
            )
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = poi.catalogId,
                vendor = "campflare",
                vendorId = "campflare-site-10",
                name = "10",
                reservationUrl = "https://www.recreation.gov/camping/campsites/10174516",
                sourcePayloadJson =
                    """{"campflare_id":"campflare-site-10","campground_id":"white-wolf-campground-567","reservation_url":"https://www.recreation.gov/camping/campsites/10174516"}""",
                bookingProvider = "campflare",
                bookingProviderRef = "campflare-site-10",
            )
        val refResolver =
            ca.floo.roadtrip.service.ref
                .DbRefResolver(ctx)
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                refResolver = refResolver,
                ctx = ctx,
                campsitesRepo = campsitesRepo,
                availabilityProviders = listOf(CampflareAvailabilityProvider(unusedCampflareClient(), enabled = true)),
                dateResolver = AvailabilityDateResolver(ctx),
                pollerRepo = AvailabilityPollerRepo(ctx),
            )
        val service = CampsiteCatalogService(refResolver, campsitesRepo, targets)

        val response = service.campsitesForPoi(poi.poiId, siteTypes = emptyList())

        assertEquals(campsiteId, response.campsites.single().id)
        assertEquals(
            "https://www.recreation.gov/camping/campsites/10174516?startDate={start_date}&endDate={end_date}",
            response.reservationUrlTemplates.getValue(campsiteId),
        )
    }

    private fun campgroundIdFor(poiId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poiId)!!
            .get("campground_id", Long::class.java)

    private fun unusedCampflareClient(): CampflareAvailabilityClient =
        CampflareAvailabilityClient { _, _, _ -> error("Campflare availability client should not be called") }

    private object TemplateProvider : AvailabilityProvider {
        override val id: BookingProvider = BookingProvider.RECGOV
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override fun reservationUrlTemplate(
            campsite: Campsite,
            parentRef: BookingProviderRef,
            catalogRef: CatalogCampsiteRef,
        ): String? {
            val parentId = (parentRef as BookingProviderRef.RecGov).facilityId
            return "provider-template://$parentId/${campsite.dataProviderRef.provider.id}/${catalogRef.vendorId}"
        }

        override suspend fun availability(
            ref: BookingProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }
}
