# PR2: Watch = Set (Intent) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a watch's scope from "exactly one of `poi_id`/`reservable_id`" into a **set** of targets — one or more `availability_watch_target` rows, each a POI or a reservable — so one watch can span multiple campgrounds/sites, while keeping poller coalescing (PR1) working unchanged underneath.

**Architecture:** Add `availability_watch_target` (watch_id, poi_id XOR reservable_id per row) and drop the single-scope `poi_id`/`reservable_id` columns + `availability_watch_scope_check` from `availability_watch`. `AvailabilityWatchRepo` grows a sibling `AvailabilityWatchTargetRepo` that owns the join table; `Watch.targets: List<WatchTarget>` replaces `Watch.poiId`/`Watch.reservableId`. `WatchScopeResolver.resolve(watch)` iterates every target and unions the resolved reservables (deduped) instead of branching on a single field. `AvailabilityPollerMembership.sync` is otherwise untouched — it already consumes `scopeResolver.resolve(watch)` as a `List<Reservable>`, so widening what that list can come from is the entire seam. Routes/DTOs move from single `poi_id`/`reservable_id` fields to a `targets: [{poi_id, reservable_id}]` array on create, while the response schema keeps derived `poi_id`/`reservable_id` (first target) alongside the new `targets` array so the existing frontend calendar toggle (`web/availability/availability-week.js`) keeps working unmodified. No poller/run/fetch/executor file is touched — that is the regression this PR proves.

**Tech Stack:** Kotlin, Ktor, jOOQ (codegen from Flyway migrations), Postgres, Testcontainers (`SharedDbTest`, shared Postgres container across the suite), kotlinx.coroutines, kotlinx.serialization.

## Global Constraints

- **Build needs JDK 17.** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew` from repo root. The default Corretto 25 breaks the Kotlin compiler.
- **jOOQ includes allowlist.** Any new table must be added to `database.includes` in `backend/build.gradle.kts` (pipe-joined list, currently lines ~175-198) or codegen silently skips it. `availability_watch_target` must be added.
- **Layering (docs/backend-architecture.md):** `routes → service → repo`; `repo` owns all SQL/jOOQ; `service` owns orchestration and holds no Ktor/SQL; routes parse input, call service/repo, set status codes, return DTOs. Prefer typed DTOs — no hand-built JSON strings.
- **No inline magic constants.** List page-size defaults, etc. — already named `const val` in the touched files; keep that pattern, do not introduce new inline literals.
- **The seam constraint (this PR's whole point): no poller/run/fetch/executor change.** Do not touch `AvailabilityPollerRepo.kt`, `AvailabilityPollExecutor.kt`, `AvailabilityRunRepo.kt`, `AvailabilityFetchCallRepo.kt`, `CatalogAvailabilityBatcher`, the scheduler framework, or Grafana dashboards. `AvailabilityPollerMembership.sync`'s signature and body are unchanged — it already takes `scopeResolver.resolve(watch)` as an opaque `List<Reservable>`; PR2 only changes what feeds that list.
- **No leaky abstractions.** `WatchScopeResolver` and the target repo must not know about providers/vendors — that stays in `AvailabilityTargetResolver` (untouched).
- **No half-finished implementations.** Every new repo method must be exercised by a test in this plan; no stub throws `UnsupportedOperationException` outside an explicit capability gate.
- **Backward-compatible API.** The existing frontend (`web/availability/availability-week.js`, `web/api/watches-api.js`) posts `{poi_id, ...}` and reads `w.poi_id` from list responses. PR2 must not break it: the create DTO accepts the legacy single-scope shape as sugar for a one-element `targets` array, and the response schema keeps `poi_id`/`reservable_id` as the first target for read compatibility.

---

## File Structure

**New files:**
- `backend/src/main/resources/db/migration/V29__watch_target_set.sql` — `availability_watch_target` table + backfill + drop of `availability_watch.poi_id`/`reservable_id`/scope CHECK.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchTargetRepo.kt` — owns all SQL for `availability_watch_target` (insert set, list by watch, delete by watch).
- `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchTargetRepoTest.kt`
- `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/WatchScopeResolverTest.kt` (scope resolver had no dedicated unit test before PR2; PR2's fan-out logic is exactly the kind of pure-function branching that needs one).

**Modified:**
- `backend/build.gradle.kts` — add `availability_watch_target` to the jOOQ includes allowlist.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt` — `Watch.poiId`/`Watch.reservableId`/`Watch.reservable` replaced by `Watch.targets: List<WatchTarget>`; `CreateInput`/`UpdateInput` take `targets: List<TargetInput>`; `create`/`update` write targets transactionally via `AvailabilityWatchTargetRepo`; `list`/`count` filters (`poiId`/`reservableId`) become EXISTS-subquery filters over the target table.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchScopeResolver.kt` — `resolve(watch)` iterates `watch.targets`, resolves each (POI-with-filters expands to reservables; reservable target resolves to itself), unions and de-duplicates by reservable id.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchService.kt` — `create`/`update` pass `targets` through to the repo; no membership-sync logic changes (it already only calls `scopeResolver.resolve(watch)`).
- `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt` — add `AvailabilityWatchTargetSchema`; `AvailabilityWatchCreateRequest`/`UpdateRequest` add `targets: List<AvailabilityWatchTargetSchema>? = null` alongside the legacy `poi_id`/`reservable_id`/`reservable_rid` fields; `AvailabilityWatchSchema` adds `targets` and keeps derived `poi_id`/`reservable_id` (first target).
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt` — `resolveCreateScope` widens to build a `List<TargetInput>` from either `targets` or the legacy single-scope fields; `toSchema()` maps `Watch.targets` to the DTO and derives `poi_id`/`reservable_id`.
- `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchServiceTest.kt` — extend with a multi-target create case.
- `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollerMembershipTest.kt` — no logic change needed (it already drives `membership.sync` off `WatchScopeResolver`), but its `insertActiveWatch` raw-SQL helper must insert into `availability_watch_target` instead of the dropped `poi_id`/`reservable_id` columns — update the helper, not the assertions.

**Unmodified (proof of the seam — call out explicitly in the regression task):**
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollerMembership.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/AvailabilityPollExecutor.kt` (or equivalent executor file)
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityRunRepo.kt`, `AvailabilityFetchCallRepo.kt`
- `grafana/dashboards/reservable-availability-watch-drill-down.json`

---

## Interfaces (locked signatures used across tasks)

```kotlin
// AvailabilityWatchTargetRepo.kt
class AvailabilityWatchTargetRepo(private val ctx: DSLContext) {
    data class TargetInput(val poiId: Long?, val reservableId: Long?)
    data class WatchTarget(val id: Long, val watchId: Long, val poiId: Long?, val reservableId: Long?)

    fun replaceForWatch(watchId: Long, targets: List<TargetInput>)   // delete-all + insert-all, same txn
    fun listForWatch(watchId: Long): List<WatchTarget>
    fun deleteForWatch(watchId: Long): Int
}

// AvailabilityWatchRepo.kt (Watch shape after PR2)
data class Watch(
    val id: Long,
    val targets: List<AvailabilityWatchTargetRepo.WatchTarget>,   // NEW, replaces poiId/reservableId/reservable
    val reservableFilters: JsonObject,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val cadenceSec: Int,
    val triggerKinds: List<String>,
    val triggerConfig: JsonObject,
    val stopWhenTriggered: Boolean,
    val status: WatchStatus,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class CreateInput(
    val targets: List<AvailabilityWatchTargetRepo.TargetInput>,   // NEW, replaces poiId/reservableId
    val reservableFilters: JsonObject,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val cadenceSec: Int,
    val triggerKinds: List<String>,
    val triggerConfig: JsonObject,
    val stopWhenTriggered: Boolean,
)
// UpdateInput unchanged except: val targets: List<AvailabilityWatchTargetRepo.TargetInput>? = null   // null = leave as-is

// WatchScopeResolver.kt
class WatchScopeResolver(private val reservablesRepo: ReservableRepo) {
    fun resolve(watch: AvailabilityWatchRepo.Watch): List<Reservable>
    // Iterates watch.targets; a reservable-target resolves to itself; a
    // poi-target expands via resolvePoi(poiId, watch.reservableFilters) as
    // before. Unions across all targets, de-duplicated by Reservable.id,
    // first-seen order preserved.
}
```

`AvailabilityPollerMembership.sync(watch, repo, tighterCadencePull)` keeps its exact existing signature and body — it calls `scopeResolver.resolve(watch)` and does not touch `watch.targets` directly. This is the load-bearing fact that makes PR2 a pure intent-layer change.

---

### Task 1: `V29` migration — `availability_watch_target` + backfill + drop old columns

**Files:**
- Create: `backend/src/main/resources/db/migration/V29__watch_target_set.sql`
- Modify: `backend/build.gradle.kts` (jOOQ includes allowlist, currently a `listOf(...)` around line 175)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchTargetRepoTest.kt` (created in Task 2; this task is verified by running the full suite once, since a migration has no unit test of its own — Flyway applies it as part of `SharedDbTest` bootstrap)

**Interfaces:**
- Produces: table `availability_watch_target(id, watch_id, poi_id, reservable_id)` with `CHECK ((poi_id IS NOT NULL) <> (reservable_id IS NOT NULL))`; `availability_watch` loses `poi_id`, `reservable_id`, and `availability_watch_scope_check`. jOOQ generates `ca.floo.roadtrip.db.generated.tables.AvailabilityWatchTarget`.

- [ ] **Step 1: Write the migration SQL**

```sql
-- PR2: watch scope widens from "exactly one of poi_id/reservable_id" to a
-- SET of targets. One row per (POI or reservable) the watch covers. Backfill
-- one row per existing watch's single-scope column before dropping it, so no
-- watch silently loses its scope. Coalescing (PR1's poller layer) is
-- unaffected — WatchScopeResolver still hands AvailabilityPollerMembership a
-- flat List<Reservable>; only how that list is derived changes.

CREATE TABLE availability_watch_target (
  id             BIGSERIAL NOT NULL PRIMARY KEY,
  watch_id       BIGINT    NOT NULL REFERENCES availability_watch(id) ON DELETE CASCADE,
  poi_id         BIGINT    REFERENCES pois(id)        ON DELETE CASCADE,
  reservable_id  BIGINT    REFERENCES reservables(id) ON DELETE CASCADE,
  CHECK ((poi_id IS NOT NULL) <> (reservable_id IS NOT NULL))
);

CREATE INDEX availability_watch_target_watch_idx
  ON availability_watch_target (watch_id);
CREATE INDEX availability_watch_target_poi_idx
  ON availability_watch_target (poi_id)
  WHERE poi_id IS NOT NULL;
CREATE INDEX availability_watch_target_reservable_idx
  ON availability_watch_target (reservable_id)
  WHERE reservable_id IS NOT NULL;

-- Backfill: one target row per existing watch's single scope column.
INSERT INTO availability_watch_target (watch_id, poi_id, reservable_id)
SELECT id, poi_id, reservable_id
FROM availability_watch
WHERE poi_id IS NOT NULL OR reservable_id IS NOT NULL;

ALTER TABLE availability_watch
  DROP CONSTRAINT availability_watch_scope_check,
  DROP COLUMN poi_id,
  DROP COLUMN reservable_id;
```

- [ ] **Step 2: Add the new table to the jOOQ includes allowlist**

In `backend/build.gradle.kts`, inside the `includes = listOf(...)` block, add `"availability_watch_target"` alphabetically next to `"availability_watch_poller"`:

```kotlin
                        includes =
                            listOf(
                                "alerts",
                                "api_cache",
                                "availability_fetch_call",
                                "availability_poller",
                                "availability_run",
                                "availability_snapshot",
                                "availability_status",
                                "availability_watch",
                                "availability_watch_poller",
                                "availability_watch_target",
                                "booking_provider",
                                "governing_body",
                                "import_runs",
                                "ingest_runs",
                                "matches",
                                "pois",
                                "reservable_availability_log",
                                "reservable_availability_monitors",
                                "reservable_pois",
                                "reservables",
                                "schedules",
                                "settings",
                            ).joinToString("|")
```

- [ ] **Step 3: Set JDK 17 and run a build to confirm the migration applies and jOOQ codegen picks up the new table**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:compileKotlin
```
Expected: BUILD SUCCESSFUL, and `backend/build/generated-src/jooq/main/ca/floo/roadtrip/db/generated/tables/AvailabilityWatchTarget.kt` exists after the build.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V29__watch_target_set.sql backend/build.gradle.kts
git commit -m "feat(watch): V29 migration — availability_watch_target set + drop single-scope columns"
```

---

### Task 2: `AvailabilityWatchTargetRepo` — owns the target join table

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchTargetRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchTargetRepoTest.kt`

**Interfaces:**
- Consumes: jOOQ table `AVAILABILITY_WATCH_TARGET` (from Task 1).
- Produces: `AvailabilityWatchTargetRepo.TargetInput(poiId, reservableId)`, `AvailabilityWatchTargetRepo.WatchTarget(id, watchId, poiId, reservableId)`, methods `replaceForWatch`, `listForWatch`, `deleteForWatch` — consumed by `AvailabilityWatchRepo` in Task 3.

- [ ] **Step 1: Write the failing test**

```kotlin
package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityWatchTargetRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_watch_target")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private fun insertPoi(): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at
                ) VALUES (
                    'test', 'poi-target-repo', 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, NULL, '2026-06-01 00:00:00+00'::timestamptz
                ) RETURNING id
                """.trimIndent(),
            )!!
            .get("id", Long::class.java)

    private fun insertReservable(vendorId: String): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (type, vendor, vendor_id, name, source)
                VALUES ('site', 'test', ?, ?, 'test')
                RETURNING id
                """.trimIndent(),
                vendorId,
                "Site $vendorId",
            )!!
            .get("id", Long::class.java)

    private fun insertWatch(): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO availability_watch (start_date, end_date, cadence_sec, trigger_kinds)
                VALUES ('2026-07-04'::date, '2026-07-06'::date, 60, ARRAY['atc'])
                RETURNING id
                """.trimIndent(),
            )!!
            .get("id", Long::class.java)

    @Test
    fun `replaceForWatch inserts a mixed set of poi and reservable targets`() {
        val watchId = insertWatch()
        val poiId = insertPoi()
        val reservableId = insertReservable("site-a")
        val repo = AvailabilityWatchTargetRepo(ctx)

        repo.replaceForWatch(
            watchId,
            listOf(
                AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, reservableId = null),
                AvailabilityWatchTargetRepo.TargetInput(poiId = null, reservableId = reservableId),
            ),
        )

        val targets = repo.listForWatch(watchId)
        assertEquals(2, targets.size)
        assertTrue(targets.any { it.poiId == poiId })
        assertTrue(targets.any { it.reservableId == reservableId })
    }

    @Test
    fun `replaceForWatch on an existing set drops stale targets`() {
        val watchId = insertWatch()
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchTargetRepo(ctx)

        repo.replaceForWatch(watchId, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null)))
        repo.replaceForWatch(watchId, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null)))

        val targets = repo.listForWatch(watchId)
        assertEquals(1, targets.size)
        assertEquals(poiB, targets.single().poiId)
    }

    @Test
    fun `deleteForWatch removes all targets for that watch only`() {
        val watchA = insertWatch()
        val watchB = insertWatch()
        val poi = insertPoi()
        val repo = AvailabilityWatchTargetRepo(ctx)
        repo.replaceForWatch(watchA, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, reservableId = null)))
        repo.replaceForWatch(watchB, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, reservableId = null)))

        val deleted = repo.deleteForWatch(watchA)

        assertEquals(1, deleted)
        assertTrue(repo.listForWatch(watchA).isEmpty())
        assertEquals(1, repo.listForWatch(watchB).size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test --tests "ca.floo.roadtrip.repo.AvailabilityWatchTargetRepoTest"
```
Expected: FAIL — compile error, `AvailabilityWatchTargetRepo` does not exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityWatchTarget.Companion.AVAILABILITY_WATCH_TARGET
import org.jooq.DSLContext
import org.jooq.Record

/**
 * Owns all SQL for `availability_watch_target` — the set of POIs/reservables
 * a watch covers. A watch's scope is exactly this set; [AvailabilityWatchRepo]
 * delegates all target reads/writes here rather than embedding the join
 * table's SQL inline, so the two repos can't drift on shape.
 */
class AvailabilityWatchTargetRepo(
    private val ctx: DSLContext,
) {
    data class TargetInput(
        val poiId: Long?,
        val reservableId: Long?,
    ) {
        init {
            require((poiId == null) xor (reservableId == null)) {
                "exactly one of poiId/reservableId must be set per target"
            }
        }
    }

    data class WatchTarget(
        val id: Long,
        val watchId: Long,
        val poiId: Long?,
        val reservableId: Long?,
    )

    /**
     * Replaces the entire target set for [watchId] with exactly [targets].
     * Delete-then-insert rather than diffing — target sets are small
     * (typically 1-5 rows) and callers always supply the full desired set,
     * so there is no partial-update case to preserve row identity for.
     */
    fun replaceForWatch(
        watchId: Long,
        targets: List<TargetInput>,
    ) {
        require(targets.isNotEmpty()) { "a watch must have at least one target" }
        ctx
            .deleteFrom(AVAILABILITY_WATCH_TARGET)
            .where(AVAILABILITY_WATCH_TARGET.WATCH_ID.eq(watchId))
            .execute()
        targets.forEach { t ->
            ctx
                .insertInto(AVAILABILITY_WATCH_TARGET)
                .set(AVAILABILITY_WATCH_TARGET.WATCH_ID, watchId)
                .set(AVAILABILITY_WATCH_TARGET.POI_ID, t.poiId)
                .set(AVAILABILITY_WATCH_TARGET.RESERVABLE_ID, t.reservableId)
                .execute()
        }
    }

    fun listForWatch(watchId: Long): List<WatchTarget> =
        ctx
            .selectFrom(AVAILABILITY_WATCH_TARGET)
            .where(AVAILABILITY_WATCH_TARGET.WATCH_ID.eq(watchId))
            .orderBy(AVAILABILITY_WATCH_TARGET.ID.asc())
            .fetch { fromRecord(it) }

    fun deleteForWatch(watchId: Long): Int =
        ctx
            .deleteFrom(AVAILABILITY_WATCH_TARGET)
            .where(AVAILABILITY_WATCH_TARGET.WATCH_ID.eq(watchId))
            .execute()

    private fun fromRecord(r: Record): WatchTarget =
        WatchTarget(
            id = r.get(AVAILABILITY_WATCH_TARGET.ID)!!,
            watchId = r.get(AVAILABILITY_WATCH_TARGET.WATCH_ID)!!,
            poiId = r.get(AVAILABILITY_WATCH_TARGET.POI_ID),
            reservableId = r.get(AVAILABILITY_WATCH_TARGET.RESERVABLE_ID),
        )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test --tests "ca.floo.roadtrip.repo.AvailabilityWatchTargetRepoTest"
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchTargetRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchTargetRepoTest.kt
git commit -m "feat(watch): AvailabilityWatchTargetRepo owns availability_watch_target CRUD"
```

---

### Task 3: `AvailabilityWatchRepo` — `Watch` becomes a set of targets

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepoTest.kt` (new — PR1 had no dedicated repo test file for `AvailabilityWatchRepo`; watch-repo behavior was covered indirectly through `AvailabilityWatchServiceTest`. PR2's multi-target create/list/count logic is repo-owned SQL and needs its own test.)

**Interfaces:**
- Consumes: `AvailabilityWatchTargetRepo.TargetInput`, `AvailabilityWatchTargetRepo.WatchTarget` (Task 2).
- Produces: `AvailabilityWatchRepo.Watch.targets: List<AvailabilityWatchTargetRepo.WatchTarget>` (replaces `poiId`/`reservableId`/`reservable`); `CreateInput.targets`, `UpdateInput.targets` — consumed by `AvailabilityWatchService` (Task 5) and routes (Task 6).

- [ ] **Step 1: Write the failing test**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.service.availability.WatchStatus
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityWatchRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_watch_target")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private var poiSeq = 0

    private fun insertPoi(): Long {
        val sourceId = "poi-repo-${poiSeq++}"
        return ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at
                ) VALUES (
                    'test', ?, 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, NULL, '2026-06-01 00:00:00+00'::timestamptz
                ) RETURNING id
                """.trimIndent(),
                sourceId,
            )!!
            .get("id", Long::class.java)
    }

    private fun createInput(targets: List<AvailabilityWatchTargetRepo.TargetInput>): AvailabilityWatchRepo.CreateInput =
        AvailabilityWatchRepo.CreateInput(
            targets = targets,
            reservableFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-06"),
            cadenceSec = 60,
            triggerKinds = listOf("atc"),
            triggerConfig = JsonObject(emptyMap()),
            stopWhenTriggered = false,
        )

    @Test
    fun `create persists a multi-poi target set`() {
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)

        val watch =
            repo.create(
                createInput(
                    listOf(
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null),
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null),
                    ),
                ),
            )

        assertEquals(2, watch.targets.size)
        assertEquals(setOf(poiA, poiB), watch.targets.mapNotNull { it.poiId }.toSet())
    }

    @Test
    fun `findById reloads the persisted target set`() {
        val poi = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val created = repo.create(createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, reservableId = null))))

        val reloaded = repo.findById(created.id)!!

        assertEquals(1, reloaded.targets.size)
        assertEquals(poi, reloaded.targets.single().poiId)
    }

    @Test
    fun `update replaces the target set when targets is provided`() {
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val created = repo.create(createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null))))

        val updated =
            repo.update(
                created.id,
                AvailabilityWatchRepo.UpdateInput(targets = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null))),
            )!!

        assertEquals(1, updated.targets.size)
        assertEquals(poiB, updated.targets.single().poiId)
    }

    @Test
    fun `update without targets leaves the existing target set untouched`() {
        val poi = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val created = repo.create(createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, reservableId = null))))

        val updated = repo.update(created.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))!!

        assertEquals(1, updated.targets.size)
        assertEquals(poi, updated.targets.single().poiId)
        assertEquals(WatchStatus.PAUSED, updated.status)
    }

    @Test
    fun `list filtered by poiId matches watches whose target set includes that poi`() {
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val watchWithA =
            repo.create(
                createInput(
                    listOf(
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null),
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null),
                    ),
                ),
            )
        val watchWithBOnly = repo.create(createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null))))

        val filtered = repo.list(poiId = poiA)

        assertEquals(listOf(watchWithA.id), filtered.map { it.id })
        assertTrue(repo.list(poiId = poiB).map { it.id }.toSet() == setOf(watchWithA.id, watchWithBOnly.id))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test --tests "ca.floo.roadtrip.repo.AvailabilityWatchRepoTest"
```
Expected: FAIL — compile error, `CreateInput` has no `targets` parameter yet (still has `poiId`/`reservableId`).

- [ ] **Step 3: Rewrite `AvailabilityWatchRepo.kt`**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityWatch.Companion.AVAILABILITY_WATCH
import ca.floo.roadtrip.db.generated.tables.AvailabilityWatchTarget.Companion.AVAILABILITY_WATCH_TARGET
import ca.floo.roadtrip.service.availability.WatchStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectField
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime

private const val DEFAULT_LIST_LIMIT = 100
private const val MAX_LIST_LIMIT = 500

class AvailabilityWatchRepo(
    private val ctx: DSLContext,
) {
    private val targetsRepo = AvailabilityWatchTargetRepo(ctx)
    private val json = Json

    data class CreateInput(
        val targets: List<AvailabilityWatchTargetRepo.TargetInput>,
        val reservableFilters: JsonObject,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val cadenceSec: Int,
        val triggerKinds: List<String>,
        val triggerConfig: JsonObject,
        val stopWhenTriggered: Boolean,
    )

    data class UpdateInput(
        val targets: List<AvailabilityWatchTargetRepo.TargetInput>? = null,
        val reservableFilters: JsonObject? = null,
        val startDate: LocalDate? = null,
        val endDate: LocalDate? = null,
        val cadenceSec: Int? = null,
        val triggerKinds: List<String>? = null,
        val triggerConfig: JsonObject? = null,
        val stopWhenTriggered: Boolean? = null,
        val status: WatchStatus? = null,
    )

    data class Watch(
        val id: Long,
        val targets: List<AvailabilityWatchTargetRepo.WatchTarget>,
        val reservableFilters: JsonObject,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val cadenceSec: Int,
        val triggerKinds: List<String>,
        val triggerConfig: JsonObject,
        val stopWhenTriggered: Boolean,
        val status: WatchStatus,
        val createdAt: OffsetDateTime,
        val updatedAt: OffsetDateTime,
    )

    fun create(input: CreateInput): Watch {
        require(input.targets.isNotEmpty()) { "a watch must have at least one target" }
        val id =
            ctx
                .insertInto(AVAILABILITY_WATCH)
                .set(
                    AVAILABILITY_WATCH.RESERVABLE_FILTERS,
                    JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.reservableFilters)),
                ).set(AVAILABILITY_WATCH.START_DATE, input.startDate)
                .set(AVAILABILITY_WATCH.END_DATE, input.endDate)
                .set(AVAILABILITY_WATCH.CADENCE_SEC, input.cadenceSec)
                .set(AVAILABILITY_WATCH.TRIGGER_KINDS, input.triggerKinds.toTypedArray())
                .set(AVAILABILITY_WATCH.TRIGGER_CONFIG, JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.triggerConfig)))
                .set(AVAILABILITY_WATCH.STOP_WHEN_TRIGGERED, input.stopWhenTriggered)
                .returningResult(AVAILABILITY_WATCH.ID)
                .fetchOne()!!
                .value1()!!
        targetsRepo.replaceForWatch(id, input.targets)
        return findById(id)!!
    }

    fun findById(id: Long): Watch? = baseSelect().where(AVAILABILITY_WATCH.ID.eq(id)).fetchOne()?.let(::fromRecord)

    fun list(
        status: WatchStatus? = null,
        poiId: Long? = null,
        reservableId: Long? = null,
        limit: Int = DEFAULT_LIST_LIMIT,
        offset: Int = 0,
    ): List<Watch> {
        val effectiveLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        return baseSelect()
            .where(scopeConditions(status, poiId, reservableId))
            .orderBy(AVAILABILITY_WATCH.CREATED_AT.desc(), AVAILABILITY_WATCH.ID.desc())
            .limit(effectiveLimit)
            .offset(offset)
            .fetch { fromRecord(it) }
    }

    fun count(
        status: WatchStatus? = null,
        poiId: Long? = null,
        reservableId: Long? = null,
    ): Int =
        ctx
            .selectCount()
            .from(AVAILABILITY_WATCH)
            .where(scopeConditions(status, poiId, reservableId))
            .fetchOne(0, Int::class.java) ?: 0

    /**
     * `poiId`/`reservableId` filters now match "watch's target set contains
     * this poi/reservable" rather than a single-column equality, since a
     * watch can have multiple targets. Modeled as an EXISTS subquery against
     * `availability_watch_target` rather than a join, so filtering never
     * duplicates a watch row when it has multiple matching targets.
     */
    private fun scopeConditions(
        status: WatchStatus?,
        poiId: Long?,
        reservableId: Long?,
    ): org.jooq.Condition {
        val conds = mutableListOf<org.jooq.Condition>()
        if (status != null) conds += AVAILABILITY_WATCH.STATUS.eq(status.wireValue)
        if (poiId != null) {
            conds +=
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(AVAILABILITY_WATCH_TARGET)
                        .where(AVAILABILITY_WATCH_TARGET.WATCH_ID.eq(AVAILABILITY_WATCH.ID))
                        .and(AVAILABILITY_WATCH_TARGET.POI_ID.eq(poiId)),
                )
        }
        if (reservableId != null) {
            conds +=
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(AVAILABILITY_WATCH_TARGET)
                        .where(AVAILABILITY_WATCH_TARGET.WATCH_ID.eq(AVAILABILITY_WATCH.ID))
                        .and(AVAILABILITY_WATCH_TARGET.RESERVABLE_ID.eq(reservableId)),
                )
        }
        return if (conds.isEmpty()) DSL.noCondition() else DSL.and(conds)
    }

    fun update(
        id: Long,
        input: UpdateInput,
    ): Watch? {
        var query = ctx.update(AVAILABILITY_WATCH).set(AVAILABILITY_WATCH.UPDATED_AT, OffsetDateTime.now())
        if (input.reservableFilters != null) {
            query =
                query.set(
                    AVAILABILITY_WATCH.RESERVABLE_FILTERS,
                    JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.reservableFilters)),
                )
        }
        if (input.startDate != null) query = query.set(AVAILABILITY_WATCH.START_DATE, input.startDate)
        if (input.endDate != null) query = query.set(AVAILABILITY_WATCH.END_DATE, input.endDate)
        if (input.cadenceSec != null) query = query.set(AVAILABILITY_WATCH.CADENCE_SEC, input.cadenceSec)
        if (input.triggerKinds != null) query = query.set(AVAILABILITY_WATCH.TRIGGER_KINDS, input.triggerKinds.toTypedArray())
        if (input.triggerConfig != null) {
            query =
                query.set(
                    AVAILABILITY_WATCH.TRIGGER_CONFIG,
                    JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.triggerConfig)),
                )
        }
        if (input.stopWhenTriggered != null) query = query.set(AVAILABILITY_WATCH.STOP_WHEN_TRIGGERED, input.stopWhenTriggered)
        if (input.status != null) query = query.set(AVAILABILITY_WATCH.STATUS, input.status.wireValue)
        val rows = query.where(AVAILABILITY_WATCH.ID.eq(id)).execute()
        if (rows == 0) return null
        if (input.targets != null) targetsRepo.replaceForWatch(id, input.targets)
        return findById(id)
    }

    fun delete(id: Long): Boolean = ctx.deleteFrom(AVAILABILITY_WATCH).where(AVAILABILITY_WATCH.ID.eq(id)).execute() > 0

    private fun baseSelect() = ctx.select(AVAILABILITY_WATCH.fields().toList()).from(AVAILABILITY_WATCH)

    /**
     * Exposed so sibling repos (e.g. [AvailabilityPollerRepo]) can extend
     * this select with their own conditions rather than re-deriving the
     * watch row mapping. Targets are no longer part of the base select (they
     * are N rows per watch); [fromRecord] loads them via a second query.
     */
    internal fun baseSelectFields(): List<SelectField<*>> = AVAILABILITY_WATCH.fields().toList()

    internal fun fromRecord(r: Record): Watch =
        Watch(
            id = r.get(AVAILABILITY_WATCH.ID)!!,
            targets = targetsRepo.listForWatch(r.get(AVAILABILITY_WATCH.ID)!!),
            reservableFilters = json.parseToJsonElement(r.get(AVAILABILITY_WATCH.RESERVABLE_FILTERS)!!.data()).jsonObject,
            startDate = r.get(AVAILABILITY_WATCH.START_DATE)!!,
            endDate = r.get(AVAILABILITY_WATCH.END_DATE)!!,
            cadenceSec = r.get(AVAILABILITY_WATCH.CADENCE_SEC)!!,
            triggerKinds = r.get(AVAILABILITY_WATCH.TRIGGER_KINDS)!!.filterNotNull(),
            triggerConfig = json.parseToJsonElement(r.get(AVAILABILITY_WATCH.TRIGGER_CONFIG)!!.data()).jsonObject,
            stopWhenTriggered = r.get(AVAILABILITY_WATCH.STOP_WHEN_TRIGGERED)!!,
            status = WatchStatus.parse(r.get(AVAILABILITY_WATCH.STATUS)!!) ?: error("invalid watch status"),
            createdAt = r.get(AVAILABILITY_WATCH.CREATED_AT)!!,
            updatedAt = r.get(AVAILABILITY_WATCH.UPDATED_AT)!!,
        )
}
```

Note: `baseSelect()` no longer left-joins `RESERVABLES` (that join only made sense for a single `reservable_id` column) and `fromRecord` now issues one extra query per watch to load targets via `targetsRepo.listForWatch`. This trades one N+1-shaped read for correctness and simplicity; watch list pages are capped at `MAX_LIST_LIMIT = 500` and this is an admin/dashboard-scale endpoint, not a hot path — acceptable per the "correctness over fetch perf" project convention. `AvailabilityPollerRepo.liveWatchesForPoller` (Task 4) follows the same pattern.

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test --tests "ca.floo.roadtrip.repo.AvailabilityWatchRepoTest"
```
Expected: PASS (5 tests). This step will also surface compile errors in every other file that still constructs `CreateInput`/reads `Watch.poiId` — fix those now before moving on (they are covered by Tasks 4-6 below; if the compiler complains about `AvailabilityPollerRepo.kt`'s `liveWatchesForPoller`, that is expected and fixed in Task 4).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepoTest.kt
git commit -m "feat(watch): Watch.targets replaces single-scope poiId/reservableId"
```

---

### Task 4: Fix `AvailabilityPollerRepo.liveWatchesForPoller`'s join (compile-only, no behavior change)

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt:280-291` (`liveWatchesForPoller`)

**Interfaces:**
- Consumes: `AvailabilityWatchRepo.baseSelectFields()` (Task 3's new shape, no `RESERVABLES` join), `AvailabilityWatchRepo.fromRecord` (Task 3).
- Produces: `AvailabilityPollerRepo.liveWatchesForPoller(pollerId): List<AvailabilityWatchRepo.Watch>` — same signature as PR1, no caller-visible change.

This is a **compile fix, not a logic change** — it is called out as its own task because it is the one line in the poller layer that must move to keep the "no poller/run/fetch/executor change" constraint honest: the *file* is touched, but only to delete a join that no longer type-checks after Task 3 dropped `AVAILABILITY_WATCH.RESERVABLE_ID`. No scheduling, claim, retire, or membership logic changes.

- [ ] **Step 1: Confirm this is the only required edit by attempting a full compile first**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:compileKotlin 2>&1 | grep -A3 "AvailabilityPollerRepo"
```
Expected: a compile error pointing at the `RESERVABLES` join / `AVAILABILITY_WATCH.RESERVABLE_ID` reference in `liveWatchesForPoller`, since that column was dropped in Task 1 and the base select no longer includes it (Task 3).

- [ ] **Step 2: Update `liveWatchesForPoller`**

```kotlin
    /**
     * Active watches linked to [pollerId] whose date window still reaches
     * the future. `end_date >= today (UTC)` is a cheap prefilter only — the
     * executor derives the exact target-local clamp per run; a watch that
     * passes here but has already elapsed in target-local time is a no-op
     * for that run, not a correctness bug.
     *
     * Delegates the watch row mapping to [AvailabilityWatchRepo] rather than
     * re-deriving it, so the two repos can't drift on shape. PR2 dropped the
     * single-scope `reservable_id` column, so this select no longer joins
     * `reservables` directly — [AvailabilityWatchRepo.fromRecord] loads each
     * watch's target set (POIs and/or reservables) itself.
     */
    fun liveWatchesForPoller(pollerId: Long): List<AvailabilityWatchRepo.Watch> =
        ctx
            .select(watchRepo.baseSelectFields())
            .from(AVAILABILITY_WATCH)
            .join(AVAILABILITY_WATCH_POLLER)
            .on(AVAILABILITY_WATCH_POLLER.WATCH_ID.eq(AVAILABILITY_WATCH.ID))
            .where(AVAILABILITY_WATCH_POLLER.POLLER_ID.eq(pollerId))
            .and(AVAILABILITY_WATCH.STATUS.eq(WatchStatus.ACTIVE.wireValue))
            .and(AVAILABILITY_WATCH.END_DATE.ge(LocalDate.now(ZoneOffset.UTC)))
            .fetch { watchRepo.fromRecord(it) }
```

Remove the now-unused `import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES` from the top of the file if nothing else in it references `RESERVABLES`.

- [ ] **Step 3: Compile and run the existing poller test suite unchanged, to prove no poller behavior moved**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test --tests "ca.floo.roadtrip.repo.AvailabilityPollerRepoTest" --tests "ca.floo.roadtrip.service.availability.AvailabilityPollerMembershipTest"
```
Expected: these test files still exist from PR1 with their assertions **unmodified** (only their raw-SQL `insertActiveWatch`-style helpers change in Task 6, if any exist in `AvailabilityPollerRepoTest`) — PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt
git commit -m "fix(poller): drop stale reservables join in liveWatchesForPoller after Watch.targets"
```

---

### Task 5: `WatchScopeResolver.resolve` iterates the target set

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchScopeResolver.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/WatchScopeResolverTest.kt` (new)

**Interfaces:**
- Consumes: `AvailabilityWatchRepo.Watch.targets: List<AvailabilityWatchTargetRepo.WatchTarget>` (Task 3), `ReservableRepo.findById`, `ReservableRepo.findByPoi` (unchanged, existing).
- Produces: `WatchScopeResolver.resolve(watch): List<Reservable>` — **signature unchanged from PR1**; this is the seam `AvailabilityPollerMembership.sync` depends on and must not need to change.

- [ ] **Step 1: Write the failing test**

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.models.domain.ReservableType
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class WatchScopeResolverTest : SharedDbTest() {
    private lateinit var reservableRepo: ReservableRepo
    private lateinit var watchRepo: AvailabilityWatchRepo
    private lateinit var resolver: WatchScopeResolver

    @BeforeEach
    fun setUp() {
        reservableRepo = ReservableRepo(ctx)
        watchRepo = AvailabilityWatchRepo(ctx)
        resolver = WatchScopeResolver(reservableRepo)
        ctx.execute("DELETE FROM availability_watch_target")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private var poiSeq = 0

    private fun insertPoi(): Long {
        val sourceId = "poi-scope-${poiSeq++}"
        return ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at
                ) VALUES (
                    'test', ?, 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, NULL, '2026-06-01 00:00:00+00'::timestamptz
                ) RETURNING id
                """.trimIndent(),
                sourceId,
            )!!
            .get("id", Long::class.java)
    }

    private fun insertReservable(
        poiId: Long,
        vendorId: String,
    ): Long {
        val id =
            reservableRepo.upsert(
                ReservableRepo.Input(
                    rid = ReservableId(type = ReservableType.SITE, vendor = "test", vendorId = vendorId),
                    name = "Site $vendorId",
                    loop = null,
                    siteType = null,
                    raw = null,
                ),
            )
        reservableRepo.linkToPoi(id, poiId)
        return id
    }

    private fun createWatch(targets: List<AvailabilityWatchTargetRepo.TargetInput>): AvailabilityWatchRepo.Watch =
        watchRepo.create(
            AvailabilityWatchRepo.CreateInput(
                targets = targets,
                reservableFilters = JsonObject(emptyMap()),
                startDate = LocalDate.parse("2026-07-04"),
                endDate = LocalDate.parse("2026-07-06"),
                cadenceSec = 60,
                triggerKinds = listOf("atc"),
                triggerConfig = JsonObject(emptyMap()),
                stopWhenTriggered = false,
            ),
        )

    @Test
    fun `resolve unions reservables across a poi target and a reservable target`() {
        val poiA = insertPoi()
        val poiB = insertPoi()
        val reservableInA1 = insertReservable(poiA, "a1")
        val reservableInA2 = insertReservable(poiA, "a2")
        val reservableInB = insertReservable(poiB, "b1")

        val watch =
            createWatch(
                listOf(
                    AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null),
                    AvailabilityWatchTargetRepo.TargetInput(poiId = null, reservableId = reservableInB),
                ),
            )

        val resolved = resolver.resolve(watch).map { it.id }.toSet()

        assertEquals(setOf(reservableInA1, reservableInA2, reservableInB), resolved)
    }

    @Test
    fun `resolve de-duplicates a reservable reachable via two targets`() {
        val poi = insertPoi()
        val reservable = insertReservable(poi, "dup")

        val watch =
            createWatch(
                listOf(
                    AvailabilityWatchTargetRepo.TargetInput(poiId = poi, reservableId = null),
                    AvailabilityWatchTargetRepo.TargetInput(poiId = null, reservableId = reservable),
                ),
            )

        val resolved = resolver.resolve(watch)

        assertEquals(1, resolved.size)
        assertEquals(reservable, resolved.single().id)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test --tests "ca.floo.roadtrip.service.availability.WatchScopeResolverTest"
```
Expected: FAIL — compile error, `WatchScopeResolver.resolve` still reads `watch.reservableId`/`watch.poiId`, which no longer exist on `Watch`.

- [ ] **Step 3: Rewrite `WatchScopeResolver.kt`**

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.ReservableRepo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class WatchScopeResolver(
    private val reservablesRepo: ReservableRepo,
) {
    /**
     * Resolves a watch's full target SET to the flat, de-duplicated list of
     * reservables it covers. A reservable target resolves to itself; a POI
     * target expands to that POI's site-type children, filtered by the
     * watch's shared `reservableFilters`. Union across all targets,
     * first-seen order preserved — this is the entire seam
     * [AvailabilityPollerMembership.sync] depends on, unchanged since PR1.
     */
    fun resolve(watch: AvailabilityWatchRepo.Watch): List<Reservable> {
        val seen = LinkedHashMap<Long, Reservable>()
        for (target in watch.targets) {
            val resolved =
                target.reservableId?.let { id -> reservablesRepo.findById(id)?.let(::listOf) ?: emptyList() }
                    ?: target.poiId?.let { poiId -> resolvePoi(poiId, watch.reservableFilters) }
                    ?: emptyList()
            for (r in resolved) seen.putIfAbsent(r.id, r)
        }
        return seen.values.toList()
    }

    private fun resolvePoi(
        poiId: Long,
        filters: JsonObject,
    ): List<Reservable> {
        val all = reservablesRepo.findByPoi(poiId, type = ReservableType.SITE)
        val loops = collectStringFilter(filters, "loop")
        val siteTypes = collectStringFilter(filters, "site_type")
        return all.filter { r ->
            (loops.isEmpty() || (r.loop != null && loops.contains(r.loop))) &&
                (siteTypes.isEmpty() || (r.siteType != null && siteTypes.contains(r.siteType)))
        }
    }
}

private fun collectStringFilter(
    filters: JsonObject,
    key: String,
): Set<String> {
    val value = filters[key] ?: return emptySet()
    return when (value) {
        is JsonPrimitive -> if (value.isString) setOf(value.content) else emptySet()
        is JsonArray ->
            value
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .toSet()
        else -> emptySet()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test --tests "ca.floo.roadtrip.service.availability.WatchScopeResolverTest"
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchScopeResolver.kt backend/src/test/kotlin/ca/floo/roadtrip/service/availability/WatchScopeResolverTest.kt
git commit -m "feat(watch): WatchScopeResolver.resolve unions reservables across a watch's full target set"
```

---

### Task 6: Regression — membership sync over the union (one watch, two parentRefs, two links) with zero poller-file changes

**Files:**
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollerMembershipTest.kt` (update the raw-SQL `insertActiveWatch`/`insertPausedWatch` helpers only — every assertion in this file is unchanged from PR1)
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchServiceTest.kt` (update `poiInput` helper to build a one-element `targets` list; add one new multi-target test)

**Interfaces:**
- Consumes: `AvailabilityPollerMembership.sync(watch, repo, tighterCadencePull)` (PR1, **unchanged**), `WatchScopeResolver.resolve` (Task 5), `AvailabilityWatchRepo.CreateInput` (Task 3).
- Produces: nothing new — this task is verification that the seam holds, not a new capability.

- [ ] **Step 1: Update `AvailabilityPollerMembershipTest`'s watch-insertion helpers to target rows**

Replace the two raw-SQL helpers (they currently `INSERT INTO availability_watch (poi_id, reservable_id, ...)`, which no longer compiles against the V29 schema):

```kotlin
    private fun insertActiveWatch(
        poiId: Long? = null,
        reservableId: Long? = null,
        startDate: String = "2026-07-04",
        endDate: String = "2026-12-31",
    ): Long {
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        start_date, end_date, cadence_sec, trigger_kinds
                    ) VALUES (
                        ?::date, ?::date, 60, ARRAY['atc']
                    ) RETURNING id
                    """.trimIndent(),
                    startDate,
                    endDate,
                )!!
                .get("id", Long::class.java)
        ctx.execute(
            "INSERT INTO availability_watch_target (watch_id, poi_id, reservable_id) VALUES (?, ?, ?)",
            watchId,
            poiId,
            reservableId,
        )
        return watchId
    }

    private fun insertPausedWatch(poiId: Long): Long {
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        start_date, end_date, cadence_sec, trigger_kinds, status
                    ) VALUES (
                        '2026-07-04'::date, '2026-12-31'::date, 60, ARRAY['atc'], 'paused'
                    ) RETURNING id
                    """.trimIndent(),
                )!!
                .get("id", Long::class.java)
        ctx.execute("INSERT INTO availability_watch_target (watch_id, poi_id) VALUES (?, ?)", watchId, poiId)
        return watchId
    }
```

Also add the same `DELETE FROM availability_watch_target` line to the top of the existing `@BeforeEach fun cleanup()` in that file, before `DELETE FROM availability_watch_poller`.

- [ ] **Step 2: Run the full PR1 membership regression suite unmodified in assertions**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test --tests "ca.floo.roadtrip.service.availability.AvailabilityPollerMembershipTest"
```
Expected: PASS — all 6 existing tests (including `two POIs sharing a parentRef produce ONE poller` and `watch spanning two parentRefs links two pollers`) pass unchanged, proving `AvailabilityPollerMembership.sync` needed zero code changes for PR2. If any test fails, the failure is in the helper SQL from Step 1, not in membership/poller logic — do not edit `AvailabilityPollerMembership.kt` or `AvailabilityPollerRepo.kt` to make this pass.

- [ ] **Step 3: Update `AvailabilityWatchServiceTest`'s `poiInput` helper and add a two-POI, two-parentRef create test**

```kotlin
    private fun poiInput(poiId: Long): AvailabilityWatchRepo.CreateInput =
        AvailabilityWatchRepo.CreateInput(
            targets = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, reservableId = null)),
            reservableFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-06"),
            cadenceSec = 60,
            triggerKinds = listOf("atc"),
            triggerConfig = JsonObject(emptyMap()),
            stopWhenTriggered = false,
        )

    @Test
    fun `a watch spanning two campgrounds links to two pollers`() {
        val poiA = seedPoi("232447")
        seedReservable(poiA, "100")
        val poiB = seedPoi("232999")
        seedReservable(poiB, "200")

        val svc = service()
        val watch =
            svc.create(
                AvailabilityWatchRepo.CreateInput(
                    targets =
                        listOf(
                            AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null),
                            AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null),
                        ),
                    reservableFilters = JsonObject(emptyMap()),
                    startDate = LocalDate.parse("2026-07-04"),
                    endDate = LocalDate.parse("2026-07-06"),
                    cadenceSec = 60,
                    triggerKinds = listOf("atc"),
                    triggerConfig = JsonObject(emptyMap()),
                    stopWhenTriggered = false,
                ),
            )

        val pollers = AvailabilityPollerRepo(ctx)
        val linked = pollers.pollerIdsForWatch(watch.id)
        assertEquals(2, linked.size)
        val parentRefs = linked.map { pollers.findById(it)!!.parentRef }.toSet()
        assertEquals(setOf("232447", "232999"), parentRefs)
    }
```

Add the import `ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo` at the top of the file.

- [ ] **Step 4: Run the updated service test suite**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test --tests "ca.floo.roadtrip.service.availability.AvailabilityWatchServiceTest"
```
Expected: PASS (5 tests: the 4 existing PR1 tests + the new two-campground test).

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollerMembershipTest.kt backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchServiceTest.kt
git commit -m "test(watch): regression proving membership sync coalesces a multi-target watch with zero poller-file changes"
```

---

### Task 7: DTOs — `targets` array on the wire, legacy single-scope fields kept as sugar

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt`

**Interfaces:**
- Produces: `AvailabilityWatchTargetSchema(poi_id, reservable_id)`; `AvailabilityWatchCreateRequest.targets: List<AvailabilityWatchTargetSchema>? = null`; `AvailabilityWatchSchema.targets: List<AvailabilityWatchTargetSchema>` plus derived `poi_id`/`reservable_id` (first target) — consumed by routes (Task 8).

- [ ] **Step 1: Add the target schema and widen create/update/response schemas**

```kotlin
package ca.floo.roadtrip.models.api

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AvailabilityWatchTargetSchema(
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("reservable_id") val reservableId: Long? = null,
)

@Serializable
data class AvailabilityWatchCreateRequest(
    // Preferred shape: an explicit target set. When omitted, the legacy
    // single-scope fields below are read as sugar for a one-element list —
    // kept so the existing calendar UI (web/availability/availability-week.js)
    // does not need to change in this PR.
    val targets: List<AvailabilityWatchTargetSchema>? = null,
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("reservable_id") val reservableId: Long? = null,
    @SerialName("reservable_rid") val reservableRid: String? = null,
    @SerialName("reservable_filters") val reservableFilters: JsonObject = JsonObject(emptyMap()),
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("cadence_sec") val cadenceSec: Int,
    @SerialName("trigger_kinds") val triggerKinds: List<String>,
    @SerialName("trigger_config") val triggerConfig: JsonObject = JsonObject(emptyMap()),
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean = true,
)

@Serializable
data class AvailabilityWatchUpdateRequest(
    // Same targets-or-legacy-fields shape as create. Absent `targets` AND
    // absent poi_id/reservable_id/reservable_rid means "leave the target set
    // untouched" (maps to UpdateInput.targets = null).
    val targets: List<AvailabilityWatchTargetSchema>? = null,
    @SerialName("reservable_filters") val reservableFilters: JsonObject? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("cadence_sec") val cadenceSec: Int? = null,
    @SerialName("trigger_kinds") val triggerKinds: List<String>? = null,
    @SerialName("trigger_config") val triggerConfig: JsonObject? = null,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean? = null,
    val status: String? = null,
)

@Serializable
data class AvailabilityWatchSchema(
    val id: Long,
    val targets: List<AvailabilityWatchTargetSchema>,
    // Derived convenience fields (first target) so existing consumers
    // (web/availability/availability-week.js reads `w.poi_id`) keep working
    // without a UI change in this PR. New consumers should read `targets`.
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("reservable_id") val reservableId: Long? = null,
    val reservable: ReservableSchema? = null,
    @SerialName("reservable_filters") val reservableFilters: JsonObject,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("cadence_sec") val cadenceSec: Int,
    @SerialName("trigger_kinds") val triggerKinds: List<String>,
    @SerialName("trigger_config") val triggerConfig: JsonObject,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class AvailabilityWatchResponse(
    val watch: AvailabilityWatchSchema,
)

@Serializable
data class AvailabilityWatchListResponse(
    val total: Int,
    val limit: Int,
    val offset: Int,
    val watches: List<AvailabilityWatchSchema>,
)

@Serializable
data class AvailabilityWatchHeatmapCell(
    @SerialName("target_date") val targetDate: String,
    val status: AvailabilityStatus? = null,
    val available: Boolean? = null,
    @SerialName("observed_at") val observedAt: String? = null,
)

@Serializable
data class AvailabilityWatchHeatmapRow(
    @SerialName("reservable_id") val reservableId: Long,
    @SerialName("reservable_rid") val reservableRid: String,
    val name: String? = null,
    val cells: List<AvailabilityWatchHeatmapCell>,
)

@Serializable
data class AvailabilityWatchHeatmapGroup(
    val loop: String? = null,
    val rows: List<AvailabilityWatchHeatmapRow>,
)

@Serializable
data class AvailabilityWatchHeatmapResponse(
    @SerialName("watch_id") val watchId: Long,
    val dates: List<String>,
    val groups: List<AvailabilityWatchHeatmapGroup>,
)
```

Note: `AvailabilityWatchSchema.reservable` (single resolved `ReservableSchema` for a reservable-scoped watch) is kept for now as a deprecated convenience mirroring the old single-scope shape; it is populated in Task 8 only when the watch has exactly one target and that target is a reservable. Multi-target watches leave it `null`. This is a DTO-level compatibility shim, not new domain modeling — do not add a `List<ReservableSchema>` here in PR2; that belongs to whichever future PR gives the UI a real multi-target editor.

There is no separate test file for this task — DTOs are exercised through the route tests in Task 8, which is where `kotlinx.serialization` (de)serialization is actually invoked.

- [ ] **Step 2: Compile to confirm the schema module builds standalone**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:compileKotlin
```
Expected: fails at this point with errors in `AvailabilityWatchRoutes.kt` (Task 8 not yet done) referencing the old `AvailabilityWatchCreateRequest`/`Watch.toSchema()` shape — that is expected; do not fix routes here, just confirm the *schema file itself* has no syntax errors by checking the error list is scoped to `AvailabilityWatchRoutes.kt` only.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt
git commit -m "feat(watch): AvailabilityWatchSchema carries a targets array, keeps legacy poi_id/reservable_id as derived fields"
```

---

### Task 8: Routes — accept a target list, keep legacy single-scope create/update working

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt` — this file **already exists** (PR1 shipped it, `watchService()`/`watchServiceWithRecgov()`/`seedPoi`/`seedReservable`/`linkReservableToPoi` helpers are all present) and covers the legacy single-scope create/patch/delete/heatmap paths end to end. Do not recreate it — extend it with the 4 new tests below, which must compile against the exact helpers already in the file (verified above by reading it in full).

**Interfaces:**
- Consumes: `AvailabilityWatchCreateRequest`/`AvailabilityWatchUpdateRequest`/`AvailabilityWatchSchema` (Task 7), `AvailabilityWatchRepo.CreateInput`/`UpdateInput` (Task 3), `AvailabilityWatchService.create`/`update` (unchanged signatures from PR1), the file's own `watchService()`, `seedPoi(sourceId, name, providerRefJson)`, `seedReservable(vendorId, name, loop, siteType)`, `linkReservableToPoi(reservableId, poiId)`.
- Produces: `POST /api/availability/watches` and `PATCH /api/availability/watches/{id}` accept either `targets: [...]` or the legacy `poi_id`/`reservable_id`/`reservable_rid` fields; `GET` responses include `targets`.

- [ ] **Step 1: Add 4 new tests to the existing `AvailabilityWatchRoutesTest.kt`**, inserted after the `POST rejects missing scope` test (which already exercises the zero-scope-fields case for the legacy shape and stays as-is):

```kotlin
    @Test
    fun `POST with an explicit targets array persists a multi-target watch`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiA = seedPoi(sourceId = "p-targets-a", name = "Upper Pines")
            val poiB = seedPoi(sourceId = "p-targets-b", name = "Lower Pines")
            val body =
                """
                {
                  "targets": [{"poi_id": $poiA}, {"poi_id": $poiB}],
                  "start_date": "2026-07-04",
                  "end_date": "2026-07-06",
                  "cadence_sec": 60,
                  "trigger_kinds": ["atc"]
                }
                """.trimIndent()
            val resp =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            val targets = obj["targets"]!!.jsonArray
            assertEquals(2, targets.size)
            assertEquals(setOf(poiA, poiB), targets.map { it.jsonObject["poi_id"]!!.jsonPrimitive.long }.toSet())
            // Derived convenience field: first target.
            assertEquals(poiA, obj["poi_id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `POST with legacy poi_id is accepted as a one-element target list`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-legacy-single", name = "Legacy Single")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            val targets = obj["targets"]!!.jsonArray
            assertEquals(1, targets.size)
            assertEquals(poiId, targets[0].jsonObject["poi_id"]!!.jsonPrimitive.long)
            assertEquals(poiId, obj["poi_id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `POST rejects both targets and legacy poi_id set together`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-conflict", name = "Conflict")
            val body =
                """
                {"targets": [{"poi_id": $poiId}], "poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_scope", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST rejects a target with both poi_id and reservable_id set`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-bad-target", name = "Bad Target")
            val rid = seedReservable("bad-target-1")
            linkReservableToPoi(rid, poiId)
            val body =
                """
                {"targets": [{"poi_id": $poiId, "reservable_id": $rid}], "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_scope", obj["error"]!!.jsonPrimitive.content)
        }
```

These reuse the file's existing `seedPoi(sourceId, name)` and `seedReservable(vendorId)` helper signatures exactly as already defined lower in the file — no new test helpers needed.

- [ ] **Step 2: Run to verify failure**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test --tests "ca.floo.roadtrip.routes.AvailabilityWatchRoutesTest"
```
Expected: FAIL — either a compile error (route still builds `CreateInput` with `poiId`/`reservableId`, or `AvailabilityWatchSchema` has no `targets` field yet if Task 7 hasn't landed) or a real assertion failure once compiling, since `resolveCreateScope` doesn't understand `targets` yet. All the file's *existing* tests (the ones from PR1, listed above) must also still be present and must pass once Step 3 below lands — do not remove or alter any of them.

- [ ] **Step 4: Update `resolveCreateScope` and `toSchema()` in `AvailabilityWatchRoutes.kt`**

Replace the `ResolveResult`/`resolveCreateScope` block and `Watch.toSchema()`:

```kotlin
private sealed class ResolveResult {
    data class Ok(
        val targets: List<AvailabilityWatchTargetRepo.TargetInput>,
    ) : ResolveResult()

    data class Err(
        val error: String,
        val detail: String?,
    ) : ResolveResult()
}

/**
 * Builds the target list for create/update from either the preferred
 * `targets` array or the legacy single-scope fields (`poi_id`,
 * `reservable_id`, `reservable_rid`) — exactly one of the two shapes must be
 * present. Legacy fields are sugar for a one-element `targets` list so the
 * existing calendar UI keeps working unmodified.
 */
private fun resolveCreateScope(
    req: AvailabilityWatchCreateRequest,
    reservablesRepo: ReservableRepo,
): ResolveResult {
    val legacyKeysSet = listOf(req.poiId, req.reservableId, req.reservableRid).count { it != null }
    val hasTargets = req.targets != null
    if (hasTargets && legacyKeysSet > 0) {
        return ResolveResult.Err("invalid_scope", "specify either targets or poi_id/reservable_id/reservable_rid, not both")
    }
    if (hasTargets) {
        val targets = req.targets!!
        if (targets.isEmpty()) return ResolveResult.Err("invalid_scope", "targets must be non-empty")
        val resolved = mutableListOf<AvailabilityWatchTargetRepo.TargetInput>()
        for (t in targets) {
            if ((t.poiId == null) == (t.reservableId == null)) {
                return ResolveResult.Err("invalid_scope", "each target must set exactly one of poi_id/reservable_id")
            }
            resolved += AvailabilityWatchTargetRepo.TargetInput(poiId = t.poiId, reservableId = t.reservableId)
        }
        return ResolveResult.Ok(resolved)
    }
    if (legacyKeysSet != 1) {
        return ResolveResult.Err("invalid_scope", "exactly one of targets, poi_id, reservable_id, or reservable_rid must be set")
    }
    if (req.reservableRid != null) {
        val parsed =
            ca.floo.roadtrip.models.domain.ReservableId
                .parse(req.reservableRid)
                ?: return ResolveResult.Err("invalid_reservable_rid", "could not parse reservable_rid '${req.reservableRid}'")
        val resolvedReservable =
            reservablesRepo.findByRid(parsed)
                ?: return ResolveResult.Err("reservable_not_found", "no reservable with rid ${req.reservableRid}")
        return ResolveResult.Ok(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = null, reservableId = resolvedReservable.id)))
    }
    return ResolveResult.Ok(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = req.poiId, reservableId = req.reservableId)))
}

/**
 * Same targets-or-legacy resolution as [resolveCreateScope], for PATCH. A
 * request with neither `targets` nor any legacy scope field means "leave
 * the target set untouched" (returns null, distinct from an empty list).
 */
private fun resolveUpdateScope(req: AvailabilityWatchUpdateRequest): ResolveResult.Ok? {
    if (req.targets != null) {
        return ResolveResult.Ok(req.targets.map { AvailabilityWatchTargetRepo.TargetInput(poiId = it.poiId, reservableId = it.reservableId) })
    }
    return null
}
```

Update the `POST` handler to use the new `ResolveResult.Ok(targets)` shape:

```kotlin
        val resolved =
            when (val r = resolveCreateScope(req, reservablesRepo)) {
                is ResolveResult.Err -> return@post call.respondError(r.error, HttpStatusCode.BadRequest, r.detail)
                is ResolveResult.Ok -> r
            }
        val err = validateCreateBody(req)
        if (err != null) return@post call.respondError(err.first, HttpStatusCode.BadRequest, err.second)
        val dateWindow =
            parseDateWindow(req.startDate, req.endDate)
                ?: return@post call.respondError("invalid_date_window", HttpStatusCode.BadRequest, "end_date must be after start_date")
        val watch =
            watchService.create(
                AvailabilityWatchRepo.CreateInput(
                    targets = resolved.targets,
                    reservableFilters = req.reservableFilters,
                    startDate = dateWindow.first,
                    endDate = dateWindow.second,
                    cadenceSec = req.cadenceSec,
                    triggerKinds = req.triggerKinds,
                    triggerConfig = req.triggerConfig,
                    stopWhenTriggered = req.stopWhenTriggered,
                ),
            )
        call.respondJson(AvailabilityWatchResponse(watch.toSchema()), HttpStatusCode.Created)
```

Update the `PATCH` handler to pass `targets = resolveUpdateScope(req)?.targets` into `UpdateInput`:

```kotlin
        val updated =
            watchService.update(
                id,
                AvailabilityWatchRepo.UpdateInput(
                    targets = resolveUpdateScope(req)?.targets,
                    reservableFilters = req.reservableFilters,
                    startDate = dateWindow?.first,
                    endDate = dateWindow?.second,
                    cadenceSec = req.cadenceSec,
                    triggerKinds = req.triggerKinds,
                    triggerConfig = req.triggerConfig,
                    stopWhenTriggered = req.stopWhenTriggered,
                    status = status,
                ),
            )
```

Replace `Watch.toSchema()`. This becomes a top-level function taking `reservablesRepo` explicitly (it is no longer an extension-only function, because loading the single-reservable convenience field needs a repo call that `baseSelect()` used to give it for free via the dropped `leftJoin(RESERVABLES)`):

```kotlin
private fun Watch.toSchema(reservablesRepo: ReservableRepo): AvailabilityWatchSchema {
    val firstTarget = targets.firstOrNull()
    val singleReservable =
        firstTarget
            ?.reservableId
            ?.takeIf { targets.size == 1 }
            ?.let { reservablesRepo.findById(it) }
            ?.let { r ->
                ReservableSchema(
                    rid = r.rid.encode(),
                    type = r.rid.type.encode(),
                    vendor = r.rid.vendor,
                    vendorId = r.rid.vendorId,
                    name = r.name,
                    loop = r.loop,
                    siteType = r.siteType,
                    poiIds = emptyList(),
                    raw = r.raw,
                )
            }
    return AvailabilityWatchSchema(
        id = id,
        targets = targets.map { AvailabilityWatchTargetSchema(poiId = it.poiId, reservableId = it.reservableId) },
        poiId = firstTarget?.poiId,
        reservableId = firstTarget?.reservableId,
        reservable = singleReservable,
        reservableFilters = reservableFilters,
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        cadenceSec = cadenceSec,
        triggerKinds = triggerKinds,
        triggerConfig = triggerConfig,
        stopWhenTriggered = stopWhenTriggered,
        status = status.wireValue,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
}
```

Update every call site in the file (`GET /{id}`, `GET` list, `POST`, `PATCH`) from `watch.toSchema()` to `watch.toSchema(reservablesRepo)` / `it.toSchema(reservablesRepo)` — `reservablesRepo` is already constructed at the top of `availabilityWatchRoutes` (`val reservablesRepo = ReservableRepo(ctx)`), so this is a one-argument threading change at each of the 4-5 call sites, not a new dependency.

Add `import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo` and `import ca.floo.roadtrip.models.api.AvailabilityWatchTargetSchema` at the top of the file.

- [ ] **Step 5: Run to verify the tests pass**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test --tests "ca.floo.roadtrip.routes.AvailabilityWatchRoutesTest"
```
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt
git commit -m "feat(watch): routes accept a targets array, legacy poi_id/reservable_id kept as create sugar"
```

---

### Task 9: Full-suite regression + seam diff check

**Files:** none new — this task is a verification gate before closing PR2.

**Interfaces:** none new.

- [ ] **Step 1: Run the entire backend test suite**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/wc/code/github/wwchen/roadtrip && ./gradlew :backend:test
```
Expected: BUILD SUCCESSFUL, all tests pass, including every PR1 poller/run/fetch test file unmodified.

- [ ] **Step 2: Diff this branch against `feat/pr1-poller-coalescing` and confirm no poller/run/fetch/executor/Grafana file appears except the one compile-only line fixed in Task 4**

Run:
```bash
git diff feat/pr1-poller-coalescing... --stat -- backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityRunRepo.kt backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityFetchCallRepo.kt grafana/dashboards
```
Expected: the only hit is `AvailabilityPollerRepo.kt` with a small diff (the `liveWatchesForPoller` join fix from Task 4); everything else shows zero changes. If anything else appears, stop and investigate before proceeding — it means the "watch = set" change leaked into the physical layer, which is exactly what this PR must not do.

- [ ] **Step 3: Record the result — no commit needed if step 2 is clean**

If Step 2 surfaces an unexpected diff, fix it and re-run Steps 1-2 before considering PR2 done. If clean, this task requires no code change and no commit; it is the plan's final gate.

---

## Self-Review

**1. Spec coverage:**
- "Multiple `availability_watch_target` rows (poi_id/reservable_id per row) + multiple poller links per watch" → Task 1 (table), Task 6 (regression proving 2 targets → 2 poller links).
- "Touches only watch service/API/UI — no poller/run/fetch change" → Global Constraints + Task 4 (the one unavoidable compile fix, called out explicitly) + Task 9 (diff gate).
- "Drop the single-scope `poi_id`/`reservable_id` columns + scope CHECK from `availability_watch`" → Task 1, Step 1 SQL.
- `availability_watch_target` schema exactly as specified (`watch_id`, `poi_id`, `reservable_id`, CHECK exactly one) → Task 1, Step 1 SQL — matches spec verbatim plus a surrogate `id` PK (needed because jOOQ's Kotlin generator and `AvailabilityWatchTargetRepo.fromRecord` want a stable row identity; the spec's table has no PK/uniqueness constraint of its own, so adding a surrogate key is a safe, additive implementation detail, not a spec deviation).
- jOOQ includes allowlist update → Task 1, Step 2 (explicitly called out per project convention/memory).
- "UI" touch → Task 7/8 keep the existing `web/availability` calendar create flow working via legacy-field sugar; a dedicated multi-target *editor* UI is out of scope for PR2 per the spec's framing ("proving the seam"), and no such UI exists today to modify.
- Testing section item "Watch spanning two parentRefs → two links; each polled once" → covered by Task 6 (`AvailabilityPollerMembershipTest`'s pre-existing test, now driven by the new target-row helper) and Task 6's new `AvailabilityWatchServiceTest` case at the service layer.

**2. Placeholder scan:** Initial draft of the `toSchema()` `singleReservable` block used an "omitted" comment; replaced with the real `reservablesRepo.findById(...)?.let { r -> ReservableSchema(...) }` call using the exact field mapping already present in the landed PR1 `AvailabilityWatchRoutes.kt` (`rid.encode()`, `rid.type.encode()`, `rid.vendor`, `rid.vendorId`, `name`, `loop`, `siteType`, `poiIds = emptyList()`, `raw`). Task 8's route tests were similarly upgraded from pseudocode to real Ktor `testApplication` bodies once `AvailabilityWatchRoutesTest.kt` was found to already exist (read in full — it has `watchService()`, `seedPoi`, `seedReservable`, `linkReservableToPoi` helpers this plan's new tests reuse verbatim). No remaining "TBD"/"similar to Task N"/unshown-code steps in the document.

**3. Type consistency:** `AvailabilityWatchRepo.Watch.targets: List<AvailabilityWatchTargetRepo.WatchTarget>` (Task 3) is the type threaded through `WatchScopeResolver.resolve` (Task 5), `AvailabilityPollerRepo.liveWatchesForPoller` (Task 4), and route `toSchema(reservablesRepo)` (Task 8) consistently. `AvailabilityWatchTargetRepo.TargetInput` is the one input type used by `AvailabilityWatchRepo.CreateInput.targets`/`UpdateInput.targets` (Task 3), routes' `resolveCreateScope`/`resolveUpdateScope` (Task 8), and every test helper (Tasks 2, 3, 5, 6). No renamed/drifted signatures found on review.
