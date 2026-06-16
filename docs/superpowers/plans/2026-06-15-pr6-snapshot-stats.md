# Snapshot Stats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface per-(reservable, target_date) availability stats — last seen open, current/last open window duration, median open window over 24h, flips in last 24h, total snapshots — so operators can read site popularity at a glance and the data is in place for future cadence tuning.

**Architecture:** New backend method on `AvailabilitySnapshotRepo` runs a single SQL query that walks snapshots ordered by `(reservable_id, target_date, observed_at)` and aggregates contiguous `available=true` runs in Kotlin (Postgres window-function gymnastics aren't worth it for this size of data). New `GET /api/availability/snapshots/summary?reservable_rid=…` endpoint returns the stats. Snapshots tab renders the stats block above the existing list.

**Tech Stack:** Kotlin/Ktor, jOOQ + Postgres, vanilla JS frontend. No schema changes.

**Reference docs:** `docs/superpowers/specs/2026-06-15-availability-watches-design.md`. Prior PRs: #229 (snapshot rename), #230 (dashboard).

**Stack base:** Branch from `avail-dashboard` (PR #230).

---

## File map

**Created:**

- `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotStatsTest.kt` — unit-shaped test for the stats aggregation against Testcontainers Postgres.

**Modified:**

- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt` — add `summarize(reservableId, windowHours)` returning per-target-date stats.
- `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityDashboardSchemas.kt` — add `AvailabilitySnapshotStatsSchema`, `AvailabilitySnapshotsSummaryResponse`.
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutes.kt` — add `GET /api/availability/snapshots/summary` endpoint.
- `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutesTest.kt` — add coverage for the new endpoint.
- `web/api/availability-dashboard-api.js` — add `getSnapshotsSummary`.
- `web/components/availability/snapshots-tab.js` — render a stats block above the existing list when filtering by `reservable_rid`.

**Untouched:**

- Schema. The new endpoint composes existing tables.
- Cadence tuning logic. Stats are observable now; using them to tune `cadence_sec` is a future PR.

---

## Stats shape

For each `target_date` of one reservable, return:

```kotlin
data class TargetDateStats(
    val targetDate: LocalDate,
    val totalSnapshots: Int,                    // rows in window
    val lastOpenAt: OffsetDateTime?,            // observed_at of most recent available=true row
    val isCurrentlyOpen: Boolean,               // latest snapshot in window has available=true
    val currentOrLastOpenWindowSec: Int?,       // length of the most recent contiguous open run
    val medianOpenWindowSec: Int?,              // median run length over the window
    val flipsLast24h: Int,                      // false→true transitions
)
```

Computed by:

1. Pull all snapshots in window for `(reservable_id, target_date)` ordered by `observed_at`.
2. Walk the list. Track contiguous runs of `available=true` (a run = ≥1 consecutive trues bracketed by either ends-of-list or a false).
3. Each run has a `start_at`, `end_at` (or `nowish`/`null` if currently open), and a duration.
4. `lastOpenAt` = `runs.last().end_at` or the latest available-true `observed_at` if currently open.
5. `isCurrentlyOpen` = the latest snapshot has `available=true`.
6. `currentOrLastOpenWindowSec` = duration of the last run.
7. `medianOpenWindowSec` = median of all run durations (sorted ascending).
8. `flipsLast24h` = count of false→true transitions in the window.

Edge cases:

- Zero snapshots in window → `totalSnapshots=0`, all other fields `null` / `false` / `0`.
- All snapshots `available=false` → `lastOpenAt=null`, `currentOrLastOpenWindowSec=null`, `medianOpenWindowSec=null`, `flipsLast24h=0`.
- All snapshots `available=true` (one ongoing run) → `isCurrentlyOpen=true`, `currentOrLastOpenWindowSec` = duration from first observed_at to latest.

---

## Task 1: `summarize` method on `AvailabilitySnapshotRepo`

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt`

The method runs one query per reservable (gets all snapshots in the window, group by target_date in Kotlin), walks each group to compute stats. The window is small enough (a 24h × 7-date watch at 60s cadence is ~10k rows) that fetching them all is fine; if it ever isn't, we add a window-function-based aggregate later.

- [ ] **Step 1: Add the data class + method**

Open `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt`. After the existing `listForRun` method, add:

```kotlin
    data class TargetDateStats(
        val targetDate: LocalDate,
        val totalSnapshots: Int,
        val lastOpenAt: OffsetDateTime?,
        val isCurrentlyOpen: Boolean,
        val currentOrLastOpenWindowSec: Int?,
        val medianOpenWindowSec: Int?,
        val flipsLast24h: Int,
    )

    /**
     * Per-target-date stats computed from the snapshot rows in the
     * given window. The window applies to `observed_at`; every snapshot
     * within it counts toward `totalSnapshots`. flipsLast24h is always
     * computed over a fixed 24h tail regardless of window length.
     *
     * Empty input (no snapshots for that target_date in window) yields
     * an entry with totalSnapshots=0 so the UI can render "never seen
     * open" rather than dropping the date.
     */
    fun summarize(
        reservableId: Long,
        targetDates: List<LocalDate>,
        now: OffsetDateTime = OffsetDateTime.now(),
        windowHours: Int = 24 * 7,
    ): List<TargetDateStats> {
        if (targetDates.isEmpty()) return emptyList()
        val windowStart = now.minusHours(windowHours.toLong())
        val flipWindowStart = now.minusHours(24)
        val rows =
            ctx
                .selectFrom(AVAILABILITY_SNAPSHOT)
                .where(AVAILABILITY_SNAPSHOT.RESERVABLE_ID.eq(reservableId))
                .and(AVAILABILITY_SNAPSHOT.TARGET_DATE.`in`(targetDates))
                .and(AVAILABILITY_SNAPSHOT.OBSERVED_AT.ge(windowStart))
                .orderBy(
                    AVAILABILITY_SNAPSHOT.TARGET_DATE.asc(),
                    AVAILABILITY_SNAPSHOT.OBSERVED_AT.asc(),
                ).fetch { fromRecord(it) }
        val grouped = rows.groupBy { it.targetDate }
        return targetDates.map { date ->
            val group = grouped[date].orEmpty()
            statsFor(date, group, flipWindowStart)
        }
    }

    private fun statsFor(
        date: LocalDate,
        snapshots: List<Snapshot>,
        flipWindowStart: OffsetDateTime,
    ): TargetDateStats {
        if (snapshots.isEmpty()) {
            return TargetDateStats(
                targetDate = date,
                totalSnapshots = 0,
                lastOpenAt = null,
                isCurrentlyOpen = false,
                currentOrLastOpenWindowSec = null,
                medianOpenWindowSec = null,
                flipsLast24h = 0,
            )
        }
        // Walk for contiguous available=true runs.
        data class Run(val start: OffsetDateTime, val end: OffsetDateTime)
        val runs = mutableListOf<Run>()
        var runStart: OffsetDateTime? = null
        var lastTrueAt: OffsetDateTime? = null
        for (s in snapshots) {
            if (s.available) {
                if (runStart == null) runStart = s.observedAt
                lastTrueAt = s.observedAt
            } else if (runStart != null) {
                runs += Run(start = runStart, end = lastTrueAt!!)
                runStart = null
            }
        }
        val isCurrentlyOpen = snapshots.last().available
        if (runStart != null) {
            runs += Run(start = runStart, end = lastTrueAt!!)
        }
        val currentOrLastOpenWindowSec =
            runs.lastOrNull()?.let {
                java.time.Duration.between(it.start, it.end).seconds.toInt().coerceAtLeast(0)
            }
        val medianOpenWindowSec =
            if (runs.isEmpty()) {
                null
            } else {
                val durations =
                    runs
                        .map { java.time.Duration.between(it.start, it.end).seconds.toInt().coerceAtLeast(0) }
                        .sorted()
                val mid = durations.size / 2
                if (durations.size % 2 == 0) {
                    (durations[mid - 1] + durations[mid]) / 2
                } else {
                    durations[mid]
                }
            }
        // Count false→true transitions within the last 24h.
        var flips = 0
        var prev: Snapshot? = null
        for (s in snapshots) {
            if (s.observedAt >= flipWindowStart && prev != null && !prev.available && s.available) {
                flips += 1
            }
            prev = s
        }
        return TargetDateStats(
            targetDate = date,
            totalSnapshots = snapshots.size,
            lastOpenAt = lastTrueAt,
            isCurrentlyOpen = isCurrentlyOpen,
            currentOrLastOpenWindowSec = currentOrLastOpenWindowSec,
            medianOpenWindowSec = medianOpenWindowSec,
            flipsLast24h = flips,
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
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt
git commit -m "AvailabilitySnapshotRepo: add summarize for per-target-date stats"
```

---

## Task 2: `AvailabilitySnapshotStatsTest`

**Files:**

- Create: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotStatsTest.kt`

Six tests covering: empty input, one open run, multiple open runs, currently-open ongoing run, all-booked window, multiple target_dates in one call.

- [ ] **Step 1: Write the test**

```kotlin
package ca.floo.roadtrip.repo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilitySnapshotStatsTest {
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
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
    }

    private fun seedReservable(): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name
                ) VALUES (
                    'site', 'recgov', '330257', 'federal-campsites', 'A12'
                ) RETURNING id
                """.trimIndent(),
            )!!.get("id", Long::class.java)

    private fun insertSnapshot(
        reservableId: Long,
        targetDate: LocalDate,
        observedAt: OffsetDateTime,
        available: Boolean,
    ) {
        ctx.execute(
            """
            INSERT INTO availability_snapshot (
                reservable_id, observed_at, target_date, status, available, day_payload
            ) VALUES (?, ?, ?, ?, ?, '{}'::jsonb)
            """.trimIndent(),
            reservableId,
            observedAt,
            targetDate,
            if (available) "available" else "booked",
            available,
        )
    }

    private val date = LocalDate.parse("2026-07-04")

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    @Test
    fun `empty input yields zeroed stats per target_date`() {
        val reservableId = seedReservable()
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now())
        assertEquals(1, stats.size)
        assertEquals(0, stats[0].totalSnapshots)
        assertNull(stats[0].lastOpenAt)
        assertNull(stats[0].currentOrLastOpenWindowSec)
        assertNull(stats[0].medianOpenWindowSec)
        assertEquals(0, stats[0].flipsLast24h)
        assertEquals(false, stats[0].isCurrentlyOpen)
    }

    @Test
    fun `all booked window yields zeroed run stats with totalSnapshots populated`() {
        val reservableId = seedReservable()
        val now = now()
        repeat(5) {
            insertSnapshot(reservableId, date, now.minusMinutes((5 - it).toLong()), available = false)
        }
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now).single()
        assertEquals(5, stats.totalSnapshots)
        assertNull(stats.lastOpenAt)
        assertEquals(false, stats.isCurrentlyOpen)
        assertNull(stats.currentOrLastOpenWindowSec)
        assertNull(stats.medianOpenWindowSec)
        assertEquals(0, stats.flipsLast24h)
    }

    @Test
    fun `one closed run computes lastOpenAt and window duration`() {
        val reservableId = seedReservable()
        val now = now()
        // 3 booked, 2 available, 2 booked → one run of length 1m (we count seconds between first true and last true within run).
        insertSnapshot(reservableId, date, now.minusMinutes(7), available = false)
        insertSnapshot(reservableId, date, now.minusMinutes(6), available = false)
        insertSnapshot(reservableId, date, now.minusMinutes(5), available = false)
        val openAt1 = now.minusMinutes(4)
        val openAt2 = now.minusMinutes(3)
        insertSnapshot(reservableId, date, openAt1, available = true)
        insertSnapshot(reservableId, date, openAt2, available = true)
        insertSnapshot(reservableId, date, now.minusMinutes(2), available = false)
        insertSnapshot(reservableId, date, now.minusMinutes(1), available = false)
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now).single()
        assertEquals(7, stats.totalSnapshots)
        assertEquals(false, stats.isCurrentlyOpen)
        assertNotNull(stats.lastOpenAt)
        assertEquals(openAt2.toEpochSecond(), stats.lastOpenAt!!.toEpochSecond())
        assertNotNull(stats.currentOrLastOpenWindowSec)
        assertTrue(stats.currentOrLastOpenWindowSec!! in 55..65) // ~60s
        assertEquals(stats.currentOrLastOpenWindowSec, stats.medianOpenWindowSec) // single run
        assertEquals(1, stats.flipsLast24h)
    }

    @Test
    fun `currently open run reports isCurrentlyOpen=true`() {
        val reservableId = seedReservable()
        val now = now()
        insertSnapshot(reservableId, date, now.minusMinutes(3), available = false)
        insertSnapshot(reservableId, date, now.minusMinutes(2), available = true)
        insertSnapshot(reservableId, date, now.minusMinutes(1), available = true)
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now).single()
        assertEquals(true, stats.isCurrentlyOpen)
        assertNotNull(stats.lastOpenAt)
        assertNotNull(stats.currentOrLastOpenWindowSec)
        assertEquals(1, stats.flipsLast24h)
    }

    @Test
    fun `multiple runs compute median across runs`() {
        val reservableId = seedReservable()
        val now = now()
        // Run 1 (~30s): t-10m through t-9m30s. Run 2 (~120s): t-7m through t-5m. Run 3 (~60s): t-3m through t-2m.
        // We approximate seconds via minutesOffset arithmetic that produces nice numbers.
        insertSnapshot(reservableId, date, now.minusSeconds(700), available = false)
        insertSnapshot(reservableId, date, now.minusSeconds(630), available = true)   // r1 start
        insertSnapshot(reservableId, date, now.minusSeconds(600), available = true)   // r1 end (30s)
        insertSnapshot(reservableId, date, now.minusSeconds(570), available = false)
        insertSnapshot(reservableId, date, now.minusSeconds(450), available = true)   // r2 start
        insertSnapshot(reservableId, date, now.minusSeconds(330), available = true)   // r2 end (120s)
        insertSnapshot(reservableId, date, now.minusSeconds(300), available = false)
        insertSnapshot(reservableId, date, now.minusSeconds(180), available = true)   // r3 start
        insertSnapshot(reservableId, date, now.minusSeconds(120), available = true)   // r3 end (60s)
        insertSnapshot(reservableId, date, now.minusSeconds(60), available = false)
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now).single()
        // Three runs of roughly 30, 120, 60 seconds. Median = 60.
        assertNotNull(stats.medianOpenWindowSec)
        assertTrue(stats.medianOpenWindowSec!! in 55..65)
        // Most recent run is r3 (60s).
        assertNotNull(stats.currentOrLastOpenWindowSec)
        assertTrue(stats.currentOrLastOpenWindowSec!! in 55..65)
        // Three false→true transitions in the last 24h.
        assertEquals(3, stats.flipsLast24h)
    }

    @Test
    fun `multiple target_dates returned in input order`() {
        val reservableId = seedReservable()
        val now = now()
        val d1 = LocalDate.parse("2026-07-04")
        val d2 = LocalDate.parse("2026-07-05")
        insertSnapshot(reservableId, d1, now.minusMinutes(2), available = true)
        insertSnapshot(reservableId, d1, now.minusMinutes(1), available = true)
        insertSnapshot(reservableId, d2, now.minusMinutes(2), available = false)
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(d1, d2), now)
        assertEquals(2, stats.size)
        assertEquals(d1, stats[0].targetDate)
        assertEquals(d2, stats[1].targetDate)
        assertEquals(true, stats[0].isCurrentlyOpen)
        assertEquals(false, stats[1].isCurrentlyOpen)
    }
}
```

- [ ] **Step 2: Run tests**

```bash
cd backend
./gradlew --stop
./gradlew test --tests AvailabilitySnapshotStatsTest
```

Expected: 6/6 passing.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotStatsTest.kt
git commit -m "AvailabilitySnapshotRepo: tests for summarize"
```

---

## Task 3: Add summary DTOs

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityDashboardSchemas.kt`

- [ ] **Step 1: Append the new DTOs**

Add to the bottom of the file:

```kotlin
@Serializable
data class AvailabilitySnapshotStatsSchema(
    @SerialName("target_date") val targetDate: String,
    @SerialName("total_snapshots") val totalSnapshots: Int,
    @SerialName("last_open_at") val lastOpenAt: String? = null,
    @SerialName("is_currently_open") val isCurrentlyOpen: Boolean,
    @SerialName("current_or_last_open_window_sec") val currentOrLastOpenWindowSec: Int? = null,
    @SerialName("median_open_window_sec") val medianOpenWindowSec: Int? = null,
    @SerialName("flips_last_24h") val flipsLast24h: Int,
)

@Serializable
data class AvailabilitySnapshotsSummaryResponse(
    @SerialName("reservable_rid") val reservableRid: String,
    val stats: List<AvailabilitySnapshotStatsSchema>,
)
```

- [ ] **Step 2: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityDashboardSchemas.kt
git commit -m "Add snapshot stats DTOs"
```

---

## Task 4: Add `GET /api/availability/snapshots/summary`

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutes.kt`

The endpoint takes a required `reservable_rid` and an optional `target_dates` (comma-separated YYYY-MM-DD). When `target_dates` is omitted, the endpoint returns stats for every distinct target_date that has any snapshot for this reservable in the window.

Resolution: parse rid → look up reservable → run `summarize`. Same shape as the existing `/snapshots` GET.

- [ ] **Step 1: Add the route**

Inside `fun Route.availabilityDashboardRoutes(ctx: DSLContext) { ... }`, after the existing `/snapshots` GET, add:

```kotlin
    get("/api/availability/snapshots/summary", {
        tags = listOf("availability")
        summary = "Per-target-date stats for one reservable's snapshot history"
        request {
            queryParameter<String>("reservable_rid") { description = "Reservable composite id (e.g. site:recgov:330257)." }
            queryParameter<String>("target_dates") {
                description = "Comma-separated YYYY-MM-DD list. If omitted, every date with snapshots in the window is returned."
            }
            queryParameter<Int>("window_hours") { description = "Snapshot window in hours, default 168 (7 days)." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilitySnapshotsSummaryResponse> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val rid =
            call.request.queryParameters["reservable_rid"]?.takeIf { it.isNotBlank() }
                ?: return@get call.respondError(
                    "missing_reservable_rid",
                    HttpStatusCode.BadRequest,
                    "reservable_rid is required",
                )
        val parsed =
            ca.floo.roadtrip.models.ReservableId.parse(rid)
                ?: return@get call.respondError(
                    "invalid_reservable_rid",
                    HttpStatusCode.BadRequest,
                    "could not parse reservable_rid '$rid'",
                )
        val reservable =
            reservables.findByRid(parsed)
                ?: return@get call.respondError(
                    "reservable_not_found",
                    HttpStatusCode.NotFound,
                    "no reservable with rid $rid",
                )
        val windowHours =
            call.request.queryParameters["window_hours"]?.toIntOrNull()?.coerceIn(1, 24 * 30) ?: (24 * 7)
        val explicitDates =
            call.request.queryParameters["target_dates"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                .orEmpty()
        val targetDates =
            explicitDates.ifEmpty {
                // Discover distinct target_dates that have any snapshot in the window.
                val windowStart = java.time.OffsetDateTime.now().minusHours(windowHours.toLong())
                ctx
                    .selectDistinct(ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.AVAILABILITY_SNAPSHOT.TARGET_DATE)
                    .from(ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.AVAILABILITY_SNAPSHOT)
                    .where(ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.AVAILABILITY_SNAPSHOT.RESERVABLE_ID.eq(reservable.id))
                    .and(ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.AVAILABILITY_SNAPSHOT.OBSERVED_AT.ge(windowStart))
                    .orderBy(ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.AVAILABILITY_SNAPSHOT.TARGET_DATE.asc())
                    .fetch { it.value1() }
            }
        val stats = snapshots.summarize(reservable.id, targetDates, windowHours = windowHours)
        call.respondJson(
            AvailabilitySnapshotsSummaryResponse(
                reservableRid = rid,
                stats = stats.map { it.toSchema() },
            ),
        )
    }
```

Then add a private extension at the bottom of the file (next to the other `toSchema` helpers):

```kotlin
private fun AvailabilitySnapshotRepo.TargetDateStats.toSchema(): AvailabilitySnapshotStatsSchema =
    AvailabilitySnapshotStatsSchema(
        targetDate = targetDate.toString(),
        totalSnapshots = totalSnapshots,
        lastOpenAt = lastOpenAt?.toString(),
        isCurrentlyOpen = isCurrentlyOpen,
        currentOrLastOpenWindowSec = currentOrLastOpenWindowSec,
        medianOpenWindowSec = medianOpenWindowSec,
        flipsLast24h = flipsLast24h,
    )
```

The verbose FQN on `AVAILABILITY_SNAPSHOT` inside the `targetDates` discovery query is to avoid touching the file's existing imports. If it bothers you, add `import ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.Companion.AVAILABILITY_SNAPSHOT` at the top and shorten.

- [ ] **Step 2: Add new schema imports**

The route uses `AvailabilitySnapshotStatsSchema` and `AvailabilitySnapshotsSummaryResponse`. Add to the imports at the top:

```kotlin
import ca.floo.roadtrip.models.api.AvailabilitySnapshotStatsSchema
import ca.floo.roadtrip.models.api.AvailabilitySnapshotsSummaryResponse
```

- [ ] **Step 3: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutes.kt
git commit -m "Add GET /api/availability/snapshots/summary"
```

---

## Task 5: Route test for summary endpoint

**Files:**

- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutesTest.kt`

Three tests:
1. Happy path with seeded data returns the right shape.
2. Missing rid returns 400.
3. Unknown rid returns 404.

- [ ] **Step 1: Add the helper to seed a reservable + snapshot**

Inside `AvailabilityDashboardRoutesTest`, near the existing `seedJob` helper, add:

```kotlin
    private fun seedReservable(): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name
                ) VALUES (
                    'site', 'recgov', '330257', 'federal-campsites', 'A12'
                ) RETURNING id
                """.trimIndent(),
            )!!.get("id", Long::class.java)

    private fun insertSnapshot(
        reservableId: Long,
        targetDate: String,
        observedAt: OffsetDateTime,
        available: Boolean,
    ) {
        ctx.execute(
            """
            INSERT INTO availability_snapshot (
                reservable_id, observed_at, target_date, status, available, day_payload
            ) VALUES (?, ?, ?::date, ?, ?, '{}'::jsonb)
            """.trimIndent(),
            reservableId,
            observedAt,
            targetDate,
            if (available) "available" else "booked",
            available,
        )
    }
```

If `OffsetDateTime` isn't imported in the test file yet, add `import java.time.OffsetDateTime` and `import java.time.ZoneOffset`.

- [ ] **Step 2: Add the three tests**

```kotlin
    @Test
    fun `GET snapshots summary returns stats per date`() = testApplication {
        application { routing { availabilityDashboardRoutes(ctx) } }
        val reservableId = seedReservable()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        insertSnapshot(reservableId, "2026-07-04", now.minusMinutes(3), available = false)
        insertSnapshot(reservableId, "2026-07-04", now.minusMinutes(2), available = true)
        insertSnapshot(reservableId, "2026-07-04", now.minusMinutes(1), available = true)
        val resp = client.get("/api/availability/snapshots/summary?reservable_rid=site:recgov:330257")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("site:recgov:330257", body["reservable_rid"]!!.jsonPrimitive.content)
        val stats = body["stats"]!!.jsonArray
        assertEquals(1, stats.size)
        val row = stats[0].jsonObject
        assertEquals("2026-07-04", row["target_date"]!!.jsonPrimitive.content)
        assertEquals(3, row["total_snapshots"]!!.jsonPrimitive.int)
        assertEquals(true, row["is_currently_open"]!!.jsonPrimitive.boolean)
        assertEquals(1, row["flips_last_24h"]!!.jsonPrimitive.int)
    }

    @Test
    fun `GET snapshots summary requires rid`() = testApplication {
        application { routing { availabilityDashboardRoutes(ctx) } }
        val resp = client.get("/api/availability/snapshots/summary")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("missing_reservable_rid", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET snapshots summary returns 404 on unknown rid`() = testApplication {
        application { routing { availabilityDashboardRoutes(ctx) } }
        val resp = client.get("/api/availability/snapshots/summary?reservable_rid=site:recgov:999999")
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
```

If the existing test file lacks `kotlinx.serialization.json.boolean`, add `import kotlinx.serialization.json.boolean` to the imports.

- [ ] **Step 3: Run tests**

```bash
cd backend
./gradlew test --tests AvailabilityDashboardRoutesTest --rerun-tasks
```

Expected: 9 tests passing total (6 existing + 3 new).

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutesTest.kt
git commit -m "Tests for snapshot summary endpoint"
```

---

## Task 6: API client + snapshots tab stats block

**Files:**

- Modify: `web/api/availability-dashboard-api.js`
- Modify: `web/components/availability/snapshots-tab.js`

The stats block renders ABOVE the existing list, only when filtering by `reservable_rid` (run-id filtering doesn't have stats).

- [ ] **Step 1: Add the API client function**

Append to `web/api/availability-dashboard-api.js`:

```javascript
export function getSnapshotsSummary(reservableRid, { signal } = {}) {
  const qs = new URLSearchParams({ reservable_rid: String(reservableRid) });
  return jsonGetOk(`/api/availability/snapshots/summary?${qs}`, { signal });
}
```

- [ ] **Step 2: Render stats above the snapshots list**

Edit `web/components/availability/snapshots-tab.js`. Update the import:

```javascript
import {
  getSnapshotsSummary,
  listSnapshotsForReservable,
  listSnapshotsForRun,
} from '/web/api/availability-dashboard-api.js';
```

Replace the existing innerHTML template — add a stats container above results:

```javascript
  rootEl.innerHTML = `
    <section class="panel">
      <h2>Filter</h2>
      <form id="snap-filter" class="filters">
        <label>Reservable RID <input name="reservable_rid" placeholder="site:recgov:330257"></label>
        <label>Run ID <input name="run_id" inputmode="numeric"></label>
        <div class="actions">
          <button class="primary" type="submit">Apply</button>
          <button type="reset">Reset</button>
        </div>
      </form>
    </section>
    <section class="panel" id="snap-stats-panel" hidden>
      <h2>Stats</h2>
      <div id="snap-stats"></div>
    </section>
    <section class="panel" aria-live="polite">
      <div id="snap-status" class="status">Set a Reservable RID or Run ID to load snapshots.</div>
      <div id="snap-results"></div>
    </section>
  `;
```

Then, inside `refresh`, after a successful reservable-rid load, fetch + render stats:

```javascript
  async function refresh() {
    const fd = new FormData(filterForm);
    const rid = (fd.get('reservable_rid') || '').trim();
    const runId = (fd.get('run_id') || '').trim();
    if (!rid === !runId) {
      statusEl.textContent = 'Set exactly one of Reservable RID or Run ID.';
      resultsEl.innerHTML = '';
      hideStats();
      return;
    }
    statusEl.textContent = 'Loading…';
    try {
      const data = rid
        ? await listSnapshotsForReservable(rid)
        : await listSnapshotsForRun(runId);
      statusEl.textContent = `${data.snapshots.length} snapshot${data.snapshots.length === 1 ? '' : 's'}.`;
      render(data.snapshots);
      if (rid) {
        await refreshStats(rid);
      } else {
        hideStats();
      }
    } catch (err) {
      statusEl.textContent = `Error: ${err.message}`;
      resultsEl.innerHTML = '';
      hideStats();
    }
  }
```

Add helpers (anywhere inside `mount` is fine):

```javascript
  const statsPanel = rootEl.querySelector('#snap-stats-panel');
  const statsEl = rootEl.querySelector('#snap-stats');

  async function refreshStats(rid) {
    try {
      const data = await getSnapshotsSummary(rid);
      if (data.stats.length === 0) {
        hideStats();
        return;
      }
      statsPanel.hidden = false;
      statsEl.innerHTML = `
        <table class="data-table">
          <thead><tr>
            <th>target date</th><th>last open</th><th>open window</th>
            <th>median 24h</th><th>flips 24h</th><th>snapshots</th>
          </tr></thead>
          <tbody>
            ${data.stats.map(renderStatsRow).join('')}
          </tbody>
        </table>
      `;
    } catch (err) {
      hideStats();
    }
  }

  function hideStats() {
    statsPanel.hidden = true;
    statsEl.innerHTML = '';
  }

  function renderStatsRow(s) {
    const lastOpen =
      s.is_currently_open ? '<strong>open NOW</strong>' :
      s.last_open_at ? `${escapeHtml(formatTimestamp(s.last_open_at))}` :
      '<span class="muted">never seen open</span>';
    const window =
      s.current_or_last_open_window_sec != null
        ? formatDuration(s.current_or_last_open_window_sec)
        : '—';
    const median =
      s.median_open_window_sec != null ? formatDuration(s.median_open_window_sec) : '—';
    return `
      <tr>
        <td>${escapeHtml(s.target_date)}</td>
        <td>${lastOpen}</td>
        <td>${escapeHtml(window)}</td>
        <td>${escapeHtml(median)}</td>
        <td>${escapeHtml(s.flips_last_24h)}</td>
        <td>${escapeHtml(s.total_snapshots)}</td>
      </tr>
    `;
  }

  function formatDuration(sec) {
    if (sec < 60) return `${sec}s`;
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    if (m < 60) return `${m}m ${s}s`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
  }
```

- [ ] **Step 3: Syntax check**

```bash
node --check web/api/availability-dashboard-api.js
node --check web/components/availability/snapshots-tab.js
```

Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add web/api/availability-dashboard-api.js web/components/availability/snapshots-tab.js
git commit -m "Snapshots tab: per-target-date stats block"
```

---

## Task 7: Manual smoke

**Files:** none.

- [ ] **Step 1: Restart backend**

```bash
tilt up
```

Wait for `scheduler availability starting`.

- [ ] **Step 2: Pick a reservable that has snapshots**

```bash
psql "$ROADTRIP_DB_URL" -c "
SELECT r.type || ':' || r.vendor || ':' || r.vendor_id AS rid,
       count(*) AS snaps
FROM availability_snapshot s
JOIN reservables r ON r.id = s.reservable_id
GROUP BY rid ORDER BY snaps DESC LIMIT 3;"
```

Take an rid with non-zero snapshots.

- [ ] **Step 3: Verify the endpoint**

```bash
curl -s "http://localhost:8765/api/availability/snapshots/summary?reservable_rid=<rid>" | jq .
```

Expected: a `stats` array with one row per distinct target_date observed.

- [ ] **Step 4: Verify the UI**

Open `http://localhost:8765/availability?tab=snapshots&reservable_rid=<rid>`. The page should show:
- Filter panel (existing)
- Stats panel with a small table of per-target-date stats (new)
- Snapshot list (existing)

If currently-open dates show "open NOW" in bold, the bracket logic is correct.

---

## Task 8: Lint, push, open PR

- [ ] **Step 1: Format + check**

```bash
cd backend
./gradlew --stop
./gradlew ktlintFormat ktlintCheck
```

Expected: green.

- [ ] **Step 2: Full test**

```bash
./gradlew test --rerun-tasks 2>&1 | tail -3
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit any ktlintFormat changes**

```bash
cd /Users/wc/code/github/wwchen/roadtrip
git add backend/src
git commit -m "ktlintFormat" || true
```

- [ ] **Step 4: Add the plan doc**

```bash
git add docs/superpowers/plans/2026-06-15-pr6-snapshot-stats.md
git commit -m "Add snapshot stats plan"
```

- [ ] **Step 5: Push + PR**

```bash
git push -u origin avail-snapshot-stats

cat > pr_body.md <<'PR'
## Snapshot per-target-date stats

Stacks on PR #230. Adds a stats block to the Snapshots tab showing per-(reservable, target_date) summary: last seen open, current or last open window, median open window over 24h, flips in 24h, total snapshots.

### What ships
- `AvailabilitySnapshotRepo.summarize(...)`: walks contiguous available=true runs to compute the stats.
- `GET /api/availability/snapshots/summary?reservable_rid=...`: new endpoint returning per-date stats.
- Snapshots tab: stats table above the existing chronological list when filtering by reservable rid.

### Why
Popular sites are booked 99% of the time and only briefly available between cancellations. The chronological list is mostly red and unreadable for popularity questions. Stats answer "is this site ever open, and for how long" in one row.

The median open window is the load-bearing future signal: a follow-up PR can use it to dynamically tune `cadence_sec` per-job (sites with 11s open windows need faster polling than the 60s default).

### Verification
- `./gradlew ktlintCheck test` — green
- Manual: hit `/availability?tab=snapshots&reservable_rid=<rid>` after a watch has accumulated history.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
PR

gh pr create --title "Snapshot per-target-date stats" --body-file pr_body.md --base avail-dashboard --repo wwchen/roadtrip
rm pr_body.md
```

- [ ] **Step 6: Verify CI**

```bash
gh pr checks
```

Expected: green.

---

## Out of scope (deferred)

- **Watch detail heatmap** at `/watches/{id}`. Stacked PR after this one. Reuses the summarize logic, fans out across all child reservables of a POI, renders as a `(reservable × date)` grid grouped by `loop`.
- **Cadence tuning.** The median open window is now observable; using it to adjust `availability_job.cadence_sec` per-job is a future PR with its own design discussion (when do we tighten? when do we loosen? what's the floor?).
- **Sparkline visualization.** Not building. Stats answer the same questions with less code.
- **Cross-park loop joins.** Loop is free-text; this PR only uses it within a single POI's scope, where collisions don't matter.
