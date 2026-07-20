package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CampgroundAvailabilitySupportTest {
    @Test
    fun `supportsCampground returns false when provider is disabled`() {
        val provider = NoopCampflareProvider(enabled = false)
        val campground =
            campground(
                dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-447"),
                bookingProvider = "campflare",
                bookingProviderRef = "upper-pines-447",
            )

        assertFalse(provider.supportsCampground(campground))
    }

    @Test
    fun `supportsCampground returns true for matching campflare provider`() {
        val provider = NoopCampflareProvider(enabled = true)
        val campground =
            campground(
                dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-447"),
                bookingProvider = "campflare",
                bookingProviderRef = "upper-pines-447",
            )

        assertTrue(provider.supportsCampground(campground))
    }

    @Test
    fun `supportsCampground campflare matches even when bookingProvider is recgov`() {
        val provider = NoopCampflareProvider(enabled = true)
        val campground =
            campground(
                dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-447"),
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )

        assertTrue(provider.supportsCampground(campground))
    }

    @Test
    fun `supportsCampground default impl uses bookingProvider and bookingProviderRef`() {
        val provider = NoopRecgovProvider()
        val campground =
            campground(
                dataProviderRef = DataProviderRef.RecGov(id = "232447"),
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )

        assertTrue(provider.supportsCampground(campground))
    }

    @Test
    fun `supportsCampground returns false when bookingProvider is null`() {
        val provider = NoopRecgovProvider()
        val campground =
            campground(
                dataProviderRef = DataProviderRef.RecGov(id = "232447"),
                bookingProvider = null,
                bookingProviderRef = null,
            )

        assertFalse(provider.supportsCampground(campground))
    }

    @Test
    fun `first matching provider wins in list iteration`() {
        val providers =
            listOf(
                NoopCampflareProvider(enabled = true),
                NoopRecgovProvider(),
            )
        val campground =
            campground(
                dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-447"),
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )

        val match = providers.firstOrNull { it.supportsCampground(campground) }
        assertEquals(BookingProvider.CAMPFLARE, match?.id)
    }

    private fun campground(
        dataProviderRef: DataProviderRef,
        bookingProvider: String?,
        bookingProviderRef: String?,
    ): Campground =
        Campground(
            id = 1L,
            name = "Test Campground",
            status = null,
            statusDescription = null,
            kind = null,
            shortDescription = null,
            mediumDescription = null,
            longDescription = null,
            location = JsonNull,
            defaultCampsiteSchedule = JsonNull,
            amenities = JsonNull,
            maxRvLength = null,
            maxTrailerLength = null,
            hasPullThroughSites = null,
            bigRigFriendly = null,
            reservationUrl = null,
            links = JsonNull,
            photos = JsonNull,
            alerts = JsonNull,
            price = JsonNull,
            cellService = JsonNull,
            management = JsonNull,
            contact = JsonNull,
            connections = JsonNull,
            metadata = JsonNull,
            sourcePayload = JsonNull,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            deletedAt = null,
            dataProviderRef = dataProviderRef,
            bookingProvider = bookingProvider,
            bookingProviderRef = bookingProviderRef,
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
            campground: Campground,
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

        override fun supportsCampground(campground: Campground): Boolean =
            isEnabled() && campground.dataProviderRef is DataProviderRef.Campflare

        override suspend fun availability(
            campground: Campground,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }
}
