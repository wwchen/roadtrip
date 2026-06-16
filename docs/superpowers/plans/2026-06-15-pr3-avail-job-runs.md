# PR 3: Availability Job Runs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every poll execution writes one `availability_job_run` row with status, snapshot count, error message, and timing. Operators (and the future dashboard) can answer "did this watch's last run succeed and when did it run."

**Architecture:** A new `availability_job_run` table keyed by `job_id`. The existing `AvailabilityPollExecutor` brackets each invocation with `runRepo.start(...)` (returns the run id) and `runRepo.complete(...)` / `runRepo.fail(...)` (idempotent terminal updates). No scheduler changes — runs are an executor concern. No FE changes — that's PR 4 (snapshot rename + reservable timeline UI) or beyond.

**Tech Stack:** Kotlin/Ktor, jOOQ + Flyway + Postgres, Testcontainers Postgres for tests.

**Reference docs:** `docs/superpowers/specs/2026-06-15-availability-watches-design.md` (entity model), prior PRs in the stack: PR 1 (#226 watches), PR 2 (#227 jobs + scheduler).

**Stack base:** Branch from `avail-jobs-and-scheduler` (PR #227).

---

## File map

**Created:**

- `backend/src/main/resources/db/migration/V16__avail_job_runs.sql`
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepo.kt`
- `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepoTest.kt`

**Modified:**

- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt` — wrap `handle(...)` in start/complete/fail bracket calls, count snapshots produced, capture errors.
- `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt` — pass the new repo into `AvailabilityPollExecutor` at boot.

**Untouched:**

- `availability_job` table — runs reference jobs by FK; the job table itself doesn't change.
- `Scheduler<T>` — runs are an executor-layer concern; the scheduler still just hands rows to the handler.
- FE — deferred.

---

## Task 1: Migration V16 — `availability_job_run` table

**Files:**

- Create: `backend/src/main/resources/db/migration/V16__avail_job_runs.sql`

- [ ] **Step 1: Write the migration**

```sql
-- PR 3: availability_job_run — one row per poll execution.
--
-- Every time the scheduler claims an availability_job and hands it to
-- AvailabilityPollExecutor, the executor writes one row here. The row
-- records the run's outcome (started / completed / failed), how many
-- snapshot rows it produced, and any error string for failed runs.
--
-- Why a separate table: PR 2 deliberately kept availability_job as
-- just-the-current-state. Per-run history (was this run successful, what
-- did it return, how long did it take) is append-only and unbounded;
-- mixing it onto the job row would conflate "what's the latest state"
-- with "what's the audit log of every execution."
--
-- Retention: indefinite for now. PR 4+ may add a sweeper if row count
-- becomes operationally annoying. The hot index supports per-job
-- "give me the N most recent runs" queries (the dashboard's load-bearing
-- query in a later PR).

CREATE TABLE availability_job_run (
  id              BIGSERIAL    PRIMARY KEY,
  job_id          BIGINT       NOT NULL REFERENCES availability_job(id) ON DELETE CASCADE,
  status          TEXT         NOT NULL DEFAULT 'started'
                                 CHECK (status IN ('started', 'completed', 'failed')),
  snapshot_count  INT          NOT NULL DEFAULT 0
                                 CHECK (snapshot_count >= 0),
  duration_ms     INT          CHECK (duration_ms IS NULL OR duration_ms >= 0),
  error           TEXT,
  started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  completed_at    TIMESTAMPTZ
);

-- Hot path: "show me the last N runs for this job, newest first" is what
-- the dashboard's per-job drill-down will query. Composite index on
-- (job_id, started_at DESC) supports it without a sort step.
CREATE INDEX availability_job_run_job_started_idx
  ON availability_job_run (job_id, started_at DESC);
```

- [ ] **Step 2: Verify migration applies cleanly**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL. jOOQ codegen generates `AvailabilityJobRun` and `AvailabilityJobRunRecord` under `backend/build/generated/jooq/main/`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V16__avail_job_runs.sql
git commit -m "PR 3: add availability_job_run table"
```

---

## Task 2: `AvailabilityJobRunRepo`

**Files:**

- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepo.kt`

The repo exposes three lifecycle methods plus inspection helpers. Idempotency on terminal calls (`complete` / `fail`) means double-firing is safe — though the executor shouldn't double-fire, defending against it makes test setup simpler and guards against future bugs.

- [ ] **Step 1: Create the repo**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityJobRun.Companion.AVAILABILITY_JOB_RUN
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime

class AvailabilityJobRunRepo(
    private val ctx: DSLContext,
) {
    data class Run(
        val id: Long,
        val jobId: Long,
        val status: String,
        val snapshotCount: Int,
        val durationMs: Int?,
        val error: String?,
        val startedAt: OffsetDateTime,
        val completedAt: OffsetDateTime?,
    )

    /**
     * Insert a new row at status='started'. Returns the new row id; the
     * executor passes it back to [complete] / [fail] when the handler
     * finishes.
     */
    fun start(
        jobId: Long,
        startedAt: OffsetDateTime,
    ): Long =
        ctx
            .insertInto(AVAILABILITY_JOB_RUN)
            .set(AVAILABILITY_JOB_RUN.JOB_ID, jobId)
            .set(AVAILABILITY_JOB_RUN.STATUS, "started")
            .set(AVAILABILITY_JOB_RUN.STARTED_AT, startedAt)
            .returningResult(AVAILABILITY_JOB_RUN.ID)
            .fetchOne()!!
            .value1()!!

    /**
     * Mark a run completed. Idempotent: if the row is already in a
     * terminal state, this is a no-op (returns false). Otherwise updates
     * status to 'completed', records snapshot_count, duration_ms, and
     * completed_at, and returns true.
     */
    fun complete(
        runId: Long,
        snapshotCount: Int,
        completedAt: OffsetDateTime,
        durationMs: Int,
    ): Boolean =
        ctx
            .update(AVAILABILITY_JOB_RUN)
            .set(AVAILABILITY_JOB_RUN.STATUS, "completed")
            .set(AVAILABILITY_JOB_RUN.SNAPSHOT_COUNT, snapshotCount)
            .set(AVAILABILITY_JOB_RUN.DURATION_MS, durationMs)
            .set(AVAILABILITY_JOB_RUN.COMPLETED_AT, completedAt)
            .where(AVAILABILITY_JOB_RUN.ID.eq(runId))
            .and(AVAILABILITY_JOB_RUN.STATUS.eq("started"))
            .execute() > 0

    /**
     * Mark a run failed. Same idempotency contract as [complete]. The
     * error string is stored verbatim (truncated to ~2KB by the caller
     * if needed; Postgres TEXT has no enforced limit).
     */
    fun fail(
        runId: Long,
        error: String,
        completedAt: OffsetDateTime,
        durationMs: Int,
    ): Boolean =
        ctx
            .update(AVAILABILITY_JOB_RUN)
            .set(AVAILABILITY_JOB_RUN.STATUS, "failed")
            .set(AVAILABILITY_JOB_RUN.ERROR, error)
            .set(AVAILABILITY_JOB_RUN.DURATION_MS, durationMs)
            .set(AVAILABILITY_JOB_RUN.COMPLETED_AT, completedAt)
            .where(AVAILABILITY_JOB_RUN.ID.eq(runId))
            .and(AVAILABILITY_JOB_RUN.STATUS.eq("started"))
            .execute() > 0

    fun findById(id: Long): Run? =
        ctx
            .selectFrom(AVAILABILITY_JOB_RUN)
            .where(AVAILABILITY_JOB_RUN.ID.eq(id))
            .fetchOne()
            ?.let(::fromRecord)

    fun listForJob(
        jobId: Long,
        limit: Int = 50,
    ): List<Run> =
        ctx
            .selectFrom(AVAILABILITY_JOB_RUN)
            .where(AVAILABILITY_JOB_RUN.JOB_ID.eq(jobId))
            .orderBy(AVAILABILITY_JOB_RUN.STARTED_AT.desc(), AVAILABILITY_JOB_RUN.ID.desc())
            .limit(limit.coerceIn(1, 500))
            .fetch { fromRecord(it) }

    private fun fromRecord(r: Record): Run =
        Run(
            id = r.get(AVAILABILITY_JOB_RUN.ID)!!,
            jobId = r.get(AVAILABILITY_JOB_RUN.JOB_ID)!!,
            status = r.get(AVAILABILITY_JOB_RUN.STATUS)!!,
            snapshotCount = r.get(AVAILABILITY_JOB_RUN.SNAPSHOT_COUNT)!!,
            durationMs = r.get(AVAILABILITY_JOB_RUN.DURATION_MS),
            error = r.get(AVAILABILITY_JOB_RUN.ERROR),
            startedAt = r.get(AVAILABILITY_JOB_RUN.STARTED_AT)!!,
            completedAt = r.get(AVAILABILITY_JOB_RUN.COMPLETED_AT),
        )
}
```

- [ ] **Step 2: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepo.kt
git commit -m "PR 3: add AvailabilityJobRunRepo"
```

---

## Task 3: `AvailabilityJobRunRepoTest`

**Files:**

- Create: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepoTest.kt`

Five tests against Testcontainers Postgres, mirroring the pattern from `AvailabilityJobRepoTest`. Each starts an empty schema, seeds a watch + job, exercises one or two repo methods, asserts row state.

- [ ] **Step 1: Write the test**

```kotlin
package ca.floo.roadtrip.repo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilityJobRunRepoTest {
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
            )!!.get("id", Long::class.java)

    private fun seedJob(poiId: Long): Long {
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        poi_id, target_dates, cadence_sec, trigger_kinds
                    ) VALUES (
                        ?, ARRAY['2026-07-04'::date], 60, ARRAY['atc']
                    ) RETURNING id
                    """.trimIndent(),
                    poiId,
                )!!.get("id", Long::class.java)
        val intent: JsonObject =
            buildJsonObject {
                put("kind", JsonPrimitive("reservable"))
                put("reservable_id", JsonPrimitive(0))
            }
        return AvailabilityJobRepo(ctx).upsertForWatch(
            watchId = watchId,
            intentPayload = intent,
            cadenceSec = 60,
            status = "active",
            nextRunAt = now(),
        ).id
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    @Test
    fun `start creates a row in 'started' state`() {
        val jobId = seedJob(seedPoi())
        val repo = AvailabilityJobRunRepo(ctx)
        val started = now()
        val runId = repo.start(jobId, started)
        val row = repo.findById(runId)
        assertNotNull(row)
        assertEquals(jobId, row.jobId)
        assertEquals("started", row.status)
        assertEquals(0, row.snapshotCount)
        assertNull(row.durationMs)
        assertNull(row.completedAt)
        assertNull(row.error)
        assertEquals(started.toEpochSecond(), row.startedAt.toEpochSecond())
    }

    @Test
    fun `complete updates a started row and returns true`() {
        val jobId = seedJob(seedPoi())
        val repo = AvailabilityJobRunRepo(ctx)
        val runId = repo.start(jobId, now().minusSeconds(2))
        val ok = repo.complete(runId, snapshotCount = 7, completedAt = now(), durationMs = 1234)
        assertTrue(ok)
        val row = repo.findById(runId)!!
        assertEquals("completed", row.status)
        assertEquals(7, row.snapshotCount)
        assertEquals(1234, row.durationMs)
        assertNotNull(row.completedAt)
        assertNull(row.error)
    }

    @Test
    fun `complete is idempotent — second call returns false`() {
        val jobId = seedJob(seedPoi())
        val repo = AvailabilityJobRunRepo(ctx)
        val runId = repo.start(jobId, now().minusSeconds(2))
        assertTrue(repo.complete(runId, snapshotCount = 1, completedAt = now(), durationMs = 100))
        // Second call: row is no longer 'started', so update returns 0 rows.
        assertFalse(repo.complete(runId, snapshotCount = 99, completedAt = now(), durationMs = 999))
        // Original values preserved.
        val row = repo.findById(runId)!!
        assertEquals(1, row.snapshotCount)
        assertEquals(100, row.durationMs)
    }

    @Test
    fun `fail updates a started row with error and returns true`() {
        val jobId = seedJob(seedPoi())
        val repo = AvailabilityJobRunRepo(ctx)
        val runId = repo.start(jobId, now().minusSeconds(2))
        val ok = repo.fail(runId, error = "upstream 503", completedAt = now(), durationMs = 5000)
        assertTrue(ok)
        val row = repo.findById(runId)!!
        assertEquals("failed", row.status)
        assertEquals("upstream 503", row.error)
        assertEquals(5000, row.durationMs)
        assertEquals(0, row.snapshotCount)
    }

    @Test
    fun `listForJob returns runs newest-first`() {
        val jobId = seedJob(seedPoi())
        val repo = AvailabilityJobRunRepo(ctx)
        val r1 = repo.start(jobId, now().minusMinutes(3))
        repo.complete(r1, 1, now().minusMinutes(2), 100)
        val r2 = repo.start(jobId, now().minusMinutes(1))
        repo.complete(r2, 2, now(), 100)
        val rows = repo.listForJob(jobId, limit = 10)
        assertEquals(2, rows.size)
        assertEquals(r2, rows[0].id)
        assertEquals(r1, rows[1].id)
    }
}
```

- [ ] **Step 2: Run tests**

```bash
cd backend
./gradlew test --tests AvailabilityJobRunRepoTest
```

Expected: 5/5 passing.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepoTest.kt
git commit -m "PR 3: AvailabilityJobRunRepo tests"
```

---

## Task 4: Wire run bracketing into `AvailabilityPollExecutor`

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt`

The executor needs to:

1. Accept an `AvailabilityJobRunRepo` in its constructor.
2. In `handle(...)`, capture `started = OffsetDateTime.now()`, call `runs.start(job.id, started)` to get a `runId`.
3. Track snapshot count produced by the run. The existing `ReservableAvailabilityFetchService.fetch(...)` returns a `Response` whose body has an `availability` list — sized N by the number of days returned by the upstream. Use that as `snapshotCount` for now (it's the count of rows the fetch service appended via `appendAvailabilityPoll`).
4. On normal completion of the inner work, call `runs.complete(runId, snapshotCount, now(), durationMs)`.
5. On any exception thrown inside `try { ... }`, call `runs.fail(runId, message, now(), durationMs)` and rethrow-as-log (the existing pattern logs and continues).
6. The outer `HandlerResult` return is unchanged — runs are an audit-trail concern, not a scheduling-loop concern.

Important: the existing executor has a top-level `try/catch (e: Exception)` that swallows any error from intent decoding or reservable resolution. The run row needs to record the failure regardless. Restructure so:

- Run insert (`start`) happens unconditionally first (before intent decode).
- Single `try/catch` wraps the entire body; failure path calls `runs.fail`.
- Success path calls `runs.complete` with the actual snapshot count.

Also, for `Poi`-scoped intents (deferred fan-out), the executor logs and skips. Treat that as a successful run with `snapshotCount=0`. Same for any "missing reservable" / "no resolvable provider" early-returns — these are not execution errors per se; they are degenerate states where polling has nothing to do. Recording them as `completed` with snapshotCount=0 is the right call so the operator can still see the cadence was honored.

A network/upstream error mid-fetch is a `failed` run.

- [ ] **Step 1: Read the current executor**

```bash
cat backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt
```

Confirm it matches Task 11 of the PR 2 plan (or whatever shape PR 2 actually shipped). The structural edit below assumes the PR 2 shape exists.

- [ ] **Step 2: Replace the executor with the new shape**

Open `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt`. Replace its full content with:

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.AvailabilityJobRunRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.api.ReservableAvailabilityFetchService
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.booking.ProviderRefParser
import ca.floo.roadtrip.service.scheduler.HandlerResult
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Executes one polling job. Wired into [Scheduler] as the handler.
 *
 * Reservable-scope: fetches per-day availability through the booking
 * provider and appends snapshot rows. POI-scope: deferred (fan-out
 * logic ships in a later PR).
 *
 * Per-run audit: every invocation writes one [AvailabilityJobRunRepo]
 * row. Successful runs (including no-op runs for unresolvable scopes
 * and for POI-scope until fan-out lands) are recorded as 'completed'
 * with `snapshot_count`. Upstream / unexpected exceptions are recorded
 * as 'failed' with the error message. Runs are never lost — even if
 * `start` succeeds and the work errors, the row gets a terminal
 * status so the operator can see the failure.
 *
 * Handler always returns a [HandlerResult] — even on upstream failure —
 * because losing the row would mean the watch silently stops polling.
 */
class AvailabilityPollExecutor(
    private val reservables: ReservableRepo,
    private val campsiteProviders: CampsiteProviderRepo,
    private val bookingProviders: BookingProviderRegistry,
    private val fetches: ReservableAvailabilityFetchService,
    private val runs: AvailabilityJobRunRepo,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(job: AvailabilityJobRepo.Job): HandlerResult {
        val startedAt = OffsetDateTime.now()
        val runId = runs.start(job.id, startedAt)
        var snapshotCount = 0
        try {
            val intent = AvailabilityJobIntent.fromJsonObject(job.intentPayload)
            snapshotCount =
                when (intent) {
                    is AvailabilityJobIntent.Reservable -> runReservable(job.id, intent)
                    is AvailabilityJobIntent.Poi -> {
                        log.info("job {} POI scope not yet executed (poi_id={})", job.id, intent.poiId)
                        0
                    }
                }
            val completedAt = OffsetDateTime.now()
            val durationMs = java.time.Duration.between(startedAt, completedAt).toMillis().toInt().coerceAtLeast(0)
            runs.complete(runId, snapshotCount, completedAt, durationMs)
        } catch (e: Exception) {
            log.warn("job {} run {} failed: {}", job.id, runId, e.message)
            val completedAt = OffsetDateTime.now()
            val durationMs = java.time.Duration.between(startedAt, completedAt).toMillis().toInt().coerceAtLeast(0)
            runs.fail(runId, error = e.message ?: e::class.simpleName ?: "unknown", completedAt = completedAt, durationMs = durationMs)
        }
        return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(job.cadenceSec.toLong()))
    }

    /**
     * Runs a Reservable-scope intent. Returns the number of snapshot
     * rows the fetch produced (sized by the upstream's per-day window).
     * Returns 0 when the intent can't be executed (missing reservable,
     * no resolvable booking provider) — these are recorded as
     * successful no-op runs, not failures.
     */
    private suspend fun runReservable(
        jobId: Long,
        intent: AvailabilityJobIntent.Reservable,
    ): Int {
        val reservable =
            reservables.findById(intent.reservableId)
                ?: run {
                    log.warn("job {}: reservable {} no longer exists", jobId, intent.reservableId)
                    return 0
                }
        val poiIds = reservables.poiIdsForReservable(reservable.id)
        if (poiIds.isEmpty()) {
            log.warn("job {}: reservable {} has no POI parent", jobId, reservable.id)
            return 0
        }
        val refRowsById = campsiteProviders.findProviderRefs(poiIds)
        val parent =
            poiIds
                .asSequence()
                .mapNotNull { refRowsById[it] }
                .firstOrNull { bookingProviders.forPoi(it) != null && ProviderRefParser.parse(it.providerRefJson) != null }
        if (parent == null) {
            log.warn("job {}: reservable {} has no resolvable booking provider", jobId, reservable.id)
            return 0
        }
        val provider = bookingProviders.forPoi(parent)!!
        val ref = ProviderRefParser.parse(parent.providerRefJson)!!

        val firstDate = intent.targetDates.firstOrNull() ?: return 0
        val start = LocalDate.parse(firstDate)
        val days =
            intent.targetDates
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .maxOrNull()
                ?.let { java.time.temporal.ChronoUnit.DAYS.between(start, it).toInt() + 1 }
                ?: 1

        val response =
            fetches.fetch(
                ReservableAvailabilityFetchService.Request(
                    reservableRid = reservable.rid.encode(),
                    provider = provider,
                    ref = ref,
                    vendorId = reservable.rid.vendorId,
                    start = start,
                    days = days,
                    minNights = intent.minNights,
                    force = false,
                ),
            )
        // Each day in the response window is one snapshot row in
        // reservable_availability_log (ReservableAvailabilityFetchService
        // calls appendAvailabilityPoll on the full response).
        return response.availability.size
    }
}
```

Key shape changes from PR 2:

- `runs: AvailabilityJobRunRepo` constructor parameter.
- `runReservable` now returns `Int` (snapshot count).
- The outer `try/catch` is repositioned to bracket the run lifecycle.
- Both success and failure paths write a terminal status to the run row.

- [ ] **Step 3: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD FAILED on `Main.kt` because the constructor signature changed. That's fixed in Task 5.

- [ ] **Step 4: Commit (broken-compile state, fixed in Task 5)**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt
git commit -m "PR 3: bracket executor with run start/complete/fail"
```

---

## Task 5: Wire `AvailabilityJobRunRepo` into `Main.kt`

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`

The bootstrap block from PR 2 currently constructs `AvailabilityPollExecutor` with four constructor args. It now needs five.

- [ ] **Step 1: Add the import**

Open `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`. Add (alphabetical, near `AvailabilityJobRepo`):

```kotlin
import ca.floo.roadtrip.repo.AvailabilityJobRunRepo
```

- [ ] **Step 2: Update the bootstrap block**

Find the existing block (added in PR 2's Task 13):

```kotlin
    val pollExecutor =
        AvailabilityPollExecutor(
            reservables = ca.floo.roadtrip.repo.ReservableRepo(ctx),
            campsiteProviders = CampsiteProviderRepo(ctx),
            bookingProviders = bookingProviderRegistry,
            fetches = availabilityFetches,
        )
```

Replace with:

```kotlin
    val pollExecutor =
        AvailabilityPollExecutor(
            reservables = ca.floo.roadtrip.repo.ReservableRepo(ctx),
            campsiteProviders = CampsiteProviderRepo(ctx),
            bookingProviders = bookingProviderRegistry,
            fetches = availabilityFetches,
            runs = AvailabilityJobRunRepo(ctx),
        )
```

- [ ] **Step 3: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run all tests**

```bash
./gradlew test
```

Expected: full suite green. Existing tests don't construct `AvailabilityPollExecutor` directly (the executor isn't unit-tested per the PR 2 plan), so the constructor change doesn't ripple into test files.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/Main.kt
git commit -m "PR 3: pass AvailabilityJobRunRepo into executor"
```

---

## Task 6: Manual smoke

**Files:** none (verification only)

The new behavior shows up in the database. Steps:

- [ ] **Step 1: Restart the backend**

After Tilt rebuilds (or manually `docker compose restart backend`), watch logs for `scheduler availability starting` from PR 2 — that confirms the boot path completed.

- [ ] **Step 2: Create a watch via the FE**

Open `http://localhost:8765/reservables`, expand availability for any reservable, click "Create watch", click Create. The route already exercises the watch+job atomicity from PR 2.

- [ ] **Step 3: Wait at least one cadence**

Default cadence is 60s. Wait ~70s for at least one tick.

- [ ] **Step 4: Verify run rows accumulate**

```bash
psql "$ROADTRIP_DB_URL" -c "
SELECT id, job_id, status, snapshot_count, duration_ms, started_at, completed_at, error
FROM availability_job_run
ORDER BY id DESC
LIMIT 10;"
```

Expected: at least one row with `status='completed'`, a non-null `completed_at`, and `snapshot_count` matching the watch's `target_dates × days`. If the booking provider succeeded, `error` is NULL.

- [ ] **Step 5: Verify failed runs land too (optional)**

If you have a way to break the upstream (cut network briefly, point a watch at an invalid reservable), a subsequent tick should write `status='failed'` with the error captured. Skip this step if no easy way to provoke a failure.

- [ ] **Step 6: Verify pause stops new runs**

Pause the watch via the FE. Wait ~70s. Re-run the SELECT — no new run rows for that job's `job_id` should appear.

If any step fails, capture the failure and stop.

---

## Task 7: Lint, push, open stacked PR

- [ ] **Step 1: Format + check**

```bash
cd backend
./gradlew ktlintFormat
./gradlew ktlintCheck
```

Expected: green.

- [ ] **Step 2: Full test suite**

```bash
./gradlew test
```

Expected: green.

- [ ] **Step 3: Commit any ktlintFormat changes**

```bash
git add backend/src
git commit -m "PR 3: ktlintFormat" || true
```

- [ ] **Step 4: Add the plan doc**

```bash
git add docs/superpowers/plans/2026-06-15-pr3-avail-job-runs.md
git commit -m "PR 3: add implementation plan"
```

- [ ] **Step 5: Push and open PR**

```bash
git push -u origin avail-job-runs
```

Write the PR body to a file (per global memory rule):

```bash
cat > pr_body.md <<'PR'
## PR 3: Availability job runs

Stacks on PR #227. Adds per-execution audit rows so operators can answer "did this watch's last poll succeed."

### What ships
- V16 migration: `availability_job_run` table (one row per execution, with status/snapshot_count/duration_ms/error).
- `AvailabilityJobRunRepo` with `start` / `complete` / `fail` / `findById` / `listForJob`. Terminal updates are idempotent.
- `AvailabilityPollExecutor` brackets each invocation. Successful work writes `completed` with the snapshot count. No-op runs (missing reservable, POI scope deferred) write `completed` with snapshot_count=0. Caught exceptions write `failed` with the error message.
- `Main.kt` passes the new repo into the executor.

### Scope
- No FE changes. Operator visibility into runs (the `/availability` dashboard) lands later.
- Retention policy is "keep forever". Sweeper deferred until row count becomes a problem.

### Verification
- `./gradlew ktlintCheck test` — green
- Manual smoke per docs/superpowers/plans/2026-06-15-pr3-avail-job-runs.md Task 6

🤖 Generated with [Claude Code](https://claude.com/claude-code)
PR

gh pr create --title "PR 3: avail job runs" --body-file pr_body.md --base avail-jobs-and-scheduler
rm pr_body.md
```

- [ ] **Step 6: Verify CI**

```bash
gh pr checks
```

Expected: green.

---

## Out of scope (deferred)

- **`/availability` dashboard.** The runs table is now populated; the UI to surface it lands in a later PR (likely combined with the snapshot rename or shipped right after).
- **Retention sweeper.** Old runs are kept indefinitely. A scheduled cleanup task lands when row count becomes operationally annoying.
- **POI fan-out.** Still deferred. POI-scoped jobs write a no-op run.
- **Per-run snapshot association.** Snapshot rows in `reservable_availability_log` (renamed to `availability_snapshot` in a later PR) don't yet carry a `run_id` FK. Adding it is the snapshot-rename PR's scope, since renaming the table is the natural moment to also reshape its FKs.
