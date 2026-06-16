# Availability Snapshots Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename `reservable_availability_log` to `availability_snapshot`, replace stringly-typed `reservable_rid` with `reservable_id` FK to `reservables`, and add `run_id` FK to `availability_job_run`. After this, snapshots are first-class joinable rows and `availability_job_run.snapshot_count` becomes derivable via SQL (kept for now as a denormalized counter).

**Architecture:** One Flyway migration that renames the table, adds two FK columns, backfills `reservable_id` from the existing `reservable_rid` strings via a join through `reservables`, drops the now-redundant text column, swaps the indexes. Repo, service, and tests rename and re-key around the new shape. Executor populates `run_id` so snapshots are linkable to their producing run.

**Tech Stack:** Kotlin/Ktor, jOOQ + Flyway + Postgres, Testcontainers Postgres for tests.

**Reference docs:** `docs/superpowers/specs/2026-06-15-availability-watches-design.md` (entity model — see "AvailSnapshot — what we observed"), prior PRs in the stack: PR 1 (#226 watches), PR 2 (#227 jobs + scheduler), PR 3 (#228 job runs).

**Stack base:** Branch from `avail-job-runs` (PR #228).

---

## File map

**Created:**

- `backend/src/main/resources/db/migration/V17__avail_snapshots.sql`
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt`

**Modified:**

- `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt` — replace `ReservableAvailabilityLogRepo` references with `AvailabilitySnapshotRepo`.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/api/ReservableAvailabilityFetchService.kt` — accept `AvailabilitySnapshotRepo` and pass `reservable_id` (resolved from RID) + optional `run_id` when appending. The fetch service exposes a way to thread the active run id through; the executor passes it in.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt` — pass the active `runId` to the fetch service so snapshots get linked to their producing run.
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/CampsiteAvailabilityRoutes.kt` — swap the repo type at the wire-up site.
- `backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt` — update SELECT statements and assertions to the new table/column names.

**Deleted:**

- `backend/src/main/kotlin/ca/floo/roadtrip/repo/ReservableAvailabilityLogRepo.kt` (replaced by `AvailabilitySnapshotRepo`).

**Untouched:**

- `availability_job_run.snapshot_count` stays as a denormalized counter. Sql derivation is now possible (`SELECT count(*) FROM availability_snapshot WHERE run_id = ?`) but we keep the column to avoid touching the executor's count-tracking and to keep dashboard queries cheap. The denormalization stops being load-bearing once the dashboard ships.
- FE — deferred to a later PR.
- `availability_watch` and `availability_job` schemas — unchanged.

---

## Task 1: Migration V17 — rename and re-key the table

**Files:**

- Create: `backend/src/main/resources/db/migration/V17__avail_snapshots.sql`

The migration must:

1. Rename `reservable_availability_log` → `availability_snapshot`.
2. Add `reservable_id BIGINT` (initially nullable for backfill).
3. Add `run_id BIGINT REFERENCES availability_job_run(id) ON DELETE SET NULL` (nullable; ad-hoc availability fetches outside a job run still write rows).
4. Backfill `reservable_id` by parsing `reservable_rid` (`{type}:{vendor}:{vendor_id}`) and joining through `reservables`. Rows that don't match are kept (vendor renames, deleted reservables) — `reservable_id` stays NULL.
5. Make `reservable_id NOT NULL` *only after backfill confirms zero unmatched rows in dev*. To avoid a scary one-shot migration in prod, this PR keeps the column **nullable** and requires the executor to populate it on new rows. If unmatched rows exist after a sweep, that's data quality, not a migration failure.
6. Drop `reservable_rid TEXT`.
7. Replace the two old indexes (`reservable_rid, target_date, observed_at` and `reservable_rid, observed_at`) with equivalents keyed on `reservable_id`.
8. Add a `run_id` index for "snapshots produced by this run" lookups.

- [ ] **Step 1: Write the migration**

```sql
-- Rename reservable_availability_log to availability_snapshot.
--
-- Replaces stringly-typed reservable_rid with a real reservable_id FK so
-- joins against reservables work without parsing composite RID strings.
-- Adds run_id FK to availability_job_run so snapshots are linkable to
-- the run that produced them; this also makes availability_job_run
-- snapshot_count derivable via SQL when needed.
--
-- Backfill: parse {type}:{vendor}:{vendor_id} from reservable_rid and
-- look up reservables.id. Unmatched rows (vendor renamed, reservable
-- deleted) keep reservable_id NULL — that's data-quality, not migration
-- failure. New rows written by the executor populate reservable_id
-- directly from the in-memory Reservable.

ALTER TABLE reservable_availability_log
  RENAME TO availability_snapshot;

-- Old indexes reference the old table name; rename so future index
-- changes don't fight Postgres' "index name doesn't match table" naming
-- conventions.
ALTER INDEX reservable_availability_log_rid_target_observed_idx
  RENAME TO availability_snapshot_old_rid_target_observed_idx;
ALTER INDEX reservable_availability_log_rid_observed_idx
  RENAME TO availability_snapshot_old_rid_observed_idx;

-- Add the FK columns. Both nullable: reservable_id stays nullable so
-- unmatched backfills don't block the migration; run_id is nullable
-- because ad-hoc availability fetches (the existing route) write
-- snapshots outside any job run.
ALTER TABLE availability_snapshot
  ADD COLUMN reservable_id BIGINT REFERENCES reservables(id) ON DELETE SET NULL,
  ADD COLUMN run_id BIGINT REFERENCES availability_job_run(id) ON DELETE SET NULL;

-- Backfill reservable_id by parsing the composite RID and joining
-- reservables on (type, vendor, vendor_id). split_part with 3 fields
-- handles the standard shape; vendor_ids that contain ':' are rare in
-- existing data but get NULL here (operator can re-fetch if needed).
UPDATE availability_snapshot s
SET reservable_id = r.id
FROM reservables r
WHERE r.type      = split_part(s.reservable_rid, ':', 1)
  AND r.vendor    = split_part(s.reservable_rid, ':', 2)
  AND r.vendor_id = split_part(s.reservable_rid, ':', 3);

-- Drop the stringly-typed column. After this, reservable_rid is no
-- longer queryable; reads go through the FK.
ALTER TABLE availability_snapshot
  DROP COLUMN reservable_rid;

-- New indexes mirror the old query shapes but on the FK. The old
-- "_old_*" indexes from the rename above are now stale (column gone)
-- and Postgres dropped them automatically when reservable_rid was
-- removed; nothing to clean up explicitly.
CREATE INDEX availability_snapshot_reservable_target_observed_idx
  ON availability_snapshot (reservable_id, target_date, observed_at DESC);

CREATE INDEX availability_snapshot_reservable_observed_idx
  ON availability_snapshot (reservable_id, observed_at DESC);

-- Hot path for the future runs dashboard: "what snapshots did this run
-- produce?" Partial index keeps it small for ad-hoc rows where run_id
-- is NULL.
CREATE INDEX availability_snapshot_run_idx
  ON availability_snapshot (run_id)
  WHERE run_id IS NOT NULL;
```

- [ ] **Step 2: Verify migration applies cleanly**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD FAILED on `Main.kt` / `CampsiteAvailabilityRoutes.kt` / etc. because the old class name is still referenced. That's intentional — Tasks 2-7 swap them.

The migration itself should apply cleanly inside the gradle codegen step; jOOQ regenerates `AvailabilitySnapshot` and the old `ReservableAvailabilityLog` is gone.

If the migration syntax is wrong, the codegen step itself fails before the Kotlin compile failure. Distinguish: "Flyway migration failed" → fix the SQL; "Kotlin can't resolve `ReservableAvailabilityLogRepo`" → expected, fix in later tasks.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V17__avail_snapshots.sql
git commit -m "Rename reservable_availability_log to availability_snapshot; add reservable_id and run_id FKs"
```

---

## Task 2: `AvailabilitySnapshotRepo`

**Files:**

- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt`

The new repo replaces `ReservableAvailabilityLogRepo`. Key shape changes:

- `appendAvailabilityPoll` now takes a `reservableId: Long` and an optional `runId: Long?`. Callers pass the ID instead of the rid string.
- The data-class for ingest input is `SnapshotBatch` with `reservableId`, `runId`, and `response: AvailabilityResponseDto`.
- Lookups by reservable use the FK.

- [ ] **Step 1: Create the new repo**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.Companion.AVAILABILITY_SNAPSHOT
import ca.floo.roadtrip.service.api.AvailabilityDayDto
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.availabilityResponseJson
import kotlinx.serialization.encodeToString
import org.jooq.DSLContext
import org.jooq.JSONB
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Append-only per-day single-night availability snapshots. Replaces the
 * earlier reservable_availability_log table.
 *
 * One snapshot per (reservable_id, target_date, observed_at). Multi-
 * night availability is derived by combining consecutive target_date
 * rows from the same observed_at batch — the executor stores
 * single-night data even when the request was multi-night, so the
 * snapshot timeline shows real per-day state regardless of the original
 * query's min_nights.
 */
class AvailabilitySnapshotRepo(
    private val ctx: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class SnapshotBatch(
        val reservableId: Long,
        val runId: Long?,
        val response: AvailabilityResponseDto,
    )

    fun appendBatch(input: SnapshotBatch): Int {
        if (input.response.availability.isEmpty()) return 0

        val observedAt = OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val inserts =
            input.response.availability.map { day ->
                ctx
                    .insertInto(AVAILABILITY_SNAPSHOT)
                    .set(AVAILABILITY_SNAPSHOT.RESERVABLE_ID, input.reservableId)
                    .set(AVAILABILITY_SNAPSHOT.RUN_ID, input.runId)
                    .set(AVAILABILITY_SNAPSHOT.OBSERVED_AT, observedAt)
                    .set(AVAILABILITY_SNAPSHOT.TARGET_DATE, LocalDate.parse(day.date))
                    .set(AVAILABILITY_SNAPSHOT.STATUS, day.status)
                    .set(AVAILABILITY_SNAPSHOT.AVAILABLE, day.availableCount > 0)
                    .set(AVAILABILITY_SNAPSHOT.DAY_PAYLOAD, JSONB.valueOf(day.toJson()))
            }
        ctx.batch(inserts).execute()
        return inserts.size
    }

    private fun AvailabilityDayDto.toJson(): String =
        availabilityResponseJson.encodeToString(AvailabilityDayDto.serializer(), this)
}
```

- [ ] **Step 2: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD FAILED on the call sites that still reference the old repo. Fixed in later tasks.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt
git commit -m "Add AvailabilitySnapshotRepo"
```

---

## Task 3: Update `ReservableAvailabilityFetchService` to use the new repo

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/ReservableAvailabilityFetchService.kt`

The fetch service is the single seam where availability fetches happen. PR 2 wired it for the executor (with optional `availabilityLogs`) and PR 1's existing campsite route also uses it. Two changes:

1. Constructor: `availabilityLogs: ReservableAvailabilityLogRepo?` becomes `snapshots: AvailabilitySnapshotRepo?`.
2. `Request` gains a `reservableId: Long` and an optional `runId: Long?`. The existing `reservableRid: String` field stays — it's used for logging in the warn path.
3. The append call uses `reservableId` instead of `reservableRid`.

- [ ] **Step 1: Replace the file content**

Open `backend/src/main/kotlin/ca/floo/roadtrip/service/api/ReservableAvailabilityFetchService.kt`. Replace the full content with:

```kotlin
package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.ReservableAvailabilityRequest
import org.slf4j.LoggerFactory
import java.time.LocalDate

class ReservableAvailabilityFetchService(
    private val snapshots: AvailabilitySnapshotRepo? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Request(
        val reservableId: Long,
        val reservableRid: String,
        val provider: BookingProvider,
        val ref: ProviderRef,
        val vendorId: String,
        val start: LocalDate,
        val days: Int,
        val minNights: Int,
        val force: Boolean,
        val runId: Long? = null,
    )

    suspend fun fetch(request: Request): AvailabilityResponseDto {
        val response =
            request.provider.reservableAvailability(
                ReservableAvailabilityRequest(
                    ref = request.ref,
                    vendorId = request.vendorId,
                    start = request.start,
                    days = request.days,
                    minNights = request.minNights,
                    force = request.force,
                ),
            )
        appendBaseAvailabilitySnapshot(request, response)
        return response
    }

    private suspend fun appendBaseAvailabilitySnapshot(
        request: Request,
        response: AvailabilityResponseDto,
    ) {
        val sink = snapshots ?: return
        try {
            // For multi-night requests we re-fetch with min_nights=1 so the
            // snapshot timeline records real per-day state, not the
            // collapsed multi-night view.
            val snapshotResponse =
                if (request.minNights == 1) {
                    response
                } else {
                    request.provider.reservableAvailability(
                        ReservableAvailabilityRequest(
                            ref = request.ref,
                            vendorId = request.vendorId,
                            start = request.start,
                            days = request.days + request.minNights - 1,
                            minNights = 1,
                            force = false,
                        ),
                    )
                }
            sink.appendBatch(
                AvailabilitySnapshotRepo.SnapshotBatch(
                    reservableId = request.reservableId,
                    runId = request.runId,
                    response = snapshotResponse,
                ),
            )
        } catch (e: Exception) {
            log.warn(
                "availability snapshot append failed reservable_id={} rid={}: {}",
                request.reservableId,
                request.reservableRid,
                e.message,
            )
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD FAILED on call sites. Fixed in Tasks 4-5.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/api/ReservableAvailabilityFetchService.kt
git commit -m "ReservableAvailabilityFetchService: use AvailabilitySnapshotRepo, take reservable_id and run_id"
```

---

## Task 4: Update `AvailabilityPollExecutor` to pass `reservableId` + `runId`

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt`

The executor already has both: it resolved `reservable.id` to know which reservable to fetch, and it has the active `runId` from `runs.start(...)`. Currently it passes only `reservable.rid.encode()` as `reservableRid`. We need to plumb the `runId` and `reservableId` through.

The shape change inside `runReservable`:

- `runReservable` currently does not see the `runId`. It needs to take it as a parameter so it can pass it down.
- The `handle` method already has `runId` in scope; pass it.

- [ ] **Step 1: Edit `runReservable` signature and `fetches.fetch(...)` call**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt`:

Change `runReservable`'s signature from:

```kotlin
    private suspend fun runReservable(
        jobId: Long,
        intent: AvailabilityJobIntent.Reservable,
    ): Int {
```

to:

```kotlin
    private suspend fun runReservable(
        jobId: Long,
        runId: Long,
        intent: AvailabilityJobIntent.Reservable,
    ): Int {
```

Update its caller inside `handle`:

```kotlin
                    is AvailabilityJobIntent.Reservable -> runReservable(job.id, intent)
```

becomes:

```kotlin
                    is AvailabilityJobIntent.Reservable -> runReservable(job.id, runId, intent)
```

Then change the `fetches.fetch(...)` call inside `runReservable`:

```kotlin
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
```

becomes:

```kotlin
        val response =
            fetches.fetch(
                ReservableAvailabilityFetchService.Request(
                    reservableId = reservable.id,
                    reservableRid = reservable.rid.encode(),
                    provider = provider,
                    ref = ref,
                    vendorId = reservable.rid.vendorId,
                    start = start,
                    days = days,
                    minNights = intent.minNights,
                    force = false,
                    runId = runId,
                ),
            )
```

- [ ] **Step 2: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD FAILED on `Main.kt` and `CampsiteAvailabilityRoutes.kt` for the old repo type. Fixed in Tasks 5-6.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt
git commit -m "Executor: pass reservable_id and run_id to fetch service"
```

---

## Task 5: Update `CampsiteAvailabilityRoutes` to use the new repo

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/CampsiteAvailabilityRoutes.kt`

The existing route wires `ReservableAvailabilityLogRepo` into the fetch service for ad-hoc reservable availability fetches. Two changes:

1. Function signature: take `AvailabilitySnapshotRepo` instead of `ReservableAvailabilityLogRepo`.
2. The existing `Request` construction must pass `reservableId` (already in scope as `row.id`). `runId` stays null — these are ad-hoc fetches, not scheduled job runs.

- [ ] **Step 1: Find and read the wire-up site**

```bash
grep -n "ReservableAvailabilityLogRepo\|reservableAvailabilityFetches\|ReservableAvailabilityFetchService.Request" backend/src/main/kotlin/ca/floo/roadtrip/routes/CampsiteAvailabilityRoutes.kt
```

The result shows where to edit. The function signature is around line 30, and the `Request` construction is around line 295.

- [ ] **Step 2: Edit the function signature**

Change the import from:

```kotlin
import ca.floo.roadtrip.repo.ReservableAvailabilityLogRepo
```

to:

```kotlin
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
```

Change the function signature from:

```kotlin
fun Route.campsiteAvailabilityRoutes(
    campsiteProviderRepo: CampsiteProviderRepo,
    bookingProviders: BookingProviderRegistry,
    reservables: ReservableRepo,
    availabilityLogs: ReservableAvailabilityLogRepo,
)
```

to:

```kotlin
fun Route.campsiteAvailabilityRoutes(
    campsiteProviderRepo: CampsiteProviderRepo,
    bookingProviders: BookingProviderRegistry,
    reservables: ReservableRepo,
    snapshots: AvailabilitySnapshotRepo,
)
```

(Use the actual parameter name; the search above shows the exact current shape.)

Change the fetch service construction from:

```kotlin
val reservableAvailabilityFetches = ReservableAvailabilityFetchService(availabilityLogs)
```

to:

```kotlin
val reservableAvailabilityFetches = ReservableAvailabilityFetchService(snapshots)
```

- [ ] **Step 3: Edit the `Request` construction**

In the same file, find the existing `Request(...)` and add `reservableId = row.id,` (matching the pattern in Task 4's executor edit). Leave `runId` unset (defaults to null per the data class default).

The existing snippet around line 295:

```kotlin
            val response =
                reservableAvailabilityFetches.fetch(
                    ReservableAvailabilityFetchService.Request(
                        reservableRid = rid.encode(),
                        provider = provider,
                        ref = ref,
                        vendorId = rid.vendorId,
                        start = query.start,
                        days = days,
                        minNights = query.minNights,
                        force = query.force,
                    ),
                )
```

becomes:

```kotlin
            val response =
                reservableAvailabilityFetches.fetch(
                    ReservableAvailabilityFetchService.Request(
                        reservableId = row.id,
                        reservableRid = rid.encode(),
                        provider = provider,
                        ref = ref,
                        vendorId = rid.vendorId,
                        start = query.start,
                        days = days,
                        minNights = query.minNights,
                        force = query.force,
                    ),
                )
```

`row.id` is the resolved `Reservable` row's PK, in scope at this site (it's the same `row` whose poi_ids feed the `parent` lookup just above). If the variable name differs, use whatever the file actually has.

- [ ] **Step 4: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD FAILED only on `Main.kt` (old import) — fixed in Task 6.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/routes/CampsiteAvailabilityRoutes.kt
git commit -m "CampsiteAvailabilityRoutes: take AvailabilitySnapshotRepo and pass reservable_id"
```

---

## Task 6: Update `Main.kt` and delete the old repo

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/repo/ReservableAvailabilityLogRepo.kt`

`Main.kt` constructs `ReservableAvailabilityLogRepo(ctx)` in two places (per the grep at the start of this plan). Both become `AvailabilitySnapshotRepo(ctx)`.

- [ ] **Step 1: Update imports**

Open `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`. Replace:

```kotlin
import ca.floo.roadtrip.repo.ReservableAvailabilityLogRepo
```

with:

```kotlin
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
```

- [ ] **Step 2: Update both constructor calls**

The two existing call sites are around lines 204 and 249 (per grep). The first is for the executor's fetch service (`availabilityLogs = ReservableAvailabilityLogRepo(ctx)` becomes `snapshots = AvailabilitySnapshotRepo(ctx)`); the second is for `campsiteAvailabilityRoutes(...)` (positional argument; just change `ReservableAvailabilityLogRepo(ctx)` to `AvailabilitySnapshotRepo(ctx)`).

Read the file at those line numbers and update accordingly.

- [ ] **Step 3: Delete the old repo file**

```bash
git rm backend/src/main/kotlin/ca/floo/roadtrip/repo/ReservableAvailabilityLogRepo.kt
```

- [ ] **Step 4: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/Main.kt
git commit -m "Main: wire AvailabilitySnapshotRepo; drop ReservableAvailabilityLogRepo"
```

---

## Task 7: Update `ReservableRoutesTest`

**Files:**

- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt`

The test currently:

- Imports `ReservableAvailabilityLogRepo`.
- `BeforeEach`: `DELETE FROM reservable_availability_log`.
- Uses `ReservableAvailabilityLogRepo(ctx)` when wiring `campsiteAvailabilityRoutes`.
- Asserts on raw SQL: `SELECT reservable_rid, target_date, status, available, day_payload FROM reservable_availability_log ORDER BY target_date` and `SELECT count(*) FROM reservable_availability_log`.

All four sites need updating. The assertion change is the trickiest — `reservable_rid` is gone, so the test needs to assert on the joined `reservable_id` (or assert via a join through `reservables`).

- [ ] **Step 1: Update import**

Replace:

```kotlin
import ca.floo.roadtrip.repo.ReservableAvailabilityLogRepo
```

with:

```kotlin
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
```

- [ ] **Step 2: Update `BeforeEach`**

Find:

```kotlin
        ctx.execute("DELETE FROM reservable_availability_log")
```

Replace with:

```kotlin
        ctx.execute("DELETE FROM availability_snapshot")
```

- [ ] **Step 3: Update the route wiring**

Find the existing call around line 334:

```kotlin
                    campsiteAvailabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeBookingProviders(),
                        ReservableRepo(ctx),
                        ReservableAvailabilityLogRepo(ctx),
                    )
```

Replace the last argument:

```kotlin
                    campsiteAvailabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeBookingProviders(),
                        ReservableRepo(ctx),
                        AvailabilitySnapshotRepo(ctx),
                    )
```

- [ ] **Step 4: Update the assertion query**

Find around line 354-374:

```kotlin
            val logRows =
                ctx.fetch(
                    """
                    SELECT reservable_rid, target_date, status, available, day_payload
                    FROM reservable_availability_log
                    ORDER BY target_date
                    """.trimIndent(),
                )
            assertEquals(2, logRows.size)
            assertEquals("site:recgov:330257", logRows[0].get("reservable_rid", String::class.java))
            assertEquals(java.time.LocalDate.parse("2026-07-01"), logRows[0].get("target_date", java.time.LocalDate::class.java))
            assertEquals("available", logRows[0].get("status", String::class.java))
            assertEquals(true, logRows[0].get("available", Boolean::class.java))
            assertEquals(
                "2026-07-01",
                Json
                    .parseToJsonElement(logRows[0].get("day_payload").toString())
                    .jsonObject["day_payload" /* keep verbatim per existing test */].toString(),
            )
```

(The original test references `"date"` inside day_payload — confirm by re-reading the file before editing.)

Replace with a query that joins through `reservables` so the assertion still references the human-readable rid:

```kotlin
            val logRows =
                ctx.fetch(
                    """
                    SELECT
                        r.type || ':' || r.vendor || ':' || r.vendor_id AS reservable_rid,
                        s.target_date,
                        s.status,
                        s.available,
                        s.day_payload
                    FROM availability_snapshot s
                    JOIN reservables r ON r.id = s.reservable_id
                    ORDER BY s.target_date
                    """.trimIndent(),
                )
            assertEquals(2, logRows.size)
            assertEquals("site:recgov:330257", logRows[0].get("reservable_rid", String::class.java))
            assertEquals(java.time.LocalDate.parse("2026-07-01"), logRows[0].get("target_date", java.time.LocalDate::class.java))
            assertEquals("available", logRows[0].get("status", String::class.java))
            assertEquals(true, logRows[0].get("available", Boolean::class.java))
            assertEquals(
                "2026-07-01",
                Json
                    .parseToJsonElement(logRows[0].get("day_payload").toString())
                    .jsonObject["date"]!!
                    .jsonPrimitive
                    .content,
            )
```

(The `day_payload["date"]` access here matches the original test; confirm the literal key during implementation.)

- [ ] **Step 5: Update the count assertion**

Find around line 380:

```kotlin
            val rowCountAfterMultiNight =
                ctx
                    .fetchOne("SELECT count(*) FROM reservable_availability_log")!!
                    .get(0, Long::class.java)
```

Replace with:

```kotlin
            val rowCountAfterMultiNight =
                ctx
                    .fetchOne("SELECT count(*) FROM availability_snapshot")!!
                    .get(0, Long::class.java)
```

- [ ] **Step 6: Run the test**

```bash
cd backend
./gradlew test --tests ReservableRoutesTest
```

Expected: all tests pass. The existing snapshot count expectations don't change (the executor still writes one row per target_date per fetch).

- [ ] **Step 7: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt
git commit -m "ReservableRoutesTest: rename to availability_snapshot, join for rid"
```

---

## Task 8: Update internal references in executor comments

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt`

The executor has a comment referencing `reservable_availability_log` and `appendAvailabilityPoll`. Update so future readers find the right symbols.

- [ ] **Step 1: Find and update**

The block currently reads:

```kotlin
        // Each day in the response window is one snapshot row in
        // reservable_availability_log (ReservableAvailabilityFetchService
        // calls appendAvailabilityPoll on the full response).
        return response.availability.size
```

Replace with:

```kotlin
        // Each day in the response window is one snapshot row in
        // availability_snapshot (ReservableAvailabilityFetchService
        // calls AvailabilitySnapshotRepo.appendBatch on the full response).
        return response.availability.size
```

- [ ] **Step 2: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt
git commit -m "Executor comment: reflect new snapshot table and method names"
```

---

## Task 9: Run the full backend test suite + lint

- [ ] **Step 1: Stop the gradle daemon to avoid stale-cache failures**

```bash
cd backend
./gradlew --stop
```

- [ ] **Step 2: Full clean test run**

```bash
./gradlew clean test
```

Expected: BUILD SUCCESSFUL with all tests passing. The renames cascade through compileTestKotlin; if anything fails, read the error and fix the test.

- [ ] **Step 3: ktlintFormat + ktlintCheck**

```bash
./gradlew ktlintFormat
./gradlew ktlintCheck
```

Expected: green.

- [ ] **Step 4: Commit any formatting changes**

```bash
cd /Users/wc/code/github/wwchen/roadtrip
git add backend/src
git commit -m "ktlintFormat" || true
```

The `|| true` covers the case where there were no changes.

---

## Task 10: Manual smoke

**Files:** none (verification only)

- [ ] **Step 1: Restart backend (Tilt or docker compose)**

Watch for `scheduler availability starting` in the logs.

- [ ] **Step 2: Confirm the table renamed cleanly**

```bash
psql "$ROADTRIP_DB_URL" -c "\d availability_snapshot"
```

Expected: column list includes `reservable_id`, `run_id`, `observed_at`, `target_date`, `status`, `available`, `day_payload` — but NOT `reservable_rid`.

- [ ] **Step 3: Confirm backfill landed**

```bash
psql "$ROADTRIP_DB_URL" -c "
SELECT count(*) FILTER (WHERE reservable_id IS NULL) AS unmatched,
       count(*) FILTER (WHERE reservable_id IS NOT NULL) AS matched,
       count(*) AS total
FROM availability_snapshot;"
```

Expected: in dev, `unmatched` should be 0 (or close to it). Non-zero `unmatched` means some pre-rename rows had RIDs that don't resolve through reservables — that's data-quality noise, not a migration bug.

- [ ] **Step 4: Trigger one new snapshot through the UI**

Create a watch via `/watches`. After one cadence elapses, run:

```bash
psql "$ROADTRIP_DB_URL" -c "
SELECT s.id, s.reservable_id, s.run_id, s.target_date, s.status
FROM availability_snapshot s
WHERE s.run_id IS NOT NULL
ORDER BY s.id DESC
LIMIT 5;"
```

Expected: at least one row with non-null `reservable_id` AND non-null `run_id`. That confirms the executor is populating both FKs.

- [ ] **Step 5: Sanity-check ad-hoc availability fetches**

Hit the existing route, e.g.:

```bash
curl -s "http://localhost:8765/api/reservable/site:recgov:330257/availability?start=2026-07-04&days=2" | head -c 200
```

Then:

```bash
psql "$ROADTRIP_DB_URL" -c "
SELECT s.id, s.reservable_id, s.run_id, s.target_date
FROM availability_snapshot s
WHERE s.run_id IS NULL
ORDER BY s.id DESC
LIMIT 5;"
```

Expected: rows with non-null `reservable_id` and null `run_id` (ad-hoc fetches don't have a job run).

If any step fails, capture the error and stop.

---

## Task 11: Push and open stacked PR

- [ ] **Step 1: Push the branch**

```bash
git push -u origin avail-snapshots
```

- [ ] **Step 2: Open the PR**

Write the body to a file (per global rule):

```bash
cat > pr_body.md <<'PR'
## Snapshot rename + reservable_id and run_id FKs

Stacks on PR #228. Replaces the stringly-typed `reservable_rid` with a real FK and links snapshots to their producing run.

### What ships
- **V17 migration:** rename `reservable_availability_log` → `availability_snapshot`. Add `reservable_id BIGINT` (backfilled by parsing the composite RID and joining through `reservables`) and `run_id BIGINT` (FK → `availability_job_run`). Drop `reservable_rid`. New indexes on `(reservable_id, target_date, observed_at DESC)`, `(reservable_id, observed_at DESC)`, and `run_id`.
- **`AvailabilitySnapshotRepo`** replaces `ReservableAvailabilityLogRepo`. `appendBatch` takes `reservableId: Long` and optional `runId: Long?`.
- **`ReservableAvailabilityFetchService`** carries `reservableId` and `runId` through `Request`. Existing ad-hoc fetches default `runId = null`.
- **`AvailabilityPollExecutor`** passes the active `runId` so scheduled-job snapshots are linkable to their run.
- **`CampsiteAvailabilityRoutes`** takes the new repo type at the wire-up site.
- Tests and `Main.kt` updated.

### Scope
- `availability_job_run.snapshot_count` is now derivable via SQL but stays as a denormalized counter to keep dashboard queries cheap. No behavior change.
- No FE changes — the snapshot timeline UI lands later.
- Backfill keeps `reservable_id` nullable for unmatched rows (vendor renames, deleted reservables). Operator sweeps if needed; not a migration failure.

### Verification
- `./gradlew ktlintCheck test` — green
- Manual smoke per `docs/superpowers/plans/2026-06-15-pr4-avail-snapshots.md` Task 10

🤖 Generated with [Claude Code](https://claude.com/claude-code)
PR

gh pr create --title "Snapshot rename + reservable_id and run_id FKs" --body-file pr_body.md --base avail-job-runs
rm pr_body.md
```

- [ ] **Step 3: Confirm CI**

```bash
gh pr checks
```

Expected: green (or pending if CI is still spinning up).

---

## Out of scope (deferred)

- **`/availability` dashboard.** Surfacing snapshots in a per-reservable timeline, plus the runs/jobs operator views, lands in a later PR.
- **Retention sweeper.** Snapshots accumulate indefinitely. A scheduled cleanup task lands when row count becomes operationally annoying.
- **Make `reservable_id` NOT NULL.** Deferred until a sweep confirms zero unmatched rows in prod. The constraint flip is a one-line follow-up migration.
