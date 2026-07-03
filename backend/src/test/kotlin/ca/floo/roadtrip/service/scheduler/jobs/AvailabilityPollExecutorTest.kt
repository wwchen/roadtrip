package ca.floo.roadtrip.service.scheduler.jobs

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.AvailabilityJobRunRepo
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.migrate
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilityPollExecutorTest {
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
        ctx.execute("DELETE FROM availability_fetch_call")
        ctx.execute("DELETE FROM availability_job_run")
        ctx.execute("DELETE FROM availability_job")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    /** Seeds a campground POI whose provider_ref resolves to ProviderRef.RecGov(campgroundId). */
    private fun seedPoi(campgroundId: String): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at
                ) VALUES (
                    'test', ?, 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, ?::jsonb, '2026-06-01 00:00:00+00'::timestamptz
                ) RETURNING id
                """.trimIndent(),
                "poi-$campgroundId",
                """{"recgov_id": "$campgroundId"}""",
            )!!
            .get("id", Long::class.java)

    /** Seeds one child reservable (site) linked to [poiId]. Returns its db id. */
    private fun seedReservable(
        poiId: Long,
        siteId: String,
    ): Long {
        val reservableId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO reservables (type, vendor, vendor_id, name, source)
                    VALUES ('site', 'recgov', ?, ?, 'test')
                    RETURNING id
                    """.trimIndent(),
                    siteId,
                    "Site $siteId",
                )!!
                .get("id", Long::class.java)
        ctx.execute(
            "INSERT INTO reservable_pois (reservable_id, poi_id) VALUES (?, ?)",
            reservableId,
            poiId,
        )
        return reservableId
    }

    private fun seedPoiJob(
        poiId: Long,
        startDate: String,
        endDate: String,
    ): Long {
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        poi_id, start_date, end_date, cadence_sec, trigger_kinds
                    ) VALUES (
                        ?, ?::date, ?::date, 60, ARRAY['atc']
                    ) RETURNING id
                    """.trimIndent(),
                    poiId,
                    startDate,
                    endDate,
                )!!
                .get("id", Long::class.java)
        val intent: JsonObject =
            AvailabilityJobIntent
                .Poi(
                    poiId = poiId,
                    startDate = startDate,
                    endDate = endDate,
                ).toJsonObject()
        return AvailabilityJobRepo(ctx)
            .upsertForWatch(
                watchId = watchId,
                intentPayload = intent,
                cadenceSec = 60,
                status = WatchStatus.ACTIVE,
                nextRunAt = now(),
            ).id
    }

    /** Fake provider that counts catalogAvailability invocations and returns one
     *  observation per requested reservable/day. */
    private class CountingRecgovProvider : ReservationProvider {
        var calls: Int = 0

        override val id: ReservationProviderId = ReservationProviderId.RECGOV
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = true,
                bookingHorizonDays = 180,
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch {
            calls++
            val observedAt = Instant.now()
            val observations =
                req.reservables.map { ref ->
                    ReservableDayObservation(
                        reservableId = ref.rid,
                        date = req.startDate,
                        observedAt = observedAt,
                        status = AvailabilityStatus.AVAILABLE,
                    )
                }
            return AvailabilityObservationBatch(
                provider = "recgov",
                startDate = req.startDate,
                endDate = req.endDate,
                observations = observations,
                cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
            )
        }

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")
    }

    private class RateLimitedProvider : ReservationProvider {
        override val id: ReservationProviderId = ReservationProviderId.RECGOV
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = true,
                bookingHorizonDays = 180,
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch =
            throw ReservationProviderError.RateLimited(RuntimeException("429"))

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")
    }

    private fun executorFor(provider: ReservationProvider): AvailabilityPollExecutor {
        val reservablesRepo = ReservableRepo(ctx)
        val registry = ReservationProviderRegistry(mapOf("test" to provider))
        val dateResolver = AvailabilityDateResolver()
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = CampsiteProviderRepo(ctx),
                reservablesRepo = reservablesRepo,
                reservationProviders = registry,
                dateResolver = dateResolver,
            )
        return AvailabilityPollExecutor(
            reservablesRepo = reservablesRepo,
            batcher = CatalogAvailabilityBatcher(),
            snapshots = AvailabilitySnapshotRepo(ctx),
            runs = AvailabilityJobRunRepo(ctx),
            dateResolver = dateResolver,
            targets = targets,
            fetchCalls = AvailabilityFetchCallRepo(ctx),
        )
    }

    @Test
    fun `poi over N same-campground sites makes one upstream call`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            listOf("100", "101", "102").forEach { seedReservable(poiId, it) }
            val jobId = seedPoiJob(poiId, "2026-07-17", "2026-07-31")
            val job = AvailabilityJobRepo(ctx).findById(jobId)!!

            val executor = executorFor(provider)
            executor.handle(job)

            assertEquals(1, provider.calls)

            val runs = AvailabilityJobRunRepo(ctx).listForJob(jobId, limit = 10)
            assertEquals(1, runs.size)
            assertEquals("completed", runs[0].status)
            assertTrue(runs[0].snapshotCount > 0)

            val snapshots = AvailabilitySnapshotRepo(ctx).listForRun(runs[0].id, limit = 100)
            assertTrue(snapshots.isNotEmpty())
            assertTrue(snapshots.all { it.runId == runs[0].id })

            val fetchCalls = AvailabilityFetchCallRepo(ctx).listForRun(runs[0].id)
            assertEquals(1, fetchCalls.size)
            assertEquals("ok", fetchCalls[0].outcome)
            assertEquals(3, fetchCalls[0].reservableCount)
            assertEquals("232447", fetchCalls[0].parentRef)
        }

    @Test
    fun `rate limited group fails the run with the outcome string`() =
        runBlocking {
            val provider = RateLimitedProvider()
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val jobId = seedPoiJob(poiId, "2026-07-17", "2026-07-31")
            val job = AvailabilityJobRepo(ctx).findById(jobId)!!

            val executor = executorFor(provider)
            executor.handle(job)

            val runs = AvailabilityJobRunRepo(ctx).listForJob(jobId, limit = 10)
            assertEquals(1, runs.size)
            assertEquals("failed", runs[0].status)
            assertEquals("rate_limited", runs[0].error)

            val fetchCalls = AvailabilityFetchCallRepo(ctx).listForRun(runs[0].id)
            assertEquals(1, fetchCalls.size)
            assertEquals("rate_limited", fetchCalls[0].outcome)
            assertEquals(1, fetchCalls[0].reservableCount)
            assertEquals("232447", fetchCalls[0].parentRef)
        }
}
