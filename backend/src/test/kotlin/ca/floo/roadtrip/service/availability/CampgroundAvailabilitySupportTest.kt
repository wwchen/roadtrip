package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.ref.DbRefResolver
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
    fun `preferredAvailabilityProvider returns null when provider is disabled`() {
        val campgroundId =
            ctx.seedCampground(
                name = "Upper Pines",
                source = "campflare",
                sourceId = "upper-pines-campground-447",
                bookingProvider = "campflare",
                bookingProviderRef = "upper-pines-campground-447",
                sourcePayloadJson = """{"campflare_id":"upper-pines-campground-447"}""",
            )
        val support =
            CampgroundAvailabilitySupport(
                refResolver = DbRefResolver(ctx),
                availabilityProviders =
                    AvailabilityProviderRegistry(
                        mapOf(
                            "campflare" to NoopCampflareProvider(enabled = false),
                        ),
                    ),
            )

        assertEquals(null, support.preferredAvailabilityProvider(campgroundId))
    }

    @Test
    fun `preferredAvailabilityProvider returns normalized provider id for the first provider ref`() {
        val campgroundId =
            ctx.seedCampground(
                name = "Upper Pines",
                source = "campflare",
                sourceId = "upper-pines-campground-447",
                bookingProvider = "campflare",
                bookingProviderRef = "upper-pines-campground-447",
                sourcePayloadJson = """{"campflare_id":"upper-pines-campground-447"}""",
            )
        val support = supportFor()

        assertEquals("campflare", support.preferredAvailabilityProvider(campgroundId))
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
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )
        val support = supportFor()

        assertEquals("recgov", support.preferredAvailabilityProvider(fixture.catalogId))
    }

    @Test
    fun `preferredAvailabilityProvider skips disabled providers`() {
        val campgroundId =
            ctx.seedCampground(
                name = "Upper Pines",
                source = "campflare",
                sourceId = "upper-pines-campground-447",
                bookingProvider = "campflare",
                bookingProviderRef = "upper-pines-campground-447",
                sourcePayloadJson = """{"campflare_id":"upper-pines-campground-447"}""",
            )
        val support = supportFor(campflareEnabled = false)

        assertEquals(null, support.preferredAvailabilityProvider(campgroundId))
    }

    private fun supportFor(campflareEnabled: Boolean = true): CampgroundAvailabilitySupport =
        CampgroundAvailabilitySupport(
            refResolver = DbRefResolver(ctx),
            availabilityProviders =
                AvailabilityProviderRegistry(
                    adaptersBySource =
                        mapOf(
                            "campflare" to NoopCampflareProvider(enabled = campflareEnabled),
                            "recgov" to NoopRecgovProvider(),
                        ),
                ),
        )

    private class NoopRecgovProvider : AvailabilityProvider {
        override val id: BookingProvider = BookingProvider.RECGOV
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override suspend fun availability(
            ref: BookingProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private class DecliningCampflareProvider : AvailabilityProvider {
        override val id: BookingProvider = BookingProvider.CAMPFLARE
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = false,
                bookingHorizonDays = 365,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override fun supportsRef(ref: BookingProviderRef): Boolean = false

        override suspend fun availability(
            ref: BookingProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private class NoopCampflareProvider(
        private val enabled: Boolean,
    ) : AvailabilityProvider {
        override val id: BookingProvider = BookingProvider.CAMPFLARE
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = false,
                bookingHorizonDays = 365,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = enabled

        override suspend fun availability(
            ref: BookingProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }
}
