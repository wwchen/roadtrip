package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.fixtures.FAKE_CART_URL
import ca.floo.roadtrip.fixtures.FAKE_PROVIDER_YEAR_HORIZON_DAYS
import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.fixtures.FakeBookingAdapter
import ca.floo.roadtrip.fixtures.RECGOV_DISPLAY_NAME
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingFailureCategory
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val caller = UserId(7L)
private const val TEST_CAMPSITE_ID = 42L

/** An Aspira ref whose tenant the shipped registry names differently from the vendor. */
private const val BC_TENANT_CODE = "bc"
private const val BC_TENANT_DISPLAY_NAME = "BC Parks"

private const val ADAPTER_FAILURE_CODE = "provider_busy"
private const val ADAPTER_FAILURE_DETAIL = "another operation holds this profile"
private val arrival: LocalDate = LocalDate.parse("2026-07-04")
private val checkout: LocalDate = LocalDate.parse("2026-07-06")

private val bcParksRef =
    BookingProviderRef.Aspira(
        tenant = BC_TENANT_CODE,
        transactionLocationId = 1L,
        mapId = 2L,
        resourceLocationId = null,
    )

class BookingActionServiceTest {
    @Test
    fun `a held site answers with the cart to finish in`() =
        runBlocking {
            val adapter = FakeBookingAdapter()
            val outcome = service(adapter = adapter).addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            // The cart the ADAPTER named, and the name the REGISTRY did. The
            // service knows no vendor's URL and no vendor's name.
            assertEquals(
                AddToCartOutcome.Held(FAKE_CART_URL, BookingProvider.RECGOV, RECGOV_DISPLAY_NAME),
                outcome,
            )
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
    fun `the held name follows the target's ref, not the adapter's vendor`() =
        runBlocking {
            // `bc` is an Aspira tenant the shipped registry names BC Parks. A
            // name taken off the adapter would read the vendor, Aspira NextGen.
            val adapter = FakeBookingAdapter(id = BookingProvider.ASPIRA)
            val outcome =
                service(adapter = adapter, parentRef = bcParksRef)
                    .addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            assertEquals(
                AddToCartOutcome.Held(FAKE_CART_URL, BookingProvider.ASPIRA, BC_TENANT_DISPLAY_NAME),
                outcome,
            )
        }

    @Test
    fun `a scope no adapter can book is refused before anything else is checked`() =
        runBlocking {
            // Campflare parent ref, rec.gov-only registry: nothing to book with.
            val adapter = FakeBookingAdapter()
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
    fun `a caller the adapter cannot fulfil for is refused before the browser`() =
        runBlocking {
            // The adapter answers for its own credentials; the service asks it.
            val adapter = FakeBookingAdapter(credentialed = { false })
            val outcome = service(adapter = adapter).addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            assertEquals(
                AddToCartOutcome.Refused(BookingActionCodes.CREDENTIALS_REQUIRED, BookingProvider.RECGOV, RECGOV_DISPLAY_NAME),
                outcome,
            )
            assertTrue(adapter.requests.isEmpty(), "no cart to hold it in, so no browser is driven")
        }

    @Test
    fun `a site we recently saw taken is refused without a vendor call`() =
        runBlocking {
            val adapter = FakeBookingAdapter()
            val outcome =
                service(adapter = adapter, freshlyUnavailableNights = setOf(arrival.plusDays(1)))
                    .addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            // Positive, recent evidence the second night is booked.
            assertEquals(
                AddToCartOutcome.Refused(BookingActionCodes.NOT_AVAILABLE, BookingProvider.RECGOV, RECGOV_DISPLAY_NAME),
                outcome,
            )
            assertTrue(adapter.requests.isEmpty())
        }

    @Test
    fun `a night never observed proceeds to the vendor`() =
        runBlocking {
            // The availability table is filled by the WATCH poller, so a site
            // nobody watches has no rows at all — and no evidence is not
            // evidence of absence. Refusing there broke browse-then-hold.
            val adapter = FakeBookingAdapter()
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
            val adapter = FakeBookingAdapter()
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
    fun `the adapter's own failure code reaches the caller unchanged`() =
        runBlocking {
            val adapter =
                FakeBookingAdapter(
                    result = {
                        AddToCartResult.Failed(
                            providerId = BookingProvider.RECGOV,
                            error = ADAPTER_FAILURE_CODE,
                            detail = ADAPTER_FAILURE_DETAIL,
                            category = BookingFailureCategory.RETRY_LATER,
                            request = buildJsonObject { },
                            response = null,
                        )
                    },
                )

            val outcome = service(adapter = adapter).addToCart(caller, TEST_CAMPSITE_ID, arrival, checkout)

            assertEquals(
                AddToCartOutcome.Failed(
                    ADAPTER_FAILURE_CODE,
                    ADAPTER_FAILURE_DETAIL,
                    BookingFailureCategory.RETRY_LATER,
                    BookingProvider.RECGOV,
                    RECGOV_DISPLAY_NAME,
                ),
                outcome,
            )
        }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun service(
        adapter: FakeBookingAdapter = FakeBookingAdapter(),
        campsite: Campsite? = campsite(),
        parentRef: BookingProviderRef = BookingProviderRef.RecGov("232447"),
        freshlyUnavailableNights: Set<LocalDate> = emptySet(),
    ): BookingActionService {
        val registry = BookingAdapterRegistry(listOf(adapter))
        return BookingActionService(
            campsites = { campsite },
            availabilityTargets = FakeTargetResolver(campsite, parentRef),
            bookingTargets = AvailabilityBookingTargetResolver(registry),
            availability = { _, nights -> nights.filter { it in freshlyUnavailableNights }.toSet() },
            bookings = registry,
            tenants = shippedTenantRegistry(),
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
}
