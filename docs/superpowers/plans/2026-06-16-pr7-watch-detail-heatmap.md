# Watch Detail Heatmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `/watches/{id}` detail page showing the watch's metadata plus a `(child reservable × target_date)` heatmap colored by latest snapshot status, grouped by `loop`. Operators can answer "is my watch finding anything across the loops I care about" in one glance.

**Architecture:** New backend endpoint `GET /api/availability/watches/{id}/heatmap` that fans out: load the watch → resolve its reservable scope (single reservable for reservable-scoped watches, all `findByPoi` children filtered by `reservable_filters` for poi-scoped watches) → for each (reservable, target_date) cell, look up the most recent snapshot and return its status. Frontend renders the page as a grouped table with cells colored by status. Reuses `summarize` from PR #231 for per-reservable open-window stats above the heatmap. No schema changes.

**Tech Stack:** Kotlin/Ktor, jOOQ + Postgres. Vanilla JS + the existing `web/components/catalog.css`.

**Reference docs:** Prior PRs in the stack: #226 (watches), #229 (snapshots), #230 (dashboard), #231 (snapshot stats).

**Stack base:** Branch from `avail-snapshot-stats` (PR #231).

---

## Heatmap shape

The endpoint returns:

```kotlin
data class HeatmapResponse(
    val watchId: Long,
    val targetDates: List<String>,    // X-axis, in chronological order
    val groups: List<HeatmapGroup>,
)

data class HeatmapGroup(
    val loop: String?,                 // null group = "(no loop)"
    val rows: List<HeatmapRow>,
)

data class HeatmapRow(
    val reservableId: Long,
    val reservableRid: String,
    val name: String?,
    val cells: List<HeatmapCell>,      // one per targetDate, same order
)

data class HeatmapCell(
    val targetDate: String,
    val status: String?,               // 'available' | 'partial' | 'booked' | 'closed' | null (no snapshot yet)
    val available: Boolean?,
    val observedAt: String?,
)
```

Rendering: each cell is a colored block. Status colors:

- `available` → green
- `partial` → yellow (kept for completeness even though single-reservable single-date won't actually produce partial)
- `booked` → red
- `closed` → gray
- `null` (no snapshot) → empty/dashed

### Reservable scope for poi-scoped watches

Watches store `reservable_filters: JsonObject`. PR 1 specced filters like `{"loop":["A","B"]}` and `{"site_type":"STANDARD"}`. For the heatmap, the page applies filters in-memory after `findByPoi`:

- If `reservable_filters` contains `loop` (string or list of strings), only include reservables whose `reservable.loop` matches.
- If `site_type`, only include matching `reservable.siteType`.
- Other keys are ignored for now (forward-compat).

This is intentionally simple — fancier filters can move to repo-level later.

### "Latest snapshot" per cell

For each (reservable_id, target_date), pick the most recent `availability_snapshot` row. SQL with `DISTINCT ON` is the natural fit. One query for the whole heatmap, joined to `reservables` for the loop/name.

---

## File map

**Created:**

- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepo.kt` — single repo class with one method, `loadHeatmap(reservableIds, targetDates)`, that runs the `DISTINCT ON` query.
- `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepoTest.kt` — Testcontainers tests for the heatmap query.
- `watches.html` — already exists. We modify but don't replace.
- `web/watch-detail.js` — new page bootstrap.
- `web/components/availability/watch-heatmap.js` — heatmap render module.

**Modified:**

- `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt` — add heatmap response DTOs.
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt` — add `GET /api/availability/watches/{id}/heatmap`. The existing `availabilityWatchRoutes` function takes `(ctx, watchService)`; add a `heatmaps` repo + `reservables` repo (already in scope via `ReservableRepo(ctx)` inside the function) and a third dep on `AvailabilityHeatmapRepo`.
- `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt` — three new tests for the heatmap endpoint.
- `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt` — add static-file handler for `/watches/<id>` so the new HTML page is reachable.
- `web/api/watches-api.js` — add `getWatchHeatmap(id)`.
- `watches.html` — make ID column a link to `/watches/<id>`.
- `pois.html`, `reservables.html`, `availability.html`, `watches.html` — no nav changes; the watch list links to the detail page directly.
- `docker-compose.yml` — bind-mount the new `watch-detail.html` (separate file so Tilt picks it up).
- `availability.html`, `pois.html`, `reservables.html`, `watches.html` — no changes (the `Activity` link already covers the dashboard).

**Created HTML:**

- `watch-detail.html` — page shell at the repo root (mounted in docker-compose like `watches.html`).

**Untouched:**

- `availability_watch` schema. The endpoint composes existing tables.
- The watches list page styling. Just a link change.
- Cadence tuning. Heatmap consumes the data; tuning is downstream.

---

## Task 1: `AvailabilityHeatmapRepo.loadHeatmap`

**Files:**

- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepo.kt`

The repo executes one SQL query that returns the latest snapshot per (reservable_id, target_date) within the given lists. Postgres `DISTINCT ON` is the load-bearing trick.

- [ ] **Step 1: Create the repo**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.Companion.AVAILABILITY_SNAPSHOT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime

class AvailabilityHeatmapRepo(
    private val ctx: DSLContext,
) {
    data class LatestCell(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: String,
        val available: Boolean,
        val observedAt: OffsetDateTime,
    )

    /**
     * For each (reservable_id, target_date) in the cross product of the two
     * inputs, return the most recent snapshot row. Cells with no snapshot
     * are not present in the result; the route layer fills them as null.
     *
     * Uses Postgres DISTINCT ON so the database returns one row per pair
     * directly, ordered by observed_at DESC. Cheaper than fetching all rows
     * and reducing in Kotlin once histories grow.
     */
    fun loadHeatmap(
        reservableIds: List<Long>,
        targetDates: List<LocalDate>,
    ): List<LatestCell> {
        if (reservableIds.isEmpty() || targetDates.isEmpty()) return emptyList()
        // We can't express DISTINCT ON cleanly in jOOQ DSL across all
        // versions without resorting to plain SQL. Use a parameterized
        // query — reservableIds and targetDates are bound, no string
        // concat.
        val reservableIdsArg = reservableIds.toTypedArray()
        val targetDatesArg = targetDates.toTypedArray()
        return ctx
            .resultQuery(
                """
                SELECT DISTINCT ON (reservable_id, target_date)
                    reservable_id, target_date, status, available, observed_at
                FROM availability_snapshot
                WHERE reservable_id = ANY(?)
                  AND target_date = ANY(?)
                ORDER BY reservable_id, target_date, observed_at DESC
                """.trimIndent(),
                reservableIdsArg,
                targetDatesArg,
            ).fetch { r ->
                LatestCell(
                    reservableId = r.get("reservable_id", Long::class.java),
                    targetDate = r.get("target_date", LocalDate::class.java),
                    status = r.get("status", String::class.java),
                    available = r.get("available", Boolean::class.java),
                    observedAt = r.get("observed_at", OffsetDateTime::class.java),
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

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepo.kt
git commit -m "Add AvailabilityHeatmapRepo with DISTINCT ON query"
```

---

## Task 2: `AvailabilityHeatmapRepoTest`

**Files:**

- Create: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepoTest.kt`

Four tests: empty inputs, single reservable + single date, latest-of-many returns newest, multiple reservables × dates returns the right cell per pair.

- [ ] **Step 1: Write the test**

```kotlin
package ca.floo.roadtrip.repo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilityHeatmapRepoTest {
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

    private fun seedReservable(vendorId: String): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name
                ) VALUES (
                    'site', 'recgov', ?, 'federal-campsites', 'site'
                ) RETURNING id
                """.trimIndent(),
                vendorId,
            )!!.get("id", Long::class.java)

    private fun insertSnapshot(
        reservableId: Long,
        targetDate: LocalDate,
        observedAt: OffsetDateTime,
        available: Boolean,
        status: String = if (available) "available" else "booked",
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
            status,
            available,
        )
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    @Test
    fun `empty inputs return empty result`() {
        val repo = AvailabilityHeatmapRepo(ctx)
        assertTrue(repo.loadHeatmap(emptyList(), listOf(LocalDate.parse("2026-07-04"))).isEmpty())
        assertTrue(repo.loadHeatmap(listOf(1L), emptyList()).isEmpty())
    }

    @Test
    fun `single reservable single date returns one row`() {
        val rid = seedReservable("100")
        val date = LocalDate.parse("2026-07-04")
        insertSnapshot(rid, date, now().minusMinutes(1), available = true)
        val repo = AvailabilityHeatmapRepo(ctx)
        val cells = repo.loadHeatmap(listOf(rid), listOf(date))
        assertEquals(1, cells.size)
        assertEquals(rid, cells[0].reservableId)
        assertEquals(date, cells[0].targetDate)
        assertEquals(true, cells[0].available)
        assertEquals("available", cells[0].status)
    }

    @Test
    fun `latest snapshot wins for same pair`() {
        val rid = seedReservable("100")
        val date = LocalDate.parse("2026-07-04")
        insertSnapshot(rid, date, now().minusMinutes(5), available = false)
        insertSnapshot(rid, date, now().minusMinutes(2), available = true)
        insertSnapshot(rid, date, now().minusMinutes(1), available = false, status = "booked")
        val repo = AvailabilityHeatmapRepo(ctx)
        val cells = repo.loadHeatmap(listOf(rid), listOf(date))
        assertEquals(1, cells.size)
        assertEquals(false, cells[0].available)
        assertEquals("booked", cells[0].status)
    }

    @Test
    fun `cross product returns one cell per pair, missing pairs absent`() {
        val r1 = seedReservable("100")
        val r2 = seedReservable("200")
        val d1 = LocalDate.parse("2026-07-04")
        val d2 = LocalDate.parse("2026-07-05")
        // r1/d1: available; r1/d2: booked; r2/d1: only one snapshot booked; r2/d2: no snapshot
        insertSnapshot(r1, d1, now().minusMinutes(1), available = true)
        insertSnapshot(r1, d2, now().minusMinutes(1), available = false, status = "booked")
        insertSnapshot(r2, d1, now().minusMinutes(1), available = false, status = "closed")
        val repo = AvailabilityHeatmapRepo(ctx)
        val cells = repo.loadHeatmap(listOf(r1, r2), listOf(d1, d2))
        assertEquals(3, cells.size)
        val byPair = cells.associateBy { it.reservableId to it.targetDate }
        assertEquals("available", byPair[r1 to d1]!!.status)
        assertEquals("booked", byPair[r1 to d2]!!.status)
        assertEquals("closed", byPair[r2 to d1]!!.status)
        assertEquals(null, byPair[r2 to d2])
    }
}
```

- [ ] **Step 2: Run tests**

```bash
cd backend
./gradlew --stop
./gradlew test --tests AvailabilityHeatmapRepoTest
```

Expected: 4/4 passing.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepoTest.kt
git commit -m "AvailabilityHeatmapRepo: tests"
```

---

## Task 3: Heatmap response DTOs

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt`

- [ ] **Step 1: Append the DTOs**

Open `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt`. Append:

```kotlin
@Serializable
data class AvailabilityWatchHeatmapCell(
    @SerialName("target_date") val targetDate: String,
    val status: String? = null,
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
    @SerialName("target_dates") val targetDates: List<String>,
    val groups: List<AvailabilityWatchHeatmapGroup>,
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
git add backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt
git commit -m "Add watch heatmap DTOs"
```

---

## Task 4: `GET /api/availability/watches/{id}/heatmap` route

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt`

The route:
1. Loads the watch by id (404 if missing).
2. Resolves child reservables:
   - `reservable_id` set → list of one (looked up via `ReservableRepo.findById`).
   - `poi_id` set → `ReservableRepo.findByPoi(poiId)` filtered by `reservable_filters.loop` and `reservable_filters.site_type` if present.
3. Calls `AvailabilityHeatmapRepo.loadHeatmap(reservableIds, targetDates)`.
4. Builds the response: cells indexed by `(reservable_id, target_date)`; missing pairs become `null` cells.
5. Groups rows by `loop` (sort: alphabetic, NULLs last). Within a group, sort by `name` then `vendor_id`.

- [ ] **Step 1: Add the imports**

Open `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt`. Add to the existing imports (alphabetical):

```kotlin
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapCell
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapGroup
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapResponse
import ca.floo.roadtrip.models.api.AvailabilityWatchHeatmapRow
import ca.floo.roadtrip.models.Reservable
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.repo.AvailabilityHeatmapRepo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
```

- [ ] **Step 2: Add the heatmap repo to the route function**

Find:

```kotlin
fun Route.availabilityWatchRoutes(
    ctx: DSLContext,
    watchService: ca.floo.roadtrip.service.availability.AvailabilityWatchService,
) {
    val watches = AvailabilityWatchRepo(ctx)
    val reservables = ReservableRepo(ctx)
```

Add immediately after:

```kotlin
    val heatmaps = AvailabilityHeatmapRepo(ctx)
```

- [ ] **Step 3: Add the route inside `availabilityWatchRoutes`**

Place this after the existing `delete("/api/availability/watches/{id}", ...)` route, still inside `availabilityWatchRoutes`:

```kotlin
    get("/api/availability/watches/{id}/heatmap", {
        tags = listOf("availability")
        summary = "(child reservable × target_date) heatmap of latest snapshot statuses for a watch"
        request {
            pathParameter<Long>("id") { description = "Watch id." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityWatchHeatmapResponse> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondError("invalid_id", HttpStatusCode.BadRequest)
        val watch =
            watches.findById(id)
                ?: return@get call.respondError("not_found", HttpStatusCode.NotFound)

        val children = resolveChildren(watch, reservables)
        val cells = heatmaps.loadHeatmap(children.map { it.id }, watch.targetDates)
        val cellsByPair = cells.associateBy { it.reservableId to it.targetDate }

        val targetDateStrings = watch.targetDates.map { it.toString() }
        val rowsByLoop = LinkedHashMap<String?, MutableList<AvailabilityWatchHeatmapRow>>()
        for (r in children.sortedWith(compareBy(nullsLast()) { it.loop ?: "" }.thenBy { it.name ?: "" }.thenBy { it.rid.vendorId })) {
            val rowCells =
                watch.targetDates.map { d ->
                    val cell = cellsByPair[r.id to d]
                    AvailabilityWatchHeatmapCell(
                        targetDate = d.toString(),
                        status = cell?.status,
                        available = cell?.available,
                        observedAt = cell?.observedAt?.toString(),
                    )
                }
            val key = r.loop?.takeIf { it.isNotBlank() }
            rowsByLoop.getOrPut(key) { mutableListOf() } += AvailabilityWatchHeatmapRow(
                reservableId = r.id,
                reservableRid = r.rid.encode(),
                name = r.name,
                cells = rowCells,
            )
        }

        val groups =
            rowsByLoop.entries.map { (loop, rows) ->
                AvailabilityWatchHeatmapGroup(loop = loop, rows = rows)
            }
        call.respondJson(
            AvailabilityWatchHeatmapResponse(
                watchId = watch.id,
                targetDates = targetDateStrings,
                groups = groups,
            ),
        )
    }
```

- [ ] **Step 4: Add `resolveChildren` helper**

At the bottom of the same file (next to other private helpers), add:

```kotlin
private fun resolveChildren(
    watch: AvailabilityWatchRepo.Watch,
    reservables: ReservableRepo,
): List<Reservable> {
    if (watch.reservableId != null) {
        val r = reservables.findById(watch.reservableId) ?: return emptyList()
        return listOf(r)
    }
    val poiId = watch.poiId ?: return emptyList()
    val all = reservables.findByPoi(poiId, type = ReservableType.parse("site"))
    val loops = collectStringFilter(watch.reservableFilters, "loop")
    val siteTypes = collectStringFilter(watch.reservableFilters, "site_type")
    return all.filter { r ->
        (loops.isEmpty() || (r.loop != null && loops.contains(r.loop))) &&
            (siteTypes.isEmpty() || (r.siteType != null && siteTypes.contains(r.siteType)))
    }
}

private fun collectStringFilter(
    filters: kotlinx.serialization.json.JsonObject,
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

- [ ] **Step 5: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL. If you see import or resolution errors, the FQN imports above should cover them; otherwise add the import the compiler suggests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt
git commit -m "Add GET /api/availability/watches/{id}/heatmap"
```

---

## Task 5: Heatmap route tests

**Files:**

- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt`

Three tests covering: 404 for unknown watch, reservable-scoped watch returns one row with cells, poi-scoped watch with `loop` filter returns only matching loops grouped correctly.

- [ ] **Step 1: Add helper methods + tests**

Inside `AvailabilityWatchRoutesTest`, add helpers if not present (the existing `seedPoi` helper is sufficient — extend with `seedReservable` + `linkReservableToPoi` + `insertSnapshot`):

```kotlin
    private fun seedReservable(
        vendorId: String,
        name: String? = null,
        loop: String? = null,
        siteType: String? = null,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name, loop, site_type
                ) VALUES (
                    'site', 'recgov', ?, 'federal-campsites', ?, ?, ?
                ) RETURNING id
                """.trimIndent(),
                vendorId,
                name,
                loop,
                siteType,
            )!!.get("id", Long::class.java)

    private fun linkReservableToPoi(reservableId: Long, poiId: Long) {
        ctx.execute(
            "INSERT INTO reservable_pois (reservable_id, poi_id) VALUES (?, ?)",
            reservableId,
            poiId,
        )
    }

    private fun insertSnapshot(
        reservableId: Long,
        targetDate: String,
        observedAt: java.time.OffsetDateTime,
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

(If `OffsetDateTime` / `ZoneOffset` aren't already imported in the file, add them.)

Append three tests:

```kotlin
    @Test
    fun `GET watch heatmap returns 404 for unknown id`() = testApplication {
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
        val resp = client.get("/api/availability/watches/99999/heatmap")
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `GET watch heatmap for reservable-scoped watch returns one row`() = testApplication {
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
        val poiId = seedPoi(sourceId = "p1", name = "Upper Pines")
        val rid = seedReservable("100", name = "A12", loop = "Loop A")
        linkReservableToPoi(rid, poiId)

        val createBody = """
            {"reservable_rid": "site:recgov:100", "target_dates": ["2026-07-04", "2026-07-05"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
        """.trimIndent()
        val created =
            client.post("/api/availability/watches") {
                contentType(ContentType.Application.Json)
                setBody(createBody)
            }
        val watchId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["watch"]!!.jsonObject["id"]!!.jsonPrimitive.long

        val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
        insertSnapshot(rid, "2026-07-04", now.minusMinutes(1), available = true)

        val resp = client.get("/api/availability/watches/$watchId/heatmap")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(2, body["target_dates"]!!.jsonArray.size)
        val groups = body["groups"]!!.jsonArray
        assertEquals(1, groups.size)
        assertEquals("Loop A", groups[0].jsonObject["loop"]!!.jsonPrimitive.content)
        val rows = groups[0].jsonObject["rows"]!!.jsonArray
        assertEquals(1, rows.size)
        val cells = rows[0].jsonObject["cells"]!!.jsonArray
        assertEquals(2, cells.size)
        assertEquals("available", cells[0].jsonObject["status"]!!.jsonPrimitive.content)
        // Second cell has no snapshot.
        assertEquals(true, cells[1].jsonObject["status"] == null || cells[1].jsonObject["status"]!!.jsonPrimitive.content.isEmpty())
    }

    @Test
    fun `GET watch heatmap for poi-scoped watch filters by loop`() = testApplication {
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
        val poiId = seedPoi(sourceId = "p2", name = "Tunnel Mountain")
        val rA1 = seedReservable("201", name = "A12", loop = "Loop A")
        val rA2 = seedReservable("202", name = "A13", loop = "Loop A")
        val rB1 = seedReservable("203", name = "B05", loop = "Loop B")
        linkReservableToPoi(rA1, poiId)
        linkReservableToPoi(rA2, poiId)
        linkReservableToPoi(rB1, poiId)

        val createBody = """
            {"poi_id": $poiId, "reservable_filters": {"loop": ["Loop A"]}, "target_dates": ["2026-07-04"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
        """.trimIndent()
        val created =
            client.post("/api/availability/watches") {
                contentType(ContentType.Application.Json)
                setBody(createBody)
            }
        val watchId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["watch"]!!.jsonObject["id"]!!.jsonPrimitive.long

        val resp = client.get("/api/availability/watches/$watchId/heatmap")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val groups = body["groups"]!!.jsonArray
        assertEquals(1, groups.size)
        assertEquals("Loop A", groups[0].jsonObject["loop"]!!.jsonPrimitive.content)
        val rows = groups[0].jsonObject["rows"]!!.jsonArray
        assertEquals(2, rows.size)
        // Loop B was filtered out.
        val ridsInResponse = rows.map { it.jsonObject["reservable_rid"]!!.jsonPrimitive.content }
        assertEquals(true, ridsInResponse.contains("site:recgov:201"))
        assertEquals(true, ridsInResponse.contains("site:recgov:202"))
        assertEquals(false, ridsInResponse.contains("site:recgov:203"))
    }
```

If imports `kotlinx.serialization.json.long`, `Json`, `jsonObject`, etc. aren't present, add them — they should be from prior tests in the file.

- [ ] **Step 2: Run tests**

```bash
cd backend
./gradlew --stop
./gradlew test --tests AvailabilityWatchRoutesTest --rerun-tasks
```

Expected: all existing tests + 3 new = passing.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt
git commit -m "Tests for watch heatmap endpoint"
```

---

## Task 6: Frontend — API client function

**Files:**

- Modify: `web/api/watches-api.js`

- [ ] **Step 1: Append the function**

Add at the bottom of `web/api/watches-api.js`:

```javascript
export function getWatchHeatmap(id, { signal } = {}) {
  return jsonGetOk(`/api/availability/watches/${encodeURIComponent(id)}/heatmap`, { signal });
}
```

It needs `jsonGetOk`. The file currently imports `HttpError, jsonGetOk` from `./http.js` — verify and add `jsonGetOk` if missing.

- [ ] **Step 2: Syntax check**

```bash
node --check web/api/watches-api.js
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add web/api/watches-api.js
git commit -m "Add getWatchHeatmap API client"
```

---

## Task 7: `web/components/availability/watch-heatmap.js` render module

**Files:**

- Create: `web/components/availability/watch-heatmap.js`

Renders a grouped table: one section per loop, each section a sub-table of reservables × target_dates with colored cells.

- [ ] **Step 1: Create the file**

```javascript
// Watch detail heatmap: groups of (reservable × target_date) cells colored
// by latest snapshot status.

const STATUS_CLASS = {
  available: 'cell-available',
  partial: 'cell-partial',
  booked: 'cell-booked',
  closed: 'cell-closed',
};

export function renderWatchHeatmap(rootEl, response) {
  const dates = response.target_dates;
  const headerRow = `
    <tr>
      <th class="rowhead">site</th>
      ${dates.map((d) => `<th>${escapeHtml(formatShortDate(d))}</th>`).join('')}
    </tr>
  `;
  const groupsHtml = response.groups.map((g) => renderGroup(g, headerRow)).join('');
  rootEl.innerHTML = `
    <div class="heatmap-legend">
      <span class="legend-swatch cell-available"></span> available
      <span class="legend-swatch cell-booked"></span> booked
      <span class="legend-swatch cell-closed"></span> closed
      <span class="legend-swatch cell-empty"></span> no snapshot
    </div>
    ${groupsHtml}
  `;
}

function renderGroup(group, headerRow) {
  const loopLabel = group.loop ? escapeHtml(group.loop) : '<span class="muted">(no loop)</span>';
  const rows = group.rows.map(renderRow).join('');
  return `
    <section class="heatmap-group">
      <h3 class="heatmap-group-title">${loopLabel}</h3>
      <table class="data-table heatmap-table">
        <thead>${headerRow}</thead>
        <tbody>${rows}</tbody>
      </table>
    </section>
  `;
}

function renderRow(row) {
  const label = row.name ? `${escapeHtml(row.name)} <span class="muted">${escapeHtml(row.reservable_rid)}</span>` : escapeHtml(row.reservable_rid);
  const cells = row.cells.map(renderCell).join('');
  return `
    <tr>
      <td class="rowhead">
        <a href="/availability?tab=snapshots&reservable_rid=${encodeURIComponent(row.reservable_rid)}">${label}</a>
      </td>
      ${cells}
    </tr>
  `;
}

function renderCell(cell) {
  const cls = cell.status ? STATUS_CLASS[cell.status] || 'cell-unknown' : 'cell-empty';
  const title = cell.observed_at
    ? `${cell.status || 'unknown'} as of ${formatTimestamp(cell.observed_at)}`
    : 'no snapshot';
  return `<td class="heatmap-cell ${cls}" title="${escapeHtml(title)}"></td>`;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function formatShortDate(iso) {
  // 2026-07-04 → 7/04
  const [_, m, d] = iso.split('-');
  return `${parseInt(m, 10)}/${d}`;
}

function formatTimestamp(iso) {
  return iso.replace('T', ' ').replace(/\.\d+/, '').replace(/Z$/, '');
}
```

- [ ] **Step 2: Syntax check**

```bash
node --check web/components/availability/watch-heatmap.js
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add web/components/availability/watch-heatmap.js
git commit -m "Add watch detail heatmap render module"
```

---

## Task 8: Watch detail HTML page

**Files:**

- Create: `watch-detail.html` (repo root)
- Create: `web/watch-detail.js`

The HTML page shell mirrors `watches.html` styling: same `<style>` block (vars + nav + panel layout) plus heatmap-specific CSS for cells. Page reads `?id=<watchId>` from the URL.

- [ ] **Step 1: Create `watch-detail.html`**

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#26272d">
<title>Watch Detail</title>
<style>
  :root {
    --surface: #26272d;
    --bg: #1c1d21;
    --bg-subtle: rgba(255,255,255,0.04);
    --bg-hover: rgba(255,255,255,0.06);
    --border: rgba(255,255,255,0.08);
    --border-strong: rgba(255,255,255,0.14);
    --text: #e6e8eb;
    --muted: #8a8f96;
    --faint: #5a6068;
    --accent: #4cb96a;
    --error: #f56565;
    --status-available: rgba(76,185,106,0.55);
    --status-booked: rgba(245,101,101,0.45);
    --status-closed: rgba(150,150,150,0.30);
    --status-empty: rgba(255,255,255,0.05);
  }
  * { box-sizing: border-box; }
  html, body {
    margin: 0;
    min-height: 100%;
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  }
  body { padding: 20px; }
  a { color: var(--accent); text-decoration: none; }
  a:hover { text-decoration: underline; }
  .shell { max-width: 1280px; margin: 0 auto; }
  .top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;
  }
  h1 { margin: 0; font-size: 22px; line-height: 1.2; font-weight: 650; }
  .sub { margin-top: 4px; color: var(--muted); font-size: 13px; }
  .nav {
    display: flex;
    align-items: center;
    gap: 10px 16px;
    flex-wrap: wrap;
    justify-content: flex-end;
  }
  .nav-group { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
  .nav-group + .nav-group {
    padding-left: 14px;
    border-left: 1px solid var(--border-strong);
  }
  .nav a, button {
    border: 1px solid var(--border-strong);
    background: var(--bg-subtle);
    color: var(--text);
    border-radius: 8px;
    height: 34px;
    padding: 0 12px;
    font: inherit;
    font-size: 13px;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }
  .nav a:hover, button:hover { background: var(--bg-hover); text-decoration: none; }
  .nav a[aria-current="page"] {
    border-color: rgba(76,185,106,0.45);
    background: rgba(76,185,106,0.12);
    color: #87d99b;
  }
  .nav a.outside-link { gap: 7px; }
  .nav a.outside-link::after {
    content: "";
    width: 10px;
    height: 10px;
    border-top: 1.5px solid currentColor;
    border-right: 1.5px solid currentColor;
    opacity: 0.7;
  }
  .panel { padding: 14px; margin-bottom: 14px; }
  .panel h2 {
    margin: 0 0 10px;
    font-size: 14px;
    color: var(--muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .meta-grid {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 4px 14px;
    font-size: 13px;
  }
  .meta-grid dt { color: var(--muted); text-transform: uppercase; font-size: 11px; letter-spacing: 0.04em; }
  .meta-grid dd { margin: 0; }
  .heatmap-legend {
    display: flex;
    gap: 14px;
    align-items: center;
    margin-bottom: 12px;
    color: var(--muted);
    font-size: 12px;
  }
  .legend-swatch {
    display: inline-block;
    width: 14px;
    height: 14px;
    border-radius: 3px;
    margin-right: 4px;
    vertical-align: middle;
    border: 1px solid var(--border);
  }
  .heatmap-group-title {
    margin: 12px 0 6px;
    font-size: 13px;
    color: var(--text);
    font-weight: 600;
  }
  .heatmap-table {
    width: auto;
    border-collapse: collapse;
    font-size: 12px;
    margin-bottom: 10px;
  }
  .heatmap-table th, .heatmap-table td {
    border: 1px solid var(--border);
    padding: 4px 6px;
    text-align: center;
    min-width: 36px;
  }
  .heatmap-table .rowhead {
    text-align: left;
    padding: 4px 10px;
    min-width: 220px;
    background: var(--bg-subtle);
  }
  .heatmap-table th {
    color: var(--muted);
    font-weight: 600;
    text-transform: none;
    font-size: 11px;
  }
  .heatmap-cell { width: 28px; height: 22px; padding: 0; }
  .cell-available { background: var(--status-available); }
  .cell-booked { background: var(--status-booked); }
  .cell-closed { background: var(--status-closed); }
  .cell-empty { background: var(--status-empty); }
  .cell-unknown { background: var(--status-empty); }
  .cell-partial { background: linear-gradient(135deg, var(--status-available) 50%, var(--status-booked) 50%); }
  .muted { color: var(--muted); }
  @media (max-width: 640px) {
    body { padding: 12px; }
    .top, .status { align-items: flex-start; flex-direction: column; }
    .nav { justify-content: flex-start; }
  }
</style>
<link rel="stylesheet" href="/web/components/catalog.css">
</head>
<body>
<main class="shell">
  <header class="top">
    <div>
      <h1 id="page-title">Watch</h1>
      <div class="sub" id="page-sub">Loading…</div>
    </div>
    <nav class="nav" aria-label="Page links">
      <div class="nav-group" aria-label="Catalog pages">
        <a href="/pois">POIs</a>
        <a href="/reservables">Reservables</a>
        <a href="/watches">Watches</a>
        <a href="/availability">Activity</a>
      </div>
      <div class="nav-group" aria-label="Outside links">
        <a class="outside-link" href="/">Map</a>
        <a class="outside-link" href="/api/docs">API docs</a>
      </div>
    </nav>
  </header>

  <section class="panel">
    <h2>Metadata</h2>
    <dl class="meta-grid" id="meta"></dl>
  </section>

  <section class="panel">
    <h2>Heatmap</h2>
    <div id="heatmap-status" class="status">Loading…</div>
    <div id="heatmap"></div>
  </section>
</main>
<script type="module" src="/web/watch-detail.js"></script>
</body>
</html>
```

- [ ] **Step 2: Create `web/watch-detail.js`**

```javascript
import { getWatch, getWatchHeatmap } from '/web/api/watches-api.js';
import { renderWatchHeatmap } from '/web/components/availability/watch-heatmap.js';

const titleEl = document.getElementById('page-title');
const subEl = document.getElementById('page-sub');
const metaEl = document.getElementById('meta');
const heatmapStatus = document.getElementById('heatmap-status');
const heatmapEl = document.getElementById('heatmap');

const id = readId();
if (id == null) {
  subEl.textContent = 'No watch id in URL.';
} else {
  Promise.all([loadMeta(id), loadHeatmap(id)]).catch((err) => {
    subEl.textContent = `Error: ${err.message}`;
  });
}

function readId() {
  const qs = new URLSearchParams(window.location.search);
  const id = qs.get('id');
  return id ? Number(id) : null;
}

async function loadMeta(id) {
  try {
    const data = await getWatch(id);
    const w = data.watch;
    titleEl.textContent = `Watch #${w.id}`;
    const scope = w.poi_id != null ? `POI ${w.poi_id}` : `Reservable ${w.reservable?.rid ?? w.reservable_id}`;
    subEl.textContent = `${scope} · ${w.status} · cadence ${w.cadence_sec}s`;
    metaEl.innerHTML = `
      <dt>id</dt><dd>${w.id}</dd>
      <dt>scope</dt><dd>${escapeHtml(scope)}</dd>
      <dt>status</dt><dd>${escapeHtml(w.status)}</dd>
      <dt>cadence</dt><dd>${escapeHtml(w.cadence_sec)}s</dd>
      <dt>target dates</dt><dd>${w.target_dates.map(escapeHtml).join(', ')}</dd>
      <dt>min nights</dt><dd>${escapeHtml(w.min_nights)}</dd>
      <dt>triggers</dt><dd>${w.trigger_kinds.map(escapeHtml).join(', ')}</dd>
      <dt>filters</dt><dd>${escapeHtml(JSON.stringify(w.reservable_filters))}</dd>
      <dt>created</dt><dd>${escapeHtml(formatTimestamp(w.created_at))}</dd>
    `;
  } catch (err) {
    subEl.textContent = `Watch error: ${err.message}`;
    metaEl.innerHTML = '';
  }
}

async function loadHeatmap(id) {
  try {
    const data = await getWatchHeatmap(id);
    if (data.groups.length === 0 || data.groups.every((g) => g.rows.length === 0)) {
      heatmapStatus.textContent = 'No reservables matched this watch yet.';
      heatmapEl.innerHTML = '';
      return;
    }
    const rowCount = data.groups.reduce((acc, g) => acc + g.rows.length, 0);
    heatmapStatus.textContent = `${rowCount} site${rowCount === 1 ? '' : 's'} × ${data.target_dates.length} date${data.target_dates.length === 1 ? '' : 's'}.`;
    renderWatchHeatmap(heatmapEl, data);
  } catch (err) {
    heatmapStatus.textContent = `Heatmap error: ${err.message}`;
    heatmapEl.innerHTML = '';
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function formatTimestamp(iso) {
  return iso.replace('T', ' ').replace(/\.\d+/, '').replace(/Z$/, '');
}
```

- [ ] **Step 3: Syntax check**

```bash
node --check web/watch-detail.js
```

- [ ] **Step 4: Commit**

```bash
git add watch-detail.html web/watch-detail.js
git commit -m "Add /watches/{id} detail page"
```

---

## Task 9: Wire `/watches/{id}` route + watches list link + docker mount

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`
- Modify: `web/watches.js`
- Modify: `docker-compose.yml`

The page is reachable at `/watches?id=<id>` (existing watches list page already supports this URL because the watches list reads `?id=` for filtering — but the detail page is a different file). Approach: serve `watch-detail.html` at `/watches/{id}` (path-based) and update the watches list to link to `/watches/{id}`.

- [ ] **Step 1: Add path route in `Main.kt`**

Open `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`. Find the existing `/watches` static handler and add an id-bearing variant after it:

```kotlin
        get("/watches/{id}") {
            call.respondFile(File(staticDir, "watch-detail.html"))
        }
```

Note: this needs `?id=` reading for the FE — the URL `/watches/123` will be served `watch-detail.html`, but the JS needs to read the `123`. Update `web/watch-detail.js` to fall back to URL path:

In `web/watch-detail.js`, replace the `readId()` function with:

```javascript
function readId() {
  // Prefer ?id=, fall back to /watches/{id} path.
  const qs = new URLSearchParams(window.location.search);
  const fromQuery = qs.get('id');
  if (fromQuery) return Number(fromQuery);
  const match = window.location.pathname.match(/^\/watches\/(\d+)\/?$/);
  return match ? Number(match[1]) : null;
}
```

- [ ] **Step 2: Make watches list ID a link**

Open `web/watches.js`. Find the `renderRow(w)` function (the one rendering watches list rows). The current `<td>${escapeHtml(w.id)}</td>` becomes a link to `/watches/{id}`:

```javascript
        <td>
          <a href="/watches/${encodeURIComponent(w.id)}">${escapeHtml(w.id)}</a>
        </td>
```

- [ ] **Step 3: Update docker-compose**

Open `docker-compose.yml`. Find the `watches.html` mount line. Add immediately after:

```yaml
      - ./watch-detail.html:/app/static/watch-detail.html:ro
```

- [ ] **Step 4: Compile + syntax checks**

```bash
cd backend
./gradlew compileKotlin
node --check web/watches.js
node --check web/watch-detail.js
```

Expected: BUILD SUCCESSFUL. node --check produces no output.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/Main.kt web/watches.js web/watch-detail.js docker-compose.yml
git commit -m "Wire /watches/{id} route + list link"
```

---

## Task 10: Lint + full tests + push + open PR

- [ ] **Step 1: Lint**

```bash
cd backend
./gradlew --stop
./gradlew ktlintFormat
./gradlew ktlintCheck
```

Expected: green.

- [ ] **Step 2: Full test suite**

```bash
./gradlew test --rerun-tasks 2>&1 | tail -3
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit ktlintFormat changes**

```bash
cd /Users/wc/code/github/wwchen/roadtrip
git add backend/src
git commit -m "ktlintFormat" || true
```

- [ ] **Step 4: Add the plan doc**

```bash
git add docs/superpowers/plans/2026-06-16-pr7-watch-detail-heatmap.md
git commit -m "Add watch detail heatmap plan"
```

- [ ] **Step 5: Push + PR**

```bash
git push -u origin avail-watch-detail

cat > pr_body.md <<'PR'
## Watch detail page + heatmap

Stacks on PR #231. Adds `/watches/{id}` showing watch metadata plus a `(child reservable × target_date)` heatmap of latest snapshot statuses, grouped by `loop`.

### What ships
- `AvailabilityHeatmapRepo.loadHeatmap`: single SQL query (`DISTINCT ON`) returning the latest snapshot per (reservable, date) pair.
- `GET /api/availability/watches/{id}/heatmap`: composes watch → child reservables (resolving `reservable_filters.loop` / `site_type`) → heatmap repo → grouped response.
- `/watches/{id}` page (new): metadata block + heatmap with colored cells per status.
- Watches list: ID is now a link to the detail page.

### Why
Operator question "is my watch finding anything?" was hard to answer without reading raw snapshot rows. The heatmap shows availability across all child sites and target dates at once; one cell per (site, date) colored by latest status. Patterns ("only Loop B has openings on weekends") become visible.

### Scope
- Read-only.
- Heatmap shows only `latest status per cell`. Time-series view stays in the snapshots tab.
- Loop grouping is free-text (existing `reservables.loop` column). Cross-park collisions don't apply because the heatmap is always scoped to one watch (one POI).

### Verification
- `./gradlew ktlintCheck test` — green
- Manual: open `/watches/<id>` for an active watch with snapshot history; verify cells colored, loop grouping correct, "PAGE History" link goes to dashboard.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
PR

gh pr create --title "Watch detail page + heatmap" --body-file pr_body.md --base avail-snapshot-stats --repo wwchen/roadtrip
rm pr_body.md
```

- [ ] **Step 6: Verify CI**

```bash
gh pr checks
```

Expected: green.

---

## Out of scope (deferred)

- **Stronger loop association.** Plan accepts free-text join with no schema change.
- **Per-cell drill-down.** Clicking a cell could go to `/availability?tab=snapshots&reservable_rid=…&target_date=…`. Currently each row's site links to its full snapshot history; per-cell linking is one more click that wasn't requested.
- **Auto-refresh.** Page is static after load.
- **Cadence tuning UI.** Watch detail could surface "this watch's median open window is 11s, but cadence is 60s — consider lowering" as an advisory. Tracked separately; needs the cadence-tuner backend first.
- **Sparklines per row.** Current decision: skip in favor of stats block on the snapshots tab (already shipped in PR #231).
