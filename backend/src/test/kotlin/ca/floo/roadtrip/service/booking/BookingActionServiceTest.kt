package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.fixtures.FAKE_PROVIDER_YEAR_HORIZON_DAYS
import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.api.RECGOV_CART_URL
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingFailureCategory
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.PollerFetchPlan
import ca.floo.roadtrip.service.availability.ResolvedAvailabilityTarget
import ca.floo.roadtrip.service.availability.provider.testCampground
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val caller = UserId(7L)
private const val TEST_CAMPSITE_ID = 42L
private val arrival: LocalDate = LocalDate.parse("2026-07-04")
private val checkout: LocalDate = LocalDate.parse("2026-07-06")

class BookingActionServiceTest {
    @Test
    fun `a held site answers with the cart to finish in`() =
        runBlocking {
            val adapter = RecordingAdapter()
            val outcome = service(adapter = adapter).addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            assertEquals(AddToCartOutcome.Held(RECGOV_CART_URL), outcome)
            val request = adapter.requests.single()
            // The hold lands in the CALLER's cart, and no watch fired it.
            assertEquals(caller.value, request.ownerUserId)
            // The caller is waiting; a re-login they cannot complete is worse
            // than a fast, honest "your session expired".
            assertEquals(false, request.allowUnattendedRelogin)
            assertEquals(arrival, request.arrivalDate)
            assertEquals(checkout, request.checkoutDate)
            assertTrue(!request.stopWhenTriggered)
        }

    @Test
    fun `a scope no adapter can book is refused before anything else is checked`() =
        runBlocking {
            // Campflare parent ref, rec.gov-only registry: nothing to book with.
            val adapter = RecordingAdapter()
            val outcome =
                service(adapter = adapter, parentRef = BookingProviderRef.Campflare("cf-1"))
                    .addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            assertEquals(AddToCartOutcome.Refused(BookingActionCodes.UNSUPPORTED_TARGET), outcome)
            assertTrue(adapter.requests.isEmpty())
        }

    @Test
    fun `an unknown campsite is unsupported, not a crash`() =
        runBlocking {
            val outcome = service(campsite = null).addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            assertEquals(AddToCartOutcome.Refused(BookingActionCodes.UNSUPPORTED_TARGET), outcome)
        }

    @Test
    fun `a caller with no rec_gov credentials is refused before the browser`() =
        runBlocking {
            val adapter = RecordingAdapter()
            val outcome = service(adapter = adapter, configured = false).addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            assertEquals(AddToCartOutcome.Refused(BookingActionCodes.CREDENTIALS_REQUIRED), outcome)
            assertTrue(adapter.requests.isEmpty(), "no cart to hold it in, so no browser is driven")
        }

    @Test
    fun `a site we recently saw taken is refused without a vendor call`() =
        runBlocking {
            val adapter = RecordingAdapter()
            val outcome =
                service(adapter = adapter, freshlyUnavailableNights = setOf(arrival.plusDays(1)))
                    .addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            // Positive, recent evidence the second night is booked.
            assertEquals(AddToCartOutcome.Refused(BookingActionCodes.NOT_AVAILABLE), outcome)
            assertTrue(adapter.requests.isEmpty())
        }

    @Test
    fun `a night never observed proceeds to the vendor`() =
        runBlocking {
            // The availability table is filled by the WATCH poller, so a site
            // nobody watches has no rows at all. Refusing on that made the grid
            // button unusable for exactly the browse-then-hold flow it exists
            // for. No evidence is not evidence of absence.
            val adapter = RecordingAdapter()
            val outcome =
                service(adapter = adapter, freshlyUnavailableNights = emptySet())
                    .addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            assertTrue(outcome is AddToCartOutcome.Held, "the vendor is the arbiter, so it must be asked")
            assertEquals(1, adapter.requests.size)
        }

    @Test
    fun `a stale observation does not block the hold`() =
        runBlocking {
            // The live bug: a bookable site was refused as "not available"
            // because its AVAILABLE observation was eight minutes old. A stale
            // cell contributes nothing to the blocking set by construction.
            val adapter = RecordingAdapter()
            val outcome =
                service(adapter = adapter, freshlyUnavailableNights = emptySet())
                    .addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            assertTrue(outcome is AddToCartOutcome.Held)
            assertEquals(1, adapter.requests.size)
        }

    @Test
    fun `an empty window is refused rather than held`() =
        runBlocking {
            val outcome = service().addToCart(caller, TEST_CAMPSITE_ID, arrival, arrival)

            assertEquals(AddToCartOutcome.Refused(BookingActionCodes.INVALID_WINDOW), outcome)
        }

    @Test
    fun `the companion's own failure code reaches the caller unchanged`() =
        runBlocking {
            val adapter =
                RecordingAdapter(
                    result = { req ->
                        AddToCartResult.Failed(
                            providerId = BookingProvider.RECGOV,
                            error = RecGovSessionCodes.PROFILE_BUSY,
                            detail = "another operation holds this profile",
                            category = BookingFailureCategory.RETRY_LATER,
                            request = buildJsonObject { },
                            response = null,
                        )
                    },
                )

            val outcome = service(adapter = adapter).addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            assertEquals(
                AddToCartOutcome.Failed(
                    RecGovSessionCodes.PROFILE_BUSY,
                    "another operation holds this profile",
                    BookingFailureCategory.RETRY_LATER,
                ),
                outcome,
            )
        }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun service(
        adapter: RecordingAdapter = RecordingAdapter(),
        configured: Boolean = true,
        campsite: Campsite? = campsite(),
        parentRef: BookingProviderRef = BookingProviderRef.RecGov("232447"),
        freshlyUnavailableNights: Set<LocalDate> = emptySet(),
    ): BookingActionService {
        val registry = BookingAdapterRegistry(listOf(adapter))
        return BookingActionService(
            campsites = { campsite },
            availabilityTargets = FakeTargetResolver(campsite, parentRef),
            bookingTargets = AvailabilityBookingTargetResolver(registry),
            credentials = { configured },
            availability = { _, nights -> nights.filter { it in freshlyUnavailableNights }.toSet() },
            bookings = registry,
        )
    }

    private fun campsite(): Campsite =
        campsiteFixture(
            id = TEST_CAMPSITE_ID,
            vendor = "recgov",
            vendorId = "site-42",
            name = "Site 42",
            loopName = null,
            kind = null,
            sourcePayload = null,
        )

    private class FakeTargetResolver(
        private val campsite: Campsite?,
        private val parentRef: BookingProviderRef,
    ) : AvailabilityTargetResolver {
        override fun resolve(campsite: Campsite): ResolvedAvailabilityTarget? {
            val known = this.campsite ?: return null
            return ResolvedAvailabilityTarget(
                campsite = known,
                provider =
                    FakeAvailabilityProvider(
                        id = parentRef.provider,
                        bookingHorizonDays = FAKE_PROVIDER_YEAR_HORIZON_DAYS,
                        parentRefOverride = { parentRef },
                    ),
                campground = testCampground(bookingProvider = parentRef.provider.id, bookingProviderRef = "232447"),
                parentPoiId = 100L,
                dateContext = PoiDateContext(ZoneId.of("UTC"), LocalDate.parse("2026-07-01")),
            )
        }

        override fun resolve(poller: AvailabilityPollerRepo.Poller): PollerFetchPlan? = throw UnsupportedOperationException("unused")
    }

    private class RecordingAdapter(
        private val result: ((AddToCartRequest) -> AddToCartResult)? = null,
    ) : BookingAdapter {
        val requests = mutableListOf<AddToCartRequest>()

        override val id: BookingProvider = BookingProvider.RECGOV

        override fun targetFor(
            parentRef: BookingProviderRef,
            campsiteId: Long,
            vendorSiteId: String,
        ): BookingTarget? {
            if (parentRef !is BookingProviderRef.RecGov) return null
            return BookingTarget(id, parentRef, campsiteId, vendorSiteId)
        }

        override fun can(
            action: BookingAction,
            target: BookingTarget,
        ): Boolean = action == BookingAction.ADD_TO_CART && target.parentRef is BookingProviderRef.RecGov

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
            requests += request
            result?.let { return it(request) }
            return AddToCartResult.Completed(
                providerId = id,
                request = buildJsonObject { },
                response = buildJsonObject { },
            )
        }
    }
}
