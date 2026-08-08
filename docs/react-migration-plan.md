# Frontend Migration: vanilla JS → React + TypeScript

> **Handoff doc.** Status as of 2026-08-08. This is the source of truth for the
> React migration; it captures the approved plan, decisions, what's already
> done (and verified), what remains, and the gotchas discovered along the way so
> a fresh agent session can continue without re-deriving anything.

---

## TL;DR for the next session

- We are doing a **full rewrite of `web/` (vanilla ES modules) → React + TypeScript**,
  executed as a **strangler migration** (one page at a time; vanilla + React coexist).
- New app lives in **`frontend/`** (Vite multi-page, 3 entries mirroring today's URLs).
- **Phase 0 (foundation) is COMPLETE and all green.** All `web/api/*` and the pure
  `web/utils/*` + `core.js` helpers are ported and typed; LDS is vendored and wired behind
  `@ui`; Zustand stores + a TanStack Query client are standing; CI gates the tree.
- **Components come from LDS** (`matthewlew/lds` → `@lew/lds-react`), styled by
  `@lew/lds/css` + the `theme-roadtrip`. **Decision: vendored into `frontend/vendor/` as
  `vendor/*` npm workspaces**; switch to a published registry dep later.
- **Phase 1 (watches page) is COMPLETE** and the real `Watch` DTO is pinned. It is built and
  tested but NOT yet served — see "Serving" below.
- **Next up: switch serving for migrated pages, then Phase 2 (availability dashboard).**

### Resume quickstart
```bash
cd frontend
npm ci              # vendor/* are workspaces — nothing is fetched for LDS
npm run typecheck   # tsc --noEmit — must be clean
npm run test        # vitest run — currently 464 tests green
npm run build       # vite build — emits dist/{index,availability,watches}.html
npm run dev         # Vite dev server :5173, proxies /api,/auth,/web,/data → :8765 (Ktor)
node ../scripts/check-color-tokens.mjs   # from frontend/, or drop the ../ from the repo root
```
All four must be green before every commit.
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
  index.html availability.html watches.html   # thin shells; <html class="theme-roadtrip mode-dark">
  vite.config.ts tsconfig.json vitest.setup.ts package.json
  vendor/{lds,lds-react,open-icons}/          # vendored LDS, consumed as npm workspaces
  src/
    app/mount.tsx  app/AppProviders.tsx        # page mount + query/toast/event-bridge providers
    pages/{map,availability,watches}/main.tsx  # one mountPage() call each
    pages/watches/WatchesPage.tsx              # Phase 1 ✅
    features/watches/*                         # WatchForm, TriggerSelector, WatchTable, hooks
    ui/index.ts  ui/styles.css                 # @ui → @lew/lds-react; LDS css + roadtrip theme
    api/*.ts                                   # all 11 clients, typed + tested
    lib/{local-date,availability-status,geo,html,poi,watch-triggers}.ts  # pure, typed + tested
    stores/{authStore,tripStore,mapStore}.ts   # zustand
    stores/transition-shim.ts                  # window.__rt* over the stores (transition only)
    queries/{client,keys,auth,legacy-events}.ts # QueryClient, key table, /api/me, event bridge
    test/fetch-stub.ts                         # shared fetch stub for api tests
    types/{tokens,legacy}.d.ts                 # declarations for the @tokens / @legacy aliases
    app/shell.css                              # page chrome shared by all three pages
    # TODO: map/ (imperative layers), features/{availability,account} (Phases 2-4)
```

### Aliases
| Alias | Resolves to | Notes |
|---|---|---|
| `@/*` | `frontend/src/*` | |
| `@ui` | `@lew/lds-react` via `src/ui/index.ts` | the one-line swap point for a published LDS |
| `@tokens` | `web/design-system/tokens.js` | the retained bridge, NOT ported — see Token strategy |
| `@legacy/core` | `web/core.js` | **transition only**; parity tests. Deleted in Phase 5 |

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

**Phase 0 — Scaffolding & shared foundation.** *(COMPLETE)* Vite+React+TS+Vitest scaffold ✅;
dev proxy ✅; all `api/*` ported ✅; `lib/` (utils + pure `core.js`) ✅; Zustand stores +
QueryClient ✅; `@ui` adapter + vendored LDS ✅; CI wiring (vitest/tsc/build/color-check) ✅;
token bridge via `@tokens` ✅; `window.__rt*` shim ✅.

**Phase 1 — Watches page.** *(COMPLETE)* `WatchForm`/`TriggerSelector`/`WatchTable` rebuilt on
LDS + TanStack Query mutations/invalidation; deep-links preserved; real `Watch` DTO pinned. No
form library was needed — the form is four fields and three toggles, and LDS's controls are
uncontrolled (see gotchas), which most form libraries assume they are not.
**Still to ship:** serving (below).

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

## Phase 0 — what landed

**Scaffold.** React 18.3, TanStack Query 5, Zustand 5; Vite 6, **Vitest 3**, TS 5.6 strict,
jsdom, RTL. Multi-page build, dev proxy, three thin shells.

**LDS vendored.** `packages/{lds,open-icons}` are byte-identical between `main` and the
`lds-react-adapter` branch, so all three packages are vendored from that one tree into
`frontend/vendor/` and consumed as `vendor/*` npm workspaces. `@ui` re-exports
`@lew/lds-react`; `@ui/styles.css` loads `@lew/lds/css` then the roadtrip theme; the shells
carry `class="theme-roadtrip mode-dark"`. The icon sprite needs no config — `@lew/open-icons`
resolves its URL through `import.meta.url`, so Vite fingerprints and emits it and
`setIconSprite` stays unused.

**API layer.** All eleven clients typed, with response DTOs pinned against the backend's
`@Serializable` classes rather than guessed. Two shapes stay deliberately open past the fields
every row carries (`Campsite`, `PoiSearchResult`) because their consumers are the Phase-4
drawer and campground card. `AvailabilityStatus` is declared once, in `lib/`.

**lib layer.** `local-date`, `availability-status`, `geo`, `html`, `poi`. `flattenHydratedPoi`
is covered by a **parity suite** that runs the port and `web/core.js` over the same eleven
fixtures and asserts deep equality, including after re-flattening.

**State.** `authStore`/`tripStore`/`mapStore`, a `QueryClient` with one retry policy, a
hierarchical `queryKeys` table, `useMe()` as the single writer syncing `/api/me` into
`authStore`, and a bidirectional bridge between the `roadtrip:*` events and query
invalidation. `AppProviders` + `mountPage` wrap every page identically.

**CI.** `frontend/**` gates the `web-tests` job, which now runs `npm ci` → typecheck →
vitest → build alongside the existing `node --test` discovery for `web/`. The color checker
scans `.ts`/`.tsx`.

**Verified:** typecheck clean · 359 tests green · build emits 3 pages · color check ok.

## Decisions taken during Phase 0 (not in the original plan)

- **`tokens.js` is not ported.** It holds the fallback hex table that
  `check-color-tokens.mjs` verifies key-by-key against `tokens.css`; a TS copy would be a
  second source of truth for those colors and would itself trip the raw-hex check. The React
  app imports the one real module through the `@tokens` alias.
- **Parity tests against the vanilla tree.** Where a port is meant to be behavior-faithful and
  the original is still present, a test that runs both over shared fixtures beats hand-written
  expectations. Reached through `@legacy/*` aliases and marked for deletion in Phase 5.
- **What the stores deliberately do NOT hold.** The MapLibre map and Popup, the route
  AbortController, the endpoint Markers, per-layer FeatureCollections, and handler-binding
  bookkeeping are imperative handles, not state. Each store documents its exclusions.
- **Only 7 of the 9 `window.__rt*` globals are shimmed.**
  `__rtUseCurrentLocationForTripStop` and `__rtRouteShareUrl` are defined by `topbar.js` and
  read by nothing in the repo, so publishing them would invent an API rather than preserve one.
- **`core.js`'s "Idempotent" comment on `flattenHydratedPoi` was wrong** and is corrected in
  the port's docs (not its behavior). Fields derived only from `raw` — a park's
  `Loc_Nm`/`GIS_Acres`/`Mang_Name`, a supercharger's `stallCount`/`powerKilowatt`, Planet
  Fitness's `opening_hours` — do not survive a second pass, because the first pass consumes and
  deletes `raw`. Pinned by tests, and the legacy implementation behaves identically.

---

## Serving — the one thing between Phase 1 and users

Ktor still serves `web/` for every page, so the React watches page is unreachable. To ship it:
point `StaticSiteRoutes.kt` + `application-*.yaml` (`static-dir`) at `frontend/dist/` for the
migrated routes while unmigrated ones keep resolving from `web/`, and update `docker-compose.yml`
(~lines 101-106), `docker-compose.sandbox.yml`, `Dockerfile`, and `Tiltfile`. With a build step
now, bake the built assets into the image (`COPY dist`) rather than bind-mounting source. Note
`/web/design-system/tokens.css` must KEEP being served — the React shells link it absolutely.

## Gotchas / lessons (save yourself the debugging)

- **LDS form controls are uncontrolled, and this is not optional.** `value`/`checked` is the
  INITIAL value only. The stateless components render a template string, so changing the prop
  swaps the control's DOM: a controlled text input loses its caret and every keystroke after the
  first. `attrs.js` maps `defaultValue`/`defaultChecked` onto the `value`/`checked` attributes
  precisely so the uncontrolled pattern works. Seed once with `defaultValue`, let the DOM own the
  live value, mirror `onChange` into state, and make a reseed a REMOUNT via a React `key`. This
  cost real debugging in Phase 1 — typing "42" posted `poi_id: 4` — and every later phase with a
  form hits it.
- **Don't pass a changing `disabled` to a field you want to keep typed text in.** It changes the
  template, which swaps the DOM and resets the value. Disable the buttons instead.
- **LDS's `toggle` puts its visible label in a `<span>`**, not a `<label for>`, so `id` alone
  leaves the checkbox with no accessible name — pass `aria-label` too. Its `textField` DOES emit
  `<label for={id}>`, so there an `id` is enough (and required: without one there is no
  association at all).
- **LDS's `Table` is presentational.** No sort hooks, no per-row class, no row keys. Sorting and
  row-level styling are the consumer's job.
- **Some `@lew/lds-react` types are narrower than the runtime.** `Table`'s column labels and cell
  values accept React nodes but are typed `Slot`. Corrected once in `src/ui/index.ts`; put any
  further corrections there rather than casting at call sites.
- **A `useMemo` dependency array must be a constant size.** A per-item query fan-out
  (`useQueries` over N ids) cannot be spread into deps; collapse it to one scalar.

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
- **npm can't select one workspace member from a git monorepo** → hence vendoring LDS. The
  vendored members depend on each other by exact version (`@lew/lds-react` → `@lew/lds` →
  `@lew/open-icons`), which only resolves because `frontend/package.json` declares
  `"workspaces": ["vendor/*"]`. Drop that field and `npm ci` goes to the registry and 404s.
- **`@lew/lds-react` ships untranspiled `.jsx`** (`main: ./src/index.jsx`). It works because
  workspace members are symlinked and Vite treats linked deps as source, so `plugin-react`
  transforms them. This only fails at *bundle* time, never at typecheck — which is why CI runs
  `npm run build` and not just `tsc`.
- **`@tokens`/`@legacy/*` resolve outside the Vite root**, so `server.fs.allow` must list
  `../web` or the dev server refuses to serve them.
- **jsdom has no CSS**, so `token()` falls back to its baked table in tests. Assert against
  `token('--rt-…')` rather than a literal hex — a literal would also trip the color checker.
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
