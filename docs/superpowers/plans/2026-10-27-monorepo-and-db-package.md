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

Every table, column, index, and `CHECK` is transcribed from Task 2's reference dump, with constraint names given explicitly. Partial indexes use Drizzle's `.where()`.

Three things the reference demands that Drizzle does not give you by default:

- **`.desc()` is not `DESC`.** Drizzle's bare `.desc()` emits `DESC NULLS LAST`; four of the reference's indexes are `DESC NULLS FIRST` and need `.desc().nullsFirst()`. A wrong one is a different index with different plans, and nothing but the dump diff reveals it.
- **There is no `date[]` column.** An earlier draft of this task said to use `.array()` for `DATE[]`, from reading `availability_watch.target_dates` in V14 — V20 drops that column. The only array column in the produced schema is `trigger_kinds text[]`.
- **drizzle-orm 0.45 has no `bytea`**, which `user_session.token_hash` needs, so it takes a custom type alongside `geometry`.

- [ ] **Step 1: Write the failing test** — Review Focus #1

```ts
test('the write carries the SRID', async () => {
  await db.insert(pois).values({ id: WRITTEN_POI_ID, geom: ewkt('POINT(-123.1 49.2)') });
  expect(await sridOf(WRITTEN_POI_ID)).toBe(4326);
});

test('the read is SRID-bearing EWKB, not EWKT', async () => {
  const [row] = await db.select({ geom: pois.geom }).from(pois).where(eq(pois.id, WRITTEN_POI_ID));
  expect(isEwkt(row.geom)).toBe(false);
  expect(row.geom).toMatch(SRID_4326_POINT_HEADER);
});
```

**Both halves are required, and the second is the one that matters.** An earlier draft of this step had a single test projecting `ST_SRID(${pois.geom})` — which makes the server compute the answer, so `fromDriver` is never invoked and the read path is untested. Select the column and assert on what comes back.

The read assertion must be an EWKB-header match, not an `ST_SRID` check on a re-inserted value: PostGIS re-applies the column's typmod SRID to SRID-less WKB, so an `ST_SRID` assertion on a round-tripped row cannot fail even when the SRID has been lost. `/^0101000020E6100000/i` is the honest assertion — little-endian, Point-with-SRID-flag, `0x10E6` = 4326.

Writes and reads are **different formats**: EWKT in, hex EWKB out. Give them distinct branded types rather than two aliases for `string`, which the compiler treats as interchangeable, and make a constructor the only way to build one so a bare WKT string is a compile error.

And Review Focus #4 — the documented migration path must work, not just the one the gate uses:

```ts
test('the documented migration command migrates an empty database', async () => {
  const { status, stderr } = runDocumentedMigrate(container.getConnectionUri());
  expect(stderr).not.toMatch(/type "geometry" does not exist/);
  expect(status).toBe(0);
});
```

Whatever command the package documents as "how you migrate" is what this runs. If that is `drizzle-kit migrate`, it will fail on an empty database unless the extension is created ahead of the generated migration — and the equivalence gate will not catch it, because the gate goes through `migrateDb`. Either make the documented path create the extension too, or document `migrateDb` as the entrypoint and have this test run that. Do not make the test call a private helper the docs do not mention.

- [ ] **Step 2: Run to verify it fails**

Run: `npm run test --workspace @roadtrip/db`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `geometry` in `src/types/geometry.ts`, and `createDb` plus `migrateDb` in `src/client.ts`**

`geometry` is a `customType` whose `dataType()` returns `geometry(Geometry, 4326)`. `toDriver`/`fromDriver` must preserve SRID — EWKT or EWKB, not bare WKT. `createDb` passes `prepare` through to postgres.js, defaulting to `true`.

`migrateDb(db)` runs `CREATE EXTENSION IF NOT EXISTS postgis` **before** applying any migration, then applies them. This ordering is required, not stylistic: `0000_init.sql` declares `geometry(Geometry, 4326)` columns, so the extension must exist before the generated migration runs, and drizzle-kit always numbers its generated migration first. Task 5's raw migration therefore does **not** create the extension.

- [ ] **Step 4: Transcribe the schema modules from the Task 2 reference**

Five modules per the File Structure table. Names, nullability, defaults and constraint names come from the reference dump, not from the Kotlin models.

- [ ] **Step 5: Add a root `build` script and make `dist` staleness impossible**

Task 3 established that packages compile to `dist/` and that `exports` resolves there, but builds happen only through `prepare` on install — and the root `package.json` has no `scripts` block at all. So editing `packages/*/src` changes nothing for any consumer until someone reinstalls. That is a correctness problem for the three tasks after this one, not a convenience: Task 6's `scripts/schema-equivalence.mjs` is plain Node resolving `dist`, so it would run against stale code and report a schema difference that does not exist, or miss one that does.

Add a root `build` script that builds every workspace that has one, call it from `Makefile:test` ahead of anything that consumes `dist`, and give `@roadtrip/db` the same `tsconfig.json` / `tsconfig.typecheck.json` split Task 3 used. Verify it: edit a value in `packages/domain/src`, run the root build, and confirm the change is visible to a plain-`node` import without a reinstall.

**`npm run build --workspaces --if-present` is not good enough**, though an earlier draft of this step suggested it. npm runs workspaces in declaration order, which builds `domain` last and so fails `db` and the frontend; and npm does not abort on a failed workspace, so a `dist` emitted from code that never compiled survives and is served to consumers. The build needs dependency order and a non-zero exit on first failure.

- [ ] **Step 6: Generate the initial migration**

Run: `npx drizzle-kit generate --name init`
Expected: `packages/db/migrations/0000_init.sql` created. Do not hand-edit it; Task 5 adds a second file instead.

- [ ] **Step 7: Run to verify the test passes**

Run: `npm run test --workspace @roadtrip/db`
Expected: PASS.

- [ ] **Step 8: Commit**

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
- Produces: **two** functions — `availability_watch_prune_when_no_targets` (V30) and `campground_site_summary` (V64) — the `availability_watch_target_prune_empty` constraint trigger, the `grafana_api_cache_metadata` view, the `grafana_reader` role and grants, and one sequence-ownership statement (below). **Not** the PostGIS extension — Task 4's `migrateDb` creates it before any migration runs, because `0000_init.sql` needs it. **No materialized views** — see below.

Three migrations create a function; the reference holds two, because V58's `campsite_wire_kind` is a one-shot canonicalization helper that V58 drops on its last line. Copy V30's and V64's bodies; do not create `campsite_wire_kind`.

**One statement Drizzle has no API for at all:**

```sql
ALTER SEQUENCE public.availability_job_run_id_seq OWNED BY public.availability_run.id;
```

Task 4 verified that adding exactly this line collapses the last of the Flyway-vs-Drizzle diff to nothing. The sequence keeps its original name because V28 renamed the table (`availability_job_run` → `availability_run`) without renaming the sequence, and Drizzle cannot express sequence ownership. Without this line the gate fails on lines that look like they belong to nobody.

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

**This script needs the root build to have run.** Task 8 removed `prepare` from `packages/db`, because npm runs workspace `prepare` scripts in path order and `packages/db` was running `tsc` before `packages/domain/dist` existed, failing every clean `npm ci`. So nothing builds `@roadtrip/db` on install, and this script imports it from plain Node, which resolves `dist/`. Two consequences: the gate must be wired into `Makefile:test` **after** `npm run build`, and the script must fail with a message naming the missing build rather than an opaque module-resolution error. A gate that fails because nobody built the package looks exactly like a gate that failed because the schema diverged.

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

**Runs after Task 4, not last.** Task 3 added `"@roadtrip/domain": "*"` to `frontend/package.json`, and `frontend/package-lock.json` has no entry for it. `ci.yml`'s `frontend-tests` and `smoke` jobs run `npm ci` with `working-directory: frontend` against that lockfile, and `npm ci` fails hard on an out-of-sync manifest — **so CI on this branch cannot install today.** There is no band-aid: with `frontend/` as the working directory, npm resolves `@roadtrip/domain` from the registry rather than the workspace, so a member-scoped install is fundamentally incompatible with a workspace dependency. The structural fix is this task, which is why it moved ahead of Tasks 5–7. It depends only on Task 1, and nothing in Tasks 5–7 depends on it.

**Added after Task 1.** Task 1 created a root `package-lock.json` because root `npm ci` requires one, but left the two member lockfiles in place and left six other `npm ci` call sites pointing at them. Two lockfiles for one dependency tree can drift: a member-lock update leaves the root stale, and then CI, Docker and local development install different versions while all three appear to work.

**`ci.yml` also never builds `packages/*`.** Task 4 added `npm run build` to `Makefile:109`, but `Makefile:92-106` states in its own header that it mirrors `ci.yml` — "change one, change the other" — and `ci.yml` did not get it. Jobs that install with `working-directory: frontend` never build the workspace packages at all, so `dist/` is absent and the frontend cannot resolve `@roadtrip/domain`. Fixing the install location and adding the build step are the same piece of work.

**Files:**
- Delete: `frontend/package-lock.json`, `companion/package-lock.json`
- Modify: `Makefile:83` (companion), `Makefile:177` (frontend), `Tiltfile:113`, `Dockerfile:36-39` (frontend build stage), `.github/workflows/ci.yml:208,211,249,252,471,479` — including the `working-directory: frontend` on the install steps and the three `cache-dependency-path` entries, plus a build step mirroring `Makefile:109`
- Test: `scripts/check-single-lockfile.mjs`

Verify the outcome the way CI will experience it: from a clean tree, the exact install command each job runs must succeed and leave `packages/domain/dist` present. An install that succeeds without building the packages is the failure this task exists to prevent, and it looks identical to success until the frontend tries to resolve the import.

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
