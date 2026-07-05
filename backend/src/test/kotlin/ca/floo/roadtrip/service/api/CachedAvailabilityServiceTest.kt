package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.SharedDbTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

class CachedAvailabilityServiceTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
    }

    @Test
    fun `vendor omissions record unknown cells for every known target date`() =
        runBlocking {
            val startDate = LocalDate.parse("2026-07-01")
            val endDate = LocalDate.parse("2026-07-03")
            val dayOneObservedAt = Instant.parse("2026-06-18T10:00:00Z")
            val dayTwoObservedAt = Instant.parse("2026-06-18T10:01:00Z")
            val seen = seedReservable("100")
            val omitted = seedReservable("200")
            val repo = AvailabilityRepo(ctx)
            val service =
                CachedAvailabilityService(
                    availability = repo,
                    clock = Clock.fixed(Instant.parse("2026-06-18T12:00:00Z"), ZoneOffset.UTC),
                )

            val batch =
                service.loadOrFetch(
                    request(seen, omitted, startDate, endDate),
                ) {
                    fetchedBatch(startDate, endDate, dayOneObservedAt, dayTwoObservedAt)
                }

            val byPair = batch.observations.associateBy { it.reservableId to it.date }
            assertEquals(4, byPair.size)
            assertEquals(AvailabilityStatus.AVAILABLE, byPair["site:recgov:100" to startDate]!!.status)
            assertEquals(AvailabilityStatus.RESERVED, byPair["site:recgov:100" to startDate.plusDays(1)]!!.status)
            assertEquals(AvailabilityStatus.UNKNOWN, byPair["site:recgov:200" to startDate]!!.status)
            assertEquals(AvailabilityStatus.UNKNOWN, byPair["site:recgov:200" to startDate.plusDays(1)]!!.status)
            assertEquals(dayOneObservedAt, byPair["site:recgov:200" to startDate]!!.observedAt)
            assertEquals(dayTwoObservedAt, byPair["site:recgov:200" to startDate.plusDays(1)]!!.observedAt)

            // Latest state is served from the interval table (current row per cell).
            val persisted =
                repo
                    .readCurrent(listOf(seen, omitted), listOf(startDate, startDate.plusDays(1)))
                    .associateBy { it.reservableId to it.targetDate }
            assertEquals(4, persisted.size)
            assertEquals(AvailabilityStatus.UNKNOWN, persisted[omitted to startDate]!!.status)
            assertEquals(AvailabilityStatus.UNKNOWN, persisted[omitted to startDate.plusDays(1)]!!.status)
        }

    @Test
    fun `an unchanged refetch bumps in place and adds no new interval rows`() =
        runBlocking {
            val startDate = LocalDate.parse("2026-07-01")
            val endDate = LocalDate.parse("2026-07-03")
            val dayOneObservedAt = Instant.parse("2026-06-18T10:00:00Z")
            val dayTwoObservedAt = Instant.parse("2026-06-18T10:01:00Z")
            val seen = seedReservable("100")
            val omitted = seedReservable("200")
            val repo = AvailabilityRepo(ctx)
            val service =
                CachedAvailabilityService(
                    availability = repo,
                    clock = Clock.fixed(Instant.parse("2026-06-18T12:00:00Z"), ZoneOffset.UTC),
                )

            // First fetch: 4 cells transition from absent → status, so 4 interval rows.
            service.loadOrFetch(request(seen, omitted, startDate, endDate)) {
                fetchedBatch(startDate, endDate, dayOneObservedAt, dayTwoObservedAt)
            }
            assertEquals(4, availabilityRowCount())

            // Identical refetch: no status changed, so each cell bumps in place —
            // NO new interval rows. (The pre-cube path appended a full cell-set on
            // every call, which is why the old snapshot table grew unbounded.)
            service.loadOrFetch(request(seen, omitted, startDate, endDate)) {
                fetchedBatch(startDate, endDate, dayOneObservedAt, dayTwoObservedAt)
            }
            assertEquals(4, availabilityRowCount())
        }

    private fun request(
        seen: Long,
        omitted: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ) = CachedAvailabilityService.Request(
        metadata = CachedAvailabilityService.Metadata(provider = "recgov", campgroundId = "232447"),
        targets =
            listOf(
                CachedAvailabilityService.TargetReservable(seen, "site:recgov:100"),
                CachedAvailabilityService.TargetReservable(omitted, "site:recgov:200"),
            ),
        startDate = startDate,
        endDate = endDate,
        ttl = Duration.ofMinutes(10),
    )

    private fun fetchedBatch(
        startDate: LocalDate,
        endDate: LocalDate,
        dayOneObservedAt: Instant,
        dayTwoObservedAt: Instant,
    ) = AvailabilityObservationBatch(
        provider = "recgov",
        startDate = startDate,
        endDate = endDate,
        observations =
            listOf(
                ReservableDayObservation("site:recgov:100", startDate, dayOneObservedAt, AvailabilityStatus.AVAILABLE),
                ReservableDayObservation("site:recgov:100", startDate.plusDays(1), dayTwoObservedAt, AvailabilityStatus.RESERVED),
            ),
        cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 600),
        campgroundId = "232447",
    )

    private fun availabilityRowCount(): Int = ctx.fetchOne("SELECT count(*) AS c FROM availability")!!.get("c", Int::class.java)

    private fun seedReservable(vendorId: String): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name
                ) VALUES (
                    'site', 'recgov', ?, 'federal-campsites', 'site'
                ) RETURNING id
                """.trimIndent(),
                vendorId,
            )!!
            .get("id", Long::class.java)
}
