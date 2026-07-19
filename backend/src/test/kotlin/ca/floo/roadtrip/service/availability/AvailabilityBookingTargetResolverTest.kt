package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingAdapter
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val TEST_CAMPSITE_ID = 7L
private const val TEST_RECGOV_SITE_ID = "recgov-site-7"
private const val TEST_RECGOV_PARENT_ID = "recgov-parent-1"
private const val TEST_CAMPFLARE_PARENT_ID = "campflare-parent-1"

class AvailabilityBookingTargetResolverTest {
    @Test
    fun `targetFor skips availability-only campflare candidate and returns recgov booking target`() {
        val registry = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider()))
        val resolver = AvailabilityBookingTargetResolver(registry)
        val resolved =
            resolvedTarget(
                candidates =
                    listOf(
                        campflareCandidate(),
                        recgovCandidate(),
                    ),
            )

        val target = resolver.targetFor(BookingAction.ADD_TO_CART, resolved)

        assertEquals(BookingProvider.RECGOV, target?.providerId)
        assertEquals(BookingProviderRef.RecGov(TEST_RECGOV_PARENT_ID), target?.parentRef)
        assertEquals(CatalogCampsiteRef(TEST_CAMPSITE_ID, TEST_RECGOV_SITE_ID), target?.campsiteRef)
    }

    @Test
    fun `targetFor returns null when no candidate maps to supported booking provider`() {
        val registry = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider()))
        val resolver = AvailabilityBookingTargetResolver(registry)

        val target = resolver.targetFor(BookingAction.ADD_TO_CART, resolvedTarget(candidates = listOf(campflareCandidate())))

        assertNull(target)
    }

    private class RecGovOnlyBookingProvider : BookingAdapter {
        override val id: BookingProvider = BookingProvider.RECGOV

        override fun targetFor(
            parentRef: BookingProviderRef,
            campsiteRef: CatalogCampsiteRef,
        ): BookingTarget? {
            if (parentRef !is BookingProviderRef.RecGov) return null
            return BookingTarget(
                providerId = id,
                parentRef = parentRef,
                campsiteRef = campsiteRef,
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

        override suspend fun availability(
            ref: BookingProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private fun resolvedTarget(candidates: List<ProviderCandidate>): ResolvedAvailabilityTarget {
        val head = candidates.first()
        return ResolvedAvailabilityTarget(
            campsite = campsite(),
            provider = head.provider,
            parentRef = head.parentRef,
            catalogRef = head.catalogRef,
            parentPoiId = 100L,
            dateContext = PoiDateContext(ZoneId.of("UTC"), LocalDate.parse("2026-07-01")),
            candidates = candidates,
        )
    }

    private fun campflareCandidate(): ProviderCandidate =
        ProviderCandidate(
            provider = FakeAvailabilityProvider(BookingProvider.CAMPFLARE),
            parentRef = BookingProviderRef.Campflare(TEST_CAMPFLARE_PARENT_ID),
            catalogRef = CatalogCampsiteRef(TEST_CAMPSITE_ID, TEST_CAMPFLARE_PARENT_ID),
        )

    private fun recgovCandidate(): ProviderCandidate =
        ProviderCandidate(
            provider = FakeAvailabilityProvider(BookingProvider.RECGOV),
            parentRef = BookingProviderRef.RecGov(facilityId = TEST_RECGOV_PARENT_ID),
            catalogRef = CatalogCampsiteRef(TEST_CAMPSITE_ID, TEST_RECGOV_SITE_ID),
        )

    private fun campsite(): CampsiteAvailabilityTarget =
        CampsiteAvailabilityTarget(
            id = TEST_CAMPSITE_ID,
            vendor = "campflare",
            vendorId = TEST_CAMPFLARE_PARENT_ID,
            name = "Site 7",
            loop = null,
            siteType = null,
            raw = null,
        )
}
