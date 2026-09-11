package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailability
import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.client.aspira.AspiraOccupancy
import ca.floo.roadtrip.client.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.provider.BookingAlias
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ca.floo.roadtrip.client.recgov.Campsite as RecGovCampsite

private const val CAMPFLARE_CAMPGROUND_ID = "upper-pines-campground-447"
private const val RECGOV_FACILITY_ID = "232447"
private const val CAMPFLARE_SITE_ID = "upper-pines-site-100"
private const val RECGOV_SITE_ID = "330257"
private const val ASPIRA_TENANT = "pc"
private const val ASPIRA_HOST = "reservation.pc.gc.ca"
private const val ASPIRA_MAP_ID = "456"
private const val ASPIRA_REF = "$ASPIRA_TENANT:111:$ASPIRA_MAP_ID:789"
private const val RESERVEAMERICA_CONTRACT = "ABPP"
private const val RESERVEAMERICA_HOST = "shop.albertaparks.ca"
private const val RESERVEAMERICA_PARK_ID = "24681"
private const val RESERVEAMERICA_REF = "$RESERVEAMERICA_CONTRACT:$RESERVEAMERICA_PARK_ID"

/**
 * The V59 shape: one Campflare-primary campground that rec.gov also sells,
 * claimed by both providers through one rule. The rec.gov `parentRefKey` is a
 * stored poller key, so it has to come out of the alias unchanged.
 */
class BookingAliasClaimTest {
    private val aliasedCampground: Campground =
        campground(bookingAliases = listOf(BookingAlias(provider = BookingProvider.RECGOV, ref = RECGOV_FACILITY_ID)))

    @Test
    fun `recgov claims a campflare-primary campground through its alias with the stored parent key`() {
        val provider = recgovProvider(enabled = true)

        assertTrue(provider.supportsCampground(aliasedCampground))
        assertEquals(RECGOV_FACILITY_ID, provider.parentRefFor(aliasedCampground)!!.parentRefKey)
        assertEquals(BookingProvider.RECGOV, provider.parentRefFor(aliasedCampground)!!.provider)
    }

    @Test
    fun `campflare claims the same row through its primary ref`() {
        val provider = campflareProvider(enabled = true)

        assertTrue(provider.supportsCampground(aliasedCampground))
        assertEquals(CAMPFLARE_CAMPGROUND_ID, provider.parentRefFor(aliasedCampground)!!.parentRefKey)
    }

    @Test
    fun `a disabled recgov leaves the row to campflare`() {
        val providers = listOf(recgovProvider(enabled = false), campflareProvider(enabled = true))

        assertEquals(
            BookingProvider.CAMPFLARE,
            providers.firstOrNull { it.supportsCampground(aliasedCampground) }?.id,
        )
    }

    @Test
    fun `a campflare row with no alias is claimed by campflare alone`() {
        val unaliased = campground(bookingAliases = emptyList())

        assertFalse(recgovProvider(enabled = true).supportsCampground(unaliased))
        assertNull(recgovProvider(enabled = true).parentRefFor(unaliased))
        assertTrue(campflareProvider(enabled = true).supportsCampground(unaliased))
    }

    @Test
    fun `vendor site ids come from the primary for campflare and the alias for recgov`() {
        val campsite =
            campsiteFixture(
                id = 100,
                vendor = "campflare",
                vendorId = CAMPFLARE_SITE_ID,
                bookingProvider = BookingProvider.CAMPFLARE.id,
                bookingProviderRef = CAMPFLARE_SITE_ID,
                bookingAliases = listOf(BookingAlias(provider = BookingProvider.RECGOV, ref = RECGOV_SITE_ID)),
            )

        assertEquals(RECGOV_SITE_ID, recgovProvider(enabled = true).vendorSiteIdFor(campsite))
        assertEquals(CAMPFLARE_SITE_ID, campflareProvider(enabled = true).vendorSiteIdFor(campsite))
    }

    @Test
    fun `recgov fetches the aliased facility and matches sites by their alias id`() =
        runBlocking {
            val client =
                object : RecGovAvailabilityClient {
                    override suspend fun fetchMonth(
                        campgroundId: String,
                        monthStart: String,
                    ): Map<String, RecGovCampsite> {
                        assertEquals(RECGOV_FACILITY_ID, campgroundId)
                        return mapOf(
                            RECGOV_SITE_ID to
                                RecGovCampsite(
                                    id = RECGOV_SITE_ID,
                                    site = "A12",
                                    loop = "A",
                                    campsiteType = "STANDARD",
                                    maxNumPeople = 6,
                                    equipmentTypes = emptyList(),
                                    availabilities = mapOf("2026-07-01" to "Available"),
                                ),
                        )
                    }
                }

            val batch =
                RecGovAvailabilityProvider(client, enabled = true).catalogAvailability(
                    campground = aliasedCampground,
                    campsites =
                        listOf(
                            campsiteFixture(
                                id = 100,
                                vendor = "campflare",
                                vendorId = CAMPFLARE_SITE_ID,
                                bookingProvider = BookingProvider.CAMPFLARE.id,
                                bookingProviderRef = CAMPFLARE_SITE_ID,
                                bookingAliases = listOf(BookingAlias(provider = BookingProvider.RECGOV, ref = RECGOV_SITE_ID)),
                            ),
                        ),
                    startDate = LocalDate.parse("2026-07-01"),
                    endDate = LocalDate.parse("2026-07-02"),
                )

            assertEquals(listOf(100L), batch.observations.map { it.campsiteId })
            assertEquals(listOf(AvailabilityStatus.AVAILABLE), batch.observations.map { it.status })
        }

    @Test
    fun `aspira fetches through the claimed alias ref instead of throwing WrongRefType`() =
        runBlocking {
            val aliased =
                campground(bookingAliases = listOf(BookingAlias(provider = BookingProvider.ASPIRA, ref = ASPIRA_REF)))
            val provider = aspiraProvider()

            assertEquals(ASPIRA_MAP_ID, provider.parentRefFor(aliased)!!.parentRefKey)
            assertEquals(BookingProvider.ASPIRA, provider.parentRefFor(aliased)!!.provider)

            // Throws if the fetch path still reads the (campflare) primary ref
            // instead of the claimed Aspira alias.
            val batch =
                provider.availability(
                    campground = aliased,
                    startDate = LocalDate.parse("2026-07-01"),
                    endDate = LocalDate.parse("2026-07-02"),
                )
            assertEquals(ASPIRA_MAP_ID, (batch.scope as BookingProviderRef.Aspira).mapId.toString())
        }

    @Test
    fun `reserveamerica fetches through the claimed alias ref instead of throwing WrongRefType`() =
        runBlocking {
            val aliased =
                campground(
                    bookingAliases = listOf(BookingAlias(provider = BookingProvider.RESERVEAMERICA, ref = RESERVEAMERICA_REF)),
                )
            val provider = reserveAmericaProvider()

            assertEquals(RESERVEAMERICA_PARK_ID, provider.parentRefFor(aliased)!!.parentRefKey)
            assertEquals(BookingProvider.RESERVEAMERICA, provider.parentRefFor(aliased)!!.provider)

            // Throws if the fetch path still reads the (campflare) primary ref
            // instead of the claimed ReserveAmerica alias.
            val batch =
                provider.availability(
                    campground = aliased,
                    startDate = LocalDate.parse("2026-07-01"),
                    endDate = LocalDate.parse("2026-07-02"),
                )
            assertEquals(RESERVEAMERICA_PARK_ID, (batch.scope as BookingProviderRef.ReserveAmerica).parkId)
        }

    private fun campground(bookingAliases: List<BookingAlias>): Campground =
        testCampground(
            bookingProvider = BookingProvider.CAMPFLARE.id,
            bookingProviderRef = CAMPFLARE_CAMPGROUND_ID,
            dataProviderRef = DataProviderRef.Campflare(id = CAMPFLARE_CAMPGROUND_ID),
            bookingAliases = bookingAliases,
        )

    private fun recgovProvider(enabled: Boolean): RecGovAvailabilityProvider =
        RecGovAvailabilityProvider(
            availabilityClient =
                object : RecGovAvailabilityClient {
                    override suspend fun fetchMonth(
                        campgroundId: String,
                        monthStart: String,
                    ): Map<String, RecGovCampsite> = emptyMap()
                },
            enabled = enabled,
        )

    private fun campflareProvider(enabled: Boolean): CampflareAvailabilityProvider =
        CampflareAvailabilityProvider(
            availabilityClient =
                CampflareAvailabilityClient { _, _, _ ->
                    CampflareAvailability(campgrounds = emptyMap(), observedAt = Instant.EPOCH)
                },
            enabled = enabled,
            configured = true,
        )

    private fun aspiraProvider(): AspiraAvailabilityProvider =
        AspiraAvailabilityProvider(
            tenants = shippedTenantRegistry().tenantsOf(BookingProvider.ASPIRA),
            availabilityClient =
                object : AspiraAvailabilityClient {
                    override suspend fun fetch(
                        host: String,
                        mapId: Int,
                        startDate: LocalDate,
                        endDate: LocalDate,
                    ): AspiraAvailability {
                        assertEquals(ASPIRA_HOST, host)
                        assertEquals(ASPIRA_MAP_ID.toInt(), mapId)
                        return AspiraAvailability(mapId = mapId, parkRollup = emptyList(), byMapLink = emptyMap())
                    }

                    override suspend fun fetchOccupancy(
                        host: String,
                        resourceLocationId: Int,
                        startDate: LocalDate,
                        endDate: LocalDate,
                    ): AspiraOccupancy = AspiraOccupancy(resourceLocationId = resourceLocationId, resourceOccupancy = emptyList())
                },
            enabled = true,
        )

    private fun reserveAmericaProvider(): ReserveAmericaAvailabilityProvider =
        ReserveAmericaAvailabilityProvider(
            tenants = shippedTenantRegistry().tenantsOf(BookingProvider.RESERVEAMERICA),
            availabilityClient =
                ReserveAmericaAvailabilityClient { host, contractCode, parkId, startDate, endDate ->
                    assertEquals(RESERVEAMERICA_HOST, host)
                    assertEquals(RESERVEAMERICA_CONTRACT, contractCode)
                    assertEquals(RESERVEAMERICA_PARK_ID, parkId)
                    ReserveAmericaAvailability(
                        contractCode = contractCode,
                        parkId = parkId,
                        startDate = startDate,
                        endDate = endDate,
                        observedAt = Instant.EPOCH,
                        statuses = emptyMap(),
                    )
                },
            enabled = true,
        )
}
