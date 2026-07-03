# PR3: Availability Cube — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split "now" from "history" for the availability cube. Add `availability_cell` — one row per `(reservable_id, target_date)`, upserted on every poll with the latest `status` + `last_observed_at` + `last_changed_at`. Change the executor's snapshot write from append-every-observation to **edge-triggered**: append an `availability_snapshot` transition row only when a cell's status differs from its prior status. Add a `past` terminal status for cells whose `target_date` has elapsed. Add a Grafana cell-matrix panel (reservables × dates, current status) — the cube's face.

**Architecture:** `AvailabilityPollExecutor.appendSnapshots` (per-run, per-fetch-group) becomes a cube-write step: for each observation, upsert `availability_cell` unconditionally (liveness bump), and only insert into `availability_snapshot` when the upserted status changed from the cell's prior status. A new `AvailabilityCellRepo` owns the upsert; `AvailabilitySnapshotRepo.appendObservations` is retargeted to take the edge-detection result rather than writing every observation. A new nightly-ish sweep (invoked once per executor run, cheap) or a `target_date < today` check inside the upsert marks cells `past`; the spec's model treats `past` as terminal, so no watch continues polling an elapsed date (this is already enforced upstream by `liveWatchesForPoller`'s window clamp — PR3 only needs the cell to *record* the terminal value when the executor happens to still observe it, and provide a batch sweep for dates that age out without ever being polled again).

**Tech Stack:** Kotlin, Ktor, jOOQ (codegen from Flyway migrations), Postgres, Testcontainers/SharedDbTest (repo tests), kotlinx.coroutines, Grafana (Postgres-datasource JSON dashboards).

## Global Constraints

- **Build needs JDK 17.** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew` from repo root. Corretto 25 (default) breaks the Kotlin compiler.
- **jOOQ includes allowlist.** Any new table must be added to `database.includes` in `backend/build.gradle.kts` (pipe-joined list, alphabetical) or codegen silently skips it. This PR adds `availability_cell`.
- **Layering (docs/backend-architecture.md):** `routes → service → repo/clients`; `repo` owns all SQL/jOOQ; `service`/`scheduler` owns orchestration and holds no raw SQL; `models` is a leaf.
- **No leaky abstractions (docs/reservation-providers.md):** cell/snapshot writing must not branch on vendor. It operates on `AvailabilityStatus` + `(reservableId, targetDate)` only.
- **No inline magic constants.** Any new limit, batch size, or sweep threshold gets a named `const val`.
- **SharedDbTest pattern.** New repo tests extend `SharedDbTest` (`backend/src/test/kotlin/ca/floo/roadtrip/repo/SharedDbTest.kt`) — each test class gets its own DB cloned from a migrated template, so classes run in parallel without truncating each other. Do not reach for a raw Testcontainers base; PR1/PR2 already migrated off it.
- **Postgres timestamp rounding.** `TIMESTAMPTZ` has microsecond resolution. Any test that constructs an `OffsetDateTime`/`Instant` in Kotlin and later asserts equality/`<=`/`>=` against a value round-tripped through Postgres must `.truncatedTo(ChronoUnit.MICROS)` before comparing — a nanosecond-precision value can round up on storage and break a `<=` assertion non-deterministically. PR1 hit this exact bug (`AvailabilityPollerMembershipTest`, commit `2bbccaac`).
- **Edge-triggered, not append-every-observation.** The whole point of PR3 is that a cell polled every 30s for a week with no change writes ~1 snapshot row, not thousands. Do not regress to appending on every fetch.
- **`past` is terminal but the cube does not police watch lifecycle.** Poller/watch expiry (retire-on-empty-window) is PR1's job and is unchanged here; PR3 only records `past` on the cell face when a date's cube history is done being written to.

## Cross-PR dependency (read before starting)

This plan assumes PR1 (`feat/pr1-poller-coalescing`, migrations **V27**–**V28**, not yet merged to master) and PR2 (`watch-as-set`, migration **V29** per its plan doc `docs/superpowers/plans/2026-07-03-pr2-watch-as-set.md`) have already landed on the branch this PR is based on. **Migration numbering in this plan (V30) is only correct if PR1 lands as V27/V28 and PR2 lands as V29, in that order, with no other migration inserted between.** If PR2's migration slips to a different number, or if PR3 is developed before PR2 merges, renumber this plan's migration file before running it — do not silently take a colliding version. Confirm the actual next-free `V*` in `backend/src/main/resources/db/migration/` at plan-execution time.

This PR also depends on PR1's `AvailabilityPollExecutor.handle(poller)` shape (loads live watches, derives window, does the fetch, then calls a private `appendSnapshots`) — PR3 rewrites the body of that one private method plus its call site; it does not change the method's signature or its callers.

---

## File Structure

**New files:**
- `backend/src/main/resources/db/migration/V30__availability_cell.sql` — `availability_cell` table + `past` status enum value.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityCellRepo.kt` — upsert + edge-detection + query methods.
- `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityCellRepoTest.kt`
- `grafana/dashboards/availability-cell-matrix.json` — new dashboard: reservables × dates, current status.

**Modified:**
- `backend/build.gradle.kts` — jOOQ includes allowlist: add `"availability_cell"`.
- `backend/src/main/kotlin/ca/floo/roadtrip/models/availability/AvailabilityStatus.kt` — add `PAST("past")`.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt` — rewrite `appendSnapshots` to upsert-then-conditionally-snapshot via `AvailabilityCellRepo`; `run.snapshot_count` becomes transition count.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt` — `appendObservations` keeps its shape (still the raw insert primitive) but the executor no longer calls it unconditionally; add a doc-comment update. No signature break needed if the executor pre-filters to only the changed subset before calling it.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepo.kt` — `loadHeatmap` currently re-derives "latest cell" via `DISTINCT ON` over `availability_snapshot`. Once `availability_cell` exists, this becomes a plain indexed point-read. Repoint `loadHeatmap` at `availability_cell`; keep its public signature unchanged so `AvailabilityResponseMapper`/routes don't ripple.
- Grafana: `grafana/dashboards/reservable-availability-watch-drill-down.json` — add a "cube face" link/panel pointing at the new cell-matrix dashboard.

---

## Interfaces (locked signatures used across tasks)

```kotlin
// AvailabilityCellRepo.kt
class AvailabilityCellRepo(private val ctx: DSLContext) {
    data class CellObservation(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val observedAt: Instant,
    )

    data class UpsertResult(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val changed: Boolean,          // true iff status != prior status (edge)
    )

    /** Upserts every observation's cell unconditionally (last_observed_at always
     *  bumped); returns one UpsertResult per input row flagging which ones changed
     *  status, so the caller knows which to also snapshot. Single round-trip via
     *  a jOOQ batched INSERT ... ON CONFLICT DO UPDATE ... RETURNING. */
    fun upsertObservations(observations: List<CellObservation>): List<UpsertResult>

    data class Cell(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val lastObservedAt: OffsetDateTime,
        val lastChangedAt: OffsetDateTime,
    )

    fun loadCells(reservableIds: List<Long>, dates: List<LocalDate>): List<Cell>

    /** Marks cells with target_date < today as status='past' where not already
     *  'past'. Called once per executor run (cheap, scoped to the run's own
     *  reservable/date set) so a date that quietly ages out without ever being
     *  re-observed still reaches its terminal state. Returns rows updated. */
    fun markElapsedAsPast(reservableIds: List<Long>, today: LocalDate): Int
}
```

```kotlin
// AvailabilityPollExecutor.kt — new private method shape
/** Replaces the old append-every-observation appendSnapshots. Upserts every
 *  observed cell (liveness bump always; status/last_changed_at on change),
 *  then appends an availability_snapshot row ONLY for cells whose status
 *  changed. Returns the transition count for run.snapshot_count. */
private fun writeCube(result: GroupFetchResult, runId: Long): Int
```

`AvailabilityStatus.PAST` wire value `"past"`. `isOnlineBookable` stays `false` for `PAST` (a `when` exhaustiveness check in the compiler will force every existing consumer of the enum to be reviewed — expected, and the point of adding it as a real enum case rather than an ad-hoc string).

---

### Task 1: Schema migration — `availability_cell` + `past` status

**Files:**
- Create: `backend/src/main/resources/db/migration/V30__availability_cell.sql`
- Modify: `backend/build.gradle.kts`

**Interfaces:** produces table `availability_cell`; adds `'past'` to the `availability_status` Postgres enum. jOOQ type `AvailabilityCell` generated under `ca.floo.roadtrip.db.generated.tables`.

- [ ] **Step 1: Write the migration.**

```sql
-- PR3: availability cube. availability_cell is the current face (upserted every
-- poll); availability_snapshot becomes the edge-triggered depth axis (a row only
-- when a cell's status changes). 'past' is the terminal status for cells whose
-- target_date has elapsed -- the cube stops recording new state for that date.

ALTER TYPE availability_status ADD VALUE 'past';

CREATE TABLE availability_cell (
  reservable_id    BIGINT      NOT NULL REFERENCES reservables(id) ON DELETE CASCADE,
  target_date      DATE        NOT NULL,
  status           availability_status NOT NULL,
  last_observed_at TIMESTAMPTZ NOT NULL,
  last_changed_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (reservable_id, target_date)
);

-- Hot path for the cell-matrix panel and any watch-eval read (PR6): one row per
-- reservable's date range.
CREATE INDEX availability_cell_reservable_date_idx
  ON availability_cell (reservable_id, target_date);
```

> **Note:** `ALTER TYPE ... ADD VALUE` cannot run inside the same transaction as
> statements that use the new value, but Flyway runs each migration file as one
> statement-by-statement batch (not necessarily one txn depending on `postgresql`
> Flyway config) — if `./gradlew flywayMigrate`/boot fails with "unsafe use of new
> value of enum type", split this into `V30a__availability_status_past.sql` (the
> `ALTER TYPE`) and `V30b__availability_cell.sql` (the `CREATE TABLE`), landing both
> in this same PR. Try the single-file version first; only split if it fails.

- [ ] **Step 2: Update the jOOQ includes allowlist.** In `backend/build.gradle.kts`, inside `includes = listOf(...)`: add `"availability_cell"` (alphabetical, between `"availability_watch_poller"` and `"booking_provider"` — check actual neighbors at execution time since PR1/PR2 may have added rows in between).

- [ ] **Step 3: Regenerate jOOQ + confirm compile.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:generateJooq`
Expected: BUILD SUCCESSFUL; `AvailabilityCell.kt` appears under `backend/build/generated/jooq/main/.../tables/`.

Run: `./gradlew :backend:compileKotlin`
Expected: BUILD SUCCESSFUL. (`compileTestKotlin` will fail at this point on the `AvailabilityStatus.PAST` exhaustiveness change in existing `when` blocks — expected; fixed in Task 2.)

- [ ] **Step 4: Commit.**

```
git add backend/src/main/resources/db/migration/V30__availability_cell.sql backend/build.gradle.kts
git commit -m "feat(cube): V30 migration -- availability_cell + past status" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `AvailabilityStatus.PAST` + fix exhaustiveness

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/availability/AvailabilityStatus.kt`
- Modify: any file whose `when (status)` over `AvailabilityStatus` is now non-exhaustive (found by compiler; expected candidates: `AvailabilityResponseMapper.kt`, dashboard status-badge rendering in `web/utils/availability-status.js` if it hardcodes strings — that one is JS, no compiler help, must be grepped for by hand).

- [ ] **Step 1: Write the failing test.** In a new or existing `AvailabilityStatusTest.kt`:

```kotlin
class AvailabilityStatusTest {
    @Test fun `PAST parses from wire value and is not online-bookable`() {
        assertEquals(AvailabilityStatus.PAST, AvailabilityStatus.parse("past"))
        assertFalse(AvailabilityStatus.PAST.isOnlineBookable)
    }
}
```

Run: `./gradlew :backend:test --tests '*AvailabilityStatusTest*'`
Expected: FAIL — `PAST` unresolved.

- [ ] **Step 2: Add the enum case.**

```kotlin
@SerialName("past")
PAST("past"),
```

Add it after `UNKNOWN` (append-only; do not renumber existing entries — the DB enum and any ordinal-sensitive code must not shift).

- [ ] **Step 3: Fix every resulting compiler error.** Run `./gradlew :backend:compileKotlin :backend:compileTestKotlin` repeatedly; for each non-exhaustive `when`, add an explicit `AvailabilityStatus.PAST ->` branch (do not add an `else` unless the file already uses one elsewhere — exhaustive `when` is intentional so a future status addition breaks the build again, not silently falls through).

- [ ] **Step 4: Grep the frontend for hardcoded status lists** (`grep -rn "first_come\|reserved\|available\|closed\|unknown" web/utils/availability-status.js web/availability/`) and add a `past` case (greyed-out / muted styling) so the cell-matrix panel and any reused status-badge component render it sanely.

- [ ] **Step 5: Run full test suite.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/models/availability/AvailabilityStatus.kt backend/src/test/kotlin/ca/floo/roadtrip/models/availability/AvailabilityStatusTest.kt
git commit -m "feat(cube): add PAST availability status, fix exhaustive when blocks" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

(Frontend files committed separately if touched, same commit is fine if small.)

---

### Task 3: `AvailabilityCellRepo` — upsert + edge detection

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityCellRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityCellRepoTest.kt`

**Interfaces:**
- Consumes: jOOQ `AVAILABILITY_CELL`; `AvailabilityStatus`.
- Produces: `AvailabilityCellRepo` with `upsertObservations`, `loadCells`, `markElapsedAsPast` per the Interfaces block above.

- [ ] **Step 1: Write the failing test.**

```kotlin
class AvailabilityCellRepoTest : SharedDbTest() {
    private fun repo() = AvailabilityCellRepo(ctx)
    private fun reservable(): Long = /* insert a minimal reservable row via existing test helper/fixture, mirroring AvailabilityPollerRepoTest's insertPoi()-style helper */

    @Test fun `first observation upserts a new cell and is flagged changed`() {
        val rid = reservable()
        val now = Instant.now()
        val result = repo().upsertObservations(listOf(
            AvailabilityCellRepo.CellObservation(rid, LocalDate.now().plusDays(1), AvailabilityStatus.AVAILABLE, now),
        ))
        assertEquals(1, result.size)
        assertTrue(result.single().changed)
        val cell = repo().loadCells(listOf(rid), listOf(LocalDate.now().plusDays(1))).single()
        assertEquals(AvailabilityStatus.AVAILABLE, cell.status)
    }

    @Test fun `re-observing the same status bumps last_observed_at but is not flagged changed`() {
        val rid = reservable()
        val date = LocalDate.now().plusDays(1)
        val repo = repo()
        repo.upsertObservations(listOf(AvailabilityCellRepo.CellObservation(rid, date, AvailabilityStatus.AVAILABLE, Instant.now())))
        val before = repo.loadCells(listOf(rid), listOf(date)).single()
        Thread.sleep(5)
        val result = repo.upsertObservations(listOf(AvailabilityCellRepo.CellObservation(rid, date, AvailabilityStatus.AVAILABLE, Instant.now())))
        assertFalse(result.single().changed)
        val after = repo.loadCells(listOf(rid), listOf(date)).single()
        assertTrue(after.lastObservedAt.isAfter(before.lastObservedAt))
        assertEquals(before.lastChangedAt, after.lastChangedAt) // unchanged
    }

    @Test fun `a status change is flagged changed and bumps last_changed_at`() {
        val rid = reservable()
        val date = LocalDate.now().plusDays(1)
        val repo = repo()
        repo.upsertObservations(listOf(AvailabilityCellRepo.CellObservation(rid, date, AvailabilityStatus.AVAILABLE, Instant.now())))
        val result = repo.upsertObservations(listOf(AvailabilityCellRepo.CellObservation(rid, date, AvailabilityStatus.RESERVED, Instant.now())))
        assertTrue(result.single().changed)
        val cell = repo.loadCells(listOf(rid), listOf(date)).single()
        assertEquals(AvailabilityStatus.RESERVED, cell.status)
    }

    @Test fun `markElapsedAsPast flips only target_date before today and only once`() {
        val rid = reservable()
        val repo = repo()
        val yesterday = LocalDate.now().minusDays(1)
        val today = LocalDate.now()
        repo.upsertObservations(listOf(
            AvailabilityCellRepo.CellObservation(rid, yesterday, AvailabilityStatus.AVAILABLE, Instant.now()),
            AvailabilityCellRepo.CellObservation(rid, today, AvailabilityStatus.AVAILABLE, Instant.now()),
        ))
        val updated = repo.markElapsedAsPast(listOf(rid), today)
        assertEquals(1, updated)
        assertEquals(AvailabilityStatus.PAST, repo.loadCells(listOf(rid), listOf(yesterday)).single().status)
        assertEquals(AvailabilityStatus.AVAILABLE, repo.loadCells(listOf(rid), listOf(today)).single().status)
        assertEquals(0, repo.markElapsedAsPast(listOf(rid), today)) // idempotent, no double-flip
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*AvailabilityCellRepoTest*'`
Expected: FAIL — `AvailabilityCellRepo` unresolved.

- [ ] **Step 3: Implement `AvailabilityCellRepo`.**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityCell.Companion.AVAILABILITY_CELL
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import ca.floo.roadtrip.db.generated.enums.AvailabilityStatus as DbAvailabilityStatus

class AvailabilityCellRepo(private val ctx: DSLContext) {
    data class CellObservation(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val observedAt: Instant,
    )

    data class UpsertResult(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val changed: Boolean,
    )

    fun upsertObservations(observations: List<CellObservation>): List<UpsertResult> {
        if (observations.isEmpty()) return emptyList()
        // ON CONFLICT ... DO UPDATE with a CASE on whether status differs, returning
        // both the resulting status and a boolean "changed" computed from comparing
        // the excluded (new) status to the row's status BEFORE the update. Postgres
        // does not expose "old value" in a RETURNING on the same statement directly,
        // so read-old-then-upsert in one statement via a CTE:
        return observations.map { obs ->
            val row = ctx.resultQuery(
                """
                WITH prior AS (
                    SELECT status FROM availability_cell
                    WHERE reservable_id = ? AND target_date = ?
                ), upsert AS (
                    INSERT INTO availability_cell (reservable_id, target_date, status, last_observed_at, last_changed_at)
                    VALUES (?, ?, ?::availability_status, ?, ?)
                    ON CONFLICT (reservable_id, target_date) DO UPDATE SET
                        status = EXCLUDED.status,
                        last_observed_at = EXCLUDED.last_observed_at,
                        last_changed_at = CASE
                            WHEN availability_cell.status IS DISTINCT FROM EXCLUDED.status
                            THEN EXCLUDED.last_changed_at
                            ELSE availability_cell.last_changed_at
                        END
                    RETURNING status
                )
                SELECT upsert.status AS new_status, prior.status AS old_status
                FROM upsert LEFT JOIN prior ON true
                """.trimIndent(),
                obs.reservableId, obs.targetDate,
                obs.reservableId, obs.targetDate, obs.status.toDb().literal,
                OffsetDateTime.ofInstant(obs.observedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(obs.observedAt, ZoneOffset.UTC),
            ).fetchOne()!!
            val oldStatus = row.get("old_status", String::class.java)
            UpsertResult(
                reservableId = obs.reservableId,
                targetDate = obs.targetDate,
                status = obs.status,
                changed = oldStatus == null || oldStatus != obs.status.toDb().literal,
            )
        }
    }
    // loadCells, markElapsedAsPast: straightforward jOOQ DSL, follow AvailabilityHeatmapRepo.loadHeatmap's
    // DISTINCT ON pattern for the shape but a plain WHERE (no DISTINCT ON needed -- one row per key already).
}
```

> **Batching note:** the per-observation CTE round-trip above is correct but issues one
> statement per observation. If a run's fetch group commonly has 50-200 reservables ×
> N dates, profile before shipping — if `upsertObservations` shows up in a run's
> duration, refactor to a single `INSERT ... SELECT * FROM UNNEST(...)` batched
> statement with the same CTE shape (jOOQ supports this via multi-row VALUES; the
> "old status" comparison must move to a self-join against a pre-upsert snapshot of
> the batch's own keys, taken in the same transaction). Do not over-engineer this in
> Task 3 — correctness first (per the project's `feedback_correctness_over_perf`
> convention); batch only if profiling says so.

- [ ] **Step 4: Run to verify it passes.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*AvailabilityCellRepoTest*'`
Expected: BUILD SUCCESSFUL, 4/4 passing.

- [ ] **Step 5: Commit.**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityCellRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityCellRepoTest.kt
git commit -m "feat(cube): AvailabilityCellRepo -- upsert with edge detection" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Executor rewrite — cell upsert + edge-triggered snapshot

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutorTest.kt`

**Interfaces:**
- Consumes: `AvailabilityCellRepo` (new constructor param).
- Produces: `writeCube` replacing `appendSnapshots`; `run.snapshot_count` now counts transitions, not raw observations.

- [ ] **Step 1: Write the failing test** (add to the existing executor test file, alongside PR1's `snapshot writing is unchanged (one row per observation)` test — that test's name and assertion become **wrong** under PR3 and must be rewritten in this same task, not left contradicting the new behavior):

```kotlin
@Test fun `unchanged status across two runs upserts the cell but writes no second snapshot row`() {
    // Arrange a poller/watch fixture (reuse existing executor test helpers), a
    // provider stub that returns the SAME status for the same (reservable, date)
    // across two consecutive handle() calls.
    val executor = buildExecutor(/* ... */)
    runBlocking { executor.handle(poller) }
    val snapshotsAfterFirst = snapshotRepo.listForReservable(reservableId)
    runBlocking { executor.handle(poller) }
    val snapshotsAfterSecond = snapshotRepo.listForReservable(reservableId)
    assertEquals(snapshotsAfterFirst.size, snapshotsAfterSecond.size) // no new row
    val cell = cellRepo.loadCells(listOf(reservableId), listOf(targetDate)).single()
    assertTrue(cell.lastObservedAt.isAfter(/* first run's observedAt, truncated to micros */))
}

@Test fun `a status change writes exactly one new snapshot row (the transition)`() {
    val executor = buildExecutor(/* provider stub: run 1 = AVAILABLE, run 2 = RESERVED */)
    runBlocking { executor.handle(poller) }
    val before = snapshotRepo.listForReservable(reservableId).size
    runBlocking { executor.handle(poller) }
    val after = snapshotRepo.listForReservable(reservableId).size
    assertEquals(before + 1, after)
}

@Test fun `run snapshot_count reflects transitions, not raw observation count`() {
    // provider stub returns 5 reservables x 3 dates = 15 observations, all
    // AVAILABLE on a poller with no prior cell rows -> all 15 are "first sight",
    // hence all 15 count as transitions on run 1. Assert run.snapshotCount == 15,
    // then on run 2 with identical statuses, assert run.snapshotCount == 0.
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*AvailabilityPollExecutorTest*'`
Expected: FAIL — old `appendSnapshots` still writes every observation.

- [ ] **Step 3: Implement.** Add `cells: AvailabilityCellRepo` to the executor's constructor; replace `appendSnapshots` with `writeCube`:

```kotlin
private fun writeCube(result: GroupFetchResult, runId: Long): Int {
    val batch = result.batch ?: return 0
    val idByRid = result.reservables.associateBy({ it.rid.encode() }, { it.id })
    val cellObservations = batch.observations.mapNotNull { obs ->
        val dbId = idByRid[obs.reservableId] ?: return@mapNotNull null
        dbId to AvailabilityCellRepo.CellObservation(
            reservableId = dbId,
            targetDate = obs.date,
            status = obs.status,
            observedAt = obs.observedAt,
        )
    }
    if (cellObservations.isEmpty()) return 0
    val upsertResults = cells.upsertObservations(cellObservations.map { it.second })
    val changedByKey = upsertResults.filter { it.changed }
        .associateBy { it.reservableId to it.targetDate }
    val snapshotObservations = batch.observations.mapNotNull { obs ->
        val dbId = idByRid[obs.reservableId] ?: return@mapNotNull null
        if (changedByKey[dbId to obs.date] == null) return@mapNotNull null
        AvailabilitySnapshotRepo.SnapshotObservation(
            reservableId = dbId,
            reservableRid = obs.reservableId,
            targetDate = obs.date,
            observedAt = obs.observedAt,
            status = obs.status,
        )
    }
    return snapshots.appendObservations(
        AvailabilitySnapshotRepo.SnapshotObservationBatch(runId = runId, observations = snapshotObservations),
    )
}
```

Update the call site (`results.sumOf { appendSnapshots(it, runId) }` → `results.sumOf { writeCube(it, runId) }`) and the doc-comment above `handle` that currently says "Snapshot writing is unchanged" (PR1's comment) — update it to describe the cube write. Also call `cells.markElapsedAsPast(reservableIdsInThisRun, LocalDate.now())` once per run after the fetch loop, so dates that age out mid-poller-lifetime still reach `past` (belt-and-suspenders alongside PR1's window-clamp retirement, which stops *polling* an elapsed date but doesn't itself flip the cell's stored status).

- [ ] **Step 4: Run to verify it passes.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*AvailabilityPollExecutorTest*'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Wire the new repo in `Main.kt`.** `AvailabilityPollExecutor(...)` construction gains `cells = AvailabilityCellRepo(ctx)`.

- [ ] **Step 6: Run the full backend suite + ktlint.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test`
Then: `./gradlew :backend:ktlintCheck`
Expected: both BUILD SUCCESSFUL.

- [ ] **Step 7: Commit.**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt backend/src/main/kotlin/ca/floo/roadtrip/Main.kt backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutorTest.kt
git commit -m "feat(cube): executor upserts cells, snapshots only on status change" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Repoint `AvailabilityHeatmapRepo` at the cell table

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepo.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepoTest.kt` (if it exists; create if not)

**Interfaces:** `loadHeatmap(reservableIds, dates): List<LatestCell>` — same public signature, new backing query.

- [ ] **Step 1: Write/extend the failing test** asserting `loadHeatmap` returns the cell's `status`/`available`/`observedAt` even when `availability_snapshot` has zero rows for that key (proves it no longer depends on the append log) — this is the behavioral delta from the old `DISTINCT ON` query, which returned nothing without a snapshot row.

- [ ] **Step 2: Run to verify it fails** (old implementation still queries `availability_snapshot`, so a cell-only fixture returns empty).

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*AvailabilityHeatmapRepoTest*'`

- [ ] **Step 3: Implement.** Replace the `DISTINCT ON ... FROM availability_snapshot` query with a plain `SELECT reservable_id, target_date, status, last_observed_at FROM availability_cell WHERE reservable_id = ANY(?) AND target_date = ANY(?)`; derive `available` from `AvailabilityStatus.isOnlineBookable` in Kotlin instead of reading a stored `available` boolean column (there is no such column on `availability_cell` — it was a `availability_snapshot`-only denormalization; dropping it here is intentional simplification, not a mistake).

- [ ] **Step 4: Run to verify it passes; then full suite.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test`

- [ ] **Step 5: Commit.**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepoTest.kt
git commit -m "refactor(cube): AvailabilityHeatmapRepo reads availability_cell, not the snapshot log" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Grafana cell-matrix panel

**Files:**
- Create: `grafana/dashboards/availability-cell-matrix.json`
- Modify: `grafana/dashboards/reservable-availability-watch-drill-down.json` (add a link to the new dashboard)

**Interfaces:** Postgres-datasource panel querying `availability_cell` filtered by a `poi_id` (via `reservables.poi_id` or the M:N join, per RFC 0008) and a date range template var; renders as a table/matrix with reservables as rows, dates as columns, `status` as the cell value (color-coded).

- [ ] **Step 1: Model the panel on an existing table panel** in `reservable-availability-watch-drill-down.json` for gridPos/fieldConfig/thresholds boilerplate (copy-paste-adapt, don't invent new JSON shape).

- [ ] **Step 2: Write the panel query.**

```sql
SELECT
  r.rid AS reservable,
  c.target_date,
  c.status,
  c.last_observed_at
FROM availability_cell c
JOIN reservables r ON r.id = c.reservable_id
JOIN reservable_pois rp ON rp.reservable_id = r.id
WHERE rp.poi_id = ${poi_id:sqlstring}::bigint
  AND c.target_date BETWEEN ${__from:date} AND ${__to:date}
ORDER BY r.rid, c.target_date
```

Use a Grafana "Transform: Rows to columns" (pivot on `target_date`) or a crosstab-style `CASE` per date column if the transform is unavailable in this Grafana version — check an existing pivoted panel in `grafana/dashboards/` for the project's established pattern before inventing one.

- [ ] **Step 3: Add a `poi_id` template variable** (mirrors the existing `watch_id`/`poller_id`/`run_id` var pattern in the drill-down dashboard).

- [ ] **Step 4: Validate JSON.**

Run: `python3 -m json.tool grafana/dashboards/availability-cell-matrix.json > /dev/null && echo OK`

- [ ] **Step 5: Link from the drill-down dashboard.** Add a markdown link in the "About this dashboard" panel's content pointing at `/d/availability-cell-matrix` — mirrors the existing "Where else to look" table row pattern.

- [ ] **Step 6: Commit.**

```
git add grafana/dashboards/availability-cell-matrix.json grafana/dashboards/reservable-availability-watch-drill-down.json
git commit -m "feat(cube): Grafana cell-matrix panel -- the cube's face" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: End-to-end verification

**Files:** none (verification only). Uses the Tilt dev stack (`tilt up`).

- [ ] **Step 1: Bring up the stack.** `tilt up`; wait for backend healthy.

- [ ] **Step 2: Let a poller run twice on a stable ground.** Confirm via SQL:

Run: `SELECT reservable_id, target_date, status, last_observed_at, last_changed_at FROM availability_cell ORDER BY last_observed_at DESC LIMIT 10;`
Expected: `last_observed_at` advances across ticks; `last_changed_at` only advances when `status` differs from the row before it.

- [ ] **Step 3: Confirm edge-triggered snapshot count.**

Run: `SELECT reservable_id, target_date, COUNT(*) FROM availability_snapshot WHERE observed_at > now() - interval '10 minutes' GROUP BY 1,2 ORDER BY 3 DESC LIMIT 10;`
Expected: counts stay low (1-2) for cells with no observed status change across several ticks, not one row per tick.

- [ ] **Step 4: Confirm the cell matrix panel renders** in Grafana against a POI with active watches.

- [ ] **Step 5: Record the evidence** (SQL results + panel screenshot) in the PR description.

---

## Self-Review

**Spec coverage (PR3 bullet: "`availability_cell` + edge-triggered snapshots + cell matrix panel"):**
- `availability_cell` current-face table, upserted every poll (`status`, `last_observed_at`, `last_changed_at`) → Task 1 + Task 3. ✓
- edge-triggered `availability_snapshot` (append only on status change) → Task 4. ✓
- terminal `past` when `target_date < today` → Task 1 (enum value) + Task 3 (`markElapsedAsPast`) + Task 4 (executor calls it). ✓
- cell-matrix Grafana panel → Task 6. ✓
- "PR1 left snapshot writing as append-every-observation; PR3 changes the executor's `appendSnapshots`" → Task 4 explicitly rewrites that method and its now-stale doc-comment/test name. ✓

**Deliberately out of PR3 scope (documented):** `pois.cadence_override_sec` fall-through, Bucket4j governor (PR4); force pull (PR5); alert eval over the cube (PR6, one-sentence deferral below).

**PR6 (out of scope):** Alert firing/notification evaluation over the cube (watching for `reserved → available` transitions in a watch's sub-cube) is deferred to PR6; PR3 only makes that transition observable as a snapshot row, it does not evaluate or notify on it.

**Open risks flagged during planning:**
1. **Migration numbering is provisional.** V30 assumes PR1 lands as V27/V28 and PR2 (watch-as-set, per its own plan doc) lands as V29, in that exact order, before PR3 starts. None of the three are merged as of this writing. Confirm the actual next-free migration version at execution time.
2. **`ALTER TYPE ... ADD VALUE` transactional caveat.** Flagged in Task 1 — may need a two-file split (`V30a`/`V30b`) depending on how this project's Flyway config batches statements; verify empirically rather than assuming.
3. **Batch-vs-per-row cell upsert.** Task 3's per-observation CTE is correct but not batched; flagged as a profile-before-optimizing decision consistent with the project's correctness-over-perf convention, not a shipped inefficiency to silently accept forever.
4. **`AvailabilityHeatmapRepo` behavior change.** Repointing it at `availability_cell` (Task 5) means a `(reservable, date)` with cell history but zero raw snapshot rows now returns data where it previously returned none — this is the intended simplification (the cell is the source of truth for "now"), but any caller relying on the old "no snapshot ⇒ no heatmap entry" behavior should be checked (none found in `AvailabilityResponseMapper` at time of writing; re-verify before merging).
