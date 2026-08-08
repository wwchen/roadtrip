# Frontend Migration: vanilla JS → React + TypeScript

> **Handoff doc.** Status as of 2026-08-07. This is the source of truth for the
> React migration; it captures the approved plan, decisions, what's already
> done (and verified), what remains, and the gotchas discovered along the way so
> a fresh agent session can continue without re-deriving anything.

---

## TL;DR for the next session

- We are doing a **full rewrite of `web/` (vanilla ES modules) → React + TypeScript**,
  executed as a **strangler migration** (one page at a time; vanilla + React coexist).
- New app lives in **`frontend/`** (Vite multi-page, 3 entries mirroring today's URLs).
- **Phase 0 (foundation) is in progress and all green.** Scaffold builds/tests/typechecks.
  `web/api/http.js` + `web/api/watches-api.js` are ported to typed `frontend/src/api/*.ts`.
- **Components come from LDS** (`matthewlew/lds` → `@lew/lds-react`), styled by
  `@lew/lds/css` + the `theme-roadtrip`. **Decision: vendor LDS into the repo now**, switch to
  a published registry dep later.

### Resume quickstart
```bash
cd frontend
npm install
npm run typecheck   # tsc --noEmit — must be clean
npm run test        # vitest run — currently 6 tests green
npm run build       # vite build — emits dist/{index,availability,watches}.html
npm run dev         # Vite dev server :5173, proxies /api,/auth,/web,/data → :8765 (Ktor)
```
Run the Ktor backend separately (e.g. `make run` or `tilt up`) so the dev proxy resolves
`/api`, and so `/web/design-system/tokens.css` is served (see Token strategy).

---

## Context & motivation

`web/` is ~14,600 LOC of hand-authored vanilla ES modules with **no build step** — Ktor
serves the files straight from disk (bind-mounted in Docker). It's deliberately architected
(a design system with a `mount(container, config) → { dispose() }` contract, a CSS-token
source of truth, a color-token CI checker, ~28 `node --test` suites). The team is moving to
React because: manual DOM/state re-render bookkeeping is error-prone, complex new UI is
coming, and React is easier to hire/onboard for. TypeScript throughout.

## Locked decisions

| Area | Decision |
|---|---|
| Framework | **React 18.3 + TypeScript** (18.3 chosen for max compatibility; `@lew/lds-react` peers `^18 \|\| ^19`) |
| Build | **Vite 6**, multi-page: 3 HTML entries mirroring `/`, `/availability`, `/watches` |
| Server state | **TanStack Query** (replaces the `roadtrip:*` custom-event refetch bus) |
| Client state | **Zustand** (replaces `state`/`trip` singletons + `window.__rt*` global RPC bridge) |
| Components/styling | **LDS** — consume `@lew/lds-react` via a local `@ui` adapter; style with `@lew/lds/css` + `theme-roadtrip` |
| LDS consumption | **Vendor into the repo now**, switch to a published registry dep later (one-line swap behind `@ui`) |
| Rollout | **Strangler, page-by-page**: watches → availability → account → map app |
| Tests | **Vitest 3** + jsdom + React Testing Library (ports the `node --test` `*.test.mjs` suites) |

## LDS findings (inspected 2026-08-07)

`matthewlew/lds` is a **public** monorepo — the "Lew Design System".
- **`@lew/lds`** — 28 framework-free components (`(props) => htmlString`); five stateful ones
  ship `mountX(el, config) → { update, dispose }` controllers (same contract as our current
  `web/design-system`). One-token CSS cascade, four themes × light/dark. Ships `.d.ts` types.
  Exports: `@lew/lds` (templates+controllers), `@lew/lds/templates`, `@lew/lds/controllers`,
  `@lew/lds/css`, `@lew/lds/css/themes/roadtrip`, etc.
- **`@lew/lds-react`** — on branch **`lds-react-adapter`** (NOT on `main`, NOT on npm). A
  complete, typed React binding: every template has a PascalCase component (`Button`, `Modal`,
  `TextField`, `Table`, `Tabs`, `Toggle`, `Banner`, `Select`, `Card`, `Menu`, `Tooltip`,
  `Checkbox`, `Radio`, `Chip`, `Link`, `Nav`, `EmptyState`, `Skeleton`, `Icon`, `Inline`,
  `Avatar`, `ButtonGroup`, `Row`, `Tag`, …). `forwardRef` to the real DOM node; slot props
  accept React nodes via `createPortal`; form-control `onChange` wiring; the five
  controller-backed stateful components (CodeField, SegmentedControl, Textarea, Toast,
  Tooltip); a `ToastProvider`/`useToast()` pair. Peer deps React `^18 || ^19`. `toSlot()`
  helper for one-level-deep nested slot props.
- **`theme-roadtrip`** (`@lew/lds/css/themes/roadtrip`) exists specifically so this app can
  "wear LDS": every value is read from our current `web/design-system/tokens.css`. Applied via
  `<html class="theme-roadtrip mode-dark">` (roadtrip is dark-native; LDS resolves light at
  `:root`, so both classes go on the root element).
- **`@lew/open-icons`** — 174-symbol SVG sprite, standalone (no LDS dependency). Used via
  `<svg><use href="/icons.svg#name"/></svg>`; LDS's `Icon` wraps this.
- LDS's README explicitly names **Roadtrip as "the real first consumer"**.

### Inspecting / vendoring LDS
```bash
# Clone to inspect (public repo):
git clone https://github.com/matthewlew/lds /tmp/lds
cd /tmp/lds
git fetch origin lds-react-adapter        # the @lew/lds-react package lives here
git show FETCH_HEAD:packages/lds-react/src/index.d.ts   # full React API surface
```
Vendor plan (task): copy `packages/lds` + `packages/open-icons` (from `main`) and
`packages/lds-react` (from `lds-react-adapter`) into `frontend/vendor/` (or an npm workspace),
depend by path, and point `@ui` at `@lew/lds-react`. **Why vendor:** npm git-deps can't cleanly
install a single workspace member of a monorepo. Keep it a one-line change to swap for a
published `@lew/lds*` registry dep later.

---

## Target architecture

### Build & entry points (Vite multi-page)
Three HTML entries mirror current URLs so `StaticSiteRoutes.kt` stays nearly unchanged:
`index.html` → map app; `availability.html` → admin dashboard; `watches.html` → watch CRUD.

### Source tree (in place today)
```
frontend/
  index.html availability.html watches.html   # thin shells; link /web/design-system/tokens.css
  vite.config.ts tsconfig.json vitest.setup.ts package.json
  src/
    pages/{map,availability,watches}/main.tsx  # React roots (Phase-0 placeholders)
    pages/watches/WatchesPage.tsx              # + smoke test (RTL harness proof)
    ui/index.ts                                # @ui adapter seam (empty; LDS lands here)
    api/http.ts  api/watches-api.ts  api/watches-api.test.ts   # ported, typed, tested
    # TODO: api/* (rest), lib/ (utils + pure core.js), stores/ (zustand), map/, features/*
```

### What is preserved (do NOT rewrite)
- **`web/design-system/tokens.css` + `tokens.js` (the `token()` bridge).** MapLibre paint and
  Chart.js can't resolve `var(--rt-*)`, so `token(name)` reading `getComputedStyle` stays the
  runtime color source. Works identically under React.
- **`web/api/*`** — pure same-origin fetch wrappers over `http.js`/`HttpError`. Port to typed
  `.ts`, wrap in TanStack Query hooks. Logic unchanged.
- **`web/utils/*`** (`local-date.js`, `availability-status.js`) + pure `core.js` helpers
  (`escapeHtml`, `distanceKm`, `formatDistance`, `geomCenter`, `zoomForBbox`,
  `flattenHydratedPoi`, `formatPhone`, `callButtonsHTML`) → `src/lib/` as `.ts`.

### What is replaced
- `web/design-system/*` primitives → `@lew/lds-react` (via `@ui`).
- Page controllers / self-init modules → React roots.
- Cross-component comms (`state`/`trip` singletons, `window.__rt*` globals, `roadtrip:*` events,
  `layers.js` listener Set) → Zustand stores + TanStack Query invalidation.

### State model
- `authStore` (replaces `roadtrip:auth-changed` + `fetchMe` gating).
- `tripStore` (replaces `topbar/state.js` `trip` singleton + `__rtSetRoutePois`/`__rtAddTripStop`/
  `__rtTripMode`/`__rtRouteActive`/… globals).
- `mapStore` (viewport, filters, selected POI/drawer; replaces `state` in `core.js` except the
  raw map instance).
- **TanStack Query** for POIs, watches, settings, availability; `roadtrip:watches-changed` →
  `queryClient.invalidateQueries(['watches'])`.
- **Transition shim:** keep `window.__rt*` as thin adapters over the stores until the map page
  migrates, so still-vanilla pages keep working.

### MapLibre (imperative escape hatch)
Do **not** express layers as JSX / don't adopt `react-map-gl` for the layer logic. Keep
`layers.js` install/reinstall logic (source+layer install, transparent `*-hit` layers, the
`setStyle` → wipe → `style.load` → reinstall-from-cache cycle, per-layer handler rebinding,
dynamic per-agency legend/filter) as an imperative `src/map/` module. A `<MapProvider>` owns
the map instance in a `ref`; React effects call the imperative install functions and subscribe
map events into `mapStore`. Call `token()` inside those effects after CSS is applied.

### Token strategy (settled by the roadtrip theme)
Adopt **`@lew/lds/css` + `theme-roadtrip` + `mode-dark`** for LDS components. During the
strangler transition, **keep `web/design-system/tokens.css` loaded alongside** so the map/canvas
`token()` bridge (reads `--rt-layer-*`, `--rt-map-*`, `--rt-series-*` by name) keeps resolving —
LDS's theme defines `--c-*`/`--grey-*`, not `--rt-*`. Reconcile later: keep a thin `--rt-*`
alias layer for the bridge, or repoint the bridge at LDS var names.

---

## Toolchain, CI & deploy changes

- **Serving:** Vite builds to `frontend/dist/`. Point Ktor `static-dir`/`StaticSiteRoutes` at
  the built output; update `docker-compose*.yml` mounts, `Dockerfile`, `Tiltfile`. With a build
  step now, **bake built assets into the image** (`COPY dist`) instead of bind-mounting source.
  During strangler: serve migrated pages from `dist/`, unmigrated from legacy `web/`.
  Files: `backend/src/main/kotlin/ca/floo/roadtrip/route/static/StaticSiteRoutes.kt`,
  `backend/src/main/resources/application-*.yaml` (`static-dir`), `docker-compose.yml`
  (~lines 101-106), `docker-compose.sandbox.yml`, `Dockerfile`, `Tiltfile`.
- **Tests:** migrate `node --test` `*.test.mjs` → Vitest. Port suites alongside modules.
- **Typecheck:** `tsc --noEmit` as a CI step.
- **Color checker:** extend `scripts/check-color-tokens.mjs` `EXTENSIONS` to include `.ts`/`.tsx`
  and repoint `ROOTS`/`EXEMPT`/`LEGACY_RAW_COLOR_BUDGET` to the new tree; keep bridge-integrity
  pointed at the retained `tokens.css`/`tokens.js`. (Today it does NOT scan `.tsx` → raw hex
  would pass silently.)
- **CI** (`.github/workflows/ci.yml` `web-tests` job, ~lines 185-217): replace the `node --test`
  discovery block with `vitest run` + `tsc --noEmit` + the color check; add `frontend/**` to the
  `dorny/paths-filter` `web` filter (~lines 58-62). **CI pins Node 22** — keep it.
- **Gallery:** rebuild `web/design-system/gallery.html` as an LDS-backed catalog (Storybook fits).

---

## Execution phases (strangler — each ships independently)

**Phase 0 — Scaffolding & shared foundation.** *(IN PROGRESS)* Vite+React+TS+Vitest scaffold ✅;
dev proxy ✅; port `api/*` (started) + `lib/` (utils + pure `core.js`); Zustand stores +
QueryClient; `@ui` adapter; vendor LDS; CI wiring (vitest/tsc/color-check); token bridge setup.

**Phase 1 — Watches page** (`watches.html`, `web/watches/*`, 733 LOC — cleanest, already
component-shaped). Rebuild `WatchForm`/`TriggerSelector`/`WatchTable` on LDS + a form lib +
TanStack Query mutations/invalidation; preserve `?action/id/poi_id/start_date` deep-links.
First end-to-end proof of the stack. Pin the real `Watch` DTO here (see `watches-api.ts` TODO).

**Phase 2 — Availability admin dashboard** (`availability.html`, `web/availability.js` +
`web/components/availability/*`). Client-routed tabs (pollers/runs/snapshots), Chart.js via npm.

**Phase 3 — Account/settings** (`web/account/*`, 1,362 LOC). Settings modal, SecretField
write-only pattern, auth port/adapter (`embedded-auth-port.js` + `auth0-embedded.js`), auth0-js
via npm.

**Phase 4 — The map app** (`index.html`, ~9k LOC — largest/hardest). Sub-sequence:
  4a. Map shell + `<MapProvider>` + imperative `map/` layer lifecycle + basemap/style-reload.
  4b. Search + legend/filters + viewport POI fetch loop (debounce + AbortController + ring cache).
  4c. Drawer (session/hydration AbortController guard, mobile drag-dismiss) — 4 POI drawer types.
  4d. `availability/availability-week.js` (1,226 LOC, ~30-field ctx) → components + hooks,
      preserving seq-guarded staleness, state machine, drag-resize, popovers.
  4e. Topbar/trip planner (drag-reorder, turf corridor, route + share-link encode/restore);
      replace remaining `window.__rt*` with `tripStore`.

**Phase 5 — Decommission.** Delete legacy `web/*` + the `window.__rt*` shim; drop dual serving;
final CI/deploy cleanup; remove the `python3 -m http.server` static launch config.

---

## Done so far (detailed)

Files created under `frontend/` (all committed on this branch):
- `package.json` (React 18.3, TanStack Query 5, Zustand 5; dev: Vite 6, **Vitest 3**, TS 5.6,
  jsdom, RTL, @types/node), `.gitignore`, `tsconfig.json` (strict; `@`/`@ui` path aliases),
  `vite.config.ts` (multi-page inputs, dev proxy, vitest block), `vitest.setup.ts`.
- `index.html`, `availability.html`, `watches.html` — thin shells linking
  `/web/design-system/tokens.css` and loading each page's `main.tsx`.
- `src/pages/{map,availability,watches}/main.tsx` — placeholder React roots.
- `src/pages/watches/WatchesPage.tsx` + `WatchesPage.smoke.test.tsx` (RTL harness proof).
- `src/ui/index.ts` — `@ui` adapter seam (empty; LDS re-exports land here).
- `src/api/http.ts` — typed `HttpError` + generic `json*` helpers (behavior preserved).
- `src/api/watches-api.ts` — typed watches client; `Watch` DTO is provisional (pin in Phase 1).
- `src/api/watches-api.test.ts` — Vitest tests (query-string build, 404-swallow, error code).

**Verified:** `npm run typecheck` clean · `npm run test` → 6 passed · `npm run build` → 3 pages.

## Remaining Phase 0 (all unblocked)

1. Port remaining `web/api/*`: `auth-api`, `account-api`, `poi-api`,
   `availability-dashboard-api`, `availability-api`, `campsite-api`, `geocode-api`,
   `directions-api`, `password-auth-api`; port existing tests (`account-api.test.mjs`,
   `campsite-api.test.mjs`, `password-auth-api.test.mjs`) to Vitest.
2. Port `web/utils/*` + pure `core.js` helpers → `src/lib/` (typed) with tests.
3. Zustand `authStore`/`tripStore`/`mapStore` + TanStack Query `QueryClient` provider.
4. **Vendor LDS** into `frontend/vendor/` and wire `@ui` → `@lew/lds-react`; load
   `@lew/lds/css` + `theme-roadtrip`; set `<html class="theme-roadtrip mode-dark">`.
5. Extend `scripts/check-color-tokens.mjs` to `.ts/.tsx`; wire CI `web-tests` to
   `vitest`/`tsc`; add `frontend/**` to the paths-filter.

---

## Gotchas / lessons (save yourself the debugging)

- **Vitest must be v3 for Vite 6.** Vitest 2.x depends on Vite 5 and pulls a *nested* copy,
  causing a `Plugin` type clash with `@vitejs/plugin-react`. Use Vitest 3 and import
  `defineConfig` from `vitest/config` (not `vite`).
- **`tokens.css` is intentionally not bundled.** Shells link the absolute
  `/web/design-system/tokens.css`, served by Ktor at runtime. `vite build` prints
  "…doesn't exist at build time, it will remain unchanged to be resolved at runtime" — expected.
  Requires the backend (or the dev proxy) to be up for styles to load.
- **GateGuard fact-forcing hook is ON** in this environment: every file create/edit and first
  Bash call demands a "facts, then retry" cycle (and denies the first attempt). Batch writes;
  present importers/purpose/instruction, then retry. (Disable path if ever wanted:
  `ECC_GATEGUARD=off` or `ECC_DISABLED_HOOKS=pre:edit-write:gateguard-fact-force,pre:bash:gateguard-fact-force`.)
- **npm can't select one workspace member from a git monorepo** → hence vendoring LDS.
- **API faithfulness:** `createWatch`/`updateWatch`/`deleteWatch` use bare `fetch` (no explicit
  `credentials`), relying on the same-origin default — preserved in the port. `deleteWatch`
  swallows 404.
- **Cross-component comms today** = 4 parallel mechanisms (`state`/`trip` singletons,
  `window.__rt*` globals, `roadtrip:*` custom events, `layers.js` listener Set). Consolidate into
  stores + Query; keep a `window.__rt*` shim during transition.
- **Hardest migration areas** (Phase 4): the MapLibre style-reload/reinstall lifecycle,
  `availability-week.js` (1,226 LOC), `topbar.js` (2,054 LOC) + the `window.__rt*` bridge, and
  the drawer session/hydration guard.

## Reference reading (repo)
`docs/frontend-components.md` (current DS + token discipline), `docs/backend-architecture.md`,
`AGENTS.md` (project rules). Backend static serving: `StaticSiteRoutes.kt`. Color checker:
`scripts/check-color-tokens.mjs`. CI: `.github/workflows/ci.yml` (`web-tests` job).
