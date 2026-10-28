# Thin Next.js Read App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A read-only Next.js app serving real campground data from `@roadtrip/db`, running both as a new Compose service on mini-ca against production Postgres and on Vercel against a Neon restore, with the §9 P1 read-latency probe measured on both.

**Architecture:** A new `apps/web` npm workspace. Server-rendered pages call a service, which calls repos that hold the SQL — the same `routes -> service -> repo` layering `AGENTS.md` mandates for the Kotlin backend, ported shape-for-shape from `CampgroundRepo` and `CampgroundSearchRepo`. Nothing runs on the client that could reach a Postgres driver, and a new guard proves it. On mini-ca the app joins the existing `roadtrip` Compose network from its **own** Compose project, so no existing service's definition changes. On Vercel it reads a Neon Postgres 16 database whose schema comes from `@roadtrip/db`'s own migrations and whose rows come from a `pg_dump --data-only` of production's catalog tables.

**Tech Stack:** Next.js 16.4.3 (App Router, `output: 'standalone'`), React 19.3.1, `@roadtrip/db` (Drizzle 0.45.4 + postgres.js 3.4.9), vitest, Neon Postgres 16 + PostGIS, Vercel.

**Spec:** `docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md` (§1, §2, §3, §9, §10 step 7, §11; constraints C2 and C4). Prior rulings: `docs/superpowers/2026-10-27-step-2-rulings.md`.

## Scope fence

This is a deliberately thin slice pulled forward out of §10 step 7. **In scope:** two SSR pages and two JSON route handlers, read-only, over the existing catalog tables. **Out of scope and must not be touched:** the Kotlin backend, `recgov-companion`, Postgres, the observability services, `frontend/`, auth, the availability read path, writes of any kind, and `deploy.sh` / `deploy.yml`. The frozen-contract gate, the Playwright port and the preview-deploy lifecycle that §10 step 7 actually requires are **not** in scope; this plan does not close step 7.

## Open questions — answer before Task 6

These block the two deployment tasks, not the first five. Tasks 1–5 can proceed while they are open.

1. **SSH to mini-ca is blocked in this session.** `ssh mini@mini-ca` is refused by the sandbox classifier ("Production Reads"). Tasks 6 and 7 need it to add the Compose service, take the `pg_dump`, and run the mini-ca half of the probe. Either a permission rule for `Bash(ssh mini@mini-ca *)`, or the user runs the handful of commands each task names.
2. **The public hostname.** `caddy/Caddyfile` only imports per-sandbox vhosts, and Cloudflare Zero Trust — not this repo — owns which hostnames reach `caddy:80`. A committed Caddy vhost is enough **only if** a `*.floo.ca` wildcard rule already points at the tunnel's caddy origin. Which hostname should the app get, and does that wildcard exist, or is a new Zero Trust public-hostname rule needed? That rule is a dashboard change nobody here can make.
3. **Region pair.** The two latency numbers are only comparable with the regions stated. Where is mini-ca physically, and which Neon region + Vercel function region should the managed side use? Default if unanswered: Neon `aws-us-west-2` and the Vercel function pinned to `pdx1`, with both recorded alongside the numbers.
4. **How read-only is enforced on production.** This plan uses `default_transaction_read_only=on` as a connection parameter, which Postgres enforces and which requires **no change to the production database**. A dedicated `nextweb_reader` role with `GRANT SELECT` is the better long-term answer but needs a one-time `CREATE ROLE` on production. Confirm the connection-parameter approach is acceptable for now.
5. **`apps/web` as the directory.** `apps/*` is a new top-level workspace glob, chosen because §1 implies a second runtime (`apps/worker`) later. Say so if a top-level `web/` is preferred.

## Global Constraints

- **No inline magic constants.** Every numeric, string and duration literal at a call site is a named `const` (or env-driven config where it is operationally tunable). `AGENTS.md`.
- **No code comments.** None inline, none in doc comments. Rationale goes in the commit message. User memory `terse-code-comments`.
- **Layering is `routes -> service -> repo`.** Pages and route handlers parse inputs, call a service, set status codes and return DTOs. SQL lives only in repo modules. `AGENTS.md`.
- **`packages/db` has no `prepare` script, on purpose.** An install alone leaves `packages/db/dist` missing, so anything that consumes it must run the root `npm run build` first. `apps/web` gets no `prepare` script either, for the same reason.
- **No `NEXT_PUBLIC_` anything.** It is inlined into the client bundle and served to every visitor. This app has no client configuration, so the correct count is zero, and Task 2's guard enforces that.
- **Node `>=22.12.0`** in every new `package.json` `engines`, matching the other workspaces.
- **Exact version pins** for new dependencies, matching how `packages/db` pins `drizzle-orm` and `postgres`.
- **Stage explicit paths.** Never `git add -A`; parallel sessions share this checkout. User memory `stage-explicit-paths`.
- **A gate must be proven able to fail.** Every check this plan adds gets a mutation test, or a recorded demonstration that breaking the thing it guards turns it red. Three gates on this project have shipped unable to fail.

## Review Focus

Five input classes the spec implies and no task's happy path exercises. Each has its test named in the task that owns the code.

- **A `bigint` id reaching `JSON.stringify`.** `packages/db` declares ids as `bigserial(..., { mode: 'bigint' })`, so rows carry JS `BigInt`, which `JSON.stringify` throws `TypeError` on and which cannot cross the React server/client boundary. A route handler returning a raw row 500s on every request. Repos must convert at their own boundary. → Task 3.
- **A malformed boundary.** `ST_GeomFromGeoJSON` raises on non-GeoJSON; `CampgroundSearchRepo` maps that to `InvalidBoundaryException` and the route to a 4xx. A port that lets the driver error escape returns 500 for a user typo. The Kotlin classifies it by matching four literal strings confirmed against PostGIS 3.4, so the behaviour is version-dependent and has to be re-checked on Neon. → Task 3, Task 4, re-verified in Task 7.
- **A self-intersecting polygon.** PostGIS raises a topology fault; the Kotlin repo logs `TOPOLOGY_FAULT_EMPTY_RESULT` and returns an empty result rather than failing. Same version-dependent string match. → Task 3, re-verified in Task 7.
- **An empty provider set.** `CampgroundSearchRepo` short-circuits when `enabledDataProviders` is empty. Without that, the port builds `IN ()` and Postgres rejects the statement as a syntax error. An unset env var is the likeliest way to hit it. → Task 3.
- **A POI id that is not a POI id.** `/campgrounds/abc`, `/campgrounds/-1`, and an id past `bigint` range must be 404 or 400, never a 500 from `BigInt()` throwing `SyntaxError`. → Task 4.

---

### Task 1: The `apps/web` workspace, registered everywhere a new top-level directory must be

A new top-level directory is load-bearing in four places on this repo, and missing any one of them fails a gate or silently empties future worktrees. This task is that registration plus a Next app that builds and serves one static page — no database yet.

**Files:**
- Create: `apps/web/package.json`, `apps/web/tsconfig.json`, `apps/web/next.config.ts`, `apps/web/src/app/layout.tsx`, `apps/web/src/app/page.tsx`, `apps/web/.gitignore`
- Modify: `package.json` (add `apps/*` to `workspaces`), `.claude/settings.json` (add `apps` to `worktree.sparsePaths`), `.github/workflows/ci.yml` (`changes` filter output `web`, a `web-tests` job, `ci-passed` needs), `Makefile` (the `test` target)

**Interfaces:**
- Consumes: nothing.
- Produces: the `@roadtrip/web` workspace, built by the root `npm run build` through `scripts/build-workspaces.mjs`'s dependency ordering. Scripts: `build` (`next build`), `dev`, `typecheck` (`tsc --noEmit`), `test` (`vitest run`). No `prepare` script.

- [ ] **Step 1: Write the failing registration check**

Nothing new to write — `scripts/check-worktree-sparse-paths.mjs` already fails on an unregistered tracked directory. Create `apps/web/package.json` with name `@roadtrip/web`, version `0.1.0`, `private: true`, `type: module`, `engines.node >=22.12.0`, dependencies `next` `16.4.3`, `react` `19.3.1`, `react-dom` `19.3.1`, and devDependencies `@types/node`, `@types/react`, `@types/react-dom`, `typescript`, `vitest` at the same ranges `frontend/package.json` uses. Add `apps/*` to the root `workspaces`. Do **not** yet touch `.claude/settings.json`.

- [ ] **Step 2: Run the guard to verify it fails**

Run: `node scripts/check-worktree-sparse-paths.mjs`
Expected: exit 1, naming `apps` under "Tracked directories missing from Claude worktrees".

This is the task's proof that the registration gate can fail. Record the output in the commit message.

- [ ] **Step 3: Register `apps` in `.claude/settings.json` → `worktree.sparsePaths`**

Insert `"apps"` in the existing alphabetical position (before `backend`).

- [ ] **Step 4: Run the guard to verify it passes**

Run: `node scripts/check-worktree-sparse-paths.mjs`
Expected: `worktree sparsePaths ok — 21 cone-mode entries ...` (20 today, plus `apps`)

- [ ] **Step 5: Add the minimal App Router app**

`apps/web/next.config.ts` exports a config with `output: 'standalone'` (the mini-ca image needs it) and `outputFileTracingRoot` set to the repo root two levels up, because the workspace root holds the hoisted `node_modules`. `apps/web/tsconfig.json` extends `../../tsconfig.base.json` with `jsx: 'preserve'`, `noEmit: true`, `moduleResolution: 'bundler'`, `lib: ['ES2023', 'DOM', 'DOM.Iterable']`, the Next plugin, `composite: false`, and `paths` mapping `@/*` to `./src/*` — mirroring `frontend/tsconfig.json`'s conventions. `layout.tsx` is a root layout with `html`/`body`; `page.tsx` renders the app name and links to the two real routes added in Task 4.

`apps/web/.gitignore` ignores `.next` and `next-env.d.ts`.

- [ ] **Step 6: Install and build from the root**

Run: `npm install && npm run build`
Expected: the lockfile gains the `apps/web` workspace, `scripts/build-workspaces.mjs` runs `next build` for `@roadtrip/web` **after** `@roadtrip/domain` and `@roadtrip/db`, and the build succeeds.

Then run: `node --test scripts/check-single-lockfile.test.mjs && node scripts/check-package-boundaries.mjs`
Expected: both pass. `check-package-boundaries.mjs` hardcodes `FRONTEND_DIRECTORY = 'frontend'`, so it does not cover `apps/web` — that is Task 2's job, not a regression here.

- [ ] **Step 7: Wire `make test` and CI**

In `Makefile`'s `test` target, after the `@roadtrip/db` lines, add `npm run typecheck --workspace @roadtrip/web`, `npm run test --workspace @roadtrip/web`, and `npm run build --workspace @roadtrip/web`.

In `.github/workflows/ci.yml`: add a `web` output to the `changes` job with filter paths `apps/**`, `packages/**`, `package.json`, `package-lock.json`, `tsconfig.base.json`, `scripts/build-workspaces.mjs`, `scripts/check-server-only-imports.mjs`, `scripts/check-server-only-imports.test.mjs`, `.github/workflows/ci.yml`; add a `web-tests` job modelled on `package-tests` (checkout, setup-node 22 with npm cache, `npm ci`, `npm run build`, then typecheck, test and build for `@roadtrip/web`); add `web-tests` to `ci-passed`'s `needs`.

- [ ] **Step 8: Run the full suite**

Run: `make test`
Expected: green. If `npm ci` is slow, the relevant assertion is that the new lines pass; a full `make test` still has to be green before the commit.

- [ ] **Step 9: Commit**

```bash
git add apps/web package.json package-lock.json .claude/settings.json .github/workflows/ci.yml Makefile
git commit -m "feat(web): register the apps/web Next.js workspace"
```

---

### Task 2: The client-boundary guard, written before the code it guards

`scripts/check-package-boundaries.mjs` hardcodes `FRONTEND_DIRECTORY = 'frontend'`, so `apps/web` is not covered by it — and should not be, because a Next.js app legitimately imports `@roadtrip/db` on the server. The thing that must never happen is a **client** component reaching it, directly or through a helper. That is a different guard and this task writes it. §10 step 3: gates before the code they guard.

**Files:**
- Create: `scripts/check-server-only-imports.mjs`, `scripts/check-server-only-imports.test.mjs`
- Modify: `Makefile` (the `test` target), `.github/workflows/ci.yml` (the `web-tests` job)

**Interfaces:**
- Consumes: Task 1's `apps/web` directory.
- Produces: `export async function findClientBoundaryViolations(repoRoot: string): Promise<string[]>` — a list of human-readable violation strings, empty when clean. Same shape and same CLI-vs-import split as `check-package-boundaries.mjs`, so the two read alike.

The rule, in two parts:

1. **Reachability.** A file is a *client file* if it declares `'use client'`, or if it is reachable through relative imports from one that does. No client file may import `@roadtrip/db` (or a subpath, or a relative path that resolves inside `packages/db`), nor any path under `apps/web/src/server/`.
2. **No `NEXT_PUBLIC_`.** No file anywhere under `apps/web` may reference a `NEXT_PUBLIC_`-prefixed name. This app has no client configuration, so the correct count is zero, and anything prefixed that way is inlined into the bundle every visitor downloads.

Reuse `check-package-boundaries.mjs`'s `MODULE_SPECIFIER` regex, `SOURCE_EXTENSION`, and `SKIPPED_DIRECTORIES` (adding `.next`) so both guards see the same import forms — static, re-export, dynamic `import()`, and `require()`.

- [ ] **Step 1: Write the failing tests**

`scripts/check-server-only-imports.test.mjs`, in `check-package-boundaries.test.mjs`'s style: a `fixture()` helper that writes files into `apps/web/` under a `mkdtemp` root, and `node:test` cases.

```javascript
test('rejects a client component importing @roadtrip/db in every import form', ...)
// 'use client' + each of: static, re-export, subpath, dynamic import(), require()
// → 5 violations, each naming the file and the specifier

test('rejects a client component reaching the db through a helper chain', ...)
// Button.tsx ('use client') -> ./lib/format.ts -> ./lib/rows.ts -> '@roadtrip/db'
// → 1 violation, naming src/lib/rows.ts and the client entry that reaches it

test('rejects a client component importing src/server/', ...)
// 'use client' + "../server/repo/campgroundSearchRepo.js" → 1 violation

test('rejects NEXT_PUBLIC_ in any file, server or client', ...)
// src/app/page.tsx reading process.env.NEXT_PUBLIC_MAPBOX_TOKEN,
// and src/server/db.ts reading it too → 2 violations

test('allows a server component importing @roadtrip/db', ...)
// no 'use client' anywhere + "import { pois } from '@roadtrip/db'" → []

test('allows a client component importing @roadtrip/domain and lookalikes', ...)
// '@roadtrip/domain', '@roadtrip/db-tools', a local ./packages/db → []

test('allows a server-only module imported by another server module', ...)
// src/app/page.tsx (no directive) -> ../server/service/campgrounds.js -> '@roadtrip/db' → []
```

The helper-chain and the server-component cases are the two that matter: the first is the real bug, the second is the false positive that would make the guard useless.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `node --test scripts/check-server-only-imports.test.mjs`
Expected: FAIL — `Cannot find module './check-server-only-imports.mjs'`.

- [ ] **Step 3: Implement `findClientBoundaryViolations(repoRoot)` in `scripts/check-server-only-imports.mjs`**

Walk `apps/web` for source files. Pass one: record each file's resolved relative imports and whether its first non-comment, non-blank line is a `'use client'` (or `"use client"`) directive. Pass two: BFS from every client-directive file over the recorded relative edges to get the client closure. Pass three: for every file in that closure, report any specifier that reaches `@roadtrip/db` or resolves under `apps/web/src/server/`; name the client entry that reaches it, since that is what the person has to fix. Separately, report every `NEXT_PUBLIC_` occurrence with its file and line.

Resolve a relative specifier by trying the literal path, then each `SOURCE_EXTENSION` suffix, then `/index` with each suffix — a specifier that resolves to nothing is not an edge and not a violation.

`main()` mirrors `check-package-boundaries.mjs`: print the violations, set `process.exitCode = 1`, and on success print a one-line OK naming what was checked.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `node --test scripts/check-server-only-imports.test.mjs`
Expected: PASS, 7/7.

- [ ] **Step 5: Prove the guard can fail against the real tree**

Run it clean first: `node scripts/check-server-only-imports.mjs` → exit 0.

Then inject each of the two failure modes into the real `apps/web` and confirm a red exit, reverting after each:

```bash
printf "'use client';\nimport { pois } from '@roadtrip/db';\n" > apps/web/src/app/mutation-probe.tsx
node scripts/check-server-only-imports.mjs; echo "exit=$?"   # expect exit=1
rm apps/web/src/app/mutation-probe.tsx

printf "export const t = process.env.NEXT_PUBLIC_TOKEN;\n" > apps/web/src/app/mutation-probe.ts
node scripts/check-server-only-imports.mjs; echo "exit=$?"   # expect exit=1
rm apps/web/src/app/mutation-probe.ts

git status --short apps/web   # expect empty
```

Both probes must print `exit=1`. Record both outputs in the commit message. A guard whose failure has not been observed is not yet a guard.

- [ ] **Step 6: Wire `make test` and CI**

In `Makefile`'s `test` target, next to the existing `check-package-boundaries` line, add `node --test scripts/check-server-only-imports.test.mjs && node scripts/check-server-only-imports.mjs` — the test file **and** the script, because the gate's ability to fail lives in the test file and running only the bare script is the exact defect ruling 81 records.

Add the same two commands as a step in the `web-tests` job.

- [ ] **Step 7: Run the suite**

Run: `node --test scripts/check-server-only-imports.test.mjs && node scripts/check-server-only-imports.mjs && node scripts/check-package-boundaries.mjs`
Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add scripts/check-server-only-imports.mjs scripts/check-server-only-imports.test.mjs Makefile .github/workflows/ci.yml
git commit -m "feat(web): guard the client boundary against @roadtrip/db and NEXT_PUBLIC_"
```

---

### Task 3: The read layer — connection, repos, service

Ports `CampgroundRepo.findPoiDetailByPoi`, `CampgroundRepo.findSummariesByPoiIds` and `CampgroundSearchRepo.searchWithinBoundary` into `apps/web/src/server/`, with the Review Focus failure modes pinned by tests against a real PostGIS container.

**Files:**
- Create: `apps/web/src/server/config.ts`, `apps/web/src/server/db.ts`, `apps/web/src/server/repo/campgroundReadRepo.ts`, `apps/web/src/server/repo/campgroundSearchRepo.ts`, `apps/web/src/server/service/campgroundService.ts`, `apps/web/src/server/dto.ts`
- Test: `apps/web/src/server/repo/campgroundSearchRepo.test.ts`, `apps/web/src/server/repo/campgroundReadRepo.test.ts`
- Create: `apps/web/vitest.config.ts`

**Interfaces:**
- Consumes: `createDb`, `migrateDb`, `type Db`, the schema tables and `ewkt` from `@roadtrip/db`; `withPostgis` and `type PostgisSession` from `scripts/schema-reference.mjs` (as `packages/db/src/types/geometry.test.ts` does) for the tests.
- Produces:
  - `config.ts`: `readonly databaseUrl: string`, `readonly databasePrepare: boolean`, `readonly enabledDataProviders: ReadonlySet<string>`, `readonly searchResultLimit: number` — read from `DATABASE_URL`, `DATABASE_PREPARE`, `ENABLED_DATA_PROVIDERS` (comma-separated), `SEARCH_RESULT_LIMIT`, each with a named `const` default. `databaseUrl` throws a named error when unset.
  - `db.ts`: `export function serverDb(): Db` — a module-level memoized `createDb(config.databaseUrl, { prepare: config.databasePrepare, connection: { default_transaction_read_only: READ_ONLY } })`. First line of the file is `import 'server-only';` so Next's own bundler also rejects a client import, independent of Task 2's guard.
  - `dto.ts`: `CampgroundDetailDto`, `CampgroundSummaryDto`, `CampgroundSearchDto` — plain serializable types whose every id field is `string`, never `bigint`.
  - `campgroundSearchRepo.ts`: `searchWithinBoundary(db: Db, boundaryGeoJson: string, limit: number, providers: ReadonlySet<string>): Promise<{ poiIds: string[]; totalInBoundary: number; totalMatching: number; truncated: boolean }>`, plus `export class InvalidBoundaryError extends Error`.
  - `campgroundReadRepo.ts`: `findDetailByPoi(db: Db, poiId: bigint): Promise<CampgroundDetailDto | null>` and `findSummariesByPoiIds(db: Db, poiIds: readonly string[]): Promise<CampgroundSummaryDto[]>`.
  - `campgroundService.ts`: `getCampgroundDetail(poiId: bigint): Promise<CampgroundDetailDto | null>` and `searchCampgrounds(boundaryGeoJson: string): Promise<CampgroundSearchDto>`, both resolving `serverDb()` and `config` themselves so routes pass no infrastructure.

The two SQL bodies are the Kotlin ones, unchanged in shape: the `WITH boundary AS ... inside AS (...)` CTE with `campground_site_summary` on a `LEFT JOIN LATERAL` and the `COUNT(*) OVER ()` window pair for `campgroundSearchRepo`; the `poi_campgrounds`/`pois`/`campgrounds` join with `ST_PointOnSurface` and the same lateral for `campgroundReadRepo`. Use drizzle's `sql` template with parameter interpolation — not string concatenation — and keep them in the repo modules only. This thin slice drops the Kotlin filter predicates (`siteType`, `groupSize`, `amenities`): the query shape the probe measures is the unfiltered one, which is the `passes = true` branch.

- [ ] **Step 1: Write the failing tests**

`apps/web/vitest.config.ts` sets a per-test timeout long enough for a PostGIS container boot (name the constant; `packages/db` uses 600_000 ms).

`campgroundSearchRepo.test.ts` boots one PostGIS container in `beforeAll` via `withPostgis` + `migrateDb`, seeds a fixture of three POIs with campgrounds and campsites inside a known polygon and one outside it, then:

```typescript
test('returns poi ids inside the boundary as strings, nearest first', async () => {
  const result = await searchWithinBoundary(db, BOUNDARY_GEOJSON, LIMIT, PROVIDERS);
  expect(result.poiIds).toEqual([NEAR_POI_ID, FAR_POI_ID]);
  expect(result.poiIds.every((id) => typeof id === 'string')).toBe(true);
  expect(result.totalInBoundary).toBe(2);
});

test('a result is JSON-serializable', async () => {
  const result = await searchWithinBoundary(db, BOUNDARY_GEOJSON, LIMIT, PROVIDERS);
  expect(() => JSON.stringify(result)).not.toThrow();
});

test('an empty provider set returns an empty result without querying', async () => {
  const result = await searchWithinBoundary(db, BOUNDARY_GEOJSON, LIMIT, new Set());
  expect(result).toEqual({ poiIds: [], totalInBoundary: 0, totalMatching: 0, truncated: false });
});

test('a malformed boundary raises InvalidBoundaryError', async () => {
  await expect(searchWithinBoundary(db, 'not geojson', LIMIT, PROVIDERS))
    .rejects.toThrow(InvalidBoundaryError);
});

test('a self-intersecting polygon returns an empty result rather than throwing', async () => {
  const result = await searchWithinBoundary(db, BOWTIE_GEOJSON, LIMIT, PROVIDERS);
  expect(result.poiIds).toEqual([]);
  expect(result.totalInBoundary).toBe(0);
});

test('truncated is true when more rows match than the limit returns', async () => {
  const result = await searchWithinBoundary(db, BOUNDARY_GEOJSON, 1, PROVIDERS);
  expect(result.truncated).toBe(true);
});
```

`campgroundReadRepo.test.ts`, sharing the same container and fixture:

```typescript
test('findDetailByPoi returns a detail dto with string ids', ...)
  // every id field typeof 'string'; name and dataProvider match the fixture
test('findDetailByPoi returns null for a soft-deleted poi', ...)
test('findDetailByPoi returns null for an unknown id', ...)
test('a detail dto is JSON-serializable', ...)          // JSON.stringify does not throw
test('findSummariesByPoiIds returns rows in the order asked, skipping unknown ids', ...)
test('findSummariesByPoiIds carries the site summary, null when the campground has no live sites', ...)
test('the read connection rejects a write', ...)
  // db.execute(sql`INSERT INTO pois ...`) rejects; message matches /read-only transaction/
```

The last one is the only proof that `default_transaction_read_only` is actually on the connection rather than merely written in a config object.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm run test --workspace @roadtrip/web`
Expected: FAIL — the modules under test do not exist.

- [ ] **Step 3: Implement `config.ts`, `db.ts`, `dto.ts` and the two repos**

Named constants for every default: `DEFAULT_SEARCH_RESULT_LIMIT`, `DEFAULT_DATABASE_PREPARE`, `DEFAULT_ENABLED_DATA_PROVIDERS`, `READ_ONLY`, `PROVIDER_SEPARATOR`. The POI type discriminator is `POI_TYPES[0]` from `@roadtrip/db`'s `catalog` schema — `'campground'`, the same literal `CampgroundSearch.kt:47` pins — not a new local constant.

Ids convert to `string` inside the repo, before the DTO is constructed — that is the seam that keeps `BigInt` out of every caller.

**Classifying the two geometry faults is the subtle part, and the Kotlin does it by message substring, not by SQLSTATE.** `backend/src/main/kotlin/ca/floo/roadtrip/support/TopologyFault.kt` matches `"TopologyException"` anywhere in the driver's cause chain for a self-intersection, and one of four literal strings — `"are not an array"`, `"are not sufficiently nested"`, `"Too few ordinates in GeoJSON"`, `"invalid GeoJson representation"` — for a structurally invalid geometry. Port those literals as named `const`s and cite the Kotlin file they came from in the commit message.

That file also records that the list was "confirmed against PostGIS 3.4" — so **it is a version-dependent string match, and Neon may not run PostGIS 3.4.** Treat that as this task's live-system-beats-the-document risk: Task 7 step 3 must re-run the malformed-boundary and self-intersecting-polygon cases against Neon and record whether the same classification holds. If Neon's PostGIS emits different text, the fault that should be a 4xx becomes a 500 on the Vercel deployment only, which is exactly the kind of divergence a probe exists to find. Note it either way.

- [ ] **Step 4: Implement `campgroundService.ts`**

Thin: resolve `serverDb()` and `config`, call `searchWithinBoundary` then `findSummariesByPoiIds` with its result, and assemble `CampgroundSearchDto`. No SQL, no HTTP.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm run test --workspace @roadtrip/web`
Expected: PASS, all cases.

Then run: `npm run typecheck --workspace @roadtrip/web`
Expected: clean.

- [ ] **Step 6: Prove the serialization test can fail**

Change one repo's id mapping from `String(row.id)` back to `row.id`, run the suite, and confirm the `JSON-serializable` and `string ids` cases go red. Revert. Record the output in the commit message — ruling 78's lesson is that an assertion nobody has watched fail is not evidence.

- [ ] **Step 7: Commit**

```bash
git add apps/web/src/server apps/web/vitest.config.ts apps/web/package.json package-lock.json
git commit -m "feat(web): port the campground detail and boundary-search reads"
```

---

### Task 4: The routes

Two SSR pages and two JSON route handlers. All four are `force-dynamic` so `next build` never opens a database connection — which the mini-ca image build and every Vercel build depend on.

**Files:**
- Create: `apps/web/src/app/campgrounds/[poiId]/page.tsx`, `apps/web/src/app/search/page.tsx`, `apps/web/src/app/api/probe/campground-search/route.ts`, `apps/web/src/app/api/health/route.ts`, `apps/web/src/server/boundary.ts`
- Test: `apps/web/src/server/boundary.test.ts`
- Modify: `apps/web/src/app/page.tsx` (link the two pages)

**Interfaces:**
- Consumes: `getCampgroundDetail`, `searchCampgrounds` from Task 3's service.
- Produces:
  - `boundary.ts`: `parsePoiId(raw: string): bigint | null` and `bboxToPolygonGeoJson(raw: string): string | null` — a `w,s,e,n` bbox string to a GeoJSON polygon, `null` on anything malformed. Pure, no database.
  - `/campgrounds/<poiId>` — the detail page. Unparseable or unknown id → `notFound()`.
  - `/search?bbox=w,s,e,n` — the list page. Missing bbox uses `DEFAULT_SEARCH_BBOX`; malformed bbox renders an error state, not a 500.
  - `/api/probe/campground-search?bbox=...` — the same service call as `/search`, returning JSON and setting `Server-Timing: db;dur=<ms>`. This is the probe's measurement surface: it isolates the read from React rendering, which is what §9 P1 asks about.
  - `/api/health` — `SELECT 1` through `serverDb()`; 200 `{ status: 'ok' }` or 503. The Compose healthcheck targets this.

- [ ] **Step 1: Write the failing tests**

`boundary.test.ts` — pure unit tests, no container:

```typescript
test('parsePoiId accepts a positive integer string', ...)        // '42' → 42n
test('parsePoiId rejects non-numeric, negative, zero, empty and fractional', ...)
  // 'abc', '-1', '0', '', '1.5', '1e3' → null for each
test('parsePoiId accepts an id at the bigint boundary without throwing', ...)
  // '9223372036854775807' → that bigint; '92233720368547758070' → null (out of range)
test('bboxToPolygonGeoJson produces a closed 5-point polygon with SRID-free coordinates', ...)
test('bboxToPolygonGeoJson rejects wrong arity, non-numeric, and out-of-range lat/lng', ...)
  // '1,2,3', 'a,b,c,d', '-200,0,10,10', '0,-91,10,10' → null for each
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm run test --workspace @roadtrip/web -- boundary`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `boundary.ts`**

`parsePoiId` validates with a named `POI_ID_PATTERN` before `BigInt()` so the constructor never throws, and range-checks against a named `MAX_POI_ID`. `bboxToPolygonGeoJson` splits on a named separator, validates arity and the four coordinate ranges against the same named bounds `packages/db/src/schema/catalog.ts` uses, and returns the closed-ring GeoJSON.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm run test --workspace @roadtrip/web -- boundary`
Expected: PASS.

- [ ] **Step 5: Implement the two pages and the two route handlers**

Each file sets `export const dynamic = 'force-dynamic'`. Every one of them is a server component or route handler — no `'use client'` anywhere in this task, which is what keeps Task 2's guard quiet and is the point of the slice. Rendering stays plain semantic HTML with inline-free styling; do not reach for `frontend/`'s design system, which is a Vite/Storybook build this app does not share.

The probe route wraps only the service call in `performance.now()` so the reported `db` duration is the read, not the serialization.

- [ ] **Step 6: Prove `next build` needs no database**

Run: `env -u DATABASE_URL npm run build --workspace @roadtrip/web`
Expected: build succeeds. If it fails with a connection error, a route is being prerendered and is missing `force-dynamic` — fix that rather than supplying a build-time `DATABASE_URL`.

- [ ] **Step 7: Verify the routes serve real data against a local Postgres**

Start a PostGIS container, apply `migrateDb`, insert a handful of fixture rows, then run `npm run dev --workspace @roadtrip/web` with `DATABASE_URL` pointed at it. Fetch each of the four routes.
Expected: the detail page renders the fixture campground's name; `/search` lists it; the probe route returns JSON with a `Server-Timing` header; `/api/health` returns 200. Also fetch `/campgrounds/abc` and `/search?bbox=garbage` and confirm 404 and a rendered error state — not a 500.

- [ ] **Step 8: Run the guard and the suite**

Run: `node scripts/check-server-only-imports.mjs && npm run typecheck --workspace @roadtrip/web && npm run test --workspace @roadtrip/web`
Expected: all pass.

- [ ] **Step 9: Commit**

```bash
git add apps/web/src
git commit -m "feat(web): serve campground detail, boundary search, probe and health routes"
```

---

### Task 5: The latency harness

§9's P1 has never run. This builds the measurement before either deployment exists, so both halves are measured by the same code against the same boundary.

**Files:**
- Create: `scripts/probe-read-latency.mjs`, `scripts/probe-read-latency.test.mjs`
- Modify: `Makefile` (a `probe-latency` target)

**Interfaces:**
- Consumes: Task 4's `/api/probe/campground-search`.
- Produces: `export function percentile(sorted: number[], p: number): number` and `export function summarize(samples: Sample[]): Summary`, plus a CLI: `node scripts/probe-read-latency.mjs --base-url <url> --label <name> [--runs N] [--warmup N] [--bbox w,s,e,n] [--out <path>]`. Prints a table of p50/p95 for total and `db` duration and appends one JSON line to `--out`.

- [ ] **Step 1: Write the failing tests**

```javascript
test('percentile interpolates and clamps at both ends', ...)
  // [1..10]: p50, p95, p0, p100 against hand-computed values
test('percentile of a single sample is that sample', ...)
test('summarize separates total from db duration and counts samples', ...)
test('summarize reports the cold first sample separately from the warm set', ...)
test('parseServerTiming reads db;dur= and returns null when the header is absent', ...)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `node --test scripts/probe-read-latency.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `scripts/probe-read-latency.mjs`**

Named constants for `DEFAULT_RUNS` (enough for a stable p95 — 200), `DEFAULT_WARMUP`, `P50`, `P95`, and the request timeout. Requests are sequential, not concurrent: this measures latency, and concurrency would measure throughput. Report the first (cold) sample separately from the warm distribution, because a Vercel cold start is a different question from read latency and mixing them makes the number meaningless.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `node --test scripts/probe-read-latency.test.mjs`
Expected: PASS.

- [ ] **Step 5: Choose the probe boundary from the real data and freeze it**

The number only means something over a representative row count. Against production (or the Neon restore once Task 7 has one), find the densest populated region: run the search for several candidate bboxes and record `totalInBoundary` for each. Pick the one whose count is the largest realistic map viewport, and write it as `PROBE_BOUNDARY_BBOX` in the script with the row count it covers in the commit message.
Expected: a bbox returning on the order of hundreds of campgrounds, not two and not the whole continent.

- [ ] **Step 6: Add a `probe-latency` Makefile target**

`make probe-latency BASE=<url> LABEL=<name>` wrapping the script, so both deployment tasks invoke it identically.

- [ ] **Step 7: Commit**

```bash
git add scripts/probe-read-latency.mjs scripts/probe-read-latency.test.mjs Makefile
git commit -m "feat(web): add the P1 read-latency probe harness"
```

---

### Task 6: mini-ca — its own Compose project, its own port, its own Caddy route

Additive by construction: a **separate Compose project** joining the base project's existing network as external, mirroring how `docker-compose.sandbox.yml` joins `roadtrip-sandbox`. `scripts/deploy.sh` only ever runs `pull` and `up -d` against the base project (verified — it never runs `down`), so a prod deploy cannot touch this service, and no existing compose file, service definition, or deploy script changes.

**Blocked on open questions 1, 2 and 4.**

**Files:**
- Create: `apps/web/Dockerfile`, `docker-compose.web.yml`, `docs/web-app.md`
- Modify: `caddy/Caddyfile` (one vhost block), `secrets/registry.yaml` (declare the connection string's placement per §11, `store: sops`, consumer `web`)

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: a `web` service listening on a named port (`WEB_PORT`, default 3100 — not 3000, which Grafana holds inside its own container), reachable at `http://web:3100` on the `roadtrip_default` network, and a Caddy vhost forwarding the chosen hostname to it.

- [ ] **Step 1: Write `apps/web/Dockerfile`**

Multi-stage on `node:22-alpine`, following the root `Dockerfile`'s `frontend` stage pattern: copy the root manifests, `tsconfig.base.json`, the workspace manifests and `packages/`, `npm ci` **without** `--ignore-scripts` (rulings 48–49: it skips `prepare`, and `@roadtrip/domain`'s build is a `prepare` hook), then `npm run build` at the root so `packages/db/dist` exists before `next build` runs. Final stage copies `.next/standalone`, `.next/static` and `public`, runs as a non-root user, and `EXPOSE`s the port.

Note in the commit message: `npm ci --workspace @roadtrip/web` would not build the dependency workspaces (ruling 48b), which is why this copies `packages/` and builds from the root.

- [ ] **Step 2: Build the image and verify it runs with no database**

Run: `docker build -f apps/web/Dockerfile -t roadtrip/web:probe .`
Expected: build succeeds without a `DATABASE_URL` build arg.

Then run the container with a bogus `DATABASE_URL` and fetch `/api/health`.
Expected: 503 with a JSON body — the app boots and reports unhealthy rather than crash-looping. A container that exits on an unreachable database is wrong for a service behind a healthcheck.

- [ ] **Step 3: Write `docker-compose.web.yml`**

`name: roadtrip-web`. One service, `web`, with the image, `restart: unless-stopped`, the same `x-log-rotation` json-file settings the base file uses (so Alloy's Docker discovery keeps its logs bounded the same way), a `mem_limit`, a healthcheck hitting `/api/health`, `DATABASE_URL` and `ENABLED_DATA_PROVIDERS` from the environment, and `networks: [roadtrip_default]` declared `external: true`. No `ports:` — Caddy reaches it on the network, and publishing a host port would put production data on a host interface for no reason.

`DATABASE_URL` points at `postgres:5432` on that network with the existing `roadtrip` user, plus the read-only connection parameter Task 3's `db.ts` sets. Per open question 4, that is what makes the access read-only without any production change.

- [ ] **Step 4: Prove a prod deploy leaves the service alone**

Before touching mini-ca, verify the claim locally: bring up the base project's `postgres` and the `roadtrip-web` project against it, then run the same `docker compose -f docker-compose.yml -f docker-compose.secrets.yml --profile tunnel --profile pois --profile recgov-companion up -d` invocation `deploy.sh` uses, and confirm the `web` container is still running with the same container id afterwards.
Expected: unchanged. If it is stopped or recreated, the separate-project assumption is wrong and the service needs a different attachment — report that rather than working around it.

- [ ] **Step 5: Add the Caddy vhost**

One `http://<hostname>` block in `caddy/Caddyfile` with `reverse_proxy web:3100`, placed after the sandbox `import` with a comment-free body. The `web` service must also join the network Caddy is on — it already does, since Caddy sits on `roadtrip_default`.

This is the one existing service whose **config** changes; it needs `docker exec <caddy> caddy reload`, which is the mechanism the sandbox flow already uses. No other service is affected. If open question 2 comes back saying a new Zero Trust rule is required, stop here and report: that rule is not something this plan can create.

- [ ] **Step 6: Deploy to mini-ca and verify against real data**

Build the image on the box from the release checkout and bring up the `roadtrip-web` project. Fetch `/api/health`, then `/campgrounds/<a real poi id>` and `/search`.
Expected: the health route is 200 and the pages render real production campground names. Confirm the other services are untouched: `docker ps` shows `roadtrip-backend`, `roadtrip-recgov-companion`, `roadtrip-postgres` and the observability containers with unchanged uptimes.

- [ ] **Step 7: Prove the connection is read-only**

From inside the running container, attempt an `INSERT` through the same `DATABASE_URL`.
Expected: rejected with a read-only transaction error. This is the claim that matters most in this task and it must be observed against production, not inferred from Task 3's container test.

- [ ] **Step 8: Measure**

Run: `make probe-latency BASE=http://<hostname> LABEL=mini-ca-local-postgres`
Expected: a recorded p50/p95 for total and db, with the cold sample reported separately.

- [ ] **Step 9: Commit**

```bash
git add apps/web/Dockerfile docker-compose.web.yml caddy/Caddyfile docs/web-app.md secrets/registry.yaml
git commit -m "feat(web): run the read app on mini-ca as its own Compose project"
```

---

### Task 7: Neon and Vercel

C4 names Neon as a datastore that must work, so this doubles as C4 evidence. Postgres **16** to match production exactly. The schema comes from `@roadtrip/db`'s own migrations — not from the dump — which avoids every PostGIS and server-version skew in the schema and exercises the step-2 deliverable; only rows come from the dump.

**Blocked on open questions 1 and 3.**

**Files:**
- Create: `scripts/restore-catalog-to-managed.mjs`, `docs/web-app.md` (extend with the connection-string and `prepare` record)
- Modify: `apps/web/README.md` or `docs/web-app.md` for the Vercel project settings

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: a Neon project and a Vercel project serving the same four routes, and the recorded `prepare` answer for Neon's pooler.

- [ ] **Step 1: Create the Neon project**

Postgres 16, in the region settled by open question 3. Enable PostGIS: `CREATE EXTENSION IF NOT EXISTS postgis`. Note the project's `branch_logical_size_limit_bytes` — the existing account's is 1 GiB, which is why step 3 restores only the catalog tables.
Expected: `SELECT postgis_version()` returns a version.

- [ ] **Step 2: Create the schema from `@roadtrip/db`**

Run `npm run migrate --workspace @roadtrip/db` with `DATABASE_URL` set to the Neon **direct** (session-mode) endpoint — `migrateDb` runs `CREATE EXTENSION IF NOT EXISTS postgis` itself before the first migration (ruling: pre-flight scan).
Expected: both migrations apply; `campground_site_summary` exists; `\d pois` shows `geometry(Geometry,4326)`.

- [ ] **Step 3: Restore production rows, data only**

`pg_dump --data-only` on mini-ca restricted to the catalog tables the read routes touch — `pois`, `campgrounds`, `campsites`, `poi_campgrounds`, `tesla_superchargers`, `planet_fitness_locations`, `poi_tesla_superchargers`, `poi_planet_fitness_locations` — and nothing else. `api_cache`, `raw_objects` and the availability tables are excluded: they are the bulk of the database, none of them is read by this app, and the size limit in step 1 would not hold them. Use `pg_dump` from the `postgis/postgis:16-3.4` image so the dumper's version matches the server's; no `pg_dump` is installed on this machine.

The dump never leaves mini-ca over the tunnel and the production database is never exposed to the internet (spec §C2, §11): the dump is written to a file on the box, copied over SSH, and loaded from here.

`scripts/restore-catalog-to-managed.mjs` drives the load against the direct endpoint and then asserts equality: for each restored table, the row count on Neon equals the count mini-ca reported. A restore whose counts were never compared is not a restore.
Expected: every count matches, and the probe boundary from Task 5 step 5 returns the same `totalInBoundary` on both databases.

Then record Neon's PostGIS version (`SELECT postgis_full_version()`) and re-run Task 3's two fault cases against it — the malformed boundary and the self-intersecting polygon. Task 3's classification is a PostGIS-3.4-confirmed string match, so this is where a version difference would surface as a 500 on Vercel that mini-ca does not have.
Expected: both classify the same way. If they do not, that is a finding to report, not something to paper over by broadening the match.

- [ ] **Step 4: Determine `prepare` for Neon's pooler and record it**

§2 is explicit that this is pooler- and version-dependent and must be determined, not assumed. Neon's pooler is PgBouncer in transaction mode; its changelog says protocol-level prepared statements have been supported since PgBouncer 1.22. So expect `prepare: true` to work — and prove it rather than trusting the changelog.

Against the **pooled** endpoint, run the probe boundary query repeatedly with concurrent connections, once with `prepare: true` and once with `prepare: false`, and record for each: whether every query succeeded, and the latency distribution.
Expected: a definite pass/fail per setting. Write the answer, the pooler endpoint it applies to, and the date into `docs/web-app.md` immediately beside the connection string — §2 says "rather than in someone's memory." Set `DATABASE_PREPARE` in Vercel to whatever won.

- [ ] **Step 5: Create the Vercel project and measure what `data/raw` costs**

Connect `wwchen/roadtrip`, Root Directory `apps/web`, **"Include files outside root directory" enabled** — with `./` as the root the workspace build does not resolve. Install command `npm ci` at the root; build command `npm run build` at the root (so `packages/*/dist` exists before `next build`, per the Global Constraints); output `.next`. Environment variables: `DATABASE_URL` (the pooled endpoint), `DATABASE_PREPARE`, `ENABLED_DATA_PROVIDERS`. All three are server-only and **none** is `NEXT_PUBLIC_`-prefixed. Pin the function region per open question 3.

Measured locally for reference: a `--depth=1` clone of this repo transfers 133 MB of packed objects and checks out 1.9 GB across 22,163 files in **10.9 s**, of which `data/raw` is 1.7 GB and 19,136 files.

From the first build's logs, record the actual clone/checkout duration and the total build duration.
Expected: a real number. Then say plainly whether it needs addressing — the honest comparison is that figure against the total build time, versus the cost of the alternatives (git-lfs migration, a submodule, or a history rewrite), all of which are disruptive to a repository three other sessions are working in. Recommend, do not act.

- [ ] **Step 6: Verify the deployed app serves real data**

Fetch `/api/health`, a real `/campgrounds/<poiId>`, and `/search` on the deployment URL.
Expected: the same campground names the mini-ca deployment renders for the same ids. If they differ, the restore in step 3 is incomplete — go back, do not reconcile by hand.

- [ ] **Step 7: Measure**

Run: `make probe-latency BASE=https://<deployment> LABEL=vercel-neon-pooled`
Expected: a recorded p50/p95 for total and db, cold sample separate.

- [ ] **Step 8: Commit**

```bash
git add scripts/restore-catalog-to-managed.mjs docs/web-app.md
git commit -m "feat(web): serve the read app from Vercel against a Neon restore"
```

---

### Task 8: Report the probe, and record what was decided

§9's P1 is the reason four later steps rest on an assumption. This writes the answer down where the spec can point at it.

**Files:**
- Create: `docs/superpowers/2026-10-28-p1-read-latency.md`, `docs/superpowers/2026-10-28-nextjs-read-app-rulings.md`
- Modify: `docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md` (§9 P1 — record that it ran and what it found)

- [ ] **Step 1: Write the P1 result**

The two p50/p95 pairs with their regions, the row count the probe boundary covers, the run count, the cold-start sample, the `prepare` answer, and the `data/raw` figures. Then the call §9 asks for: whether the regression is acceptable, and therefore whether the API runs on Vercel or self-hosted on mini-ca. State the threshold being applied and why, so a later reader can disagree with the judgement rather than only with the number.

- [ ] **Step 2: Amend spec §9**

P1 currently reads as an open probe. Record that it ran on 2026-10-28, what it found, and link the result document. Do not restate the numbers in two places.

- [ ] **Step 3: Write the rulings document**

Every call taken without asking, in the style of `2026-10-27-step-2-rulings.md`: the decision, and what it costs if it was wrong. The ones already known going in: `apps/web` as the directory; a separate Compose project rather than a service in `docker-compose.yml`; `default_transaction_read_only` rather than a new production role; schema-from-migrations rather than schema-from-dump; catalog-tables-only restore; the filter predicates dropped from the search port; `Server-Timing` as the measurement surface; ids as `string` at the repo boundary.

Note explicitly which of the Global Constraints' "verified" hazards turned out to be real in practice and which did not — the project's own habit is that the live system wins over the document.

- [ ] **Step 4: Run the full suite**

Run: `make test`
Expected: green. This is the gate the task brief names; nothing is done until it passes.

- [ ] **Step 5: Commit and open the PR**

```bash
git add docs/superpowers
git commit -m "docs(web): record the P1 read-latency result and the rulings taken"
```

Open a PR against `master`. Do not merge it — the user merges their own PRs.

---

## Self-review

**1. Spec coverage.** §1 (Next.js as a runtime importing `@roadtrip/db`): Tasks 1, 3. §2 (two connection strings; `prepare` determined per deployment and recorded beside the connection string): Task 3 config, Task 7 step 4. §3 (live schema is the source; schema from the proven-equivalent Drizzle migrations): Task 7 step 2. §9 P1: Tasks 5, 6, 7, 8. §10 step 7 (read API and SSR pages for POI and campground): Tasks 3, 4 — **partially**, by design; the frozen-contract gate, the Playwright port and the seeded-preview lifecycle are out of scope and the scope fence says so. §11 (Vercel owns its own secrets; registry records placement not values): Task 6 step 3's registry entry, Task 7 step 5's Vercel-held variables. C2 (companion never gets a public vhost): untouched — this app never calls the companion. C4 (Neon must work): Task 7. §5, §6, §8.1, §10 steps 4, 8–12: deliberately out of scope.

**2. Step scan.** Every step names a command and an expected result, or a signature and the file it lives in. No step says "handle errors" or "add tests for the above". Task 3 step 3 is the one that carries the most judgement; it is pinned by six named tests and an instruction to read the SQLSTATE codes out of the Kotlin source rather than invent them.

**3. Type consistency.** `searchWithinBoundary` returns `poiIds: string[]`, and `findSummariesByPoiIds` takes `readonly string[]` — they compose. `findDetailByPoi` takes `bigint` and `parsePoiId` returns `bigint | null`, so the route converts once at the edge and the service never sees a string id. `Db` is `@roadtrip/db`'s exported type throughout; no task invents a wrapper. `percentile`/`summarize` are used only by the probe CLI and `make probe-latency`.

**4. Review Focus.** All five lines have a named test: bigint serialization (Task 3, two cases plus a mutation proof), malformed boundary (Task 3, Task 4 step 7), topology fault (Task 3), empty provider set (Task 3), bad POI id (Task 4, four cases). The two geometry-fault lines are additionally re-run against Neon in Task 7 step 3, because the Kotlin classifies them by a PostGIS-3.4-confirmed string match and Neon's PostGIS version is not known in advance. The read-only claim gets a container test in Task 3 and an against-production observation in Task 6 step 7, because the second is the one that actually matters.

**5. Proportion.** The plan is longer than the spec sections it implements, which is expected: it spans two deployments, a managed-database restore and a measurement, and most of its length is commands and expected outputs rather than code. Code blocks are test names with assertions, and the two SQL bodies are referenced in the Kotlin source rather than transcribed. No function body appears that its signature and tests already determine.
