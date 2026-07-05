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
import kotlin.test.assertTrue

class AvailabilityLoaderTest : SharedDbTest() {
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
                AvailabilityLoader(
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
                AvailabilityLoader(
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

    @Test
    fun `records the full fetched window even when it is wider than the requested window`() =
        runBlocking {
            // Requested (target) window is 1 day; the fetch returns 3 days.
            // Two targets: the provider reports `seen` across all 3 fetched days but
            // OMITS `omitted` entirely. The omitted target only reaches the wide
            // window via UNKNOWN-fill, whose range is the edited line — so the
            // persisted-cell count guards `recordFetched` using the batch window.
            val requestStart = LocalDate.parse("2026-07-01")
            val requestEnd = LocalDate.parse("2026-07-02")
            val fetchStart = LocalDate.parse("2026-07-01")
            val fetchEnd = LocalDate.parse("2026-07-04")
            val fetchDates = (0L until 3L).map { fetchStart.plusDays(it) }
            val observedAt = Instant.parse("2026-06-18T10:00:00Z")
            val seen = seedReservable("100")
            val omitted = seedReservable("200")
            val repo = AvailabilityRepo(ctx)
            val service =
                AvailabilityLoader(
                    availability = repo,
                    clock = Clock.fixed(Instant.parse("2026-06-18T12:00:00Z"), ZoneOffset.UTC),
                )

            service.loadOrFetch(
                AvailabilityLoader.Request(
                    metadata = AvailabilityLoader.Metadata(provider = "recgov", campgroundId = "232447"),
                    targets =
                        listOf(
                            AvailabilityLoader.TargetReservable(seen, "site:recgov:100"),
                            AvailabilityLoader.TargetReservable(omitted, "site:recgov:200"),
                        ),
                    startDate = requestStart,
                    endDate = requestEnd,
                    ttl = Duration.ofMinutes(10),
                ),
            ) {
                AvailabilityObservationBatch(
                    provider = "recgov",
                    startDate = fetchStart,
                    endDate = fetchEnd,
                    observations =
                        fetchDates.map {
                            ReservableDayObservation(
                                "site:recgov:100",
                                it,
                                observedAt,
                                AvailabilityStatus.AVAILABLE,
                            )
                        },
                    cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 600),
                    campgroundId = "232447",
                )
            }

            // Both targets are persisted across all 3 fetched days (6 cells), not just
            // the requested 1 day — the omitted target reaches days 2-3 only because
            // UNKNOWN-fill covers the fetched batch window.
            val persisted = repo.readCurrent(listOf(seen, omitted), fetchDates)
            assertEquals(6, persisted.size)
        }

    @Test
    fun `a later request inside the recorded wide window is served from the DB without fetching`() =
        runBlocking {
            val week1Start = LocalDate.parse("2026-07-01")
            val week1End = LocalDate.parse("2026-07-08")
            val wideEnd = LocalDate.parse("2026-07-15")
            val week2Start = LocalDate.parse("2026-07-08")
            val week2End = LocalDate.parse("2026-07-15")
            val observedAt = Instant.parse("2026-06-18T10:00:00Z")
            val seen = seedReservable("100")
            val omitted = seedReservable("200")
            val repo = AvailabilityRepo(ctx)
            val service =
                AvailabilityLoader(
                    availability = repo,
                    clock = Clock.fixed(Instant.parse("2026-06-18T12:00:00Z"), ZoneOffset.UTC),
                )

            fun req(
                start: LocalDate,
                end: LocalDate,
            ) = AvailabilityLoader.Request(
                metadata = AvailabilityLoader.Metadata(provider = "recgov", campgroundId = "232447"),
                targets =
                    listOf(
                        AvailabilityLoader.TargetReservable(seen, "site:recgov:100"),
                        AvailabilityLoader.TargetReservable(omitted, "site:recgov:200"),
                    ),
                startDate = start,
                endDate = end,
                ttl = Duration.ofHours(2),
            )

            // Week-1 request, but the fetch returns the wide [07-01, 07-15) window with
            // observations ONLY for site:recgov:100 — site:recgov:200 is omitted, so its
            // coverage across the wide window depends entirely on UNKNOWN-fill.
            service.loadOrFetch(req(week1Start, week1End)) {
                AvailabilityObservationBatch(
                    provider = "recgov",
                    startDate = week1Start,
                    endDate = wideEnd,
                    observations =
                        (0L until 14L).map {
                            ReservableDayObservation("site:recgov:100", week1Start.plusDays(it), observedAt, AvailabilityStatus.AVAILABLE)
                        },
                    cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 7200),
                    campgroundId = "232447",
                )
            }

            // Week-2 request is fully inside the recorded window → must NOT fetch.
            // This only holds if UNKNOWN-fill reached the wide window for the omitted
            // target; recording on the request window instead would leave week-2 of
            // site:recgov:200 uncovered and force a refetch.
            var week2Fetched = false
            val batch =
                service.loadOrFetch(req(week2Start, week2End)) {
                    week2Fetched = true
                    error("week 2 must be served from the DB, not fetched")
                }

            assertEquals(false, week2Fetched)
            assertEquals(true, batch.cacheBlock!!.hit)
            // Response is the week-2 slice for both targets (2 targets × 7 days),
            // not the whole wide window.
            assertEquals(14, batch.observations.size)
        }

    @Test
    fun `a repo-less fetch is sliced to the target window, not the wide fetched window`() =
        runBlocking {
            // No repo: loadOrFetch hands back the fetch result directly, but must still
            // return only the target window even when the composer fetches wider.
            val requestStart = LocalDate.parse("2026-07-01")
            val requestEnd = LocalDate.parse("2026-07-04")
            val wideEnd = LocalDate.parse("2026-08-30")
            val observedAt = Instant.parse("2026-06-18T10:00:00Z")
            val service = AvailabilityLoader(availability = null)

            val batch =
                service.loadOrFetch(
                    AvailabilityLoader.Request(
                        metadata = AvailabilityLoader.Metadata(provider = "recgov", campgroundId = "232447"),
                        targets = listOf(AvailabilityLoader.TargetReservable(1L, "site:recgov:100")),
                        startDate = requestStart,
                        endDate = requestEnd,
                        ttl = Duration.ofMinutes(10),
                    ),
                ) {
                    AvailabilityObservationBatch(
                        provider = "recgov",
                        startDate = requestStart,
                        endDate = wideEnd,
                        observations =
                            (0L until 60L).map {
                                ReservableDayObservation(
                                    "site:recgov:100",
                                    requestStart.plusDays(it),
                                    observedAt,
                                    AvailabilityStatus.AVAILABLE,
                                )
                            },
                        cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 600),
                        campgroundId = "232447",
                    )
                }

            assertEquals(requestStart, batch.startDate)
            assertEquals(requestEnd, batch.endDate)
            // Only the 3 target days [07-01, 07-04) survive the slice.
            assertEquals(3, batch.observations.size)
            assertTrue(batch.observations.all { it.date < requestEnd })
        }

    private fun request(
        seen: Long,
        omitted: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ) = AvailabilityLoader.Request(
        metadata = AvailabilityLoader.Metadata(provider = "recgov", campgroundId = "232447"),
        targets =
            listOf(
                AvailabilityLoader.TargetReservable(seen, "site:recgov:100"),
                AvailabilityLoader.TargetReservable(omitted, "site:recgov:200"),
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
