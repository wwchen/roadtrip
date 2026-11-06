# Availability Egress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vercel serves the campsite availability matrix from Neon, and a POST refresh fetches fresh availability through a Kotlin endpoint on mini-ca and writes it to Neon.

**Architecture:** `apps/web` ports the two matrix GETs (campsite list, availability week) to read Neon, and adds `POST …/availability/refresh`, which answers 202 and, in `after()`, calls `POST /internal/availability/fetch` on mini-ca. Kotlin serves that endpoint through its existing read-through `CampsiteAvailabilityService`. The refresh writes the returned cells to Neon through a minimal writer role, stamped with mini-ca's `observed_at`. The frontend polls the GET while the week is stale.

**Tech Stack:** Kotlin/Ktor + jOOQ + Flyway (backend), Next.js 15 route handlers + Drizzle + vitest (apps/web), React + TanStack Query (frontend), Caddy, Postgres/Neon.

**Spec:** `docs/superpowers/specs/2026-11-05-availability-egress-design.md`

## Global Constraints

- Records cross the Vercel/mini-ca boundary as `(data_provider, data_provider_ref)` pairs, never database ids.
- A cell written to Neon carries the `observed_at` mini-ca returned, never Vercel's clock.
- `GET /api/pois/{id}/campsites/availability` never calls mini-ca.
- Refresh is on only when `AVAILABILITY_EGRESS_URL`, `AVAILABILITY_EGRESS_TOKEN` and `DATABASE_WRITER_URL` are all set; a partial set is a `ConfigError` at boot.
- Defaults: `AVAILABILITY_TTL` 2 hours; `AVAILABILITY_EGRESS_TIMEOUT` 25 seconds; refresh route `maxDuration` 60 seconds; poll every 2 seconds, at most 15 polls.
- Provider capabilities (booking horizon days / max poll window days), in Kotlin registration order: recgov 180/90, campflare 365/60, reservecalifornia 183/30, aspira 365/30, reserveamerica 270/30.
- Watching stays gated: every cell's `watchable` is `false` and `watch_capabilities` is `{ trigger_kinds: [], add_to_cart: { state: 'unsupported' } }`.
- `WEB_ROUTED_PATH` does not change: on mini-ca, Kotlin keeps serving the campsite routes.
- AGENTS.md applies: no inline magic constants; routes → service → repo; SQL only in repos; `api-types.ts` regenerated with `make api-types`, never edited by hand.
- No code comments (owner's standing rule); rationale goes in commit messages.
- Stage explicit paths; never `git add -A`.

## Review Focus

1. **The frontend against Kotlin.** Production `roadtrip.floo.ca` still serves the matrix from Kotlin, which has no refresh route; a POST there answers 404. The page must treat any non-202 refresh answer as "no refresh": no polling, no error shown. Pinned in Task 9.
2. **A week with no observations at all.** It must render `unknown` cells with `cache: null` and trigger exactly one refresh, not a broken grid. Pinned in Task 7.
3. **Catalog drift.** mini-ca returns a campsite pair Neon lacks, or Neon has a campground mini-ca doesn't know. Drop and count the pair; log the 404; never throw. Pinned in Task 8.
4. **A partly fresh window.** One stale cell makes the whole week stale under the per-cell rule. `cache.age_seconds` reports the stalest cell, not the newest. Pinned in Task 7.
5. **A slow mini-ca.** A refresh past the timeout writes nothing and leaves the GET's answer unchanged. Pinned in Task 8.

---

### Task 1: Grants for reading and writing `availability`

**Files:**
- Create: `packages/db/migrations/0003_availability_egress.sql` (plus the entry Drizzle's `meta/_journal.json` needs)
- Modify: `backend/src/main/resources/db/migration/R__web_reader_grants.sql`
- Create: `backend/src/main/resources/db/migration/R__availability_writer_grants.sql`
- Modify: `apps/web/src/server/repo/webReadGrants.ts` (`WEB_READ_TABLES` gains `'availability'`) and `webReadGrants.test.ts`

**Interfaces:**
- Produces: role `roadtrip_availability_writer`; `roadtrip_web` can `SELECT availability`.

- [ ] **Step 1: Write the failing test** in `webReadGrants.test.ts`, against the migrated test database:
  - `roadtrip_web reads availability`: `SELECT count(*) FROM availability` succeeds as `roadtrip_web`.
  - `the writer inserts and updates availability`: `INSERT` then `UPDATE … SET last_observed_at` succeed as `roadtrip_availability_writer`.
  - `the writer cannot delete, alter or touch the catalog`: `DELETE FROM availability`, `ALTER TABLE availability ADD COLUMN x int` and `UPDATE campsites SET name = name` each fail with SQLSTATE `42501`.
- [ ] **Step 2: Run it.** Run: `npm test --workspace apps/web -- webReadGrants`. Expected: FAIL (role missing, permission denied on `availability`).
- [ ] **Step 3: Write the migration.** Model it on `0002_web_reader.sql`: create the role only if missing, with `LOGIN` and no password; `statement_timeout` `'10s'`; `GRANT SELECT ON campgrounds, campsites, availability`; `GRANT INSERT, UPDATE ON availability`; `GRANT USAGE ON SEQUENCE availability_id_seq`; and `GRANT SELECT ON availability TO roadtrip_web`. Add the same grants to the two Flyway repeatable files.
- [ ] **Step 4: Run the test and the schema gate.** Run: `npm test --workspace apps/web -- webReadGrants && node scripts/schema-equivalence.mjs`. Expected: PASS, and no schema diff.
- [ ] **Step 5: Commit.** `git add` the four files; message `db: availability read grant and a minimal writer role`.

### Task 2: Availability provider capabilities in `@roadtrip/domain`

**Files:**
- Create: `packages/domain/src/availability-providers.json`, `packages/domain/src/availability-providers.ts`, `packages/domain/src/availability-providers.test.ts`
- Modify: `packages/domain/src/index.ts`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityProviderCapabilitiesParityTest.kt`

**Interfaces:**
- Produces:
  - `type AvailabilityProviderId = 'recgov' | 'campflare' | 'reservecalifornia' | 'aspira' | 'reserveamerica'`
  - `interface AvailabilityProviderCapabilities { id: AvailabilityProviderId; bookingHorizonDays: number; maxPollWindowDays: number }`
  - `const AVAILABILITY_PROVIDERS: readonly AvailabilityProviderCapabilities[]` (registration order, loaded from the JSON)
  - `servingAvailabilityProvider(ref: BookingProviderRef | null, enabled: readonly string[], tenants: TenantRegistry): AvailabilityProviderCapabilities | null`, where `ref` is what `campgroundBookingRef` (in `apps/web/src/server/service/booking/campgroundCta.ts`) returns

- [ ] **Step 1: Write the failing tests.**
  - TS `serving provider follows each Kotlin claim`: one case per provider, built from the claim rules in each `*AvailabilityProvider.supportsCampground`. Each case is a campground whose booking ref that provider claims, and it expects that provider's id.
  - TS `a disabled provider claims nothing`: the case expects `null`.
  - TS `an unclaimed campground has no serving provider`.
  - Kotlin `every registered provider's capabilities match the shared table`: it reads `../packages/domain/src/availability-providers.json` with `File(...)`, the same way `RoadtripRuntimeConfigTest` reads `poi-registry.yaml`, and asserts order, ids, `bookingHorizonDays` and `maxPollWindowDays` against the provider list `ServiceModule` builds.
- [ ] **Step 2: Run them.** Run: `npm test --workspace @roadtrip/domain -- availability-providers` and `./gradlew :backend:test --tests '*AvailabilityProviderCapabilitiesParityTest' -x :backend:contractLedgerCheck`. Expected: both FAIL (missing module, missing file).
- [ ] **Step 3: Implement.** The JSON holds the Global Constraints table. Port `servingAvailabilityProvider` from `BookingHorizonResolver.servingProvider` and each provider's `supportsCampground`: the first provider in order that claims the ref and is in `enabled`, with Aspira and ReserveAmerica also requiring the tenant to be in the registry.
- [ ] **Step 4: Run both.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `domain: availability provider capabilities shared with Kotlin`.

### Task 3: Kotlin egress endpoint

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityEgressDtos.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityEgressController.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/route/internal/AvailabilityEgressRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt` (lookup by provider pair)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`, `model/api/ApiContract.kt`, the availability config class, `backend/src/main/resources/application.yaml`
- Modify: `secrets/registry.yaml`; regenerate `docker-compose.secrets.yml` with `./secrets/manage.py generate`
- Modify: `frontend/src/api/generated/api-types.ts` (via `make api-types`)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityEgressRoutesTest.kt`

**Interfaces:**
- Produces (wire names in snake_case through `@SerialName`):
  - `ProviderRefDto(dataProvider: String, dataProviderRef: String)`
  - `AvailabilityEgressRequestDto(campground: ProviderRefDto, startDate: String, endDate: String)`
  - `AvailabilityEgressCellDto(campsite: ProviderRefDto, date: String, status: AvailabilityStatus, observedAt: String)`
  - `AvailabilityEgressResponseDto(startDate: String, endDate: String, cells: List<AvailabilityEgressCellDto>)`
  - Errors use the existing `AvailabilityErrorDto`.
  - `CampgroundRepo.findByProviderRef(dataProvider: String, dataProviderRef: String): Campground?` (excludes `deleted_at`).
  - `AvailabilityEgressController.fetch(request: AvailabilityEgressRequestDto): AvailabilityEgressResponseDto`
  - `Route.availabilityEgressRoutes(controller: AvailabilityEgressController, token: String)`

- [ ] **Step 1: Write the failing route tests** in `AvailabilityEgressRoutesTest`, using the fake-provider setup `CampsiteRoutesTest` uses:
  - `no bearer is 401` and `a wrong bearer is 401`
  - `an unknown campground pair is 404 unknown_campground`
  - `a reversed window is 400 bad_date_window`
  - `a campground no provider serves is 501`
  - `a stale window calls the provider and returns its cells with their observed_at`: asserts cell count, one cell's `campsite.data_provider_ref`, and that `observed_at` equals the fake's observation time.
  - `a fresh window is answered from the store without a provider call`: the fake provider's call count stays 0.
  - `a provider failure is 503 with its code`
  - `the route is absent when the token is unset`: 404 for the POST.
- [ ] **Step 2: Run them.** Run: `./gradlew :backend:test --tests '*AvailabilityEgressRoutesTest' -x :backend:contractLedgerCheck`. Expected: FAIL to compile.
- [ ] **Step 3: Implement.**
  - The route parses the body, checks `Authorization: Bearer` with `MessageDigest.isEqual`, and calls the controller.
  - The controller resolves the campground with `findByProviderRef` and the date context through the campground's POI. It calls `CampsiteAvailabilityService.fetchAvailability` with all the campground's campsites and the requested window, then maps each observation's campsite id to its `(data_provider, data_provider_ref)` and keeps its observed time.
  - Map `AvailabilityServiceError` and `AvailabilityProviderError` with the helpers `CampsiteRoutes` already uses.
  - Mount the route in `RouteModule` only when `availability.egress.token` (`${AVAILABILITY_EGRESS_TOKEN:}`) is non-blank, with `RouteAccess.Anonymous`, since the bearer is the guard.
  - Add the contract row `POST /internal/availability/fetch`, with request `AvailabilityEgressRequestDto`, success `200` with `AvailabilityEgressResponseDto`, errors `400/404/501/503` with `AvailabilityErrorDto`, and `conditional = true`.
  - Add the secret `AVAILABILITY_EGRESS_TOKEN`: consumer `backend`, `required_in` empty.
- [ ] **Step 4: Regenerate and run.** Run: `make api-types && ./gradlew :backend:test --tests '*AvailabilityEgressRoutesTest' --tests '*ApiContractCoverageTest' && ./gradlew :backend:contractLedgerCheck && ./secrets/manage.py check`. Expected: all PASS; `api-types.ts` shows the four new interfaces.
- [ ] **Step 5: Commit.** `git add` the listed files; message `backend: /internal/availability/fetch serves mini-ca's read-through availability`.

### Task 4: Caddy exposes only the egress path on `egress.floo.ca`

**Files:**
- Modify: `caddy/Caddyfile`
- Modify: `scripts/test_caddy_routing.py`

- [ ] **Step 1: Write the failing tests.** Add an `EGRESS_HOST = "egress.floo.ca"` case:
  - `test_the_egress_host_proxies_only_the_fetch_path`: a `POST /internal/availability/fetch` reaches the backend stub.
  - `test_the_egress_host_hides_everything_else`: `GET /`, `GET /api/me` and `GET /internal/availability/fetch` each answer 404 and never reach the backend stub.
  - `fetch` must gain a method argument. The stub backend answers any method.
- [ ] **Step 2: Run it.** Run: `python3 -m unittest scripts.test_caddy_routing -v`. Expected: the two new tests FAIL.
- [ ] **Step 3: Add the site block** `http://egress.floo.ca` to the Caddyfile: a matcher `@egress { method POST; path /internal/availability/fetch }` with `handle @egress { reverse_proxy backend:8765 }`, and `handle { respond 404 }`.
- [ ] **Step 4: Run it.** Expected: PASS, with the existing host tests unchanged.
- [ ] **Step 5: Commit.** Message: `caddy: egress.floo.ca serves only the availability fetch`.

### Task 5: Web config and the writer connection

**Files:**
- Modify: `apps/web/src/server/config.ts`, `apps/web/src/server/config.test.ts`
- Modify: `apps/web/src/server/db.ts` and its test

**Interfaces:**
- Produces:
  - `config.availabilityRefresh: { egressUrl: URL; egressToken: string; writerDatabaseUrl: string } | null`
  - `config.availabilityTtlSeconds: number` (default `7200`)
  - `writerDb(): Promise<Db>`, which throws when `config.availabilityRefresh` is null
  - constants `AVAILABILITY_EGRESS_TIMEOUT_MS = 25_000`

- [ ] **Step 1: Write the failing tests.**
  - `refresh is null when none of the three is set`
  - `refresh is configured when all three are set`
  - `any one or two of the three is a ConfigError naming the missing ones`: three cases.
  - `AVAILABILITY_TTL overrides the TTL`, and `a non-positive TTL is a ConfigError`
  - `writerDb refuses when refresh is not configured`
- [ ] **Step 2: Run them.** Run: `npm test --workspace apps/web -- config db`. Expected: FAIL.
- [ ] **Step 3: Implement** in the existing `config.ts` style. `writerDb()` uses `openServerDb(writerDatabaseUrl, …)` without `assertReadOnly`.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `web: availability refresh config and writer connection`.

### Task 6: Web repos for availability cells

**Files:**
- Create: `apps/web/src/server/repo/availabilityReadRepo.ts`, `apps/web/src/server/repo/availabilityWriteRepo.ts`, and tests for both
- Modify: `apps/web/src/server/testing/catalogFixture.ts` (campsites with provider refs under `NEAR_CAMPGROUND_ID`)

**Interfaces:**
- Produces:
  - `interface StoredCell { campsiteId: bigint; date: string; status: AvailabilityStatus; lastObservedAt: Date }`
  - `currentCells(db: Db, campsiteIds: readonly bigint[], startDate: string, endDateExclusive: string): Promise<StoredCell[]>`: the latest row per `(campsite, date)`, the same `DISTINCT ON` ordering as Kotlin's `currentStatusRuns`
  - `campsiteIdsByProviderRef(db: Db, campgroundId: bigint): Promise<Map<string, bigint>>`, keyed `` `${dataProvider}\u0000${dataProviderRef}` `` and excluding `deleted_at`
  - `interface Observation { campsiteId: bigint; date: string; status: AvailabilityStatus; observedAt: Date }`
  - `recordObservations(db: Db, observations: readonly Observation[]): Promise<number>`, returning the number of status changes inserted

- [ ] **Step 1: Write the failing tests** against real Postgres, mirroring `AvailabilityRepoTest`'s `recordObservations` cases:
  - `an unchanged status bumps last_observed_at in place`: still one row, with the new time.
  - `a changed status inserts a row linked by previous_id`
  - `the latest observation per cell wins within one batch`
  - `two concurrent writes of the same change leave one new row`: `Promise.all` of two calls.
  - `currentCells returns the newest row per cell inside the window only`
  - `campsiteIdsByProviderRef skips deleted campsites`
- [ ] **Step 2: Run them.** Run: `npm test --workspace apps/web -- availabilityReadRepo availabilityWriteRepo`. Expected: FAIL.
- [ ] **Step 3: Implement.** Port `AvailabilityRepo.recordObservations` (read current, bump the unchanged, insert the changed with `ON CONFLICT DO NOTHING`, `run_id` null) in one transaction, as Drizzle `sql` with `unnest` arrays like the Kotlin queries.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `web: availability cell read and write repos`.

### Task 7: The two matrix GETs on Vercel

**Files:**
- Create: `apps/web/src/server/service/availability/availabilityWindow.ts` (port of `AvailabilityDateResolver.resolveWindow` and `latestDate`)
- Create: `apps/web/src/server/service/availability/matrixFusion.ts` (port of `PoiAvailabilityFusion.kt`)
- Create: `apps/web/src/server/service/availability/availabilityMatrixService.ts`
- Create: `apps/web/src/server/service/availability/campsiteListService.ts` (port of `CampsiteCatalogService` for the list response)
- Create: `apps/web/src/app/api/pois/[id]/campsites/route.ts`, `apps/web/src/app/api/pois/[id]/campsites/availability/route.ts`
- Tests beside each

**Interfaces:**
- Consumes: Task 2's `servingAvailabilityProvider` and `AVAILABILITY_PROVIDERS`; Task 6's `currentCells`; `dateContext.ts` from `service/booking`; Task 5's `config.availabilityTtlSeconds`
- Produces:
  - `getCampsites(poiId: bigint, siteTypes: CampsiteKind[]): Promise<PoiCampsitesResponseSchema | null>`
  - `getAvailabilityWeek(poiId: bigint, siteTypes: CampsiteKind[], startDate: string | null, endDate: string | null, now: Date): Promise<PoiCampsitesAvailabilityResponseDto>`, which throws the error types the route maps to `400 bad_date_window`, `404` and `501`
  - `isWindowFresh(cells: StoredCell[], campsiteIds: bigint[], startDate: string, endDate: string, ttlSeconds: number, now: Date): boolean`
  - `resolveWeekWindow(provider: AvailabilityProviderCapabilities, startDate: string | null, endDate: string | null, context: PoiDateContext): { startDate: string; endDate: string; latestDate: string }`, which throws `BadDateWindowError`. `PoiDateContext` comes from `poiDateContext()` in `service/booking/dateContext.ts`.

- [ ] **Step 1: Write the failing tests.**
  - Service:
    - `a fully fresh week reports cache hit with the stalest cell's age`
    - `one stale cell makes the week stale and age_seconds is that cell's age` (Review Focus 4)
    - `a week with no observations has unknown cells and cache null` (Review Focus 2)
    - `all campsites closed is closed_for_season with no days and season null`
    - `no campsites is empty`
    - `the window clamps to the serving provider's horizon and latest_date is reported`
    - `an unclaimed campground is 501 no_availability_provider`
    - `cells are never watchable and watch_capabilities is the gated shape`
  - Route: `bad id is 400`, `bad dates are 400 bad_date_window`, `unknown POI is 404`, `site_type filters campsites`, and `the GET never calls the egress` (assert no `fetch` to the egress URL with refresh configured).
  - Parity: `the fused week matches Kotlin's fixture`, using the same campsites, observations and window as `CampsiteAvailabilityServiceTest`'s fused-week case, compared field by field.
- [ ] **Step 2: Run them.** Run: `npm test --workspace apps/web -- availability campsites`. Expected: FAIL.
- [ ] **Step 3: Implement.** Port the Kotlin files named above without behavior changes, with three exceptions recorded in the commit message:
  - `season` is always null.
  - `watchable` is always false.
  - `cache` is computed from stored cells: null when any needed cell is missing, otherwise `{ hit: true, age_seconds, ttl_seconds }`.
- [ ] **Step 4: Run them, plus the typecheck.** Run: `npm test --workspace apps/web && npm run typecheck --workspace apps/web`. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `web: campsite list and availability week read from Neon`.

### Task 8: The refresh route and service

**Files:**
- Create: `apps/web/src/server/client/availabilityEgress.ts` and its test
- Create: `apps/web/src/server/service/availability/availabilityRefreshService.ts` and its test
- Create: `apps/web/src/app/api/pois/[id]/campsites/availability/refresh/route.ts` and its test
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt` (a conditional row for the POST), then run `make api-types`

**Interfaces:**
- Consumes: Task 3's DTO types from `api-types.ts`; Task 5's `config.availabilityRefresh`, `writerDb` and `AVAILABILITY_EGRESS_TIMEOUT_MS`; Task 6's `campsiteIdsByProviderRef` and `recordObservations`; Task 7's `resolveWeekWindow` and `isWindowFresh`
- Produces:
  - `fetchEgress(request: AvailabilityEgressRequestDto, signal: AbortSignal): Promise<EgressResult>`, where `EgressResult = { kind: 'cells'; body: AvailabilityEgressResponseDto } | { kind: 'error'; status: number; code: string | null }`
  - `planRefresh(poiId: bigint, startDate: string | null, endDate: string | null, now: Date): Promise<RefreshPlan>`, where `RefreshPlan = { kind: 'fresh' } | { kind: 'fetch'; request: AvailabilityEgressRequestDto; campgroundId: bigint } | { kind: 'disabled' } | { kind: 'unsupported' } | { kind: 'not_found' }`
  - `runRefresh(plan: Extract<RefreshPlan, { kind: 'fetch' }>): Promise<RefreshOutcome>`, where `RefreshOutcome = { written: number; dropped: number } | { failed: string }`

- [ ] **Step 1: Write the failing tests.**
  - Client:
    - `sends the bearer and the JSON body`
    - `a 503 body becomes kind error with its code`
    - `an abort past the timeout becomes kind error with code timeout`
  - Service:
    - `a fresh window plans fresh` and `a stale window plans fetch with the campground's provider pair`
    - `refresh unconfigured plans disabled` and `an unclaimed campground plans unsupported`
    - `runRefresh writes cells with mini-ca's observed_at` (assert the stored `last_observed_at` equals the returned value)
    - `runRefresh drops and counts pairs Neon lacks` (Review Focus 3)
    - `runRefresh on a timeout writes nothing` (Review Focus 5)
    - `runRefresh on a 404 logs catalog drift and writes nothing`
  - Route:
    - `fresh is 202 without calling the egress`
    - `stale is 202 and schedules runRefresh through after` (mock `next/server`'s `after` and assert it received a function)
    - `disabled and unsupported are 501`
    - `unknown POI is 404`
    - `bad dates are 400`
- [ ] **Step 2: Run them.** Run: `npm test --workspace apps/web -- availabilityEgress availabilityRefresh refresh`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - The route exports `maxDuration = 60` (a named constant) and `dynamic = 'force-dynamic'`.
  - `runRefresh` logs one structured line per run: outcome, written, dropped, code.
  - Add the contract row `POST /api/pois/{id}/campsites/availability/refresh`, with success `ApiBody(202)`, errors `400/404/501` with `AvailabilityErrorDto`, and `conditional = true`, since Kotlin never mounts it.
- [ ] **Step 4: Run them, plus the backend contract tests.** Run: `npm test --workspace apps/web && ./gradlew :backend:test --tests '*ApiContractCoverageTest' --tests '*OpenApi*'`. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `web: refresh a stale week through mini-ca's egress`.

### Task 9: The frontend asks for one refresh and polls

**Files:**
- Modify: `frontend/src/api/availability-api.ts` (add `requestAvailabilityRefresh`)
- Modify: `frontend/src/features/availability/useWeekAvailability.ts`
- Test: `frontend/src/features/availability/useWeekAvailability.test.tsx` (create if absent), `frontend/src/api/availability-api.test.ts`

**Interfaces:**
- Produces:
  - `requestAvailabilityRefresh(poiId: string | number, window: { startDate: string; endDate: string }): Promise<boolean>`, true only on 202
  - constants `REFRESH_POLL_INTERVAL_MS = 2_000` and `REFRESH_POLL_LIMIT = 15`
  - `isStale(cache: AvailabilityCache | null | undefined): boolean`, true when `cache` is null/undefined or `age_seconds > ttl_seconds`

- [ ] **Step 1: Write the failing tests.**
  - `a stale week posts one refresh and polls until fresh`: the mocked GET returns stale twice, then fresh. Assert one POST and three GETs.
  - `polling stops after 15 polls and reports it could not refresh`
  - `a fresh week posts nothing`
  - `a 404 or 501 refresh answer means no polling and no error` (Review Focus 1)
  - `changing week posts once for the new window`
  - `isStale` table: null, `age == ttl`, `age > ttl`.
- [ ] **Step 2: Run them.** Run: `npm test --workspace frontend -- useWeekAvailability availability-api`. Expected: FAIL.
- [ ] **Step 3: Implement.** Keep `retry: false`.
  - A `useEffect` keyed on the query key POSTs once when the first response is stale.
  - Use `refetchInterval` returning `REFRESH_POLL_INTERVAL_MS` while a refresh was accepted, the week is stale, and polls are under the limit; otherwise `false`.
  - Expose `refreshFailed: boolean` on the hook's result. The existing freshness pill shows it as the copy "Couldn't refresh". Put the string in the component's copy constants.
- [ ] **Step 4: Run them, then the full frontend suite.** Run: `npm test --workspace frontend`. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `frontend: stale weeks ask for one refresh and poll for it`.

### Task 10: Docs

**Files:**
- Modify: `docs/web-app.md`: a section "Availability refresh" covering the three env vars, the writer role and how to set its password on Neon, the Firewall rule (30 per minute per IP on `POST /api/pois/*/campsites/availability/refresh`), and rollback by unsetting `AVAILABILITY_EGRESS_URL`.
- Modify: `docs/reservation-providers.md`: the egress endpoint, the shared capabilities JSON and its parity test.

- [ ] **Step 1: Write the sections.** Match the spec's Rollout list.
- [ ] **Step 2: Check the sections landed.** Run: `grep -n "availability/refresh" docs/web-app.md && grep -n "internal/availability/fetch" docs/reservation-providers.md`. Expected: both match.
- [ ] **Step 3: Commit.** Message: `docs: availability refresh on Vercel and the egress endpoint`.
