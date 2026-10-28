# TypeScript Importer + Differential Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Kotlin ETL framework and its eight vendor adapter families to a TypeScript CLI in a new `@roadtrip/worker` package, and prove it correct by diffing its catalog rows against the Kotlin importer's over the same captures.

**Architecture:** `packages/worker` holds a `roadtrip-import` CLI that mirrors the Kotlin pipeline seam for seam — `RawCaptureStore` → `InputBundle` → `SourceEtl.parse` → `SourceEtl.transform` → batched repo upsert — reading `data/raw` and writing through `@roadtrip/db`. A harness (`scripts/importer-differential.mjs`) stands up one PostGIS container with two databases, migrates one with Flyway and one with Drizzle, runs the Kotlin importer into the first and the TypeScript importer into the second, binary-`COPY`s the Kotlin catalog tables into a `reference` schema beside the TypeScript ones, and diffs them with one SQL statement per table so every JSONB column is compared by the `jsonb` `=` operator. The diff's column list is derived from the Drizzle schema, so a new column is compared without anyone remembering to add it.

**Tech Stack:** Node 22.12+, TypeScript 7, `drizzle-orm` 0.45.4, `postgres` 3.4.9, `yaml` (new dependency, for `poi-registry.yaml`), vitest 4, `postgis/postgis:16-3.4` and `flyway/flyway:13.2.0` via Docker, Gradle `JavaExec` for the Kotlin reference importer.

**Spec:** [docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md](../specs/2026-10-27-kotlin-to-typescript-backend-design.md) — §4 (the importer), §8 (testing), §3 (schema and typed JSONB), §10 step 4. Also binding: [docs/superpowers/2026-10-27-step-2-rulings.md](../2026-10-27-step-2-rulings.md).

## Global Constraints

Every task's requirements implicitly include these.

- **No code comments in TypeScript or Kotlin.** `AGENTS.md`: none inline, none in KDoc/JSDoc; rationale goes in the commit message.
- **No inline magic constants.** Numeric, string and duration literals at call sites are extracted to a named `const` (`AGENTS.md` design principles). Batch sizes come from named constants mirroring `MAX_CATALOG_UPSERT_BATCH_SIZE = 1000` and `BULK_CHUNK_SIZE = 500`.
- **The JSONB columns carry no `.$type<>`** and must not gain one. They type as `unknown`; every read and write goes through `encodeCatalogJson` / `decodeCatalogJson` from `@roadtrip/db`. The codec is the only guard in both directions.
- **Stored rows omit every null and every defaulted value**, and keys are snake_case. This is Kotlin's `Json { explicitNulls = false }` with no `encodeDefaults`. `encodeCatalogJson` already implements it; do not bypass it.
- **`{}` decodes to null** for the seven columns Kotlin reads through `decodeObjectColumn`: `location`, `default_campsite_schedule`, `price`, `management`, `contact`, `metadata`, `geometry_provenance`. Nested objects are not nulled — only the column itself. Already implemented by `columnObjectOf`.
- **`campsites.price` and `campsites.schedule` are raw vendor JSON**, not `CampgroundPrice` / `CampgroundSchedule`. `CampsiteRepo.kt:338-339` reads both through `parseJsonElement`. The identically named *campground* columns are those shapes; only the campsite pair is raw. Write them through the opaque passthrough, never through `priceShape` / `scheduleShape`.
- **The diff compares JSONB as `jsonb`, never as `::text`.** Kotlin writes `12.0` where TypeScript writes `12`; equal under `=`, different as text.
- **The importer refreshes nothing.** There are no materialized views; `campground_canonical` and `campsite_canonical` were dropped in V44.
- **`data/raw` is tracked in git**, 19,136 files and 1.7 G, and is present in this worktree. `scripts/check-worktree-sparse-paths.mjs` lists it under `EXCLUDED`, which only means the guard tolerates its absence from `worktree.sparsePaths`; nothing prunes it, and `core.sparseCheckout` is not set here. Work in this worktree — the harness runs in place. The requirement that stands regardless of where it runs: **anything that reads the captures must fail loudly when they are absent or partial**, never report a clean diff over nothing. That is Review Focus #1, and it is about a gate that cannot fail, not about a working directory.
- **The new package lives at `packages/worker`**, inside the existing `packages/*` workspace glob, so no new top-level directory and no `.claude/settings.json` → `worktree.sparsePaths` edit is needed. Spec §10 step 2's sparsePaths warning applies only if that choice is changed.
- **One root lockfile.** `scripts/check-single-lockfile.mjs` fails on a second one; add dependencies with a root `npm install --workspace @roadtrip/worker`.
- **The frontend must not reach `@roadtrip/db`** (`scripts/check-package-boundaries.mjs`). Nothing in this plan touches `frontend/`.

### Facts established by reading the live system

Trust these over any summary; each was checked against the code, the registry or the captures on 2026-10-28.

- **18 terminal ETL runs**: 10 `poi_data` rows then 8 `campsite_data` rows, in `poi-registry.yaml` order. That order is load-bearing — campsite adapters resolve parents through `campgrounds.(data_provider, data_provider_ref)`, so campgrounds must already be in the database.
- **Tables the import writes**: `campgrounds`, `campsites`, `pois`, `poi_campgrounds`, `tesla_superchargers`, `poi_tesla_superchargers`, `planet_fitness_locations`, `poi_planet_fitness_locations`, `import_runs`. `ingest_runs` is written only by `IngestController`, which this port does not include.
- **Only one `client/` class is reachable from the ETL tree**: `ReserveAmericaCatalogParser` (39 LOC). The brief's "vendor clients" is that one file.
- **12,571 envelopes are read through `InputBundle`** from the newest capture of the 25 `data_source` slugs that appear in some row's `inputs:`, plus up to 3,793 `tesla-locations` slug directories that `TeslaIndexEtl` side-loads from `ctx.rawDir` directly. `tesla-locations` is the 26th declared `data_source` and is an input to nothing. `aspira-resources-{wa,bc,pc}` (794 files) are captured but appear nowhere in the registry and are never read. The 19,136 figure is the tracked file count, not the read count.
- **Nothing clock-derived reaches a stored column.** `fetchedAt` is carried on five DTOs (`AspiraCampgroundsEtl.kt:90` even sets it from `Instant.now()`) and read by nothing. `created_at` / `updated_at` are `now()` in SQL and are excluded from the diff.
- **`CampgroundUpsertCandidate.sourceUrl` is written to no column.** It is absent from `CampgroundRepo`'s INSERT list. Port the field or not; it changes no row.
- **No JSON integer above 2^53 appears** in the newest capture of any of the 29 sources (sampled to 3 pages per source). The 17-digit literals present are inside strings. This is a sample, not a proof — see Review Focus.
- **Two capture layouts**: a timestamped `.json` file (`aspira-maps-*`, `aspira-dictionaries-*`, `osm-pf`, `tesla-index`) or a timestamped directory of pages (everything else). `tesla-locations` is a third, per-slug layout read outside `RawCaptureStore`.

## Review Focus

Five conditions the spec implies that no task's own tests would otherwise exercise, most likely to bite first. Each one's test is added to the task that owns the code.

1. **A missing, empty or partial `data/raw`.** This is the *normal* state in a Claude worktree, so the harness will meet it. A run over zero captures must fail, not report a clean diff — the exact "gate that cannot fail" mutation testing caught in step 2. Owned by Task 5.
2. **Duplicate `(data_provider, data_provider_ref)` inside one batch.** Kotlin calls `distinctBy` and keeps the *first*. A Postgres `INSERT … ON CONFLICT DO UPDATE` over a batch containing a duplicate fails with "cannot affect row a second time", so the TypeScript must dedupe the same way and keep the same winner. Owned by Task 3.
3. **A capture directory holding no `.json` pages, and a source directory holding none.** Kotlin throws `NoCaptureException`; silently returning `[]` turns a broken capture into a clean empty import. Owned by Task 2.
4. **Non-finite or out-of-range coordinates reaching the encoder and `ST_MakePoint`.** `GeometryIndex.isOnEarth` guards this for Aspira only; every other vendor hands latitude/longitude straight to `pois.geom` and to `locationShape`. `NaN` survives `toDoubleOrNull`, and `encodeCatalogJson`'s number check rejects it — a single bad row must fail the way Kotlin fails, not differently. Owned by Tasks 3 and 9.
5. **A JSON integer wider than IEEE-754 exact range in an opaque passthrough column.** Kotlin's `JsonElement` keeps the literal digits; `JSON.parse` rounds. Any such value in `source_payload`, `index_payload`, `detail_payload` or `payload` is a silent, permanent corruption that the diff would report as a mismatch. Owned by Task 3.

---

### Task 1: Worker package and the POI registry

**Files:**
- Create: `packages/worker/package.json`, `packages/worker/tsconfig.json`, `packages/worker/tsconfig.typecheck.json`, `packages/worker/vitest.config.ts`
- Create: `packages/worker/src/index.ts`, `packages/worker/src/registry/poi-registry.ts`, `packages/worker/src/registry/targets.ts`
- Test: `packages/worker/src/registry/poi-registry.test.ts`
- Modify: root `package.json` (nothing — `packages/*` already matches), `Makefile:110` (`test` target)
- Reference: `backend/src/main/resources/poi-registry.yaml`, `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/*.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/RegistryTargets.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `loadPoiRegistry(yamlPath: string): PoiRegistry`
  - `interface PoiRegistry { dataSources: DataSourceEntry[]; poiData: PoiDataEntry[]; campsiteData: CampsiteDataEntry[]; bookingProviders: BookingProviderEntry[]; dataSource(slug: string): DataSourceEntry | undefined; poiDataByName(name): PoiDataEntry | undefined; campsiteDataByName(name): CampsiteDataEntry | undefined }`
  - `interface DataSourceEntry { slug: string; name: string; dependsOn?: string[]; fetcher: { executor?: string; filename?: string; args?: Record<string, string>; timeoutSec?: number; outputDirPrefix: string } }` — field for field with `DataSourceEntry.kt` and `Fetcher.kt`. Task 2 resolves a capture directory from `fetcher.outputDirPrefix`, so that field is load-bearing.
  - `interface EtlEntry { slug: string; adapter: string; inputs: string[]; args: Record<string, string>; geometry?: GeometryPolicy }`
  - `type AgencyConfig = { kind: 'constant'; value: string } | { kind: 'derived'; field: string }`, mirroring `AgencyConfig.kt`
  - `interface GeometryPolicy { sources: GeometrySourceSpec[]; match: MatchPolicy }`, `interface GeometrySourceSpec { input: string; format: GeometryFormat; state?: string; nameProperty?: string }`, `interface MatchPolicy { fuzzyThreshold: number; parentFallback: boolean }` — field for field with `GeometryPolicy.kt`, including its default `fuzzyThreshold`
  - `type ImportSection = 'poi_data' | 'campsite_data'`
  - `interface ImportTarget { name: string; section: ImportSection }` — name and section only, as Kotlin's `RegistryTargets` emits. An earlier version of this plan carried the row's terminal `EtlEntry` here and Task 1 spent a fix round pinning it to the *last* entry; Task 4 then showed `runTarget` re-resolves the row by name and never reads it, so the field was dead surface that tests had to fabricate, and a hand-built target with a mismatched entry would silently run the registry's. Removed.
  - `importTargets(registry: PoiRegistry, implemented: ReadonlySet<string>): ImportTarget[]`

Package manifest mirrors `packages/db/package.json`: `"name": "@roadtrip/worker"`, `"private": true`, `"type": "module"`, `engines.node >= 22.12.0`, scripts `build` / `typecheck` / `test`, dependencies `@roadtrip/db`, `@roadtrip/domain`, `yaml`. Add a `bin` entry `roadtrip-import` → `./dist/cli/import.js` in Task 4.

`importTargets` mirrors `RegistryTargets.kt`: skip rows with `enabled: false`, skip rows any of whose etl slugs is absent from `implemented`, emit `poi_data` rows first then `campsite_data`, each in YAML order.

- [ ] **Step 1: Write the failing test**

```ts
test('the production registry yields the eighteen import targets in Kotlin order', () => {
  const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
  const names = importTargets(registry, ALL_PRODUCTION_SLUGS).map((t) => `${t.section}:${t.name}`);
  expect(names).toEqual([
    'poi_data:Campflare Campgrounds',
    'poi_data:Rec.gov Campgrounds',
    'poi_data:Washington State Parks',
    'poi_data:BC Provincial Parks',
    'poi_data:Parks Canada',
    'poi_data:Alberta Provincial Parks',
    'poi_data:New York State Parks',
    'poi_data:California State Parks',
    'poi_data:Planet Fitness',
    'poi_data:Tesla Superchargers',
    'campsite_data:Campflare Campsites',
    'campsite_data:Rec.gov Campsites',
    'campsite_data:Washington Aspira Resources',
    'campsite_data:BC Aspira Resources',
    'campsite_data:Parks Canada Aspira Resources',
    'campsite_data:California State Park Sites',
    'campsite_data:Alberta Provincial Park Sites',
    'campsite_data:New York State Park Sites',
  ]);
});

test('a row whose etl slug is not implemented yields no target', () => {
  const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
  const without = new Set([...ALL_PRODUCTION_SLUGS].filter((s) => s !== 'planet-fitness'));
  expect(importTargets(registry, without).map((t) => t.name)).not.toContain('Planet Fitness');
});

test('args and geometry survive the load', () => {
  const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
  const wa = registry.poiDataByName('Washington State Parks')!.etls[0]!;
  expect(wa.args).toEqual({ host: 'washington.goingtocamp.com', tenant: 'wa' });
  expect(wa.inputs).toEqual(['aspira-maps-wa', 'uscampgrounds', 'aspira-inventory-wa', 'aspira-dictionaries-wa']);
  expect(wa.geometry?.sources.map((s) => s.input)).toEqual(['uscampgrounds']);
  expect(wa.geometry?.match.parentFallback).toBe(true);
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm run test --workspace @roadtrip/worker`
Expected: FAIL — no such module.

- [ ] **Step 3: Implement the package skeleton, `loadPoiRegistry` and `importTargets`**

Parse with `yaml`'s `parse`. Convert snake_case YAML keys to the camelCase field names in the Produces block. Carry `agency` as `{ kind: 'constant'; value: string } | { kind: 'derived'; field: string }`, mirroring `AgencyConfig`. Do not port `PoiRegistry.validate` — the Kotlin boot already enforces it and the importer reads a registry that passed.

- [ ] **Step 4: Run the test to verify it passes**

Run: `npm run test --workspace @roadtrip/worker`
Expected: PASS

- [ ] **Step 5: Wire the package into the repo's build and test**

Add to `Makefile`'s `test` target, beside the existing `@roadtrip/db` lines: `npm run typecheck --workspace @roadtrip/worker` and `npm run test --workspace @roadtrip/worker`. Add a `package_tests` path-filter entry for `packages/**` — it already matches.

- [ ] **Step 6: Run the gates**

Run: `npm run build && npm run typecheck --workspace @roadtrip/worker && node scripts/check-worktree-sparse-paths.mjs && node scripts/check-single-lockfile.mjs`
Expected: all pass; the sparse-paths check still prints "ok".

- [ ] **Step 7: Commit**

```bash
git add packages/worker package.json package-lock.json Makefile
git commit -m "feat(worker): add the worker package and the POI registry loader"
```

---

### Task 2: RawCaptureStore, Envelope and InputBundle

**Files:**
- Create: `packages/worker/src/etl/envelope.ts`, `packages/worker/src/etl/raw-capture-store.ts`, `packages/worker/src/etl/input-bundle.ts`
- Test: `packages/worker/src/etl/raw-capture-store.test.ts`, `packages/worker/src/etl/input-bundle.test.ts`
- Reference: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/{RawCaptureStore,InputBundle}.kt`

**Interfaces:**
- Consumes: `DataSourceEntry` from Task 1.
- Produces:
  - `interface Envelope { fetcher: string; fetcherVersion: string; fetchedAt: string; request: unknown; response: unknown; pollerRunId: number | null; payload: unknown; part?: string }`
  - `parseEnvelope(file: string): Envelope`
  - `class NoCaptureError extends Error`
  - `class RawCaptureStore { constructor(rawDir: string, staticDir: string); loadNewestEnvelopes(source: string | DataSourceEntry): Envelope[] }`
  - `class InputBundle { constructor(captures: Map<string, Envelope[]>); envelopes(slug): Envelope[]; envelope(slug): Envelope; soleEnvelopes(): Envelope[]; dataSourceSlugs(): string[] }`

`loadNewestEnvelopes` selects the entry with the greatest `name` among directories and `.json` files in the source directory, comparing with the default UTF-16 code-unit ordering (`a < b`), *not* `localeCompare` and not numeric collation — Kotlin's `maxByOrNull { it.name }` is code-unit ordering and `facility-10000305.json` style names depend on it. A directory winner yields its `.json` files sorted by the same ordering; a file winner yields one envelope. For a `DataSourceEntry`, resolve `fetcher.outputDirPrefix` against `staticDir` when relative, and reject a path that normalizes outside `rawDir`.

- [ ] **Step 1: Write the failing tests**

```ts
test('the newest capture directory wins and its pages come back in name order', () => {
  const store = new RawCaptureStore(fixtureRawDir, fixtureStaticDir);
  const envelopes = store.loadNewestEnvelopes('paged');
  expect(envelopes.map((e) => e.part)).toEqual(['page-001', 'page-002', 'page-010']);
});

test('a single timestamped file is one envelope', () => {
  expect(new RawCaptureStore(fixtureRawDir, fixtureStaticDir).loadNewestEnvelopes('single')).toHaveLength(1);
});

test('a source directory with no captures throws', () => {
  expect(() => store.loadNewestEnvelopes('empty-source')).toThrow(NoCaptureError);
});

test('a newest capture directory with no json pages throws', () => {
  expect(() => store.loadNewestEnvelopes('empty-capture')).toThrow(NoCaptureError);
});

test('a source directory that does not exist throws', () => {
  expect(() => store.loadNewestEnvelopes('absent')).toThrow(NoCaptureError);
});

test('an output_dir_prefix resolving outside the raw dir is rejected', () => {
  const entry = { slug: 'escapee', fetcher: { outputDirPrefix: 'data/../etc' } };
  expect(() => store.loadNewestEnvelopes(entry)).toThrow(/resolves outside raw dir/);
});

test('names sort by code unit, not by locale or number', () => {
  expect(new RawCaptureStore(ordRawDir, ordStaticDir).loadNewestEnvelopes('ordering').map((e) => e.part))
    .toEqual(['facility-10', 'facility-9']);
});

test('an integer wider than IEEE-754 exact range survives parsing with its digits intact', () => {
  const envelope = parseEnvelope(fileHolding('{"payload":{"photo_id":72157625256027958}}'));
  expect(JSON.stringify(envelope.payload)).toContain('72157625256027958');
});

test('envelope(slug) rejects a multi-part input and soleEnvelopes rejects two inputs', () => {
  const bundle = new InputBundle(new Map([['a', [e1, e2]], ['b', [e1]]]));
  expect(() => bundle.envelope('a')).toThrow(/multi-part/);
  expect(() => bundle.soleEnvelopes()).toThrow(/exactly one data_source input/);
  expect(() => bundle.envelopes('c')).toThrow(/not a declared data_source input/);
});
```

Fixtures are written into a temp directory by the test, not read from `data/raw` — these tests must pass in a Claude worktree. One further test reads `data/raw` and is skipped when it is absent; it asserts `loadNewestEnvelopes('recgov-campsites')` returns 4001 envelopes and that the first is `facility-10000305.json`'s.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm run test --workspace @roadtrip/worker -- raw-capture-store`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `envelope.ts`, `raw-capture-store.ts`, `input-bundle.ts`**

Read synchronously (`readFileSync`); the orchestrator is a batch job and streaming buys nothing against a 60-page capture. `payload` stays `unknown` — per-adapter parsing owns its shape.

`parseEnvelope` owns Review Focus #5. A bare `JSON.parse` rounds an integer wider than IEEE-754 exact range, while Kotlin's `JsonElement` keeps the literal digits, so such a value in an opaque passthrough column would be silent permanent corruption that the diff reports as an unexplainable mismatch. Preserve the digits: a `JSON.parse` reviver cannot see the raw text, so use a source-text-preserving parse for integers outside `Number.MIN_SAFE_INTEGER..Number.MAX_SAFE_INTEGER` — carry them as `BigInt` and have the writer render them unquoted, or carry the raw literal in a wrapper the opaque encoder emits verbatim. Whichever is chosen, `JSON.stringify` of the parsed payload must reproduce the original digits, and the codec's opaque passthrough must accept the carrier. The sampled captures contain no such value; that is a sample over three pages per source, not a proof, and the cost of being wrong is undetectable corruption rather than a failure.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm run test --workspace @roadtrip/worker -- raw-capture-store input-bundle`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add packages/worker/src/etl
git commit -m "feat(worker): mirror RawCaptureStore, Envelope and InputBundle"
```

---

### Task 3: The write layer

**Files:**
- Create: `packages/worker/src/repo/{bulk,poi-repo,campground-repo,campsite-repo,tesla-repo,planet-fitness-repo,import-run-repo}.ts`
- Create: `packages/worker/src/model/{campground-candidate,campsite-candidate,tesla-candidate,planet-fitness-candidate}.ts`
- Create: `packages/domain/src/provider.ts`; Modify: `packages/domain/src/index.ts`
- Test: `packages/worker/src/repo/catalog-write.test.ts`, `packages/domain/src/provider.test.ts`
- Reference: `backend/src/main/kotlin/ca/floo/roadtrip/repo/{CampgroundRepo,CampsiteRepo,TeslaSuperchargerRepo,PlanetFitnessLocationRepo,PoiRepo,ImportRunRepo,SqlBindSupport}.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/*UpsertCandidate.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/provider/*.kt`

**Interfaces:**
- Consumes: `Db` and the codec from `@roadtrip/db`. Also, from Task 2's `packages/worker/src/etl/envelope.ts`, the opaque-payload carrier: `type OpaquePayloadJson` (a unique-symbol branded string), `serializeOpaquePayload(value: unknown): OpaquePayloadJson`, `type WideNumber`, `isWideNumber(value): value is WideNumber`, and `RequestMeta` / `ResponseMeta`. **Every opaque passthrough column is written by passing `serializeOpaquePayload`'s branded result to the JSONB parameter.** A parsed payload may contain `WideNumber` carriers for integers wider than IEEE-754 exact range; `structuredClone` throws on them, and an object spread or a `JSON.parse(JSON.stringify(x))` round trip corrupts them silently. So the opaque write path takes the payload as parsed and never copies it. Type the repo parameter as `OpaquePayloadJson`, not `string`, so a bare `JSON.stringify` at a call site fails to typecheck.
- Produces, in `@roadtrip/domain`:
  - `const DataProvider = { CAMPFLARE: 'campflare', RECGOV: 'recgov', ASPIRA: 'aspira', STRAPI: 'bcparks-strapi', RESERVEAMERICA: 'reserveamerica', RESERVECALIFORNIA: 'reservecalifornia', TESLA_SUPERCHARGER: 'tesla_supercharger', PLANET_FITNESS_LOCATION: 'planet_fitness_location' } as const` plus `dataProviders`, following `amenity.ts`'s `memberList` idiom
  - `type DataProviderRef = { provider: DataProvider; serialize(): string }` with one constructor per `DataProviderRef.kt` variant and `parseDataProviderRef(provider, ref)`
  - the same for `BookingProviderRef` / `parseBookingProviderRef`
- Produces, in `@roadtrip/worker`:
  - `interface CampgroundUpsertCandidate` / `CampsiteUpsertCandidate` / `TeslaSuperchargerUpsertCandidate` / `PlanetFitnessLocationUpsertCandidate` — field for field with the Kotlin data classes, same optionality
  - `interface FlushCounts { upserted: number; skipped: number }`
  - `upsertCampgroundBatch(db: Db, records: CampgroundUpsertCandidate[]): Promise<number>`
  - `upsertCampsiteBatch(db: Db, records: CampsiteUpsertCandidate[]): Promise<FlushCounts>`
  - `upsertTeslaSuperchargerBatch`, `upsertPlanetFitnessLocationBatch` with the same shape as the campground one
  - `startImportRun(db, source): Promise<bigint>`, `completeImportRun(db, runId, seenCount)`, `failImportRun(db, runId, notes)`
  - `MAX_CATALOG_UPSERT_BATCH_SIZE = 1000`, `BULK_CHUNK_SIZE = 500`

Each upsert reproduces its Kotlin counterpart's column list, conflict target (`(data_provider, data_provider_ref) WHERE deleted_at IS NULL` for campgrounds and campsites; the plain unique index for the other two), update set, `updated_at = now()`, `deleted_at = NULL`, `distinctBy` dedupe keeping the first occurrence, and `BULK_CHUNK_SIZE` chunking. Campground upsert also inserts or updates the `pois` row and the `poi_campgrounds` link exactly as `bulkUpsertCampgroundPois` does: existing links get `ST_SetSRID(ST_MakePoint(lng, lat), 4326)`, new ones insert a POI of type `campground` then link `ON CONFLICT (campground_id) DO NOTHING`. Write geometry with `ewkt(\`POINT(${lng} ${lat})\`)` from `@roadtrip/db`. Campsite upsert resolves parents by `(data_provider, data_provider_ref)` and counts a record with no parent ref, or an unresolved one, as skipped rather than failing.

`onConflictDoUpdate` accepts `targetWhere` (checked against Drizzle's insert documentation on 2026-10-28; `setWhere` exists too and is not needed here), so the partial unique index is expressible as `onConflictDoUpdate({ target: [t.dataProvider, t.dataProviderRef], targetWhere: sql\`deleted_at IS NULL\`, set: { … } })`. Confirm it compiles against the installed 0.45.4; only if it does not, build the statement with the `sql` template — never by widening the conflict target.

Column-by-column JSONB handling, which is the whole point of this task:

| Column | How it is written |
| --- | --- |
| `campgrounds.{location, default_campsite_schedule, price, management, contact, metadata}` | its `columnObjectOf` shape — absent encodes to `{}` |
| `campgrounds.geometry_provenance` | `geometryProvenanceShape` — absent encodes to SQL `NULL` |
| `campgrounds.{amenities, links, photos, alerts, cell_service, booking_aliases}` and `campsites.{equipment, photos, attributes, booking_aliases}` | the array shape |
| `campgrounds.{connections, source_payload}`, `campsites.{price, schedule, source_payload}`, `tesla_superchargers.*`, `planet_fitness_locations.*` | the opaque passthrough: `serializeOpaquePayload` from Task 2 for a value that came from a capture payload, `sourcePayloadShape` for one the adapter built itself; absent becomes `{}` for object columns and `[]` for array ones, matching `jsonObject` / `jsonArray` |

- [ ] **Step 1: Write the failing tests**

A vitest suite that boots one PostGIS database through `withPostgis` + `migrateDb`, exactly as `packages/db/src/codec/catalog-json.test.ts` does.

```ts
test('an upserted campground stores the shape Kotlin stores', async () => {
  await upsertCampgroundBatch(db, [candidate]);
  const [row] = await db.execute(sql`SELECT amenities::text a, price::text p, metadata::text m,
    contact::text c, geometry_provenance gp, connections::text cn
    FROM campgrounds WHERE data_provider_ref = ${REF}`);
  expect(JSON.parse(row.a)).toEqual([{ key: 'showers' }, { key: 'wifi', present: false }]);
  expect(JSON.parse(row.p)).toEqual({ minimum: 12.5 });
  expect(JSON.parse(row.m)).toEqual({ activities: ['hiking'] });
  expect(JSON.parse(row.c)).toEqual({});
  expect(row.gp).toBeNull();
  expect(JSON.parse(row.cn)).toEqual({});
});

test('a campsite price and schedule are stored verbatim, not through the campground shapes', async () => {
  await upsertCampsiteBatch(db, [campsiteWith({
    price: { per_night: 32, currency_code: 'USD', uniform: true },
    schedule: { check_in_time: '15:00' },
  })]);
  const [row] = await db.execute(sql`SELECT price::text p, schedule::text s FROM campsites WHERE data_provider_ref = ${REF}`);
  expect(JSON.parse(row.p)).toEqual({ per_night: 32, currency_code: 'USD', uniform: true });
  expect(JSON.parse(row.s)).toEqual({ check_in_time: '15:00' });
});

test('a duplicate provider ref inside one batch keeps the first and does not error', async () => {
  await upsertCampgroundBatch(db, [named('First'), named('Second')]);
  const [row] = await db.execute(sql`SELECT name FROM campgrounds WHERE data_provider_ref = ${DUP_REF}`);
  expect(row.name).toBe('First');
});

test('re-upserting the same ref updates in place and clears deleted_at', async () => {
  await db.execute(sql`UPDATE campgrounds SET deleted_at = now() WHERE data_provider_ref = ${REF}`);
  await upsertCampgroundBatch(db, [candidate]);
  const rows = await db.execute(sql`SELECT deleted_at FROM campgrounds WHERE data_provider_ref = ${REF}`);
  expect([...rows]).toHaveLength(1);
  expect([...rows][0].deleted_at).toBeNull();
});

test('a campground gets one POI, and a second upsert moves it rather than making another', async () => {
  await upsertCampgroundBatch(db, [at(1, 1)]);
  const first = await poiFor(REF);
  await upsertCampgroundBatch(db, [at(2, 2)]);
  expect(await poiFor(REF)).toMatchObject({ id: first.id, lng: 2, lat: 2 });
  expect(await countPois()).toBe(1);
});

test('a campsite with no resolvable parent is skipped, not written', async () => {
  const counts = await upsertCampsiteBatch(db, [orphan, resolvable]);
  expect(counts).toEqual({ upserted: 1, skipped: 1 });
});

test('a non-finite coordinate fails the batch instead of storing a broken point', async () => {
  await expect(upsertCampgroundBatch(db, [at(Number.NaN, 0)])).rejects.toThrow();
});

test('an integer wider than IEEE-754 exact range in source_payload is stored with its digits intact', async () => {
  await upsertCampgroundBatch(db, [withSourcePayload(JSON.parse('{"id":72157625256027958}'))]);
  const [row] = await db.execute(sql`SELECT source_payload::text sp FROM campgrounds WHERE data_provider_ref = ${REF}`);
  expect(row.sp).toContain('72157625256027958');
});
```

The last test is the end-to-end half of Review Focus #5; Task 2's `parseEnvelope` already carries the parse-side half, so this one should be green on first run. If it is not, the gap is in Task 2's carrier or in the codec's opaque passthrough accepting it — fix it there, not by special-casing this repo layer.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm run test --workspace @roadtrip/worker -- catalog-write`
Expected: FAIL — no `upsertCampgroundBatch`.

- [ ] **Step 3: Implement `packages/domain/src/provider.ts` and export it**

Port `DataProvider`, `DataProviderRef` and `BookingProviderRef` including the two-and-one-segment Aspira / BC Parks parse branches and the `"unknown"` / `"null"` sentinels. A unit test asserts `serialize` round-trips every variant and that `parseDataProviderRef('aspira', 'wa:123')` yields the campsite variant while `'123:456'` yields the campground one.

- [ ] **Step 4: Implement the candidates and the repos**

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm run test --workspace @roadtrip/worker -- catalog-write && npm run test --workspace @roadtrip/domain`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add packages/domain/src packages/worker/src/repo packages/worker/src/model
git commit -m "feat(worker): port the catalog write layer and the provider refs"
```

---

### Task 4: The orchestrator, transform context and shared helpers

**Files:**
- Create: `packages/worker/src/etl/{source-etl,transform-ctx,batch,orchestrator,html-text,geojson,campsite-kinds,adapter-registry}.ts`
- Create: `packages/worker/src/cli/import.ts`
- Test: `packages/worker/src/etl/{orchestrator,campsite-kinds,html-text}.test.ts`
- Reference: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/{EtlOrchestrator,TransformCtx,TerminalEtlBinding,SourceEtl,CampsiteKinds,HtmlText,GeoJson,ProductionTerminalEtlRegistry}.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/CampsiteKindsTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `type ParseResult<DTO> = { ok: true; dto: DTO } | { ok: false; sourceId?: string; errors: string[] }`, and `TransformResult<OUT>` with `record` in place of `dto`
  - `interface SourceEtl<DTO, OUT> { etlSlug: string; parse(inputs: InputBundle): Iterable<ParseResult<DTO>>; transform(dto: DTO, ctx: TransformCtx): Iterable<TransformResult<OUT>> }`
  - `class TransformCtx { static load(rawDir: string, registry: PoiRegistry): TransformCtx; readonly rawDir: string; subcategoryFor(slug): string | undefined; agencyFor(slug): AgencyConfig | undefined; requiredConstantAgency(slug): string; argFor(slug, key): string | undefined }`
  - `interface TerminalSink<OUT> { batchSize: number; write(records: OUT[]): Promise<FlushCounts> }`
  - `interface TerminalEtlBinding<DTO, OUT> { etl: SourceEtl<DTO, OUT>; sink(db: Db): TerminalSink<OUT> }`
  - `interface VendorKind { kind: CampsiteKind; electric?: boolean }`
  - `class BatchAccumulator<OUT> { add(record: OUT): Promise<FlushCounts>; flush(): Promise<FlushCounts> }`
  - `interface EtlStats { name: string; etlSlug: string; runId: bigint; parsed: number; transformed: number; upserted: number; skipped: number }`
  - `runTarget(target: ImportTarget, deps: { db: Db; store: RawCaptureStore; registry: PoiRegistry; rawDir: string }): Promise<EtlStats>`
  - `adapterRegistry: Record<string, (entry: EtlEntry) => TerminalEtlBinding>` — keyed by the YAML `adapter` name, empty at the end of this task. Kotlin keys its two factory maps the same way (`poiAdapters`, `campsiteAdapters`) and keeps a *separate* slug-keyed map (`productionTerminalEtlDefinitions`) for the implemented set.
  - `implementedSlugs(registry: PoiRegistry, adapters: typeof adapterRegistry): Set<string>` — the etl slugs whose adapter name is registered. `importTargets` compares its second argument against etl **slugs**, not adapter names, so passing `Object.keys(adapterRegistry)` straight in yields zero targets for every production row even once all eight adapter tasks are done. That is how Kotlin's `RegistryTargets` works too: its `implemented` is `EtlOrchestrator.registry.keys`, which is slug-keyed.
  - `stripTags(value: string): string`, `pointGeoJson(lng: number, lat: number): string`
  - `campsiteKindRecGov(type?: string): VendorKind`, `campsiteKindCampflare`, `campsiteKindAspira`, `campsiteKindReserveCalifornia`

The counting arithmetic must match `EtlOrchestrator.runTerminal` exactly, because `import_runs.seen_count` is a diffed column: `seen = transformOk + parseBad + transformBad`, `skipped = parseBad + transformBad + repoSkipped`. A terminal that throws marks the run failed and rethrows.

The CLI takes `--raw-dir`, `--static-dir`, `--database-url`, repeatable `--target <name>`, and `--list`. With no `--target` it runs every target from `importTargets(registry, implementedSlugs(registry, adapterRegistry))`, sequentially, in registry order.

`stripTags` must reproduce **Java's** whitespace semantics, not JavaScript's. Kotlin's `Regex("\\s+")` compiles to Java's `\s`, which is ASCII-only (`[ \t\n\x0B\f\r]`), and `String.trim()` uses `Character.isWhitespace`, which excludes U+00A0. JavaScript's `\s` and `String.prototype.trim` both include it. So a naive port rewrites every no-break space in a description, and the newest `recgov-campgrounds` capture alone holds 659 non-ASCII whitespace characters with 46 more in Campflare's — all flowing into `short_description`, `medium_description` and `long_description`, which the diff compares. Port the Java behaviour and pin it with a test containing U+00A0.

- [ ] **Step 1: Write the failing tests**

```ts
test('counts follow the Kotlin arithmetic', async () => {
  const stats = await runTarget(targetFor(fakeEtl({ parseBad: 2, transformBad: 3, ok: 10 })), deps);
  expect(stats).toMatchObject({ parsed: 15, transformed: 10, upserted: 10, skipped: 5 });
});

test('a sink that reports skips rolls them into the run skipped count', async () => {
  const stats = await runTarget(targetFor(fakeEtl({ ok: 4 }), sinkSkipping(1)), deps);
  expect(stats).toMatchObject({ transformed: 4, upserted: 3, skipped: 1 });
});

test('the accumulator flushes at the sink batch size and once more at the end', async () => {
  const written: number[] = [];
  const acc = new BatchAccumulator({ batchSize: 2, write: async (r) => { written.push(r.length); return NONE; } });
  for (const r of [1, 2, 3]) await acc.add(r);
  await acc.flush();
  expect(written).toEqual([2, 1]);
});

test('a throwing terminal marks the import run failed and rethrows', async () => {
  await expect(runTarget(targetFor(throwingEtl), deps)).rejects.toThrow('boom');
  expect(await runStatus()).toBe('failed');
});
```

Plus `campsite-kinds.test.ts`, ported case for case from `CampsiteKindsTest.kt` — these are genuine algorithm tests and spec §8.2 keeps them. Include the ordering cases the Kotlin comments single out: `"Backcountry Cabin"` is a cabin, `"Equestrain Group Tent Primitive Campsite"` is a group site, `"Tent Only - Walk-In"` is a walk-in, rec.gov's trailing `ELECTRIC` / `NONELECTRIC` token sets `electric`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm run test --workspace @roadtrip/worker -- orchestrator campsite-kinds`
Expected: FAIL

- [ ] **Step 3: Implement the framework modules and the CLI**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm run test --workspace @roadtrip/worker`
Expected: PASS

- [ ] **Step 5: Verify the CLI reports an empty adapter registry rather than silently doing nothing**

Run: `node packages/worker/dist/cli/import.js --raw-dir data/raw --static-dir . --list`
Expected: exits non-zero with "no adapters registered", not an empty list and exit 0.

- [ ] **Step 6: Commit**

```bash
git add packages/worker/src/etl packages/worker/src/cli
git commit -m "feat(worker): port the ETL orchestrator, transform context and shared helpers"
```

---

### Task 5: The differential harness, proven red

**Files:**
- Create: `scripts/importer-differential.mjs`, `scripts/importer-differential.test.mjs`, `scripts/importer-diff-columns.mjs`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/tools/CatalogImportCli.kt`
- Modify: `scripts/schema-reference.mjs` and `scripts/schema-reference.d.mts` (add `exec` and `containerName` to `PostgisSession`), `build.gradle.kts` (a `JavaExec` task)
- Reference: `scripts/schema-equivalence.mjs` for the gate's style; `backend/src/main/kotlin/ca/floo/roadtrip/repo/JooqUnitOfWork.kt`, `.../service/etl/framework/{EtlOrchestrator,ProductionTerminalEtlRegistry,RegistryTargets}.kt`

**Interfaces:**
- Consumes: Task 4's CLI; `withPostgis` from `scripts/schema-reference.mjs`.
- Produces:
  - from `importer-diff-columns.mjs`: `DIFFED_TABLES` — for each of `campgrounds`, `campsites`, `tesla_superchargers`, `planet_fitness_locations`, `pois`, `import_runs`: its key columns, its ignored columns with a reason per entry, and (for `pois`) the link table and owner key it joins through — and `diffSql(table)`, the one statement per table whose compared column list is derived from `getTableConfig`
  - from `importer-differential.mjs`: `runDifferential({ rawDir, staticDir, targets, sources, referenceDump, maxDifferences, emitSeed })` → `{ differences: Difference[], counts: Record<string, number> }`, and `interface Difference { table: string; key: string[]; kind: 'missing_in_kotlin' | 'missing_in_typescript' | 'differs'; columns: string[] }`
  - the CLI flags `--target <name>` (repeatable), `--source <slug>` (repeatable; restricts which `data_source` captures *both* importers read, for Task 14's CI subset fallback — it must constrain the Kotlin side too or the diff is meaningless), `--reference-dump <path>`, `--max-differences <n>` (default 50), `--emit-seed <path>` (after a clean diff, `pg_dump` the TypeScript database plus a provenance sidecar, for spec §8.1's seed), and `--passes <n>` (default 1)
  - `--passes <n>` runs **each importer n times over the same captures** before the rows are diffed. At `n = 2` this proves the `ON CONFLICT` branch is reached and is idempotent — no duplicated rows, no widened conflict target, no second POI per campground — at full scale. It does **not** prove the update carries values: `.values(chunk.map(campgroundRow))` writes every column on insert regardless of `CONFLICT_UPDATE_KEYS`, so with identical captures a dropped update key leaves the correct pass-1 value in place and the rows stay byte-identical. Do not write an assertion that assumes otherwise.
  - `--prove-update-keys` is what proves the update writes. After pass 1 it blanks every updatable column **in both databases**, then runs pass 2; each side must restore every column from the captures, so a column absent from one implementation's update set stays blank on that side alone and the diff names it. Blanking is generated from `getTableConfig` so it cannot drift from the schema: identity and key columns are left alone, nullable columns go to `NULL`, `jsonb` object columns to `'{}'` and array columns to `'[]'` (satisfying their `jsonb_typeof` CHECKs), and a non-nullable scalar goes to a fixed sentinel that satisfies its own CHECK — `name` and `status` carry `nonBlank` checks, so the sentinel is a non-empty string. The mode fails loudly if blanking left any row unchanged, since a blank step that blanked nothing makes the whole run vacuous.
  - `--reference-dump` caches the Kotlin side per mode; a dump taken under one mode must not be restored under another, so the pass count and `--prove-update-keys` both join the provenance key.
  - Gradle: `./gradlew :backend:importCatalog --args="--jdbc-url <url> --raw-dir <dir> --static-dir <dir> [--target <name>] [--source <slug>]"`

How it runs:

0. `npm run build` first, so `packages/worker/dist` is not stale — the same reason `make test` builds before the schema-equivalence lines. The harness refuses to run if `dist/cli/import.js` is missing.
1. `withPostgis` → one container. `createDatabase('roadtrip_kotlin')` + `migrateFlyway`, `createDatabase('roadtrip_ts')` + `migrateDb`.
2. Kotlin side: `./gradlew :backend:importCatalog` against `roadtrip_kotlin`, repeated `--passes` times, unless `--reference-dump <path>` names a dump whose provenance matches, in which case restore it.
3. TypeScript side: `node packages/worker/dist/cli/import.js` against `roadtrip_ts`, repeated `--passes` times.
4. In `roadtrip_ts`: `CREATE SCHEMA reference` and, per diffed table, `CREATE TABLE reference.<t> (LIKE public.<t>)`, then stream the rows across with `psql -d roadtrip_kotlin -c "COPY public.<t> TO STDOUT (FORMAT binary)" | psql -d roadtrip_ts -c "COPY reference.<t> FROM STDIN (FORMAT binary)"` inside the container. Binary COPY preserves `jsonb` exactly and needs no extension.

**This mechanism was verified end to end against `postgis/postgis:16-3.4` on 2026-10-28, before this task was written; do not re-derive it.** One container, two databases, a `jsonb` column holding `12.0` on one side and `12` on the other, a 17-digit integer, and a geometry written the Kotlin way (`ST_SetSRID(ST_MakePoint(-122.25, 44.5), 4326)`) on one side against the TypeScript way (`'SRID=4326;POINT(-122.25 44.5)'`) on the other. Results: `CREATE TABLE … (LIKE …)` and the piped binary `COPY` both work; `k.j::text <> t.j::text` is **true** while `k.j <> t.j` is **false**, so the ruling's premise is real and the `jsonb` operator does paper over it; `ROW(k.j, k.g) IS DISTINCT FROM ROW(t.j, t.g)` is **false**, so the diff's central predicate genuinely uses `jsonb` and geometry semantics rather than text; `ST_AsBinary(k.g) = ST_AsBinary(t.g)` is **true**, so `ST_MakePoint` and an EWKT literal produce byte-identical geometry and the two writers cannot diverge on coordinate rendering; and the 17-digit integer survives the COPY intact. Each database needs `CREATE EXTENSION IF NOT EXISTS postgis` before a `geometry` column can be declared — the image does not create it, which is why `migrateDb` does.
5. Diff with one `FULL OUTER JOIN … USING (<key>)` per table whose predicate is `k.<key> IS NULL OR t.<key> IS NULL OR ROW(k.<cols>) IS DISTINCT FROM ROW(t.<cols>)`. `ROW(...) IS DISTINCT FROM ROW(...)` applies each column's own `=`, so `jsonb` columns compare as `jsonb`. For each differing row, a second query names the columns where `k.<c> IS DISTINCT FROM t.<c>`.
6. Report the first `--max-differences` (default 50) with table, key, kind (`missing_in_kotlin` / `missing_in_typescript` / `differs`) and the differing column names. Exit non-zero on any difference.

The compared column list for a table is `getTableConfig(table).columns` minus `IGNORED`, which is `id`, `created_at`, `updated_at` everywhere, plus `campsites.campground_id` (a surrogate FK; the parent is compared through its own natural key by joining `campgrounds`), plus `import_runs.{id, started_at, completed_at}`. Keys: `(data_provider, data_provider_ref)` for `campgrounds` and `campsites`, `location_slug` for `tesla_superchargers`, `location_id` for `planet_fitness_locations`, `source` for `import_runs` (one run per slug in a fresh database). `pois` is compared through each of its three link tables, keyed on the owner's natural key, with `poi_id` and the owning id ignored.

**The diff must prove its keys are unique before it trusts them.** `campgrounds` and `campsites` are unique on `(data_provider, data_provider_ref)` only *among live rows* — the index is partial on `WHERE deleted_at IS NULL`, so a soft-deleted row and a live row can share a natural key, and Task 3 found that re-upserting a soft-deleted row inserts a second one rather than reviving it. A fresh differential database soft-deletes nothing, so the keys are in fact unique there; but a `FULL OUTER JOIN … USING (key)` over a duplicated key silently becomes a cross join and reports nonsense. So, per table, before diffing: assert on both sides that no key value appears twice, and fail with the offending key if one does. That assertion is cheap, and without it the harness's central claim rests on an invariant nobody checked.

Provenance for `--reference-dump`: a sibling `.provenance.json` holding `git rev-parse HEAD:data/raw`, `git rev-parse HEAD:backend/src/main/kotlin/ca/floo/roadtrip/service/etl`, the same for `.../repo` and `.../model/domain`, and `git hash-object backend/src/main/resources/poi-registry.yaml`. A mismatch, or any uncommitted change under those paths, refuses the cache and says why.

`CatalogImportCli.kt` is ~60 lines: build a `DSLContext` over the JDBC URL, take `JooqUnitOfWork(ctx).autocommit`, build `EtlOrchestrator(ImportRunRepo(ctx), rawDir, PoiRegistry.loadResource("poi-registry.yaml"), staticDir, productionEtlRegistry(repos))`, walk `importTargetsFromRegistry(registry)` in order calling `runPoiData` or `runCampsiteData` per section, print one summary line per target, exit non-zero on the first failure.

- [ ] **Step 1: Write the failing test**

```js
test('a differential over two databases built from the same rows reports nothing', async () => { … });

test('a single changed scalar is reported with its table, key and column', async () => {
  await ref(`UPDATE reference.campgrounds SET name = 'Changed' WHERE data_provider_ref = $1`, REF);
  const { differences } = await diffOnly();
  assert.deepEqual(differences, [{ table: 'campgrounds', key: ['test', REF], kind: 'differs', columns: ['name'] }]);
});

test('a jsonb value differing only in numeric rendering is NOT reported', async () => {
  await ref(`UPDATE reference.campgrounds SET price = '{"minimum": 12.0}'::jsonb WHERE data_provider_ref = $1`, REF);
  await ts(`UPDATE public.campgrounds SET price = '{"minimum": 12}'::jsonb WHERE data_provider_ref = $1`, REF);
  assert.deepEqual((await diffOnly()).differences, []);
});

test('the same pair compared as text WOULD be reported', async () => {
  assert.notDeepEqual(await textDiff('campgrounds', 'price'), []);
});

test('a row present on one side only is reported with the right kind', async () => { … });

test('every diffed table names every one of its columns as compared or ignored', () => {
  for (const spec of DIFFED_TABLES) {
    const declared = new Set([...spec.compared, ...Object.keys(spec.ignored)]);
    assert.deepEqual(columnsOf(spec.table).filter((c) => !declared.has(c)), []);
    assert.deepEqual([...declared].filter((c) => !columnsOf(spec.table).includes(c)), []);
  }
});

test('a run over a raw dir with no captures fails instead of reporting a clean diff', async () => {
  await assert.rejects(runDifferential({ rawDir: emptyDir, … }), /no captures/);
});

test('a run whose TypeScript side wrote zero rows fails instead of reporting a clean diff', async () => { … });
```

Tests 3 and 4 together are the proof that the ruling is implemented and that the harness would have caught the opposite choice. Tests 7 and 8 are Review Focus #1.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `node --test scripts/importer-differential.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Add `exec` and `containerName` to `withPostgis`**

Run: `node --test scripts/schema-reference.test.mjs`
Expected: PASS — the existing suite still green after the addition.

- [ ] **Step 4: Implement `CatalogImportCli.kt` and the Gradle task**

Run: `./gradlew :backend:ktlintCheck :backend:detekt`
Expected: PASS

- [ ] **Step 5: Implement the harness and the diff**

- [ ] **Step 6: Run the harness tests to verify they pass**

Run: `node --test scripts/importer-differential.test.mjs`
Expected: PASS

- [ ] **Step 7: Prove the harness is red end to end, over one real target**

Run: `node scripts/importer-differential.mjs --target 'Planet Fitness'`
Expected: exits non-zero reporting every Planet Fitness row as `missing_in_typescript` — no adapters are registered yet. Record the row count in the commit message; Task 6 turns it to zero.

- [ ] **Step 8: Commit**

```bash
git add scripts/importer-differential.mjs scripts/importer-differential.test.mjs scripts/importer-diff-columns.mjs \
        scripts/schema-reference.mjs scripts/schema-reference.d.mts \
        backend/src/main/kotlin/ca/floo/roadtrip/tools/CatalogImportCli.kt build.gradle.kts
git commit -m "test: add the importer differential harness, red against an unported importer"
```

---

### Tasks 6–13: the vendor adapters

These eight tasks share one shape, so it is written once here. Each ends on a differential run over `data/raw`, which is present in this worktree; their unit tests do not need it.

Each task:

1. Ports the named Kotlin files to `packages/worker/src/vendors/<vendor>/`, one TypeScript module per Kotlin file, same names in camelCase.
2. Registers the adapter(s) in `adapterRegistry` under the YAML `adapter` name, with the sink its Kotlin counterpart uses.
3. Adds a unit test **only** where the logic is an algorithm with edge cases the diff cannot localize — spec §8.2's rule. The per-slug differential is the coverage for everything else; do not reproduce the Kotlin test distribution.
4. Ends with its slugs' differential clean.

The steps, per task, with `<TARGET>` standing for the task's registry row names:

- [ ] **Step 1: Run the differential for `<TARGET>` and record that it is red**

Run: `node scripts/importer-differential.mjs --target '<TARGET>'`
Expected: non-zero, every row `missing_in_typescript`.

- [ ] **Step 2: Write the unit tests this task owns** (listed per task below; none for some)
- [ ] **Step 3: Run them to verify they fail**
- [ ] **Step 4: Port the Kotlin files**
- [ ] **Step 5: Run the unit tests to verify they pass**
- [ ] **Step 6: Run the differential for `<TARGET>` to verify it is clean**

Run: `node scripts/importer-differential.mjs --target '<TARGET>' --reference-dump <cached>`
Expected: `0 differences` across every diffed table.

- [ ] **Step 7: Commit**

---

### Task 6: Planet Fitness and Tesla Superchargers

**Targets:** `Planet Fitness`, `Tesla Superchargers`
**Files:** Create `packages/worker/src/vendors/osmpf/planet-fitness.ts`, `packages/worker/src/vendors/tesla/{index-etl,dto}.ts`
**Reference:** `.../vendors/osmpf/*.kt` (179 LOC), `.../vendors/tesla/*.kt` (~330 LOC)

Both write their own tables and their own POI type, so this task proves the whole pipe — registry → capture store → orchestrator → repo → POI link — on the two simplest adapters before any campground exists.

`TeslaIndexEtl` side-loads `data/raw/tesla-locations/<slug>/<newest>.json` from `ctx.rawDir` rather than through `InputBundle`; it is not a declared input. Its unit test asserts that a slug directory with no `.json` file is skipped rather than throwing, and that the newest file per slug is the one read.

### Task 7: Campflare

**Targets:** `Campflare Campgrounds`, `Campflare Campsites`
**Files:** `packages/worker/src/vendors/campflare/{campgrounds,campsites,fields,bags,urls,support}.ts`
**Reference:** `.../vendors/campflare/*.kt` (515 LOC)

The largest row volume: ~10,963 campgrounds and ~299,904 campsites. This is the task that exercises `MAX_CATALOG_UPSERT_BATCH_SIZE` chunking for real, and it is where the campsite raw-`price`/`schedule` ruling has visible consequences — a campsite row here carries `per_night`, `currency_code`, `check_in_time` and `uniform`, and the diff reports it if they are dropped.

Unit tests: `CampflareCampgroundBagsTest` and `CampflareCampgroundFieldsTest` are field-mapping tables worth porting; the rest is covered by the diff.

### Task 8: Rec.gov

**Targets:** `Rec.gov Campgrounds`, `Rec.gov Campsites`
**Files:** `packages/worker/src/vendors/recgov/{campgrounds,campsites,dto}.ts`
**Reference:** `.../vendors/recgov/*.kt` (696 LOC)

`recgov-campgrounds` is 120 paged envelopes; `recgov-campground-enrichment` is 4,006 per-facility envelopes and `recgov-campsites` 4,001, both read in code-unit name order. The campground adapter derives `management.agency` from the registry's `agency.derived_from_field: ORGANIZATION[0].OrgName`, so this task is where `TransformCtx.agencyFor`'s derived branch gets used.

Unit test: the `ORGANIZATION[0].OrgName` path expression, including a facility with no organization.

### Task 9: Aspira shared machinery

**Targets:** none — this task registers no adapter and its gate is its unit tests.
**Files:** `packages/worker/src/vendors/aspira/{leaf,leaves-walk,leaf-match,leaf-matcher,geometry-point,named-point,geometry-source,geometry-sources,geometry-index,normalize,inventory-categories,booking-cta-refs,us-campgrounds-csv,geojson-features,arcgis-centroid,join-dto}.ts`
**Reference:** `.../vendors/aspira/` minus the two ETL files (~650 LOC)

The subtle one. Three ordering rules must survive the port exactly, because a reordering changes which row wins and the diff will say so without saying why:

- `GeometryIndex.build` walks sources in declared order and the **first** point to claim a normalized name keeps it (`LinkedHashMap.putIfAbsent`). A `Map` in JavaScript preserves insertion order the same way; use `if (!map.has(k)) map.set(k, v)`.
- `AspiraLeafMatcher.match` scores every index entry and keeps the **first** maximum (`score > bestScore`, not `>=`), so ties resolve by index order.
- `GeometryIndex.byNormalizedName` drops blank keys and is first-writer-wins, and the metadata index is built by the same function so the two cannot drift.

Unit tests, ported from `GeometryIndexTest`, `AspiraLeafMatcherTest`, `GeometrySourcesTest` and `UsCampgroundsCsvSourceTest`: the first-writer-wins rule, the tie-break, the fuzzy threshold boundary, the parent fallback, the two leaf gates (no `resourceLocationId` → container, non-bookable `resourceLocationId` → activity mount), `buildOrFail` on an empty index, and `isOnEarth` rejecting `NaN`, `Infinity` and out-of-range latitude and longitude (Review Focus #4).

### Task 10: Aspira and BC Parks campgrounds

**Targets:** `Washington State Parks`, `BC Provincial Parks`, `Parks Canada`
**Files:** `packages/worker/src/vendors/aspira/campgrounds.ts`, `packages/worker/src/vendors/bcparks/{campgrounds,strapi-source,dto}.ts`
**Reference:** `.../vendors/aspira/AspiraCampgroundsEtl.kt` (256), `.../vendors/bcparks/*.kt` (226)

This is where `geometry_provenance` is written, and so where the `'sql-null'` null encoding is exercised against real rows: a matched leaf stores `{match_kind, source, matched_name, score?}`, an unmatched one stores SQL `NULL`, never `{}`. The diff compares that column as `jsonb`, and `NULL IS DISTINCT FROM '{}'::jsonb` is true, so the distinction is caught.

### Task 11: Aspira campsites

**Targets:** `Washington Aspira Resources`, `BC Aspira Resources`, `Parks Canada Aspira Resources`
**Files:** `packages/worker/src/vendors/aspira/campsites.ts`
**Reference:** `.../vendors/aspira/AspiraCampsitesEtl.kt` (678) — the single largest file in the tree

It takes `mapsInputSlug`, `inventoryInputSlug`, optional `dictionariesInputSlug`, `aspiraTenant` and `parentDataProvider` from the registry `args`; BC passes `parent_data_provider: bcparks-strapi`, so its campsites resolve parents written by `BcParksCampgroundsEtl` under `DataProvider.STRAPI`, not under `ASPIRA`. Getting that wrong skips every BC campsite, which the diff reports as ~every BC row missing.

### Task 12: ReserveAmerica

**Targets:** `Alberta Provincial Parks`, `New York State Parks`, `Alberta Provincial Park Sites`, `New York State Park Sites`
**Files:** `packages/worker/src/vendors/reserveamerica/{campgrounds,sites,dto,parsed-park,catalog-parser}.ts`
**Reference:** `.../vendors/reserveamerica/*.kt` (386) and `backend/src/main/kotlin/ca/floo/roadtrip/client/reserveamerica/ReserveAmericaCatalogParser.kt` (39)

The one `client/` class the ETL tree reaches. Both tenants run the same adapter with a different `contract` arg, so the `contract` → `BookingProviderRef.ReserveAmerica(contractCode, parkId)` rendering is what the diff checks; a wrong contract code changes `booking_provider_ref` on every row.

Unit test: `ReserveAmericaCatalogParser` against the smallest real capture page, asserting the parsed park list.

### Task 13: ReserveCalifornia

**Targets:** `California State Parks`, `California State Park Sites`
**Files:** `packages/worker/src/vendors/reservecalifornia/{campgrounds,sites,catalog,facility,place,unit,grid-catalog}.ts`
**Reference:** `.../vendors/reservecalifornia/*.kt` (~400)

Both targets read the same `reservecalifornia-catalog` input (823 envelopes). `BookingProviderRef.ReserveCalifornia` serializes as `placeId:facilityId,facilityId,…`, so the facility-id order is a stored value; preserve the Kotlin's list order rather than sorting.

---

### Task 14: The full set, the gates and the seed dump

**Files:**
- Modify: `Makefile` (new `importer-diff` target), `.github/workflows/ci.yml` (new `importer-differential` job and path filter)
- Create: `docs/superpowers/2026-10-28-step-4-rulings.md`
- Reference: `.github/workflows/ci.yml`'s `schema-equivalence` job for the job's shape

- [ ] **Step 1: Run the full differential over every target, all three ways**

Run, timing each: `node scripts/importer-differential.mjs`, then `--passes 2`, then `--prove-update-keys`.
Expected: `0 differences` across every diffed table and all 18 targets, in all three runs. Record all three wall-clock times and the per-table row counts.

- [ ] **Step 2: Prove the full run can still fail**

Run: mutate one row per diffed table in the `reference` schema of a completed run and re-diff.
Expected: one difference per diffed table, each naming the mutated column. A clean run that cannot go red is worth nothing.

- [ ] **Step 3: Prove each mode catches what it claims, and only that**

Drop `statusDescription` from `CONFLICT_UPDATE_KEYS` in `campground-repo.ts` and run all three modes.
Expected: the single pass is clean, `--passes 2` is **also clean**, and `--prove-update-keys` reports a difference on `status_description` across every campground row. The middle result is not a failure — it is the point. With identical captures a dropped update key leaves the correct pass-1 value in place, so `--passes 2` cannot see it, and an assertion that expects it to would send the next person debugging a correct harness.

Then revert that mutation and widen the conflict target instead: replace `targetWhere: sql\`deleted_at IS NULL\`` with no `targetWhere`.
Expected: the single pass is clean and `--passes 2` reports differences, because the re-import is where a wrong conflict target first duplicates or fails. That is the property `--passes 2` exists for. Revert.

- [ ] **Step 3: Add the `make importer-diff` target**

The full differential is **not** added to `make test`: it needs `data/raw`, which Claude worktrees do not have, and it is minutes rather than seconds. `make test` gets Task 1's two worker lines and `node --test scripts/importer-differential.test.mjs`, which runs on synthetic fixtures and needs no captures.

- [ ] **Step 4: Decide and wire the CI job**

Rule, applied to Step 1's three measured times. CI runs the full capture set in **all three** modes if the whole job lands under 30 minutes on a GitHub runner. If it does not, the single pass keeps the full capture set and the other two drop to a `--source`-filtered subset covering one target per adapter family: the single pass is the strongest check of parse and transform and has by far the most rows to lose, while `--passes 2` and `--prove-update-keys` each check one specific structural property that a per-adapter subset establishes. If even that does not fit, the full-set runs of those two stay the `make importer-diff` gate before landing. `data/raw` is already in every CI checkout (138 MiB packed), so the captures cost nothing extra. Record all three measurements and the resulting choice in the job's comment and in the rulings file.

Path filter: `packages/**`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/**`, `backend/src/main/kotlin/ca/floo/roadtrip/repo/**`, `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/**`, `backend/src/main/kotlin/ca/floo/roadtrip/tools/**`, `backend/src/main/resources/poi-registry.yaml`, `backend/src/main/resources/db/migration/**`, `data/raw/**`, `scripts/importer-differential*.mjs`, `scripts/schema-reference.mjs`, `.github/workflows/ci.yml`.

- [ ] **Step 5: Close the silent-no-op hole the partial registry left open**

Through Tasks 6–13 the adapter registry is deliberately incomplete, so `importTargets` skipping a row with no registered adapter is correct. Once every adapter exists that tolerance becomes a hazard: a typo in an `adapter:` name silently shrinks the target set and the import reports success over fewer sources, where Kotlin fails loudly when it builds its registry (`createPoiTerminal` errors on an unknown adapter).

Add an assertion that every **enabled** `poi_data` and `campsite_data` row resolves an adapter, and fail with the offending row and adapter name when one does not. Prove it: misspell one `adapter:` value in a copy of the registry and show the CLI exiting non-zero and naming it. This is Review Focus #1's class — a run that does less than asked must not report success.

- [ ] **Step 6: Produce the seed dump spec §8.1 step 5 consumes**

Run: `node scripts/importer-differential.mjs --emit-seed <path>` — a `pg_dump` of the TypeScript database taken after a clean diff, with a provenance sidecar naming the commit and the capture-set hash. The dump itself is not committed; the target and its provenance format are.

- [ ] **Step 6: Run the whole suite**

Run: `make test`
Expected: PASS

- [ ] **Step 7: Write the rulings file**

`docs/superpowers/2026-10-28-step-4-rulings.md`, in the shape of step 2's: every call taken that this plan did not settle, with what it costs if it was wrong.

- [ ] **Step 8: Commit**

```bash
git add Makefile .github/workflows/ci.yml docs/superpowers/2026-10-28-step-4-rulings.md
git commit -m "ci: gate the TypeScript importer on the differential against the Kotlin one"
```
