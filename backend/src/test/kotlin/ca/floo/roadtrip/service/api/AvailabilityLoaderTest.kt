package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCampsite
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

private val fixedNow: Instant = Instant.parse("2026-07-30T12:00:00Z")
private val snapshotTtl: Duration = Duration.ofMinutes(30)
private val windowStart: LocalDate = LocalDate.of(2026, 8, 1)
private val windowEnd: LocalDate = windowStart.plusDays(2)
private const val PROVIDER = "recgov"

class AvailabilityLoaderTest : SharedDbTest() {
    private val repo by lazy { AvailabilityRepo(ctx) }
    private val loader by lazy { AvailabilityLoader(repo, Clock.fixed(fixedNow, ZoneOffset.UTC)) }

    private var siteA = 0L
    private var siteB = 0L

    @BeforeEach
    fun seed() {
        ctx.cleanCanonicalCatalogFixtures()
        val campgroundId = ctx.seedCampground(name = "Loader CG", sourceId = "loader-cg")
        siteA = ctx.seedCampsite(campgroundId = campgroundId, vendorId = "loader-a")
        siteB = ctx.seedCampsite(campgroundId = campgroundId, vendorId = "loader-b")
    }

    private fun request(targets: List<Long> = listOf(siteA, siteB)) =
        AvailabilityLoader.Request(
            metadata = AvailabilityLoader.Metadata(provider = PROVIDER),
            targets = targets.map { AvailabilityLoader.CampsiteTarget(dbId = it) },
            startDate = windowStart,
            endDate = windowEnd,
            ttl = snapshotTtl,
        )

    private fun batch(observations: List<CampsiteDayObservation>) =
        AvailabilityObservationBatch(
            provider = PROVIDER,
            startDate = windowStart,
            endDate = windowEnd,
            observations = observations,
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = snapshotTtl.seconds),
        )

    private fun observation(
        campsiteId: Long,
        date: LocalDate,
        status: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
        observedAt: Instant = fixedNow,
    ) = CampsiteDayObservation(campsiteId = campsiteId, date = date, observedAt = observedAt, status = status)

    private fun windowDates(): List<LocalDate> = listOf(windowStart, windowStart.plusDays(1))

    private fun seedStoredWindow(
        status: AvailabilityStatus,
        observedAt: Instant,
    ) {
        repo.recordObservations(
            null,
            listOf(siteA, siteB).flatMap { site ->
                windowDates().map { date ->
                    AvailabilityRepo.Observation(site, date, status, observedAt)
                }
            },
        )
    }

    @Test
    fun `fresh full-coverage stored rows are served without fetching`() {
        seedStoredWindow(AvailabilityStatus.RESERVED, observedAt = fixedNow.minusSeconds(60))

        val result =
            runBlocking {
                loader.loadOrFetch(request()) { fail("fresh cache must not trigger a live fetch") }
            }

        assertTrue(result.cacheBlock.hit)
        assertEquals(snapshotTtl.seconds, result.cacheBlock.ttlSeconds)
        assertEquals(60, result.cacheBlock.ageSeconds)
        assertEquals(4, result.observations.size)
        assertTrue(result.observations.all { it.status == AvailabilityStatus.RESERVED })
        assertEquals(windowStart, result.startDate)
        assertEquals(windowEnd, result.endDate)
    }

    @Test
    fun `empty store fetches live and records the observations`() {
        var fetchCount = 0
        val result =
            runBlocking {
                loader.loadOrFetch(request()) {
                    fetchCount++
                    batch(
                        listOf(siteA, siteB).flatMap { site ->
                            windowDates().map { date -> observation(site, date) }
                        },
                    )
                }
            }

        assertEquals(1, fetchCount)
        assertFalse(result.cacheBlock.hit)
        assertEquals(4, result.observations.size)
        assertTrue(result.observations.all { it.status == AvailabilityStatus.AVAILABLE })

        val stored = repo.readCurrent(listOf(siteA, siteB), windowDates())
        assertEquals(4, stored.size)
        assertTrue(stored.all { it.status == AvailabilityStatus.AVAILABLE })
    }

    @Test
    fun `stale stored rows trigger a refetch and the fetched status wins`() {
        seedStoredWindow(AvailabilityStatus.RESERVED, observedAt = fixedNow.minus(snapshotTtl).minusSeconds(3600))

        var fetchCount = 0
        val result =
            runBlocking {
                loader.loadOrFetch(request()) {
                    fetchCount++
                    batch(
                        listOf(siteA, siteB).flatMap { site ->
                            windowDates().map { date -> observation(site, date) }
                        },
                    )
                }
            }

        assertEquals(1, fetchCount)
        assertFalse(result.cacheBlock.hit)
        assertTrue(result.observations.all { it.status == AvailabilityStatus.AVAILABLE })
        val stored = repo.readCurrent(listOf(siteA, siteB), windowDates())
        assertTrue(stored.all { it.status == AvailabilityStatus.AVAILABLE })
    }

    @Test
    fun `cells the fetch does not cover are backfilled as UNKNOWN`() {
        // The live fetch only reports siteA on the first day; the other three
        // (campsite, date) cells must be recorded as UNKNOWN so the window still
        // reaches full coverage instead of looking permanently stale.
        val result =
            runBlocking {
                loader.loadOrFetch(request()) {
                    batch(listOf(observation(siteA, windowStart)))
                }
            }

        val stored = repo.readCurrent(listOf(siteA, siteB), windowDates())
        assertEquals(4, stored.size)
        val byCell = stored.associate { (it.campsiteId to it.targetDate) to it.status }
        assertEquals(AvailabilityStatus.AVAILABLE, byCell[siteA to windowStart])
        assertEquals(AvailabilityStatus.UNKNOWN, byCell[siteA to windowStart.plusDays(1)])
        assertEquals(AvailabilityStatus.UNKNOWN, byCell[siteB to windowStart])
        assertEquals(AvailabilityStatus.UNKNOWN, byCell[siteB to windowStart.plusDays(1)])

        // Backfill completes coverage, so the response is served from the store.
        assertFalse(result.cacheBlock.hit)
        assertEquals(4, result.observations.size)
        assertEquals(
            1,
            result.observations.count { it.status == AvailabilityStatus.AVAILABLE },
        )
        assertEquals(
            3,
            result.observations.count { it.status == AvailabilityStatus.UNKNOWN },
        )
    }

    @Test
    fun `observations for campsites outside the target set are not persisted`() {
        val outsider =
            ctx.seedCampsite(
                campgroundId = ctx.seedCampground(name = "Other CG", sourceId = "loader-other"),
                vendorId = "loader-x",
            )

        runBlocking {
            loader.loadOrFetch(request(targets = listOf(siteA))) {
                batch(
                    listOf(
                        observation(siteA, windowStart),
                        observation(siteA, windowStart.plusDays(1)),
                        observation(outsider, windowStart),
                    ),
                )
            }
        }

        assertTrue(repo.readCurrent(listOf(outsider), windowDates()).isEmpty())
        assertEquals(2, repo.readCurrent(listOf(siteA), windowDates()).size)
    }
}
