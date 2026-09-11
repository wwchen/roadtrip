package ca.floo.roadtrip.model.metadata.registry

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TenantRegistryTest {
    private val registry = TenantRegistry.from(PoiRegistry.loadResource("poi-registry.yaml"))

    @Test
    fun `displayName names the tenant for a known aspira tenant`() {
        assertEquals("BC Parks", registry.displayName(aspiraRef("bc")))
        assertEquals("Parks Canada", registry.displayName(aspiraRef("pc")))
        assertEquals("Washington State Parks", registry.displayName(aspiraRef("wa")))
    }

    @Test
    fun `displayName falls back to the vendor for an unknown tenant`() {
        assertEquals("Aspira NextGen", registry.displayName(aspiraRef("zz")))
        assertEquals("Aspira NextGen", registry.displayName(aspiraRef(null)))
        assertEquals(
            "ReserveAmerica",
            registry.displayName(BookingProviderRef.ReserveAmerica(contractCode = "ZZ", parkId = "1")),
        )
    }

    @Test
    fun `displayName on a single-tenant vendor is the vendor name`() {
        assertEquals("Recreation.gov", registry.displayName(BookingProviderRef.RecGov(facilityId = "232450")))
        assertEquals("Campflare", registry.displayName(BookingProviderRef.Campflare(campgroundId = "abc")))
        assertEquals(
            "ReserveCalifornia",
            registry.displayName(BookingProviderRef.ReserveCalifornia(placeId = 660, facilityIds = listOf(901))),
        )
    }

    @Test
    fun `displayName names the reserveamerica contract`() {
        assertEquals(
            "Alberta Parks",
            registry.displayName(BookingProviderRef.ReserveAmerica(contractCode = "ABPP", parkId = "10")),
        )
        assertEquals(
            "New York State Parks",
            registry.displayName(BookingProviderRef.ReserveAmerica(contractCode = "NY", parkId = "10")),
        )
    }

    @Test
    fun `ctaLabel picks the verb from sells`() {
        assertEquals("Reserve on BC Parks", registry.ctaLabel(aspiraRef("bc")))
        assertEquals("Reserve on Recreation.gov", registry.ctaLabel(BookingProviderRef.RecGov(facilityId = "1")))
        assertEquals("View on Campflare", registry.ctaLabel(BookingProviderRef.Campflare(campgroundId = "1")))
    }

    @Test
    fun `linkLabel answers by host with the viewing verb, ignoring www and case`() {
        // It labels an info link, so the verb is the kind of link, not the
        // vendor's `sells` flag — that stays with ctaLabel.
        assertEquals("View on Recreation.gov", registry.linkLabel("WWW.Recreation.gov"))
        assertEquals("View on Recreation.gov", registry.linkLabel("recreation.gov"))
        assertEquals("View on Campflare", registry.linkLabel("campflare.com"))
        assertEquals("View on Washington State Parks", registry.linkLabel("washington.goingtocamp.com"))
        assertNull(registry.linkLabel("www.fs.usda.gov"))
        assertNull(registry.linkLabel("bcparks.ca"))
    }

    @Test
    fun `tenantsOf returns the vendor rows with resolved names`() {
        assertEquals(
            listOf("pc" to "Parks Canada", "bc" to "BC Parks", "wa" to "Washington State Parks"),
            registry.tenantsOf(BookingProvider.ASPIRA).map { it.code to it.displayName },
        )
        assertEquals(
            listOf(null to "Recreation.gov"),
            registry.tenantsOf(BookingProvider.RECGOV).map { it.code to it.displayName },
        )
    }

    @Test
    fun `sells is a registry fact`() {
        assertEquals(false, registry.sells(BookingProvider.CAMPFLARE))
        assertEquals(true, registry.sells(BookingProvider.RECGOV))
    }

    /**
     * `from` builds a map keyed by vendor, so a duplicated row would otherwise
     * be last-write-wins in silence for any caller that skips [PoiRegistry].
     */
    @Test
    fun `from rejects a duplicated vendor row`() {
        val entries = PoiRegistry.loadResource("poi-registry.yaml").bookingProviders
        val err = assertFailsWith<IllegalArgumentException> { TenantRegistry.from(entries + entries.first()) }
        assertTrue(err.message!!.contains("duplicate rows for '${entries.first().id.id}'"), err.message)
    }

    private fun aspiraRef(tenant: String?) =
        BookingProviderRef.Aspira(
            tenant = tenant,
            transactionLocationId = 4189,
            mapId = -2147483361,
            resourceLocationId = null,
        )
}
