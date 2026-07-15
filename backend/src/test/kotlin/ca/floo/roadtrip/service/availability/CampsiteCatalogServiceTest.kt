package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
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

    private fun campgroundIdFor(poiId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poiId)!!
            .get("campground_id", Long::class.java)

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
