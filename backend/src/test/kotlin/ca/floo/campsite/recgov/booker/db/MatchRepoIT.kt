package ca.floo.campsite.recgov.booker.db

import ca.floo.roadtrip.importer.dsl
import ca.floo.roadtrip.importer.migrate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatchRepoIT {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: DataSource
    private lateinit var alerts: AlertRepo
    private lateinit var matches: MatchRepo

    @BeforeAll
    fun startContainer() {
        // postgis/postgis is a postgres derivative; Testcontainers needs the
        // explicit compatibility hint so PostGISContainer picks the right
        // wait-for-readiness probe. V1__pois.sql requires the PostGIS
        // extension, which plain postgres:16 doesn't ship.
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg = PostgreSQLContainer<Nothing>(image).apply {
            withDatabaseName("campsite_test")
            withUsername("test")
            withPassword("test")
        }
        pg.start()
        val cfg = HikariConfig().apply {
            jdbcUrl = pg.jdbcUrl
            username = pg.username
            password = pg.password
            maximumPoolSize = 4
        }
        ds = HikariDataSource(cfg)
        migrate(ds)
        val ctx = dsl(ds)
        alerts = AlertRepo(ctx)
        matches = MatchRepo(ctx)
    }

    @AfterAll
    fun stopContainer() {
        (ds as HikariDataSource).close()
        pg.stop()
    }

    @BeforeEach
    fun cleanTables() {
        val ctx = dsl(ds)
        ctx.execute("TRUNCATE matches, alerts RESTART IDENTITY CASCADE")
    }

    private fun seedAlertAndMatch(): Long {
        val alertId = alerts.create(AlertRepo.CreateInput(
            campgroundId = "232447", campgroundName = "Upper Pines",
            parentName = null, parentId = null,
            startDate = "2026-07-01", endDate = "2026-07-05",
            minNights = 1,
            campsiteTypes = emptyList(), equipmentTypes = emptyList(),
            maxPeople = null, specificSites = emptyList(),
            notifySlack = false, autoCart = false, stopAfterMatch = false,
            notes = null,
        ))
        return matches.create(MatchRepo.CreateInput(
            alertId = alertId,
            campgroundId = "232447",
            campsiteId = "12345",
            site = "A12", loop = "Loop A", campsiteType = "STANDARD",
            availableDates = listOf("2026-07-01"),
            firstDate = "2026-07-01", nights = 1,
        ))!!
    }

    @Test
    fun `claim succeeds once and second claim from another companion is rejected`() {
        val matchId = seedAlertAndMatch()
        val first = matches.claim(matchId, "companion-A", Duration.ofMinutes(5))
        assertNotNull(first)
        assertEquals("companion-A", first.claimedBy)
        assertNotNull(first.leaseExpires)

        val second = matches.claim(matchId, "companion-B", Duration.ofMinutes(5))
        assertNull(second, "second claim must lose")
    }

    @Test
    fun `result clears lease and is one-way`() {
        val matchId = seedAlertAndMatch()
        matches.claim(matchId, "companion-A", Duration.ofMinutes(5))
        val resulted = matches.result(matchId, cartAdded = true)
        assertNotNull(resulted)
        assertEquals(true, resulted.cartAdded)
        assertNotNull(resulted.resultAt)

        // Second result on a finalized match must fail (already has result_at).
        val second = matches.result(matchId, cartAdded = false)
        assertNull(second)
    }

    @Test
    fun `result on unclaimed match fails`() {
        val matchId = seedAlertAndMatch()
        val r = matches.result(matchId, cartAdded = true)
        assertNull(r)
    }

    @Test
    fun `sweepExpiredLeases releases timed-out claims`() {
        val matchId = seedAlertAndMatch()
        // Claim with negative lease (already expired) so the next sweep picks it up.
        matches.claim(matchId, "companion-A", Duration.ofSeconds(-1))

        val released = matches.sweepExpiredLeases()
        assertEquals(1, released.size)
        assertEquals(matchId, released[0].id)

        // Now another companion can claim it.
        val reclaim = matches.claim(matchId, "companion-B", Duration.ofMinutes(5))
        assertNotNull(reclaim)
        assertEquals("companion-B", reclaim.claimedBy)
    }

    @Test
    fun `sweepExpiredLeases ignores resulted matches`() {
        val matchId = seedAlertAndMatch()
        matches.claim(matchId, "companion-A", Duration.ofSeconds(-1))
        matches.result(matchId, cartAdded = true)
        val released = matches.sweepExpiredLeases()
        assertTrue(released.isEmpty())
    }

    @Test
    fun `create dedups duplicate matches within an hour`() {
        val matchId = seedAlertAndMatch()
        val dup = matches.create(MatchRepo.CreateInput(
            alertId = matches.get(matchId)!!.alertId,
            campgroundId = "232447", campsiteId = "12345",
            site = "A12", loop = "Loop A", campsiteType = "STANDARD",
            availableDates = listOf("2026-07-01"),
            firstDate = "2026-07-01", nights = 1,
        ))
        assertNull(dup, "duplicate match within 1h must be deduped")
    }
}
