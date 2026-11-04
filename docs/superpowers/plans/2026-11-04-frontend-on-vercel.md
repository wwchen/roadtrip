# Frontend on Vercel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The existing map page (`/`) and POI page (`/poi.html`) render on the `roadtrip-web` Vercel deployment, against Neon, with no Kotlin backend: viewport pins, POI drawer and page, campground search, place search, and (with a Mapbox token) routing.

**Architecture:** The Vite build of `frontend/` is copied into `apps/web/public/` at build time and served as static files, with `/` rewritten to `/index.html`. The read-only endpoints the two pages call become Next.js route handlers in `apps/web`, layered `route -> service -> repo` like the existing `/api/campgrounds/search`. Every response body is typed by the frozen contract in `frontend/src/api/generated/api-types.ts`, so `tsc` refuses a shape the frontend would not accept. Auth, watches, availability and the worker stay out: the pages already degrade when those answer 401 or 404.

**Tech Stack:** Next.js 16 route handlers, `@roadtrip/db` (Drizzle, PostGIS), vitest with the `withPostgis` fixture, Vite 6 multi-page build.

**Spec:** `docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md` §1, §7, §10 steps 7 and 11, resequenced on 2026-11-04: frontend rendering first; auth (8), worker (9) and the Playwright port (6) after. Reference behaviour is the Kotlin handler named in each task; where this plan and the Kotlin code disagree, the Kotlin code wins unless the plan says otherwise.

## Decisions

- **D1. Static frontend, not a Next.js rewrite of the pages** (decided 2026-11-04). Pages move to Next.js later, one at a time.
- **D2. Contract by type.** Route handlers return values typed with the generated DTOs (`import type` from `frontend/src/api/generated/api-types.ts`, re-exported once from `apps/web/src/server/contract.ts`). `api-types.ts` is not edited. The existing camelCase `dto.ts` stays for the existing `/search` and `/campgrounds/[poiId]` pages.
- **D3. Booking-derived detail fields are deferred.** `/api/pois/{id}` for a campground omits `availability_provider`, `time_zone`, `earliest_date`, `latest_date`, `booking_ref`, `availability_supported`, `cta` and `booking_system`. They come from the booking registry and the availability date resolver, which belong with step 9. Omitting `availability_supported` keeps the availability grid (which needs deferred endpoints) from mounting; the drawer still shows `reserve_url`.
- **D4. Mapbox from Vercel.** `/api/geocode`, `/api/route` and `/api/pois/on-route` call Mapbox with `MAPBOX_TOKEN` set as a Vercel environment variable. Mapbox is a paid API, not a booking vendor, so spec C1 does not apply. Unset, they answer 503 as Kotlin does. No persistent route cache: production connects as the read-only `roadtrip_web`, which cannot write `api_cache`; an in-process cache per function instance is enough.
- **D5. Anonymous stubs.** `/api/me` answers the no-identity-provider shape; `/api/watches` answers 401. Both match what Kotlin answers an anonymous caller.

## Global Constraints

- `AGENTS.md` applies: no inline magic constants (named `const`, env-driven when tunable), `route -> service -> repo`, SQL only in `apps/web/src/server/repo/`.
- Every new route module exports `dynamic = 'force-dynamic'` and is listed in `apps/web/src/app/routes.test.ts`.
- `scripts/check-server-only-imports.mjs` and `scripts/check-package-boundaries.mjs` stay green; `frontend/` never imports `@roadtrip/db`.
- Error bodies are `{ error: <code> }` (`ApiErrorSchema`), codes copied from the Kotlin handler; `/api/route` errors are `{ error, detail }` (`RouteErrorDto`).
- Ids in responses are JSON numbers (`api-types.ts` header: database bigints below 2^53). Convert with one helper, `toContractId(id: bigint): number`, that throws above `Number.MAX_SAFE_INTEGER`.
- `ENABLED_DATA_PROVIDERS` filters every endpoint that returns campgrounds, as it already does for `/api/campgrounds/search` (GET).
- No vendor (booking) host is called. Mapbox is the only outbound host (D4).

## Review Focus

1. **A dense viewport at zoom ≥ 6** (Bay Area, all categories). Expected: at most `POI_LIMIT` = 2000 features, sampled across the viewport, `truncated: true`; never an unbounded result. Test in Task 3.
2. **A bbox the frontend never sends but a URL can** (w > e, lat beyond ±90, three numbers, strings). Expected: 400 `bad_request`, never a 500. Test in Task 3.
3. **A deep link to a POI that does not exist or is not a number** (`/?poi=999999999`, `/poi.html?poi=abc`). Expected: 404 `not_found` / 400 `bad_id`; the page shows its error banner. Test in Task 4.
4. **A campground from a provider not in `ENABLED_DATA_PROVIDERS`.** Expected: absent from `/api/pois`, `/api/pois/search` and campground search, and `/api/pois/{id}` answers 404 for it. Tests in Tasks 3, 4, 5.
5. **No `MAPBOX_TOKEN` on a preview.** Expected: geocode, route and on-route answer 503 with their Kotlin codes and the UI shows its message; place search still returns POI hits. Test in Task 6.

---

### Task 1: Serve the Vite frontend from `apps/web`

**Files:**
- Create: `apps/web/scripts/copy-frontend.mjs`, `apps/web/scripts/copy-frontend.test.mjs`
- Modify: `apps/web/package.json` (`build`, devDependency `roadtrip-frontend`), `apps/web/next.config.ts` (rewrites, build-info env), `apps/web/src/next-config.test.ts`, `.gitignore` (`apps/web/public/`)
- Delete: `apps/web/src/app/page.tsx` and its entry in `routes.test.ts` if listed

**Interfaces:**
- Produces: `copyFrontend({ distDir, dataFiles, publicDir }): Promise<string[]>` returning the copied relative paths; constants `FRONTEND_DIST = 'frontend/dist'`, `STATIC_DATA_FILES = ['data/us-states.geojson']`.

- [ ] **Step 1: Failing tests.** `copy-frontend.test.mjs`: `copies every file of the Vite build and the static data files under public` (temp dirs; asserts `index.html`, `poi.html`, `assets/x.js`, `data/us-states.geojson`); `refuses when the Vite build is missing, naming the directory`; `empties public first, so a removed asset does not survive`. `next-config.test.ts`: `/ is rewritten to /index.html before the app routes` (asserts `beforeFiles` contains `{ source: '/', destination: '/index.html' }`).
- [ ] **Step 2: Run** `node --test apps/web/scripts/copy-frontend.test.mjs` and `npm run test --workspace @roadtrip/web`. Expected: FAIL.
- [ ] **Step 3: Implement.** `build` becomes `node scripts/copy-frontend.mjs && next build`. Adding `"roadtrip-frontend": "*"` to `devDependencies` makes `scripts/build-workspaces.mjs` build `frontend` before `@roadtrip/web`. `next.config.ts` gains `rewrites: async () => ({ beforeFiles: [{ source: '/', destination: '/index.html' }] })`, and maps `VERCEL_ENV`, `VERCEL_GIT_COMMIT_SHA`, `VERCEL_GIT_COMMIT_REF` into `env.BUILD_ENV`, `env.BUILD_SHA`, `env.BUILD_BRANCH` so Vercel knowledge stays in deploy config (Task 2 reads the generic names). Remove the placeholder home page.
- [ ] **Step 4: Run** the tests, then `npm run build` at the root and `npx next start` in `apps/web`. Expected: tests PASS; `curl -s localhost:3000/ | grep -c 'src/pages/map'` is 0 and the response is the built `index.html` (hashed `/assets/` script); `/poi.html`, `/data/us-states.geojson` answer 200; `/search` still renders.
- [ ] **Step 5: Commit.**

### Task 2: The page-shell endpoints: `/api/me`, `/api/build-info`, `/api/watches`

**Files:**
- Create: `apps/web/src/server/contract.ts`, `apps/web/src/server/contractIds.ts` (+ test), `apps/web/src/app/api/me/route.ts`, `apps/web/src/app/api/build-info/route.ts`, `apps/web/src/app/api/watches/route.ts`, a `route.test.ts` beside each
- Modify: `apps/web/src/server/config.ts` (+ test), `apps/web/src/app/routes.test.ts`

**Interfaces:**
- Produces: `contract.ts` re-exports, type-only, every DTO later tasks name (`MeResponseDto`, `BuildInfoDto`, `ApiErrorSchema`, `PoiFeatureCollectionSchema`, `PoisRequestSchema`, `PoiSearchResponseSchema`, `PoiDetailFeatureSchema`, `CampgroundSearchRequestDto`, `CampgroundSearchResponseDto`, `CampgroundDetailsRequestDto`, `CampgroundDetailsResponseDto`, `CampgroundSummaryDto`, `GeocodeResponseDto`, `RouteFeatureCollectionDto`, `RouteErrorDto`, `OnRouteRequestDto`, `PoisOnRouteResponseSchema`); `toContractId(id: bigint): number`; `apiError(status: number, code: string): Response`; `config.buildInfo: BuildInfoDto` from `BUILD_ENV` (default `local`), `BUILD_SHA`, `BUILD_BRANCH` (default `''`).

- [ ] **Step 1: Failing tests.** `/api/me` `answers 200 with no identity provider` → exactly `{ authenticated: false, auth_enabled: false, auth_embedded: false }`. `/api/build-info` `reports the configured build` and `defaults env to local`. `/api/watches` `refuses an anonymous caller with 401` → `{ error: 'unauthorized' }`. `toContractId` `converts a safe bigint` and `throws above MAX_SAFE_INTEGER`. `routes.test.ts` lists the three routes.
- [ ] **Step 2: Run** `npm run test --workspace @roadtrip/web`. Expected: FAIL.
- [ ] **Step 3: Implement.** The `/api/watches` 401 code is the one Kotlin's `RouteAccess.User` gate sends; copy it from `backend/src/main/kotlin/ca/floo/roadtrip/route/common` (the access plugin), not this plan's guess, if it differs.
- [ ] **Step 4: Run** tests, then `npm run typecheck --workspace @roadtrip/web`. Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 3: `POST /api/pois`, the viewport pins

**Files:**
- Create: `apps/web/src/server/repo/poiServingRepo.ts` (+ test), `apps/web/src/server/service/poiService.ts` (+ test), `apps/web/src/server/poiRequest.ts` (+ test), `apps/web/src/app/api/pois/route.ts` (+ test)

**Interfaces:**
- Consumes: `contract.ts`, `toContractId`, `apiError` (Task 2); `config.enabledDataProviders`.
- Produces: `parsePoisRequest(body: unknown): { bbox: Bbox; zoom: number | null; categories: PoiCategory[] } | { problem: 'bad_request' }`; `fetchPois(db, query: ViewportQuery): Promise<PoiFeatureCollectionSchema>`; `type PoiCategory = 'campground' | 'tesla_supercharger' | 'planet_fitness_location'`; constants `POI_LIMIT = 2000`, `CAMPGROUND_MIN_ZOOM` (value from `CampgroundService.MIN_POI_ZOOM`), `CATEGORY_ALIASES = { 'planet-fitness': 'planet_fitness_location', supercharger: 'tesla_supercharger' }`.
- Reference: `route/api/pois/PoiRoutes.kt:49-83,144-159`, `service/poi/PoiService.kt:22-33`, `service/poi/PoiCategoryPolicy.kt:10-15`, `repo/PoiServingRepo.kt:245-330`.

- [ ] **Step 1: Failing tests.** `poiRequest.test.ts`: `accepts the frontend's body`; `rejects` each of `[w>e]`, `[s>n]`, `lat 91`, `lng 181`, three numbers, strings, a missing bbox → `bad_request`; `maps both category aliases`. `poiServingRepo.test.ts` (withPostgis + a fixture extending `catalogFixture`): `returns a slim feature per POI in the bbox with category, subcategory and agency`; `drops campgrounds below CAMPGROUND_MIN_ZOOM`; `excludes a campground from a disabled provider`; `caps a dense bbox at POI_LIMIT with truncated true, sampled across the bbox` (seed `POI_LIMIT + 1` points over a grid; assert the count, `truncated`, and that both halves of the bbox are represented). Route test: `a bad body is 400 bad_request and never reaches the service`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** the per-category budget and the grid-sampled overflow as `PoiServingRepo.fetchPois` does; port its SQL, keep its ordering.
- [ ] **Step 4: Run** tests. Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 4: `GET /api/pois/{id}`, the drawer and POI page

**Files:**
- Create: `apps/web/src/server/repo/poiDetailRepo.ts` (+ test), `apps/web/src/server/service/poiDetailService.ts` (+ test), `apps/web/src/app/api/pois/[id]/route.ts` (+ test)
- Modify: `apps/web/src/server/repo/campgroundReadRepo.ts` only if a column the detail needs is not yet selected

**Interfaces:**
- Consumes: Task 2's contract types and helpers; `findDetailByPoi` from `campgroundReadRepo.ts` where it already reads the row.
- Produces: `getPoiDetail(id: bigint): Promise<PoiDetailFeatureSchema | null>`; one mapper per category, `campgroundDetail(row)`, `teslaDetail(row)`, `planetFitnessDetail(row)`, each returning `PoiDetailPropertiesSchema`.
- Reference: `route/api/pois/PoiRoutes.kt:115-133`, `service/poi/CampgroundService.kt:33-117`, `TeslaSuperchargerService`, `PlanetFitnessLocationService`.

- [ ] **Step 1: Failing tests.** Route: `a non-numeric id is 400 bad_id`; `an unknown id is 404 not_found`; `a found POI is 200 with Cache-Control public, max-age=300, stale-while-revalidate=3600`. Service, against PostGIS fixtures, one per category: `a campground's detail carries the Kotlin fields` (asserts `source`, `source_id`, `name`, `detail.sources`, `reserve_url`, `booking_site` (host of `reserve_url`), `description` falling back medium → short → long, `photo_url`, `parent_name` dropped when equal to `name` ignoring case and whitespace, `amenities`, `alerts`, `upstream`); `a campground's detail omits every D3 field` (asserts each D3 key is absent, `availability_supported` included); `a Tesla supercharger's detail` and `a Planet Fitness location's detail` assert their category fields; `a campground from a disabled provider is not found`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** Map field by field from the Kotlin services. Fields serialized from JSON columns (`address`, `links`, `management`, `contact`, `upstream`) pass through as stored. Omit a field rather than send `null` (the contract's `explicitNulls = false`).
- [ ] **Step 4: Run** tests. Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 5: Place search and campground search

**Files:**
- Create: `apps/web/src/server/repo/poiSearchRepo.ts` (+ test), `apps/web/src/app/api/pois/search/route.ts` (+ test), `apps/web/src/app/api/campgrounds/details/route.ts` (+ test)
- Modify: `apps/web/src/app/api/campgrounds/search/route.ts` (add `POST` beside the existing `GET`), its test, `apps/web/src/server/service/campgroundService.ts`

**Interfaces:**
- Consumes: Task 2; `searchWithinBoundary` and `findSummariesByPoiIds` (existing).
- Produces: `searchPois(q: string, limit: number, categories: PoiCategory[]): Promise<PoiSearchResponseSchema>`; `searchCampgroundIds(request: CampgroundSearchRequestDto): Promise<CampgroundSearchResponseDto>`; `campgroundDetails(ids: number[]): Promise<CampgroundDetailsResponseDto>`; constants `SEARCH_MIN_QUERY_LENGTH = 2`, `SEARCH_DEFAULT_LIMIT = 10`, `SEARCH_MAX_LIMIT = 25`, `MAX_DETAIL_IDS = 50`.
- Reference: `PoiRoutes.kt:89-108`, `PoiService.kt:87-111`, `PoiServingRepo.kt:180-240,364-370`, `route/api/campgrounds/CampgroundRoutes.kt:34-77`, `service/poi/CampgroundSearchService.kt:42-90`.

- [ ] **Step 1: Failing tests.** `/api/pois/search`: `a query under two characters is an empty result, not an error`; `limit is clamped to 1..25`; `matches every term of a multi-word query`; `filters by categories`; `excludes a campground from a disabled provider`. `POST /api/campgrounds/search`: `a missing or non-polygon boundary is 400 bad_boundary`; `an unknown filter value is 400 bad_request`; `answers campground_ids, total_in_boundary, total_matching, truncated` (snake_case). `POST /api/campgrounds/details`: `more than 50 ids is 400`; `unknown ids are omitted`; `each summary has availability_supported false and site_counts from campground_site_summary`. The existing `GET` tests still pass.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** The POST search reuses `searchWithinBoundary` and maps to the contract; the filter (`site_type`, `group_size`, `amenities`) follows `CampgroundSearchService`. `availability_supported` is `false` in summaries until D3's fields land.
- [ ] **Step 4: Run** tests. Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 6: Mapbox: geocode, route, POIs on a route

**Files:**
- Create: `apps/web/src/server/client/mapbox.ts` (+ test), `apps/web/src/server/service/routePlanService.ts` (+ test), `apps/web/src/server/repo/routeCorridorRepo.ts` (+ test), `apps/web/src/app/api/geocode/route.ts`, `apps/web/src/app/api/route/route.ts`, `apps/web/src/app/api/pois/on-route/route.ts`, a test beside each
- Modify: `apps/web/src/server/config.ts` (`MAPBOX_TOKEN`, optional), `docs/web-app.md` (the variable)

**Interfaces:**
- Consumes: Task 2; `fetchPoisWithinPolygon` added to `poiServingRepo.ts` (Task 3's file).
- Produces: `createMapboxClient({ token, fetch }): { directions(coords: LngLat[]): Promise<Directions>; geocode(q: string, options: GeocodeOptions): Promise<GeocodeResponseDto> }`; `class RoutingUnavailableError`; `bufferedCorridor(db, line: GeoJsonLineString, radiusMiles: number): Promise<string>`; constants `MAX_WAYPOINTS = 25`, `MIN_RADIUS_MILES = 1`, `MAX_RADIUS_MILES = 100`, `GEOCODE_MAX_QUERY = 200`, `GEOCODE_DEFAULT_LIMIT = 5`, `GEOCODE_MAX_LIMIT = 10`, `ROUTE_CACHE_ENTRIES` (in-process LRU size).
- Reference: `route/api/route/RouteRoutes.kt:33-149`, `service/routing/RoutePlanService.kt:32-70`, `client/mapbox/MapboxDirections.kt:26-61`, `client/mapbox/MapboxGeocoder.kt:31,60-73`, `route/api/geocode/GeocodeRoutes.kt:30-62`, `route/api/pois/PoisOnRouteRoutes.kt:43-129`, `service/poi/PoisOnRouteService.kt:21-48`.

- [ ] **Step 1: Failing tests** (stub `fetch`, no network). Client: `directions sends the token and coords and returns the geometry, legs, distance and duration`; `an upstream non-2xx is RoutingUnavailableError`; `the token never appears in an error message`. Routes: `with no MAPBOX_TOKEN, geocode is 503 geocoding_unavailable, route and on-route are 503 routing_unavailable`; route `400` for each of `too_few_points`, `too_many_points`, `bad_coords`, `out_of_range`, `bad_radius`, `duplicate_adjacent`, with `{ error, detail }`; geocode `a blank or 201-character q is 400 bad_query`, `a malformed proximity is dropped, not an error`; on-route `radius_miles 0 or 101 is 400 bad_request`. Corridor repo (PostGIS): `a buffered line contains a POI within the radius and excludes one beyond it`. Route service: `a repeated plan is served from the in-process cache without a second upstream call`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** following the referenced Kotlin; the route response is feature[0] the LineString with `distance_m`, `duration_s`, `legs`, `waypoints`, and feature[1] the corridor polygon with `radius_miles` when a radius was given.
- [ ] **Step 4: Run** tests. Expected: PASS. Then set `MAPBOX_TOKEN` for Preview and Production in Vercel (the user does this; no task creates or prints it).
- [ ] **Step 5: Commit.**

### Task 7: Check it on a preview

**Files:**
- Modify: `docs/web-app.md` (the endpoints served, what is deferred), and the `PROBE_*` values in `.github/workflows/preview-check.yml` only if that workflow has merged and the check should also load `/`.

- [ ] **Step 1: Open the PR** and wait for its Vercel preview.
- [ ] **Step 2: Drive the preview** through the Vercel connector or a browser: `/` shows the basemap and state lines; zooming past 6 over Wells Gray shows campground pins; clicking Mahood Lake opens the drawer with its description and Reserve link; place search for `mahood` lists it; `/poi.html?poi=<its id>` renders; `/?poi=abc` shows the error banner. With `MAPBOX_TOKEN` set, a two-stop route draws and lists campgrounds along it.
- [ ] **Step 3: Read the browser console** on `/`: no errors other than the expected 401 from `/api/watches`.
- [ ] **Step 4: Commit** the doc update.
