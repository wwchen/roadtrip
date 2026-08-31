package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.FAKE_PROVIDER_YEAR_HORIZON_DAYS
import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.fixtures.campsiteFixture
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
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.service.availability.provider.testCampground
import ca.floo.roadtrip.service.booking.BookingAdapter
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TEST_PARENT_POI_ID = 100L

class WatchCapabilityServiceTest {
    @Test
    fun `supports add to cart when every scoped campsite resolves to a supported booking target`() {
        val campsiteA = campsite(1L, "site-1")
        val campsiteB = campsite(2L, "site-2")
        val service = service(campsiteA, campsiteB)

        val support = service.bookingSupportFor(BookingAction.ADD_TO_CART, listOf(campsiteA, campsiteB))

        assertTrue(support.supported)
        assertEquals(2, support.scopedCount)
        assertEquals(0, support.unsupportedCount)
        assertEquals(setOf(BookingAction.ADD_TO_CART), service.supportedBookingActions(listOf(campsiteA, campsiteB)))
        assertEquals(
            listOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.EMAIL_NOTIFY, AvailabilityTriggerKinds.ATC),
            service.supportedTriggerKinds(listOf(campsiteA, campsiteB)),
        )
    }

    @Test
    fun `does not support add to cart when any scoped campsite is unsupported`() {
        val supported = campsite(1L, "site-1")
        val unsupported = campsite(2L, "")
        val service = service(supported, unsupported)

        val support = service.bookingSupportFor(BookingAction.ADD_TO_CART, listOf(supported, unsupported))

        assertFalse(support.supported)
        assertEquals(2, support.scopedCount)
        assertEquals(1, support.unsupportedCount)
        assertEquals(emptySet(), service.supportedBookingActions(listOf(supported, unsupported)))
        assertEquals(
            listOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.EMAIL_NOTIFY),
            service.supportedTriggerKinds(listOf(supported, unsupported)),
        )
    }

    @Test
    fun `empty scope does not support add to cart`() {
        val service = service()

        val support = service.bookingSupportFor(BookingAction.ADD_TO_CART, emptyList())

        assertFalse(support.supported)
        assertEquals(0, support.scopedCount)
        assertEquals(0, support.unsupportedCount)
        assertFalse(service.internalPollingSupportFor(emptyList()).supported)
        assertEquals(emptyList(), service.supportedTriggerKinds(emptyList()))
    }

    @Test
    fun `does not support watch triggers when provider cannot be internally polled`() {
        val campsite = campsite(1L, "site-1")
        val service = service(campsites = listOf(campsite), supportsInternalPolling = false)

        val support = service.internalPollingSupportFor(listOf(campsite))

        assertFalse(support.supported)
        assertEquals(1, support.scopedCount)
        assertEquals(1, support.unsupportedCount)
        assertEquals(setOf(BookingAction.ADD_TO_CART), service.supportedBookingActions(listOf(campsite)))
        assertEquals(emptyList(), service.supportedTriggerKinds(listOf(campsite)))
    }

    @Test
    fun `supported trigger kinds use configured notification trigger kinds`() {
        val campsite = campsite(1L, "site-1")
        val service =
            service(
                campsites = listOf(campsite),
                notificationTriggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY),
            )

        assertEquals(
            listOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.ATC),
            service.supportedTriggerKinds(listOf(campsite)),
        )
    }

    private fun service(vararg campsites: Campsite): WatchCapabilityService = service(campsites = campsites.toList())

    private fun service(
        campsites: List<Campsite>,
        supportsInternalPolling: Boolean = true,
        notificationTriggerKinds: List<String> =
            listOf(
                AvailabilityTriggerKinds.SLACK_NOTIFY,
                AvailabilityTriggerKinds.EMAIL_NOTIFY,
            ),
    ): WatchCapabilityService {
        val registry = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider))
        return WatchCapabilityService(
            availabilityTargets = FakeTargetResolver(campsites, supportsInternalPolling),
            bookingTargets = AvailabilityBookingTargetResolver(registry),
            notificationTriggerKinds = notificationTriggerKinds,
        )
    }

    private object RecGovOnlyBookingProvider : BookingAdapter {
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
                target.parentRef is BookingProviderRef.RecGov &&
                target.vendorSiteId.isNotBlank()

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult = AddToCartResult.Unsupported
    }

    private fun fakeCampground(): Campground =
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
            dataProviderRef = DataProviderRef.RecGov(id = "facility-1"),
            bookingProvider = "recgov",
            bookingProviderRef = "facility-1",
        )

    private class FakeTargetResolver(
        private val campsites: List<Campsite>,
        supportsInternalPolling: Boolean,
    ) : AvailabilityTargetResolver {
        private val byId = campsites.associateBy { it.id }
        private val provider =
            FakeAvailabilityProvider(
                id = BookingProvider.RECGOV,
                supportsInternalPolling = supportsInternalPolling,
                bookingHorizonDays = FAKE_PROVIDER_YEAR_HORIZON_DAYS,
            )
        private val campground = testCampground(bookingProvider = "recgov", bookingProviderRef = "facility-1")

        override fun resolve(campsite: Campsite): ResolvedAvailabilityTarget? {
            val known = byId[campsite.id] ?: return null
            return ResolvedAvailabilityTarget(
                campsite = known,
                provider = provider,
                campground = campground,
                parentPoiId = TEST_PARENT_POI_ID,
                dateContext = PoiDateContext(ZoneId.of("UTC"), LocalDate.parse("2026-07-01")),
            )
        }

        override fun resolve(poller: AvailabilityPollerRepo.Poller): PollerFetchPlan? {
            // Unused by WatchCapabilityService tests
            throw UnsupportedOperationException("resolve(poller) not implemented in test fake")
        }
    }

    private fun campsite(
        id: Long,
        vendorId: String,
    ): Campsite =
        campsiteFixture(
            id = id,
            vendor = "recgov",
            vendorId = vendorId,
            name = "Site $id",
            loopName = null,
            kind = null,
            sourcePayload = null,
        )
}
