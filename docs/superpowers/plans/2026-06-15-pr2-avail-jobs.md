# PR 2: Availability Jobs + Scheduler — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Watches start polling. PR 2 adds the `availability_job` table (one row per active polling unit), a generic `Scheduler<T>` abstraction, and a worker that claims due jobs, calls `BookingProvider.reservableAvailability(...)`, and writes snapshots through the existing `ReservableAvailabilityLogRepo`.

**Architecture:** A watch creates exactly one job atomically. The job carries scheduler state (`next_run_at`, `claim_token`, `claimed_until`) plus a frozen `intent_payload` JSONB so the worker doesn't have to read the watch row. A single in-process `Scheduler<AvailabilityJob>` instance ticks every few seconds, claims rows via `UPDATE … WHERE next_run_at <= now() AND (claimed_until IS NULL OR claimed_until < now()) RETURNING *`, hands each row to a handler, and the handler — `AvailabilityPollExecutor` — resolves the reservable + provider, calls the booking adapter, appends snapshot rows, and re-schedules `next_run_at = now() + cadence_sec`.

**Tech Stack:** Kotlin/Ktor, jOOQ + Flyway + Postgres, kotlinx.coroutines for the scheduler tick, Testcontainers Postgres for tests.

**Reference docs:** `docs/superpowers/specs/2026-06-15-availability-watches-design.md` (entity model), `docs/booking-providers.md` (provider port + cadence semantics).

**Stack base:** Branch from `availability-watches-redesign` (PR #226). PR 3 (job runs + dashboard) stacks on this branch when it lands.

---

## File map

**Created:**

- `backend/src/main/resources/db/migration/V15__avail_jobs.sql`
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepo.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/Schedulable.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/Scheduler.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityJobIntent.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchService.kt`
- `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepoTest.kt`
- `backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/SchedulerTest.kt`
- `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutorTest.kt`

**Modified:**

- `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt` — POST/PATCH/DELETE go through `AvailabilityWatchService` instead of `AvailabilityWatchRepo` directly, so jobs stay in sync with watch lifecycle.
- `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt` — instantiate `Scheduler<AvailabilityJob>` at boot, start its tick loop, register shutdown hook.
- `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt` — assert that POST/PATCH/DELETE route through the service and update jobs.

**Untouched:**

- `web/` — no UI changes in PR 2. Operator visibility into jobs lands in PR 3 (`/availability` dashboard).
- `AvailabilityWatchRepo` — keeps full CRUD; the new service composes it.
- Booking-provider adapters — used as-is via the existing `BookingProviderRegistry`.

---

## Task 1: Migration V15 — `availability_job` table

**Files:**

- Create: `backend/src/main/resources/db/migration/V15__avail_jobs.sql`

- [ ] **Step 1: Write the migration**

```sql
-- PR 2: availability_job — one schedulable polling unit per watch.
--
-- The watch row holds intent (what dates, which reservable, what to do on
-- match). The job row holds *scheduler state*: when to run next, who has
-- the row claimed, and a frozen intent_payload so the worker doesn't have
-- to read the watch table to do its job.
--
-- Why a separate table: PR 1 deliberately kept watches as intent-only.
-- Adding next_run_at / claim_token / claimed_until to the watch row would
-- mix scheduler concerns with user-facing fields. A 1:1 split lets the
-- watch table stay user-shaped and the job table stay scheduler-shaped.
--
-- The intent_payload is denormalized on purpose — editing a watch's
-- target_dates after a run started should not retroactively change what
-- that run polled. Watch service rebuilds intent_payload whenever the
-- watch fields change.

CREATE TABLE availability_job (
  id              BIGSERIAL    PRIMARY KEY,
  watch_id        BIGINT       NOT NULL REFERENCES availability_watch(id) ON DELETE CASCADE,
  intent_payload  JSONB        NOT NULL CHECK (jsonb_typeof(intent_payload) = 'object'),
  cadence_sec     INT          NOT NULL CHECK (cadence_sec >= 5),
  status          TEXT         NOT NULL DEFAULT 'active'
                                 CHECK (status IN ('active', 'paused', 'done')),
  next_run_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  claimed_until   TIMESTAMPTZ,
  claim_token     TEXT,
  last_run_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One job per watch. Lookup by watch is hot when watches are paused/edited.
CREATE UNIQUE INDEX availability_job_watch_idx
  ON availability_job (watch_id);

-- Hot path for the scheduler tick: "give me up to N rows that are due and
-- unclaimed." Partial index keeps it small even when most rows are paused
-- or done.
CREATE INDEX availability_job_due_idx
  ON availability_job (next_run_at)
  WHERE status = 'active';
```

- [ ] **Step 2: Confirm migration applies cleanly**

Run from the repo root:

```bash
cd backend && ./gradlew compileKotlin
```

jOOQ codegen runs against Testcontainers Postgres during the build. Expected: BUILD SUCCESSFUL with `AvailabilityJob` and `AvailabilityJobRecord` generated under `backend/build/generated/jooq/main/`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V15__avail_jobs.sql
git commit -m "PR 2: add availability_job table"
```

---

## Task 2: `AvailabilityJobRepo`

**Files:**

- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepoTest.kt` (added in Task 3)

`AvailabilityJobRepo` exposes everything the watch service and the scheduler need. Three operation groups:

1. **Lifecycle** — `upsertForWatch`, `pause`, `resume`, `markDone`, `deleteForWatch`. Watch service calls these.
2. **Scheduler** — `claimDue`, `release`, `reclaimExpired`. Scheduler calls these.
3. **Inspection** — `findById`, `findByWatchId`. Used by tests and (in PR 3) the operator dashboard.

- [ ] **Step 1: Create the repo**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityJob.Companion.AVAILABILITY_JOB
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class AvailabilityJobRepo(
    private val ctx: DSLContext,
) {
    private val json = Json

    data class Job(
        val id: Long,
        val watchId: Long,
        val intentPayload: JsonObject,
        val cadenceSec: Int,
        val status: String,
        val nextRunAt: OffsetDateTime,
        val claimedUntil: OffsetDateTime?,
        val claimToken: String?,
        val lastRunAt: OffsetDateTime?,
        val createdAt: OffsetDateTime,
        val updatedAt: OffsetDateTime,
    )

    /**
     * Atomically create or refresh the job backing a watch. Called whenever
     * a watch is created, updated, paused, or resumed. Re-uses the existing
     * row if it exists so jobs and watches stay 1:1.
     */
    fun upsertForWatch(
        watchId: Long,
        intentPayload: JsonObject,
        cadenceSec: Int,
        status: String,
        nextRunAt: OffsetDateTime,
    ): Job {
        require(status in setOf("active", "paused", "done")) { "invalid status" }
        ctx
            .insertInto(AVAILABILITY_JOB)
            .set(AVAILABILITY_JOB.WATCH_ID, watchId)
            .set(AVAILABILITY_JOB.INTENT_PAYLOAD, intentPayload.toJSONB())
            .set(AVAILABILITY_JOB.CADENCE_SEC, cadenceSec)
            .set(AVAILABILITY_JOB.STATUS, status)
            .set(AVAILABILITY_JOB.NEXT_RUN_AT, nextRunAt)
            .onConflict(AVAILABILITY_JOB.WATCH_ID)
            .doUpdate()
            .set(AVAILABILITY_JOB.INTENT_PAYLOAD, intentPayload.toJSONB())
            .set(AVAILABILITY_JOB.CADENCE_SEC, cadenceSec)
            .set(AVAILABILITY_JOB.STATUS, status)
            .set(AVAILABILITY_JOB.NEXT_RUN_AT, nextRunAt)
            .set(AVAILABILITY_JOB.UPDATED_AT, OffsetDateTime.now())
            .execute()
        return findByWatchId(watchId)!!
    }

    fun findById(id: Long): Job? =
        ctx
            .selectFrom(AVAILABILITY_JOB)
            .where(AVAILABILITY_JOB.ID.eq(id))
            .fetchOne()
            ?.let(::fromRecord)

    fun findByWatchId(watchId: Long): Job? =
        ctx
            .selectFrom(AVAILABILITY_JOB)
            .where(AVAILABILITY_JOB.WATCH_ID.eq(watchId))
            .fetchOne()
            ?.let(::fromRecord)

    fun deleteForWatch(watchId: Long): Boolean =
        ctx.deleteFrom(AVAILABILITY_JOB).where(AVAILABILITY_JOB.WATCH_ID.eq(watchId)).execute() > 0

    /**
     * Claim up to [limit] active jobs whose next_run_at has passed. Sets
     * status untouched ("active" only — paused/done rows are ignored). Lease
     * extends `claimed_until` by [leaseDuration]; expired or null leases are
     * eligible. Returns the rows the caller now owns.
     *
     * Postgres `FOR UPDATE SKIP LOCKED` means parallel scheduler ticks (or a
     * future second worker) won't hand the same row to two callers.
     */
    fun claimDue(
        now: OffsetDateTime,
        limit: Int,
        leaseDuration: Duration,
    ): List<Job> {
        val token = UUID.randomUUID().toString()
        val leaseUntil = now.plus(leaseDuration)
        // Two-step claim: SELECT … FOR UPDATE SKIP LOCKED, then UPDATE the
        // selected ids. Done in a single transaction.
        return ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val due =
                txn
                    .select(AVAILABILITY_JOB.ID)
                    .from(AVAILABILITY_JOB)
                    .where(AVAILABILITY_JOB.STATUS.eq("active"))
                    .and(AVAILABILITY_JOB.NEXT_RUN_AT.le(now))
                    .and(
                        AVAILABILITY_JOB.CLAIMED_UNTIL.isNull
                            .or(AVAILABILITY_JOB.CLAIMED_UNTIL.lt(now)),
                    )
                    .orderBy(AVAILABILITY_JOB.NEXT_RUN_AT.asc())
                    .limit(limit)
                    .forUpdate()
                    .skipLocked()
                    .fetch(AVAILABILITY_JOB.ID)
            if (due.isEmpty()) return@transactionResult emptyList()
            txn
                .update(AVAILABILITY_JOB)
                .set(AVAILABILITY_JOB.CLAIM_TOKEN, token)
                .set(AVAILABILITY_JOB.CLAIMED_UNTIL, leaseUntil)
                .set(AVAILABILITY_JOB.UPDATED_AT, now)
                .where(AVAILABILITY_JOB.ID.`in`(due))
                .execute()
            txn
                .selectFrom(AVAILABILITY_JOB)
                .where(AVAILABILITY_JOB.ID.`in`(due))
                .fetch { fromRecord(it) }
        }
    }

    /**
     * Release a claimed job after the worker finishes. Verifies the
     * claim_token matches; mismatched calls (lease expired, reclaimed)
     * return false without modifying the row.
     */
    fun release(
        id: Long,
        token: String,
        nextRunAt: OffsetDateTime,
        ranAt: OffsetDateTime,
    ): Boolean =
        ctx
            .update(AVAILABILITY_JOB)
            .set(AVAILABILITY_JOB.CLAIM_TOKEN, null as String?)
            .set(AVAILABILITY_JOB.CLAIMED_UNTIL, null as OffsetDateTime?)
            .set(AVAILABILITY_JOB.NEXT_RUN_AT, nextRunAt)
            .set(AVAILABILITY_JOB.LAST_RUN_AT, ranAt)
            .set(AVAILABILITY_JOB.UPDATED_AT, ranAt)
            .where(AVAILABILITY_JOB.ID.eq(id))
            .and(AVAILABILITY_JOB.CLAIM_TOKEN.eq(token))
            .execute() > 0

    /**
     * Boot recovery: rows whose lease expired without being released
     * (worker crashed, app restarted) get their claim wiped so the next
     * tick can re-claim them.
     */
    fun reclaimExpired(now: OffsetDateTime): Int =
        ctx
            .update(AVAILABILITY_JOB)
            .set(AVAILABILITY_JOB.CLAIM_TOKEN, null as String?)
            .set(AVAILABILITY_JOB.CLAIMED_UNTIL, null as OffsetDateTime?)
            .set(AVAILABILITY_JOB.UPDATED_AT, now)
            .where(AVAILABILITY_JOB.CLAIMED_UNTIL.isNotNull)
            .and(AVAILABILITY_JOB.CLAIMED_UNTIL.lt(now))
            .execute()

    private fun JsonObject.toJSONB(): JSONB =
        JSONB.valueOf(json.encodeToString(JsonObject.serializer(), this))

    private fun fromRecord(r: Record): Job =
        Job(
            id = r.get(AVAILABILITY_JOB.ID)!!,
            watchId = r.get(AVAILABILITY_JOB.WATCH_ID)!!,
            intentPayload = json.parseToJsonElement(r.get(AVAILABILITY_JOB.INTENT_PAYLOAD)!!.data()).jsonObject,
            cadenceSec = r.get(AVAILABILITY_JOB.CADENCE_SEC)!!,
            status = r.get(AVAILABILITY_JOB.STATUS)!!,
            nextRunAt = r.get(AVAILABILITY_JOB.NEXT_RUN_AT)!!,
            claimedUntil = r.get(AVAILABILITY_JOB.CLAIMED_UNTIL),
            claimToken = r.get(AVAILABILITY_JOB.CLAIM_TOKEN),
            lastRunAt = r.get(AVAILABILITY_JOB.LAST_RUN_AT),
            createdAt = r.get(AVAILABILITY_JOB.CREATED_AT)!!,
            updatedAt = r.get(AVAILABILITY_JOB.UPDATED_AT)!!,
        )
}
```

- [ ] **Step 2: Compile**

```bash
cd backend && ./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepo.kt
git commit -m "PR 2: add AvailabilityJobRepo"
```

---

## Task 3: `AvailabilityJobRepoTest`

**Files:**

- Create: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepoTest.kt`

Six tests. Each starts an empty schema, seeds a watch via raw SQL, exercises one repo method, asserts the resulting row state.

- [ ] **Step 1: Write the test class**

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
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilityJobRepoTest {
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

    private fun seedWatch(poiId: Long): Long =
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

    private val sampleIntent: JsonObject =
        buildJsonObject {
            put("kind", JsonPrimitive("reservable_window"))
            put("rid", JsonPrimitive("site:recgov:330257"))
        }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    @Test
    fun `upsertForWatch creates a new job`() {
        val watchId = seedWatch(seedPoi())
        val repo = AvailabilityJobRepo(ctx)
        val job = repo.upsertForWatch(watchId, sampleIntent, 60, "active", now())
        assertEquals(watchId, job.watchId)
        assertEquals(60, job.cadenceSec)
        assertEquals("active", job.status)
        assertEquals(sampleIntent, job.intentPayload)
    }

    @Test
    fun `upsertForWatch is idempotent on watch_id`() {
        val watchId = seedWatch(seedPoi())
        val repo = AvailabilityJobRepo(ctx)
        val first = repo.upsertForWatch(watchId, sampleIntent, 60, "active", now())
        val second = repo.upsertForWatch(watchId, sampleIntent, 120, "paused", now())
        assertEquals(first.id, second.id)
        assertEquals(120, second.cadenceSec)
        assertEquals("paused", second.status)
    }

    @Test
    fun `claimDue returns active jobs whose nextRunAt has passed`() {
        val watchId = seedWatch(seedPoi())
        val repo = AvailabilityJobRepo(ctx)
        val past = now().minusMinutes(1)
        repo.upsertForWatch(watchId, sampleIntent, 60, "active", past)
        val claimed = repo.claimDue(now(), limit = 10, leaseDuration = Duration.ofSeconds(30))
        assertEquals(1, claimed.size)
        assertNotNull(claimed[0].claimToken)
        assertNotNull(claimed[0].claimedUntil)
    }

    @Test
    fun `claimDue skips paused and future jobs`() {
        val poiId = seedPoi()
        val activeWatch = seedWatch(poiId)
        val pausedWatchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        poi_id, target_dates, cadence_sec, trigger_kinds, status
                    ) VALUES (
                        ?, ARRAY['2026-07-04'::date], 60, ARRAY['atc'], 'paused'
                    ) RETURNING id
                    """.trimIndent(),
                    poiId,
                )!!.get("id", Long::class.java)
        val repo = AvailabilityJobRepo(ctx)
        repo.upsertForWatch(activeWatch, sampleIntent, 60, "active", now().minusSeconds(5))
        repo.upsertForWatch(pausedWatchId, sampleIntent, 60, "paused", now().minusSeconds(5))
        val futureWatchId =
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
        repo.upsertForWatch(futureWatchId, sampleIntent, 60, "active", now().plusMinutes(1))
        val claimed = repo.claimDue(now(), limit = 10, leaseDuration = Duration.ofSeconds(30))
        assertEquals(1, claimed.size)
        assertEquals(activeWatch, claimed[0].watchId)
    }

    @Test
    fun `release advances nextRunAt only with matching token`() {
        val watchId = seedWatch(seedPoi())
        val repo = AvailabilityJobRepo(ctx)
        repo.upsertForWatch(watchId, sampleIntent, 60, "active", now().minusMinutes(1))
        val claimed = repo.claimDue(now(), limit = 1, leaseDuration = Duration.ofSeconds(30))[0]
        val nextRun = now().plusMinutes(1)
        assertTrue(repo.release(claimed.id, claimed.claimToken!!, nextRun, now()))
        val after = repo.findById(claimed.id)!!
        assertNull(after.claimToken)
        assertNull(after.claimedUntil)
        assertEquals(nextRun.toEpochSecond(), after.nextRunAt.toEpochSecond())
        // Wrong token: no-op.
        assertFalse(repo.release(claimed.id, "wrong-token", nextRun.plusMinutes(1), now()))
    }

    @Test
    fun `reclaimExpired clears expired leases`() {
        val watchId = seedWatch(seedPoi())
        val repo = AvailabilityJobRepo(ctx)
        repo.upsertForWatch(watchId, sampleIntent, 60, "active", now().minusMinutes(1))
        repo.claimDue(now().minusMinutes(2), limit = 1, leaseDuration = Duration.ofSeconds(10))
        val reclaimed = repo.reclaimExpired(now())
        assertEquals(1, reclaimed)
        val after = repo.findByWatchId(watchId)!!
        assertNull(after.claimToken)
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
cd backend && ./gradlew test --tests AvailabilityJobRepoTest
```

Expected: 6/6 passing.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepoTest.kt
git commit -m "PR 2: AvailabilityJobRepo tests"
```

---

## Task 4: `Schedulable` + `Scheduler<T>` abstraction

**Files:**

- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/Schedulable.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/Scheduler.kt`

The scheduler is generic so the same loop can drive `availability_job` today and `ingest_runs` later (out of scope for PR 2). It owns: tick interval, claim batch size, lease duration, boot recovery, and exception isolation per job. It does NOT own: domain logic (handler decides what to do) or cadence (handler computes `nextRunAt` from row + clock).

- [ ] **Step 1: Create the Schedulable port**

```kotlin
package ca.floo.roadtrip.service.scheduler

import java.time.Duration
import java.time.OffsetDateTime

/**
 * Contract a scheduled-work table must satisfy. Each row is a unit of
 * work the scheduler can claim, hand to a handler, and re-schedule.
 *
 * Implementations live in `repo/` (for example, [AvailabilityJobRepo]
 * fronts `availability_job`).
 */
interface SchedulableRepo<T> {
    /**
     * Claim up to [limit] eligible rows by setting a fresh claim token
     * and a lease. Rows with expired leases are eligible for re-claim.
     * Implementations use `FOR UPDATE SKIP LOCKED` so concurrent ticks
     * never hand the same row to two callers.
     */
    fun claimDue(
        now: OffsetDateTime,
        limit: Int,
        leaseDuration: Duration,
    ): List<T>

    /**
     * Release a claim after the handler runs to completion (success or
     * caught failure). [nextRunAt] is the new schedule; the handler
     * computes it from the row's cadence + the run timestamp. Returns
     * false when the claim_token doesn't match, signalling the lease
     * was reclaimed by [reclaimExpired] mid-run.
     */
    fun release(
        id: Long,
        token: String,
        nextRunAt: OffsetDateTime,
        ranAt: OffsetDateTime,
    ): Boolean

    /**
     * Boot-time / periodic recovery: clear claim_token + claimed_until
     * on rows whose lease has expired without a release. Returns the
     * number of rows reset.
     */
    fun reclaimExpired(now: OffsetDateTime): Int
}

/**
 * Things a scheduled row carries that the scheduler reads. Intentionally
 * minimal — anything domain-specific stays inside the row type and is
 * read by the handler.
 */
interface Schedulable {
    val id: Long
    val claimToken: String?
}
```

- [ ] **Step 2: Create the Scheduler**

```kotlin
package ca.floo.roadtrip.service.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Result of one handler invocation. The handler returns the next
 * scheduling timestamp; the scheduler writes it back via
 * [SchedulableRepo.release] together with the run timestamp.
 *
 * Handlers should catch their own domain errors and return a usable
 * [HandlerResult] anyway; uncaught throwables are logged and the row's
 * lease is released with the original cadence so we don't lose the row.
 */
data class HandlerResult(
    val nextRunAt: OffsetDateTime,
)

/**
 * In-process scheduler. One instance per Schedulable type
 * (`Scheduler<AvailabilityJob>` for polling jobs, eventually
 * `Scheduler<IngestRun>` for ETLs).
 *
 * Owns: tick cadence, claim batch size, lease duration, boot recovery,
 * exception isolation. Does NOT own: domain logic (the handler) or
 * cadence math (handler returns next_run_at).
 */
class Scheduler<T : Schedulable>(
    private val repo: SchedulableRepo<T>,
    private val handler: suspend (T) -> HandlerResult,
    private val tickInterval: Duration = Duration.ofSeconds(5),
    private val claimBatchSize: Int = 10,
    private val leaseDuration: Duration = Duration.ofMinutes(2),
    private val name: String = "scheduler",
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger("Scheduler($name)")
    private var loop: Job? = null

    fun start(scope: CoroutineScope) {
        check(loop == null) { "scheduler already running" }
        log.info("scheduler {} starting (tick={}s, batch={}, lease={}s)", name, tickInterval.seconds, claimBatchSize, leaseDuration.seconds)
        // Recover before the first claim. If the app crashed mid-run, the
        // matching row's lease has not yet expired (we'd wait the full
        // lease duration); bumping reclaim here cuts that to zero.
        repo.reclaimExpired(now())
        loop = scope.launch { runLoop() }
    }

    suspend fun stop() {
        loop?.cancel()
        loop = null
    }

    private suspend fun runLoop() {
        while (currentScopeIsActive()) {
            try {
                val now = now()
                repo.reclaimExpired(now)
                val rows = repo.claimDue(now, claimBatchSize, leaseDuration)
                for (row in rows) {
                    runOne(row)
                }
            } catch (e: Exception) {
                log.error("scheduler tick failed: {}", e.message, e)
            }
            delay(tickInterval.toMillis())
        }
    }

    private suspend fun runOne(row: T) {
        val started = now()
        val result =
            try {
                handler(row)
            } catch (e: Exception) {
                log.error("handler failed for row id={}: {}", row.id, e.message, e)
                // Re-schedule with default cadence-of-failure: try again
                // after the lease window so we don't hot-loop on a broken
                // row. Concrete cadence is decided by the repo's row data;
                // here we just push it past the current lease.
                HandlerResult(nextRunAt = started.plus(leaseDuration))
            }
        // Release uses NonCancellable so a stop() during the handler
        // still flushes the schedule update. Otherwise a stop racing a
        // running handler would leave the row claimed until lease expiry.
        withContext(NonCancellable) {
            val token = row.claimToken
            if (token == null) {
                log.warn("row id={} had no claim token; cannot release", row.id)
                return@withContext
            }
            val released = repo.release(row.id, token, result.nextRunAt, started)
            if (!released) {
                log.warn("row id={} release rejected (token mismatch — lease was reclaimed)", row.id)
            }
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private suspend fun currentScopeIsActive(): Boolean = kotlin.coroutines.coroutineContext[Job]?.isActive ?: true
}
```

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/
git commit -m "PR 2: add generic Scheduler<T> abstraction"
```

---

## Task 5: `SchedulerTest` — generic behavior

**Files:**

- Create: `backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/SchedulerTest.kt`

Three tests against an in-memory fake `SchedulableRepo`. The point is to exercise the scheduler loop's behavior — claim → handler → release — without dragging Postgres into a unit test. Postgres-level claim semantics are already covered by `AvailabilityJobRepoTest`.

- [ ] **Step 1: Write the test**

```kotlin
package ca.floo.roadtrip.service.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private data class FakeJob(
    override val id: Long,
    override val claimToken: String?,
    val payload: String,
) : Schedulable

private class FakeRepo : SchedulableRepo<FakeJob> {
    val rows = mutableListOf<MutableMap<String, Any?>>()
    val released = mutableListOf<Triple<Long, OffsetDateTime, OffsetDateTime>>()

    fun add(
        id: Long,
        nextRunAt: OffsetDateTime,
        payload: String,
    ) {
        rows +=
            mutableMapOf(
                "id" to id,
                "claim_token" to null,
                "claimed_until" to null,
                "next_run_at" to nextRunAt,
                "payload" to payload,
            )
    }

    override fun claimDue(
        now: OffsetDateTime,
        limit: Int,
        leaseDuration: Duration,
    ): List<FakeJob> {
        val claimable =
            rows.filter {
                val due = it["next_run_at"] as OffsetDateTime
                val lease = it["claimed_until"] as OffsetDateTime?
                due <= now && (lease == null || lease < now)
            }.take(limit)
        val token = "tok-${now.toEpochSecond()}"
        for (row in claimable) {
            row["claim_token"] = token
            row["claimed_until"] = now.plus(leaseDuration)
        }
        return claimable.map { FakeJob(it["id"] as Long, it["claim_token"] as String, it["payload"] as String) }
    }

    override fun release(
        id: Long,
        token: String,
        nextRunAt: OffsetDateTime,
        ranAt: OffsetDateTime,
    ): Boolean {
        val row = rows.first { it["id"] == id }
        if (row["claim_token"] != token) return false
        row["claim_token"] = null
        row["claimed_until"] = null
        row["next_run_at"] = nextRunAt
        released += Triple(id, ranAt, nextRunAt)
        return true
    }

    override fun reclaimExpired(now: OffsetDateTime): Int {
        var count = 0
        for (row in rows) {
            val lease = row["claimed_until"] as OffsetDateTime?
            if (lease != null && lease < now) {
                row["claim_token"] = null
                row["claimed_until"] = null
                count += 1
            }
        }
        return count
    }
}

class SchedulerTest {
    @Test
    fun `due rows get handed to the handler`() =
        runBlocking {
            val repo = FakeRepo()
            val ranIds = mutableListOf<Long>()
            repo.add(1L, OffsetDateTime.now().minusSeconds(10), "a")
            repo.add(2L, OffsetDateTime.now().minusSeconds(10), "b")
            val done = CompletableDeferred<Unit>()
            val handler: suspend (FakeJob) -> HandlerResult = { row ->
                ranIds += row.id
                if (ranIds.size == 2) done.complete(Unit)
                HandlerResult(nextRunAt = OffsetDateTime.now().plusMinutes(1))
            }
            val scheduler =
                Scheduler(
                    repo = repo,
                    handler = handler,
                    tickInterval = Duration.ofMillis(20),
                    claimBatchSize = 5,
                    leaseDuration = Duration.ofSeconds(30),
                )
            coroutineScope {
                scheduler.start(this)
                withTimeout(2_000) { done.await() }
                scheduler.stop()
            }
            assertEquals(setOf(1L, 2L), ranIds.toSet())
            assertEquals(2, repo.released.size)
        }

    @Test
    fun `handler exception still releases the row`() =
        runBlocking {
            val repo = FakeRepo()
            repo.add(1L, OffsetDateTime.now().minusSeconds(10), "a")
            val attempts = AtomicInteger(0)
            val seen = AtomicReference<HandlerResult?>(null)
            val done = CompletableDeferred<Unit>()
            val handler: suspend (FakeJob) -> HandlerResult = {
                if (attempts.incrementAndGet() == 1) {
                    done.complete(Unit)
                    throw RuntimeException("boom")
                }
                HandlerResult(OffsetDateTime.now().plusMinutes(5)).also { seen.set(it) }
            }
            val scheduler =
                Scheduler(
                    repo = repo,
                    handler = handler,
                    tickInterval = Duration.ofMillis(20),
                    claimBatchSize = 1,
                    leaseDuration = Duration.ofSeconds(30),
                )
            coroutineScope {
                scheduler.start(this)
                withTimeout(2_000) { done.await() }
                // Give the release a moment to finish writing.
                delay(100)
                scheduler.stop()
            }
            assertEquals(1, repo.released.size)
            // Released even though the handler threw.
            assertNull(seen.get())
        }

    @Test
    fun `boot recovery clears expired leases`() {
        val repo = FakeRepo()
        repo.add(1L, OffsetDateTime.now().minusMinutes(5), "a")
        // Pretend a previous run claimed the row and crashed.
        repo.rows[0]["claim_token"] = "stale"
        repo.rows[0]["claimed_until"] = OffsetDateTime.now().minusSeconds(1)
        runBlocking {
            val scheduler =
                Scheduler(
                    repo = repo,
                    handler = { HandlerResult(OffsetDateTime.now().plusMinutes(1)) },
                    tickInterval = Duration.ofSeconds(60),
                    claimBatchSize = 1,
                    leaseDuration = Duration.ofSeconds(30),
                )
            coroutineScope {
                scheduler.start(this)
                delay(50)
                scheduler.stop()
            }
        }
        assertNull(repo.rows[0]["claim_token"])
    }
}
```

- [ ] **Step 2: Run tests**

```bash
cd backend && ./gradlew test --tests SchedulerTest
```

Expected: 3/3 passing.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/SchedulerTest.kt
git commit -m "PR 2: Scheduler tests"
```

---

## Task 6: `AvailabilityJob` adapts `AvailabilityJobRepo.Job` to `Schedulable`

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepo.kt` — make `Job` implement `Schedulable`, expose `AvailabilityJobRepo` as `SchedulableRepo<Job>`.

The cleanest way: have `AvailabilityJobRepo.Job` declare `id` and `claimToken` and the repo class itself implement `SchedulableRepo<Job>`. The earlier methods from Task 2 (`claimDue`, `release`, `reclaimExpired`) already match the interface signatures.

- [ ] **Step 1: Edit the repo to implement the interface**

In `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepo.kt`:

1. Add `import ca.floo.roadtrip.service.scheduler.Schedulable` and `import ca.floo.roadtrip.service.scheduler.SchedulableRepo` to the imports.
2. Change the class header from:

   ```kotlin
   class AvailabilityJobRepo(
       private val ctx: DSLContext,
   ) {
   ```

   to:

   ```kotlin
   class AvailabilityJobRepo(
       private val ctx: DSLContext,
   ) : SchedulableRepo<AvailabilityJobRepo.Job> {
   ```

3. Change the `Job` data class header from:

   ```kotlin
   data class Job(
       val id: Long,
       val watchId: Long,
   ```

   to:

   ```kotlin
   data class Job(
       override val id: Long,
       val watchId: Long,
   ```

   Then change the `val claimToken: String?,` line to `override val claimToken: String?,` and add `: Schedulable`:

   ```kotlin
   data class Job(
       override val id: Long,
       val watchId: Long,
       val intentPayload: JsonObject,
       val cadenceSec: Int,
       val status: String,
       val nextRunAt: OffsetDateTime,
       val claimedUntil: OffsetDateTime?,
       override val claimToken: String?,
       val lastRunAt: OffsetDateTime?,
       val createdAt: OffsetDateTime,
       val updatedAt: OffsetDateTime,
   ) : Schedulable
   ```

4. Add `override` to `claimDue`, `release`, `reclaimExpired`:

   ```kotlin
   override fun claimDue(
   override fun release(
   override fun reclaimExpired(
   ```

- [ ] **Step 2: Compile + tests still pass**

```bash
cd backend && ./gradlew compileKotlin test --tests AvailabilityJobRepoTest --tests SchedulerTest
```

Expected: BUILD SUCCESSFUL, 6 + 3 = 9 tests passing.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepo.kt
git commit -m "PR 2: AvailabilityJobRepo implements SchedulableRepo"
```

---

## Task 7: `AvailabilityJobIntent` — frozen polling intent shape

**Files:**

- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityJobIntent.kt`

The job's `intent_payload` JSONB has a strict shape so the worker doesn't have to parse a `Map<String, JsonElement>` ad-hoc. Two variants today, mirroring the watch's scope check:

- `Reservable` — single reservable, vendor + vendor_id frozen at intent time.
- `Poi` — POI scope; the worker would resolve children at run time (deferred to PR 3 when we wire actual POI fan-out).

PR 2 ships `Reservable` only. POI-scoped watches still create a job, but the executor surfaces a clear "not yet supported" log and re-schedules. This is intentional: schema lands now, fan-out logic lands with the worker work in PR 3.

- [ ] **Step 1: Create the intent module**

```kotlin
package ca.floo.roadtrip.service.availability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Frozen polling intent stored in `availability_job.intent_payload`. The
 * worker reads this and never reads back to `availability_watch`, so
 * editing a watch never retroactively changes what an in-flight run
 * polls. The watch service rebuilds and writes a fresh intent_payload
 * whenever any underlying watch field changes.
 *
 * Two variants today:
 *   - [Reservable]: poll one reservable's per-day availability.
 *   - [Poi]: POI-scoped watch — full fan-out lands in PR 3.
 *
 * `kind` is the discriminator used at the JSONB layer.
 */
@Serializable
sealed class AvailabilityJobIntent {
    abstract val targetDates: List<String>
    abstract val minNights: Int

    @Serializable
    @SerialName("reservable")
    data class Reservable(
        @SerialName("reservable_id") val reservableId: Long,
        @SerialName("reservable_rid") val reservableRid: String,
        @SerialName("target_dates") override val targetDates: List<String>,
        @SerialName("min_nights") override val minNights: Int,
    ) : AvailabilityJobIntent()

    @Serializable
    @SerialName("poi")
    data class Poi(
        @SerialName("poi_id") val poiId: Long,
        @SerialName("reservable_filters") val reservableFilters: JsonObject = JsonObject(emptyMap()),
        @SerialName("target_dates") override val targetDates: List<String>,
        @SerialName("min_nights") override val minNights: Int,
    ) : AvailabilityJobIntent()

    fun toJsonObject(): JsonObject = JSON.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        // Sealed class polymorphism is class-discriminator-by-default; the
        // SerialName values above become the "type" key value in the
        // emitted JSON. We use a fixed key name so the DB schema and the
        // generator-generated bindings agree.
        val JSON =
            Json {
                classDiscriminator = "kind"
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            }

        fun fromJsonObject(obj: JsonObject): AvailabilityJobIntent =
            JSON.decodeFromJsonElement(serializer(), obj)
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd backend && ./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityJobIntent.kt
git commit -m "PR 2: add AvailabilityJobIntent sealed class"
```

---

## Task 8: `AvailabilityWatchService` — atomic watch + job lifecycle

**Files:**

- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchService.kt`

The service is the only public seam for mutating watches. Routes call it; routes never call `AvailabilityWatchRepo` directly. Three responsibilities:

1. Create a watch + job atomically (transaction).
2. Update a watch + recompute job (transaction).
3. Delete a watch (cascade deletes the job via FK).

For status transitions: setting watch to `paused` parks the job's `next_run_at` to far-future (effectively pausing the scheduler). Resuming sets it to `now` so it polls on the next tick.

- [ ] **Step 1: Create the service**

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo.Watch
import ca.floo.roadtrip.repo.ReservableRepo
import org.jooq.DSLContext
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Mutates watches and keeps their backing job in sync. Single seam for
 * routes; routes never touch [AvailabilityWatchRepo] or
 * [AvailabilityJobRepo] for writes.
 *
 * All mutations transact across both tables so a watch is never visible
 * without its job.
 */
class AvailabilityWatchService(
    private val ctx: DSLContext,
    private val reservables: ReservableRepo,
) {
    private val parkedFar: OffsetDateTime = OffsetDateTime.parse("9999-01-01T00:00:00Z")

    fun create(input: AvailabilityWatchRepo.CreateInput): Watch =
        ctx.transactionResult { config ->
            val watchRepo = AvailabilityWatchRepo(org.jooq.impl.DSL.using(config))
            val jobRepo = AvailabilityJobRepo(org.jooq.impl.DSL.using(config))
            val watch = watchRepo.create(input)
            val intent = buildIntent(watch)
            val nextRun = if (watch.status == "active") OffsetDateTime.now() else parkedFar
            jobRepo.upsertForWatch(
                watchId = watch.id,
                intentPayload = intent.toJsonObject(),
                cadenceSec = watch.cadenceSec,
                status = watch.status,
                nextRunAt = nextRun,
            )
            watch
        }

    fun update(
        id: Long,
        input: AvailabilityWatchRepo.UpdateInput,
    ): Watch? =
        ctx.transactionResult { config ->
            val watchRepo = AvailabilityWatchRepo(org.jooq.impl.DSL.using(config))
            val jobRepo = AvailabilityJobRepo(org.jooq.impl.DSL.using(config))
            val updated = watchRepo.update(id, input) ?: return@transactionResult null
            val intent = buildIntent(updated)
            val nextRun =
                when (updated.status) {
                    "active" -> {
                        // If the watch was just resumed, kick the next run
                        // to "now" so polling restarts on the next tick.
                        // For an in-place edit (already active), keep the
                        // existing schedule by reusing the job's nextRunAt
                        // when present; otherwise default to now.
                        val existing = jobRepo.findByWatchId(updated.id)
                        if (existing == null || existing.status != "active" || existing.nextRunAt == parkedFar) {
                            OffsetDateTime.now()
                        } else {
                            existing.nextRunAt
                        }
                    }
                    "paused" -> parkedFar
                    else -> parkedFar
                }
            jobRepo.upsertForWatch(
                watchId = updated.id,
                intentPayload = intent.toJsonObject(),
                cadenceSec = updated.cadenceSec,
                status = updated.status,
                nextRunAt = nextRun,
            )
            updated
        }

    fun delete(id: Long): Boolean =
        ctx.transactionResult { config ->
            val watchRepo = AvailabilityWatchRepo(org.jooq.impl.DSL.using(config))
            // FK cascade deletes the matching availability_job row.
            watchRepo.delete(id)
        }

    private fun buildIntent(watch: Watch): AvailabilityJobIntent {
        val dates = watch.targetDates.map(LocalDate::toString)
        return if (watch.reservableId != null) {
            val r =
                reservables.findById(watch.reservableId)
                    ?: error("watch ${watch.id} references missing reservable ${watch.reservableId}")
            AvailabilityJobIntent.Reservable(
                reservableId = r.id,
                reservableRid = r.rid.encode(),
                targetDates = dates,
                minNights = watch.minNights,
            )
        } else {
            AvailabilityJobIntent.Poi(
                poiId = watch.poiId!!,
                reservableFilters = watch.reservableFilters,
                targetDates = dates,
                minNights = watch.minNights,
            )
        }
    }
}
```

`ReservableRepo.findById` — confirm the method name during implementation. From PR 1's reading, the existing public methods are `findByRid`, `search`, `findByPoi`, `poiIdsForReservable`. There may not be a `findById(Long)`; if not, add a minimal one in this task: open `backend/src/main/kotlin/ca/floo/roadtrip/repo/ReservableRepo.kt` and add:

```kotlin
fun findById(id: Long): ca.floo.roadtrip.models.Reservable? =
    ctx
        .selectFrom(RESERVABLES)
        .where(RESERVABLES.ID.eq(id))
        .fetchOne()
        ?.let(::fromRecord)
```

- [ ] **Step 2: Compile**

```bash
cd backend && ./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL. If `ReservableRepo.findById` was missing and you added it, that's expected.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchService.kt backend/src/main/kotlin/ca/floo/roadtrip/repo/ReservableRepo.kt
git commit -m "PR 2: AvailabilityWatchService — atomic watch + job mutations"
```

---

## Task 9: Routes go through the service

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt`

POST and PATCH handlers currently call `watches.create(...)` and `watches.update(...)` (the repo). Change them to call the service. DELETE goes through the service for symmetry. GET endpoints stay on the repo (read-only).

- [ ] **Step 1: Add service param to the route function**

Open `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt`. Change the function signature from:

```kotlin
fun Route.availabilityWatchRoutes(ctx: DSLContext) {
    val watches = AvailabilityWatchRepo(ctx)
    val reservables = ReservableRepo(ctx)
```

to:

```kotlin
fun Route.availabilityWatchRoutes(
    ctx: DSLContext,
    watchService: ca.floo.roadtrip.service.availability.AvailabilityWatchService,
) {
    val watches = AvailabilityWatchRepo(ctx)
    val reservables = ReservableRepo(ctx)
```

- [ ] **Step 2: POST goes through the service**

In the POST handler, replace the `watches.create(...)` call with `watchService.create(...)`. The argument shape (`AvailabilityWatchRepo.CreateInput`) stays the same.

Find:

```kotlin
        val watch =
            watches.create(
                AvailabilityWatchRepo.CreateInput(
                    poiId = resolved.poiId,
                    reservableId = resolved.reservableId,
                    ...
                ),
            )
```

Change `watches.create(` to `watchService.create(`.

- [ ] **Step 3: PATCH goes through the service**

In the PATCH handler, replace `watches.update(...)` with `watchService.update(...)`. Same arg shape.

Find:

```kotlin
                watches.update(
                    id,
                    AvailabilityWatchRepo.UpdateInput(
                        ...
                    ),
                )
```

Change `watches.update(` to `watchService.update(`.

- [ ] **Step 4: DELETE goes through the service**

In the DELETE handler, replace `watches.delete(id)` with `watchService.delete(id)`.

- [ ] **Step 5: Wire the service in `Main.kt`**

Open `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`. Add the import (alphabetical):

```kotlin
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
```

Inside `module()`, before the `routing { ... }` block, add:

```kotlin
    val availabilityWatchService = AvailabilityWatchService(ctx, ca.floo.roadtrip.repo.ReservableRepo(ctx))
```

Inside `routing { ... }`, change:

```kotlin
        availabilityWatchRoutes(ctx)
```

to:

```kotlin
        availabilityWatchRoutes(ctx, availabilityWatchService)
```

- [ ] **Step 6: Existing route tests need the service too**

Open `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt`. Inside each `application { routing { availabilityWatchRoutes(ctx) } }` call, change to:

```kotlin
application {
    routing {
        availabilityWatchRoutes(
            ctx,
            ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                ctx,
                ca.floo.roadtrip.repo.ReservableRepo(ctx),
            ),
        )
    }
}
```

There are five occurrences (one per test). Updating all of them is the right move; consider extracting a `private fun mountRoutes(...)` helper if it makes the diff cleaner.

- [ ] **Step 7: Run tests**

```bash
cd backend && ./gradlew test --tests AvailabilityWatchRoutesTest
```

Expected: 5/5 still passing — the service preserves the watch return shape exactly.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt backend/src/main/kotlin/ca/floo/roadtrip/Main.kt backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt
git commit -m "PR 2: route watch CRUD through AvailabilityWatchService"
```

---

## Task 10: Add a route test that exercises watch + job atomicity

**Files:**

- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt`

One new test that asserts: creating a watch via POST also creates exactly one `availability_job` row, and pausing the watch via PATCH parks the job's `next_run_at`.

- [ ] **Step 1: Add the test method**

Inside the `AvailabilityWatchRoutesTest` class, add:

```kotlin
@Test
fun `POST creates a job and PATCH paused parks it`() = testApplication {
    application {
        routing {
            availabilityWatchRoutes(
                ctx,
                ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                    ctx,
                    ca.floo.roadtrip.repo.ReservableRepo(ctx),
                ),
            )
        }
    }
    val poiId = seedPoi(sourceId = "p99", name = "Atomic")
    val createBody = """
        {"poi_id": $poiId, "target_dates": ["2026-07-04"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
    """.trimIndent()
    val created =
        client.post("/api/availability/watches") {
            contentType(ContentType.Application.Json)
            setBody(createBody)
        }
    val watchId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["watch"]!!.jsonObject["id"]!!.jsonPrimitive.long

    val jobs = ca.floo.roadtrip.repo.AvailabilityJobRepo(ctx)
    val job = jobs.findByWatchId(watchId)
    assertNotNull(job)
    assertEquals(60, job.cadenceSec)
    assertEquals("active", job.status)

    val paused =
        client.patch("/api/availability/watches/$watchId") {
            contentType(ContentType.Application.Json)
            setBody("""{"status": "paused"}""")
        }
    assertEquals(HttpStatusCode.OK, paused.status)
    val pausedJob = jobs.findByWatchId(watchId)!!
    assertEquals("paused", pausedJob.status)
    assertTrue(pausedJob.nextRunAt.year >= 9999)
}
```

Add the import for `assertTrue` and `assertNotNull` if not already present.

- [ ] **Step 2: Run test**

```bash
cd backend && ./gradlew test --tests AvailabilityWatchRoutesTest
```

Expected: 6/6 (5 original + 1 new) passing.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt
git commit -m "PR 2: route test for watch + job atomicity"
```

---

## Task 11: `AvailabilityPollExecutor` — the worker handler

**Files:**

- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt`

The executor is the function the scheduler hands each claimed job to. It:

1. Decodes `intent_payload` into an `AvailabilityJobIntent`.
2. For `Reservable` intent: resolves the reservable + booking provider (same as existing `CampsiteAvailabilityRoutes` does for its single-reservable endpoint), calls `BookingProvider.reservableAvailability(...)`, appends snapshot rows via `ReservableAvailabilityLogRepo.appendAvailabilityPoll`.
3. For `Poi` intent: log + skip (PR 3 wires fan-out).
4. Returns `HandlerResult(nextRunAt = now() + cadence_sec)` regardless of poll success — failures don't push the row off-schedule.

- [ ] **Step 1: Create the executor**

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableAvailabilityLogRepo
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
 * provider and appends snapshot rows. POI-scope: deferred to PR 3
 * (fan-out logic).
 *
 * Handler always returns a [HandlerResult] — even on upstream failure —
 * because losing the row would mean the watch silently stops polling.
 */
class AvailabilityPollExecutor(
    private val reservables: ReservableRepo,
    private val campsiteProviders: CampsiteProviderRepo,
    private val bookingProviders: BookingProviderRegistry,
    private val fetches: ReservableAvailabilityFetchService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(job: AvailabilityJobRepo.Job): HandlerResult {
        try {
            val intent = AvailabilityJobIntent.fromJsonObject(job.intentPayload)
            when (intent) {
                is AvailabilityJobIntent.Reservable -> runReservable(job.id, intent)
                is AvailabilityJobIntent.Poi -> {
                    log.info("job {} POI scope not yet executed (poi_id={})", job.id, intent.poiId)
                }
            }
        } catch (e: Exception) {
            log.warn("job {} failed: {}", job.id, e.message)
        }
        return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(job.cadenceSec.toLong()))
    }

    private suspend fun runReservable(
        jobId: Long,
        intent: AvailabilityJobIntent.Reservable,
    ) {
        val reservable =
            reservables.findById(intent.reservableId)
                ?: run {
                    log.warn("job {}: reservable {} no longer exists", jobId, intent.reservableId)
                    return
                }
        val poiIds = reservables.poiIdsForReservable(reservable.id)
        if (poiIds.isEmpty()) {
            log.warn("job {}: reservable {} has no POI parent", jobId, reservable.id)
            return
        }
        val refRowsById = campsiteProviders.findProviderRefs(poiIds)
        val parent =
            poiIds
                .asSequence()
                .mapNotNull { refRowsById[it] }
                .firstOrNull { bookingProviders.forPoi(it) != null && ProviderRefParser.parse(it.providerRefJson) != null }
        if (parent == null) {
            log.warn("job {}: reservable {} has no resolvable booking provider", jobId, reservable.id)
            return
        }
        val provider = bookingProviders.forPoi(parent)!!
        val ref = ProviderRefParser.parse(parent.providerRefJson)!!

        val firstDate = intent.targetDates.firstOrNull() ?: return
        val start = LocalDate.parse(firstDate)
        // Span the full window so a single fetch covers every target date.
        val days =
            intent.targetDates
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .maxOrNull()
                ?.let { java.time.temporal.ChronoUnit.DAYS.between(start, it).toInt() + 1 }
                ?: 1

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
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd backend && ./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt
git commit -m "PR 2: AvailabilityPollExecutor handler"
```

---

## Task 12: `AvailabilityPollExecutorTest` — DEFERRED

**Decision:** Skip unit tests for the executor in PR 2. Coverage instead:

- The route-level test in Task 10 confirms watch → job atomic creation.
- The smoke test in Task 14 exercises the full chain (job claim → fetch → snapshot rows).
- The executor itself is thin glue between already-tested components (`ReservableRepo`, `BookingProviderRegistry`, `ReservableAvailabilityFetchService`, `ReservableAvailabilityLogRepo`).

The cost-benefit doesn't justify adding MockK to `backend/build.gradle.kts` for two unit tests; the existing test stack (Testcontainers + Ktor `testApplication`) covers the seam at a higher level.

**Skip:** create no new files in this task. Move to Task 13.
---

## Task 13: Wire scheduler at boot

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`

The scheduler runs in a coroutine scope tied to the Ktor application lifecycle. Stopped on shutdown so tests don't leak threads.

- [ ] **Step 1: Add the imports + bootstrap**

Open `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`. Add these imports (alphabetical with the existing ones):

```kotlin
import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.ReservableAvailabilityLogRepo
import ca.floo.roadtrip.service.api.ReservableAvailabilityFetchService
import ca.floo.roadtrip.service.availability.AvailabilityPollExecutor
import ca.floo.roadtrip.service.scheduler.Scheduler
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
```

Inside `module()`, after the `availabilityWatchService` line but before `routing { ... }`, add:

```kotlin
    // Availability poller. One Scheduler<AvailabilityJob> ticks every few
    // seconds, claims due jobs, calls AvailabilityPollExecutor, and writes
    // snapshot rows. Cancelled on app shutdown so tests don't leak threads.
    val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val availabilityJobs = AvailabilityJobRepo(ctx)
    val availabilityFetches =
        ReservableAvailabilityFetchService(
            availabilityLogs = ReservableAvailabilityLogRepo(ctx),
        )
    val pollExecutor =
        AvailabilityPollExecutor(
            reservables = ca.floo.roadtrip.repo.ReservableRepo(ctx),
            campsiteProviders = CampsiteProviderRepo(ctx),
            bookingProviders = bookingProviderRegistry,
            fetches = availabilityFetches,
        )
    val availabilityScheduler =
        Scheduler(
            repo = availabilityJobs,
            handler = pollExecutor::handle,
            name = "availability",
        )
    availabilityScheduler.start(schedulerScope)
    environment.monitor.subscribe(ApplicationStopping) {
        schedulerScope.cancel()
    }
```

- [ ] **Step 2: Compile + run all tests**

```bash
cd backend && ./gradlew compileKotlin test
```

Expected: BUILD SUCCESSFUL, full test suite green.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/Main.kt
git commit -m "PR 2: bootstrap availability scheduler"
```

---

## Task 14: Smoke test in dev

**Files:** none (manual verification)

- [ ] **Step 1: Restart Tilt**

```bash
tilt down
tilt up
```

Wait for backend logs to show `scheduler availability starting`.

- [ ] **Step 2: Create a watch via the FE**

Open `http://localhost:8765/reservables`, expand availability for any reservable, click "Create watch". On the prefilled watches page, click Create.

- [ ] **Step 3: Verify the job was created and is running**

```bash
psql "$ROADTRIP_DB_URL" -c "SELECT id, watch_id, cadence_sec, status, next_run_at, last_run_at FROM availability_job ORDER BY id DESC LIMIT 5;"
```

Expected: a row for your watch with `status='active'`, `last_run_at` advancing every `cadence_sec` seconds.

- [ ] **Step 4: Verify snapshots are landing**

```bash
psql "$ROADTRIP_DB_URL" -c "SELECT count(*) FROM reservable_availability_log WHERE observed_at > now() - interval '5 minutes';"
```

Expected: rows accumulating proportional to `(target_dates × ticks)`.

- [ ] **Step 5: Pause the watch via the FE → confirm polling stops**

In the watches page, click the pause button on your watch row. Wait `cadence_sec + 30s`, then re-check `SELECT last_run_at FROM availability_job WHERE watch_id = <id>` — it should not advance after the pause.

- [ ] **Step 6: Delete the watch → confirm the job is gone**

```bash
psql "$ROADTRIP_DB_URL" -c "SELECT count(*) FROM availability_job WHERE watch_id = <id>;"
```

Expected: 0 rows.

If any step fails, capture the failure and stop — don't paper over with code edits.

---

## Task 15: Lint, push, open PR

- [ ] **Step 1: Format + ktlint**

```bash
cd backend && ./gradlew ktlintFormat && ./gradlew ktlintCheck
```

Expected: green.

- [ ] **Step 2: Full test suite**

```bash
cd backend && ./gradlew test
```

Expected: green.

- [ ] **Step 3: Commit any ktlintFormat changes**

```bash
git add backend/src
git commit -m "PR 2: ktlintFormat" || true
```

- [ ] **Step 4: Push and open PR**

```bash
git push -u origin availability-watches-redesign
```

Open a stacked PR against `availability-watches-redesign` (the PR 1 branch) — when PR 1 lands, this auto-rebases onto master.

Write the PR body to a file first:

```bash
cat > pr_body.md <<'PR'
## PR 2: Availability jobs + scheduler

Stacks on PR #226. Adds the polling worker — watches now actually poll.

### What ships
- V15 migration: `availability_job` table (one row per active watch).
- `AvailabilityJobRepo` + `Schedulable`/`Scheduler<T>` abstraction.
- `AvailabilityWatchService`: routes mutate watches and jobs atomically.
- `AvailabilityPollExecutor`: handler that fetches per-day availability through `BookingProvider.reservableAvailability` and appends snapshots.
- Bootstrap in `Main.kt`: one `Scheduler<AvailabilityJob>` per app instance.

### Scope
- POI-scoped watches still create a job, but the executor logs and skips. POI fan-out lands in PR 3.
- No UI changes. Operator visibility (`/availability` dashboard) is PR 3.

### Verification
- `./gradlew ktlintCheck test`
- Manual smoke per docs/superpowers/plans/2026-06-15-pr2-avail-jobs.md Task 14.
PR

gh pr create --title "PR 2: avail jobs + scheduler" --body-file pr_body.md --base availability-watches-redesign
rm pr_body.md
```

- [ ] **Step 5: Verify CI**

```bash
gh pr checks
```

Expected: all green.

---

## Out of scope (deferred)

- **POI fan-out.** The POI-scoped intent variant is wired but the executor just logs. PR 3 fills it in.
- **`availability_job_run` table.** Right now we update `last_run_at` on the job; we don't record per-run details. PR 3 adds the runs table and the operator dashboard.
- **Notifications / dispatches.** A successful poll doesn't fire any side-effects yet. PR 5 adds `availability_dispatch` + the companion-facing HTTP outbox.
- **ETL migration to Scheduler.** `Scheduler<T>` is generic enough to drive `ingest_runs`; we don't migrate yet. Out of scope for PR 2.
