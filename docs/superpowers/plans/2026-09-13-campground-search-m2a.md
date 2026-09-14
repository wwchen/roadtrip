# Campground Search and Bulk Details (M2a of #565) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two new backend endpoints, `POST /api/campgrounds/search` (campground POI ids inside a GeoJSON boundary that pass a filter) and `POST /api/campgrounds/details` (bulk campground summaries), backed by a per-campground site summary the campsite ETL maintains.

**Architecture:** A new `campground_site_summary` table holds site counts by kind, the site total and the largest `max_people` per campground; `CampsiteRepo` refreshes the rows it touches inside the upsert transaction and the migration backfills the rest. `CampgroundSearchRepo` runs the boundary + filter query with PostGIS; `CampgroundRepo` gains a bulk summary read by POI id. `CampgroundSearchService` validates the boundary, applies the caps, and maps rows to DTOs, deciding `availability_supported` exactly as `CampgroundService` does. `CampgroundRoutes` is the HTTP shell. Two `ApiContract` rows publish the DTOs to TypeScript.

**Tech Stack:** Kotlin, Ktor, jOOQ over PostgreSQL + PostGIS, kotlinx.serialization, Koin DI, JUnit 5 with a shared Testcontainers database (`SharedDbTest`), Flyway migrations, ktlint + detekt.

**Spec:** `docs/superpowers/specs/2026-09-12-campground-availability-map-design.md`, section "M2 design (2026-09-13, revised)". Mirrors [#565](https://github.com/wwchen/roadtrip/issues/565); this plan is M2a (backend) only.

## Global Constraints

- Layering is `routes -> service -> repo`. `org.jooq` appears only under `repo/`, `db/` and `di/InfraModule.kt`; `LayeringGuardTest` fails the build otherwise. Services take repo handles, never a `DSLContext`.
- SQL lives in `repo/` classes only. Routes parse, call the service, set status codes, return DTOs.
- Request and response bodies are `@Serializable` data classes under `model/api/`. No hand-built JSON.
- No inline magic constants: caps, limits and defaults are named `const val` or config values.
- Config-driven caps: `CampgroundSearchConfig` under `roadtrip.campground-search`, defaults in code.
- Every new route is an `ApiContract` row stating its success body and every error body it serves. Route tests must produce each success body or `:backend:contractLedgerCheck` fails.
- Never edit `frontend/src/api/generated/api-types.ts` by hand; run `make api-types` (from the repo root) after the DTOs land and commit the diff.
- Never edit an applied migration. New schema goes in `backend/src/main/resources/db/migration/V64__campground_site_summary.sql`.
- Comments are short and rare; write them for the non-obvious only.
- Commit messages follow `type(scope): sentence`, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Backend commands run from the repo root. Docker Desktop must be running (jOOQ codegen uses Testcontainers): `open -a Docker`, wait ~30s. Fast loop: `./gradlew :backend:test --tests '<pattern>' :backend:ktlintCheck :backend:detekt --offline -q`. ktlint fixes: `./gradlew :backend:ktlintFormat --offline -q`.
- The worktree Bash guard rejects paths held in shell variables and compound git-adjacent commands; write literal paths, one command per call.
- A hook denies the first Write/Edit to each file even with facts present; re-issue the identical call immediately.

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `backend/src/main/resources/db/migration/V64__campground_site_summary.sql` | create | The summary table and its backfill. |
| `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundSiteSummary.kt` | create | Domain value: counts by kind, total, max people. |
| `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampsiteRepo.kt` | modify | Refresh the summary rows for the campgrounds an upsert batch touched. |
| `backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogFixtures.kt` | modify | `seedCampsite` gains `maxPeople`; `seedCampground`/`seedCatalogPoi` gain `amenitiesJson`. |
| `backend/src/test/kotlin/ca/floo/roadtrip/repo/CampsiteRepoSummaryTest.kt` | create | Pins the refresh and the backfill. |
| `backend/src/main/kotlin/ca/floo/roadtrip/config/CampgroundSearchConfig.kt` | create | `maxResults`, `maxDetailIds`. |
| `backend/src/main/kotlin/ca/floo/roadtrip/config/AppConfig.kt` | modify | Load the section. |
| `backend/src/test/kotlin/ca/floo/roadtrip/config/CampgroundSearchConfigTest.kt` | create | Defaults and guards. |
| `backend/src/main/kotlin/ca/floo/roadtrip/model/api/campground/*.kt` | create | Request/response DTOs. |
| `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundSearch.kt` | create | Domain filter/result/row types. |
| `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundSearchRepo.kt` | create | Boundary + filter query. |
| `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt` | modify | `findSummariesByPoiIds`. |
| `backend/src/test/kotlin/ca/floo/roadtrip/repo/CampgroundSearchRepoTest.kt` | create | Filter semantics, ordering, truncation. |
| `backend/src/test/kotlin/ca/floo/roadtrip/repo/CampgroundRepoSummaryReadTest.kt` | create | Bulk read shape. |
| `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/CampgroundSearchService.kt` | create | Validation, caps, DTO mapping. |
| `backend/src/test/kotlin/ca/floo/roadtrip/service/poi/CampgroundSearchServiceTest.kt` | create | Boundary validation, cap errors, `availability_supported`. |
| `backend/src/main/kotlin/ca/floo/roadtrip/route/api/campgrounds/CampgroundRoutes.kt` | create | The two routes. |
| `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt` | modify | Two rows. |
| `backend/src/main/kotlin/ca/floo/roadtrip/di/{RepoModule,ServiceModule,RouteModule}.kt` | modify | Wiring. |
| `backend/src/test/kotlin/ca/floo/roadtrip/route/api/campgrounds/CampgroundRoutesTest.kt` | create | Success bodies for the ledger, 400s. |
| `frontend/src/api/generated/api-types.ts` | generated | `make api-types`. |
| `docs/superpowers/specs/2026-09-12-campground-availability-map-design.md` | modify | `checkable_only` moves to M4. |

---

### Task 1: The campsite ETL maintains a per-campground site summary

> Superseded on 2026-09-13: the summary table became an on-read SQL function; see the spec's "Aggregates are computed on read".

**Files:**
- Create: `backend/src/main/resources/db/migration/V64__campground_site_summary.sql`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundSiteSummary.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampsiteRepo.kt` (inside `bulkUpsertCampsitesTx`, after `bulkUpsertCampsiteRows(campsiteRows)`)
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogFixtures.kt` (`seedCampsite`)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CampsiteRepoSummaryTest.kt`

**Interfaces:**
- Consumes: `CampsiteUpsertCandidate`, `CampsiteRepo.upsertCampsiteBatch`, fixtures `seedCampground`, `seedCampsite`, `cleanCanonicalCatalogFixtures`.
- Produces: table `campground_site_summary(campground_id BIGINT PK, site_total INT, site_counts JSONB, max_people INT NULL, updated_at)`; `data class CampgroundSiteSummary(siteTotal: Int, siteCounts: Map<String, Int>, maxPeople: Int?)`; `CampsiteRepo.refreshSiteSummaries(campgroundIds: Collection<Long>)` (public, used by tests and by the upsert); `CampsiteRepo.findSiteSummary(campgroundId: Long): CampgroundSiteSummary?`; file-scope `internal fun decodeSiteCounts(json: String?): Map<String, Int>` in `CampsiteRepo.kt`. Tasks 3 and 4 read the table by join and use the domain class.

- [ ] **Step 1: Extend the campsite fixture so a test can seed `max_people`**

In `CanonicalCatalogFixtures.kt`, `seedCampsite` currently has `kind: String = CampsiteKind.OTHER.wire,`. Add after it:

```kotlin
    maxPeople: Int? = null,
```

Change the INSERT so `max_people` follows `kind`:

```kotlin
        INSERT INTO campsites (
          campground_id, name, kind, max_people, data_provider, data_provider_ref,
          booking_provider, booking_provider_ref, booking_aliases, loop_name, reservation_url, source_payload
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb
        )
```

and bind `maxPeople` as the fourth argument, right after `kind`; every other bind keeps its order. Run `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.CatalogEntityRepoTest' --offline -q` to prove existing callers still pass.

- [ ] **Step 2: Write the failing repo test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/repo/CampsiteRepoSummaryTest.kt`:

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CampgroundSiteSummary
import ca.floo.roadtrip.model.domain.CampsiteKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampsiteRepoSummaryTest : SharedDbTest() {
    private val repo by lazy { CampsiteRepo(ctx) }

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `refresh counts live sites by kind and keeps the largest people count`() {
        val campground = ctx.seedCampground(name = "Nevada Beach", sourceId = "nb")
        ctx.seedCampsite(campgroundId = campground, vendorId = "1", kind = CampsiteKind.TENT.wire, maxPeople = 6)
        ctx.seedCampsite(campgroundId = campground, vendorId = "2", kind = CampsiteKind.TENT.wire, maxPeople = 8)
        ctx.seedCampsite(campgroundId = campground, vendorId = "3", kind = CampsiteKind.RV.wire, maxPeople = null)

        repo.refreshSiteSummaries(listOf(campground))

        assertEquals(
            CampgroundSiteSummary(siteTotal = 3, siteCounts = mapOf("tent" to 2, "rv" to 1), maxPeople = 8),
            repo.findSiteSummary(campground),
        )
    }

    @Test
    fun `a campground whose sites carry no people count summarises to null`() {
        val campground = ctx.seedCampground(name = "Zephyr Cove", sourceId = "zc")
        ctx.seedCampsite(campgroundId = campground, vendorId = "1", kind = CampsiteKind.STANDARD.wire)

        repo.refreshSiteSummaries(listOf(campground))

        assertEquals(
            CampgroundSiteSummary(siteTotal = 1, siteCounts = mapOf("standard" to 1), maxPeople = null),
            repo.findSiteSummary(campground),
        )
    }

    @Test
    fun `a campground with no live sites has no summary row`() {
        val campground = ctx.seedCampground(name = "Empty", sourceId = "empty")

        repo.refreshSiteSummaries(listOf(campground))

        assertNull(repo.findSiteSummary(campground))
    }

    @Test
    fun `soft-deleted sites are not counted`() {
        val campground = ctx.seedCampground(name = "Bayview", sourceId = "bv")
        ctx.seedCampsite(campgroundId = campground, vendorId = "1", kind = CampsiteKind.TENT.wire)
        val gone = ctx.seedCampsite(campgroundId = campground, vendorId = "2", kind = CampsiteKind.TENT.wire)
        ctx.execute("UPDATE campsites SET deleted_at = now() WHERE id = ?", gone)

        repo.refreshSiteSummaries(listOf(campground))

        assertEquals(1, repo.findSiteSummary(campground)?.siteTotal)
    }

    @Test
    fun `a refresh with no ids is a no-op`() {
        repo.refreshSiteSummaries(emptyList())
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.CampsiteRepoSummaryTest' --offline -q`
Expected: compilation failure, `CampgroundSiteSummary` and `refreshSiteSummaries` unresolved.

- [ ] **Step 4: Write the migration**

Create `backend/src/main/resources/db/migration/V64__campground_site_summary.sql`:

```sql
-- One row per campground with live campsites: what the campground filter reads
-- instead of grouping campsites per request. CampsiteRepo refreshes the rows an
-- upsert batch touches; this backfill covers everything already imported.
CREATE TABLE campground_site_summary (
  campground_id  BIGINT      PRIMARY KEY REFERENCES campgrounds(id) ON DELETE CASCADE,
  site_total     INT         NOT NULL,
  site_counts    JSONB       NOT NULL DEFAULT '{}'::jsonb,
  max_people     INT,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT campground_site_summary_total_check CHECK (site_total >= 0),
  CONSTRAINT campground_site_summary_counts_check CHECK (jsonb_typeof(site_counts) = 'object')
);

INSERT INTO campground_site_summary (campground_id, site_total, site_counts, max_people)
SELECT campground_id,
       SUM(kind_count)::int,
       jsonb_object_agg(kind, kind_count),
       MAX(max_people)
FROM (
  SELECT campground_id, kind, COUNT(*)::int AS kind_count, MAX(max_people) AS max_people
  FROM campsites
  WHERE deleted_at IS NULL
  GROUP BY campground_id, kind
) per_kind
GROUP BY campground_id;
```

- [ ] **Step 5: Add the domain class**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundSiteSummary.kt`:

```kotlin
package ca.floo.roadtrip.model.domain

/** Per-campground campsite aggregates, as `campground_site_summary` stores them. */
data class CampgroundSiteSummary(
    val siteTotal: Int,
    /** Live campsites per `CampsiteKind` wire value. */
    val siteCounts: Map<String, Int>,
    /** The largest campsite `max_people`, or null when no site carries one. */
    val maxPeople: Int?,
)
```

- [ ] **Step 6: Add the refresh and the read to `CampsiteRepo`**

In `CampsiteRepo.kt`, inside `bulkUpsertCampsitesTx`, after `bulkUpsertCampsiteRows(campsiteRows)` and before the `return`, add:

```kotlin
        refreshSiteSummaries(campsiteRows.map { it.campgroundId }.distinct())
```

Add at the top of the file (below the imports):

```kotlin
private const val SUMMARY_CHUNK_SIZE = 500
```

Add these members to the class after `upsertCampsiteBatch`:

```kotlin
    /**
     * Recompute `campground_site_summary` for [campgroundIds]. A campground left
     * with no live sites loses its row, so "no summary" always means "no sites".
     */
    fun refreshSiteSummaries(campgroundIds: Collection<Long>) {
        val ids = campgroundIds.distinct()
        if (ids.isEmpty()) return
        for (chunk in ids.chunked(SUMMARY_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(", ") { "?" }
            ctx.execute(
                "DELETE FROM campground_site_summary WHERE campground_id IN ($placeholders)",
                *chunk.toTypedArray(),
            )
            ctx.execute(
                """
                INSERT INTO campground_site_summary (campground_id, site_total, site_counts, max_people, updated_at)
                SELECT campground_id,
                       SUM(kind_count)::int,
                       jsonb_object_agg(kind, kind_count),
                       MAX(max_people),
                       now()
                FROM (
                  SELECT campground_id, kind, COUNT(*)::int AS kind_count, MAX(max_people) AS max_people
                  FROM campsites
                  WHERE deleted_at IS NULL
                    AND campground_id IN ($placeholders)
                  GROUP BY campground_id, kind
                ) per_kind
                GROUP BY campground_id
                """.trimIndent(),
                *chunk.toTypedArray(),
            )
        }
    }

    fun findSiteSummary(campgroundId: Long): CampgroundSiteSummary? =
        ctx
            .fetchOne(
                """
                SELECT site_total, site_counts::text AS site_counts_text, max_people
                FROM campground_site_summary
                WHERE campground_id = ?
                """.trimIndent(),
                campgroundId,
            )?.let { record ->
                CampgroundSiteSummary(
                    siteTotal = record.get("site_total", Int::class.java),
                    siteCounts = decodeSiteCounts(record.get("site_counts_text", String::class.java)),
                    maxPeople = record.get("max_people", Int::class.javaObjectType),
                )
            }
```

Add at file scope (bottom of the file), reused by `CampgroundRepo` in Task 3:

```kotlin
internal fun decodeSiteCounts(json: String?): Map<String, Int> {
    if (json.isNullOrBlank()) return emptyMap()
    return Json.parseToJsonElement(json).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.int }
}
```

Imports: `ca.floo.roadtrip.model.domain.CampgroundSiteSummary`, `kotlinx.serialization.json.Json`, `kotlinx.serialization.json.int`, `kotlinx.serialization.json.jsonObject`, `kotlinx.serialization.json.jsonPrimitive`.

- [ ] **Step 7: Run the tests**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.CampsiteRepoSummaryTest' --tests 'ca.floo.roadtrip.repo.CatalogEntityRepoTest' --tests 'ca.floo.roadtrip.repo.CanonicalCatalogSchemaTest' :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS. The schema test runs the new migration against the shared database.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V64__campground_site_summary.sql backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundSiteSummary.kt backend/src/main/kotlin/ca/floo/roadtrip/repo/CampsiteRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogFixtures.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/CampsiteRepoSummaryTest.kt
git commit -m "feat(etl): keep a per-campground site summary the campground filter can read (#565 M2a)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Config and DTOs

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/config/CampgroundSearchConfig.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/AppConfig.kt` (the `AppConfig` data class and its loader)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/campground/CampgroundFilterDto.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/campground/CampgroundSearchRequestDto.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/campground/CampgroundSearchResponseDto.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/campground/CampgroundDetailsRequestDto.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/campground/CampgroundDetailsResponseDto.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/campground/CampgroundSummaryDto.kt`
- Modify: `docs/superpowers/specs/2026-09-12-campground-availability-map-design.md` (the `CampgroundFilterDto` table)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/config/CampgroundSearchConfigTest.kt`

**Interfaces:**
- Consumes: `ConfigSection` (`config.value(name)`), `AmenityDto`, `RatingDto` from `model/api/poi`.
- Produces: `CampgroundSearchConfig(maxResults: Int, maxDetailIds: Int)` with `default` and `fromConfig(ConfigSection)`; `AppConfig.campgroundSearch`; the six DTOs below, used verbatim by Tasks 3 to 5.

- [ ] **Step 1: Write the failing config test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/config/CampgroundSearchConfigTest.kt`:

```kotlin
package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CampgroundSearchConfigTest {
    @Test
    fun `defaults bound the search and the bulk read`() {
        assertEquals(200, CampgroundSearchConfig.default.maxResults)
        assertEquals(50, CampgroundSearchConfig.default.maxDetailIds)
    }

    @Test
    fun `caps below one are refused`() {
        assertFailsWith<IllegalArgumentException> { CampgroundSearchConfig(maxResults = 0, maxDetailIds = 50) }
        assertFailsWith<IllegalArgumentException> { CampgroundSearchConfig(maxResults = 200, maxDetailIds = 0) }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.config.CampgroundSearchConfigTest' --offline -q`
Expected: compilation failure, `CampgroundSearchConfig` unresolved.

- [ ] **Step 3: Write the config class**

Create `backend/src/main/kotlin/ca/floo/roadtrip/config/CampgroundSearchConfig.kt`:

```kotlin
package ca.floo.roadtrip.config

/**
 * Caps for the campground search read. Both bound one request's work: how many
 * ids a boundary search may return, and how many summaries one details call may
 * ask for.
 */
data class CampgroundSearchConfig(
    val maxResults: Int,
    val maxDetailIds: Int,
) {
    init {
        require(maxResults >= 1) { "campground-search max-results must be >= 1 (got $maxResults)" }
        require(maxDetailIds >= 1) { "campground-search max-detail-ids must be >= 1 (got $maxDetailIds)" }
    }

    companion object {
        private const val DEFAULT_MAX_RESULTS = 200
        private const val DEFAULT_MAX_DETAIL_IDS = 50

        val default =
            CampgroundSearchConfig(
                maxResults = DEFAULT_MAX_RESULTS,
                maxDetailIds = DEFAULT_MAX_DETAIL_IDS,
            )

        fun fromConfig(config: ConfigSection): CampgroundSearchConfig =
            CampgroundSearchConfig(
                maxResults = config.value("max-results")?.toInt() ?: default.maxResults,
                maxDetailIds = config.value("max-detail-ids")?.toInt() ?: default.maxDetailIds,
            )
    }
}
```

- [ ] **Step 4: Load it in `AppConfig`**

In `AppConfig.kt`, add `val campgroundSearch: CampgroundSearchConfig,` to the `AppConfig` data class beside `cache`, and in the loader add among the existing `fromConfig` lines:

```kotlin
                campgroundSearch = CampgroundSearchConfig.fromConfig(roadtrip.section("campground-search")),
```

Grep `AppConfig(` under `backend/src` for any other constructor call (tests, a `default`) and add `campgroundSearch = CampgroundSearchConfig.default` there.

- [ ] **Step 5: Write the DTOs**

`CampgroundFilterDto.kt`:

```kotlin
package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `POST /api/campgrounds/search` narrows by. Every field mirrors one on
 * [CampgroundSummaryDto]. A campground with no data for a field passes it:
 * only a stated miss drops it (see the spec's "No data is not a miss").
 */
@Serializable
data class CampgroundFilterDto(
    /** A `CampsiteKind` wire value; null means any. */
    @SerialName("site_type") val siteType: String? = null,
    /** People count; passes when `max_people >= group_size` or `max_people` is unknown. */
    @SerialName("group_size") val groupSize: Int? = null,
    /** `AmenityKey` wire values that must not be marked absent. */
    val amenities: List<String> = emptyList(),
)
```

`CampgroundSearchRequestDto.kt`:

```kotlin
package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Body of `POST /api/campgrounds/search`. [boundary] is a GeoJSON Polygon or MultiPolygon. */
@Serializable
data class CampgroundSearchRequestDto(
    val boundary: JsonObject? = null,
    val filter: CampgroundFilterDto? = null,
)
```

`CampgroundSearchResponseDto.kt`:

```kotlin
package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CampgroundSearchResponseDto(
    /** POI ids of the campgrounds inside the boundary that pass the filter, nearest the boundary's centre first. */
    @SerialName("campground_ids") val campgroundIds: List<Long>,
    /** Campgrounds inside the boundary before the filter, so the head can say "9 of 17 in view". */
    @SerialName("total_in_boundary") val totalInBoundary: Int,
    /** True when more passed the filter than `max-results` allows. */
    val truncated: Boolean,
)
```

`CampgroundDetailsRequestDto.kt`:

```kotlin
package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `POST /api/campgrounds/details`: POI ids, at most `max-detail-ids`. */
@Serializable
data class CampgroundDetailsRequestDto(
    @SerialName("campground_ids") val campgroundIds: List<Long> = emptyList(),
)
```

`CampgroundDetailsResponseDto.kt`:

```kotlin
package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.Serializable

@Serializable
data class CampgroundDetailsResponseDto(
    /** One per known id, in request order; unknown ids are omitted. */
    val campgrounds: List<CampgroundSummaryDto>,
)
```

`CampgroundSummaryDto.kt`:

```kotlin
package ca.floo.roadtrip.model.api.campground

import ca.floo.roadtrip.model.api.poi.AmenityDto
import ca.floo.roadtrip.model.api.poi.RatingDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the in-view campground list renders and filters on. A slim cousin of
 * `GET /api/pois/{id}`: same field names where both carry a field, none of the
 * detail's raw payloads.
 */
@Serializable
data class CampgroundSummaryDto(
    /** POI id, the identity cards, pins and the drawer share. */
    val id: Long,
    @SerialName("campground_id") val campgroundId: Long,
    val name: String,
    val region: String? = null,
    val agency: String? = null,
    val lng: Double,
    val lat: Double,
    val rating: RatingDto? = null,
    @SerialName("availability_supported") val availabilitySupported: Boolean,
    @SerialName("booking_system") val bookingSystem: String? = null,
    val amenities: List<AmenityDto>,
    /** Live campsites per `CampsiteKind` wire value. */
    @SerialName("site_counts") val siteCounts: Map<String, Int>,
    @SerialName("site_total") val siteTotal: Int,
    /** The largest campsite `max_people`; absent when no site carries one. */
    @SerialName("max_people") val maxPeople: Int? = null,
)
```

- [ ] **Step 6: Amend the spec**

In the spec's `CampgroundFilterDto` table, delete the `checkable_only` row and add one sentence under the table: "`checkable_only` (M4's toggle) is not in M2a: `availability_supported` is decided by the booking identity resolver in the service, not by a column, so a server-side predicate for it needs its own design."

- [ ] **Step 7: Run the tests**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.config.*' :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/config/CampgroundSearchConfig.kt backend/src/main/kotlin/ca/floo/roadtrip/config/AppConfig.kt backend/src/main/kotlin/ca/floo/roadtrip/model/api/campground backend/src/test/kotlin/ca/floo/roadtrip/config/CampgroundSearchConfigTest.kt docs/superpowers/specs/2026-09-12-campground-availability-map-design.md
git commit -m "feat(api): campground search config and the search/details DTOs (#565 M2a)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Repos: the boundary search and the bulk summary read

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundSearch.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundSearchRepo.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt` (add `findSummariesByPoiIds`)
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogFixtures.kt` (`seedCampground` and `seedCatalogPoi` gain `amenitiesJson`)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CampgroundSearchRepoTest.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CampgroundRepoSummaryReadTest.kt`

**Interfaces:**
- Consumes: `campground_site_summary` and `decodeSiteCounts` (Task 1), `CampsiteRepo.refreshSiteSummaries`, fixtures `seedCatalogPoi` (returns `CatalogPoiFixture` with `poiId` and `campgroundId`), `seedCampsite`.
- Produces (all in `model/domain/CampgroundSearch.kt`, because repos must not depend on `model/api`):
  - `data class CampgroundSearchFilter(siteType: String?, groupSize: Int?, amenities: List<String>)`
  - `data class CampgroundSearchResult(poiIds: List<Long>, totalInBoundary: Int, truncated: Boolean)`
  - `data class CampgroundSummaryRow(poiId: Long, lng: Double, lat: Double, campground: Campground, summary: CampgroundSiteSummary?)`
  - `CampgroundSearchRepo(ctx: DSLContext, enabledDataProviders: Set<String>).searchWithinBoundary(boundaryGeoJson: String, filter: CampgroundSearchFilter, limit: Int): CampgroundSearchResult`
  - `CampgroundRepo.findSummariesByPoiIds(poiIds: List<Long>): List<CampgroundSummaryRow>` in request order.

- [ ] **Step 1: Extend the fixtures with amenities**

In `seedCampground`, add `amenitiesJson: String = "[]",` after `country`, add `amenities` to the INSERT column list with a `?::jsonb` placeholder after `management`, and bind `amenitiesJson` in that position. Amenities are an array of `{"key","present","detail"}` objects (see `V57__typed_campground_bags.sql`). Then read `seedCatalogPoi`'s body: if it calls `seedCampground`, add the same `amenitiesJson: String = "[]"` parameter and forward it; if it inserts the campground itself, add the column to that INSERT the same way.

- [ ] **Step 2: Write the failing search repo test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/repo/CampgroundSearchRepoTest.kt`:

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CampgroundSearchFilter
import ca.floo.roadtrip.model.domain.CampsiteKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A square around Lake Tahoe; every seed below sits inside unless the test says otherwise. */
private const val TAHOE =
    """{"type":"Polygon","coordinates":[[[-120.4,38.7],[-119.6,38.7],[-119.6,39.4],[-120.4,39.4],[-120.4,38.7]]]}"""

private val NO_FILTER = CampgroundSearchFilter(siteType = null, groupSize = null, amenities = emptyList())

class CampgroundSearchRepoTest : SharedDbTest() {
    private val campsites by lazy { CampsiteRepo(ctx) }

    private fun repo() = CampgroundSearchRepo(ctx, enabledDataProviders = setOf("recgov", "campflare"))

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun seed(
        sourceId: String,
        lon: Double,
        lat: Double,
        source: String = "recgov",
        amenitiesJson: String = "[]",
    ): CatalogPoiFixture =
        ctx.seedCatalogPoi(
            sourceId = sourceId,
            name = sourceId,
            lon = lon,
            lat = lat,
            source = source,
            amenitiesJson = amenitiesJson,
        )

    private fun site(
        campgroundId: Long,
        vendorId: String,
        kind: CampsiteKind,
        maxPeople: Int? = null,
    ) {
        ctx.seedCampsite(campgroundId = campgroundId, vendorId = vendorId, kind = kind.wire, maxPeople = maxPeople)
        campsites.refreshSiteSummaries(listOf(campgroundId))
    }

    @Test
    fun `returns the campgrounds inside the boundary nearest the centre first`() {
        val near = seed("near", -120.0, 39.05)
        val far = seed("far", -120.35, 38.75)
        seed("outside", -118.0, 37.0)

        val result = repo().searchWithinBoundary(TAHOE, NO_FILTER, limit = 10)

        assertEquals(listOf(near.poiId, far.poiId), result.poiIds)
        assertEquals(2, result.totalInBoundary)
        assertFalse(result.truncated)
    }

    @Test
    fun `a site type keeps campgrounds with that kind or with no sites at all`() {
        val tent = seed("tent", -120.0, 39.0)
        site(tent.campgroundId, "1", CampsiteKind.TENT)
        val rvOnly = seed("rv", -120.1, 39.0)
        site(rvOnly.campgroundId, "2", CampsiteKind.RV)
        val unknown = seed("unknown", -120.2, 39.0)

        val result = repo().searchWithinBoundary(TAHOE, NO_FILTER.copy(siteType = "tent"), limit = 10)

        assertEquals(setOf(tent.poiId, unknown.poiId), result.poiIds.toSet())
        assertEquals(3, result.totalInBoundary)
    }

    @Test
    fun `a group size drops only campgrounds whose known cap is below it`() {
        val big = seed("big", -120.0, 39.0)
        site(big.campgroundId, "1", CampsiteKind.TENT, maxPeople = 8)
        val small = seed("small", -120.1, 39.0)
        site(small.campgroundId, "2", CampsiteKind.TENT, maxPeople = 2)
        val unknown = seed("unknown", -120.2, 39.0)
        site(unknown.campgroundId, "3", CampsiteKind.TENT)

        val result = repo().searchWithinBoundary(TAHOE, NO_FILTER.copy(groupSize = 4), limit = 10)

        assertEquals(setOf(big.poiId, unknown.poiId), result.poiIds.toSet())
    }

    @Test
    fun `an amenity drops only campgrounds that state it is absent`() {
        val has = seed("has", -120.0, 39.0, amenitiesJson = """[{"key":"toilets","present":true}]""")
        val lacks = seed("lacks", -120.1, 39.0, amenitiesJson = """[{"key":"toilets","present":false}]""")
        val silent = seed("silent", -120.2, 39.0, amenitiesJson = """[{"key":"showers","present":true}]""")

        val result = repo().searchWithinBoundary(TAHOE, NO_FILTER.copy(amenities = listOf("toilets")), limit = 10)

        assertEquals(setOf(has.poiId, silent.poiId), result.poiIds.toSet())
        assertTrue(lacks.poiId !in result.poiIds)
    }

    @Test
    fun `the limit truncates and says so`() {
        seed("a", -120.0, 39.0)
        seed("b", -120.1, 39.0)
        seed("c", -120.2, 39.0)

        val result = repo().searchWithinBoundary(TAHOE, NO_FILTER, limit = 2)

        assertEquals(2, result.poiIds.size)
        assertEquals(3, result.totalInBoundary)
        assertTrue(result.truncated)
    }

    @Test
    fun `campgrounds from a disabled data provider are not served`() {
        seed("hidden", -120.0, 39.0, source = "reserveamerica")
        val shown = seed("shown", -120.1, 39.0)

        val result = repo().searchWithinBoundary(TAHOE, NO_FILTER, limit = 10)

        assertEquals(listOf(shown.poiId), result.poiIds)
    }
}
```

- [ ] **Step 3: Write the failing summary-read test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/repo/CampgroundRepoSummaryReadTest.kt`:

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CampsiteKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampgroundRepoSummaryReadTest : SharedDbTest() {
    private val repo by lazy { CampgroundRepo(ctx) }
    private val campsites by lazy { CampsiteRepo(ctx) }

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `reads one row per known id in request order with its summary`() {
        val a = ctx.seedCatalogPoi(sourceId = "a", name = "Alpha", lon = -120.0, lat = 39.0, agency = "USFS")
        val b = ctx.seedCatalogPoi(sourceId = "b", name = "Beta", lon = -120.1, lat = 39.1)
        ctx.seedCampsite(campgroundId = a.campgroundId, vendorId = "1", kind = CampsiteKind.TENT.wire, maxPeople = 6)
        campsites.refreshSiteSummaries(listOf(a.campgroundId))

        val rows = repo.findSummariesByPoiIds(listOf(b.poiId, a.poiId, 999_999L))

        assertEquals(listOf(b.poiId, a.poiId), rows.map { it.poiId })
        val alpha = rows.last()
        assertEquals("Alpha", alpha.campground.name)
        assertEquals("USFS", alpha.campground.management?.agency)
        assertEquals(-120.0, alpha.lng, 1e-6)
        assertEquals(39.0, alpha.lat, 1e-6)
        assertEquals(1, alpha.summary?.siteTotal)
        assertEquals(mapOf("tent" to 1), alpha.summary?.siteCounts)
        assertEquals(6, alpha.summary?.maxPeople)
        assertNull(rows.first().summary)
    }

    @Test
    fun `an empty request reads nothing`() {
        assertEquals(emptyList(), repo.findSummariesByPoiIds(emptyList()))
    }
}
```

- [ ] **Step 4: Run both to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.CampgroundSearchRepoTest' --tests 'ca.floo.roadtrip.repo.CampgroundRepoSummaryReadTest' --offline -q`
Expected: compilation failure on the new types.

- [ ] **Step 5: Add the domain types**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundSearch.kt`:

```kotlin
package ca.floo.roadtrip.model.domain

/** The search predicate as the repo applies it; mirrors the API's `CampgroundFilterDto`. */
data class CampgroundSearchFilter(
    val siteType: String?,
    val groupSize: Int?,
    val amenities: List<String>,
)

data class CampgroundSearchResult(
    /** POI ids, nearest the boundary's centre first. */
    val poiIds: List<Long>,
    /** Campgrounds inside the boundary before the filter. */
    val totalInBoundary: Int,
    val truncated: Boolean,
)

/** One campground as the bulk summary read returns it. */
data class CampgroundSummaryRow(
    val poiId: Long,
    val lng: Double,
    val lat: Double,
    val campground: Campground,
    val summary: CampgroundSiteSummary?,
)
```

- [ ] **Step 6: Write `CampgroundSearchRepo`**

Create `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundSearchRepo.kt`. Bind order matters: the boundary comes first, then the predicate binds (they sit in the `SELECT` list of `inside`), then the provider binds (in its `WHERE`), then the limit.

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CampgroundSearchFilter
import ca.floo.roadtrip.model.domain.CampgroundSearchResult
import org.jooq.DSLContext

/**
 * Campground POIs inside a boundary that pass a filter. One row per campground
 * thanks to `campground_site_summary`; a campground with no summary row has no
 * live sites and passes the site filters as "no data".
 */
internal class CampgroundSearchRepo(
    private val ctx: DSLContext,
    private val enabledDataProviders: Set<String>,
) {
    fun searchWithinBoundary(
        boundaryGeoJson: String,
        filter: CampgroundSearchFilter,
        limit: Int,
    ): CampgroundSearchResult {
        if (enabledDataProviders.isEmpty()) {
            return CampgroundSearchResult(emptyList(), totalInBoundary = 0, truncated = false)
        }

        val predicates = mutableListOf<String>()
        val predicateArgs = mutableListOf<Any>()
        filter.siteType?.let {
            predicates += "(s.site_total IS NULL OR COALESCE((s.site_counts->>?)::int, 0) > 0)"
            predicateArgs += it
        }
        filter.groupSize?.let {
            predicates += "(s.max_people IS NULL OR s.max_people >= ?)"
            predicateArgs += it
        }
        for (key in filter.amenities) {
            predicates +=
                """NOT EXISTS (
                     SELECT 1 FROM jsonb_array_elements(cg.amenities) a
                     WHERE a->>'key' = ? AND COALESCE((a->>'present')::boolean, true) = false
                   )"""
            predicateArgs += key
        }
        val passes = if (predicates.isEmpty()) "true" else predicates.joinToString(" AND ")
        val providerPlaceholders = enabledDataProviders.joinToString(",") { "?" }

        val sql =
            """
            WITH boundary AS (
              SELECT ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) AS poly
            ),
            inside AS (
              SELECT p.id AS poi_id,
                     ST_Distance(ST_Centroid(p.geom), ST_Centroid(boundary.poly)) AS dist,
                     ($passes) AS passes
              FROM pois p
              JOIN poi_campgrounds pc ON pc.poi_id = p.id
              JOIN campgrounds cg ON cg.id = pc.campground_id AND cg.deleted_at IS NULL
              LEFT JOIN campground_site_summary s ON s.campground_id = cg.id,
              boundary
              WHERE p.deleted_at IS NULL
                AND p.poi_type = 'campground'
                AND cg.data_provider IN ($providerPlaceholders)
                AND ST_Within(ST_Centroid(p.geom), boundary.poly)
            )
            SELECT poi_id,
                   passes,
                   COUNT(*) OVER () AS total_in_boundary,
                   COUNT(*) FILTER (WHERE passes) OVER () AS total_passing
            FROM inside
            ORDER BY passes DESC, dist ASC, poi_id ASC
            LIMIT ?
            """.trimIndent()

        val args = mutableListOf<Any>(boundaryGeoJson)
        args.addAll(predicateArgs)
        args.addAll(enabledDataProviders)
        args += limit

        val rows = ctx.fetch(sql, *args.toTypedArray())
        val passing = rows.filter { it.get("passes", Boolean::class.java) }
        val totalInBoundary = rows.firstOrNull()?.let { (it.get("total_in_boundary") as Number).toInt() } ?: 0
        val totalPassing = rows.firstOrNull()?.let { (it.get("total_passing") as Number).toInt() } ?: 0
        return CampgroundSearchResult(
            poiIds = passing.map { (it.get("poi_id") as Number).toLong() },
            totalInBoundary = totalInBoundary,
            truncated = totalPassing > passing.size,
        )
    }
}
```

The window counts run over `inside` before `LIMIT`, so both totals are exact when the page is cut. `ORDER BY passes DESC` puts the passing rows first so the limit never spends a slot on a non-passing row while passing rows remain. One caveat: when nothing is inside the boundary the row set is empty, so both totals fall back to 0.

- [ ] **Step 7: Add `findSummariesByPoiIds` to `CampgroundRepo`**

Inside `CampgroundRepo`, after `findPoiDetailByPoi`, add (imports: `CampgroundSiteSummary`, `CampgroundSummaryRow` from `model/domain`):

```kotlin
    /** Bulk read for the in-view list, in the order the ids were asked for; unknown ids are skipped. */
    fun findSummariesByPoiIds(poiIds: List<Long>): List<CampgroundSummaryRow> {
        val wanted = poiIds.distinct()
        if (wanted.isEmpty()) return emptyList()
        val placeholders = wanted.joinToString(", ") { "?" }
        val rows =
            ctx.fetch(
                """
                SELECT
                  $baseSelectColumns,
                  p.id AS poi_id,
                  ST_X(ST_PointOnSurface(p.geom)) AS lng,
                  ST_Y(ST_PointOnSurface(p.geom)) AS lat,
                  s.site_total,
                  s.site_counts::text AS site_counts_text,
                  s.max_people
                FROM poi_campgrounds pc
                JOIN pois p ON p.id = pc.poi_id
                JOIN campgrounds cg ON cg.id = pc.campground_id
                LEFT JOIN campground_site_summary s ON s.campground_id = cg.id
                WHERE pc.poi_id IN ($placeholders)
                  AND cg.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                """.trimIndent(),
                *wanted.toTypedArray(),
            )
        val byPoi =
            rows.associateBy({ it.get("poi_id", Long::class.java) }) { record ->
                val total = record.get("site_total", Int::class.javaObjectType)
                CampgroundSummaryRow(
                    poiId = record.get("poi_id", Long::class.java),
                    lng = record.get("lng", Double::class.java),
                    lat = record.get("lat", Double::class.java),
                    campground = fromRecord(record),
                    summary =
                        total?.let {
                            CampgroundSiteSummary(
                                siteTotal = it,
                                siteCounts = decodeSiteCounts(record.get("site_counts_text", String::class.java)),
                                maxPeople = record.get("max_people", Int::class.javaObjectType),
                            )
                        },
                )
            }
        return wanted.mapNotNull(byPoi::get)
    }
```

`baseSelectColumns` exists in the file (used by `findPoiDetailByPoi`) and aliases the campground table `cg`; if its alias differs, match it.

- [ ] **Step 8: Run the tests**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.CampgroundSearchRepoTest' --tests 'ca.floo.roadtrip.repo.CampgroundRepoSummaryReadTest' --tests 'ca.floo.roadtrip.repo.PoiServingRepoTest' --tests 'ca.floo.roadtrip.LayeringGuardTest' :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundSearch.kt backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundSearchRepo.kt backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogFixtures.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/CampgroundSearchRepoTest.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/CampgroundRepoSummaryReadTest.kt
git commit -m "feat(repo): boundary search over campgrounds and a bulk summary read (#565 M2a)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: `CampgroundSearchService`

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/CampgroundSearchService.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/poi/CampgroundSearchServiceTest.kt`

**Interfaces:**
- Consumes: `CampgroundSearchRepo`, `CampgroundRepo.findSummariesByPoiIds`, `CampgroundSearchConfig`, `BookingHorizonResolver.servingProvider(campground)`, `BookingIdentityResolver.forCampground(campground, provider)`, `CampgroundCta.bookingSystem(bookingRef)`, `AmenityDto.fromAll`, `RatingDto.from`, the DTOs from Task 2. Test fixtures `testBookingHorizons(ctx, providers)` and `shippedTenantRegistry()` in `ca.floo.roadtrip.fixtures` (the ones `fixtures/CampgroundServiceFixture.kt` uses; copy its imports).
- Produces:
  - `class CampgroundSearchRequestException(val code: String, message: String) : IllegalArgumentException(message)`; codes `bad_boundary`, `too_many_ids`, `bad_request`.
  - `CampgroundSearchService.search(request: CampgroundSearchRequestDto): CampgroundSearchResponseDto`
  - `CampgroundSearchService.details(request: CampgroundDetailsRequestDto): CampgroundDetailsResponseDto`

- [ ] **Step 1: Write the failing service test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/service/poi/CampgroundSearchServiceTest.kt`:

```kotlin
package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.config.CampgroundSearchConfig
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.fixtures.testBookingHorizons
import ca.floo.roadtrip.model.api.campground.CampgroundDetailsRequestDto
import ca.floo.roadtrip.model.api.campground.CampgroundFilterDto
import ca.floo.roadtrip.model.api.campground.CampgroundSearchRequestDto
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampgroundSearchRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.BookingIdentityResolver
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TAHOE =
    """{"type":"Polygon","coordinates":[[[-120.4,38.7],[-119.6,38.7],[-119.6,39.4],[-120.4,39.4],[-120.4,38.7]]]}"""

class CampgroundSearchServiceTest : SharedDbTest() {
    private fun service(config: CampgroundSearchConfig = CampgroundSearchConfig.default) =
        CampgroundSearchService(
            searchRepo = CampgroundSearchRepo(ctx, enabledDataProviders = setOf("recgov", "campflare")),
            campgroundRepo = CampgroundRepo(ctx),
            bookingHorizons = testBookingHorizons(ctx, emptyList()),
            identities = BookingIdentityResolver(shippedTenantRegistry()),
            cta = CampgroundCta(shippedTenantRegistry()),
            config = config,
        )

    private fun boundary(json: String) = Json.parseToJsonElement(json).jsonObject

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `search returns ids inside the boundary that pass the filter`() {
        val tent = ctx.seedCatalogPoi(sourceId = "t", name = "Tent Flat", lon = -120.0, lat = 39.0)
        ctx.seedCampsite(campgroundId = tent.campgroundId, vendorId = "1", kind = CampsiteKind.TENT.wire)
        val rv = ctx.seedCatalogPoi(sourceId = "r", name = "RV Park", lon = -120.1, lat = 39.0)
        ctx.seedCampsite(campgroundId = rv.campgroundId, vendorId = "2", kind = CampsiteKind.RV.wire)
        CampsiteRepo(ctx).refreshSiteSummaries(listOf(tent.campgroundId, rv.campgroundId))

        val response =
            service().search(
                CampgroundSearchRequestDto(boundary = boundary(TAHOE), filter = CampgroundFilterDto(siteType = "tent")),
            )

        assertEquals(listOf(tent.poiId), response.campgroundIds)
        assertEquals(2, response.totalInBoundary)
        assertFalse(response.truncated)
    }

    @Test
    fun `a missing or non-polygon boundary is refused`() {
        val missing = assertFailsWith<CampgroundSearchRequestException> { service().search(CampgroundSearchRequestDto()) }
        assertEquals("bad_boundary", missing.code)

        val point =
            assertFailsWith<CampgroundSearchRequestException> {
                service().search(CampgroundSearchRequestDto(boundary = boundary("""{"type":"Point","coordinates":[0,0]}""")))
            }
        assertEquals("bad_boundary", point.code)
    }

    @Test
    fun `an unknown site type is refused`() {
        val error =
            assertFailsWith<CampgroundSearchRequestException> {
                service().search(
                    CampgroundSearchRequestDto(boundary = boundary(TAHOE), filter = CampgroundFilterDto(siteType = "yurt")),
                )
            }
        assertEquals("bad_request", error.code)
    }

    @Test
    fun `details maps the summary row onto the DTO`() {
        val poi =
            ctx.seedCatalogPoi(
                sourceId = "nb",
                name = "Nevada Beach",
                lon = -119.943,
                lat = 38.976,
                agency = "USDA Forest Service",
                region = "NV",
                amenitiesJson = """[{"key":"toilets","present":true},{"key":"showers","present":false}]""",
            )
        ctx.seedCampsite(campgroundId = poi.campgroundId, vendorId = "1", kind = CampsiteKind.TENT.wire, maxPeople = 8)
        CampsiteRepo(ctx).refreshSiteSummaries(listOf(poi.campgroundId))

        val response = service().details(CampgroundDetailsRequestDto(campgroundIds = listOf(poi.poiId)))

        val summary = response.campgrounds.single()
        assertEquals(poi.poiId, summary.id)
        assertEquals(poi.campgroundId, summary.campgroundId)
        assertEquals("Nevada Beach", summary.name)
        assertEquals("NV", summary.region)
        assertEquals("USDA Forest Service", summary.agency)
        assertEquals(mapOf("tent" to 1), summary.siteCounts)
        assertEquals(1, summary.siteTotal)
        assertEquals(8, summary.maxPeople)
        assertEquals(listOf("toilets", "showers"), summary.amenities.map { it.key })
        assertFalse(summary.availabilitySupported)
    }

    @Test
    fun `details without sites reports zero and no people count`() {
        val poi = ctx.seedCatalogPoi(sourceId = "e", name = "Empty", lon = -120.0, lat = 39.0)

        val summary = service().details(CampgroundDetailsRequestDto(campgroundIds = listOf(poi.poiId))).campgrounds.single()

        assertEquals(0, summary.siteTotal)
        assertTrue(summary.siteCounts.isEmpty())
        assertNull(summary.maxPeople)
    }

    @Test
    fun `details refuses more ids than the cap`() {
        val error =
            assertFailsWith<CampgroundSearchRequestException> {
                service(CampgroundSearchConfig(maxResults = 10, maxDetailIds = 2))
                    .details(CampgroundDetailsRequestDto(campgroundIds = listOf(1, 2, 3)))
            }
        assertEquals("too_many_ids", error.code)
    }

    @Test
    fun `details with no ids is an empty answer, not an error`() {
        assertTrue(service().details(CampgroundDetailsRequestDto()).campgrounds.isEmpty())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.poi.CampgroundSearchServiceTest' --offline -q`
Expected: compilation failure, `CampgroundSearchService` unresolved.

- [ ] **Step 3: Write the service**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/CampgroundSearchService.kt`:

```kotlin
package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.config.CampgroundSearchConfig
import ca.floo.roadtrip.model.api.campground.CampgroundDetailsRequestDto
import ca.floo.roadtrip.model.api.campground.CampgroundDetailsResponseDto
import ca.floo.roadtrip.model.api.campground.CampgroundFilterDto
import ca.floo.roadtrip.model.api.campground.CampgroundSearchRequestDto
import ca.floo.roadtrip.model.api.campground.CampgroundSearchResponseDto
import ca.floo.roadtrip.model.api.campground.CampgroundSummaryDto
import ca.floo.roadtrip.model.api.poi.AmenityDto
import ca.floo.roadtrip.model.api.poi.RatingDto
import ca.floo.roadtrip.model.domain.AmenityKey
import ca.floo.roadtrip.model.domain.CampgroundSearchFilter
import ca.floo.roadtrip.model.domain.CampgroundSummaryRow
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampgroundSearchRepo
import ca.floo.roadtrip.service.availability.BookingHorizonResolver
import ca.floo.roadtrip.service.availability.BookingIdentityResolver
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A request the caller can fix; [code] is the wire error code. */
class CampgroundSearchRequestException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

private val BOUNDARY_TYPES = setOf("Polygon", "MultiPolygon")
private const val MIN_GROUP_SIZE = 1

internal class CampgroundSearchService(
    private val searchRepo: CampgroundSearchRepo,
    private val campgroundRepo: CampgroundRepo,
    private val bookingHorizons: BookingHorizonResolver,
    private val identities: BookingIdentityResolver,
    private val cta: CampgroundCta,
    private val config: CampgroundSearchConfig,
) {
    fun search(request: CampgroundSearchRequestDto): CampgroundSearchResponseDto {
        val boundary = validatedBoundary(request.boundary)
        val filter = validatedFilter(request.filter ?: CampgroundFilterDto())
        val result = searchRepo.searchWithinBoundary(boundary.toString(), filter, limit = config.maxResults)
        return CampgroundSearchResponseDto(
            campgroundIds = result.poiIds,
            totalInBoundary = result.totalInBoundary,
            truncated = result.truncated,
        )
    }

    fun details(request: CampgroundDetailsRequestDto): CampgroundDetailsResponseDto {
        val ids = request.campgroundIds.distinct()
        if (ids.size > config.maxDetailIds) {
            throw CampgroundSearchRequestException(
                "too_many_ids",
                "at most ${config.maxDetailIds} campground_ids per request",
            )
        }
        if (ids.isEmpty()) return CampgroundDetailsResponseDto(campgrounds = emptyList())
        return CampgroundDetailsResponseDto(campgrounds = campgroundRepo.findSummariesByPoiIds(ids).map(::summaryOf))
    }

    private fun validatedBoundary(boundary: JsonObject?): JsonObject {
        boundary ?: throw CampgroundSearchRequestException("bad_boundary", "boundary is required")
        val type = boundary["type"]?.jsonPrimitive?.content
        if (type !in BOUNDARY_TYPES) {
            throw CampgroundSearchRequestException("bad_boundary", "boundary must be a GeoJSON Polygon or MultiPolygon")
        }
        if (boundary["coordinates"] == null) {
            throw CampgroundSearchRequestException("bad_boundary", "boundary has no coordinates")
        }
        return boundary
    }

    private fun validatedFilter(filter: CampgroundFilterDto): CampgroundSearchFilter {
        val siteType =
            filter.siteType?.let { wire ->
                CampsiteKind.entries.firstOrNull { it.wire == wire }?.wire
                    ?: throw CampgroundSearchRequestException("bad_request", "unknown site_type '$wire'")
            }
        filter.groupSize?.let {
            if (it < MIN_GROUP_SIZE) {
                throw CampgroundSearchRequestException("bad_request", "group_size must be >= $MIN_GROUP_SIZE")
            }
        }
        val amenities =
            filter.amenities.map { wire ->
                AmenityKey.entries.firstOrNull { it.wire == wire }?.wire
                    ?: throw CampgroundSearchRequestException("bad_request", "unknown amenity '$wire'")
            }
        return CampgroundSearchFilter(siteType = siteType, groupSize = filter.groupSize, amenities = amenities)
    }

    /** The same decision `CampgroundService` makes for `availability_supported` and `booking_system`. */
    private fun summaryOf(row: CampgroundSummaryRow): CampgroundSummaryDto {
        val campground = row.campground
        val bookingRef = identities.forCampground(campground, bookingHorizons.servingProvider(campground))
        return CampgroundSummaryDto(
            id = row.poiId,
            campgroundId = campground.id,
            name = campground.name,
            region = campground.location?.region,
            agency = campground.management?.agency,
            lng = row.lng,
            lat = row.lat,
            rating = campground.metadata?.rating?.let(RatingDto::from),
            availabilitySupported = bookingRef != null,
            bookingSystem = cta.bookingSystem(bookingRef),
            amenities = AmenityDto.fromAll(campground.amenities),
            siteCounts = row.summary?.siteCounts ?: emptyMap(),
            siteTotal = row.summary?.siteTotal ?: 0,
            maxPeople = row.summary?.maxPeople,
        )
    }
}
```

Check the exact parameter list of `BookingIdentityResolver.forCampground` and the signature of `CampgroundCta.bookingSystem` in their files; `CampgroundService.kt` lines 78 to 90 call both the same way, so mirror that call. If `CampsiteKind.entries` is unavailable at the project's Kotlin level, use `CampsiteKind.values()`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.poi.CampgroundSearchServiceTest' --tests 'ca.floo.roadtrip.LayeringGuardTest' :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/poi/CampgroundSearchService.kt backend/src/test/kotlin/ca/floo/roadtrip/service/poi/CampgroundSearchServiceTest.kt
git commit -m "feat(service): campground search validates the boundary and maps summaries (#565 M2a)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Routes, contract rows, wiring, generated types

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/campgrounds/CampgroundRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt` (two rows after the `/api/pois/{id}/campsites/availability` row)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt`, `ServiceModule.kt`, `RouteModule.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/api/campgrounds/CampgroundRoutesTest.kt`
- Generated: `frontend/src/api/generated/api-types.ts`

**Interfaces:**
- Consumes: `CampgroundSearchService` and `CampgroundSearchRequestException` (Task 4), `CampgroundSearchConfig` (Task 2), `receiveJsonBody`, `RouteBodyResult`, `respondApiError`, `respondEncodedJson`, `describeApi`, `access(RouteAccess.Anonymous)` from `route/common`; `routeTestApplication` from `ca.floo.roadtrip.route` in the test tree.
- Produces: `internal fun Route.campgroundRoutes(service: CampgroundSearchService, config: CampgroundSearchConfig)`; two `ApiEndpoint` rows; TypeScript interfaces `CampgroundSearchRequestDto`, `CampgroundSearchResponseDto`, `CampgroundDetailsRequestDto`, `CampgroundDetailsResponseDto`, `CampgroundSummaryDto`, `CampgroundFilterDto` for M2b.

- [ ] **Step 1: Write the failing route test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/route/api/campgrounds/CampgroundRoutesTest.kt`:

```kotlin
package ca.floo.roadtrip.route.api.campgrounds

import ca.floo.roadtrip.config.CampgroundSearchConfig
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.fixtures.testBookingHorizons
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampgroundSearchRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.route.routeTestApplication
import ca.floo.roadtrip.service.availability.BookingIdentityResolver
import ca.floo.roadtrip.service.poi.CampgroundSearchService
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private const val TAHOE =
    """{"type":"Polygon","coordinates":[[[-120.4,38.7],[-119.6,38.7],[-119.6,39.4],[-120.4,39.4],[-120.4,38.7]]]}"""

class CampgroundRoutesTest : SharedDbTest() {
    private fun service(config: CampgroundSearchConfig = CampgroundSearchConfig.default) =
        CampgroundSearchService(
            searchRepo = CampgroundSearchRepo(ctx, enabledDataProviders = setOf("recgov", "campflare")),
            campgroundRepo = CampgroundRepo(ctx),
            bookingHorizons = testBookingHorizons(ctx, emptyList()),
            identities = BookingIdentityResolver(shippedTenantRegistry()),
            cta = CampgroundCta(shippedTenantRegistry()),
            config = config,
        )

    private fun error(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `search answers ids, the boundary total and the truncation flag`() =
        testApplication {
            val tent = ctx.seedCatalogPoi(sourceId = "t", name = "Tent Flat", lon = -120.0, lat = 39.0)
            ctx.seedCampsite(campgroundId = tent.campgroundId, vendorId = "1", kind = CampsiteKind.TENT.wire)
            CampsiteRepo(ctx).refreshSiteSummaries(listOf(tent.campgroundId))
            application { routeTestApplication { campgroundRoutes(service(), CampgroundSearchConfig.default) } }

            val resp =
                client.post("/api/campgrounds/search") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"boundary":$TAHOE,"filter":{"site_type":"tent"}}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(listOf(tent.poiId), body["campground_ids"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() })
            assertEquals(1, body["total_in_boundary"]!!.jsonPrimitive.content.toInt())
            assertEquals("false", body["truncated"]!!.jsonPrimitive.content)
        }

    @Test
    fun `search without a boundary is a 400 naming the boundary`() =
        testApplication {
            application { routeTestApplication { campgroundRoutes(service(), CampgroundSearchConfig.default) } }

            val resp =
                client.post("/api/campgrounds/search") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"filter":{}}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("bad_boundary", error(resp.bodyAsText()))
        }

    @Test
    fun `search with an unparseable body is a 400`() =
        testApplication {
            application { routeTestApplication { campgroundRoutes(service(), CampgroundSearchConfig.default) } }

            val resp =
                client.post("/api/campgrounds/search") {
                    contentType(ContentType.Application.Json)
                    setBody("not json")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("bad_request", error(resp.bodyAsText()))
        }

    @Test
    fun `details answers one summary per known id`() =
        testApplication {
            val poi = ctx.seedCatalogPoi(sourceId = "nb", name = "Nevada Beach", lon = -119.943, lat = 38.976, agency = "USFS")
            ctx.seedCampsite(campgroundId = poi.campgroundId, vendorId = "1", kind = CampsiteKind.TENT.wire, maxPeople = 6)
            CampsiteRepo(ctx).refreshSiteSummaries(listOf(poi.campgroundId))
            application { routeTestApplication { campgroundRoutes(service(), CampgroundSearchConfig.default) } }

            val resp =
                client.post("/api/campgrounds/details") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"campground_ids":[${poi.poiId},999999]}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val campgrounds = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["campgrounds"]!!.jsonArray
            assertEquals(1, campgrounds.size)
            val summary = campgrounds.single().jsonObject
            assertEquals("Nevada Beach", summary["name"]!!.jsonPrimitive.content)
            assertEquals("USFS", summary["agency"]!!.jsonPrimitive.content)
            assertEquals(6, summary["max_people"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, summary["site_counts"]!!.jsonObject["tent"]!!.jsonPrimitive.content.toInt())
        }

    @Test
    fun `details over the cap is a 400 too_many_ids`() =
        testApplication {
            val config = CampgroundSearchConfig(maxResults = 10, maxDetailIds = 1)
            application { routeTestApplication { campgroundRoutes(service(config), config) } }

            val resp =
                client.post("/api/campgrounds/details") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"campground_ids":[1,2]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("too_many_ids", error(resp.bodyAsText()))
        }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.campgrounds.CampgroundRoutesTest' --offline -q`
Expected: compilation failure, `campgroundRoutes` unresolved.

- [ ] **Step 3: Write the routes**

Create `backend/src/main/kotlin/ca/floo/roadtrip/route/api/campgrounds/CampgroundRoutes.kt`:

```kotlin
package ca.floo.roadtrip.route.api.campgrounds

import ca.floo.roadtrip.config.CampgroundSearchConfig
import ca.floo.roadtrip.model.api.campground.CampgroundDetailsRequestDto
import ca.floo.roadtrip.model.api.campground.CampgroundSearchRequestDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.receiveJsonBody
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.service.poi.CampgroundSearchRequestException
import ca.floo.roadtrip.service.poi.CampgroundSearchService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val BAD_REQUEST_ERROR = "bad_request"

// POST /api/campgrounds/search and POST /api/campgrounds/details.
//
// The campground list's pair: which campground POIs inside a boundary pass a
// filter, then bulk summaries for the ids the list will render. POST /api/pois
// stays the pin layer for every category.
internal fun Route.campgroundRoutes(
    service: CampgroundSearchService,
    config: CampgroundSearchConfig,
) {
    route("/api") {
        route("/campgrounds") {
            post("/search") {
                val request =
                    when (val body = call.receiveJsonBody<CampgroundSearchRequestDto>()) {
                        is RouteBodyResult.Invalid -> return@post call.respondBadRequest(body.detail)
                        is RouteBodyResult.Valid -> body.value
                    }
                val response =
                    try {
                        service.search(request)
                    } catch (e: CampgroundSearchRequestException) {
                        return@post call.respondApiError(e.code, HttpStatusCode.BadRequest, e.message)
                    }
                call.respondEncodedJson(response)
            }.describeApi(
                tag = "campground",
                summary = "Campground POI ids inside a GeoJSON boundary that pass a filter",
                description =
                    "Body: { boundary: GeoJSON Polygon|MultiPolygon, filter?: { site_type?, group_size?, amenities? } }. " +
                        "Ids are pois.id, nearest the boundary's centre first, at most ${config.maxResults} " +
                        "(truncated:true past that). A campground with no data for a filter field passes it.",
            ).access(RouteAccess.Anonymous)

            post("/details") {
                val request =
                    when (val body = call.receiveJsonBody<CampgroundDetailsRequestDto>()) {
                        is RouteBodyResult.Invalid -> return@post call.respondBadRequest(body.detail)
                        is RouteBodyResult.Valid -> body.value
                    }
                val response =
                    try {
                        service.details(request)
                    } catch (e: CampgroundSearchRequestException) {
                        return@post call.respondApiError(e.code, HttpStatusCode.BadRequest, e.message)
                    }
                call.respondEncodedJson(response)
            }.describeApi(
                tag = "campground",
                summary = "Bulk campground summaries by POI id",
                description =
                    "Body: { campground_ids: [pois.id, ...1..${config.maxDetailIds}] }. One summary per known id in " +
                        "request order; unknown ids are omitted. Carries the fields the in-view list and its filters read.",
            ).access(RouteAccess.Anonymous)
        }
    }
}

private suspend fun ApplicationCall.respondBadRequest(detail: String?) =
    respondApiError(BAD_REQUEST_ERROR, HttpStatusCode.BadRequest, detail ?: "parse failed")
```

`receiveJsonBody` returns a `RouteBodyResult`; `PoiRoutes.kt` chains `.mapCatching(::parseRequest)` because its validation is in the route. Here validation lives in the service, so no `mapCatching` step. If `RouteBodyResult.Invalid.detail` has a different name, read `route/common/RouteBody.kt` and match it.

- [ ] **Step 4: Add the contract rows**

In `ApiContract.kt`, import `ca.floo.roadtrip.model.api.campground.CampgroundDetailsRequestDto`, `CampgroundDetailsResponseDto`, `CampgroundSearchRequestDto`, `CampgroundSearchResponseDto`, and add after the `/api/pois/{id}/campsites/availability` row:

```kotlin
            ApiEndpoint(
                ApiMethod.POST,
                "/api/campgrounds/search",
                CampgroundSearchRequestDto::class,
                ApiBody(HTTP_OK, CampgroundSearchResponseDto::class),
                // CampgroundRoutes.kt: an unparseable body, a missing or non-polygon boundary,
                // or an unknown site_type / amenity.
                apiErrors(HTTP_BAD_REQUEST),
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/campgrounds/details",
                CampgroundDetailsRequestDto::class,
                ApiBody(HTTP_OK, CampgroundDetailsResponseDto::class),
                // CampgroundRoutes.kt: an unparseable body or more ids than max-detail-ids.
                apiErrors(HTTP_BAD_REQUEST),
            ),
```

- [ ] **Step 5: Wire DI**

`RepoModule.kt`: beside the `PoiServingRepo` binding, add a `CampgroundSearchRepo` binding that passes the same `enabledDataProviders` expression `PoiServingRepo` receives (copy it from that line):

```kotlin
        single { CampgroundSearchRepo(get(), /* same enabledDataProviders expression as PoiServingRepo */) }
```

`ServiceModule.kt`: after the `PoiService` binding:

```kotlin
        single {
            CampgroundSearchService(
                searchRepo = get<CampgroundSearchRepo>(),
                campgroundRepo = get<CampgroundRepo>(),
                bookingHorizons = get<BookingHorizonResolver>(),
                identities = get<BookingIdentityResolver>(),
                cta = get<CampgroundCta>(),
                config = get<AppConfig>().campgroundSearch,
            )
        }
```

If `AppConfig` is not a Koin single, look at how another config-bearing service in `ServiceModule.kt` receives its section (grep `config.` in that file) and use the same idiom; the requirement is one config source.

`RouteModule.kt`: after `poiRoutes(poiService)`, using the file's injection idiom (`by inject()` at the top of the function or `get()` inside `routing`):

```kotlin
        campgroundRoutes(campgroundSearchService, config.campgroundSearch)
```

- [ ] **Step 6: Run the route tests, the boot guard and the layering guard**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.campgrounds.CampgroundRoutesTest' --tests 'ca.floo.roadtrip.model.api.*' --tests 'ca.floo.roadtrip.di.*' --tests 'ca.floo.roadtrip.LayeringGuardTest' --tests 'ca.floo.roadtrip.OpenApiSmokeTest' :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 7: Generate the TypeScript types**

Run from the repo root: `make api-types`
Then `git diff --stat frontend/src/api/generated/api-types.ts` shows the six new interfaces. Run `npm run typecheck` from `frontend/` to prove the generated file still compiles with the app.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/route/api/campgrounds/CampgroundRoutes.kt backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt backend/src/test/kotlin/ca/floo/roadtrip/route/api/campgrounds/CampgroundRoutesTest.kt frontend/src/api/generated/api-types.ts
git commit -m "feat(api): POST /api/campgrounds/search and /details for the in-view list (#565 M2a)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Full gate

**Files:** none (verification only).

- [ ] **Step 1: Full backend gate**

Run from the repo root: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt :backend:checkApiTypes :backend:contractLedgerCheck --offline -q`
Expected: PASS. The ledger check must not list `/api/campgrounds/search 200` or `/api/campgrounds/details 200` among the unproduced bodies.

- [ ] **Step 2: Frontend still green with the generated types**

Run from `frontend/`: `npm run typecheck && npm run lint`
Expected: PASS.

- [ ] **Step 3: Smoke the running backend**

With Docker up, start the backend against the local database the way the repo's `Makefile` or `docs/backend-architecture.md` describes (the M1 ledger used a host gradle run on port 8765), then:

```bash
curl -s -X POST localhost:8765/api/campgrounds/search -H 'content-type: application/json' -d '{"boundary":{"type":"Polygon","coordinates":[[[-120.4,38.7],[-119.6,38.7],[-119.6,39.4],[-120.4,39.4],[-120.4,38.7]]]},"filter":{"site_type":"tent","group_size":4}}'
```

Expected: `{"campground_ids":[...],"total_in_boundary":N,"truncated":false}` against the local catalog. Then feed the first five ids to `/api/campgrounds/details` and confirm `site_counts`, `max_people` and `amenities` are populated for a rec.gov campground. Record the observed numbers in the progress ledger.

---

## Self-review notes

- Spec coverage: search (Tasks 3, 5), details (Tasks 3, 4, 5), POI-id identity (Task 3 joins `poi_campgrounds`), filter mirrors the DTO (Task 2), "no data is not a miss" (Task 3 predicates and tests), ETL-maintained aggregates plus backfill (Task 1), caps and errors (Tasks 2, 4, 5), contract rows and generated types (Task 5), layering (services take repos; SQL only in `repo/`). `checkable_only` is deferred to M4 and the spec is amended in Task 2.
- Type consistency: `CampgroundSearchFilter`, `CampgroundSearchResult`, `CampgroundSummaryRow` (Task 3) and `CampgroundSiteSummary` (Task 1) are defined once and used by name in Tasks 4 and 5; `decodeSiteCounts` is `internal` at file scope in `CampsiteRepo.kt` and reused in `CampgroundRepo`.
- Known simplification: the search does not collapse cross-vendor duplicates by booking ref the way `PoiServingRepo` does; the enabled-provider filter removes most of them. Revisit if the list shows doubles the pin layer does not.
