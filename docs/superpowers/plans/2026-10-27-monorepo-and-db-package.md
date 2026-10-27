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
3. **A JSONB codec mishandles the keys that are not there, in either direction.** The Kotlin codec runs `ignoreUnknownKeys = true`, `explicitNulls = false` and no `encodeDefaults`, so stored rows carry keys a TS decoder has never seen *and* omit every null and every defaulted value. A strict decoder throws on the first; a decoder without defaults silently reads an absent `present` as undefined rather than `true`, and an absent `activities` as undefined rather than `[]`. Keys are snake_case (`check_in`, `ends_on`, `last_updated`) via `@SerialName`. *Test in Task 7, both directions.*
4. **The documented migration path differs from the tested one.** If `migrateDb` is the only thing that creates the PostGIS extension, then `npx drizzle-kit migrate` — the obvious command, and the one a new contributor reaches for — fails on an empty database while the equivalence gate passes, because the gate goes through `migrateDb`. *Test in Task 4:* the project's documented entrypoint migrates an empty database successfully.
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

`normalize` must also **drop both migration-bookkeeping objects**: Flyway's `flyway_schema_history` table and Drizzle's `__drizzle_migrations` (in the `drizzle` schema). Each exists on exactly one side, so leaving either in makes Task 6's gate impossible to pass.

**`normalize` must also sort columns within `CREATE TABLE`.** Flyway's history is a sequence of `ALTER TABLE … ADD COLUMN`, so its dump lists later-added columns last; Drizzle emits them in declaration order. Without sorting, Task 6's gate fails on physical column order — a difference with no consumer here, since there is no data to migrate and Drizzle names columns explicitly in every query. Sorting keeps the gate comparing what matters: types, nullability, defaults, constraint names and bodies, and indexes. Forcing parity instead would make Task 4 encode Flyway's ALTER history as declaration order, which is historical accident dressed as a requirement.

`writeReference` must **fail rather than write an empty or near-empty reference**. If Flyway applies zero migrations — an empty mount comes up exit 0 — or the statement splitter swallows everything, an empty file is written successfully and Task 6's gate then passes vacuously whenever both sides are empty. Assert a post-condition before writing: a plausible `CREATE TABLE` count. Delete or refuse to overwrite a stale `outPath` on failure.

Export the container-and-dump pipeline, not just `writeReference`. Task 6 runs the same dump over a Drizzle-migrated database **in the same container** (see Task 6), and it needs the image pin, the `FROM template0` database creation and the `pg_dump` invocation to be literally the same code. Two copies drift on exactly the parameters that make the comparison valid.

`--no-privileges` means the comparison covers structure, not grants. That is deliberate — `R__grafana_reader_grants.sql` is mini-ca's concern per spec §11 — but it means the gate does not protect the `grafana_reader` privileges. Say so in the commit.

- [ ] **Step 1: Write the failing test**

```js
test('reference dump contains the objects Drizzle cannot express', async () => {
  const sql = await readFile(ref, 'utf8');
  assert.match(sql, /CREATE EXTENSION IF NOT EXISTS postgis/);
  assert.match(sql, /CREATE CONSTRAINT TRIGGER availability_watch_target_prune_empty/);
  assert.match(sql, /CREATE TYPE public\.availability_status AS ENUM/);
  assert.match(sql, /CREATE VIEW public\.grafana_api_cache_metadata/);
  assert.equal((sql.match(/CHECK \(/g) ?? []).length, 76);
});

test('the dropped materialized views are absent', async () => {
  const sql = await readFile(ref, 'utf8');
  assert.doesNotMatch(sql, /MATERIALIZED VIEW/);
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
Expected: PASS. The counts here are against the *produced schema*, not the migration source: 76 `CHECK (` in the dump versus 107 occurrences in the migration text, the difference being constraints on tables and columns that later migrations dropped. If your run disagrees with 76, investigate before changing the number, and say why in the commit.

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
- Produces: the union types `AmenityKey`, `Carrier`, `CampsiteKind`; the frozen member arrays `amenityKeys`, `carriers`, `campsiteKinds` (each `readonly T[]`, so callers can enumerate); `amenityLabel(k: AmenityKey): string`, **`amenityNegativeLabel(k: AmenityKey): string | undefined`**, `carrierLabel(c: Carrier): string`, `campsiteKindLabel(k: CampsiteKind): string`; and the shape types `CarrierSignal`, `CampgroundLocation`, `Address`, `CampgroundPrice`, `CampgroundSchedule`, `CampgroundAlert`, `CampgroundMetadata`, `CampgroundRating`, `CampgroundAmenity`, `BookingProvider`, `BookingAlias`.

Three corrections to an earlier draft of this task, all found by transcribing the Kotlin rather than trusting this plan:

- **`negativeLabel` is nullable in Kotlin** (`AmenityKey.kt:11`, `String? = null`) and passed positionally, so only some of the 13 members carry one. The return type is `string | undefined` and no label is invented. Tasks 4 and 7 must handle `undefined`.
- **`CarrierSignal` is an object, not a union** — `data class CarrierSignal(carrier, average, count: Int? = null)`. It is one entry of the `campgrounds.cell_service` JSONB array.
- **`Address`, `BookingProvider` and `CampgroundAmenity` are required** by the shapes above, and belong here even though an earlier draft omitted them.

**The stored values are the `wire` strings, not the Kotlin member names.** `AmenityKey` carries a `wire` field alongside its `@SerialName`, and that is what is persisted and what the API serves. The union members are the wire values.

**The package builds.** `exports` points at compiled output under `dist/`, with declarations, and a `build` script runs `tsc`. Exporting `./src/index.ts` directly works under Vite, vitest and `tsc` but not under plain `node`, and Task 6's `scripts/schema-equivalence.mjs` is plain Node importing `@roadtrip/db`, which depends on this package. `tsconfig.base.json` already sets `declaration` and `composite`, which is a build configuration.

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

- [ ] **Step 3: Implement `geometry` in `src/types/geometry.ts`, and `createDb` plus `migrateDb` in `src/client.ts`**

`geometry` is a `customType` whose `dataType()` returns `geometry(Geometry, 4326)`. `toDriver`/`fromDriver` must preserve SRID — EWKT or EWKB, not bare WKT. `createDb` passes `prepare` through to postgres.js, defaulting to `true`.

`migrateDb(db)` runs `CREATE EXTENSION IF NOT EXISTS postgis` **before** applying any migration, then applies them. This ordering is required, not stylistic: `0000_init.sql` declares `geometry(Geometry, 4326)` columns, so the extension must exist before the generated migration runs, and drizzle-kit always numbers its generated migration first. Task 5's raw migration therefore does **not** create the extension.

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
- Produces: the `availability_watch_prune_when_no_targets` function, the `availability_watch_target_prune_empty` constraint trigger, the `grafana_api_cache_metadata` view, and the `grafana_reader` role and grants. **Not** the PostGIS extension — Task 4's `migrateDb` creates it before any migration runs, because `0000_init.sql` needs it. **No materialized views** — see below.

SQL bodies are copied verbatim from the Flyway migrations that own them — V30 for the prune function and trigger, `R__grafana_reader_grants.sql` for the role guard, the grants and the `grafana_api_cache_metadata` view. Copy, do not paraphrase: these are the live definitions. The role guard is the `DO $$ … IF NOT EXISTS (SELECT FROM pg_roles …) $$` block, which is what makes the file safe to run against a database that has no `grafana_reader`.

**Do not create `campground_canonical` or `campsite_canonical`.** An earlier draft of this task listed them. They are created in V39, recreated in V41 and V43, and dropped for good in V44; `CanonicalCatalogSchemaTest` asserts their absence against `pg_matviews`, and Task 2's reference dump contains neither. Creating them would fail Task 6's gate.

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

test('no materialized views exist', async () => {
  const rows = await db.execute(sql`SELECT matviewname FROM pg_matviews WHERE schemaname = 'public'`);
  expect(rows).toEqual([]);
});

test('grafana_api_cache_metadata is queryable', async () => {
  await expect(db.execute(sql`SELECT * FROM grafana_api_cache_metadata LIMIT 1`)).resolves.toBeDefined();
});
```

The first test is the one that matters. It fails if the trigger is created without `DEFERRABLE INITIALLY DEFERRED`, which is the plausible mistake.

- [ ] **Step 2: Run to verify they fail**

Run: `npm run test --workspace @roadtrip/db -- raw-objects`
Expected: FAIL — relation `grafana_api_cache_metadata` does not exist, and the trigger tests fail because nothing prunes.

- [ ] **Step 3: Write `0001_raw_objects.sql`**

Order matters: function, then constraint trigger, then role guard, then grants, then the view.

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

**One container, two databases** — not two containers. Task 2 reports the PostGIS image is amd64-only, so it runs under emulation on Apple Silicon and the first pull is ~850MB; booting it twice doubles a cost that is already the slowest thing in this plan. Create two databases in the single container, both from `template0` as Task 2 does, migrate one with Flyway and one with Drizzle, and dump both. Reuse Task 2's container setup rather than writing a second one.

Dump and normalize both with Task 2's `normalize`. Diff line-by-line.

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

test('decode supplies the defaults the writer omitted', () => {
  expect(decodeCatalogJson(amenityShape, { key: 'showers' }).present).toBe(true);
  expect(decodeCatalogJson(scheduleShape, {}).activities).toEqual([]);
});

test('encode writes the snake_case keys the stored rows use', () => {
  expect(encodeCatalogJson(scheduleShape, schedule)).toHaveProperty('check_in');
});

test('encode then decode is identity for every covered shape', () => {
  for (const [shape, value] of COVERED_SHAPES) {
    expect(decodeCatalogJson(shape, encodeCatalogJson(shape, value))).toEqual(value);
  }
});

test('an encoded row read back and decoded preserves the shape', async () => {
  await db.insert(campgrounds).values({ ...fixture, amenities: encodeCatalogJson(amenitiesShape, value) });
  const [row] = await db.select().from(campgrounds).limit(1);
  expect(decodeCatalogJson(amenitiesShape, row.amenities)).toEqual(value);
});
```

The third test goes through `encode` and `decode` explicitly. Drizzle's `jsonb().$type<T>()` only asserts a TypeScript type — it applies no codec — so asserting `row.amenities` directly would pass on parsed JSON and test nothing. Columns are declared `.$type<T>()` and callers encode and decode at the boundary.

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

---

### Task 8: One lockfile

**Added after Task 1.** Task 1 created a root `package-lock.json` because root `npm ci` requires one, but left the two member lockfiles in place and left six other `npm ci` call sites pointing at them. Two lockfiles for one dependency tree can drift: a member-lock update leaves the root stale, and then CI, Docker and local development install different versions while all three appear to work.

**Files:**
- Delete: `frontend/package-lock.json`, `companion/package-lock.json`
- Modify: `Makefile:83` (companion), `Makefile:177` (frontend), `Tiltfile:113`, `Dockerfile:36-39` (frontend build stage), `.github/workflows/ci.yml:208,211,249,252,471,479`
- Test: `scripts/check-single-lockfile.mjs`

**Interfaces:**
- Consumes: Task 1's root `package.json` and `package-lock.json`.
- Produces: `npm ci` resolves from exactly one lockfile everywhere. No new exports.

- [ ] **Step 1: Write the failing test**

```js
test('exactly one lockfile exists, at the repo root', () => {
  assert.ok(existsSync('package-lock.json'));
  for (const m of ['frontend', 'companion']) {
    assert.equal(existsSync(`${m}/package-lock.json`), false, `${m} has its own lockfile`);
  }
});

test('no tracked file references a member lockfile', () => {
  const hits = grepTracked('package-lock.json')
    .filter((l) => /(frontend|companion)\/package-lock\.json/.test(l));
  assert.deepEqual(hits, []);
});
```

The second test is the one that matters: deleting the files while CI still names them in `cache-dependency-path` produces a green build and a broken cache.

- [ ] **Step 2: Run to verify it fails**

Run: `node --test scripts/check-single-lockfile.test.mjs`
Expected: FAIL on both — member lockfiles exist and are referenced in 3 `cache-dependency-path` entries plus 2 Dockerfile `COPY` lines.

- [ ] **Step 3: Repoint every call site at the root lockfile**

`npm ci --workspace <name>` installs one workspace's dependencies from the root lock, so each site keeps its current scope. In `Dockerfile`, the frontend stage currently copies `frontend/package.json frontend/package-lock.json`; it must copy the root `package.json` and `package-lock.json` plus `frontend/package.json` instead.

**Two `prepare`-related traps, both from Task 3.** `@roadtrip/domain` compiles to `dist/` via a `prepare` script, which a root `npm ci` runs automatically. So:

- **`--ignore-scripts` skips `prepare`**, leaving `dist/` absent and every consumer broken. `ci.yml:252` still carries it on the companion job. The companion does not depend on `@roadtrip/domain`, so that one site is safe — but verify that rather than assume it, and drop the flag anywhere a site does need the built package. Keep it off generally: only `esbuild` and `fsevents` declare install scripts in the whole tree, and esbuild needs its to link its platform binary.
- **A scoped `npm ci --workspace roadtrip-frontend` may not build `@roadtrip/domain` at all.** Task 3 did not verify whether a scoped install triggers a dependency workspace's `prepare` or even copies `packages/domain`. The `Dockerfile` frontend stage and the `Makefile` `frontend` target are both scoped installs that need the built package. Check this first; if a scoped install does not produce `dist/`, those sites need an explicit `npm run build --workspace @roadtrip/domain` (and the Dockerfile stage needs `packages/domain` in its build context).

The second one is the likelier breakage and it fails at image-build time, not in CI.

- [ ] **Step 4: Delete the member lockfiles and run the test**

Run: `node --test scripts/check-single-lockfile.test.mjs`
Expected: PASS.

- [ ] **Step 5: Verify installs still work from the root lock**

Run: `npm ci && npm run test --workspace roadtrip-frontend && npm run test --workspace campsite-companion`
Expected: PASS, with the same test counts Task 1 recorded — 1868 frontend across 129 files, 230 companion.

- [ ] **Step 6: Add the check to `Makefile:test` and commit**

```bash
git add scripts/check-single-lockfile.mjs scripts/check-single-lockfile.test.mjs Makefile Tiltfile Dockerfile .github/workflows/ci.yml package-lock.json
git rm frontend/package-lock.json companion/package-lock.json
git commit -m "build: resolve every install from the root lockfile"
```
