package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.domain.ProviderRef
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CanonicalViewRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class CampgroundAvailabilitySupportTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `preferredAvailabilityProvider falls back through recgov alias when campflare provider declines the ref`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "upper-pines-campflare-support",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "campflare",
                providerRefJson = """{"campflare_id":"upper-pines-campground-447"}""",
            )
        linkCampgroundRef(
            campgroundId = fixture.catalogId,
            vendor = "recgov",
            externalId = "recgov-232447",
            payloadJson = """{"recgov_id":"232447"}""",
        )
        val support =
            CampgroundAvailabilitySupport(
                providerRefs = CampsiteProviderRepo(ctx),
                availabilityProviders =
                    AvailabilityProviderRegistry(
                        mapOf(
                            "campflare" to DecliningCampflareProvider(),
                            "recgov" to NoopRecgovProvider(),
                        ),
                    ),
            )

        assertEquals("recgov", support.preferredAvailabilityProvider(fixture.catalogId))
    }

    @Test
    fun `preferredAvailabilityProvider returns normalized provider id for the first provider ref`() {
        val campground = seedDualVendorCampground()
        val support = supportFor()

        assertEquals("campflare", support.preferredAvailabilityProvider(campground.campgroundId))
    }

    @Test
    fun `preferredAvailabilityProvider reports recgov for recgov catalog source`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "recgov-232447",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "recgov",
                providerRefJson = """{"recgov_id":"232447"}""",
            )
        val support = supportFor()

        assertEquals("recgov", support.preferredAvailabilityProvider(fixture.catalogId))
    }

    @Test
    fun `preferredAvailabilityProvider skips disabled providers`() {
        val campground = seedDualVendorCampground()
        val support =
            supportFor(
                campflareEnabled = false,
            )

        assertEquals("recgov", support.preferredAvailabilityProvider(campground.campgroundId))
    }

    private fun supportFor(campflareEnabled: Boolean = true): CampgroundAvailabilitySupport =
        CampgroundAvailabilitySupport(
            providerRefs = CampsiteProviderRepo(ctx),
            availabilityProviders =
                AvailabilityProviderRegistry(
                    adaptersBySource =
                        mapOf(
                            "campflare" to NoopCampflareProvider(enabled = campflareEnabled),
                            "recgov" to NoopRecgovProvider(),
                        ),
                ),
        )

    private data class MultiRefCampground(
        val campgroundId: Long,
    )

    private fun seedDualVendorCampground(): MultiRefCampground {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "upper-pines-preferred-provider",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "campflare",
                providerRefJson = """{"campflare_id":"upper-pines-campground-447"}""",
            )
        linkCampgroundRef(
            campgroundId = fixture.catalogId,
            vendor = "recgov",
            externalId = "recgov-232447",
            payloadJson = """{"recgov_id":"232447"}""",
        )
        CanonicalViewRepo(ctx).refreshCanonicalViews()
        return MultiRefCampground(campgroundId = fixture.catalogId)
    }

    private fun linkCampgroundRef(
        campgroundId: Long,
        vendor: String,
        externalId: String,
        payloadJson: String,
    ) {
        val vendorRefId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO vendor_refs (vendor, entity_type, external_id, payload)
                    VALUES (?, 'campground', ?, ?::jsonb)
                    RETURNING id
                    """.trimIndent(),
                    vendor,
                    externalId,
                    payloadJson,
                )!!
                .get("id", Long::class.java)
        ctx.execute(
            """
            INSERT INTO campground_vendor_refs (campground_id, vendor_ref_id)
            VALUES (?, ?)
            """.trimIndent(),
            campgroundId,
            vendorRefId,
        )
    }

    private class NoopRecgovProvider : AvailabilityProvider {
        override val id: AvailabilityProviderId = AvailabilityProviderId.RECGOV
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private class DecliningCampflareProvider : AvailabilityProvider {
        override val id: AvailabilityProviderId = AvailabilityProviderId.CAMPFLARE
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = false,
                bookingHorizonDays = 365,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override fun supportsRef(ref: ProviderRef): Boolean = false

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private class NoopCampflareProvider(
        private val enabled: Boolean,
    ) : AvailabilityProvider {
        override val id: AvailabilityProviderId = AvailabilityProviderId.CAMPFLARE
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = false,
                bookingHorizonDays = 365,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = enabled

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }
}
