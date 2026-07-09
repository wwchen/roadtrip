# Per-Vendor Catalog Rows and Match Tables Implementation Plan (Part 2 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop merging campgrounds/campsites across vendors at write time; give every ETL source its own rows (`data_source`), record cross-vendor identity in `campground_matches`/`campsite_matches` with heuristic receipts, and serve the richest record per match group through materialized views refreshed as a terminal ETL stage.

**Architecture:** Catalog tables become the raw per-vendor layer. A `CatalogMatcherService` (deterministic shared-vendor-ref pass, then geo+name heuristic pass) writes pairwise match rows. Materialized views `campground_canonical`/`campsite_canonical` pick a row-level winner per match group and expose sibling ids/sources. POIs, watches, and availability key on the representative (winning) row; a re-pointing step migrates them transactionally when the winner changes. Merge happens at `REFRESH ... CONCURRENTLY` time inside the ETL pipeline — serving queries are plain selects.

**Tech Stack:** Kotlin, Ktor, jOOQ (raw SQL in repos), PostgreSQL/PostGIS, Flyway (`V39`), kotlinx.serialization, existing ETL orchestrator/registry.

## Global Constraints

- This is **part 2 of 3**: it lands AFTER part 1 (`2026-07-09-part-1-campsite-rename-cleanup.md`) and BEFORE part 3 (`2026-07-09-part-3-provider-seams.md`). Use post-rename names: domain type `Campsite` (was `Reservable`), `CatalogCampsiteRef`, `CampsiteParentJoiner`.
- Build green per task; one commit per task. `./gradlew build` from repo root runs Flyway → jOOQ codegen → compile → tests.
- Graphite local-only: `gt track` / `gt restack` only, never `gt sync`.
- Git commits: multiple `-m` flags for multiline, never heredocs.
- No inline magic constants: matcher thresholds are named consts with env overrides.
- Migration is **non-destructive**: backfill `data_source` from each row's primary vendor ref before dropping `is_primary`.
- SQL lives in `repo/` classes only; matcher policy lives in `service/`; routes stay thin.
- Grafana regression must pass: `python3 scripts/test_grafana_canonical_catalog_dashboards.py`. Grep `grafana/dashboards/` for `is_primary` before dropping it and update any dashboard SQL + regression expectations in the same task.

## Scope Decisions

- Per-vendor rows: each source owns its campground/campsite rows; no write-time cross-vendor merge. `additionalVendorRefs` (e.g. Campflare's embedded recgov ids) still land as `vendor_refs` on the owning row — they become **matcher input**, not dispatch aliases.
- Matches are pairwise with normalized ordering (`a_id < b_id`), `heuristic JSONB NOT NULL` (`{"method": "shared_vendor_ref"|"geo_name"|"manual", "score": <float>, ...}`).
- Match groups are connected components over pairwise matches. To keep the view SQL sane, the matcher **precomputes a group id**: `group_id = MIN(campground id in component)`, stored on a `match_group_id` column on `campgrounds`/`campsites` (NULL = singleton). The views group by `COALESCE(match_group_id, id)`. This avoids recursive CTEs in the view definition.
- Row-level winner: the view exposes the winning row's columns whole (no field coalesce), plus `member_ids BIGINT[]`, `member_sources TEXT[]`. Richness score = count of non-null/non-empty data columns; campgrounds add live campsite count.
- POI wraps the representative row; when the winner changes, re-point `poi_campgrounds`, `availability_watch_target.campsite_id`, `availability.campsite_id` transactionally (campsite matches translate site ids), logging every re-point.
- `campgrounds.preferred_availability_source TEXT` (nullable) ships in V39 — consumed by the provider-seams plan.
- API: keep `source`/`source_id`; **drop `data_source`** (duplicate of `source`, added in #404); add `sources: List<String>` = member_sources. `availability_provider` untouched here.
- Pipeline order: import per-vendor rows → matcher → `REFRESH ... CONCURRENTLY` → re-point representatives. Wired into ETL admin flow + a manual admin route.

## File Structure

- Create: `backend/src/main/resources/db/migration/V39__per_vendor_catalog_matches.sql`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CatalogMatchRepo.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/catalog/CatalogMatcherService.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CanonicalViewRepo.kt` (refresh + representative re-point queries)
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CatalogMatchRepoTest.kt`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/service/catalog/CatalogMatcherServiceTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogRepo.kt` (write `data_source`, stop writing `is_primary`)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampsiteProviderRepo.kt` (interim ordering without `is_primary`)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/PoiServingRepo.kt`, `repo/OnRoutePoiRepo.kt` (read through views)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt` + ingest/admin route (matcher + refresh stage)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/PoiSchemas.kt`, `routes/PoiRoutes.kt` (drop `data_source`, add `sources`)
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogSchemaTest.kt`
- Modify: `web/campground-card.js`, `web/campground-detail.test.mjs` (drop `data_source` read; show `sources` if trivially displayable)
- Modify: `docs/backend-architecture.md` (matcher + view read path)

---

### Task 1: V39 migration — data_source, matches, drop is_primary, views

**Files:**
- Create: `backend/src/main/resources/db/migration/V39__per_vendor_catalog_matches.sql`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogSchemaTest.kt`

**Interfaces:**
- Produces: tables `campground_matches(campground_a_id, campground_b_id, heuristic, created_at, updated_at)`, `campsite_matches(campsite_a_id, campsite_b_id, heuristic, ...)`; columns `campgrounds.data_source`, `campgrounds.match_group_id`, `campgrounds.preferred_availability_source`, `campsites.data_source`, `campsites.match_group_id`; matviews `campground_canonical`, `campsite_canonical`; **removed**: `campground_vendor_refs.is_primary`, `campsite_vendor_refs.is_primary` and their partial unique indexes.

- [ ] **Step 1: Extend `CanonicalCatalogSchemaTest` with failing assertions** — new tables/columns/views exist; `is_primary` columns do NOT exist. Follow the existing assertion helpers in that test (`assertTableExists`, column checks via `information_schema`).
- [ ] **Step 2: Run** `./gradlew test --tests "ca.floo.roadtrip.repo.CanonicalCatalogSchemaTest"` — expect FAIL (missing tables).
- [ ] **Step 3: Write the migration:**

```sql
-- V39__per_vendor_catalog_matches.sql
-- Per-vendor catalog rows: each ETL source owns its campground/campsite rows.
-- Cross-vendor identity moves from is_primary vendor-ref aliasing to explicit
-- match tables + canonical materialized views (row-level winner per group).

ALTER TABLE campgrounds
  ADD COLUMN data_source TEXT,
  ADD COLUMN match_group_id BIGINT,
  ADD COLUMN preferred_availability_source TEXT;
ALTER TABLE campsites
  ADD COLUMN data_source TEXT,
  ADD COLUMN match_group_id BIGINT;

-- Backfill data_source from the current primary vendor ref (pre-drop).
UPDATE campgrounds cg SET data_source = vr.vendor
FROM campground_vendor_refs cvr
JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
WHERE cvr.campground_id = cg.id AND cvr.is_primary;

UPDATE campsites cs SET data_source = vr.vendor
FROM campsite_vendor_refs cvr
JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
WHERE cvr.campsite_id = cs.id AND cvr.is_primary;

-- Rows without a primary ref inherit their only ref's vendor; anything still
-- null gets 'unknown' so NOT NULL can hold (rebuildable data, V38 precedent).
UPDATE campgrounds cg SET data_source = (
  SELECT vr.vendor FROM campground_vendor_refs cvr
  JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
  WHERE cvr.campground_id = cg.id
  ORDER BY cvr.vendor_ref_id LIMIT 1
) WHERE data_source IS NULL;
UPDATE campsites cs SET data_source = (
  SELECT vr.vendor FROM campsite_vendor_refs cvr
  JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
  WHERE cvr.campsite_id = cs.id
  ORDER BY cvr.vendor_ref_id LIMIT 1
) WHERE data_source IS NULL;
UPDATE campgrounds SET data_source = 'unknown' WHERE data_source IS NULL;
UPDATE campsites SET data_source = 'unknown' WHERE data_source IS NULL;

ALTER TABLE campgrounds ALTER COLUMN data_source SET NOT NULL;
ALTER TABLE campsites ALTER COLUMN data_source SET NOT NULL;
ALTER TABLE campgrounds
  ADD CONSTRAINT campgrounds_data_source_check CHECK (length(btrim(data_source)) > 0);
ALTER TABLE campsites
  ADD CONSTRAINT campsites_data_source_check CHECK (length(btrim(data_source)) > 0);

CREATE INDEX campgrounds_match_group_idx ON campgrounds (match_group_id) WHERE match_group_id IS NOT NULL;
CREATE INDEX campsites_match_group_idx ON campsites (match_group_id) WHERE match_group_id IS NOT NULL;

DROP INDEX IF EXISTS campground_vendor_refs_primary_uidx;
DROP INDEX IF EXISTS campsite_vendor_refs_primary_uidx;
ALTER TABLE campground_vendor_refs DROP COLUMN is_primary;
ALTER TABLE campsite_vendor_refs DROP COLUMN is_primary;

CREATE TABLE campground_matches (
  id               BIGSERIAL PRIMARY KEY,
  campground_a_id  BIGINT NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  campground_b_id  BIGINT NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  heuristic        JSONB  NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT campground_matches_order_check CHECK (campground_a_id < campground_b_id),
  CONSTRAINT campground_matches_heuristic_check CHECK (jsonb_typeof(heuristic) = 'object'),
  CONSTRAINT campground_matches_pair_uidx UNIQUE (campground_a_id, campground_b_id)
);
CREATE INDEX campground_matches_b_idx ON campground_matches (campground_b_id);

CREATE TABLE campsite_matches (
  id             BIGSERIAL PRIMARY KEY,
  campsite_a_id  BIGINT NOT NULL REFERENCES campsites(id) ON DELETE CASCADE,
  campsite_b_id  BIGINT NOT NULL REFERENCES campsites(id) ON DELETE CASCADE,
  heuristic      JSONB  NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT campsite_matches_order_check CHECK (campsite_a_id < campsite_b_id),
  CONSTRAINT campsite_matches_heuristic_check CHECK (jsonb_typeof(heuristic) = 'object'),
  CONSTRAINT campsite_matches_pair_uidx UNIQUE (campsite_a_id, campsite_b_id)
);
CREATE INDEX campsite_matches_b_idx ON campsite_matches (campsite_b_id);

-- Canonical views: one row per match group, the richest member's columns whole.
-- Groups key on COALESCE(match_group_id, id); the matcher maintains
-- match_group_id = MIN(member id) per connected component.
CREATE MATERIALIZED VIEW campground_canonical AS
WITH scored AS (
  SELECT cg.*,
         COALESCE(cg.match_group_id, cg.id) AS group_key,
         (
           (cg.status IS NOT NULL)::int + (cg.kind IS NOT NULL)::int +
           (cg.short_description IS NOT NULL)::int + (cg.medium_description IS NOT NULL)::int +
           (cg.long_description IS NOT NULL)::int + (cg.reservation_url IS NOT NULL)::int +
           (cg.max_rv_length IS NOT NULL)::int + (cg.has_pull_through_sites IS NOT NULL)::int +
           (cg.big_rig_friendly IS NOT NULL)::int +
           (cg.location <> '{}'::jsonb)::int + (cg.amenities <> '{}'::jsonb)::int +
           (cg.links <> '[]'::jsonb)::int + (cg.photos <> '[]'::jsonb)::int +
           (cg.price <> '{}'::jsonb)::int + (cg.cell_service <> '{}'::jsonb)::int +
           (cg.management <> '{}'::jsonb)::int + (cg.contact <> '{}'::jsonb)::int +
           (cg.connections <> '{}'::jsonb)::int
         ) * 1000
         + (SELECT count(*) FROM campsites cs
            WHERE cs.campground_id = cg.id AND cs.deleted_at IS NULL) AS richness
  FROM campgrounds cg
  WHERE cg.deleted_at IS NULL
),
winners AS (
  SELECT DISTINCT ON (group_key) *
  FROM scored
  ORDER BY group_key, richness DESC, id ASC
)
SELECT w.*,
       ARRAY(SELECT s.id FROM scored s WHERE s.group_key = w.group_key ORDER BY s.id) AS member_ids,
       ARRAY(SELECT s.data_source FROM scored s WHERE s.group_key = w.group_key ORDER BY s.id) AS member_sources
FROM winners w;

CREATE UNIQUE INDEX campground_canonical_id_uidx ON campground_canonical (id);
CREATE INDEX campground_canonical_group_idx ON campground_canonical (group_key);

CREATE MATERIALIZED VIEW campsite_canonical AS
WITH scored AS (
  SELECT cs.*,
         COALESCE(cs.match_group_id, cs.id) AS group_key,
         (
           (cs.loop_name IS NOT NULL)::int + (cs.latitude IS NOT NULL)::int +
           (cs.reservation_url IS NOT NULL)::int + (cs.kind_listed IS NOT NULL)::int +
           (cs.firepit IS NOT NULL)::int + (cs.picnic_table IS NOT NULL)::int +
           (cs.ada_accessible IS NOT NULL)::int + (cs.water_hookups IS NOT NULL)::int +
           (cs.electric_hookups IS NOT NULL)::int + (cs.sewer_hookups IS NOT NULL)::int +
           (cs.max_people IS NOT NULL)::int + (cs.max_cars IS NOT NULL)::int +
           (cs.pull_through IS NOT NULL)::int + (cs.driveway_length IS NOT NULL)::int +
           (cs.max_rv_length IS NOT NULL)::int +
           (COALESCE(cs.equipment, '[]'::jsonb) <> '[]'::jsonb)::int +
           (cs.schedule <> '{}'::jsonb)::int + (cs.price <> '{}'::jsonb)::int +
           (cs.photos <> '[]'::jsonb)::int
         ) AS richness
  FROM campsites cs
  WHERE cs.deleted_at IS NULL
),
winners AS (
  SELECT DISTINCT ON (group_key) *
  FROM scored
  ORDER BY group_key, richness DESC, id ASC
)
SELECT w.*,
       ARRAY(SELECT s.id FROM scored s WHERE s.group_key = w.group_key ORDER BY s.id) AS member_ids,
       ARRAY(SELECT s.data_source FROM scored s WHERE s.group_key = w.group_key ORDER BY s.id) AS member_sources
FROM winners w;

CREATE UNIQUE INDEX campsite_canonical_id_uidx ON campsite_canonical (id);
CREATE INDEX campsite_canonical_group_idx ON campsite_canonical (group_key);
CREATE INDEX campsite_canonical_campground_idx ON campsite_canonical (campground_id);
```

- [ ] **Step 4:** `./gradlew build` (migration runs, jOOQ regenerates, schema test passes). Fix any generated-code compile fallout from the dropped `is_primary` (source uses raw SQL, so expected fallout is only in `CanonicalCatalogRepo` — handled next task; if the build breaks there already, do the minimal `is_primary` write-removal in this task instead and note it in the commit).
- [ ] **Step 5:** Grep `grafana/dashboards/ -e is_primary` — update any dashboard SQL + `scripts/test_grafana_canonical_catalog_dashboards.py` expectations; run the script.
- [ ] **Step 6: Commit** `git commit -m "feat: V39 per-vendor catalog rows, match tables, canonical views" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 2: Write path — CanonicalCatalogRepo writes data_source, drops is_primary

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogRepo.kt` (upsertCampground ~:114, upsertCampsite ~:151, vendor-ref link SQL ~:428-439 and ~:846-857)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampsiteProviderRepo.kt`
- Test: existing `repo/catalog` test files for upsert idempotency

**Interfaces:**
- Consumes: `CampgroundEtlRecord.vendor`, `CampsiteEtlRecord` vendor (service/etl/framework/CampsiteEtlOutput.kt).
- Produces: campground/campsite INSERT/UPDATE sets include `data_source = record.vendor`; link-table SQL no longer references `is_primary`; **row identity for upsert becomes (vendor, external ref) per source** — a Campflare record never updates a recgov row. Verify the current upsert resolves rows via vendor_refs lookup (it does — keep that, it's already per-vendor keyed).

- [ ] **Step 1:** Extend an existing upsert idempotency test: two records for the same real-world campground from different vendors produce **two rows** with distinct `data_source`, each with its own vendor refs; re-running either updates its own row only. Run → FAIL.
- [ ] **Step 2:** Implement: add `data_source` to insert/update column lists (value = record vendor); delete the two `is_primary` UPDATE statements and the `is_primary` column/CASE from both link INSERTs.
- [ ] **Step 3:** In `CampsiteProviderRepo`, replace the two `cvr.is_primary DESC` ORDER BY terms (4 query sites) with interim ordering: shape-match first (existing `providerRefShapeSql`), then `vendor_ref_id ASC`. (The provider-seams plan replaces this wholesale.)
- [ ] **Step 4:** `./gradlew build` → PASS. **Commit** `git commit -m "feat: per-vendor catalog writes (data_source, no is_primary)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 3: CatalogMatchRepo + CatalogMatcherService

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CatalogMatchRepo.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/catalog/CatalogMatcherService.kt`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CatalogMatchRepoTest.kt`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/service/catalog/CatalogMatcherServiceTest.kt`

**Interfaces:**
- Produces:
```kotlin
class CatalogMatchRepo(private val ctx: DSLContext) {
    data class MatchPair(val aId: Long, val bId: Long, val heuristic: JsonObject)
    fun upsertCampgroundMatches(pairs: List<MatchPair>): Int   // normalized a<b, ON CONFLICT update heuristic+updated_at
    fun upsertCampsiteMatches(pairs: List<MatchPair>): Int
    fun sharedVendorRefCampgroundPairs(): List<MatchPair>      // SQL self-join on vendor_refs (vendor, entity_type, external_id)
    fun sharedVendorRefCampsitePairs(): List<MatchPair>
    fun geoNameCampgroundCandidates(maxDistanceM: Double): List<GeoNameCandidate>
        // pairs within distance, different data_source, not already matched; returns names for scoring in Kotlin
    fun campsiteNameCandidates(): List<CampsiteNameCandidate>  // campsites under matched campgrounds, grouped
    fun recomputeMatchGroups(): Int
        // UPDATE campgrounds/campsites match_group_id = MIN(id) over connected components
        // (iterative label propagation in SQL: seed group = own id, repeat UPDATE joining matches until no row changes)
}

class CatalogMatcherService(
    private val matches: CatalogMatchRepo,
    private val config: MatcherConfig,
) {
    data class MatcherConfig(val maxDistanceM: Double, val minNameSimilarity: Double)
    companion object {
        const val DEFAULT_MAX_DISTANCE_M = 500.0        // env MATCH_MAX_DISTANCE_M
        const val DEFAULT_MIN_NAME_SIMILARITY = 0.85    // env MATCH_MIN_NAME_SIMILARITY
        const val METHOD_SHARED_VENDOR_REF = "shared_vendor_ref"
        const val METHOD_GEO_NAME = "geo_name"
        const val METHOD_MANUAL = "manual"
    }
    data class MatchRunStats(val campgroundPairs: Int, val campsitePairs: Int, val groupsRecomputed: Int)
    fun run(): MatchRunStats
}
```
- Heuristic jsonb shapes: `{"method":"shared_vendor_ref","score":1.0,"vendor":"recgov","external_id":"232447"}`; `{"method":"geo_name","score":0.91,"distance_m":112.4,"name_similarity":0.93}`.
- Name normalization + similarity in Kotlin (trigram/Jaro-Winkler-style; implement a small `nameSimilarity(a, b): Double` — no new deps, normalized-token Jaccard over lowercased alphanumeric tokens is acceptable and testable).
- Campsite pass: only within campground match groups; key = normalized `(loop_name, name)` equality → score 1.0 with `method: geo_name` and `matched_on: "loop+name"` (site names are short; token similarity is noise — exact normalized match only in v1).

- [ ] **Step 1:** Repo test: seed two campgrounds sharing a vendor_refs triple → `sharedVendorRefCampgroundPairs` returns the normalized pair; `upsertCampgroundMatches` idempotent; `recomputeMatchGroups` sets both rows' `match_group_id` to the lower id, and chains transitively (A-B, B-C → all three share group). Run → FAIL.
- [ ] **Step 2:** Implement repo (raw SQL per repo conventions). Run → PASS. Commit.
- [ ] **Step 3:** Service test: deterministic pass writes shared_vendor_ref matches; heuristic pass matches near+similar-name cross-vendor campgrounds and skips far/dissimilar ones; campsite pass matches normalized loop+name within groups; `run()` returns stats; thresholds honored from config. Run → FAIL.
- [ ] **Step 4:** Implement service; env wiring for the two thresholds follows the existing config pattern in `config/`. Run → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: catalog matcher (shared-ref + geo-name passes)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 4: View refresh + representative re-pointing

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CanonicalViewRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalViewRepoTest.kt`

**Interfaces:**
```kotlin
class CanonicalViewRepo(private val ctx: DSLContext) {
    fun refreshCanonicalViews()   // REFRESH MATERIALIZED VIEW CONCURRENTLY campground_canonical; then campsite_canonical
    data class RepointStats(val poisRepointed: Int, val watchTargetsRepointed: Int, val availabilityRowsRepointed: Int)
    fun repointRepresentatives(): RepointStats
    // In ONE transaction:
    //  1. poi_campgrounds: for each poi whose campground_id is a non-winner member,
    //     UPDATE to the group winner (from campground_canonical), collapsing duplicate
    //     POIs if both members had one (keep lowest poi id, soft-delete the other pois row).
    //  2. availability_watch_target.campsite_id / availability.campsite_id: translate
    //     non-winner campsite ids to their group winner via campsite_canonical
    //     (member_ids ↔ id), guarding UNIQUE collisions with ON CONFLICT DO NOTHING +
    //     delete of the now-duplicate source row.
    //  Log one line per re-point at INFO with old/new ids.
}
```

- [ ] **Step 1:** Test: seed two matched campgrounds where the non-winner holds the POI + a watch target on its campsite; after `refreshCanonicalViews()` + `repointRepresentatives()`, the POI joins the winner, the watch target points at the winner's matched campsite, stats are correct, and re-running is a no-op. Run → FAIL.
- [ ] **Step 2:** Implement. Run → PASS. **Commit** `git commit -m "feat: canonical view refresh and representative re-pointing" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 5: Pipeline wiring — matcher stage + admin route

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt` (or the ingest controller that sequences imports — follow where `runJoiner` is invoked)
- Modify: the admin/ingest route file that exposes ETL runs (grep `runPoiData\|runCampsiteData` in `routes/`)
- Modify: `Main.kt`/`RoadtripRuntime.kt` wiring

**Interfaces:**
- Produces: `EtlOrchestrator.runCatalogMatch(): CatalogMatcherService.MatchRunStats` — runs matcher → `refreshCanonicalViews()` → `repointRepresentatives()`; invoked automatically after campsite-data terminal imports (same place joiners run) and manually via `POST /api/admin/etl/catalog-match` (follow the existing admin ETL route pattern/auth).

- [ ] **Step 1:** Route test (existing admin route test pattern): POST returns 200 with stats JSON DTO (`@Serializable` — no hand-built JSON). Run → FAIL.
- [ ] **Step 2:** Implement orchestrator method + route + wiring. Run → PASS.
- [ ] **Step 3: Commit** `git commit -m "feat: catalog match stage in ETL pipeline + admin route" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 6: Read path — serve through canonical views + API sources

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/PoiServingRepo.kt`, `repo/OnRoutePoiRepo.kt` — `FROM campgrounds` → `FROM campground_canonical` (join key unchanged: `poi_campgrounds.campground_id = campground_canonical.id`; representative invariant makes this exact), campsite serving reads `campsite_canonical`; select `member_sources`.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/PoiSchemas.kt` — delete `dataSource` field; add `val sources: List<String> = emptyList()`.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/PoiRoutes.kt` (~:366-380) — delete `dataSource = r.source`; populate `sources = r.memberSources`; `source`/`sourceId` now come from the winner row's `data_source` + its vendor ref.
- Modify: `web/campground-card.js` (drop the `data_source` read added in #404), `web/campground-detail.test.mjs`, `backend/src/test/.../PoiServingRepoTest.kt`, `FeatureCollectionContractTest.kt`.

- [ ] **Step 1:** Update `PoiServingRepoTest` + contract test: response has `sources` array listing all member vendors, no `data_source`. Run → FAIL.
- [ ] **Step 2:** Implement backend; then web changes; run `./gradlew build` and the web test suite → PASS.
- [ ] **Step 3: Commit** `git commit -m "feat: serve campgrounds through canonical views, expose member sources" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 7: Docs + end-to-end verification

**Files:**
- Modify: `docs/backend-architecture.md` (ETL flow gains match → refresh → re-point stages; read path notes canonical views)

- [ ] **Step 1:** Update the docs.
- [ ] **Step 2: E2E** against the local tilt stack (compose project "roadtrip", backend :8765):
  - Run the Campflare and recgov imports (existing admin ETL routes), then `POST /api/admin/etl/catalog-match`.
  - `SELECT id, data_source, match_group_id FROM campgrounds WHERE name ILIKE '%upper pines%';` → two rows, same `match_group_id`.
  - `SELECT member_sources FROM campground_canonical WHERE 'campflare' = ANY(member_sources) AND name ILIKE '%upper pines%';` → one row, both vendors listed.
  - One POI on the map for Upper Pines; drawer shows the richer record; `GET /api/pois/{id}` has `sources: ["campflare","recgov"]` (order by member id).
  - `python3 scripts/test_grafana_canonical_catalog_dashboards.py` passes.
- [ ] **Step 3: Commit** `git commit -m "docs: per-vendor catalog + match pipeline" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

## Self-Review

- [ ] Every task compiles and tests green on its own commit.
- [ ] No task references `is_primary` after Task 2; interim `CampsiteProviderRepo` ordering stated explicitly.
- [ ] View SQL avoids recursive CTEs (matcher-maintained `match_group_id`); `REFRESH ... CONCURRENTLY` backed by unique indexes.
- [ ] Re-pointing is transactional, idempotent, logged; watch/availability identity preserved.
- [ ] `data_source` removal covers backend field, route mapping, web read, and both test suites.
- [ ] Matcher thresholds are named consts with env overrides; no magic numbers.
- [ ] Names produced here (`campground_canonical`, `member_sources`, `preferred_availability_source`, `CatalogMatchRepo`, `CanonicalViewRepo`) are the ones the provider-seams plan consumes.
