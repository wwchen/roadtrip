package ca.floo.roadtrip.repo

import ca.floo.roadtrip.service.availability.WatchStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AvailabilityFetchCallRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_fetch_call")
        ctx.execute("DELETE FROM availability_job_run")
        ctx.execute("DELETE FROM availability_job")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private fun seedPoi(): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at
                ) VALUES (
                    'test', 'p1', 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, NULL, '2026-06-01 00:00:00+00'::timestamptz
                ) RETURNING id
                """.trimIndent(),
            )!!
            .get("id", Long::class.java)

    private fun seedJob(poiId: Long): Long {
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        poi_id, start_date, end_date, cadence_sec, trigger_kinds
                    ) VALUES (
                        ?, '2026-07-04'::date, '2026-07-05'::date, 60, ARRAY['atc']
                    ) RETURNING id
                    """.trimIndent(),
                    poiId,
                )!!
                .get("id", Long::class.java)
        val intent: JsonObject =
            buildJsonObject {
                put("kind", JsonPrimitive("reservable"))
                put("reservable_id", JsonPrimitive(0))
            }
        return AvailabilityJobRepo(ctx)
            .upsertForWatch(
                watchId = watchId,
                intentPayload = intent,
                cadenceSec = 60,
                status = WatchStatus.ACTIVE,
                nextRunAt = now(),
            ).id
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    @Test
    fun `record inserts a fetch call row tied to the run`() {
        val jobId = seedJob(seedPoi())
        val runId = AvailabilityJobRunRepo(ctx).start(jobId, now())
        val repo = AvailabilityFetchCallRepo(ctx)
        repo.record(
            AvailabilityFetchCallRepo.NewCall(
                runId = runId,
                provider = "recgov",
                parentRef = "232447",
                reservableCount = 235,
                windowStart = LocalDate.parse("2026-07-17"),
                windowEnd = LocalDate.parse("2026-07-31"),
                outcome = "rate_limited",
                durationMs = 240941,
                error = "rec.gov 429 after 3 retries",
            ),
        )
        val rows = repo.listForRun(runId)
        assertEquals(1, rows.size)
        assertEquals("rate_limited", rows[0].outcome)
        assertEquals(235, rows[0].reservableCount)
        assertEquals("recgov", rows[0].provider)
        assertEquals("232447", rows[0].parentRef)
        assertEquals(LocalDate.parse("2026-07-17"), rows[0].windowStart)
        assertEquals(LocalDate.parse("2026-07-31"), rows[0].windowEnd)
        assertEquals(240941, rows[0].durationMs)
        assertEquals("rec.gov 429 after 3 retries", rows[0].error)
        assertEquals(runId, rows[0].runId)
    }

    @Test
    fun `record rejects an invalid outcome value`() {
        val jobId = seedJob(seedPoi())
        val runId = AvailabilityJobRunRepo(ctx).start(jobId, now())
        val repo = AvailabilityFetchCallRepo(ctx)
        assertFailsWith<DataAccessException> {
            repo.record(
                AvailabilityFetchCallRepo.NewCall(
                    runId = runId,
                    provider = "recgov",
                    parentRef = "232447",
                    reservableCount = 1,
                    windowStart = LocalDate.parse("2026-07-17"),
                    windowEnd = LocalDate.parse("2026-07-31"),
                    outcome = "bogus",
                    durationMs = null,
                    error = null,
                ),
            )
        }
    }
}
