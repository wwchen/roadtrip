package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.migrate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotBackedAvailabilityServiceTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext

    @BeforeAll
    fun start() {
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg =
            PostgreSQLContainer<Nothing>(image).apply {
                withDatabaseName("roadtrip_test")
                withUsername("test")
                withPassword("test")
            }
        pg.start()
        val cfg =
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                username = pg.username
                password = pg.password
                maximumPoolSize = 2
            }
        ds = HikariDataSource(cfg)
        migrate(ds)
        ctx = DSL.using(ds, SQLDialect.POSTGRES)
    }

    @AfterAll
    fun stop() {
        ds.close()
        pg.stop()
    }

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_snapshot")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
    }

    @Test
    fun `vendor omissions append unknown observations for every known target date`() =
        runBlocking {
            val startDate = LocalDate.parse("2026-07-01")
            val endDate = LocalDate.parse("2026-07-03")
            val dayOneObservedAt = Instant.parse("2026-06-18T10:00:00Z")
            val dayTwoObservedAt = Instant.parse("2026-06-18T10:01:00Z")
            val seen = seedReservable("100")
            val omitted = seedReservable("200")
            val repo = AvailabilitySnapshotRepo(ctx)
            val service =
                SnapshotBackedAvailabilityService(
                    snapshots = repo,
                    clock = Clock.fixed(Instant.parse("2026-06-18T12:00:00Z"), ZoneOffset.UTC),
                )

            val batch =
                service.loadOrFetch(
                    SnapshotBackedAvailabilityService.Request(
                        metadata = SnapshotBackedAvailabilityService.Metadata(provider = "recgov", campgroundId = "232447"),
                        targets =
                            listOf(
                                SnapshotBackedAvailabilityService.TargetReservable(seen, "site:recgov:100"),
                                SnapshotBackedAvailabilityService.TargetReservable(omitted, "site:recgov:200"),
                            ),
                        startDate = startDate,
                        endDate = endDate,
                        ttl = Duration.ofMinutes(10),
                        force = true,
                    ),
                ) {
                    AvailabilityObservationBatch(
                        provider = "recgov",
                        startDate = startDate,
                        endDate = endDate,
                        observations =
                            listOf(
                                ReservableDayObservation("site:recgov:100", startDate, dayOneObservedAt, AvailabilityStatus.AVAILABLE),
                                ReservableDayObservation(
                                    "site:recgov:100",
                                    startDate.plusDays(1),
                                    dayTwoObservedAt,
                                    AvailabilityStatus.RESERVED,
                                ),
                            ),
                        cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 600),
                        campgroundId = "232447",
                    )
                }

            val byPair = batch.observations.associateBy { it.reservableId to it.date }
            assertEquals(4, byPair.size)
            assertEquals(AvailabilityStatus.AVAILABLE, byPair["site:recgov:100" to startDate]!!.status)
            assertEquals(AvailabilityStatus.RESERVED, byPair["site:recgov:100" to startDate.plusDays(1)]!!.status)
            assertEquals(AvailabilityStatus.UNKNOWN, byPair["site:recgov:200" to startDate]!!.status)
            assertEquals(AvailabilityStatus.UNKNOWN, byPair["site:recgov:200" to startDate.plusDays(1)]!!.status)
            assertEquals(dayOneObservedAt, byPair["site:recgov:200" to startDate]!!.observedAt)
            assertEquals(dayTwoObservedAt, byPair["site:recgov:200" to startDate.plusDays(1)]!!.observedAt)

            val persisted =
                repo
                    .loadLatestObservations(listOf(seen, omitted), listOf(startDate, startDate.plusDays(1)))
                    .associateBy { it.reservableId to it.targetDate }
            assertEquals(4, persisted.size)
            assertEquals(AvailabilityStatus.UNKNOWN, persisted[omitted to startDate]!!.status)
            assertEquals(AvailabilityStatus.UNKNOWN, persisted[omitted to startDate.plusDays(1)]!!.status)
        }

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
