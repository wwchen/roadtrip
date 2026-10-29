# Thin Next.js Read App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A read-only Next.js app serving real campground data from `@roadtrip/db`, deployed on Vercel against a managed Postgres populated by the project's own importer.

**Architecture:** A new `apps/web` npm workspace. Server-rendered pages call a service, which calls repos that hold the SQL — the same `routes -> service -> repo` layering `AGENTS.md` mandates for the Kotlin backend, ported shape-for-shape from `CampgroundRepo` and `CampgroundSearchRepo`. Nothing on the client can reach a Postgres driver, and a new guard proves it. The managed database is populated by running the existing importer against it, not by copying production.

**Tech Stack:** Next.js 16.4.3 (App Router), React 19.3.0, `@roadtrip/db` (Drizzle 0.45.4 + postgres.js 3.4.9), vitest, Neon Postgres 16 + PostGIS, Vercel.

**Spec:** `docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md` (§1, §2, §3, §10 step 7, §11; constraint C4). Prior rulings: `docs/superpowers/2026-10-27-step-2-rulings.md`.

## Scope fence

A deliberately thin slice pulled forward out of §10 step 7.

**In scope:** two SSR pages and two JSON route handlers, read-only, over the existing catalog tables; one Vercel deployment; a managed Postgres populated by the importer.

**Out of scope and not to be touched:** production, which keeps running the current Kotlin stack on mini-ca. The Kotlin backend, `recgov-companion`, the production Postgres, the observability services, `frontend/`, `docker-compose.yml`, `caddy/`, `scripts/deploy.sh` and `.github/workflows/deploy.yml` all stay exactly as they are. There is **no mini-ca deployment**, no Compose service, no Caddy route, and **no `pg_dump` or any other copy of production data**. Auth, the availability read path, writes of any kind, and porting the existing SPA are all out. The frozen-contract gate, the Playwright port and the preview-deploy lifecycle that §10 step 7 actually requires are not in scope; **this plan does not close step 7.**

**Not a deliverable:** p50/p95 latency measurement. An earlier draft built a probe harness for §9's P1. It was cut as ceremony: the point of putting this on Vercel is that the response times are visible by using it, and C4 means a bad answer costs a config value rather than a rewrite. Task 5 notes the response times observed on the heaviest read and says whether anything looks alarming. That is all.

## Decided

**Read-only access uses the connection parameter.** `default_transaction_read_only=on`, which Postgres enforces and which requires no privileged setup. **Write this down beside it:** it guards against *accidental* writes and is **not a security boundary** — a session can `SET transaction_read_only = off` and write. For this app that is sufficient; the honest framing matters so nobody later reads it as isolation it does not provide. Task 3's test proves the guard is on, not that it cannot be lifted.

**`apps/web` is the directory.** `packages/*` for libraries, `apps/*` for deployables; §1's two runtimes mean `apps/worker` will want the same home.

**The managed database gets its schema and rows from the project's own tooling, not from production.** How, exactly, is Task 5's first question to answer — see its risk note.

## Global Constraints

- **No inline magic constants.** Numeric, string and duration literals at call sites get extracted to named `const` (or env-driven config when operationally tunable).
- **No code comments.** None inline, none in JSDoc/TSDoc. Rationale goes in the commit message.
- **`packages/db` has no `prepare` script, on purpose** — npm runs workspace `prepare` hooks in path order and `db` was compiling before `packages/domain/dist` existed, breaking every clean `npm ci`. So an install alone leaves `packages/db/dist` missing and consumers must run the root `npm run build` first. `apps/web` gets no `prepare` script either.
- **No `NEXT_PUBLIC_` anything.** It is inlined into the client bundle and served to every visitor. This app has no client configuration, so the correct count is zero. Task 2's guard enforces it.
- **Node `>=22.12.0`** in every new `package.json` `engines`.
- **Exact version pins** for new dependencies, matching how `packages/db` pins `drizzle-orm` and `postgres`.
- **Stage explicit paths.** Never `git add -A`; parallel sessions share this checkout.
- **A gate must be proven able to fail.** Every check added gets a mutation test or a recorded demonstration that breaking the thing it guards turns it red. Three gates on this project have shipped unable to fail, and a fourth was caught during this plan's own execution.

## Review Focus

Five input classes the spec implies and no happy path exercises.

- **A `bigint` id reaching `JSON.stringify`.** `packages/db` declares ids as `bigserial(..., { mode: 'bigint' })`, so rows carry JS `BigInt`, which `JSON.stringify` throws `TypeError` on and which cannot cross the React server/client boundary. A route handler returning a raw row 500s on every request. Repos convert at their own boundary. → Task 3.
- **A JSONB column that does not match its codec.** Per step 2's rulings the JSONB columns deliberately carry no `.$type<>`, so `decodeCatalogJson` is the only correct read path — and it is strict, while real rows predate it. One odd `price` object must not take out the page. → Task 3.
- **A malformed boundary.** `ST_GeomFromGeoJSON` raises on non-GeoJSON; the Kotlin maps that to `InvalidBoundaryException` and a 4xx. A port that lets the driver error escape returns 500 for a user typo. → Task 3, Task 4.
- **An empty provider set.** `CampgroundSearchRepo` short-circuits when `enabledDataProviders` is empty; without that the port builds `IN ()`, a syntax error. An unset env var is the likeliest way to hit it. → Task 3.
- **A POI id that is not a POI id.** `/campgrounds/abc`, `/campgrounds/-1`, and an id past `bigint` range must be 404 or 400, never a 500 from `BigInt()` throwing. → Task 4.

---

### Task 1: The `apps/web` workspace — COMPLETE (commits 5889c68a..f9773be1)

Created the workspace and registered it in the four places a new top-level directory is load-bearing: root `workspaces`, `.claude/settings.json` → `worktree.sparsePaths`, the CI `changes` filter plus a `web-tests` job in `ci-passed`'s `needs`, and `make test`.

Two commits by design: the first adds the workspace unregistered so `scripts/check-worktree-sparse-paths.mjs` can be **observed failing** (it reads `git ls-tree HEAD`, so an uncommitted directory is invisible to it), the second registers it. `next-config.test.ts` pins `outputFileTracingRoot` to the repo root.

- [ ] **Amendment: drop `output: 'standalone'` from `next.config.ts`**

It was there for the mini-ca Docker image, which is now out of scope, and Vercel does not want it. `outputFileTracingRoot` **stays** — Vercel needs it to resolve a monorepo workspace root — and so does its test, which now pins a Vercel build concern rather than a Docker one. Re-run `npm run build --workspace @roadtrip/web` and `npm run test --workspace @roadtrip/web`, then commit.

---

### Task 2: The client-boundary guard — COMPLETE pending re-review (commits ca26eeb9..05bf9405)

`scripts/check-package-boundaries.mjs` hardcodes `FRONTEND_DIRECTORY = 'frontend'` and forbids `@roadtrip/db` outright, which is right for the Vite SPA and wrong for a Next app that legitimately imports it **on the server**. So `scripts/check-server-only-imports.mjs` enforces a different rule: no file in the client closure — any file carrying `'use client'`, or reachable from one — may reach `@roadtrip/db` or `apps/web/src/server/`, and no file under `apps/web` may reference a `NEXT_PUBLIC_` name.

Along the way this closed **three parsing holes that also existed in the shipped SPA guard**: a magic comment in a dynamic import, a backtick specifier, and — worst — a stray `"` in a comment causing the regex to capture a bogus specifier and **miss the real import entirely**. Both guards now share `scripts/module-specifiers.mjs`.

---

### Task 3: The read layer — connection, repos, service

Ports `CampgroundRepo.findPoiDetailByPoi`, `CampgroundRepo.findSummariesByPoiIds` and `CampgroundSearchRepo.searchWithinBoundary` into `apps/web/src/server/`, with the Review Focus failure modes pinned by tests against a real PostGIS container.

**Files:**
- Create: `apps/web/src/server/config.ts`, `apps/web/src/server/db.ts`, `apps/web/src/server/repo/campgroundReadRepo.ts`, `apps/web/src/server/repo/campgroundSearchRepo.ts`, `apps/web/src/server/service/campgroundService.ts`, `apps/web/src/server/dto.ts`
- Test: `apps/web/src/server/repo/campgroundSearchRepo.test.ts`, `apps/web/src/server/repo/campgroundReadRepo.test.ts`
- Create: `apps/web/vitest.config.ts`

**Interfaces:**
- Consumes: `createDb`, `migrateDb`, `type Db`, the schema tables, `ewkt`, and the JSONB codec (`decodeCatalogJson`, `CatalogJsonError`, `locationShape`, `amenitiesShape`, `priceShape`, `contactShape`) from `@roadtrip/db`; `withPostgis` and `type PostgisSession` from `scripts/schema-reference.mjs` for the tests. **Two hazards there:** the relative depth is five levels up from `apps/web/src/server/repo/`, not the four `packages/db/src/types/` uses; and that module is a `.mjs` typed by a sibling `.d.mts`, which `packages/db` resolves under `nodenext` while this app uses `bundler`. Resolve it concretely — a `tsconfig.typecheck.json` including the tests under `nodenext` (mirroring `packages/db`'s split) or a local `.d.ts` shim — and report which. Not a type assertion, not `@ts-expect-error`.
- Produces:
  - `config.ts`: `databaseUrl`, `databasePrepare`, `enabledDataProviders`, `searchResultLimit` from `DATABASE_URL`, `DATABASE_PREPARE`, `ENABLED_DATA_PROVIDERS` (comma-separated), `SEARCH_RESULT_LIMIT`, each with a named `const` default. `databaseUrl` throws a named error when unset.
  - `db.ts`: `serverDb(): Db` — module-level memoized `createDb(config.databaseUrl, { prepare: config.databasePrepare, connection: { default_transaction_read_only: READ_ONLY } })`. First line is `import 'server-only';` so Next's bundler also rejects a client import, independent of Task 2's guard.
  - `dto.ts`: `CampgroundDetailDto`, `CampgroundSummaryDto`, `CampgroundSearchDto` — plain serializable types, every id field `string`, plus `decodeErrors: string[]` (see below).
  - `campgroundSearchRepo.ts`: `searchWithinBoundary(db, boundaryGeoJson, limit, providers): Promise<{ poiIds: string[]; totalInBoundary: number; totalMatching: number; truncated: boolean }>` and `class InvalidBoundaryError extends Error`.
  - `campgroundReadRepo.ts`: `findDetailByPoi(db, poiId: bigint): Promise<CampgroundDetailDto | null>`, `findSummariesByPoiIds(db, poiIds: readonly string[]): Promise<CampgroundSummaryDto[]>`.
  - `campgroundService.ts`: `getCampgroundDetail(poiId: bigint)`, `searchCampgrounds(boundaryGeoJson: string)` — both resolve `serverDb()` and `config` themselves so routes pass no infrastructure.

The SQL is the Kotlin's, unchanged in shape: the `WITH boundary AS … inside AS (…)` CTE with `campground_site_summary` on a `LEFT JOIN LATERAL` and the `COUNT(*) OVER ()` window pair for the search; the `poi_campgrounds`/`pois`/`campgrounds` join with `ST_PointOnSurface` for the reads. Use drizzle's `sql` template with parameter interpolation, never string concatenation, and keep it in the repo modules only. This slice drops the Kotlin's filter predicates (`siteType`, `groupSize`, `amenities`) — the shape that matters is the unfiltered one.

**The projection is a subset, deliberately.** `CampgroundRepo.baseSelectColumns` is 40 columns with every JSONB one cast `::text` so Kotlin can run its own decoders. A thin detail page needs the identity and descriptive fields plus the four JSONB ones that exercise the codec. Reproducing a 40-column projection is not the point; reading through `@roadtrip/db` is.

**JSONB decoding, which is the subtle part.** Step 2 ruled that the JSONB columns carry no `.$type<>`, because the annotation asserted the decoded type for the encoded value. So `decodeCatalogJson(shape, raw)` is the *only* correct read path — a bare `row.amenities as CampgroundAmenity[]` repeats exactly the defect that ruling caught. But the codec is strict and real rows predate it, so decode each JSONB field **independently**: on `CatalogJsonError`, that field becomes `null` and its name is appended to `decodeErrors`. Drift stays visible; one odd field costs a field, not the page.

- [ ] **Step 1: Write the failing tests**

`apps/web/vitest.config.ts` sets a per-test timeout long enough for a PostGIS container boot (name the constant; `packages/db` uses 600_000 ms).

`campgroundSearchRepo.test.ts` boots one PostGIS container in `beforeAll` via `withPostgis` + `migrateDb`, seeds three POIs with campgrounds and campsites inside a known polygon and one outside, then:

```typescript
test('returns poi ids inside the boundary as strings, nearest first', ...)
  // [NEAR_POI_ID, FAR_POI_ID]; every id typeof 'string'; totalInBoundary === 2
test('a result is JSON-serializable', ...)            // JSON.stringify does not throw
test('an empty provider set returns an empty result without querying', ...)
  // { poiIds: [], totalInBoundary: 0, totalMatching: 0, truncated: false }
test('a malformed boundary raises InvalidBoundaryError', ...)
  // 'not geojson', and the Kotlin's own fixture: {"type":"Polygon","coordinates":"nope"}
test('truncated is true when more rows match than the limit returns', ...)
```

`campgroundReadRepo.test.ts`, sharing the container and fixture:

```typescript
test('findDetailByPoi returns a detail dto with string ids', ...)
test('findDetailByPoi returns null for a soft-deleted poi', ...)
test('findDetailByPoi returns null for an unknown id', ...)
test('a detail dto is JSON-serializable', ...)
test('findSummariesByPoiIds returns rows in the order asked, skipping unknown ids', ...)
test('findSummariesByPoiIds carries the site summary, null when no live sites', ...)
test('a malformed JSONB field becomes null and names itself in decodeErrors', ...)
  // seed a campground whose price JSON cannot decode; assert price === null,
  // decodeErrors includes 'price', and name and amenities still decode
test('the read connection rejects a write', ...)
  // db.execute(sql`INSERT INTO pois ...`) rejects; message matches /read-only transaction/
```

The last two are the ones that matter: the first is the difference between a missing field and a dead page, the second is the only proof `default_transaction_read_only` is on the connection rather than merely in a config object.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm run test --workspace @roadtrip/web`
Expected: FAIL — the modules under test do not exist.

- [ ] **Step 3: Implement `config.ts`, `db.ts`, `dto.ts` and the two repos**

Named constants for every default. The POI type discriminator is `POI_TYPES[0]` from `@roadtrip/db`'s catalog schema — `'campground'`, the literal `CampgroundSearch.kt:47` pins — not a new local constant.

**Classifying the two geometry faults is done by message substring, not SQLSTATE.** `backend/src/main/kotlin/ca/floo/roadtrip/support/TopologyFault.kt` matches `"TopologyException"` in the driver's cause chain for a self-intersection, and one of four literal strings — `"are not an array"`, `"are not sufficiently nested"`, `"Too few ordinates in GeoJSON"`, `"invalid GeoJson representation"` — for an invalid geometry. Port those literals as named `const`s and cite the Kotlin file in the commit message.

That file records the list was "confirmed against PostGIS 3.4", so it is version-dependent, and Neon may not run 3.4. Task 5 re-checks it there.

**On the self-intersecting-polygon case:** the Kotlin has a topology-fault branch but `CampgroundSearchRepoTest.kt` has **no** test for it — the branch is shared with `PoisOnRouteService`, which buffers, and GEOS raises `TopologyException` mainly from overlay operations rather than the `ST_Within`/`&&` this query uses. Determine empirically whether a bowtie polygon raises from *this* query shape. If it does, add the test. **If it does not, write no test and say so in the report** — do not keep an assertion that cannot fail, and do not reshape the query to manufacture a fault. Keep the handler either way: it costs nothing and mirrors the Kotlin.

- [ ] **Step 4: Implement `campgroundService.ts`**

Thin: resolve `serverDb()` and `config`, call `searchWithinBoundary` then `findSummariesByPoiIds` on its result, assemble `CampgroundSearchDto`. No SQL, no HTTP.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm run test --workspace @roadtrip/web`, then `npm run typecheck --workspace @roadtrip/web`
Expected: all pass, typecheck clean.

- [ ] **Step 6: Prove the two load-bearing tests can fail**

Change a repo's id mapping from `String(row.id)` back to `row.id` and confirm the serialization and string-id cases go red. Then make `decodeErrors` always empty and confirm the decode test goes red. Revert both; record both outputs in the commit message.

- [ ] **Step 7: Run the guard and commit**

Run: `node scripts/check-server-only-imports.mjs` (exit 0 — `src/server/` exists now but nothing client-side reaches it).

```bash
git add apps/web/src/server apps/web/vitest.config.ts apps/web/package.json package-lock.json
git commit -m "feat(web): port the campground detail and boundary-search reads"
```

---

### Task 4: The routes

Two SSR pages and two JSON route handlers, all `force-dynamic` so `next build` never opens a database connection — which every Vercel build depends on.

**Files:**
- Create: `apps/web/src/app/campgrounds/[poiId]/page.tsx`, `apps/web/src/app/search/page.tsx`, `apps/web/src/app/api/campgrounds/search/route.ts`, `apps/web/src/app/api/health/route.ts`, `apps/web/src/server/boundary.ts`
- Test: `apps/web/src/server/boundary.test.ts`
- Modify: `apps/web/src/app/page.tsx` (link the two pages)

**Interfaces:**
- Consumes: `getCampgroundDetail`, `searchCampgrounds` from Task 3.
- Produces:
  - `boundary.ts`: `parsePoiId(raw: string): bigint | null`, `bboxToPolygonGeoJson(raw: string): string | null`, and `DEFAULT_SEARCH_BBOX`. All pure. `DEFAULT_SEARCH_BBOX` lives here, not in `config.ts`: it is a route-layer presentation default and nothing about it differs per environment.
  - `/campgrounds/<poiId>` — detail page; unparseable or unknown id → `notFound()`.
  - `/search?bbox=w,s,e,n` — list page; missing bbox uses `DEFAULT_SEARCH_BBOX`, malformed bbox renders an error state rather than 500.
  - `/api/campgrounds/search?bbox=…` — the same service call as `/search`, returning JSON and setting `Server-Timing: db;dur=<ms>`. It earns its place as a read endpoint (§10 step 7's subject is "Read API and SSR pages"), and the header makes the database time visible in browser devtools for free — which is how the response-time question gets answered now that the harness is cut.
  - `/api/health` — `SELECT 1` through `serverDb()`; 200 `{ status: 'ok' }` or 503. The fastest way to tell whether a deployment reaches its database.

- [ ] **Step 1: Write the failing tests**

`boundary.test.ts` — pure unit tests, no container:

```typescript
test('parsePoiId accepts a positive integer string', ...)             // '42' → 42n
test('parsePoiId rejects non-numeric, negative, zero, empty, fractional', ...)
  // 'abc', '-1', '0', '', '1.5', '1e3' → null each
test('parsePoiId accepts an id at the bigint boundary without throwing', ...)
  // '9223372036854775807' → that bigint; '92233720368547758070' → null
test('bboxToPolygonGeoJson produces a closed 5-point polygon', ...)
test('bboxToPolygonGeoJson rejects wrong arity, non-numeric, out-of-range lat/lng', ...)
  // '1,2,3', 'a,b,c,d', '-200,0,10,10', '0,-91,10,10' → null each
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm run test --workspace @roadtrip/web -- boundary`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `boundary.ts`**

`parsePoiId` validates against a named `POI_ID_PATTERN` before `BigInt()` so the constructor never throws, and range-checks against a named `MAX_POI_ID`. `bboxToPolygonGeoJson` splits on a named separator, validates arity and the four coordinate ranges against the same named bounds `packages/db/src/schema/catalog.ts` uses, and returns the closed ring.

**`parsePoiId` must canonicalise, and this is a requirement rather than a nicety.** Task 3's review established that `findSummariesByPoiIds` silently returns `[]` for a non-canonical id — `'01001'` matches nothing, where the Kotlin's `Long` keys would have matched — because the port compares id strings. So the route layer is the only thing standing between a leading zero and an empty page. `parsePoiId` returning a `bigint` already canonicalises; the requirement is that **every** id reaching a repo has been through it, and that `'01001'` and `'1001'` therefore behave identically. Add that as a test case.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm run test --workspace @roadtrip/web -- boundary`
Expected: PASS.

- [ ] **Step 5: Implement the two pages and the two route handlers**

Each sets `export const dynamic = 'force-dynamic'`. Every file is a server component or route handler — no `'use client'` anywhere, which is what keeps Task 2's guard quiet and is the point of the slice. Plain semantic HTML; do not reach for `frontend/`'s design system, which is a Vite/Storybook build this app does not share.

The search route wraps only the service call in `performance.now()`, so the reported `db` duration is the read rather than the serialization.

- [ ] **Step 6: Prove `next build` needs no database**

Run: `env -u DATABASE_URL npm run build --workspace @roadtrip/web`
Expected: succeeds. If it fails with a connection error, a route is being prerendered and is missing `force-dynamic` — fix that rather than supplying a build-time `DATABASE_URL`.

**Correction, established by Task 4's mutation check: `force-dynamic` is *not* load-bearing in Next 16.4.3, and this step does not prove that it is.** An earlier draft of this plan asserted that without the directive Next would prerender, open a database connection at build time, and fail every Vercel build. Removing it from all four routes was tried: the no-database build still succeeds and all four routes are still reported dynamic, because GET route handlers are uncached by default and both pages read `params`/`searchParams`. So the directive is **defensive**, protecting against a later edit that would make a route prerenderable — for instance dropping the `searchParams` read from `/search`. Keep it, and guard its presence with a per-route assertion that `dynamic === 'force-dynamic'`, since a passing build cannot distinguish its presence from its absence.

- [ ] **Step 7: Verify the routes serve real data locally**

Start a PostGIS container, apply `migrateDb`, insert fixture rows, run `npm run dev --workspace @roadtrip/web` against it, and fetch all four routes.
Expected: the detail page renders the fixture campground's name; `/search` lists it; the JSON route returns results with a `Server-Timing` header; `/api/health` returns 200. Also fetch `/campgrounds/abc` and `/search?bbox=garbage` and confirm 404 and a rendered error state — not 500.

- [ ] **Step 8: Run the guard and the suite, then commit**

Run: `node scripts/check-server-only-imports.mjs && npm run typecheck --workspace @roadtrip/web && npm run test --workspace @roadtrip/web`

```bash
git add apps/web/src
git commit -m "feat(web): serve campground detail, search, and health routes"
```

---

### Task 5: Neon, the data import, and Vercel

**This task's first job is to answer a question the plan cannot answer on paper**, so step 1 is a feasibility probe and the rest of the task depends on its result.

**The risk, stated plainly.** `make data-import` is `curl -X POST $(ADMIN_BASE)/api/admin/data/import` — it needs a **running Kotlin backend** whose `rawDir` resolves to `data/raw` on local disk (`di/InfraModule.kt:53,140`). Three things follow, and each could block:

1. **The backend runs Flyway on boot**, including `R__grafana_reader_grants.sql`, which does `CREATE ROLE grafana_reader` and `GRANT pg_read_all_stats TO grafana_reader`. Granting a predefined role needs membership WITH ADMIN OPTION or superuser. **Neon's owner role is not a superuser, so this may fail and abort the migration.** The Drizzle `0001` migration carries the same block, so `migrateDb` is not an escape — both paths hit it.
2. **`data/raw` is 1.7 GB and excluded from this worktree** by `sparsePaths`, so the import must run from the main checkout, which other sessions share.
3. **Neon's `branch_logical_size_limit` on this account is 1 GiB.** A full import of every source may not fit.

- [ ] **Step 1: Probe the schema path before anything else**

Create a Neon project, Postgres **16** (matching production), in a sensibly-chosen region — record which, but it need not be comparable against anything. Enable PostGIS: `CREATE EXTENSION IF NOT EXISTS postgis`.

Then run `npm run migrate --workspace @roadtrip/db` with `DATABASE_URL` set to the Neon **direct** (session-mode) endpoint.
Expected, if all is well: both migrations apply, `campground_site_summary` exists, `\d pois` shows `geometry(Geometry,4326)`.

**If the `grafana_reader` block fails:** do **not** edit the migration — it is the schema proven byte-identical to Flyway's, and editing it breaks the equivalence gate. Report the exact failure. That outcome is itself a finding worth having: **C4 names Neon as a datastore that must work**, and "the committed schema migration cannot run on Neon unmodified" is evidence against an assumption the spec treats as settled. Try the obvious narrow workarounds first (pre-creating the role, granting from a role that can) and say which, if any, worked.

- [ ] **Step 2: Populate it with the importer, not a copy**

Point a Kotlin backend at the Neon database (`POSTGRES_URL` as a JDBC URL with `sslmode=require`) and run `make data-import` against it, from a checkout that has `data/raw`.

Work out the least invasive way to do this and **say what you landed on**. Running a local backend process against a remote database changes nothing on mini-ca and touches no production service — confirm that is true of whatever you choose. If the only workable route would disturb a running service or require changes to deploy tooling, **stop and report rather than improvising**: the constraint that is firm is *no copy of production*, not any particular route to data.

Start with a single `TARGET` rather than `--all`, both to keep inside the size limit and to fail fast.
Expected: rows land in `campgrounds`, `campsites`, `pois` and `poi_campgrounds`; record the counts and the database size against the 1 GiB limit.

- [ ] **Step 3: Re-check the geometry-fault classification on Neon**

Record `SELECT postgis_full_version()` and re-run Task 3's malformed-boundary case against Neon. Task 3's classification is a PostGIS-3.4-confirmed string match, so this is where a version difference would surface as a 500 on Vercel that local Postgres does not have.
Expected: it classifies the same way. If not, report it — do not broaden the match to paper over it.

- [ ] **Step 4: Determine `prepare` for Neon's pooler, and the rest of the serverless connection config, and record them beside the connection string**

**Two items carried here from Task 4's review, because they are deployment concerns rather than route concerns:**

- **The postgres.js client sets no `max`, `connect_timeout` or statement timeout.** Task 4's `/api/health` bounds its own *response* with a 5-second race, but nothing bounds the *resource*: a blackholed database can pin pooled connections, and a hang on a page or the search API surfaces as a platform 504 rather than the 500 the error matrix predicts. A serverless function holding up to postgres.js's default of 10 connections per instance is also the wrong shape for Vercel. Decide `max`, `connect_timeout` and an idle timeout deliberately for this environment and record them with `prepare`.
- **`/api/health` is public and runs a query on every hit.** Neon autosuspends an idle compute, so an uptime checker or a crawler polling this endpoint would hold the database awake and bill for it. Decide whether to keep it open, gate it, or make it cheaper, and say which.

§2 is explicit that this is pooler- and version-dependent and must be determined, not assumed. Neon's pooler is PgBouncer in transaction mode, with protocol-level prepared statements since PgBouncer 1.22, so expect `prepare: true` to work — and prove it rather than trusting the changelog. Run the search query repeatedly over concurrent pooled connections with `prepare: true`, then `false`, and record which succeeded.
Expected: a definite answer, written into `docs/web-app.md` next to the connection string with the date and the endpoint it applies to — §2 says "rather than in someone's memory." Set `DATABASE_PREPARE` in Vercel accordingly.

- [ ] **Step 5: Deploy to Vercel**

Connect `wwchen/roadtrip`. Root Directory `apps/web`, **"Include files outside root directory" enabled** — with `./` the workspace build does not resolve. Install command `npm ci` at the root; build command `npm run build` at the root, so `packages/*/dist` exists before `next build`. Environment variables `DATABASE_URL` (pooled endpoint), `DATABASE_PREPARE`, `ENABLED_DATA_PROVIDERS` — all server-only, **none** `NEXT_PUBLIC_`-prefixed.

The root build also builds `frontend`'s Vite bundle, which Vercel never serves. That waste is deliberate: it is the exact command `make test` and CI run, so it cannot drift from what they prove. Note the cost; do not add a narrower build script to avoid it.

`data/raw` is 1.7 GB and 19,136 files that Vercel clones on every build. Measured locally, a `--depth=1` clone transfers 133 MB packed and checks out 1.9 GB / 22,163 files in 10.9 s. Record the real clone and build durations from the first build's logs and say whether it needs addressing — the alternatives (LFS migration, submodule, history rewrite) are all disruptive to a repository other sessions are working in. Recommend; do not act.

- [ ] **Step 6: Verify the deployment serves real data, and note what it feels like**

Fetch `/api/health`, a real `/campgrounds/<poiId>`, `/search`, and `/api/campgrounds/search`.
Expected: health 200, pages render real campground names from the imported data.

Then note the observed response times on `/api/campgrounds/search` — the heaviest read — including the `Server-Timing` database figure, and say whether anything looks alarming. A handful of observations, not a distribution. If something is alarming, say what and why; if not, say that.

- [ ] **Step 7: Commit**

```bash
git add docs/web-app.md
git commit -m "feat(web): serve the read app from Vercel against a Neon database"
```

---

### Task 6: Record what was decided

- [ ] **Step 1: Write the rulings document**

`docs/superpowers/2026-10-28-nextjs-read-app-rulings.md`, in the style of `2026-10-27-step-2-rulings.md`: each call taken without asking, and what it costs if it was wrong. Known going in: `apps/web`; `default_transaction_read_only` with its not-a-security-boundary caveat; a subset projection rather than 40 columns; per-field JSONB decoding with `decodeErrors`; the filter predicates dropped from the search port; ids as `string` at the repo boundary; the three parsing holes found in the shipped SPA guard and the decision to fix both guards at once.

Note which of the plan's stated hazards proved real in practice and which did not — the project's habit is that the live system wins over the document.

- [ ] **Step 2: Note the Neon finding in the spec, if there is one**

If step 1 of Task 5 found that the committed schema cannot run on Neon unmodified, record it against C4 — which asserts Neon must work — rather than leaving it in a rulings file. If it ran clean, say so there instead; C4 needs no amendment.

- [ ] **Step 3: Run the full suite**

Run: `make test`
Expected: green. Run it with nothing else dispatched — it boots Docker and a Gradle build, and a concurrent vitest run has already starved its fork pool once in this plan's execution.

- [ ] **Step 4: Commit and open the PR**

Open a PR against `master`. Do not merge it.

---

## Self-review

**Spec coverage.** §1 (Next.js importing `@roadtrip/db`): Tasks 1, 3. §2 (`prepare` determined per deployment and recorded beside the connection string): Task 3's config, Task 5 step 4. §3 (schema from the proven-equivalent migrations): Task 5 step 1. §10 step 7 (read API and SSR pages for campground): Tasks 3, 4, partially and by design — the scope fence says what is left. §11 (Vercel owns its own secrets): Task 5 step 5. C4 (Neon must work): Task 5, which may return evidence against it. §9's P1: deliberately not a deliverable; see the scope fence. §5, §6, §8.1 and §10 steps 4, 8–12: out of scope.

**Step scan.** Every step names a command and an expected result, or a signature and its file. Task 5 steps 1 and 2 are the two carrying real uncertainty, and both say explicitly what to do if the expected path fails — report rather than improvise.

**Type consistency.** `searchWithinBoundary` returns `poiIds: string[]` and `findSummariesByPoiIds` takes `readonly string[]`, so they compose. `findDetailByPoi` takes `bigint` and `parsePoiId` returns `bigint | null`, so the route converts once at the edge and the service never sees a string id. `Db` is `@roadtrip/db`'s exported type throughout.

**Review Focus.** All five have a named test: bigint serialization (Task 3, two cases plus a mutation proof), JSONB decode failure (Task 3, with its own mutation proof), malformed boundary (Task 3, Task 4 step 7, re-checked in Task 5 step 3), empty provider set (Task 3), bad POI id (Task 4, four cases). The read-only claim gets a container test in Task 3.

**Proportion.** Shorter than the draft it replaces, because a whole deployment target, a probe harness and a report task came out. Code blocks are test names and assertions; the two SQL bodies are referenced in the Kotlin source rather than transcribed.
