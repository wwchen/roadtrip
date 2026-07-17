package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.client.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.model.domain.ProviderRef
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.availability.provider.CampflareAvailabilityProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

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
                ).poiId
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = campgroundIdFor(poiId),
                vendor = "custom_catalog",
                vendorId = "site-100",
                name = "Site 100",
            )
        val providerRefs = CampsiteProviderRepo(ctx)
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = providerRefs,
                campsitesRepo = campsitesRepo,
                availabilityProviders = AvailabilityProviderRegistry(mapOf("test" to TemplateProvider)),
                dateResolver = AvailabilityDateResolver(),
            )
        val service = CampsiteCatalogService(providerRefs, campsitesRepo, targets)

        val response = service.campsitesForPoi(poiId, siteTypes = emptyList())

        assertEquals(campsiteId, response.campsites.single().id)
        assertEquals(
            "provider-template://232447/custom_catalog/site-100",
            response.campsites.single().reservationUrlTemplate,
        )
    }

    @Test
    fun `campflare campsite raw recgov URL is exposed as reservation URL template`() {
        val poi =
            ctx.seedCatalogPoi(
                sourceId = "white-wolf-campground-567",
                name = "White Wolf",
                lon = -119.65,
                lat = 37.87,
                source = "campflare",
                providerRefJson = """{"campflare_id":"white-wolf-campground-567"}""",
            )
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = poi.catalogId,
                vendor = "campflare",
                vendorId = "campflare-site-10",
                name = "10",
                providerRefJson = """{"campflare_id":"campflare-site-10","campground_id":"white-wolf-campground-567"}""",
                sourcePayloadJson = """{"reservation_url":"https://www.recreation.gov/camping/campsites/10174516"}""",
            )
        val providerRefs = CampsiteProviderRepo(ctx)
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = providerRefs,
                campsitesRepo = campsitesRepo,
                availabilityProviders =
                    AvailabilityProviderRegistry(
                        mapOf("campflare" to CampflareAvailabilityProvider(unusedCampflareClient(), enabled = true)),
                    ),
                dateResolver = AvailabilityDateResolver(),
            )
        val service = CampsiteCatalogService(providerRefs, campsitesRepo, targets)

        val response = service.campsitesForPoi(poi.poiId, siteTypes = emptyList())

        assertEquals(campsiteId, response.campsites.single().id)
        assertEquals(
            "https://www.recreation.gov/camping/campsites/10174516?startDate={start_date}&endDate={end_date}",
            response.campsites.single().reservationUrlTemplate,
        )
    }

    private fun campgroundIdFor(poiId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poiId)!!
            .get("campground_id", Long::class.java)

    private fun unusedCampflareClient(): CampflareAvailabilityClient =
        CampflareAvailabilityClient { _, _, _ -> error("Campflare availability client should not be called") }

    private object TemplateProvider : AvailabilityProvider {
        override val id: AvailabilityProviderId = AvailabilityProviderId.RECGOV
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override fun reservationUrlTemplate(
            campsite: CampsiteAvailabilityTarget,
            parentRef: ProviderRef,
        ): String? {
            val parentId = (parentRef as ProviderRef.RecGov).recgovId
            return "provider-template://$parentId/${campsite.vendor}/${campsite.vendorId}"
        }

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }
}
