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
        pg =
            PostgreSQLContainer<Nothing>(image).apply {
                withDatabaseName("campsite_test")
                withUsername("test")
                withPassword("test")
            }
        pg.start()
        val cfg =
            HikariConfig().apply {
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
        val alertId =
            alerts.create(
                AlertRepo.CreateInput(
                    campgroundId = "232447",
                    campgroundName = "Upper Pines",
                    parentName = null,
                    parentId = null,
                    startDate = "2026-07-01",
                    endDate = "2026-07-05",
                    minNights = 1,
                    campsiteTypes = emptyList(),
                    equipmentTypes = emptyList(),
                    maxPeople = null,
                    specificSites = emptyList(),
                    notifySlack = false,
                    autoCart = false,
                    stopAfterMatch = false,
                    notes = null,
                ),
            )
        return matches.create(
            MatchRepo.CreateInput(
                alertId = alertId,
                campgroundId = "232447",
                campsiteId = "12345",
                site = "A12",
                loop = "Loop A",
                campsiteType = "STANDARD",
                availableDates = listOf("2026-07-01"),
                firstDate = "2026-07-01",
                nights = 1,
            ),
        )!!
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

    // ----- nextWorkItem (the planner SQL behind /api/campsite/work/next) -----

    private fun seedAlert(
        autoCart: Boolean = true,
        status: String = "active",
    ): Long {
        val id =
            alerts.create(
                AlertRepo.CreateInput(
                    campgroundId = "232447",
                    campgroundName = "Upper Pines",
                    parentName = null,
                    parentId = null,
                    startDate = "2026-07-01",
                    endDate = "2026-07-05",
                    minNights = 1,
                    campsiteTypes = emptyList(),
                    equipmentTypes = emptyList(),
                    maxPeople = null,
                    specificSites = emptyList(),
                    notifySlack = false,
                    autoCart = autoCart,
                    stopAfterMatch = false,
                    notes = null,
                ),
            )
        if (status != "active") alerts.patch(id, mapOf("status" to status))
        return id
    }

    private fun seedMatch(
        alertId: Long,
        campsiteId: String = "12345",
        firstDate: String = "2026-07-01",
    ): Long =
        matches.create(
            MatchRepo.CreateInput(
                alertId = alertId,
                campgroundId = "232447",
                campsiteId = campsiteId,
                site = "site-$campsiteId",
                loop = "Loop A",
                campsiteType = "STANDARD",
                availableDates = listOf(firstDate),
                firstDate = firstDate,
                nights = 1,
            ),
        )!!

    @Test
    fun `nextWorkItem returns oldest pickable match for an active auto_cart alert`() {
        val alertId = seedAlert(autoCart = true)
        val first = seedMatch(alertId, campsiteId = "100")
        val second = seedMatch(alertId, campsiteId = "200")
        // Both pickable; planner returns the older one (smaller id == earlier found_at).
        val pick = matches.nextWorkItem()
        assertNotNull(pick)
        assertEquals(first, pick.id)
        // Sanity: not the second.
        assertEquals(true, pick.id < second)
    }

    @Test
    fun `nextWorkItem returns null when alert has an in-flight claim`() {
        val alertId = seedAlert(autoCart = true)
        val a = seedMatch(alertId, campsiteId = "100")
        seedMatch(alertId, campsiteId = "200")
        // Claim the first; planner must NOT hand out the second while the
        // alert has an in-flight ATC.
        matches.claim(a, "companion-A", Duration.ofMinutes(5))
        assertNull(matches.nextWorkItem())
    }

    @Test
    fun `nextWorkItem returns next match for the same alert after a result`() {
        val alertId = seedAlert(autoCart = true)
        val a = seedMatch(alertId, campsiteId = "100")
        val b = seedMatch(alertId, campsiteId = "200")
        matches.claim(a, "companion-A", Duration.ofMinutes(5))
        matches.result(a, cartAdded = false)
        // First is resulted → no longer in-flight; planner falls through to b.
        val pick = matches.nextWorkItem()
        assertNotNull(pick)
        assertEquals(b, pick.id)
    }

    @Test
    fun `nextWorkItem skips dismissed matches`() {
        val alertId = seedAlert(autoCart = true)
        val a = seedMatch(alertId, campsiteId = "100")
        val b = seedMatch(alertId, campsiteId = "200")
        matches.softDelete(a)
        val pick = matches.nextWorkItem()
        assertNotNull(pick)
        assertEquals(b, pick.id)
    }

    @Test
    fun `nextWorkItem skips paused alerts`() {
        val alertId = seedAlert(autoCart = true, status = "paused")
        seedMatch(alertId)
        assertNull(matches.nextWorkItem())
    }

    @Test
    fun `nextWorkItem skips done alerts`() {
        val alertId = seedAlert(autoCart = true, status = "done")
        seedMatch(alertId)
        assertNull(matches.nextWorkItem())
    }

    @Test
    fun `nextWorkItem skips alerts with auto_cart=false`() {
        val alertId = seedAlert(autoCart = false)
        seedMatch(alertId)
        assertNull(matches.nextWorkItem())
    }

    @Test
    fun `nextWorkItem returns match for second alert when first is in-flight`() {
        val a1 = seedAlert(autoCart = true)
        val a2 = seedAlert(autoCart = true)
        val m1 = seedMatch(a1, campsiteId = "100")
        val m2 = seedMatch(a2, campsiteId = "200")
        matches.claim(m1, "companion-A", Duration.ofMinutes(5))
        // a1 is in-flight, but a2 is independent → planner returns m2.
        val pick = matches.nextWorkItem()
        assertNotNull(pick)
        assertEquals(m2, pick.id)
    }

    @Test
    fun `nextWorkItem treats expired lease as not-in-flight`() {
        val alertId = seedAlert(autoCart = true)
        val a = seedMatch(alertId, campsiteId = "100")
        val b = seedMatch(alertId, campsiteId = "200")
        // Claim with negative lease so the alert's "in-flight" subquery sees
        // an expired lease — planner should NOT consider this in-flight.
        matches.claim(a, "companion-A", Duration.ofSeconds(-1))
        // a itself is still claimed_by NOT NULL so it isn't pickable; but b
        // should be.
        val pick = matches.nextWorkItem()
        assertNotNull(pick)
        assertEquals(b, pick.id)
    }

    // ---------------------------------------------------------------------

    @Test
    fun `create dedups duplicate matches within an hour`() {
        val matchId = seedAlertAndMatch()
        val dup =
            matches.create(
                MatchRepo.CreateInput(
                    alertId = matches.get(matchId)!!.alertId,
                    campgroundId = "232447",
                    campsiteId = "12345",
                    site = "A12",
                    loop = "Loop A",
                    campsiteType = "STANDARD",
                    availableDates = listOf("2026-07-01"),
                    firstDate = "2026-07-01",
                    nights = 1,
                ),
            )
        assertNull(dup, "duplicate match within 1h must be deduped")
    }
}
