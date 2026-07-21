package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingAdapter
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val TEST_CAMPSITE_ID = 7L
private const val TEST_RECGOV_PARENT_ID = "recgov-parent-1"
private const val TEST_CAMPFLARE_PARENT_ID = "campflare-parent-1"

class AvailabilityBookingTargetResolverTest {
    private val campflareProvider = FakeAvailabilityProvider(BookingProvider.CAMPFLARE)
    private val recgovProvider = FakeAvailabilityProvider(BookingProvider.RECGOV)

    @Test
    fun `targetFor skips availability-only campflare candidate and returns recgov booking target`() {
        val registry = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider()))
        val resolver = AvailabilityBookingTargetResolver(registry)
        val resolved =
            resolvedTarget(
                candidates = listOf(campflareProvider, recgovProvider),
            )

        val target = resolver.targetFor(BookingAction.ADD_TO_CART, resolved)

        assertEquals(BookingProvider.RECGOV, target?.providerId)
        assertEquals(BookingProviderRef.RecGov(TEST_RECGOV_PARENT_ID), target?.parentRef)
        assertEquals(TEST_CAMPSITE_ID, target?.campsiteId)
        assertEquals(TEST_CAMPFLARE_PARENT_ID, target?.vendorSiteId)
    }

    @Test
    fun `targetFor returns null when no candidate maps to supported booking provider`() {
        val registry = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider()))
        val resolver = AvailabilityBookingTargetResolver(registry)

        val target = resolver.targetFor(BookingAction.ADD_TO_CART, resolvedTarget(candidates = listOf(campflareProvider)))

        assertNull(target)
    }

    private class RecGovOnlyBookingProvider : BookingAdapter {
        override val id: BookingProvider = BookingProvider.RECGOV

        override fun targetFor(
            parentRef: BookingProviderRef,
            campsiteId: Long,
            vendorSiteId: String,
        ): BookingTarget? {
            if (parentRef !is BookingProviderRef.RecGov) return null
            return BookingTarget(
                providerId = id,
                parentRef = parentRef,
                campsiteId = campsiteId,
                vendorSiteId = vendorSiteId,
            )
        }

        override fun can(
            action: BookingAction,
            target: BookingTarget,
        ): Boolean =
            action == BookingAction.ADD_TO_CART &&
                target.providerId == BookingProvider.RECGOV &&
                target.parentRef is BookingProviderRef.RecGov

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult = AddToCartResult.Unsupported
    }

    private class FakeAvailabilityProvider(
        override val id: BookingProvider,
    ) : AvailabilityProvider {
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = true,
                bookingHorizonDays = 365,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override fun parentRefFor(campground: Campground): BookingProviderRef? =
            when (id) {
                BookingProvider.CAMPFLARE -> BookingProviderRef.Campflare(TEST_CAMPFLARE_PARENT_ID)
                else -> super.parentRefFor(campground)
            }

        override suspend fun availability(
            campground: Campground,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private fun resolvedTarget(candidates: List<AvailabilityProvider>): ResolvedAvailabilityTarget {
        val cg =
            campground(
                bookingProvider = "recgov",
                bookingProviderRef = TEST_RECGOV_PARENT_ID,
                dataProviderRef = DataProviderRef.RecGov(id = TEST_RECGOV_PARENT_ID),
            )
        return ResolvedAvailabilityTarget(
            campsite = campsite(),
            provider = candidates.first(),
            campground = cg,
            parentPoiId = 100L,
            dateContext = PoiDateContext(ZoneId.of("UTC"), LocalDate.parse("2026-07-01")),
            candidates = candidates,
        )
    }

    private fun campground(
        bookingProvider: String?,
        bookingProviderRef: String?,
        dataProviderRef: DataProviderRef = DataProviderRef.Campflare(id = TEST_CAMPFLARE_PARENT_ID),
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

    private fun campsite(): Campsite =
        campsiteFixture(
            id = TEST_CAMPSITE_ID,
            vendor = "campflare",
            vendorId = TEST_CAMPFLARE_PARENT_ID,
            name = "Site 7",
            loopName = null,
            kind = null,
            sourcePayload = null,
        )
}
