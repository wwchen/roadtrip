# Monorepo and `@roadtrip/db` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An npm workspace holding `@roadtrip/domain` and `@roadtrip/db`, whose migrations reproduce today's Postgres schema exactly, proven against a reference built by running the existing Flyway migrations.

**Architecture:** `@roadtrip/domain` holds browser-safe enums, labels and JSONB shapes and depends on nothing. `@roadtrip/db` holds the Drizzle schema, the PostGIS custom type, the JSONB codecs and the client factory, and depends on `@roadtrip/domain`. Schema correctness is established by differential comparison against Flyway, not by review.

**Tech Stack:** npm workspaces, TypeScript, `drizzle-orm@0.45.4`, `drizzle-kit@0.31.11`, `postgres@3.4.9`, `@testcontainers/postgresql@12.2.1`, vitest.

**Spec:** [docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md](../specs/2026-10-27-kotlin-to-typescript-backend-design.md) — §3 is this plan's contract. Read §C4 before choosing anything datastore-specific.

## Global Constraints

- Node `>=22.12.0` (`frontend/package.json` floor; companion's is `>=22.9.0`).
- Package manager is **npm**. The repo has `package-lock.json` files, not pnpm or yarn.
- No code comments, inline or in doc blocks. Rationale goes in the commit message.
- Nothing in `@roadtrip/db` may be imported by `frontend/`. It depends on a Postgres driver.
- Every new top-level directory must be added to `.claude/settings.json` → `worktree.sparsePaths`. Cone mode can only enumerate, never subtract, so an unregistered directory is absent from every future worktree and `scripts/check-worktree-sparse-paths.mjs` fails the build.
- The schema reference is produced by running all 66 Flyway migrations, never by reading them and never from a production database.
- `CHECK` constraint names are pinned explicitly to match Flyway's. There are 107 of them.
- Transaction-mode pooling requires `prepare: false` in postgres.js.

## Review Focus

Five conditions §3 implies that no task's happy path would exercise, most likely to bite first:

1. **PostGIS SRID is lost in the custom type.** A `geometry(Geometry, 4326)` value written and read through Drizzle must come back with SRID 4326 intact; a custom type that serializes to WKT without SRID silently degrades every geo query in step 7. *Test in Task 4.*
2. **The constraint trigger is ported as an immediate trigger.** `availability_watch_target_prune_empty` is `DEFERRABLE INITIALLY DEFERRED`: deleting all of a watch's targets and re-inserting one inside a single transaction must leave the watch alive. A plain `AFTER DELETE` trigger deletes it. *Test in Task 5.*
3. **A JSONB codec throws on keys it does not know.** The Kotlin importer writes these columns today and its `Json` is configured `ignoreUnknownKeys = true`. A strict TS decoder fails on real rows during step 4's differential run. *Test in Task 7.*
4. **A query reads a materialized view before it is refreshed.** `campground_canonical` and `campsite_canonical` do not reflect an insert until refreshed, so a later step reading them gets stale rows with no error. *Test in Task 5.*
5. **Everything is tested on a direct connection only.** The API path uses a transaction-mode pooler where prepared statements fail; a suite that never exercises `prepare: false` passes here and breaks in step 7. *Test in Task 6.*

---

## File Structure

| Path | Responsibility |
| --- | --- |
| `package.json` | Workspace root; `workspaces: ["frontend", "companion", "packages/*"]` |
| `packages/domain/src/index.ts` | Barrel |
| `packages/domain/src/amenity.ts` | `AmenityKey`, labels, `CampgroundAmenity` |
| `packages/domain/src/carrier.ts` | `Carrier`, `CarrierSignal` |
| `packages/domain/src/campsite.ts` | `CampsiteKind` and its label |
| `packages/domain/src/campground.ts` | `CampgroundLocation`, `CampgroundPrice`, `CampgroundSchedule`, `CampgroundAlert`, `CampgroundMetadata`, `CampgroundRating`, `BookingAlias` |
| `packages/db/src/types/geometry.ts` | Drizzle custom type for `geometry(Geometry, 4326)` |
| `packages/db/src/schema/*.ts` | Tables, split by area: `catalog.ts`, `availability.ts`, `auth.ts`, `scheduler.ts`, `ingest.ts` |
| `packages/db/src/codec/catalog-json.ts` | The one encoder/decoder for typed JSONB columns |
| `packages/db/src/client.ts` | `createDb(url, opts)` |
| `packages/db/migrations/0000_init.sql` | drizzle-kit generated |
| `packages/db/migrations/0001_raw_objects.sql` | Hand-written: extension, 2 matviews, 3 functions, 1 constraint trigger, grants |
| `scripts/schema-reference.mjs` | Flyway → normalized reference dump |
| `scripts/schema-equivalence.mjs` | Drizzle vs reference; exits non-zero on difference |

---

### Task 1: Workspace root

**Files:**
- Create: `package.json`, `tsconfig.base.json`
- Modify: `.claude/settings.json` (add `packages` to `worktree.sparsePaths`), `Makefile:test`
- Test: `scripts/check-worktree-sparse-paths.mjs` (existing, must stay green)

**Interfaces:**
- Consumes: nothing.
- Produces: a workspace root where `npm install` at the top level links `packages/*`, `frontend` and `companion`.

- [ ] **Step 1: Create `packages/.gitkeep` and add `packages` to `worktree.sparsePaths` in `.claude/settings.json`**

- [ ] **Step 2: Run the guard to verify it passes**

Run: `node scripts/check-worktree-sparse-paths.mjs`
Expected: exit 0. Before the settings edit it must have failed naming `packages` — confirm that first by running it after creating the directory and before editing settings.

- [ ] **Step 3: Create root `package.json`**

`private: true`, `workspaces: ["frontend", "companion", "packages/*"]`, `engines.node: ">=22.12.0"`. No dependencies at the root.

- [ ] **Step 4: Create `tsconfig.base.json`**

`strict: true`, `module: "nodenext"`, `moduleResolution: "nodenext"`, `target: "es2023"`, `declaration: true`, `composite: true`.

- [ ] **Step 5: Update `Makefile:test`**

The existing recipe runs `cd frontend && npm ci` and `cd companion && npm ci --ignore-scripts`. Under workspaces a member-level `npm ci` does not resolve workspace links. Replace both with a single root `npm ci`, keeping each member's `npm run` invocations as `npm run <script> --workspace <name>`.

- [ ] **Step 6: Verify the existing suites still pass**

Run: `npm ci && npm run test --workspace roadtrip-frontend && npm run test --workspace campsite-companion`
Expected: PASS, same counts as before the change.

- [ ] **Step 7: Commit**

```bash
git add package.json tsconfig.base.json .claude/settings.json Makefile packages/.gitkeep
git commit -m "build: npm workspace root"
```

---

### Task 2: Flyway schema reference

**Files:**
- Create: `scripts/schema-reference.mjs`
- Test: `scripts/schema-reference.test.mjs`

**Interfaces:**
- Consumes: `backend/src/main/resources/db/migration/` (66 files), Docker.
- Produces: `writeReference(outPath: string): Promise<void>`, and a normalized dump at a caller-chosen path. Task 6 consumes that file.

Normalization is what makes the comparison possible: `pg_dump --schema-only --no-owner --no-privileges`, then strip `--` comment lines, strip `SET`/`SELECT pg_catalog.set_config` preamble, collapse blank runs, and sort statements by their first identifier. Both sides of Task 6's comparison run through the same function.

- [ ] **Step 1: Write the failing test**

```js
test('reference dump contains the objects Drizzle cannot express', async () => {
  const sql = await readFile(ref, 'utf8');
  assert.match(sql, /CREATE EXTENSION IF NOT EXISTS postgis/);
  assert.match(sql, /CREATE MATERIALIZED VIEW campground_canonical/);
  assert.match(sql, /CREATE MATERIALIZED VIEW campsite_canonical/);
  assert.match(sql, /CREATE CONSTRAINT TRIGGER availability_watch_target_prune_empty/);
  assert.match(sql, /CREATE TYPE public\.availability_status AS ENUM/);
  assert.equal((sql.match(/CHECK \(/g) ?? []).length, 107);
});

test('normalization is idempotent', () => {
  assert.equal(normalize(normalize(raw)), normalize(raw));
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `node --test scripts/schema-reference.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `writeReference(outPath)` and `normalize(sql)` in `scripts/schema-reference.mjs`**

Start a Postgres container with PostGIS available, run Flyway over the migration directory, `pg_dump`, normalize, write. Flyway and the Postgres image are already pinned in `backend/build.gradle.kts`'s buildscript block (`flyway-core:13.2.0`, Testcontainers `postgresql:1.21.4`) — match those versions so the reference matches what the Kotlin build produces.

- [ ] **Step 4: Run to verify it passes**

Run: `node --test scripts/schema-reference.test.mjs`
Expected: PASS. If the `CHECK (` count differs from 107, the assertion is right and the migrations have changed — update the number and say so in the commit.

- [ ] **Step 5: Commit**

```bash
git add scripts/schema-reference.mjs scripts/schema-reference.test.mjs
git commit -m "build: Flyway-produced schema reference"
```

---

### Task 3: `@roadtrip/domain`

**Files:**
- Create: `packages/domain/package.json`, `tsconfig.json`, `src/index.ts`, `src/amenity.ts`, `src/carrier.ts`, `src/campsite.ts`, `src/campground.ts`
- Test: `packages/domain/src/amenity.test.ts`, `src/campsite.test.ts`

**Interfaces:**
- Consumes: nothing. Zero runtime dependencies — this is the browser-safe half.
- Produces: the union types `AmenityKey`, `Carrier`, `CarrierSignal`, `CampsiteKind`; the frozen member arrays `amenityKeys`, `carriers`, `campsiteKinds` (each `readonly T[]`, so callers can enumerate); `amenityLabel(k: AmenityKey): string`, `amenityNegativeLabel(k: AmenityKey): string`, `carrierLabel(c: Carrier): string`, `campsiteKindLabel(k: CampsiteKind): string`; and the JSONB shape types `CampgroundLocation`, `CampgroundPrice`, `CampgroundSchedule`, `CampgroundAlert`, `CampgroundMetadata`, `CampgroundRating`, `BookingAlias`.

Source of truth for every member and label is `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/` — `AmenityKey.kt`, `Carrier.kt`, `CampsiteKind.kt`. Labels ride on the enum there (`AmenityKey.label`/`.negativeLabel`), so they ride on it here.

- [ ] **Step 1: Write the failing tests**

```ts
test('every AmenityKey has a label and a negative label', () => {
  for (const k of amenityKeys) {
    expect(amenityLabel(k)).toBeTruthy();
    expect(amenityNegativeLabel(k)).toBeTruthy();
  }
});

test('members match the Kotlin enum exactly', () => {
  expect([...amenityKeys].sort()).toEqual(KOTLIN_AMENITY_KEYS.sort());
});
```

`KOTLIN_AMENITY_KEYS` is a literal list transcribed from `AmenityKey.kt` in the test file, so a drift in either direction fails.

- [ ] **Step 2: Run to verify they fail**

Run: `npm run test --workspace @roadtrip/domain`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the four modules and the barrel**

Enums as `as const` object plus a derived union type, not TS `enum` — the values are persisted strings and must serialize as themselves.

- [ ] **Step 4: Run to verify they pass**

Run: `npm run test --workspace @roadtrip/domain`
Expected: PASS.

- [ ] **Step 5: Verify the frontend can import it**

Run: `npm run typecheck --workspace roadtrip-frontend` after adding `@roadtrip/domain` to the frontend's dependencies and a single import in `frontend/src/lib/campground-vocab.ts`.
Expected: PASS. This proves the browser-safe boundary before `@roadtrip/db` exists to violate it.

- [ ] **Step 6: Commit**

```bash
git add packages/domain frontend/package.json frontend/src/lib/campground-vocab.ts package-lock.json
git commit -m "feat: @roadtrip/domain vocabulary package"
```

---

### Task 4: `@roadtrip/db` tables

**Files:**
- Create: `packages/db/package.json`, `tsconfig.json`, `drizzle.config.ts`, `src/index.ts`, `src/client.ts`, `src/types/geometry.ts`, `src/schema/catalog.ts`, `src/schema/availability.ts`, `src/schema/auth.ts`, `src/schema/scheduler.ts`, `src/schema/ingest.ts`
- Create: `packages/db/migrations/0000_init.sql` (generated)
- Test: `packages/db/src/types/geometry.test.ts`

**Interfaces:**
- Consumes: `@roadtrip/domain` for JSONB column types.
- Produces: `createDb(url: string, opts?: { prepare?: boolean }): Db`, the `geometry` custom type, the `availabilityStatus` pgEnum, and one exported table object per table. `pois`, `campgrounds` and `campsites` live in `src/schema/catalog.ts`; `availabilityWatch`, `availabilityWatchTarget` and `availability` in `availability.ts`. Tasks 5, 6 and 7 import these.

Every table, column, index, and `CHECK` is transcribed from Task 2's reference dump, with constraint names given explicitly. Partial indexes use Drizzle's `.where()`; `DATE[]` columns use `.array()`.

- [ ] **Step 1: Write the failing test** — Review Focus #1

```ts
test('geometry round-trips with SRID 4326 intact', async () => {
  const db = createDb(container.getConnectionUri());
  await db.insert(pois).values({ id: 1n, geom: 'SRID=4326;POINT(-123.1 49.2)' });
  const [row] = await db.select({ srid: sql`ST_SRID(${pois.geom})` }).from(pois);
  expect(row.srid).toBe(4326);
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npm run test --workspace @roadtrip/db`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `geometry` in `src/types/geometry.ts` and `createDb` in `src/client.ts`**

`geometry` is a `customType` whose `dataType()` returns `geometry(Geometry, 4326)`. `toDriver`/`fromDriver` must preserve SRID — EWKT or EWKB, not bare WKT. `createDb` passes `prepare` through to postgres.js, defaulting to `true`.

- [ ] **Step 4: Transcribe the schema modules from the Task 2 reference**

Five modules per the File Structure table. Names, nullability, defaults and constraint names come from the reference dump, not from the Kotlin models.

- [ ] **Step 5: Generate the initial migration**

Run: `npx drizzle-kit generate --name init`
Expected: `packages/db/migrations/0000_init.sql` created. Do not hand-edit it; Task 5 adds a second file instead.

- [ ] **Step 6: Run to verify the test passes**

Run: `npm run test --workspace @roadtrip/db`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add packages/db package-lock.json
git commit -m "feat: @roadtrip/db schema and PostGIS geometry type"
```

---

### Task 5: Raw schema objects

**Files:**
- Create: `packages/db/migrations/0001_raw_objects.sql`
- Test: `packages/db/src/schema/raw-objects.test.ts`

**Interfaces:**
- Consumes: Task 4's migration output; runs immediately after it.
- Produces: the PostGIS extension, `campground_canonical`, `campsite_canonical`, three plpgsql functions, `availability_watch_target_prune_empty`, and the `grafana_reader` grants.

SQL bodies are copied verbatim from the Flyway migrations that own them — V38 and later for the matviews, V30 for the prune function and trigger, `R__grafana_reader_grants.sql` for the grants. Copy, do not paraphrase: these are the live definitions.

- [ ] **Step 1: Write the failing tests** — Review Focus #2 and #4

```ts
test('deleting all targets then re-inserting inside one transaction keeps the watch', async () => {
  await db.transaction(async (tx) => {
    await tx.delete(availabilityWatchTarget).where(eq(availabilityWatchTarget.watchId, 1n));
    await tx.insert(availabilityWatchTarget).values({ watchId: 1n, campsiteId: 7n });
  });
  expect(await watchExists(1n)).toBe(true);
});

test('deleting the last target with no re-insert prunes the watch', async () => {
  await db.delete(availabilityWatchTarget).where(eq(availabilityWatchTarget.watchId, 2n));
  expect(await watchExists(2n)).toBe(false);
});

test('campground_canonical does not reflect an insert until refreshed', async () => {
  await db.insert(campgrounds).values(fixture);
  expect(await canonicalCount()).toBe(before);
  await db.execute(sql`REFRESH MATERIALIZED VIEW campground_canonical`);
  expect(await canonicalCount()).toBe(before + 1);
});
```

The first test is the one that matters. It fails if the trigger is created without `DEFERRABLE INITIALLY DEFERRED`, which is the plausible mistake.

- [ ] **Step 2: Run to verify they fail**

Run: `npm run test --workspace @roadtrip/db -- raw-objects`
Expected: FAIL — relation `campground_canonical` does not exist.

- [ ] **Step 3: Write `0001_raw_objects.sql`**

Order matters: extension, functions, matviews, constraint trigger, grants.

- [ ] **Step 4: Run to verify they pass**

Run: `npm run test --workspace @roadtrip/db -- raw-objects`
Expected: PASS, all three.

- [ ] **Step 5: Commit**

```bash
git add packages/db/migrations/0001_raw_objects.sql packages/db/src/schema/raw-objects.test.ts
git commit -m "feat: matviews, functions, constraint trigger and grants"
```

---

### Task 6: Schema equivalence gate

**Files:**
- Create: `scripts/schema-equivalence.mjs`
- Modify: `Makefile:test` (add the gate)
- Test: `scripts/schema-equivalence.test.mjs`

**Interfaces:**
- Consumes: `writeReference` and `normalize` from Task 2; Task 4's and Task 5's migrations.
- Produces: a check that exits non-zero with a readable diff on any difference. This is step 2's gate.

- [ ] **Step 1: Write the failing tests** — Review Focus #5

```ts
test('a schema matching the reference passes', async () => {
  expect(await compare()).toEqual({ equal: true, diff: '' });
});

test('an injected extra column is reported', async () => {
  await applyToDrizzleDb(sql`ALTER TABLE campgrounds ADD COLUMN bogus text`);
  const r = await compare();
  expect(r.equal).toBe(false);
  expect(r.diff).toContain('bogus');
});

test('the schema works through a transaction-mode pooler with prepare disabled', async () => {
  const db = createDb(poolerUri, { prepare: false });
  await expect(db.select().from(campgrounds).limit(1)).resolves.toBeDefined();
});
```

The second test is what proves the gate can fail. A gate only ever observed passing is not a gate.

- [ ] **Step 2: Run to verify they fail**

Run: `node --test scripts/schema-equivalence.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `compare(): Promise<{ equal: boolean, diff: string }>` in `scripts/schema-equivalence.mjs`**

Two containers: one Flyway-migrated via Task 2, one Drizzle-migrated. Dump and normalize both with Task 2's `normalize`. Diff line-by-line.

- [ ] **Step 4: Run to verify they pass**

Run: `node --test scripts/schema-equivalence.test.mjs`
Expected: PASS. A failure on the first test is the real signal of this whole plan — read the diff and fix Task 4 or 5, not the gate.

- [ ] **Step 5: Add the gate to `Makefile:test` and verify it runs there**

Run: `make test`
Expected: PASS, with the equivalence check appearing in the output. A `make test` that passes without printing it means the recipe line was added to the wrong target.

- [ ] **Step 6: Commit**

```bash
git add scripts/schema-equivalence.mjs scripts/schema-equivalence.test.mjs Makefile
git commit -m "test: schema equivalence gate against Flyway"
```

---

### Task 7: JSONB codecs

**Files:**
- Create: `packages/db/src/codec/catalog-json.ts`
- Test: `packages/db/src/codec/catalog-json.test.ts`

**Interfaces:**
- Consumes: `@roadtrip/domain` shape types; Task 4's tables.
- Produces: `Shape<T>` — the per-column descriptor carrying that column's field list and defaults — plus `encodeCatalogJson<T>(shape: Shape<T>, value: T): unknown` and `decodeCatalogJson<T>(shape: Shape<T>, raw: unknown): T`, one pair used by every typed JSONB column, and one exported shape constant per column (`amenitiesShape`, `priceShape`, …). Step 4's importer and step 7's API both go through these and nothing else.

Covers `amenities`, `cell_service`, `metadata`, `price`, `default_campsite_schedule`, `alerts`, `photos`, `equipment`, `attributes`, `booking_aliases`, `kind`, `location`/`address`, `properties`, `source_payload`.

- [ ] **Step 1: Write the failing tests** — Review Focus #3

```ts
test('decode tolerates keys it does not know', () => {
  const raw = { ...validAmenities, future_key: 'whatever' };
  expect(() => decodeCatalogJson(amenitiesShape, raw)).not.toThrow();
});

test('encode then decode is identity for every covered shape', () => {
  for (const [shape, value] of COVERED_SHAPES) {
    expect(decodeCatalogJson(shape, encodeCatalogJson(shape, value))).toEqual(value);
  }
});

test('a row written as JSONB and read back through the column preserves the shape', async () => {
  await db.insert(campgrounds).values({ ...fixture, amenities: value });
  const [row] = await db.select().from(campgrounds).limit(1);
  expect(row.amenities).toEqual(value);
});
```

The first test is not defensive style — the Kotlin importer writes these columns with `ignoreUnknownKeys = true`, so real rows will carry keys this decoder has never seen during step 4's differential run.

- [ ] **Step 2: Run to verify they fail**

Run: `npm run test --workspace @roadtrip/db -- catalog-json`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement both functions in `packages/db/src/codec/catalog-json.ts`**

Unknown keys are dropped on decode, never thrown on. `COVERED_SHAPES` in the test is the completeness check: a column added later without a shape fails it.

- [ ] **Step 4: Run to verify they pass**

Run: `npm run test --workspace @roadtrip/db -- catalog-json`
Expected: PASS.

- [ ] **Step 5: Run the whole suite and the gate**

Run: `make test`
Expected: PASS, including the Task 6 gate and every pre-existing Kotlin, frontend and companion suite.

- [ ] **Step 6: Commit**

```bash
git add packages/db/src/codec
git commit -m "feat: one codec for typed JSONB catalog columns"
```
