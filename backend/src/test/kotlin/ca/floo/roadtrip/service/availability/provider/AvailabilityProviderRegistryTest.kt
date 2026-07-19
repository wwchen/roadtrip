package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class AvailabilityProviderRegistryTest {
    private class FakeProvider(
        override val id: BookingProvider,
        private val enabled: Boolean,
    ) : AvailabilityProvider {
        override val capabilities: AvailabilityProviderCapabilities = AvailabilityProviderCapabilities.unsupported

        override fun isEnabled(): Boolean = enabled

        override suspend fun availability(
            ref: BookingProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = error("not used in this test")
    }

    @Test
    fun `forSource resolves source to its adapter instance`() {
        val recgov = FakeProvider(BookingProvider.RECGOV, enabled = true)
        val aspiraPc = FakeProvider(BookingProvider.ASPIRA, enabled = true)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource =
                    mapOf(
                        "recgov-campgrounds" to recgov,
                        "aspira_pc" to aspiraPc,
                    ),
            )

        val resolved = registry.forSource("recgov-campgrounds")
        assertNotNull(resolved)
        assertEquals(BookingProvider.RECGOV, resolved.id)

        val pc = registry.forSource("aspira_pc")
        assertNotNull(pc)
        assertEquals(BookingProvider.ASPIRA, pc.id)
    }

    @Test
    fun `forSource returns null for unmapped source`() {
        val registry = AvailabilityProviderRegistry(adaptersBySource = emptyMap())
        assertNull(registry.forSource("never-registered"))
    }

    @Test
    fun `forBooking returns null when mapped provider declines the ref`() {
        val declining =
            object : AvailabilityProvider {
                override val id: BookingProvider = BookingProvider.CAMPFLARE
                override val capabilities: AvailabilityProviderCapabilities = AvailabilityProviderCapabilities.unsupported

                override fun isEnabled(): Boolean = true

                override fun supportsRef(ref: BookingProviderRef): Boolean = false

                override suspend fun availability(
                    ref: BookingProviderRef,
                    startDate: LocalDate,
                    endDate: LocalDate,
                ): AvailabilityObservationBatch = error("not used in this test")
            }
        val registry = AvailabilityProviderRegistry(adaptersBySource = mapOf("campflare" to declining))

        assertSame(declining, registry.forSource("campflare"))
        assertNull(registry.forBooking(BookingProvider.CAMPFLARE, BookingProviderRef.Campflare("upper-pines-campground-447")))
    }


    @Test
    fun `multiple sources can share one adapter instance`() {
        val recgov = FakeProvider(BookingProvider.RECGOV, enabled = true)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource =
                    mapOf(
                        "recgov-campgrounds" to recgov,
                        "another-recgov-source" to recgov,
                    ),
            )
        assertSame(recgov, registry.forSource("recgov-campgrounds"))
        assertSame(recgov, registry.forSource("another-recgov-source"))
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `disabled providers are hidden from lookup helpers`() {
        val recgov = FakeProvider(BookingProvider.RECGOV, enabled = true)
        val campflare = FakeProvider(BookingProvider.CAMPFLARE, enabled = false)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource =
                    mapOf(
                        "recgov-campgrounds" to recgov,
                        "campflare-campgrounds" to campflare,
                        "campflare" to campflare,
                    ),
            )

        assertSame(recgov, registry.forSource("recgov-campgrounds"))
        assertNull(registry.forSource("campflare-campgrounds"))
        assertNull(registry.forBooking(BookingProvider.CAMPFLARE, BookingProviderRef.Campflare("upper-pines-campground-447")))
        assertSame(recgov, registry.firstByVendor(BookingProvider.RECGOV))
        assertNull(registry.firstByVendor(BookingProvider.CAMPFLARE))
        assertEquals(listOf(BookingProvider.RECGOV), registry.all().map { it.id })
    }

    @Test
    fun `multiple Aspira tenants share an id but have distinct instances`() {
        val pc = FakeProvider(BookingProvider.ASPIRA, enabled = true)
        val bc = FakeProvider(BookingProvider.ASPIRA, enabled = true)
        val wa = FakeProvider(BookingProvider.ASPIRA, enabled = true)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource =
                    mapOf(
                        "aspira_pc" to pc,
                        "aspira_bc" to bc,
                        "aspira_wa" to wa,
                    ),
            )
        assertSame(pc, registry.forSource("aspira_pc"))
        assertSame(bc, registry.forSource("aspira_bc"))
        assertSame(wa, registry.forSource("aspira_wa"))
        assertEquals(3, registry.all().size)
    }
}
