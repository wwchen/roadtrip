# Availability Persistence Realign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the two availability tables (`availability_cell` matrix + `availability_snapshot` history) into one `availability` interval table, deleting the cross-table `AvailabilityCacheStore` and the duplicate `AvailabilityHeatmapRepo`, and codify the repo-per-table rules that were violated.

**Architecture:** One `availability` table where each row is a status-run for a `(reservable_id, target_date)` cell. Current state = the row with `MAX(last_observed_at)` per cell; unchanged polls bump `last_observed_at` in place; a status change inserts a new row linked by `previous_id`. History and stats derive from walking the rows. One `AvailabilityRepo` owns the table (writes + reads); cache fall-through lives in the renamed `CachedAvailabilityService`.

**Tech Stack:** Kotlin, Ktor, jOOQ (generated types), Flyway, Postgres, Testcontainers (JUnit5), Grafana (provisioned JSON dashboards).

## Global Constraints

- Base branch: `feat/availability-persistence-realign` off current `master` (`f2646c3`, #355). PR targets `master`.
- Build with `JAVA_HOME` set to the Gradle-provisioned corretto 21 — JDK 25 breaks the Kotlin compiler here. Build command: `./gradlew :backend:build`.
- Backend layering (docs/backend-architecture.md): SQL/jOOQ only in `repo`; no cross-table transaction in `repo`; services never build Ktor responses; one meaningful top-level type per file, file name matches class.
- No inline magic constants — extract durations/limits to named `const val`.
- `make reset-db` wipes the dev DB (tilt is up in this checkout); no data migration/backfill required.
- Next Flyway version is **V36** (highest existing is V35).
- jOOQ regenerates from migrations on build; never edit generated sources by hand.
- Availability status enum values: `first_come | reserved | available | closed | unknown | past`. `isOnlineBookable == (this == AVAILABLE)`.

---

## File Structure

**Created:**
- `backend/src/main/resources/db/migration/V36__availability_interval_table.sql` — new table, drop old two.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityRepo.kt` — sole owner of `availability` (writes + reads + history + stats).
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityFreshness.kt` — pure TTL/coverage functions (no DB).
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/ImportRunRepo.kt` — sole writer of `import_runs`.
- Tests mirroring each of the above.

**Renamed:**
- `service/api/SnapshotBackedAvailabilityService.kt` → `service/api/CachedAvailabilityService.kt`.

**Deleted:**
- `repo/AvailabilityCacheStore.kt`, `repo/AvailabilitySnapshotStore.kt`, `repo/AvailabilitySnapshotRepo.kt`, `repo/AvailabilityHeatmapRepo.kt`, `repo/AvailabilityCellRepo.kt` (all folded into `AvailabilityRepo`).
- Their test files.

**Modified:**
- `Main.kt` (wiring), `service/availability/ReservableAvailabilityComposer.kt`, `service/availability/WatchAlertDispatcher.kt`, `routes/AvailabilityWatchRoutes.kt`, `routes/AvailabilityDashboardRoutes.kt`, `service/scheduler/jobs/AvailabilityPollExecutor.kt`, `repo/PoiRepo.kt`, `repo/ReservableRepo.kt`.
- `docs/backend-architecture.md`, `docs/reservation-providers.md`.
- 10 Grafana dashboards under `grafana/dashboards/`.

---

## Task 1: Codify the rules in docs

**Files:**
- Modify: `docs/backend-architecture.md` (append to the "Anti-patterns" / repo section)
- Modify: `docs/reservation-providers.md:74` and the "Availability history" section

No code; documentation sets the contract before the refactor. This task is a single commit.

- [ ] **Step 1: Add the principles block to `backend-architecture.md`**

Add under the repo layer rules (after the "New table → one repo file" row context), a new subsection:

```markdown
### Repo ownership rules

- **Write-ownership is 1:1.** Each table has exactly one repo that owns its
  mutations (INSERT/UPDATE/DELETE). Reads may join other tables; multiple
  read-projection repos per table are allowed. `PoiRepo` (writer) + `PoiServingRepo`
  (reader) is the model.
- **Reader vs writer is explicit in the name** — by function
  (`upsert*/insert*/update*/delete*/mark*` vs `read*/load*/find*`) or by class
  (`…ServingRepo`, `…ReadRepo`). Never name a repo after the UI feature it feeds.
- **No cross-table transactions in `repo`.** A transaction spanning two tables is
  service orchestration; the service opens it and calls each single-table repo.
- **Storage names never climb into service names.** `matrix`, `interval`,
  `snapshot`, `cube` are persistence vocabulary; a service is named for its job
  (`CachedAvailabilityService`), never its store.
- **Store only non-derivable facts.** Don't persist derivable state (`is_current`,
  `observed_from`); persist what you can't (`previous_id`).
```

- [ ] **Step 2: Fix the stale `reservation-providers.md` line**

At line 74, replace the phrase claiming caching reads `availability_snapshots`:

Old: `caching is handled above the adapter by SnapshotBackedAvailabilityService reading the availability_snapshots table.`
New: `caching is handled above the adapter by CachedAvailabilityService reading current state from the availability interval table.`

In the "Availability history" section, replace the description of a separate append-only snapshot table with: history is the chain of status-run rows in `availability` (walk `previous_id`); each row is an interval `[previous.last_observed_at, last_observed_at]`.

- [ ] **Step 3: Commit**

```bash
git add docs/backend-architecture.md docs/reservation-providers.md
git commit -m "docs(availability): codify repo write-ownership + naming rules"
```

---

## Task 2: V36 migration — the `availability` interval table

**Files:**
- Create: `backend/src/main/resources/db/migration/V36__availability_interval_table.sql`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityRepoTest.kt` (created here, extended in later tasks)

**Interfaces:**
- Produces: table `availability(id, reservable_id, target_date, status, last_observed_at, previous_id, run_id)`.

- [ ] **Step 1: Write the migration**

```sql
-- Collapse availability_cell (matrix) + availability_snapshot (history) into one
-- interval table. Each row is a status-run for a (reservable_id, target_date)
-- cell: last_observed_at advances in place on unchanged polls; a status change
-- inserts a new row linked by previous_id. Current state = MAX(last_observed_at)
-- per cell. No backfill (dev DB is reset; history is disposable).

CREATE TABLE availability (
  id               BIGSERIAL PRIMARY KEY,
  reservable_id    BIGINT      NOT NULL REFERENCES reservables(id) ON DELETE CASCADE,
  target_date      DATE        NOT NULL,
  status           availability_status NOT NULL,
  last_observed_at TIMESTAMPTZ NOT NULL,
  previous_id      BIGINT      REFERENCES availability(id) ON DELETE SET NULL,
  run_id           BIGINT      REFERENCES availability_job_run(id) ON DELETE SET NULL
);

-- A status-run has at most one successor: keeps the previous_id chain linear.
CREATE UNIQUE INDEX availability_previous_id_uq
  ON availability (previous_id) WHERE previous_id IS NOT NULL;

-- Current-state read + write-path "find current" lookup: top-1 per cell.
CREATE INDEX availability_current_idx
  ON availability (reservable_id, target_date, last_observed_at DESC);

CREATE INDEX availability_run_idx
  ON availability (run_id) WHERE run_id IS NOT NULL;

DROP TABLE availability_cell;
DROP TABLE availability_snapshot;
```

- [ ] **Step 2: Reset the dev DB and regenerate jOOQ**

Run: `make reset-db`
Then: `JAVA_HOME=$(./gradlew -q javaHome 2>/dev/null || echo $JAVA_HOME) ./gradlew :backend:generateJooq`
Expected: generated `Availability` table class appears; `AvailabilityCell` / `AvailabilitySnapshot` classes disappear. (Compilation of dependent Kotlin will fail until later tasks — expected at this point; do not fix yet, this task's deliverable is the migration + regen.)

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V36__availability_interval_table.sql
git commit -m "feat(availability): V36 single interval table, drop cell+snapshot"
```

---

## Task 3: `AvailabilityRepo` — write path (`recordObservations`)

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityRepoTest.kt`

**Interfaces:**
- Produces:
  - `data class Observation(reservableId: Long, targetDate: LocalDate, status: AvailabilityStatus, observedAt: Instant)`
  - `fun recordObservations(runId: Long?, observations: List<Observation>): Int` — returns number of transitions (new rows inserted). Bumps `last_observed_at` in place when status unchanged; inserts a new row (linked via `previous_id`) on change or first sight. Runs in one transaction.

- [ ] **Step 1: Write the failing test**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class AvailabilityRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
    }

    private fun seedReservable(vendorId: String): Long =
        ctx.fetchOne(
            """
            INSERT INTO reservables (type, vendor, vendor_id, source, name)
            VALUES ('site', 'recgov', ?, 'federal-campsites', 'site') RETURNING id
            """.trimIndent(),
            vendorId,
        )!!.get("id", Long::class.java)

    private val date = LocalDate.parse("2026-07-04")

    @Test
    fun `unchanged status bumps last_observed_at in place, no new row`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T10:05:00Z")
        repo.recordObservations(runId = null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.RESERVED, t1)))
        val transitions = repo.recordObservations(runId = null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.RESERVED, t2)))
        assertEquals(0, transitions)
        assertEquals(1, ctx.fetchCount(ctx.selectFrom(ca.floo.roadtrip.db.generated.tables.Availability.AVAILABILITY)))
        val current = repo.readCurrent(listOf(rid), listOf(date)).single()
        assertEquals(t2, current.observedAt.toInstant())
    }

    @Test
    fun `status change inserts a new row linked by previous_id`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T10:05:00Z")
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.RESERVED, t1)))
        val transitions = repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.AVAILABLE, t2)))
        assertEquals(1, transitions)
        assertEquals(2, ctx.fetchCount(ctx.selectFrom(ca.floo.roadtrip.db.generated.tables.Availability.AVAILABILITY)))
        val current = repo.readCurrent(listOf(rid), listOf(date)).single()
        assertEquals(AvailabilityStatus.AVAILABLE, current.status)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.AvailabilityRepoTest'`
Expected: FAIL — `AvailabilityRepo` unresolved.

- [ ] **Step 3: Implement `AvailabilityRepo` write path + `readCurrent` stub**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.Availability.Companion.AVAILABILITY
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import ca.floo.roadtrip.db.generated.enums.AvailabilityStatus as DbAvailabilityStatus

/**
 * Sole owner of the `availability` interval table. Each row is a status-run for a
 * (reservable_id, target_date) cell. Writes bump `last_observed_at` in place while
 * status is unchanged and insert a new row (linked by `previous_id`) on a change;
 * reads take the row with the greatest `last_observed_at` per cell as current.
 */
class AvailabilityRepo(
    private val ctx: DSLContext,
) {
    data class Observation(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val observedAt: Instant,
    )

    data class CurrentCell(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val available: Boolean,
        val observedAt: OffsetDateTime,
    )

    /** Bump-or-insert each observation; returns the count of transitions (new rows). */
    fun recordObservations(
        runId: Long?,
        observations: List<Observation>,
    ): Int {
        if (observations.isEmpty()) return 0
        return ctx.transactionResult { config ->
            val txn = DSL.using(config)
            var transitions = 0
            for (obs in observations) {
                val observedAt = OffsetDateTime.ofInstant(obs.observedAt, ZoneOffset.UTC)
                val current =
                    txn.select(AVAILABILITY.ID, AVAILABILITY.STATUS)
                        .from(AVAILABILITY)
                        .where(AVAILABILITY.RESERVABLE_ID.eq(obs.reservableId))
                        .and(AVAILABILITY.TARGET_DATE.eq(obs.targetDate))
                        .orderBy(AVAILABILITY.LAST_OBSERVED_AT.desc(), AVAILABILITY.ID.desc())
                        .limit(1)
                        .fetchOne()
                val newStatus = obs.status.toDb()
                if (current != null && current.get(AVAILABILITY.STATUS) == newStatus) {
                    txn.update(AVAILABILITY)
                        .set(AVAILABILITY.LAST_OBSERVED_AT, observedAt)
                        .where(AVAILABILITY.ID.eq(current.get(AVAILABILITY.ID)))
                        .execute()
                } else {
                    txn.insertInto(AVAILABILITY)
                        .set(AVAILABILITY.RESERVABLE_ID, obs.reservableId)
                        .set(AVAILABILITY.TARGET_DATE, obs.targetDate)
                        .set(AVAILABILITY.STATUS, newStatus)
                        .set(AVAILABILITY.LAST_OBSERVED_AT, observedAt)
                        .set(AVAILABILITY.PREVIOUS_ID, current?.get(AVAILABILITY.ID))
                        .set(AVAILABILITY.RUN_ID, runId)
                        .execute()
                    transitions += 1
                }
            }
            transitions
        }
    }

    /** Current cell per (reservable, date): the row with the greatest last_observed_at. */
    fun readCurrent(
        reservableIds: List<Long>,
        dates: List<LocalDate>,
    ): List<CurrentCell> {
        if (reservableIds.isEmpty() || dates.isEmpty()) return emptyList()
        return ctx
            .resultQuery(
                """
                SELECT DISTINCT ON (reservable_id, target_date)
                    reservable_id, target_date, status, last_observed_at
                FROM availability
                WHERE reservable_id = ANY(?::bigint[])
                  AND target_date = ANY(?::date[])
                ORDER BY reservable_id, target_date, last_observed_at DESC, id DESC
                """.trimIndent(),
                reservableIds.toTypedArray(),
                dates.toTypedArray(),
            ).fetch { r ->
                val status = AvailabilityStatus.parse(r.get("status", String::class.java))
                CurrentCell(
                    reservableId = r.get("reservable_id", Long::class.java),
                    targetDate = r.get("target_date", LocalDate::class.java),
                    status = status,
                    available = status.isOnlineBookable,
                    observedAt = r.get("last_observed_at", OffsetDateTime::class.java),
                )
            }
    }
}

private fun AvailabilityStatus.toDb(): DbAvailabilityStatus =
    DbAvailabilityStatus.entries.firstOrNull { it.literal == wireValue }
        ?: error("availability status has no DB enum literal: $wireValue")
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.AvailabilityRepoTest'`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityRepoTest.kt
git commit -m "feat(availability): AvailabilityRepo write path + readCurrent"
```

---

## Task 4: `AvailabilityRepo` — `markElapsedAsPast`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityRepo.kt`
- Test: `AvailabilityRepoTest.kt`

**Interfaces:**
- Produces: `fun markElapsedAsPast(reservableIds: List<Long>, today: LocalDate): Int` — for each cell whose current row has `target_date < today` and status ≠ `past`, insert a `past` status-run (chained). Returns rows inserted.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `markElapsedAsPast adds a past status-run for elapsed cells only`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val past = LocalDate.parse("2026-06-01")
        val future = LocalDate.parse("2026-08-01")
        val t = Instant.parse("2026-06-18T10:00:00Z")
        repo.recordObservations(null, listOf(
            AvailabilityRepo.Observation(rid, past, AvailabilityStatus.RESERVED, t),
            AvailabilityRepo.Observation(rid, future, AvailabilityStatus.RESERVED, t),
        ))
        val inserted = repo.markElapsedAsPast(listOf(rid), today = LocalDate.parse("2026-07-04"))
        assertEquals(1, inserted)
        assertEquals(AvailabilityStatus.PAST, repo.readCurrent(listOf(rid), listOf(past)).single().status)
        assertEquals(AvailabilityStatus.RESERVED, repo.readCurrent(listOf(rid), listOf(future)).single().status)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.AvailabilityRepoTest'`
Expected: FAIL — `markElapsedAsPast` unresolved.

- [ ] **Step 3: Implement**

Add to `AvailabilityRepo`:

```kotlin
    /**
     * Insert a terminal `past` status-run for every cell whose current row has an
     * elapsed target_date and is not already `past`. Chained via previous_id so the
     * transition is visible in history. Returns rows inserted.
     */
    fun markElapsedAsPast(
        reservableIds: List<Long>,
        today: LocalDate,
    ): Int {
        if (reservableIds.isEmpty()) return 0
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val elapsed =
                txn.resultQuery(
                    """
                    SELECT DISTINCT ON (reservable_id, target_date) id, reservable_id, target_date
                    FROM availability
                    WHERE reservable_id = ANY(?::bigint[])
                      AND target_date < ?::date
                      AND status <> 'past'
                    ORDER BY reservable_id, target_date, last_observed_at DESC, id DESC
                    """.trimIndent(),
                    reservableIds.toTypedArray(),
                    today,
                ).fetch { it }
            for (row in elapsed) {
                txn.insertInto(AVAILABILITY)
                    .set(AVAILABILITY.RESERVABLE_ID, row.get("reservable_id", Long::class.java))
                    .set(AVAILABILITY.TARGET_DATE, row.get("target_date", LocalDate::class.java))
                    .set(AVAILABILITY.STATUS, DbAvailabilityStatus.past)
                    .set(AVAILABILITY.LAST_OBSERVED_AT, now)
                    .set(AVAILABILITY.PREVIOUS_ID, row.get("id", Long::class.java))
                    .execute()
            }
            elapsed.size
        }
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.AvailabilityRepoTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityRepoTest.kt
git commit -m "feat(availability): AvailabilityRepo.markElapsedAsPast"
```

---

## Task 5: `AvailabilityRepo` — history reads + stats

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityRepo.kt`
- Test: `AvailabilityRepoTest.kt`

**Interfaces:**
- Produces:
  - `data class StatusRun(reservableId, runId, targetDate, status, available, observedFrom: OffsetDateTime?, lastObservedAt: OffsetDateTime)` — one per row; `observedFrom` = the previous row's `last_observed_at` (null for the first).
  - `fun listForReservable(reservableId: Long, limit: Int = 200): List<StatusRun>` — newest first.
  - `fun listForRun(runId: Long, limit: Int = 500): List<StatusRun>` — rows created by that run.
  - `data class TargetDateStats(targetDate, totalRuns: Int, lastOpenAt: OffsetDateTime?, isCurrentlyOpen: Boolean, currentOrLastOpenWindowSec: Int?, medianOpenWindowSec: Int?, opensLast24h: Int)`
  - `fun summarize(reservableId: Long, dates: List<LocalDate>, now: OffsetDateTime = OffsetDateTime.now(), windowHours: Int = 24 * 7): List<TargetDateStats>`

> **Note (spec semantic change):** the old `total_snapshots` counted dense observations; over intervals it becomes `totalRuns` (status-run count), and `flipsLast24h` becomes `opensLast24h` (available-runs started in the last 24h). The DTO field renames land in Task 9.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `history walks the previous_id chain, observedFrom derives from previous`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T11:00:00Z")
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.RESERVED, t1)))
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.AVAILABLE, t2)))
        val runs = repo.listForReservable(rid)
        assertEquals(2, runs.size)
        val current = runs.first { it.status == AvailabilityStatus.AVAILABLE }
        assertEquals(t1, current.observedFrom!!.toInstant()) // start = prior run's last_observed_at
    }

    @Test
    fun `summarize reports an open window from an available run`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T10:30:00Z")
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.AVAILABLE, t1)))
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.AVAILABLE, t2)))
        val stats = repo.summarize(rid, listOf(date), now = OffsetDateTime.parse("2026-06-18T12:00:00Z"))
        val s = stats.single()
        assertEquals(true, s.isCurrentlyOpen)
        assertEquals(1800, s.currentOrLastOpenWindowSec) // t1..t2 = 30 min
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.AvailabilityRepoTest'`
Expected: FAIL — `listForReservable`/`summarize` unresolved.

- [ ] **Step 3: Implement history reads + stats**

Add to `AvailabilityRepo` (uses a window function to attach each row's `observedFrom` = `lag(last_observed_at)` over the per-cell chain ordered by `last_observed_at`):

```kotlin
    data class StatusRun(
        val reservableId: Long,
        val runId: Long?,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val available: Boolean,
        val observedFrom: OffsetDateTime?,
        val lastObservedAt: OffsetDateTime,
    )

    private val statusRunSelect = """
        SELECT reservable_id, run_id, target_date, status, last_observed_at,
               lag(last_observed_at) OVER (
                 PARTITION BY reservable_id, target_date ORDER BY last_observed_at, id
               ) AS observed_from
        FROM availability
    """.trimIndent()

    private fun mapStatusRun(r: org.jooq.Record): StatusRun {
        val status = AvailabilityStatus.parse(r.get("status", String::class.java))
        return StatusRun(
            reservableId = r.get("reservable_id", Long::class.java),
            runId = r.get("run_id", Long::class.java),
            targetDate = r.get("target_date", LocalDate::class.java),
            status = status,
            available = status.isOnlineBookable,
            observedFrom = r.get("observed_from", OffsetDateTime::class.java),
            lastObservedAt = r.get("last_observed_at", OffsetDateTime::class.java),
        )
    }

    fun listForReservable(
        reservableId: Long,
        limit: Int = 200,
    ): List<StatusRun> =
        ctx.resultQuery(
            "SELECT * FROM ($statusRunSelect) t WHERE reservable_id = ? " +
                "ORDER BY target_date DESC, last_observed_at DESC LIMIT ?",
            reservableId,
            limit.coerceIn(1, 1000),
        ).fetch { mapStatusRun(it) }

    fun listForRun(
        runId: Long,
        limit: Int = 500,
    ): List<StatusRun> =
        ctx.resultQuery(
            "SELECT * FROM ($statusRunSelect) t WHERE run_id = ? ORDER BY target_date ASC LIMIT ?",
            runId,
            limit.coerceIn(1, 1000),
        ).fetch { mapStatusRun(it) }

    data class TargetDateStats(
        val targetDate: LocalDate,
        val totalRuns: Int,
        val lastOpenAt: OffsetDateTime?,
        val isCurrentlyOpen: Boolean,
        val currentOrLastOpenWindowSec: Int?,
        val medianOpenWindowSec: Int?,
        val opensLast24h: Int,
    )

    fun summarize(
        reservableId: Long,
        dates: List<LocalDate>,
        now: OffsetDateTime = OffsetDateTime.now(),
        windowHours: Int = DEFAULT_SUMMARY_WINDOW_HOURS,
    ): List<TargetDateStats> {
        if (dates.isEmpty()) return emptyList()
        val windowStart = now.minusHours(windowHours.toLong())
        val opensSince = now.minusHours(24)
        val rows =
            ctx.resultQuery(
                "SELECT * FROM ($statusRunSelect) t WHERE reservable_id = ? " +
                    "AND target_date = ANY(?::date[]) AND last_observed_at >= ? " +
                    "ORDER BY target_date, last_observed_at",
                reservableId,
                dates.toTypedArray(),
                windowStart,
            ).fetch { mapStatusRun(it) }
        val byDate = rows.groupBy { it.targetDate }
        return dates.map { d -> statsFor(d, byDate[d].orEmpty(), opensSince) }
    }

    private fun statsFor(
        date: LocalDate,
        runs: List<StatusRun>,
        opensSince: OffsetDateTime,
    ): TargetDateStats {
        if (runs.isEmpty()) {
            return TargetDateStats(date, 0, null, false, null, null, 0)
        }
        val openRuns = runs.filter { it.available }
        val openWindows =
            openRuns.map { r ->
                val from = r.observedFrom ?: r.lastObservedAt
                java.time.Duration.between(from, r.lastObservedAt).seconds.toInt().coerceAtLeast(0)
            }
        return TargetDateStats(
            targetDate = date,
            totalRuns = runs.size,
            lastOpenAt = openRuns.lastOrNull()?.lastObservedAt,
            isCurrentlyOpen = runs.last().available,
            currentOrLastOpenWindowSec = openWindows.lastOrNull(),
            medianOpenWindowSec = medianOrNull(openWindows),
            opensLast24h = openRuns.count { (it.observedFrom ?: it.lastObservedAt) >= opensSince },
        )
    }

    private fun medianOrNull(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2 else s[mid]
    }
```

Add at file top-level (after imports):

```kotlin
private const val DEFAULT_SUMMARY_WINDOW_HOURS: Int = 24 * 7
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.AvailabilityRepoTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityRepoTest.kt
git commit -m "feat(availability): AvailabilityRepo history reads + interval stats"
```

---

## Task 6: Extract pure freshness/coverage logic

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityFreshness.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityFreshnessTest.kt`

**Interfaces:**
- Produces (pure, no DB):
  - `fun hasFullCoverage(targetCount: Int, dateCount: Int, rowCount: Int): Boolean = rowCount == targetCount * dateCount`
  - `fun isFresh(observedAts: List<Instant>, now: Instant, ttl: Duration): Boolean` — all within ttl.

- [ ] **Step 1: Write the failing test**

```kotlin
package ca.floo.roadtrip.service.availability

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AvailabilityFreshnessTest {
    private val now = Instant.parse("2026-06-18T12:00:00Z")

    @Test
    fun `coverage requires one row per target per date`() {
        assertTrue(hasFullCoverage(targetCount = 2, dateCount = 3, rowCount = 6))
        assertFalse(hasFullCoverage(targetCount = 2, dateCount = 3, rowCount = 5))
    }

    @Test
    fun `fresh only when every observation is within ttl`() {
        val ttl = Duration.ofMinutes(10)
        assertTrue(isFresh(listOf(now.minusSeconds(60)), now, ttl))
        assertFalse(isFresh(listOf(now.minusSeconds(60), now.minusSeconds(3600)), now, ttl))
        assertEquals(true, isFresh(emptyList(), now, ttl))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.AvailabilityFreshnessTest'`
Expected: FAIL — functions unresolved.

- [ ] **Step 3: Implement**

```kotlin
package ca.floo.roadtrip.service.availability

import java.time.Duration
import java.time.Instant

/** Full coverage: exactly one row per (target, date) in the requested window. */
fun hasFullCoverage(
    targetCount: Int,
    dateCount: Int,
    rowCount: Int,
): Boolean = rowCount == targetCount * dateCount

/** Fresh when every observation was seen within [ttl] of [now]. Empty = vacuously fresh. */
fun isFresh(
    observedAts: List<Instant>,
    now: Instant,
    ttl: Duration,
): Boolean {
    val freshAfter = now.minus(ttl)
    return observedAts.all { !it.isBefore(freshAfter) }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.AvailabilityFreshnessTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityFreshness.kt backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityFreshnessTest.kt
git commit -m "feat(availability): extract pure freshness/coverage functions"
```

---

## Task 7: Rename service → `CachedAvailabilityService`, rewire to `AvailabilityRepo`

**Files:**
- Rename: `service/api/SnapshotBackedAvailabilityService.kt` → `service/api/CachedAvailabilityService.kt`
- Modify: `service/availability/ReservableAvailabilityComposer.kt`
- Test: rename `SnapshotBackedAvailabilityServiceTest.kt` → `CachedAvailabilityServiceTest.kt`, retarget to `AvailabilityRepo`

**Interfaces:**
- Consumes: `AvailabilityRepo.recordObservations`, `AvailabilityRepo.readCurrent`, `hasFullCoverage`, `isFresh`.
- Produces: `class CachedAvailabilityService(private val availability: AvailabilityRepo?, private val clock: Clock = Clock.systemUTC())` with unchanged `suspend fun loadOrFetch(request, fetch)` behavior.

- [ ] **Step 1: Rename the files (git mv)**

```bash
git mv backend/src/main/kotlin/ca/floo/roadtrip/service/api/SnapshotBackedAvailabilityService.kt backend/src/main/kotlin/ca/floo/roadtrip/service/api/CachedAvailabilityService.kt
git mv backend/src/test/kotlin/ca/floo/roadtrip/service/api/SnapshotBackedAvailabilityServiceTest.kt backend/src/test/kotlin/ca/floo/roadtrip/service/api/CachedAvailabilityServiceTest.kt
```

- [ ] **Step 2: Rewrite the service**

Replace the class in `CachedAvailabilityService.kt`. Key deltas from the current `SnapshotBackedAvailabilityService`:
- Rename class; `store: AvailabilityCacheStore?` → `availability: AvailabilityRepo?`.
- `cache.loadLatest(dbIds, dates)` → `availability.readCurrent(dbIds, dates)` (returns `List<AvailabilityRepo.CurrentCell>`).
- `cache.recordFetched(runId, observations, ridByDbId)` → build `List<AvailabilityRepo.Observation>` and call `availability.recordObservations(runId, observations)` (the `ridByDbId` map is no longer needed — the interval table has no rid column).
- Coverage/freshness use the pure functions: `hasFullCoverage(request.targets.size, dates.size, rows.size)` and `isFresh(rows.map { it.observedAt.toInstant() }, Instant.now(clock), request.ttl)`.
- `batchFromLatest`/`maxAgeSeconds` operate on `CurrentCell` (fields `reservableId`, `targetDate`, `status`, `observedAt`) instead of `AvailabilitySnapshotRepo.LatestObservation` (same field names — mechanical type swap).
- The UNKNOWN-backfill loop in `recordFetched` is preserved verbatim (still needed so unreturned cells reach full coverage), but emits `AvailabilityRepo.Observation` instead of `AvailabilityCellRepo.CellObservation`.

Full rewritten file:

```kotlin
package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.service.availability.hasFullCoverage
import ca.floo.roadtrip.service.availability.isFresh
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class CachedAvailabilityService(
    private val availability: AvailabilityRepo?,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class TargetReservable(val dbId: Long, val rid: String)

    data class Metadata(
        val provider: String,
        val campgroundId: String? = null,
        val host: String? = null,
        val mapId: String? = null,
        val reservableId: String? = null,
    )

    data class Request(
        val metadata: Metadata,
        val targets: List<TargetReservable>,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val ttl: Duration,
        val runId: Long? = null,
    )

    suspend fun loadOrFetch(
        request: Request,
        fetch: suspend () -> AvailabilityObservationBatch,
    ): AvailabilityObservationBatch {
        val repo = availability
        if (repo == null || request.targets.isEmpty()) return fetch()

        val dates = datesInWindow(request.startDate, request.endDate)
        val dbIds = request.targets.map { it.dbId }
        val cached = repo.readCurrent(dbIds, dates)
        if (hasFullCoverage(request.targets.size, dates.size, cached.size) &&
            isFresh(cached.map { it.observedAt.toInstant() }, Instant.now(clock), request.ttl)
        ) {
            return batchFromLatest(request, cached, hit = true)
        }

        val fetched = fetch()
        recordFetched(repo, request, fetched)

        val latest = repo.readCurrent(dbIds, dates)
        return if (hasFullCoverage(request.targets.size, dates.size, latest.size)) {
            batchFromLatest(
                request = request.copy(metadata = metadataFromBatch(fetched, request.metadata)),
                rows = latest,
                hit = false,
                seasonBlock = fetched.seasonBlock,
            )
        } else {
            fetched.copy(cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = request.ttl.seconds))
        }
    }

    private fun recordFetched(
        repo: AvailabilityRepo,
        request: Request,
        batch: AvailabilityObservationBatch,
    ) {
        val targetByRid = request.targets.associateBy { it.rid }
        val dates = datesInWindow(request.startDate, request.endDate)
        val observedAtByDate =
            batch.observations.groupBy { it.date }.mapValues { (_, o) -> o.maxOf { it.observedAt } }
        val fallbackObservedAt = batch.observations.maxOfOrNull { it.observedAt } ?: Instant.now(clock)
        val covered = mutableSetOf<Pair<Long, LocalDate>>()
        val observations = mutableListOf<AvailabilityRepo.Observation>()
        for (o in batch.observations) {
            val target = targetByRid[o.reservableId] ?: continue
            covered += target.dbId to o.date
            observations += AvailabilityRepo.Observation(target.dbId, o.date, o.status, o.observedAt)
        }
        for (target in request.targets) {
            for (date in dates) {
                if (target.dbId to date in covered) continue
                observations += AvailabilityRepo.Observation(
                    target.dbId, date, AvailabilityStatus.UNKNOWN, observedAtByDate[date] ?: fallbackObservedAt,
                )
            }
        }
        repo.recordObservations(request.runId, observations)
    }

    private fun batchFromLatest(
        request: Request,
        rows: List<AvailabilityRepo.CurrentCell>,
        hit: Boolean,
        seasonBlock: AvailabilitySeasonBlock? = null,
    ): AvailabilityObservationBatch {
        val ridByDbId = request.targets.associate { it.dbId to it.rid }
        val now = Instant.now(clock)
        return AvailabilityObservationBatch(
            provider = request.metadata.provider,
            startDate = request.startDate,
            endDate = request.endDate,
            observations =
                rows.map { row ->
                    ReservableDayObservation(
                        reservableId = ridByDbId[row.reservableId] ?: row.reservableId.toString(),
                        date = row.targetDate,
                        observedAt = row.observedAt.toInstant(),
                        status = row.status,
                    )
                },
            cacheBlock = AvailabilityCacheBlock(hit = hit, ageSeconds = maxAgeSeconds(rows, now), ttlSeconds = request.ttl.seconds),
            seasonBlock = seasonBlock,
            campgroundId = request.metadata.campgroundId,
            host = request.metadata.host,
            mapId = request.metadata.mapId,
            reservableId = request.metadata.reservableId,
        )
    }

    private fun metadataFromBatch(batch: AvailabilityObservationBatch, fallback: Metadata): Metadata =
        fallback.copy(
            provider = batch.provider,
            campgroundId = batch.campgroundId ?: fallback.campgroundId,
            host = batch.host ?: fallback.host,
            mapId = batch.mapId ?: fallback.mapId,
            reservableId = batch.reservableId ?: fallback.reservableId,
        )

    private fun maxAgeSeconds(rows: List<AvailabilityRepo.CurrentCell>, now: Instant): Long =
        rows.maxOfOrNull { Duration.between(it.observedAt.toInstant(), now).seconds.coerceAtLeast(0) } ?: 0

    private fun datesInWindow(startDate: LocalDate, endDate: LocalDate): List<LocalDate> =
        (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt()).map { startDate.plusDays(it.toLong()) }
}
```

- [ ] **Step 3: Update `ReservableAvailabilityComposer`**

In `service/availability/ReservableAvailabilityComposer.kt`:
- Replace `import ca.floo.roadtrip.repo.AvailabilityCacheStore` with `import ca.floo.roadtrip.repo.AvailabilityRepo`.
- Replace `import ...SnapshotBackedAvailabilityService` with `import ...CachedAvailabilityService`.
- Constructor param `cacheStore: AvailabilityCacheStore? = null` → `availability: AvailabilityRepo? = null`.
- `private val snapshotAvailability = SnapshotBackedAvailabilityService(cacheStore)` → `private val cachedAvailability = CachedAvailabilityService(availability)`.
- Update the reference from `snapshotAvailability` to `cachedAvailability` at its use site in `availabilityFor(...)`.

- [ ] **Step 4: Update the renamed test to construct against `AvailabilityRepo`**

In `CachedAvailabilityServiceTest.kt`: change class construction to `CachedAvailabilityService(availability = AvailabilityRepo(ctx), clock = ...)`; change cleanup to `DELETE FROM availability` (drop the `availability_cell`/`availability_snapshot` deletes); keep the existing behavioral assertions (unknown-backfill etc.).

- [ ] **Step 5: Run tests**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.api.CachedAvailabilityServiceTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(availability): CachedAvailabilityService over AvailabilityRepo"
```

---

## Task 8: Rewrite the poller write path

**Files:**
- Modify: `service/scheduler/jobs/AvailabilityPollExecutor.kt`
- Test: `service/scheduler/jobs/AvailabilityPollExecutorTest.kt`

**Interfaces:**
- Consumes: `AvailabilityRepo.recordObservations`, `AvailabilityRepo.markElapsedAsPast`.

- [ ] **Step 1: Replace the executor's repo dependency**

- In construction/fields: replace `cells: AvailabilityCellRepo` with `availability: AvailabilityRepo`. Remove any `AvailabilitySnapshotRepo` usage.
- `cells.markElapsedAsPast(observedReservableIds, ...)` → `availability.markElapsedAsPast(observedReservableIds, ...)`.
- Retention prune: the old `AvailabilitySnapshotRepo.pruneObservationsBefore` is dropped (interval rows are the history; pruning old *closed* runs is out of scope for this task — see spec "retention policy semantics unchanged" is N/A; leave no prune call). Remove the prune call and the `SNAPSHOT_HISTORY_RETENTION` constant.

- [ ] **Step 2: Rewrite `writeCube` as a single-table record**

Replace the whole `writeCube(result, runId)` method — it currently upserts cells then appends snapshot edges in one transaction. The interval repo does both in one call:

```kotlin
    /** Record one fetch group's observations into the availability interval table.
     *  recordObservations bumps unchanged cells and inserts a new status-run on a
     *  change; the transition count feeds run.snapshot_count. */
    private fun writeCube(
        result: GroupFetchResult,
        runId: Long,
    ): Int {
        val batch = result.batch ?: return 0
        val idByRid = result.reservables.associateBy({ it.rid.encode() }, { it.id })
        val observations =
            batch.observations.mapNotNull { obs ->
                val dbId = idByRid[obs.reservableId] ?: return@mapNotNull null
                AvailabilityRepo.Observation(
                    reservableId = dbId,
                    targetDate = obs.date,
                    status = obs.status,
                    observedAt = obs.observedAt,
                )
            }
        if (observations.isEmpty()) return 0
        return availability.recordObservations(runId, observations)
    }
```

(The `run.snapshot_count` now counts *transitions*, matching the old edge count. Rename the local var/label from `snapshotCount` only if it improves clarity; the run column stays.)

- [ ] **Step 3: Update the test's construction and cleanup**

In `AvailabilityPollExecutorTest.kt`: pass `availability = AvailabilityRepo(ctx)` instead of `cells = AvailabilityCellRepo(ctx)`; change cleanup `DELETE FROM availability_snapshot` + `DELETE FROM availability_cell` → `DELETE FROM availability`. Update assertions that queried `availability_cell`/`availability_snapshot` to query `availability` (current state via `AvailabilityRepo.readCurrent`, transitions via `listForRun`).

- [ ] **Step 4: Run tests**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.scheduler.jobs.AvailabilityPollExecutorTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(availability): poller records to the interval table"
```

---

## Task 9: Repoint dashboard routes; delete the old repos/stores

**Files:**
- Modify: `routes/AvailabilityDashboardRoutes.kt`, `routes/AvailabilityWatchRoutes.kt`, `service/availability/WatchAlertDispatcher.kt`, `Main.kt`
- Modify: `models/api/AvailabilityDashboardSchemas.kt` (field renames)
- Delete: `repo/AvailabilityCacheStore.kt`, `repo/AvailabilitySnapshotStore.kt`, `repo/AvailabilitySnapshotRepo.kt`, `repo/AvailabilityHeatmapRepo.kt`, `repo/AvailabilityCellRepo.kt` and their tests.

**Interfaces:**
- Consumes: `AvailabilityRepo.readCurrent`, `AvailabilityRepo.listForReservable`, `AvailabilityRepo.listForRun`, `AvailabilityRepo.summarize`, `AvailabilityRepo.StatusRun`, `AvailabilityRepo.TargetDateStats`.

- [ ] **Step 1: `WatchAlertDispatcher` — read current from `AvailabilityRepo`**

Replace `import ...AvailabilityHeatmapRepo` with `import ...AvailabilityRepo`; constructor field `heatmaps: AvailabilityHeatmapRepo` → `availability: AvailabilityRepo`; `heatmaps.loadHeatmap(ids, dates)` → `availability.readCurrent(ids, dates)`. Field names on the returned rows are identical (`reservableId`, `targetDate`, `status`, `available`, `observedAt`), so the `bookable`/`CellTransition` mapping is unchanged.

- [ ] **Step 2: `AvailabilityWatchRoutes` — heatmap endpoint reads `AvailabilityRepo`**

Replace `val heatmaps = AvailabilityHeatmapRepo(ctx)` with `val availability = AvailabilityRepo(ctx)`; `heatmaps.loadHeatmap(children.map { it.id }, dates)` → `availability.readCurrent(children.map { it.id }, dates)`. The `AvailabilityWatchHeatmap*` response DTOs and the `cellsByPair`/row-building code are unchanged (same field names).

- [ ] **Step 3: `AvailabilityDashboardRoutes` — history reads from `AvailabilityRepo`**

- Replace `val snapshots = AvailabilitySnapshotRepo(ctx)` with `val availability = AvailabilityRepo(ctx)`.
- `snapshots.listForReservable(id, limit)` / `snapshots.listForRun(runId, limit)` → `availability.listForReservable(...)` / `availability.listForRun(...)`, returning `List<AvailabilityRepo.StatusRun>`.
- `snapshots.summarize(id, dates, windowHours=..)` → `availability.summarize(...)`, returning `List<AvailabilityRepo.TargetDateStats>`.
- The "discover distinct dates" query currently hits `AVAILABILITY_SNAPSHOT.TARGET_DATE`; retarget to the generated `AVAILABILITY.TARGET_DATE` / `AVAILABILITY.LAST_OBSERVED_AT` (`observed_at` → `last_observed_at`).
- Update the two `toSchema()` mappers to the new `StatusRun` / `TargetDateStats` fields (below).

- [ ] **Step 4: Rename DTO fields in `AvailabilityDashboardSchemas.kt`**

`AvailabilitySnapshotSchema` — `observedAt`/`observed_at` maps from `StatusRun.lastObservedAt`; add `observed_from`; drop `id`-only concerns are fine to keep. `AvailabilitySnapshotStatsSchema` — rename `total_snapshots` → `total_runs`, `flips_last_24h` → `opens_last_24h`; keep the rest. Update the mapper functions accordingly:

```kotlin
private fun AvailabilityRepo.StatusRun.toSchema(): AvailabilitySnapshotSchema =
    AvailabilitySnapshotSchema(
        reservableId = reservableId,
        runId = runId,
        targetDate = targetDate.toString(),
        observedFrom = observedFrom?.toString(),
        observedAt = lastObservedAt.toString(),
        status = status,
        available = available,
    )

private fun AvailabilityRepo.TargetDateStats.toSchema(): AvailabilitySnapshotStatsSchema =
    AvailabilitySnapshotStatsSchema(
        targetDate = targetDate.toString(),
        totalRuns = totalRuns,
        lastOpenAt = lastOpenAt?.toString(),
        isCurrentlyOpen = isCurrentlyOpen,
        currentOrLastOpenWindowSec = currentOrLastOpenWindowSec,
        medianOpenWindowSec = medianOpenWindowSec,
        opensLast24h = opensLast24h,
    )
```

Update `AvailabilitySnapshotSchema` (remove `id`, add `@SerialName("observed_from") observedFrom: String? = null`) and `AvailabilitySnapshotStatsSchema` (rename the two fields as above).

- [ ] **Step 5: `Main.kt` — wiring**

- Delete the `AvailabilitySnapshotRepo(ctx)`, `AvailabilityCacheStoreImpl(ctx)`, `AvailabilityHeatmapRepo(ctx)`, `AvailabilityCellRepo(ctx)` constructions.
- Introduce `val availability = AvailabilityRepo(ctx)` once; pass it into `ReservableAvailabilityComposer(... availability = availability ...)` (via `AvailabilityQueryServiceImpl` if that's the owner), `WatchAlertDispatcher(availability = availability, ...)`, `AvailabilityPollExecutor(availability = availability, ...)`, and `availabilityDashboardRoutes(ctx)` (already takes `ctx`; it builds its own `AvailabilityRepo(ctx)` in Step 3).

- [ ] **Step 6: Delete the obsolete repos + tests**

```bash
git rm backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityCacheStore.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotStore.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepo.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityCellRepo.kt \
       backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepoTest.kt
```

Also remove/retarget any other test referencing the deleted types (grep below).

- [ ] **Step 7: Verify nothing references the deleted types**

Run: `grep -rn "AvailabilityCacheStore\|AvailabilitySnapshotStore\|AvailabilitySnapshotRepo\|AvailabilityHeatmapRepo\|AvailabilityCellRepo\|SnapshotBackedAvailabilityService" backend/src`
Expected: no matches.

- [ ] **Step 8: Full build**

Run: `./gradlew :backend:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor(availability): delete cell/snapshot repos + cache store, route to AvailabilityRepo"
```

---

## Task 10: Rewrite the 10 Grafana dashboards

**Files:**
- Modify each JSON under `grafana/dashboards/` that references the old tables:
  `availability-cell-matrix.json`, `api-sql-equivalence.json`, `poi-detail.json`,
  `db-stats.json`, `reservable-detail.json`, `poller-run-detail.json`,
  `catalog-explorer.json`, `reservable-availability-watch-drill-down.json`,
  `reservable-stats.json`, `poi-reservables.json`.

**Rewrite rules (apply to each `rawSql`):**
- Current-state panels reading `availability_cell c` → read latest per cell from `availability`:
  `FROM (SELECT DISTINCT ON (reservable_id, target_date) reservable_id, target_date, status, last_observed_at FROM availability ORDER BY reservable_id, target_date, last_observed_at DESC, id DESC) c`.
- History/timeseries panels reading `availability_snapshot s` (e.g. drill-down) → `FROM availability s`, and replace `s.observed_at` with `s.last_observed_at`; drop `s.available`/`s.day_payload` columns (no longer stored) or derive `available` as `(s.status = 'available')`.
- `db-stats.json` table-size panels listing `availability_cell` / `availability_snapshot` → single row `availability`.
- Rename dashboard file/title `availability-cell-matrix` → `availability-matrix` (update `title` and `uid` stays unchanged — do NOT change `uid` per the dashboard-uid-stability rule).

- [ ] **Step 1: Rewrite `availability-cell-matrix.json` primary query**

Replace the `rawSql` that does `FROM availability_cell c JOIN scoped sc ...` with:

```sql
WITH scoped AS (
  SELECT r.id AS reservable_id,
         coalesce(r.loop || ' / ', '') || coalesce(r.name, r.vendor_id) AS label
  FROM reservables r
  JOIN reservable_pois rp ON rp.reservable_id = r.id
  WHERE ${poi_id:sqlstring} ~ '^[0-9]+$'
    AND rp.poi_id::text = ${poi_id:sqlstring}
    AND r.deleted_at IS NULL
), current AS (
  SELECT DISTINCT ON (reservable_id, target_date)
         reservable_id, target_date, status
  FROM availability
  ORDER BY reservable_id, target_date, last_observed_at DESC, id DESC
)
SELECT format('[%s] %s', sc.reservable_id, sc.label) AS site,
       to_char(c.target_date, 'MM-DD Dy')            AS day,
       c.status::text                                AS status
FROM current c
JOIN scoped sc ON sc.reservable_id = c.reservable_id
WHERE c.target_date >= ${__from:date:YYYY-MM-DD}::date
  AND c.target_date <= ${__to:date:YYYY-MM-DD}::date
ORDER BY sc.label, c.target_date;
```

- [ ] **Step 2: Rewrite the drill-down dashboard queries**

In `reservable-availability-watch-drill-down.json`, both queries: `FROM availability_snapshot s` → `FROM availability s`, `s.observed_at` → `s.last_observed_at`, and `$__timeFilter(s.observed_at)` → `$__timeFilter(s.last_observed_at)`. For the detail query `SELECT s.*`, replace with an explicit column list (`s.id, s.reservable_id, s.run_id, s.target_date, s.status, s.last_observed_at, s.previous_id`) since `available`/`day_payload` no longer exist.

- [ ] **Step 3: Sweep the remaining 8 dashboards**

Run: `grep -l "availability_cell\|availability_snapshot" grafana/dashboards/*.json`
For each hit, apply the rewrite rules above. Re-run the grep until it returns nothing except intentional history references now pointing at `availability`.

- [ ] **Step 4: Restart Grafana + verify panels load**

Run: `make reset-db` (fresh schema) then confirm tilt restarts Grafana on dashboard change.
Verify with the preview/browse flow that `availability-matrix` and the drill-down dashboards render without SQL errors (check Grafana query inspector or the panel error state).

- [ ] **Step 5: Commit**

```bash
git add grafana/dashboards/
git commit -m "refactor(grafana): dashboards read the availability interval table"
```

---

## Task 11: Extract `ImportRunRepo` (single writer of `import_runs`)

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/ImportRunRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/ImportRunRepoTest.kt`
- Modify: `repo/PoiRepo.kt`, `repo/ReservableRepo.kt`

**Interfaces:**
- Produces:
  - `fun start(source: String): Long` — insert a `started` row, return id.
  - `fun complete(runId: Long, seenCount: Int)` — mark `completed`.
  - `fun fail(runId: Long, notes: String)` — mark `failed`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ca.floo.roadtrip.repo

import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ImportRunRepoTest : SharedDbTest() {
    @BeforeEach fun cleanup() { ctx.execute("DELETE FROM import_runs") }

    @Test
    fun `start then complete records seen count and status`() {
        val repo = ImportRunRepo(ctx)
        val id = repo.start("recgov")
        repo.complete(id, seenCount = 42)
        val row = ctx.fetchOne("SELECT status, seen_count FROM import_runs WHERE id = ?", id)!!
        assertEquals("completed", row.get("status", String::class.java))
        assertEquals(42, row.get("seen_count", Int::class.java))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.ImportRunRepoTest'`
Expected: FAIL — `ImportRunRepo` unresolved.

- [ ] **Step 3: Implement `ImportRunRepo`**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.ImportRuns.Companion.IMPORT_RUNS
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Sole writer of the `import_runs` table. */
class ImportRunRepo(
    private val ctx: DSLContext,
) {
    fun start(source: String): Long =
        ctx.insertInto(IMPORT_RUNS)
            .set(IMPORT_RUNS.SOURCE, source)
            .set(IMPORT_RUNS.STATUS, "started")
            .set(IMPORT_RUNS.STARTED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .returningResult(IMPORT_RUNS.ID)
            .fetchOne()!!.value1()!!

    fun complete(runId: Long, seenCount: Int) {
        ctx.update(IMPORT_RUNS)
            .set(IMPORT_RUNS.STATUS, "completed")
            .set(IMPORT_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(IMPORT_RUNS.SEEN_COUNT, seenCount)
            .where(IMPORT_RUNS.ID.eq(runId))
            .execute()
    }

    fun fail(runId: Long, notes: String) {
        ctx.update(IMPORT_RUNS)
            .set(IMPORT_RUNS.STATUS, "failed")
            .set(IMPORT_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(IMPORT_RUNS.NOTES, notes)
            .where(IMPORT_RUNS.ID.eq(runId))
            .execute()
    }
}
```

- [ ] **Step 4: Repoint `PoiRepo` and `ReservableRepo`**

In `PoiRepo` (`Upsert.run()`/`fail()`) and `ReservableRepo` (`runImport()`/`fail()`): construct `ImportRunRepo(ctx)` (or accept it as a constructor dep) and replace the inline `IMPORT_RUNS` insert/update calls with `importRuns.start(...)`, `importRuns.complete(runId, seen)`, `importRuns.fail(runId, notes)`. Remove the `IMPORT_RUNS` import if no longer referenced. When these run inside an existing `ctx.transaction`, construct `ImportRunRepo(txnCtx)` on the transactional context so the run lifecycle stays in the same transaction.

- [ ] **Step 5: Run tests + build**

Run: `./gradlew :backend:build`
Expected: BUILD SUCCESSFUL, including existing PoiRepo/ReservableRepo import tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(repo): single-writer ImportRunRepo for import_runs"
```

---

## Task 12: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Clean build + full test suite**

Run: `./gradlew :backend:build`
Expected: BUILD SUCCESSFUL; all repo/service/route tests green.

- [ ] **Step 2: Reset DB and boot against the running stack**

Run: `make reset-db`
Confirm tilt rebuilds the backend jar and it boots (backend :8765) with no Flyway/jOOQ errors in `preview_logs`/tilt logs.

- [ ] **Step 3: Smoke the availability read path**

Trigger a POI reservables availability request (drawer path) against `:8765`; confirm a cache-miss fetch records rows into `availability` and a second identical request is a cache hit (`cache.hit = true` in the response). Confirm the watch heatmap endpoint returns cells and the dashboard `/api/availability/snapshots?run_id=…` returns transition rows.

- [ ] **Step 4: Confirm dashboards**

Load the `availability-matrix` and watch drill-down Grafana dashboards (`:3000`); confirm no SQL errors.

- [ ] **Step 5: Final grep for leftover references**

Run: `grep -rn "availability_cell\|availability_snapshot" backend/src grafana docs`
Expected: only intentional references (migration history in `db/migration/V17`,`V23`,`V31`, and the spec/design docs describing the change). No live code or dashboard query hits.

---

## Self-Review

- **Spec coverage:** rule codification (Task 1), single `availability` table + no backfill (Task 2), interval write/read (Tasks 3–4), history + stats re-derivation (Task 5), pure-logic testing / option (b) (Task 6), `CachedAvailabilityService` rename + no storage name in service (Task 7), poller (Task 8), deletions + call sites + dashboards (Tasks 9–10), `import_runs` audit fix (Task 11), verification incl. `make reset-db` (Task 12). All spec sections mapped.
- **Placeholder scan:** none — every code step has literal code; mechanical edits give exact before→after strings and grep/commands.
- **Type consistency:** `AvailabilityRepo.Observation`, `CurrentCell`, `StatusRun`, `TargetDateStats` are defined in Tasks 3/5 and consumed with the same field names in Tasks 7–9. `recordObservations`/`readCurrent`/`markElapsedAsPast`/`listForReservable`/`listForRun`/`summarize` names are stable across tasks. DTO field renames (`total_runs`, `opens_last_24h`, `observed_from`) are defined in Task 9 where the mapper and schema both change together.
