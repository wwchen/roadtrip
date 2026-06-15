# PR 1: Availability Watches — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `reservable_availability_monitors` with a reshaped `availability_watch` table, repo, routes, and `/watches` admin page. Establishes the vocabulary and entity boundaries for the rest of the stack (jobs, runs, snapshots, dispatches) without yet shipping a scheduler or worker.

**Architecture:** New table `availability_watch` widens scope from "one reservable" to "POI-with-filters or one reservable" and renames `cadence_sec`/`trigger_actions`/`stop_when_triggered` semantics to belong to *intent only* (no scheduler columns yet — those move onto `availability_job` in PR 2). The old `reservable_availability_monitors` table and `/api/reservables/availability/monitors` routes are removed. `reservable_availability_log` stays untouched in PR 1; it gets renamed to `availability_snapshot` in PR 4.

**Tech Stack:** Kotlin/Ktor backend, jOOQ + Flyway + Postgres, kotlinx.serialization for DTOs, vanilla JS frontend with shared components in `web/components/`, Testcontainers Postgres for route tests.

**Reference docs:** `docs/superpowers/specs/2026-06-15-availability-watches-design.md`, `docs/backend-architecture.md`, `docs/booking-providers.md`.

**Stack base:** Branch from `master`. PR 2 (`avail_job` + scheduler) stacks on this branch.

---

## File map

**Created:**
- `backend/src/main/resources/db/migration/V14__avail_watches.sql` — drop monitors, create watch table
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt`
- `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt`
- `watches.html` — replaces `monitors.html`
- `web/watches.js` — replaces `web/monitors.js`
- `web/api/watches-api.js`

**Modified:**
- `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt` — wire new routes; replace `/monitors` route with `/watches`
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/ReservableRoutes.kt` — remove monitor handlers + DTOs
- `backend/src/main/kotlin/ca/floo/roadtrip/models/api/ReservableSchemas.kt` — remove monitor schemas
- `pois.html`, `reservables.html` — update top nav (remove `/monitors`, add `/watches`)
- `web/components/catalog.css` — minor: rename `.catalog-page-monitors` → `.catalog-page-watches` if used

**Deleted:**
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/ReservableAvailabilityMonitorRepo.kt`
- `monitors.html`
- `web/monitors.js`

**Untouched (reshape lands in PR 4):**
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/ReservableAvailabilityLogRepo.kt`
- `backend/src/main/resources/db/migration/V13__reservable_availability_monitors.sql` (the `reservable_availability_log` table created in V13 stays)

---

## Task 1: Migration — drop monitors, create avail_watch

**Files:**
- Create: `backend/src/main/resources/db/migration/V14__avail_watches.sql`

- [ ] **Step 1: Write the migration**

```sql
-- PR 1: Replace reservable_availability_monitors with availability_watch.
--
-- The watch table is user intent only. Scheduler state (next_run_at, claim
-- token, lease) lives on a separate availability_job table introduced in
-- PR 2. Watch scope widens from "one reservable" to "POI-with-filters OR
-- one reservable" so a single watch can cover all child sites of a
-- campground.
--
-- The old reservable_availability_log table (V13) is left intact and gets
-- renamed to availability_snapshot in PR 4.

DROP TABLE IF EXISTS reservable_availability_monitors;

CREATE TABLE availability_watch (
  id                    BIGSERIAL    PRIMARY KEY,
  poi_id                BIGINT       REFERENCES pois(id)        ON DELETE CASCADE,
  reservable_id         BIGINT       REFERENCES reservables(id) ON DELETE CASCADE,
  reservable_filters    JSONB        NOT NULL DEFAULT '{}'::jsonb
                                       CHECK (jsonb_typeof(reservable_filters) = 'object'),
  target_dates          DATE[]       NOT NULL
                                       CHECK (cardinality(target_dates) > 0),
  min_nights            INT          NOT NULL DEFAULT 1
                                       CHECK (min_nights >= 1),
  cadence_sec           INT          NOT NULL
                                       CHECK (cadence_sec >= 5),
  trigger_kinds         TEXT[]       NOT NULL
                                       CHECK (cardinality(trigger_kinds) > 0),
  trigger_config        JSONB        NOT NULL DEFAULT '{}'::jsonb
                                       CHECK (jsonb_typeof(trigger_config) = 'object'),
  stop_when_triggered   BOOLEAN      NOT NULL DEFAULT TRUE,
  status                TEXT         NOT NULL DEFAULT 'active'
                                       CHECK (status IN ('active', 'paused', 'done')),
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT availability_watch_scope_check CHECK (
    (poi_id IS NOT NULL AND reservable_id IS NULL)
    OR (poi_id IS NULL AND reservable_id IS NOT NULL)
  )
);

CREATE INDEX availability_watch_active_idx
  ON availability_watch (status)
  WHERE status = 'active';

CREATE INDEX availability_watch_poi_idx
  ON availability_watch (poi_id)
  WHERE poi_id IS NOT NULL;

CREATE INDEX availability_watch_reservable_idx
  ON availability_watch (reservable_id)
  WHERE reservable_id IS NOT NULL;
```

- [ ] **Step 2: Run jOOQ codegen + tests to confirm migration applies cleanly**

Run: `./gradlew jooqCodegen test --tests AvailabilityWatchRoutesTest`
Expected: jOOQ regenerates against the new table. Test class doesn't exist yet — Gradle will report no matching tests. That's fine; we run the codegen here and confirm the migration is valid SQL.

Alternative quick check: `./gradlew compileKotlin` after codegen runs.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V14__avail_watches.sql
git commit -m "PR 1: add availability_watch table, drop monitors"
```

---

## Task 2: Watch DTO schemas

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/ReservableSchemas.kt:43-78` (remove monitor schemas)

- [ ] **Step 1: Create the new schema file**

```kotlin
package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AvailabilityWatchCreateRequest(
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("reservable_id") val reservableId: Long? = null,
    @SerialName("reservable_filters") val reservableFilters: JsonObject = JsonObject(emptyMap()),
    @SerialName("target_dates") val targetDates: List<String>,
    @SerialName("min_nights") val minNights: Int = 1,
    @SerialName("cadence_sec") val cadenceSec: Int,
    @SerialName("trigger_kinds") val triggerKinds: List<String>,
    @SerialName("trigger_config") val triggerConfig: JsonObject = JsonObject(emptyMap()),
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean = true,
)

@Serializable
data class AvailabilityWatchUpdateRequest(
    @SerialName("reservable_filters") val reservableFilters: JsonObject? = null,
    @SerialName("target_dates") val targetDates: List<String>? = null,
    @SerialName("min_nights") val minNights: Int? = null,
    @SerialName("cadence_sec") val cadenceSec: Int? = null,
    @SerialName("trigger_kinds") val triggerKinds: List<String>? = null,
    @SerialName("trigger_config") val triggerConfig: JsonObject? = null,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean? = null,
    val status: String? = null,
)

@Serializable
data class AvailabilityWatchSchema(
    val id: Long,
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("reservable_id") val reservableId: Long? = null,
    val reservable: ReservableSchema? = null,
    @SerialName("reservable_filters") val reservableFilters: JsonObject,
    @SerialName("target_dates") val targetDates: List<String>,
    @SerialName("min_nights") val minNights: Int,
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
```

- [ ] **Step 2: Remove old monitor schemas from `ReservableSchemas.kt`**

Open `backend/src/main/kotlin/ca/floo/roadtrip/models/api/ReservableSchemas.kt` and delete the four `@Serializable data class` blocks at the bottom (`ReservableAvailabilityMonitorCreateRequestSchema`, `ReservableAvailabilityMonitorSchema`, `ReservableAvailabilityMonitorResponseSchema`, `ReservableAvailabilityMonitorListResponseSchema`). Also delete the `import kotlinx.serialization.json.JsonArray` line if nothing else in the file uses it.

- [ ] **Step 3: Compile to confirm import sites still build**

Run: `./gradlew compileKotlin`
Expected: failures only in `ReservableRoutes.kt` referencing the deleted monitor schemas. Those are fixed in Task 4.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt backend/src/main/kotlin/ca/floo/roadtrip/models/api/ReservableSchemas.kt
git commit -m "PR 1: add AvailabilityWatch DTOs, drop monitor DTOs"
```

---

## Task 3: AvailabilityWatchRepo — TDD

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt`
- Test: covered by route tests in Task 5 (no separate repo test class — repos in this codebase are tested through route tests against Testcontainers Postgres; see `ReservableRoutesTest.kt` as the pattern)

- [ ] **Step 1: Write the repo**

The repo writes/reads the `availability_watch` table and joins through to `reservables` when a watch is reservable-scoped. `JsonObject` columns serialize to `JSONB`; arrays use jOOQ array binding.

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityWatch.Companion.AVAILABILITY_WATCH
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.models.Reservable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime

private const val DEFAULT_LIST_LIMIT = 100
private const val MAX_LIST_LIMIT = 500

class AvailabilityWatchRepo(
    private val ctx: DSLContext,
) {
    private val reservables = ReservableRepo(ctx)
    private val json = Json

    data class CreateInput(
        val poiId: Long?,
        val reservableId: Long?,
        val reservableFilters: JsonObject,
        val targetDates: List<LocalDate>,
        val minNights: Int,
        val cadenceSec: Int,
        val triggerKinds: List<String>,
        val triggerConfig: JsonObject,
        val stopWhenTriggered: Boolean,
    )

    data class UpdateInput(
        val reservableFilters: JsonObject? = null,
        val targetDates: List<LocalDate>? = null,
        val minNights: Int? = null,
        val cadenceSec: Int? = null,
        val triggerKinds: List<String>? = null,
        val triggerConfig: JsonObject? = null,
        val stopWhenTriggered: Boolean? = null,
        val status: String? = null,
    )

    data class Watch(
        val id: Long,
        val poiId: Long?,
        val reservableId: Long?,
        val reservable: Reservable?,
        val reservableFilters: JsonObject,
        val targetDates: List<LocalDate>,
        val minNights: Int,
        val cadenceSec: Int,
        val triggerKinds: List<String>,
        val triggerConfig: JsonObject,
        val stopWhenTriggered: Boolean,
        val status: String,
        val createdAt: OffsetDateTime,
        val updatedAt: OffsetDateTime,
    )

    fun create(input: CreateInput): Watch {
        require((input.poiId == null) xor (input.reservableId == null)) {
            "exactly one of poiId/reservableId must be set"
        }
        val id =
            ctx
                .insertInto(AVAILABILITY_WATCH)
                .set(AVAILABILITY_WATCH.POI_ID, input.poiId)
                .set(AVAILABILITY_WATCH.RESERVABLE_ID, input.reservableId)
                .set(AVAILABILITY_WATCH.RESERVABLE_FILTERS, JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.reservableFilters)))
                .set(AVAILABILITY_WATCH.TARGET_DATES, input.targetDates.toTypedArray())
                .set(AVAILABILITY_WATCH.MIN_NIGHTS, input.minNights)
                .set(AVAILABILITY_WATCH.CADENCE_SEC, input.cadenceSec)
                .set(AVAILABILITY_WATCH.TRIGGER_KINDS, input.triggerKinds.toTypedArray())
                .set(AVAILABILITY_WATCH.TRIGGER_CONFIG, JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.triggerConfig)))
                .set(AVAILABILITY_WATCH.STOP_WHEN_TRIGGERED, input.stopWhenTriggered)
                .returningResult(AVAILABILITY_WATCH.ID)
                .fetchOne()!!
                .value1()!!
        return findById(id)!!
    }

    fun findById(id: Long): Watch? = baseSelect().where(AVAILABILITY_WATCH.ID.eq(id)).fetchOne()?.let(::fromRecord)

    fun list(
        status: String? = null,
        poiId: Long? = null,
        reservableId: Long? = null,
        limit: Int = DEFAULT_LIST_LIMIT,
        offset: Int = 0,
    ): List<Watch> {
        val effectiveLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        val conds = mutableListOf<org.jooq.Condition>()
        if (status != null) conds += AVAILABILITY_WATCH.STATUS.eq(status)
        if (poiId != null) conds += AVAILABILITY_WATCH.POI_ID.eq(poiId)
        if (reservableId != null) conds += AVAILABILITY_WATCH.RESERVABLE_ID.eq(reservableId)
        return baseSelect()
            .where(if (conds.isEmpty()) DSL.noCondition() else DSL.and(conds))
            .orderBy(AVAILABILITY_WATCH.CREATED_AT.desc(), AVAILABILITY_WATCH.ID.desc())
            .limit(effectiveLimit)
            .offset(offset)
            .fetch { fromRecord(it) }
    }

    fun count(
        status: String? = null,
        poiId: Long? = null,
        reservableId: Long? = null,
    ): Int {
        val conds = mutableListOf<org.jooq.Condition>()
        if (status != null) conds += AVAILABILITY_WATCH.STATUS.eq(status)
        if (poiId != null) conds += AVAILABILITY_WATCH.POI_ID.eq(poiId)
        if (reservableId != null) conds += AVAILABILITY_WATCH.RESERVABLE_ID.eq(reservableId)
        return ctx
            .selectCount()
            .from(AVAILABILITY_WATCH)
            .where(if (conds.isEmpty()) DSL.noCondition() else DSL.and(conds))
            .fetchOne(0, Int::class.java) ?: 0
    }

    fun update(
        id: Long,
        input: UpdateInput,
    ): Watch? {
        var query = ctx.update(AVAILABILITY_WATCH).set(AVAILABILITY_WATCH.UPDATED_AT, OffsetDateTime.now())
        if (input.reservableFilters != null) {
            query = query.set(AVAILABILITY_WATCH.RESERVABLE_FILTERS, JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.reservableFilters)))
        }
        if (input.targetDates != null) query = query.set(AVAILABILITY_WATCH.TARGET_DATES, input.targetDates.toTypedArray())
        if (input.minNights != null) query = query.set(AVAILABILITY_WATCH.MIN_NIGHTS, input.minNights)
        if (input.cadenceSec != null) query = query.set(AVAILABILITY_WATCH.CADENCE_SEC, input.cadenceSec)
        if (input.triggerKinds != null) query = query.set(AVAILABILITY_WATCH.TRIGGER_KINDS, input.triggerKinds.toTypedArray())
        if (input.triggerConfig != null) {
            query = query.set(AVAILABILITY_WATCH.TRIGGER_CONFIG, JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.triggerConfig)))
        }
        if (input.stopWhenTriggered != null) query = query.set(AVAILABILITY_WATCH.STOP_WHEN_TRIGGERED, input.stopWhenTriggered)
        if (input.status != null) {
            require(input.status in setOf("active", "paused", "done")) { "invalid status" }
            query = query.set(AVAILABILITY_WATCH.STATUS, input.status)
        }
        val rows = query.where(AVAILABILITY_WATCH.ID.eq(id)).execute()
        if (rows == 0) return null
        return findById(id)
    }

    fun delete(id: Long): Boolean = ctx.deleteFrom(AVAILABILITY_WATCH).where(AVAILABILITY_WATCH.ID.eq(id)).execute() > 0

    private fun baseSelect() =
        ctx
            .select(AVAILABILITY_WATCH.fields().toList() + RESERVABLES.fields().toList())
            .from(AVAILABILITY_WATCH)
            .leftJoin(RESERVABLES)
            .on(RESERVABLES.ID.eq(AVAILABILITY_WATCH.RESERVABLE_ID))

    private fun fromRecord(r: Record): Watch {
        val reservableId = r.get(AVAILABILITY_WATCH.RESERVABLE_ID)
        return Watch(
            id = r.get(AVAILABILITY_WATCH.ID)!!,
            poiId = r.get(AVAILABILITY_WATCH.POI_ID),
            reservableId = reservableId,
            reservable = if (reservableId != null) reservables.fromRecord(r) else null,
            reservableFilters = json.parseToJsonElement(r.get(AVAILABILITY_WATCH.RESERVABLE_FILTERS)!!.data()).jsonObject,
            targetDates = r.get(AVAILABILITY_WATCH.TARGET_DATES)!!.toList(),
            minNights = r.get(AVAILABILITY_WATCH.MIN_NIGHTS)!!,
            cadenceSec = r.get(AVAILABILITY_WATCH.CADENCE_SEC)!!,
            triggerKinds = r.get(AVAILABILITY_WATCH.TRIGGER_KINDS)!!.toList(),
            triggerConfig = json.parseToJsonElement(r.get(AVAILABILITY_WATCH.TRIGGER_CONFIG)!!.data()).jsonObject,
            stopWhenTriggered = r.get(AVAILABILITY_WATCH.STOP_WHEN_TRIGGERED)!!,
            status = r.get(AVAILABILITY_WATCH.STATUS)!!,
            createdAt = r.get(AVAILABILITY_WATCH.CREATED_AT)!!,
            updatedAt = r.get(AVAILABILITY_WATCH.UPDATED_AT)!!,
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: passes (route tests reference this in Task 5; route file is the next failure, fixed in Task 4).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt
git commit -m "PR 1: add AvailabilityWatchRepo with full CRUD"
```

---

## Task 4: Routes — `/api/availability/watches` CRUD + remove monitor handlers

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/ReservableRoutes.kt` (remove monitor sections)
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/repo/ReservableAvailabilityMonitorRepo.kt`

- [ ] **Step 1: Create the route file**

```kotlin
package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.models.api.AvailabilityWatchListResponse
import ca.floo.roadtrip.models.api.AvailabilityWatchResponse
import ca.floo.roadtrip.models.api.AvailabilityWatchSchema
import ca.floo.roadtrip.models.api.ReservableSchema
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo.Watch
import ca.floo.roadtrip.repo.ReservableRepo
import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.patch
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jooq.DSLContext
import java.time.LocalDate

@OptIn(ExperimentalSerializationApi::class)
private val watchJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

fun Route.availabilityWatchRoutes(ctx: DSLContext) {
    val watches = AvailabilityWatchRepo(ctx)
    val reservables = ReservableRepo(ctx)

    get("/api/availability/watches", {
        tags = listOf("availability")
        summary = "List availability watches"
        request {
            queryParameter<String>("status") { description = "active | paused | done" }
            queryParameter<Long>("poi_id") { description = "Filter to watches scoped to this POI." }
            queryParameter<Long>("reservable_id") { description = "Filter to watches scoped to this reservable." }
            queryParameter<Int>("limit") { description = "Page size, default 100, max 500." }
            queryParameter<Int>("offset") { description = "Page offset, default 0." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityWatchListResponse> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val status = call.request.queryParameters["status"]
        val poiId = call.request.queryParameters["poi_id"]?.toLongOrNull()
        val reservableId = call.request.queryParameters["reservable_id"]?.toLongOrNull()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        val rows = watches.list(status, poiId, reservableId, limit, offset)
        val total = watches.count(status, poiId, reservableId)
        call.respondJson(
            AvailabilityWatchListResponse(
                total = total,
                limit = limit,
                offset = offset,
                watches = rows.map { it.toSchema() },
            ),
        )
    }

    get("/api/availability/watches/{id}", {
        tags = listOf("availability")
        summary = "Get one watch"
        response {
            code(HttpStatusCode.OK) { body<AvailabilityWatchResponse> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return@get call.respondError("invalid_id", HttpStatusCode.BadRequest)
        val watch = watches.findById(id)
            ?: return@get call.respondError("not_found", HttpStatusCode.NotFound)
        call.respondJson(AvailabilityWatchResponse(watch.toSchema()))
    }

    post("/api/availability/watches", {
        tags = listOf("availability")
        summary = "Create a watch"
        request { body<AvailabilityWatchCreateRequest> { mediaTypes(ContentType.Application.Json) } }
        response {
            code(HttpStatusCode.Created) { body<AvailabilityWatchResponse> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val raw = call.receiveText()
        val req =
            try {
                watchJson.decodeFromString<AvailabilityWatchCreateRequest>(raw)
            } catch (e: Exception) {
                return@post call.respondError("invalid_body", HttpStatusCode.BadRequest, e.message)
            }
        val err = validateCreate(req, reservables)
        if (err != null) return@post call.respondError(err.first, HttpStatusCode.BadRequest, err.second)
        val watch =
            watches.create(
                AvailabilityWatchRepo.CreateInput(
                    poiId = req.poiId,
                    reservableId = req.reservableId,
                    reservableFilters = req.reservableFilters,
                    targetDates = req.targetDates.map(LocalDate::parse),
                    minNights = req.minNights,
                    cadenceSec = req.cadenceSec,
                    triggerKinds = req.triggerKinds,
                    triggerConfig = req.triggerConfig,
                    stopWhenTriggered = req.stopWhenTriggered,
                ),
            )
        call.respondJson(AvailabilityWatchResponse(watch.toSchema()), HttpStatusCode.Created)
    }

    patch("/api/availability/watches/{id}", {
        tags = listOf("availability")
        summary = "Update a watch"
        response {
            code(HttpStatusCode.OK) { body<AvailabilityWatchResponse> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return@patch call.respondError("invalid_id", HttpStatusCode.BadRequest)
        val raw = call.receiveText()
        val req =
            try {
                watchJson.decodeFromString<ca.floo.roadtrip.models.api.AvailabilityWatchUpdateRequest>(raw)
            } catch (e: Exception) {
                return@patch call.respondError("invalid_body", HttpStatusCode.BadRequest, e.message)
            }
        val updated =
            watches.update(
                id,
                AvailabilityWatchRepo.UpdateInput(
                    reservableFilters = req.reservableFilters,
                    targetDates = req.targetDates?.map(LocalDate::parse),
                    minNights = req.minNights,
                    cadenceSec = req.cadenceSec,
                    triggerKinds = req.triggerKinds,
                    triggerConfig = req.triggerConfig,
                    stopWhenTriggered = req.stopWhenTriggered,
                    status = req.status,
                ),
            ) ?: return@patch call.respondError("not_found", HttpStatusCode.NotFound)
        call.respondJson(AvailabilityWatchResponse(updated.toSchema()))
    }

    delete("/api/availability/watches/{id}", {
        tags = listOf("availability")
        summary = "Delete a watch"
        response {
            code(HttpStatusCode.NoContent) { description = "Deleted." }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return@delete call.respondError("invalid_id", HttpStatusCode.BadRequest)
        if (watches.delete(id)) {
            call.respondText("", status = HttpStatusCode.NoContent)
        } else {
            call.respondError("not_found", HttpStatusCode.NotFound)
        }
    }
}

private fun validateCreate(
    req: AvailabilityWatchCreateRequest,
    reservables: ReservableRepo,
): Pair<String, String?>? {
    if ((req.poiId == null) == (req.reservableId == null)) {
        return "invalid_scope" to "exactly one of poi_id, reservable_id must be set"
    }
    if (req.targetDates.isEmpty()) return "invalid_target_dates" to "target_dates must be non-empty"
    runCatching { req.targetDates.forEach(LocalDate::parse) }
        .onFailure { return "invalid_target_dates" to it.message }
    if (req.minNights < 1) return "invalid_min_nights" to "min_nights must be >= 1"
    if (req.cadenceSec < 5) return "invalid_cadence" to "cadence_sec must be >= 5"
    if (req.triggerKinds.isEmpty()) return "invalid_triggers" to "trigger_kinds must be non-empty"
    if (req.reservableId != null && reservables.findById(req.reservableId) == null) {
        return "reservable_not_found" to "no reservable with id ${req.reservableId}"
    }
    return null
}

private fun Watch.toSchema(): AvailabilityWatchSchema =
    AvailabilityWatchSchema(
        id = id,
        poiId = poiId,
        reservableId = reservableId,
        reservable = reservable?.let { r ->
            ReservableSchema(
                rid = r.id().value,
                type = r.type.value,
                vendor = r.vendor,
                vendorId = r.vendorId,
                name = r.name,
                loop = r.loop,
                siteType = r.siteType,
                poiIds = emptyList(),
                raw = r.raw,
            )
        },
        reservableFilters = reservableFilters,
        targetDates = targetDates.map { it.toString() },
        minNights = minNights,
        cadenceSec = cadenceSec,
        triggerKinds = triggerKinds,
        triggerConfig = triggerConfig,
        stopWhenTriggered = stopWhenTriggered,
        status = status,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private suspend inline fun <reified T> ApplicationCall.respondJson(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(watchJson.encodeToString(body), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    val payload = ApiErrorSchema(error = error, detail = detail)
    respondText(watchJson.encodeToString(payload), ContentType.Application.Json, status)
}
```

(Note: the codebase uses `ReservableRepo.findById` style — confirm exact method name when implementing. If it's `findById`, use it; if it's named differently, adjust the validator accordingly. The test in Task 5 will fail loudly if this is wrong.)

- [ ] **Step 2: Strip monitor handlers from `ReservableRoutes.kt`**

Open `backend/src/main/kotlin/ca/floo/roadtrip/routes/ReservableRoutes.kt`. Remove:

- The `import` lines for `ReservableAvailabilityMonitor*` schemas and `ReservableAvailabilityMonitorRepo`.
- The `val monitors = ReservableAvailabilityMonitorRepo(ctx)` line.
- All routes under `/api/reservables/{rid}/availability/monitors` (POST + GET handlers).

Leave the rest of `reservableRoutes(ctx)` intact.

- [ ] **Step 3: Delete the old monitor repo**

```bash
git rm backend/src/main/kotlin/ca/floo/roadtrip/repo/ReservableAvailabilityMonitorRepo.kt
```

- [ ] **Step 4: Wire route in Main.kt**

Open `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`. Add `import ca.floo.roadtrip.routes.availabilityWatchRoutes` (alphabetically). Inside `routing { … }`, add a call after `reservableRoutes(ctx)`:

```kotlin
        availabilityWatchRoutes(ctx)
```

Also replace the `/monitors` static-file routes (lines ~230-235) with `/watches`:

```kotlin
        get("/watches") {
            call.respondFile(File(staticDir, "watches.html"))
        }
        get("/watches/") {
            call.respondFile(File(staticDir, "watches.html"))
        }
```

- [ ] **Step 5: Compile**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: passes. The `ReservableRoutesTest` may have monitor-related tests that fail to compile — if so, delete those test methods (they belong with the deleted monitor routes).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt backend/src/main/kotlin/ca/floo/roadtrip/routes/ReservableRoutes.kt backend/src/main/kotlin/ca/floo/roadtrip/Main.kt
git rm backend/src/main/kotlin/ca/floo/roadtrip/repo/ReservableAvailabilityMonitorRepo.kt
git commit -m "PR 1: add /api/availability/watches CRUD; remove monitor routes"
```

---

## Task 5: Route tests — TDD

**Files:**
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt`

Pattern from `ReservableRoutesTest.kt`: one Testcontainers Postgres for the class, ktor `testApplication` per test method.

- [ ] **Step 1: Write the failing test**

```kotlin
package ca.floo.roadtrip.routes

import ca.floo.roadtrip.repo.migrate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilityWatchRoutesTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext

    @BeforeAll
    fun start() {
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg = PostgreSQLContainer<Nothing>(image).apply {
            withDatabaseName("roadtrip_test")
            withUsername("test")
            withPassword("test")
        }
        pg.start()
        val cfg = HikariConfig().apply {
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
        ctx.deleteFrom(ca.floo.roadtrip.db.generated.tables.AvailabilityWatch.AVAILABILITY_WATCH).execute()
        ctx.deleteFrom(ca.floo.roadtrip.db.generated.tables.Reservables.RESERVABLES).execute()
        ctx.deleteFrom(ca.floo.roadtrip.db.generated.tables.Pois.POIS).execute()
    }

    private fun seedPoi(): Long =
        ctx
            .insertInto(ca.floo.roadtrip.db.generated.tables.Pois.POIS)
            .set(ca.floo.roadtrip.db.generated.tables.Pois.POIS.NAME, "Upper Pines")
            .set(ca.floo.roadtrip.db.generated.tables.Pois.POIS.CATEGORY, "campground")
            .returningResult(ca.floo.roadtrip.db.generated.tables.Pois.POIS.ID)
            .fetchOne()!!.value1()!!

    @Test
    fun `POST creates a poi-scoped watch with filters`() = testApplication {
        application { routing { availabilityWatchRoutes(ctx) } }
        val poiId = seedPoi()
        val body = """
            {
              "poi_id": $poiId,
              "reservable_filters": {"loop": ["A"]},
              "target_dates": ["2026-07-04", "2026-07-05"],
              "min_nights": 2,
              "cadence_sec": 60,
              "trigger_kinds": ["atc"]
            }
        """.trimIndent()
        val resp = client.post("/api/availability/watches") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
        assertEquals(poiId, obj["poi_id"]!!.jsonPrimitive.long)
        assertEquals(2, obj["target_dates"]!!.jsonArray.size)
        assertEquals("active", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `POST rejects missing scope`() = testApplication {
        application { routing { availabilityWatchRoutes(ctx) } }
        val body = """
            {"target_dates": ["2026-07-04"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
        """.trimIndent()
        val resp = client.post("/api/availability/watches") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("invalid_scope", obj["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET list filters by status`() = testApplication {
        application { routing { availabilityWatchRoutes(ctx) } }
        val poiId = seedPoi()
        val body = """
            {"poi_id": $poiId, "target_dates": ["2026-07-04"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
        """.trimIndent()
        repeat(3) {
            client.post("/api/availability/watches") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val resp = client.get("/api/availability/watches?status=active")
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(3, obj["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `PATCH pauses a watch`() = testApplication {
        application { routing { availabilityWatchRoutes(ctx) } }
        val poiId = seedPoi()
        val body = """
            {"poi_id": $poiId, "target_dates": ["2026-07-04"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
        """.trimIndent()
        val created = client.post("/api/availability/watches") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["watch"]!!.jsonObject["id"]!!.jsonPrimitive.long
        val resp = client.patch("/api/availability/watches/$id") {
            contentType(ContentType.Application.Json)
            setBody("""{"status": "paused"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
        assertEquals("paused", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `DELETE removes a watch`() = testApplication {
        application { routing { availabilityWatchRoutes(ctx) } }
        val poiId = seedPoi()
        val body = """
            {"poi_id": $poiId, "target_dates": ["2026-07-04"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
        """.trimIndent()
        val created = client.post("/api/availability/watches") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["watch"]!!.jsonObject["id"]!!.jsonPrimitive.long
        val del = client.delete("/api/availability/watches/$id")
        assertEquals(HttpStatusCode.NoContent, del.status)
        val getAfter = client.get("/api/availability/watches/$id")
        assertEquals(HttpStatusCode.NotFound, getAfter.status)
    }
}
```

(Imports of `Long.long`, `Int.int` come from `kotlinx.serialization.json.long` / `kotlinx.serialization.json.int`. If your IDE shows them red, add `import kotlinx.serialization.json.long` and `import kotlinx.serialization.json.int`.)

(Note on POI seed: the test inserts a minimal POI row. If `pois` requires more columns (`source`, `geom`, etc. — check `V1__pois.sql` and `V5__pois_v2.sql`), extend `seedPoi()` to set whatever columns are NOT NULL with no default. The test won't compile until the columns match the live schema; that's intentional.)

- [ ] **Step 2: Run the tests**

Run: `./gradlew test --tests AvailabilityWatchRoutesTest`
Expected: All five tests pass. If they fail, the most likely causes are (a) `seedPoi()` missing required columns, (b) `ReservableRepo` method names diverging from what `validateCreate` calls.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt
git commit -m "PR 1: add AvailabilityWatchRoutesTest"
```

---

## Task 6: Frontend — `/watches` page

The frontend follows the dense-table-with-create-form pattern from `pollers.html` (in PR #224's branch — for this PR we're on master, so port the layout fresh). Use existing `web/components/catalog.css` styles and the shared form components.

**Files:**
- Create: `watches.html`
- Create: `web/watches.js`
- Create: `web/api/watches-api.js`
- Modify: `pois.html`, `reservables.html` (nav links)
- Delete: `monitors.html`, `web/monitors.js`

- [ ] **Step 1: Write the API helper**

```javascript
// web/api/watches-api.js
const BASE = '/api/availability/watches';

export async function listWatches({ status, poiId, reservableId, limit = 100, offset = 0 } = {}) {
    const qs = new URLSearchParams();
    if (status) qs.set('status', status);
    if (poiId != null) qs.set('poi_id', poiId);
    if (reservableId != null) qs.set('reservable_id', reservableId);
    qs.set('limit', limit);
    qs.set('offset', offset);
    const r = await fetch(`${BASE}?${qs}`);
    if (!r.ok) throw new Error(`list watches: ${r.status}`);
    return r.json();
}

export async function getWatch(id) {
    const r = await fetch(`${BASE}/${id}`);
    if (!r.ok) throw new Error(`get watch: ${r.status}`);
    return r.json();
}

export async function createWatch(body) {
    const r = await fetch(BASE, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!r.ok) throw new Error(`create watch: ${r.status} ${await r.text()}`);
    return r.json();
}

export async function updateWatch(id, body) {
    const r = await fetch(`${BASE}/${id}`, {
        method: 'PATCH',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!r.ok) throw new Error(`update watch: ${r.status} ${await r.text()}`);
    return r.json();
}

export async function deleteWatch(id) {
    const r = await fetch(`${BASE}/${id}`, { method: 'DELETE' });
    if (!r.ok && r.status !== 404) throw new Error(`delete watch: ${r.status}`);
}
```

- [ ] **Step 2: Write watches.html**

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#26272d">
<title>Availability Watches</title>
<link rel="stylesheet" href="/web/components/catalog.css">
</head>
<body class="catalog-page catalog-page-watches">
<main class="shell">
  <header class="top">
    <div>
      <h1>Availability Watches</h1>
      <div class="sub">Reservable availability intents and their status.</div>
    </div>
    <nav class="nav" aria-label="Page links">
      <div class="nav-group" aria-label="Catalog pages">
        <a href="/pois">POIs</a>
        <a href="/reservables">Reservables</a>
        <a href="/watches" aria-current="page">Watches</a>
      </div>
      <div class="nav-group" aria-label="Outside links">
        <a class="outside-link" href="/">Map</a>
        <a class="outside-link" href="/api/docs">API docs</a>
      </div>
    </nav>
  </header>

  <section class="panel">
    <h2>Filter</h2>
    <form id="filter-form" class="filters">
      <label>Status
        <select name="status">
          <option value="">any</option>
          <option value="active" selected>active</option>
          <option value="paused">paused</option>
          <option value="done">done</option>
        </select>
      </label>
      <label>POI ID <input name="poi_id" inputmode="numeric"></label>
      <label>Reservable ID <input name="reservable_id" inputmode="numeric"></label>
      <div class="actions">
        <button class="primary" type="submit">Apply</button>
        <button type="reset">Reset</button>
      </div>
    </form>
  </section>

  <section class="panel">
    <h2>Create watch</h2>
    <form id="create-form" class="form-stack">
      <label>POI ID (or)
        <input name="poi_id" inputmode="numeric" placeholder="2258">
      </label>
      <label>Reservable ID
        <input name="reservable_id" inputmode="numeric" placeholder="leave blank if poi_id set">
      </label>
      <label>Reservable filters (JSON object)
        <textarea name="reservable_filters" rows="2">{}</textarea>
      </label>
      <label>Target dates (comma-separated YYYY-MM-DD)
        <input name="target_dates" placeholder="2026-07-04, 2026-07-05" required>
      </label>
      <label>Min nights <input name="min_nights" type="number" min="1" value="1"></label>
      <label>Cadence (seconds) <input name="cadence_sec" type="number" min="5" value="60" required></label>
      <label>Trigger kinds (comma-separated)
        <input name="trigger_kinds" value="atc" required>
      </label>
      <label>Trigger config (JSON)
        <textarea name="trigger_config" rows="2">{}</textarea>
      </label>
      <label class="checkbox">
        <input name="stop_when_triggered" type="checkbox" checked>
        Stop when triggered
      </label>
      <div class="actions">
        <button class="primary" type="submit">Create</button>
      </div>
    </form>
  </section>

  <section class="panel" aria-live="polite">
    <div id="status" class="status">Loading…</div>
    <div id="results"></div>
    <div id="empty" class="empty" hidden>No watches.</div>
  </section>
</main>
<script type="module" src="/web/watches.js"></script>
</body>
</html>
```

- [ ] **Step 3: Write watches.js**

```javascript
// web/watches.js
import { listWatches, createWatch, updateWatch, deleteWatch } from '/web/api/watches-api.js';

const filterForm = document.getElementById('filter-form');
const createForm = document.getElementById('create-form');
const statusEl = document.getElementById('status');
const resultsEl = document.getElementById('results');
const emptyEl = document.getElementById('empty');

filterForm.addEventListener('submit', (e) => {
    e.preventDefault();
    refresh();
});
filterForm.addEventListener('reset', () => {
    setTimeout(refresh, 0);
});

createForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const fd = new FormData(createForm);
    const body = buildCreatePayload(fd);
    if (!body) return;
    try {
        await createWatch(body);
        createForm.reset();
        await refresh();
    } catch (err) {
        statusEl.textContent = `Create failed: ${err.message}`;
    }
});

function buildCreatePayload(fd) {
    const poiId = fd.get('poi_id')?.trim();
    const reservableId = fd.get('reservable_id')?.trim();
    if (!poiId === !reservableId) {
        statusEl.textContent = 'Set exactly one of POI ID or Reservable ID.';
        return null;
    }
    let filters = {};
    let triggerConfig = {};
    try {
        filters = JSON.parse(fd.get('reservable_filters') || '{}');
        triggerConfig = JSON.parse(fd.get('trigger_config') || '{}');
    } catch (err) {
        statusEl.textContent = `Invalid JSON: ${err.message}`;
        return null;
    }
    const targetDates = (fd.get('target_dates') || '')
        .split(',').map((s) => s.trim()).filter(Boolean);
    const triggerKinds = (fd.get('trigger_kinds') || '')
        .split(',').map((s) => s.trim()).filter(Boolean);
    return {
        poi_id: poiId ? Number(poiId) : null,
        reservable_id: reservableId ? Number(reservableId) : null,
        reservable_filters: filters,
        target_dates: targetDates,
        min_nights: Number(fd.get('min_nights') || 1),
        cadence_sec: Number(fd.get('cadence_sec') || 60),
        trigger_kinds: triggerKinds,
        trigger_config: triggerConfig,
        stop_when_triggered: fd.get('stop_when_triggered') === 'on',
    };
}

async function refresh() {
    const fd = new FormData(filterForm);
    const params = {
        status: fd.get('status') || undefined,
        poiId: fd.get('poi_id') ? Number(fd.get('poi_id')) : undefined,
        reservableId: fd.get('reservable_id') ? Number(fd.get('reservable_id')) : undefined,
    };
    statusEl.textContent = 'Loading…';
    try {
        const data = await listWatches(params);
        statusEl.textContent = `${data.total} watch${data.total === 1 ? '' : 'es'}.`;
        render(data.watches);
    } catch (err) {
        statusEl.textContent = `Error: ${err.message}`;
        resultsEl.innerHTML = '';
    }
}

function render(watches) {
    if (watches.length === 0) {
        emptyEl.hidden = false;
        resultsEl.innerHTML = '';
        return;
    }
    emptyEl.hidden = true;
    const rows = watches.map(renderRow).join('');
    resultsEl.innerHTML = `
      <table class="data-table">
        <thead><tr>
          <th>id</th><th>scope</th><th>dates</th>
          <th>cadence</th><th>triggers</th><th>status</th><th>actions</th>
        </tr></thead>
        <tbody>${rows}</tbody>
      </table>`;
    resultsEl.querySelectorAll('[data-action]').forEach((btn) => {
        btn.addEventListener('click', onAction);
    });
}

function renderRow(w) {
    const scope = w.poi_id != null
        ? `poi:${w.poi_id}${Object.keys(w.reservable_filters).length ? ' (filtered)' : ''}`
        : `resv:${w.reservable?.rid ?? w.reservable_id}`;
    const dates = `${w.target_dates.length} dt${w.target_dates.length === 1 ? '' : 's'}`;
    const triggers = w.trigger_kinds.join(', ');
    return `
      <tr>
        <td>${w.id}</td>
        <td>${escapeHtml(scope)}</td>
        <td>${dates}</td>
        <td>${w.cadence_sec}s</td>
        <td>${escapeHtml(triggers)}</td>
        <td>${w.status}</td>
        <td>
          ${w.status === 'active'
            ? `<button data-action="pause" data-id="${w.id}">⏸</button>`
            : w.status === 'paused'
                ? `<button data-action="resume" data-id="${w.id}">▶</button>`
                : ''}
          <button data-action="delete" data-id="${w.id}">✕</button>
        </td>
      </tr>`;
}

async function onAction(e) {
    const btn = e.currentTarget;
    const id = btn.dataset.id;
    const action = btn.dataset.action;
    try {
        if (action === 'pause') await updateWatch(id, { status: 'paused' });
        else if (action === 'resume') await updateWatch(id, { status: 'active' });
        else if (action === 'delete') await deleteWatch(id);
        await refresh();
    } catch (err) {
        statusEl.textContent = `Action failed: ${err.message}`;
    }
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
}

refresh();
```

- [ ] **Step 4: Update nav on existing pages**

Open `pois.html` and `reservables.html`. In the `<nav class="nav-group">` block, replace:

```html
<a href="/monitors">Monitors</a>
```

with:

```html
<a href="/watches">Watches</a>
```

If those pages don't have a Monitors link (some don't on master), still ensure `/watches` is present.

- [ ] **Step 5: Delete the old monitors files**

```bash
git rm monitors.html web/monitors.js
```

- [ ] **Step 6: Smoke test in browser**

Run: `tilt up` (per memory `reference_tilt_dev_stack`)
Open: `http://localhost:8080/watches`
Verify:
1. Page loads with empty state.
2. Creating a watch with `poi_id=1` (or whatever exists in dev DB), `target_dates=2026-07-04`, `cadence_sec=60`, `trigger_kinds=atc` succeeds and shows the row.
3. Pause button flips status to `paused`.
4. Delete button removes the row.
5. Filter status to `paused` and confirm only paused rows show.
6. POIs and Reservables top-nav links navigate correctly; the Watches link is highlighted on `/watches`.

If a step fails: capture the failure message from `#status` and the network panel; fix before committing.

- [ ] **Step 7: Commit**

```bash
git add watches.html web/watches.js web/api/watches-api.js pois.html reservables.html
git rm monitors.html web/monitors.js
git commit -m "PR 1: add /watches admin page; remove /monitors"
```

---

## Task 7: Lint, full test pass, ship

- [ ] **Step 1: Run ktlint and tests**

```bash
./gradlew ktlintCheck test
```

Expected: green. If ktlint complains about formatting, run `./gradlew ktlintFormat` and re-commit.

- [ ] **Step 2: Verify spec doc + plan doc are in this branch**

Run: `git log --oneline master..HEAD -- docs/superpowers/`
Expected: at least the spec from the brainstorming session shows up. If not, cherry-pick or commit it now:

```bash
git add docs/superpowers/specs/2026-06-15-availability-watches-design.md docs/superpowers/plans/2026-06-15-pr1-avail-watches.md
git commit -m "PR 1: add availability watches design and PR 1 plan"
```

- [ ] **Step 3: Push and open PR**

```bash
git push -u origin availability-watches-redesign
```

Write the PR body to a temp file (per global memory) and open the PR:

```bash
cat > pr_body.md <<'PR'
## PR 1: Availability watches

First slice of the availability watches/jobs/snapshots/dispatches redesign. See `docs/superpowers/specs/2026-06-15-availability-watches-design.md` for the full design and `docs/superpowers/plans/2026-06-15-pr1-avail-watches.md` for the PR plan.

This PR replaces `reservable_availability_monitors` with `availability_watch`:

- New table widens scope from "one reservable" to "POI-with-filters OR one reservable"
- Splits trigger config: `trigger_kinds TEXT[]` + `trigger_config JSONB`
- Removes scheduler columns from intent table — those move onto `availability_job` in PR 2
- New routes under `/api/availability/watches` (CRUD)
- New `/watches` admin page replaces `/monitors`

Stack: PR 2 will add `availability_job` + scheduler abstraction.

## Verification
- `./gradlew ktlintCheck test`
- Manual: `tilt up`; create/pause/resume/delete watches at `/watches`
PR

gh pr create --title "PR 1: avail watches (intent table + routes + UI)" --body-file pr_body.md
rm pr_body.md
```

- [ ] **Step 4: Verify CI green**

```bash
gh pr checks
```

Expected: all checks pass. If anything fails, fix on this branch and push; do not merge until green.

---

## Out of scope (later PRs in stack)

- **PR 2:** `availability_job` + `Scheduler<T>` abstraction. Watches start auto-creating their backing job; ad-hoc poll endpoint creates a `cadence_sec=0` job.
- **PR 3:** `availability_job_run` + worker + `/availability` operator dashboard (jobs/runs tabs).
- **PR 4:** Rename `reservable_availability_log` → `availability_snapshot`; add `reservable_id` FK; snapshots tab + reservable timeline.
- **PR 5:** `availability_dispatch` + companion-facing HTTP outbox endpoints (`/api/dispatches/claim` etc.) + dispatches tab.
