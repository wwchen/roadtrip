package ca.floo.roadtrip.repo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilityHeatmapRepoTest {
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

    private fun insertSnapshot(
        reservableId: Long,
        targetDate: LocalDate,
        observedAt: OffsetDateTime,
        available: Boolean,
        status: String = if (available) "available" else "booked",
    ) {
        ctx.execute(
            """
            INSERT INTO availability_snapshot (
                reservable_id, observed_at, target_date, status, available, day_payload
            ) VALUES (?::bigint, ?::timestamptz, ?::date, ?, ?::boolean, '{}'::jsonb)
            """.trimIndent(),
            reservableId,
            observedAt.toString(),
            targetDate.toString(),
            status,
            available,
        )
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    @Test
    fun `empty inputs return empty result`() {
        val repo = AvailabilityHeatmapRepo(ctx)
        assertTrue(repo.loadHeatmap(emptyList(), listOf(LocalDate.parse("2026-07-04"))).isEmpty())
        assertTrue(repo.loadHeatmap(listOf(1L), emptyList()).isEmpty())
    }

    @Test
    fun `single reservable single date returns one row`() {
        val rid = seedReservable("100")
        val date = LocalDate.parse("2026-07-04")
        insertSnapshot(rid, date, now().minusMinutes(1), available = true)
        val repo = AvailabilityHeatmapRepo(ctx)
        val cells = repo.loadHeatmap(listOf(rid), listOf(date))
        assertEquals(1, cells.size)
        assertEquals(rid, cells[0].reservableId)
        assertEquals(date, cells[0].targetDate)
        assertEquals(true, cells[0].available)
        assertEquals("available", cells[0].status)
    }

    @Test
    fun `latest snapshot wins for same pair`() {
        val rid = seedReservable("100")
        val date = LocalDate.parse("2026-07-04")
        insertSnapshot(rid, date, now().minusMinutes(5), available = false)
        insertSnapshot(rid, date, now().minusMinutes(2), available = true)
        insertSnapshot(rid, date, now().minusMinutes(1), available = false, status = "booked")
        val repo = AvailabilityHeatmapRepo(ctx)
        val cells = repo.loadHeatmap(listOf(rid), listOf(date))
        assertEquals(1, cells.size)
        assertEquals(false, cells[0].available)
        assertEquals("booked", cells[0].status)
    }

    @Test
    fun `cross product returns one cell per pair, missing pairs absent`() {
        val r1 = seedReservable("100")
        val r2 = seedReservable("200")
        val d1 = LocalDate.parse("2026-07-04")
        val d2 = LocalDate.parse("2026-07-05")
        insertSnapshot(r1, d1, now().minusMinutes(1), available = true)
        insertSnapshot(r1, d2, now().minusMinutes(1), available = false, status = "booked")
        insertSnapshot(r2, d1, now().minusMinutes(1), available = false, status = "closed")
        val repo = AvailabilityHeatmapRepo(ctx)
        val cells = repo.loadHeatmap(listOf(r1, r2), listOf(d1, d2))
        assertEquals(3, cells.size)
        val byPair = cells.associateBy { it.reservableId to it.targetDate }
        assertEquals("available", byPair[r1 to d1]!!.status)
        assertEquals("booked", byPair[r1 to d2]!!.status)
        assertEquals("closed", byPair[r2 to d1]!!.status)
        assertEquals(null, byPair[r2 to d2])
    }
}
