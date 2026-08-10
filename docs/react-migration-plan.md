# Frontend Migration: vanilla JS → React + TypeScript

> **Handoff doc.** Status as of 2026-08-09 (Phase 4e complete). This is the source of truth for the
> React migration; it captures the approved plan, decisions, what's already
> done (and verified), what remains, and the gotchas discovered along the way so
> a fresh agent session can continue without re-deriving anything.

---

## TL;DR for the next session

- We are doing a **full rewrite of `web/` (vanilla ES modules) → React + TypeScript**,
  executed as a **strangler migration** (one page at a time; vanilla + React coexist).
- New app lives in **`frontend/`** (Vite multi-page, 3 entries mirroring today's URLs).
- **Phase 0 (foundation) is COMPLETE and all green.** All `web/api/*` and the pure
  `web/utils/*` + `core.js` helpers are ported and typed; LDS is wired behind
  `@ui`; Zustand stores + a TanStack Query client are standing; CI gates the tree.
- **Components come from LDS** (`matthewlew/lds` → `@lew-ds/lds-react`), styled by
  `@lew-ds/lds/css` + the `theme-roadtrip`. The three packages are installed from npm;
  `@lew-ds/lds` is pinned to 1.0.0 because 1.1.0 removes the Roadtrip theme export.
- **Phase 1 (watches page) is COMPLETE, served, and merged** (#568). The real `Watch` DTO is
  pinned and `web/watches/` is deleted.
- **Phase 2 (availability dashboard) is COMPLETE, served, and merged** (#569). Chart.js comes from
  npm; `web/availability.js` + `web/components/availability/*` and the root `availability.html`
  are deleted.
- **Serving is wired**: `tilt up` / `make run` / `make sandbox` build and serve both React pages.
  Neither has a legacy fallback any more — an unbuilt `frontend/dist` 404s, deliberately and
  loudly, and the prod deploy health check probes both paths.
- **Phase 3 (account/settings) is COMPLETE as React work** (#570) — every component exists and is
  tested. Nothing mounts `SettingsModal` yet; read its note in "Execution phases" for why that is
  deliberate, and note the mounting task has moved to **4e**, not 4a.
- **Phase 4a is merged** (#571): `maplibre-gl` from npm, the basemap registry, and `MapProvider`
  with the style-reload lifecycle. It shipped the map instance only — no layers, no fetch loop.
- **Phase 4b is merged**: the imperative overlay module, the viewport POI fetch loop, and the
  legend/filter panel. Read "Phase 4b — what landed" for the two scoping corrections it makes
  (the panel's search box is dead code; park layers are deliberately absent).
- **Phase 4d is merged** (#576), and so are the three P2 defects a later review of it found
  (#577) — read "Phase 4d follow-ups" before touching `AvailabilityWeek`, `DayDetail` or
  `useWatches`, because that fix reshaped all three.
- **Phase 5 is COMPLETE, and `web/` is gone entirely.** The strangled app and the root
  `index.html` went first (116 files, #580), and a follow-up moved the three files that
  had stayed — `tokens.css`, its JS bridge, and the sandbox chrome — into `frontend/`,
  taking the `/web` mount, the flat catch-all and the `runtimeServedAssets` plugin with
  them. **The migration is done**; read "Phase 5 — COMPLETE" and then "Finishing the job"
  for what moved and why the served-not-bundled rationale did not hold up.
- **Phase 4e is COMPLETE**, across `claude/react-migration-4e-topbar` (PR #578) and
  `claude/react-migration-4e-results` stacked on it: the pure layers, the route/corridor
  overlay and markers, the fetch hooks, the panel (search, directions, drag-reorder), the
  campgrounds-along-route card list with the corridor slider inside it, the alerts panel with
  its Slack deep link, `?route=` in both directions, the auth row that finally mounts Phase
  3's `<SettingsModal>`, the map's zoom/locate-me controls and the user-location puck, and
  the `window.__rt*` shim — which Phase 0 wrote and **nothing had ever installed**. Its own
  section below is the handoff.
- **`/` is React.** `index.html` is in the page list, the `/preview/*` mount and its sandbox
  flag are deleted, and `SmokeTest.kt` drives the React DOM.
- **Browser coverage is real now.** `SmokeTest.kt` covers all three pages, and its seven map
  tests drive the React app: the cold load, the mobile layers sheet, the agency filter, two
  shared-link paths, the shared route, and route mode from the current location. It needs a live
  stack (`make qa` with `QA_BASE_URL`), so it does not run where there is no Postgres — the
  selectors were verified against `frontend/dist` in headless Chromium instead, which is how the
  drawer-registry P0 in "Graduating `/`" was found.

### Resume quickstart
```bash
cd frontend
npm ci              # installs LDS and the rest of the locked npm dependency graph
npm run typecheck   # tsc --noEmit — must be clean
npm run test        # vitest run — currently 1,394 tests green
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
| Framework | **React 18.3 + TypeScript** (18.3 chosen for max compatibility; `@lew-ds/lds-react` peers `^18 \|\| ^19`) |
| Build | **Vite 6**, multi-page: 3 HTML entries mirroring `/`, `/availability`, `/watches` |
| Server state | **TanStack Query** (replaces the `roadtrip:*` custom-event refetch bus) |
| Client state | **Zustand** (replaces `state`/`trip` singletons + `window.__rt*` global RPC bridge) |
| Components/styling | **LDS** — consume `@lew-ds/lds-react` via a local `@ui` adapter; style with `@lew-ds/lds/css` + `theme-roadtrip` |
| LDS consumption | **Published npm packages under `@lew-ds/*`**, with `@lew-ds/lds` pinned to 1.0.0 for the Roadtrip theme |
| Rollout | **Strangler, page-by-page**: watches → availability → account → map app |
| Tests | **Vitest 3** + jsdom + React Testing Library (ports the `node --test` `*.test.mjs` suites) |

## LDS findings (inspected 2026-08-07)

`matthewlew/lds` is a **public** monorepo — the "Lew Design System".
- **`@lew-ds/lds`** — 28 framework-free components (`(props) => htmlString`); five stateful ones
  ship `mountX(el, config) → { update, dispose }` controllers (same contract as our current
  `web/design-system`). One-token CSS cascade, four themes × light/dark. Ships `.d.ts` types.
  Exports: `@lew-ds/lds` (templates+controllers), `@lew-ds/lds/templates`, `@lew-ds/lds/controllers`,
  `@lew-ds/lds/css`, `@lew-ds/lds/css/themes/roadtrip`, etc. at version 1.0.0.
- **`@lew-ds/lds-react`** — a complete, typed React binding: every template has a PascalCase
  component (`Button`, `Modal`,
  `TextField`, `Table`, `Tabs`, `Toggle`, `Banner`, `Select`, `Card`, `Menu`, `Tooltip`,
  `Checkbox`, `Radio`, `Chip`, `Link`, `Nav`, `EmptyState`, `Skeleton`, `Icon`, `Inline`,
  `Avatar`, `ButtonGroup`, `Row`, `Tag`, …). `forwardRef` to the real DOM node; slot props
  accept React nodes via `createPortal`; form-control `onChange` wiring; the five
  controller-backed stateful components (CodeField, SegmentedControl, Textarea, Toast,
  Tooltip); a `ToastProvider`/`useToast()` pair. Peer deps React `^18 || ^19`. `toSlot()`
  helper for one-level-deep nested slot props.
- **`theme-roadtrip`** (`@lew-ds/lds/css/themes/roadtrip`) exists specifically so this app can
  "wear LDS": every value is read from our current `web/design-system/tokens.css`. Applied via
  `<html class="theme-roadtrip mode-dark">` (roadtrip is dark-native; LDS resolves light at
  `:root`, so both classes go on the root element).
- **`@lew-ds/open-icons`** — 174-symbol SVG sprite, standalone (no LDS dependency). Used via
  `<svg><use href="/icons.svg#name"/></svg>`; LDS's `Icon` wraps this.
- LDS's README explicitly names **Roadtrip as "the real first consumer"**.

### Inspecting LDS
```bash
# Clone to inspect (public repo). All three packages are on main now:
git clone https://github.com/matthewlew/lds /tmp/lds
cd /tmp/lds
cat packages/lds-react/src/index.d.ts     # full React API surface
```
The packages were originally vendored from revision `2bdcf68`. They are now published under
the `@lew-ds/*` scope and installed through `frontend/package-lock.json`. Keep
`@lew-ds/lds` pinned to 1.0.0 while the app imports `css/themes/roadtrip`; the 1.1.0 export
map no longer includes that theme.

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
  src/
    app/mount.tsx  app/AppProviders.tsx        # page mount + query/toast/event-bridge providers
    pages/{map,availability,watches}/main.tsx  # one mountPage() call each
    pages/watches/WatchesPage.tsx              # Phase 1 ✅
    features/watches/*                         # WatchForm, TriggerSelector, WatchTable, hooks
    features/availability-dashboard/*          # Phase 2 ✅
    features/account/*                         # Phase 3 ✅ (built, not mounted — see below)
    features/map/                              # Phase 4a-4b ✅
      MapProvider.tsx basemaps.ts map.css      #   4a: instance + style lifecycle
      MapView.tsx                              #   4b: composes the three hooks below
      useViewportPois.ts useMapOverlays.ts     #   4b: fetch loop / install+paint+filter
      LegendPanel.tsx BasemapPicker.tsx legend.css
    map/                                       # imperative, non-React map internals
      overlays.ts                              #   the pin-overlay registry + install calls
      pins.ts agencies.ts state-lines.ts
      viewport.ts viewport-cache.ts            #   request shaping / containment cache
    test/fake-map.ts                           # recorder fake for the map suites
    ui/index.ts  ui/styles.css                 # @ui → @lew-ds/lds-react; LDS css + roadtrip theme
    api/*.ts                                   # all 11 clients, typed + tested
    lib/{local-date,availability-status,geo,html,poi,watch-triggers}.ts  # pure, typed + tested
    stores/{authStore,tripStore,mapStore}.ts   # zustand
    stores/transition-shim.ts                  # window.__rt* over the stores (transition only)
    queries/{client,keys,auth,legacy-events}.ts # QueryClient, key table, /api/me, event bridge
    test/fetch-stub.ts                         # shared fetch stub for api tests
    types/{tokens,legacy}.d.ts                 # declarations for the @tokens / @legacy aliases
    app/shell.css                              # page chrome shared by all three pages
    # TODO: features/map/{Drawer,availability,topbar} (Phases 4c-4e)
```

### Aliases
| Alias | Resolves to | Notes |
|---|---|---|
| `@/*` | `frontend/src/*` | |
| `@ui` | `@lew-ds/lds-react` via `src/ui/index.ts` | the app-local LDS boundary |
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
- `web/design-system/*` primitives → `@lew-ds/lds-react` (via `@ui`).
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
Adopt **`@lew-ds/lds/css` + `theme-roadtrip` + `mode-dark`** for LDS components. During the
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
QueryClient ✅; `@ui` adapter + LDS ✅; CI wiring (vitest/tsc/build/color-check) ✅;
token bridge via `@tokens` ✅; `window.__rt*` shim ✅.

**Phase 1 — Watches page.** *(COMPLETE)* `WatchForm`/`TriggerSelector`/`WatchTable` rebuilt on
LDS + TanStack Query mutations/invalidation; deep-links preserved; real `Watch` DTO pinned. No
form library was needed — the form is four fields and three toggles, and LDS's controls are
uncontrolled (see gotchas), which most form libraries assume they are not.
**Still to ship:** serving (below).

**Phase 2 — Availability admin dashboard.** *(COMPLETE)* Tabs (pollers/runs/changes) rebuilt on
LDS + TanStack Query; Chart.js from npm, tree-shaken, replacing two CDN `<script>` tags; URL
contract (`?tab=…` plus per-tab params) preserved. `availability.html`, `web/availability.js` and
`web/components/availability/*` are deleted, and the hand-written `get("/availability")` route
went with them — `migratedPages` generates both URL forms.

**Scope note, because the file tree misleads.** `wc -l web/availability*` reports ~4,100 lines,
but only ~640 of those were this phase: the tab router (51) and the three tab modules (585).
The big files under `web/availability/` — `availability-week.js` (1,226), `site-matrix.js` (627),
`watch-editor.js` (417), `site-detail.js` (338) — are the MAP DRAWER's availability UI, reached
from `index.html`. **Phase 4d ports them** (see below); they stay on disk until Phase 5 deletes
`web/`, because the vanilla map page and the vanilla topbar still import them.

**Phase 3 — Account/settings** (`web/account/*`, 1,362 LOC). Settings modal, SecretField
write-only pattern, the account/profile/notifications panels, and the login card's
hosted-redirect branch.

~~auth port/adapter (`embedded-auth-port.js` + `auth0-embedded.js`), auth0-js via npm~~ — struck
because Auth0 is not the live provider and the embedded flow is dormant. See the note below;
this line is what led to porting it once already.

> ⚠️ **Account/settings is NOT a page, and the original plan missed that.** There is no
> `account.html`. `web/topbar/auth.js` mounts `mountLoginCard` and `mountSettingsModal` into
> `#tb-auth`, and the topbar exists on **`index.html` only** — the map page, which stays vanilla
> until Phase 4. So Phase 3 cannot be "migrate a page" the way Phases 1 and 2 were.
>
> **Decision: a React island inside the vanilla page.** A dedicated Vite entry mounts a React
> root into its own container, and the vanilla topbar opens it through the `window.__rt*` shim
> that already exists for cross-tree calls. This is the standard strangler move and keeps Phase 3
> from being blocked behind Phase 4's ~9k LOC. Every component written this way (SettingsModal,
> ProfilePanel, NotificationsPanel, LoginCard, SecretField) is reusable as-is in Phase 4 — only
> the mounting glue and the shim hook are throwaway.
>
> The alternative considered and rejected: reorder Phase 4 ahead of Phase 3. It removes the glue
> but pays ~9k LOC first, and the account UI is the smaller, better-understood surface to do next.
>
> **The embedded (Auth0) login path is deliberately NOT ported.** It is dead in the current
> configuration, and briefly porting it was a mistake caught in review:
>
> - `docker-compose.yml` sets `AUTH_PROVIDER=${AUTH_PROVIDER:-clerk}`. Auth0 is the RFC 0009
>   rollback path, not the live vendor.
> - `/api/me` gates the surface at runtime through `auth_embedded` — "True → mount the embedded
>   email/password card; false → redirect to `/auth/login`" (`src/api/auth-api.ts`). With Clerk
>   active the hosted redirect is what runs, and `signIn()` already covers it from Phase 0.
>
> So `web/account/{embedded-auth-port,auth0-embedded}.js` get no React counterpart, and `auth0-js`
> is not a frontend dependency. **When the login card is ported, implement the
> `auth_embedded: false` branch only.** Do not port the port either: an interface whose only
> implementation is a test double is speculative generality, and it would drag the vendor SDK into
> the bundle on the one path where a failed third-party fetch stops anyone signing in.
>
> If Auth0 is ever reactivated the legacy modules are still in `web/` and still the reference — and
> two bugs found while briefly porting them are worth carrying over then: Auth0 reports an
> unverified email as `access_denied` in some tenant configurations, and the legacy mapper tests
> `access_denied` first, so those users are told their password is wrong; and a login callback
> reporting success with no `code` resolves `undefined` as the artifact.
>
> **Order the work so nothing is wasted:** the pure/logic layers are independent of the mounting
> decision and go first.
>
> - ✅ `src/lib/settings-errors.ts` — ported + tested.
> - ✅ `account-api.ts` — already typed in Phase 0.
> - ✅ `ConfirmButton`, `SecretField` in `@ui`; `ProfilePanel`, `AccountPanel`,
>   `NotificationsPanel`, `SettingsModal` in `src/features/account/`. All tested.
> - ✅ **The login card needs no port.** `web/topbar/auth.js`'s `startSignIn()` mounts it
>   only when `me.auth_embedded` is true; otherwise it calls `signIn()`, a plain redirect
>   already ported in Phase 0. With Clerk active the redirect is the live path, so there is
>   no "hosted-redirect branch of the login card" — the redirect bypasses the card entirely.
>   An earlier version of this checklist said otherwise and was wrong.
>
> **So Phase 3's React work is COMPLETE.** Every component exists and is tested. What is
> missing is only mounting: nothing yet renders `SettingsModal`, so users still get the
> vanilla modal from `web/account/settings-modal.js`. That is deliberate and not a
> regression — the legacy path keeps working untouched.
>
> **The island glue was deliberately NOT built.** It needed a Vite entry, a stable output
> filename, a `<script>` tag in the root `index.html`, a `window.__rtOpenSettings()` global
> and a hook in the vanilla topbar — all of which Phase 4a deletes the moment `index.html`
> becomes a React entry. With Phase 4 landing immediately after, that is 100% throwaway
> work, so the decision is to let Phase 4a mount the modal instead. `frontend/index.html`
> already exists and already builds as a Phase 0 placeholder, so the entry is in place;
> what it needs is the map app inside it.
>
> **That task now belongs to 4e, not 4a.** 4a shipped the map instance and 4b the overlays and
> legend, and neither has anywhere to open the modal FROM: the settings modal's only trigger is
> the topbar's account button (`web/topbar/auth.js` mounts it into `#tb-auth`), and the topbar is
> 4e. Mounting it earlier would mean inventing a second entry point and then deleting it — the
> same throwaway work the island glue was rejected for. So: **4e renders `<SettingsModal>` from
> the migrated topbar and deletes `web/account/*` plus the `mountSettingsModal` import in
> `web/topbar/auth.js`.**
>
> **A latent bug was fixed in the port**, worth knowing because the same shape appears elsewhere
> in `web/`: `settings-errors.js` looks up `MESSAGES[code] ?? DEFAULT` on a plain object, so a
> code naming an `Object.prototype` member resolves up the prototype chain —
> `settingsErrorMessage('toString')` returns the *function*, and `??` never fires because a
> function is not nullish. The port uses a `Map`, which has no prototype keys to collide with.

**Phase 4 — The map app** (`index.html`, ~9k LOC — largest/hardest). Sub-sequence:
  4a. *(MERGED, #571)* Map shell + `<MapProvider>` + basemap/style-reload. The imperative
      layer lifecycle written into this line was **not** in it — 4b did that.
  4b. *(COMPLETE, unserved)* Legend/filters + viewport POI fetch loop (debounce + abort + ring
      cache) + the imperative overlay module 4a left. "Search" is struck: see below.
  4c. Drawer (session/hydration AbortController guard, mobile drag-dismiss) — 4 POI drawer types.
  4d. The map-side availability tree → components + hooks, preserving seq-guarded
      staleness, the state machine, drag-resize and the popovers. **Scope corrected:
      this is 3,298 LOC across 12 files, not the 1,226 of `availability-week.js`
      alone** — that file is the biggest, not the whole phase. Measured:
      `availability-week.js` 1226, `site-matrix.js` 627, `watch-editor.js` 417,
      `site-detail.js` 338, `site-list.js` 202, `calendar-popover.js` 147,
      `watch-popover.js` 110, `booking-links.js` 88, `day-detail.js` 73,
      plus `day-fields.js`/`watch-events.js`/`auth-events.js` at ~70 together.
      It mounts inside the campground drawer (`campground.js:48`), so 4c must land
      first, and a sane sub-sequence is: the week grid + its fetch/staleness guard,
      then the day detail and site matrix, then the watch editor and popovers.
  4e. Topbar/trip planner (drag-reorder, turf corridor, route + share-link encode/restore);
      replace remaining `window.__rt*` with `tripStore`.

**Phase 5 — Decommission.** Delete legacy `web/*` + the `window.__rt*` shim; drop dual serving;
final CI/deploy cleanup; remove the `python3 -m http.server` static launch config.

---

## Phase 0 — what landed

**Scaffold.** React 18.3, TanStack Query 5, Zustand 5; Vite 6, **Vitest 3**, TS 5.6 strict,
jsdom, RTL. Multi-page build, dev proxy, three thin shells.

**LDS.** Phase 0 originally vendored all three packages from revision `2bdcf68`; they are now
installed from npm under the `@lew-ds/*` scope. `@ui` re-exports
`@lew-ds/lds-react`; `@ui/styles.css` loads `@lew-ds/lds/css` then the roadtrip theme; the shells
carry `class="theme-roadtrip mode-dark"`. The icon sprite needs no config — `@lew-ds/open-icons`
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
- **Only 7 of the 9 `window.__rt*` globals are shimmed** — and the stated reason was **wrong**.
  Phase 0 held that `__rtUseCurrentLocationForTripStop` and `__rtRouteShareUrl` are "read by
  nothing in the repo". `SmokeTest.kt` reads both (`__rtRouteShareUrl` at ~line 662,
  `__rtUseCurrentLocationForTripStop` at ~803). They are a TEST seam, not dead API. The shim
  itself is unchanged for now — the topbar that defines them is still vanilla — but **4e cannot
  finish without publishing them**, or those smoke steps fail against a working page. The same
  audit found the smoke also drives `__rtMap.jumpTo` and asserts on `__rtState.mapReady` and
  `__rtState.overlayData.{cg,sc}.features[].id`; those are published now, see below.
- **`core.js`'s "Idempotent" comment on `flattenHydratedPoi` was wrong** and is corrected in
  the port's docs (not its behavior). Fields derived only from `raw` — a park's
  `Loc_Nm`/`GIS_Acres`/`Mang_Name`, a supercharger's `stallCount`/`powerKilowatt`, Planet
  Fitness's `opening_hours` — do not survive a second pass, because the first pass consumes and
  deletes `raw`. Pinned by tests, and the legacy implementation behaves identically.

---

## Phase 4b — what landed

**The map page now renders for real in dev** (`npm run dev`, `/`): basemap, pins, viewport
fetching, and the legend. Ktor still serves `/` from `web/`, so this is invisible in a deployed
build — deliberately, because the drawer (4c) and topbar (4e) are not there yet and a served page
without them would be a regression.

**`src/map/` — the imperative layer module 4a's line promised.**
`installCGLayer`/`installPFLayer`/`installSCLayer` were three copies of the same six steps
differing only in ids, paint and categories, so `overlays.ts` holds a **registry** —
`POINT_OVERLAYS` — and one `installPointOverlay`. Layer ids are derived (`cg` → `cg-points`,
`cg-points-hit`), so a fourth overlay is a registry entry rather than a fourth install function,
and `bucketPins` dispatches categories from the same table instead of restating them in a
`paintPois` if-chain. Two pieces of vanilla bookkeeping are simply gone, because React effect
cleanup replaces them: `state.bound` (the "have I bound these handlers" flags) and
`rebindLayerHandler` (an off-then-on pair to survive style reloads).

**The fetch loop, mechanism by mechanism** (`features/map/useViewportPois.ts` documents this at
the call site too):

| Vanilla | React |
|---|---|
| 250ms `moveend` debounce | still hand-rolled — it is a property of the gesture, not the fetch |
| `AbortController` per refresh | TanStack Query's `signal`; a pan changes the key, the old query loses its observer and query-core cancels it (it cancels only if the `queryFn` consumed the signal — passing it to `fetchViewportPois` is what makes this work) |
| 8-entry viewport ring cache | `map/viewport-cache.ts`, consulted **inside** the `queryFn` |
| `routePoiModeActive()` + two manual aborts | `enabled: !routeActive`, painting `tripStore.routePois` instead |

The ring cache is **not** redundant with Query's cache: a query key matches exactly, so a
one-pixel pan is a miss, while the ring answers "I already fetched a bbox that *contains* this
one". Both tiers are needed and each is tested. The cache key still folds in whether campgrounds
will actually come back (`|cg=1`), because the server strips them below zoom 6 — without that a
cached low-zoom response would satisfy a contained high-zoom view and campgrounds would stay
invisible.

**Scope correction 1: the panel's search box is dead code and was NOT ported.** `web/search.js`
filters `searchIndex`, and **nothing has called `registerSearchItems` since the slim `/api/pois`
response stopped shipping names** — `web/app.js` says so in a comment. So the box cannot return a
result, "sort by nearest" sorts nothing, and `topbar.js`'s `pinSearch` (which reads the same index
through `getSearchIndex`) is dead for the same reason. Its one live effect was that focusing
`#search` unlocked campgrounds at low zoom *so that search results could include them* — the
purpose disappears with the search, so both go together. The sticky unlock itself is preserved for
the zoom path. **Real cross-viewport search is `GET /api/pois/search`, driven by the topbar
(4e).** Do not "restore" the panel search box on the assumption it worked.

**Scope correction 2: no park layers, and no park toggles.** The vanilla map stopped requesting
`national-park`/`state-park` (see the category list in `refreshBbox`) and left `f-np`/`f-sp` behind
as `display:none` DOM stubs feeding always-empty sources. Porting that would reproduce ~100 lines
of paint for data that never arrives. `map/viewport.ts` documents what reintroducing parks needs:
a tile-rendered path plus a polygon overlay — adding the category alone would fetch data nothing
paints.

**`mapStore`'s filter state was reshaped, and 4b is its first consumer.** Phase 0 guessed at
`categories: string[]` ("empty means unfiltered") and `agencies: string[] | null`, which cannot
express the legacy behaviour: an all-on legend with per-row opt-out. Both are **hidden sets** now
(`hiddenOverlays`, `hiddenAgencies`), which is what `web/layers.js` actually kept
(`cgHiddenAgencies`) and why it works: the legend is viewport-scoped, so an agency appearing for
the first time must default to visible. With a visible-set, panning into a new region would show
nothing until the user ticked every new row. `mapReady` is also gone from the store — `MapProvider`
owns that as `styleReady`, and two sources of truth for "may I install layers" is exactly how an
overlay ends up attached to a style that no longer describes it.

**Two API types were lying.** `fetchViewportPois` and `fetchOnRoutePois` were typed
`PoiSearchResponse` (`{ results }`) when both POST paths return GeoJSON FeatureCollections. It
typechecked because nothing read the result yet. Now `ViewportPoiCollection` / `PoiPinCollection`,
pinned against `PoiFeatureCollectionSchema` and `PoisOnRouteResponseSchema`, and the api test's
nested `[[w,s],[e,n]]` bbox is flat, which is what `PoisRequestSchema` takes.

**MapLibre is its own build chunk.** `manualChunks: { maplibre: [...] }` in `vite.config.ts`: it is
~800kB and changes only when we bump it, so bundling it with the map page's own code would
invalidate all of it on every deploy. It still trips Rollup's 500kB warning and that is expected —
the warning names `maplibre`, so a new warning naming something else is worth reading.

**The QA globals are published** (`features/map/useQaHooks.ts`). `web/app.js` publishes
`__rtMap`/`__rtState` for the smoke suite, and the React page has to as well or every map step in
`SmokeTest.kt` fails on a page that renders perfectly. Two deliberate differences: `overlayData`
carries only the overlays that exist (`cg`/`pf`/`sc` — publishing empty `np`/`sp` would invent a
fact), and `selectedPoiId` is added, because a pin click records a selection that nothing renders
until 4c and this is the only way to observe the click path. Still missing for a React `/`: the two
topbar globals above.

**The drawer's hydration path is verified, ahead of 4c.** The drawer fetches
`GET /api/pois/{id}` from the id a pin click carries, and that id has only one source: MapLibre's
top-level feature `id`. Driven in Chromium against the dev server, a click on a rendered pin yields
`id` intact for a large numeric id **and for `0`**, which is falsy but a legitimate POI id — the
case `pinFeatureId` and `selectIsDrawerOpen` were written for. The same run confirms
`properties.id` is absent from the slim response, so **the fallback never fires and numeric feature
ids are a hard dependency**: if `/api/pois` ever ships non-numeric string ids, MapLibre drops them
and every drawer silently stops opening.

**Not in 4b, and landed in 4e instead:** the `NavigationControl`/`GeolocateControl` pair and the
custom user-location puck from `web/app.js` — `map/controls.ts`, `map/user-location.ts` and
`features/map/useUserLocation.ts` now. `core.js`'s single-popup `openPopup` was on this list too
and is **deliberately not ported**: nothing in `web/` imports it (the drawer replaced popups long
ago), so porting it would have added dead code to the new tree.

### Seeing it

Historical, as of 4e: while 4b–4d were in flight the React map lived at `/preview/map` behind
`ROADTRIP_SANDBOX_PREVIEW_PAGES`, because `/` still served the vanilla page and that was what QA
of everything else ran against. **4e graduated `/`, and the preview mount and its flag are gone**
— every URL is the React one. `npm run dev` → `http://localhost:5173/` is still the fastest way
to see it (with the backend up for `/api` and `tokens.css`).

**Three things were only findable in a browser**, which is worth repeating given how much of
this migration is otherwise unit-tested. Driving the dev server in headless Chromium (POI
responses stubbed at the network layer) turned up:

1. **The map rendered inset by 20px and scrolled the page.** `app/shell.css` pads `body` for the
   two list pages, and a `100dvh` map inside that padding overflows by the padding — MapLibre's
   attribution ended up under the fold. `.rt-map-shell` is `position: fixed; inset: 0` now.
2. **`viewport-fit=cover` was missing from `frontend/index.html`.** Without it iOS reports every
   `env(safe-area-inset-*)` as 0, and the panel and its buttons — which position with
   `max(10px, env(safe-area-inset-top))` — would sit under the notch.
3. **Phase 4a's opening view did not match the vanilla map** despite a comment saying it did:
   `[-119.5, 37.5] @ z5` (California) instead of `[-98.5, 39.5] @ z3.6` (continental US). Fixed;
   it also changed what the first POI request asks for.

**Verified:** typecheck clean · 701 tests green · build emits 3 pages · color check ok · and in
headless Chromium: the page mounts, the canvas fills the viewport (1280×800, no page scroll), all
three pin overlays paint in their token colors, the legend counts and agency rows match the
response, unticking an overlay hides its pins, unticking an agency filters them, and **the overlays
survive a basemap change** — the one behaviour `styleReady` exists for. The dev-server harness is
not committed; `scripts/` has no browser tooling and the real home for this is `SmokeTest.kt`, once
Ktor serves the page.

## Phase 4c — what landed (branch `claude/react-migration-4c-drawer`)

**4b is merged** (#573, squash-merged as `4c061332`). 4c is branched off master and is
**complete**: all four drawer types, the shell, the gesture, the category dispatch, and
the `?poi=` deep link in both directions.

**What is done, and where:**

| Piece | File |
|---|---|
| Hydration by id | `features/drawer/usePoiDetail.ts` |
| Shell: sheet / panel, snap states, drag-dismiss | `Drawer.tsx`, `useDrawerDrag.ts`, `drawer.css` |
| Shared header / subline / distance / directions / pills / upstream / call buttons | `parts.tsx` |
| `?poi=<id>` deep link (write) | `poi-url.ts` |
| Category dispatch | `registry.ts` |
| Composition (loading, error+retry, unknown category) | `PoiDrawer.tsx`, rendered from `MapView` |
| Park (both kinds), Planet Fitness, Supercharger | `ParkDrawer.tsx`, `PlanetFitnessDrawer.tsx`, `SuperchargerDrawer.tsx` + `supercharger-detail.ts` |
| Campground | `CampgroundDrawer.tsx` + `campground-detail.ts` |
| `?poi=<id>` **restore** (select, reveal the overlay, fly to it) | `features/map/useDeepLinkedPoi.ts` |

**The campground drawer ships everything except availability.** `web/drawer/campground.js`
(275 lines) plus `web/campground-card.js` (589) are ported, and the one hole is by design:
`campground.js:48` imports `mountAvailabilityWeek` from `web/availability/availability-week.js`,
and that 1,226-line component is **4d, not 4c**. So there is no 7-day grid and no day-detail
panel; a pin whose provider supports availability renders an `rt-cg-availability-pending`
notice instead, and **that notice is where 4d mounts**. Everything else in the card (parent
park name, rating/reviews, amenities, cell coverage, season verdict, CTAs, structured details,
last-verified and booking-system footers) is in.

One shape change worth knowing, because 4d will touch the same file: the legacy detail rows
carried `{__html}` descriptors, so every consumer had to trust its producer. They are now a
`DetailValue` union (`text` / `link` / `chips`) that React renders, and the descriptors carry
no markup at all. The only `dangerouslySetInnerHTML` left in the drawer is `ProviderHtml`,
fed solely by `lib/upstream-html.ts`'s whitelist sanitiser — do not add a second one.

**Three decisions already made that the campground port has to honour:**

- **Hydration is the query key, not a session guard.** `beginSession` /
  `isActiveFeature` / the per-id promise `Map` all collapse into
  `queryKeys.pois.detail(id)`: a late response for a superseded selection cannot
  reach the component, Query cancels what it started, and repeat clicks on one pin do
  not refetch. Do not reintroduce a staleness check.
- **A failed hydration is an error state with a retry**, not a permanent "Loading…".
  That legacy bug (`openHydratedDrawer` had no `.catch`) is fixed in `PoiDrawer`, and
  the campground drawer's own `restartController` "Retry" affordance is therefore
  already covered.
- **Dispatch is the registry.** Add `['campground', CampgroundDrawer]` to
  `registry.ts`; until then campground pins open the drawer and say there is no panel
  yet, which is deliberate and visible rather than a dead click.

**Verified:** typecheck clean, 876 tests green, build ok, colour check ok — and, unlike every
earlier phase's claim, **driven in a real browser**: headless Chromium against the built
`dist` with `/api/pois` and `/api/pois/{id}` stubbed, at 420×900 and 1280×820, exercising
deep link → drawer → accordion → dismiss for a campground and a charger.

**Three bugs only the browser could find.** Worth reading before 4d, because two of them are
about jsdom's blind spots rather than about the drawer:

- **The drag gesture was bound to nothing.** `useDrawerDrag` read its elements from refs
  inside an effect whose deps were those (stable) ref objects — and `<Drawer>` returns null
  until its `mounted` state flips, so on the first commit the refs were empty, the effect
  bailed, and it never re-ran once the panel existed. Every unit test passed; no drawer on a
  phone could be dismissed. **The elements are now nodes held in state** (callback refs), so
  the effect has a dependency that changes when they mount. `useDrawerDrag.test.tsx` covers
  it — six of its fifteen tests fail if the refs come back. Any hook that reaches for a DOM
  node a *later* render creates has this bug; prefer a node in state over `ref.current` in a
  dep array.
- **The mobile grab pill computed to 0px tall.** The panel is a `max-height`-capped flex
  column and the pill is its only empty child; a flex item's automatic minimum size is its
  content, which for an empty box is zero, so the pill was the one thing flex could shrink
  once the content overflowed — which for a campground is always. It vanished exactly when
  the sheet was fullest, and on mobile it is the *only* dismiss affordance (the × is
  `display: none` there, and the backdrop takes no pointer events by design). Fixed with
  `flex: 0 0 auto`. **jsdom does no layout, so no unit test can hold this** — the comment in
  `drawer.css` says so at the site.
- **The `?poi=` deep link only worked in one direction.** `poi-url.ts` wrote the parameter
  and nothing read it, so clicking pins updated the URL correctly and every shared link
  opened a bare map. `useDeepLinkedPoi.ts` is the read half, ported from
  `restoreSharedLinkFromUrl` / `openSharedPoiFeature`: it selects the POI, reveals its
  overlay (and, for a campground, its agency — a shared link can point at a layer the
  recipient switched off), and flies the camera once, gated on `styleReady` because a
  `flyTo` before the style is up is silently dropped. `SmokeTest.kt` loads `/?poi=…`
  directly, so this was also a live contract.

While fixing that last one: **`geomCenter` answers `[0, 0]` for a geometry it could not
read**, and `[0, 0]` is a coordinate in the Gulf of Guinea that passes every
`Number.isFinite` check. The vanilla `flyTo` paths guarded on exactly that, so a POI whose
geometry failed to load flew the map to null island. `lib/geo.ts` now exports
**`hasCoordinates`** for camera-moving callers; `geomCenter`'s own contract is unchanged
(it is parity-pinned).

**Two things that are faithful, not broken, if you go looking:** the dismiss gesture is
touch-only in both trees (`chrome.js` binds `touchstart`/`touchmove` and nothing else), so a
*mouse* drag on a narrow desktop window cannot dismiss the sheet — and on that width the ×
is hidden, so the only exits are a map click that hits no pin or widening the window. And
the geolocate control is still unported, so `useDistanceTo` returns `''` and no drawer shows
a distance yet; that is 4e.

**The harness, if it is wanted again:** a ~120-line Node static server (serve
`frontend/dist` at `/`, the repo's `web/` at `/web` for the runtime-injected `tokens.css`,
canned `/api/pois*`) plus `playwright-core` against `/opt/pw-browsers/chromium`. Two notes
that cost time: **pick a raster basemap** via `localStorage.setItem('basemap', 'carto-dark')`
in an init script, because the default vector styles fetch style JSON from openfreemap and
without a loaded style `styleReady` never flips — the overlays and the flyTo then look
broken for a reason that has nothing to do with the code; and **drive touch through CDP**
(`Input.dispatchTouchEvent`), since Playwright's mouse API cannot exercise a touch-only
gesture.

## Phase 4d — what landed (branch `claude/react-migration-4d-availability`)

The map-side availability tree: `web/availability/`'s 3,298 lines across twelve modules,
mounted where 4c left an `rt-cg-availability-pending` notice in the campground drawer.
Gated on the backend's own provider-capability flag, so it only appears for pins that
genuinely have availability to show.

**Nothing in `web/availability/` is deleted.** The vanilla map page still mounts it, and
`watch-editor.js` is imported by the vanilla topbar's alerts panel as well. Phase 5 removes
them with the rest of `web/`.

**What is where.** The split is deliberate: everything a reviewer needs to check is a pure
function, and the components only draw.

| Piece | File |
|---|---|
| One envelope per campsite → one status per day | `fuse.ts` |
| Which rows, in which order, and what a cell means | `matrix-rows.ts` |
| Watch windows and the provider capability gates | `watch-windows.ts` |
| Booking deep links from a per-campsite template | `booking-links.ts` |
| Campsite facts, features, capacity, image search | `site-detail-facts.ts` |
| Selected-day list rows | `site-list-rows.ts` |
| Date labels (all UTC — see below) | `week-labels.ts` |
| Persisted Site-column width | `site-column.ts` |
| Provider-fault copy | `availability-errors.ts` |
| Week fetch, catalog fetch, watch list + mutations | `useWeekAvailability.ts`, `useCampsites.ts`, `useWatches.ts` |
| Skeleton delay | `useDelayedFlag.ts` |
| Components | `AvailabilityWeek.tsx`, `SiteMatrix.tsx`, `SiteList.tsx`, `SiteDetail.tsx`, `DayDetail.tsx`, `WeekNav.tsx`, `CalendarPopover.tsx`, `WatchPopover.tsx`, `WatchEditor.tsx` |
| Styles | `availability.css` |

**Three pieces of the vanilla controller are absent rather than translated**, and the
absences are the point of the port:

- **The scroll capture/restore dance is gone.** `captureMatrixScroll` /
  `restoreMatrixScroll` / `restoreMatrixScrollAfterTap` existed because an `innerHTML`
  re-render replaced the scrolling element and lost `scrollLeft` — twice over for a booking
  tap, which needed a `requestAnimationFrame` double-restore. React keeps the node.
- **`weekRequestSeq` / `sitesRequestSeq` are gone.** They were compared at every await point
  so a slow response for last week could not paint over this one. Query keys do that
  structurally.
- **`restoreMatrixFilterFocus` is gone.** It re-focused the search box and restored its caret
  after every keystroke, because the input was destroyed on each re-render. A controlled
  input keeps both.

**What is NOT simplified**, because each is a product decision:

- **Booking takes two taps.** The first arms the cell (its label becomes "Book"), the second
  opens the provider. These are 66px cells in a horizontally scrolling grid on a phone; one
  tap would open a booking tab every time a thumb brushed the wrong column. Arming is
  exclusive, so the label itself says which night is about to be booked — which is why
  changing week *or* changing a filter disarms it. Both are pinned by tests.
- **The rollup puts `unknown` ahead of `reserved`.** A stream we could not read must not
  render as a confident "full": "?" sends the user to check, "R" sends them away.
- **`empty` and `closed_for_season` stay separate states.** One is permanent, one is a date.
- **The day panel's four-way message.** Watch toggle / "sign in" / "not available for this
  campground" / "no online openings to watch" — each of the other three would be a lie in
  the cases the others cover.

**Two deviations from the original, both noted at the site:**

- `rollupStatus` normalises before comparing. The original compared provider strings
  verbatim, so `"Available"` fell through every branch and rolled up as `unknown` — the one
  place in either tree where casing changed the answer.
- The watch editor's add-to-cart toggle is **enabled** for a watch that already uses it, so
  it can be turned off. The original wrote `disabled: busy || (!canAtc && !state.addToCart)`,
  whose second clause is exactly the case where the row is not rendered at all.

**Verified:** typecheck clean, 1,090 tests green, build ok, colour check ok, and driven in
headless Chromium at 1280×900 and 420×900 against a stubbed 30-site campground — the sticky
Site column holds at 128px through a 200px horizontal scroll, the page itself never scrolls
sideways, the two-tap arm/open flow works, the watch popover escapes the matrix's scroll box
at 240px, the calendar disables the 15 out-of-range days in the opening month, selecting a
day lists its five openings with per-night booking links, and a site row expands to seven
facts. No console errors on either viewport.

**The bug that mattered, and the guardrail it earned.** The watch editor rendered 1061px
wide instead of 240. Two causes, stacked:

1. Its rules were the one part of the availability CSS that never lived in a stylesheet —
   `watch-editor.js` built ~150 lines as a template string and injected a `<style>` tag into
   `document.head`, so lifting the `cg-*` block out of `index.html` missed all of them. (They
   are also now under the colour guardrail for the first time; two literal colours needed
   tokenising.)
2. The `cg-btn` family, which the day panel's toggle uses, lost its closing brace on the way
   in — so every rule after it became a descendant of `.cg-btn[disabled]` and silently
   stopped matching.

A rule without its closing brace is still **valid CSS**, which is why nothing caught it: the
build succeeded, the colour checker passed, and jsdom does no layout so 1,090 unit tests
passed too. `scripts/check-css-blocks.mjs` now checks brace balance and rejects a selector
appearing inside a rule body, and runs in `make test` and CI. It is not a parser check — a
parser accepts the broken file, since nesting is legal CSS now; we simply do not use nesting,
so its appearance means a brace went missing.

**Five more defects, from an adversarial review after the port was "done".** Worth
reading as a set, because four of the five are shapes that will recur:

- **The calendar popover opened off-screen.** `.cg-cal-host` positions itself with
  `position: absolute; top: 100%`, so it needs a positioned ancestor — the vanilla
  appended it to the clicked button's `parentElement`, which is `.cg-week-nav`
  (`position: relative` for exactly that reason). Rendered at the section root it
  resolved against the drawer instead: 906px top in a 900px viewport, 619px below the
  button. **The first browser pass reported the calendar "working"** because
  `waitForSelector` found it in the DOM — a lesson about what a smoke assertion is
  worth. It is a `calendar` slot on `<WeekNav>` now.
- **A 401 on a watch mutation said "Could not save. Try again."** Retrying is exactly
  what cannot help. Mutations map a 401 to `WatchAuthError` and refetch the list, which
  collapses the grid to "Sign in to set availability alerts."
  Two things fell out of testing it, both worth knowing: **a throw inside a mutation's
  `onError` is discarded** (`mutateAsync` still rejects with the original), so the
  mapping belongs in `mutationFn`; and the message **cannot live in the editor**,
  because withdrawing the affordance unmounts the anchor cell and closes the popover.
  It is a toast — one surface, and one that outlives the clicked control.
- **`console.warn` in a render body** (`usePoiWatches`), which re-fires on every
  re-render while the error is cached. A render must have no side effects.
- **`setState` inside another setter's updater** (`onSelectDate`). Updaters must be
  pure; StrictMode double-invokes them, and both setters batch identically outside one.
- **An armed booking cell survived expanding a site row**, which pushes rows down. The
  vanilla disarmed on any non-booking click in the grid.

**One parity observation for design, not a regression.** At the default 128px, the Site column
truncates to "Upper Loop / Site …" for two-digit site numbers, because the loop prefix eats
the width. The markup, the CSS and the 128px default are all straight lifts, so the vanilla
grid does the same; the full name is in the `title` attribute and the column is drag-resizable.
Worth a look, but widening the default is a design change rather than part of the port.

### Phase 4d follow-ups (MERGED, #577)

Three defects a second review of the merged 4d found, all in `AvailabilityWeek`'s wiring
rather than in the pure layers, and all three worth reading as shapes that recur:

- **Availability state survived switching campgrounds.** The drawer does not unmount the
  grid when a second pin is clicked — it re-renders with the new feature, in one commit,
  because Query usually has it cached. So the new campground inherited the old one's
  visible week, selected day, expanded site row, armed booking cell and filter text.
  `weekStart` was the one that could not recover: it is seeded from the feature's
  earliest date by `useState` once and never again, so a campground whose booking window
  opens in October was shown a week its provider will not quote for. Fixed by splitting
  the component in two — an outer gate that returns null without an id and renders the
  inner view **keyed on the POI id**, so the whole reset is a remount. The split also
  removes a latent rules-of-hooks violation: the old `if (poiId == null) return null`
  sat *after* the id gate but *before* the hooks, so a feature arriving with an id after
  one without would have changed the component's hook count. **Any component holding
  state scoped to a prop that can change under it wants a key, not a reset effect** — a
  reset effect has to be extended for every new piece of state, and this one had seven.
- **Email-only providers got a dead "Set watch" button.** `supportsWatchAlerts` accepts
  Slack *or* email (deliberately), so the day panel offered its toggle — but the toggle's
  one-tap default is a Slack watch, and the handler `return`ed silently when the provider
  had no Slack. The button now opens the watch editor anchored to itself, which is where
  an email watch's address goes, and `WatchEditor` pre-ticks email when it is the only
  channel the provider has (Slack stays preferred when both exist: it needs no further
  input). A form whose single toggle starts off can only fail its own "select at least
  one trigger" check.
- **Watch loading and server failures were presented as signed-out sessions.**
  `usePoiWatches` returned `canManage: false` for the in-flight first load *and* for every
  error, and the grid read any `!canManage` as anonymous — so a slow request told a
  signed-in user to sign in, and a 500 told them so permanently. `PoiWatches` now carries
  a four-state `WatchAccess` (`loading` / `ready` / `unauthorized` / `error`), and
  `DayDetail` takes a `WatchUnavailableReason` instead of a `signedOut` boolean:
  "Checking your availability alerts…", "Couldn't check your availability alerts" with a
  retry, "Sign in…", "not available for this campground". `usePoiWatches` also tightened
  to a non-null `poiId` — the disabled-query branch was unreachable once the outer gate
  existed, and it would have been a fifth thing for `access` to mean.

**Verified:** typecheck clean, 1,104 tests green, build ok, colour check ok, CSS-block
check ok — and driven in headless Chromium at 1280×900 and 420×900 against a stubbed
email-only campground: the day panel renders "Set watch", the tap opens the editor at
240px fully inside the viewport (top 354 / 484, no clipping) with Email pre-ticked and no
Slack row, and saving posts `trigger_kinds: ["email_notify"]` with the address in
`trigger_config`. The one anchor worth checking in a browser was the day-panel button:
4d only ever anchored the popover to a matrix cell.

## Phase 4e — COMPLETE (branches `claude/react-migration-4e-topbar`, `…-4e-results`)

The topbar and trip planner: `web/topbar.js` (2,054) + `web/topbar/alerts.js` (655) +
`web/topbar/auth.js` (167) + `web/topbar/state.js` (46) + `web/share-links.js` — plus the
map's own controls, and **the graduation of `/`**. The React map is what every visitor
gets now; `web/` is still on disk and still serves `tokens.css`, the sandbox modules and
the GeoJSON layers, but no page resolves to it.

Landed across two branches: the panel itself on `claude/react-migration-4e-topbar`
(PR #578), and the results list, the alerts panel, the map controls and the graduation on
`claude/react-migration-4e-results`, which is stacked on it.

### What landed

| Piece | File | Notes |
|---|---|---|
| Share links: both writers, the `?route=` reader, clipboard | `lib/share-links.ts` | 4c already ported the `?poi=` reader |
| The stop list as algebra | `features/trip/stops.ts` | every rule from `onRowX`/`onAddStop`/`addTripStopFromExternal`/the drop handler |
| Corridor buffer + radius clamp | `features/trip/corridor.ts` | turf from npm |
| Summary copy, leg lines, routing errors | `features/trip/route-summary.ts` | |
| Dropdown data: kinds, ordering, sections | `features/trip/search-results.ts` | |
| Route fetch (single writer of `tripStore.route`) | `features/trip/useRoute.ts` | key replaces `generation` + `routeAbort` |
| Corridor POI fetch | `features/trip/useOnRoutePois.ts` | 250ms debounce, publishes on success only |
| Search-as-you-type | `features/trip/useSearchResults.ts` | two sources in parallel |
| `?route=` in both directions | `features/trip/useSharedTrip.ts` | |
| The controller | `features/trip/useTripPlanner.ts` | transitions + focus + geolocation + camera |
| The panel | `TopBar`, `StopRow`, `SearchDropdown`, `RouteStatus`, `CorridorSlider`, `topbar.css` | mounted from `MapView` |
| Auth row + `<SettingsModal>` trigger | `features/trip/AuthRow.tsx`, `auth-row.css` | Phase 3's mounting task, done |
| Route line + corridor fill, bounds | `map/route-overlay.ts` | imperative, `FakeMap`-tested |
| Stop markers | `map/trip-markers.ts` | positional registry |
| The four map effects | `features/map/useTripOverlay.ts` | install / fit / radius / markers |
| The `window.__rt*` seams | `stores/transition-shim.ts` (`useTransitionShim`) + `TopBar` | installed for the first time — see below |
| Distance along the route | `features/trip/route-index.ts` | `indexRoute` / `distanceAlongRouteKm`, a cumulative table |
| The card list's data | `features/trip/trip-cards.ts`, `useTripCards.ts` | sort, hydrate, agency/campground filtering |
| The card list | `features/trip/TripResults.tsx` + results CSS | collapsible; **owns the corridor slider** |
| Alerts: the rules | `features/alerts/alert-rows.ts` | ordering, counts, copy, the Slack deep-link contract |
| Alerts: the data | `features/alerts/useAlerts.ts` | three statuses via `useQueries`; 401 = anonymous |
| Alerts: the panel | `features/alerts/AlertsPanel.tsx`, `alerts.css` | row actions + 4d's `WatchEditor` as the edit row |
| Watch formatting, shared | `lib/watch-format.ts` | lifted out of `WatchTable.tsx`, two consumers now |
| Zoom + locate-me controls | `map/controls.ts` | bottom-right, one low-accuracy fix |
| The user-location puck | `map/user-location.ts`, `features/map/useUserLocation.ts` | follows the STORE, not the control event |
| Graduation of `/` | `StaticSiteRoutes.kt`, `SmokeTest.kt` | `previewPages` and its flag deleted |

`TripStop` gained `pending` (the legacy `_pending`); `tripStore`'s geometry fields are
GeoJSON-typed rather than `Record<string, unknown>`; and turf arrived as `@turf/buffer` +
`@turf/simplify`, replacing the CDN `@turf/turf@7` global — the same swap Phase 2 made for
Chart.js.

**What the query key replaced, and what it did not.** As in 4b, three pieces of hand-rolled
bookkeeping are gone because the key does the job: `trip.generation` (a stale response has
no observer to reach), `trip.routeAbort` (Query's signal), and the vanilla's inconsistent
"should this re-route" decision per handler — producing a complete trip *is* requesting its
route. The two debounces stayed hand-rolled, because both are properties of a gesture
rather than of a fetch: 250ms on the corridor slider, 220ms on typing.

**Six things worth knowing before continuing:**

- **The share format is a contract.** `share-links.test.ts` pins a literal string the
  vanilla encoder produced, in both directions — decoded, and re-encoded byte for byte.
  A link already in circulation has to keep working, so treat those two tests as the
  spec rather than as characterisation.
- **The corridor's simplification is not cosmetic.** The same polygon is POSTed to
  `/api/pois/on-route`, where the backend caps a request polygon at 2000 vertices, and an
  unsimplified 100-mile buffer of a cross-country line exceeds it. Pinned by a
  vertex-count test.
- **The corridor radius is deliberately NOT in the route key** (`queryKeys.route` says so
  at the definition): the road does not depend on it, only the corridor polygon the
  response carries does, and the slider recomputes that locally. Keying on it would fire a
  routing request per slider settle.
- **`pending` has to live on the stop.** The row can move while geolocation resolves, and
  both routing and marker placement must skip a stop whose coordinates are still (0, 0) —
  null island passes every finite check, which is the same trap `hasCoordinates` exists
  for in 4c. The `Number(null) === 0` variant of it bit the search-result coordinate guard
  too, and is fixed there.
- **Two legacy branches are unreachable and were dropped**, not translated: `onRowX`'s
  `if (wasFilled) { clear in place }` inside its `stops.length <= 2` guard (every row in a
  two-row trip is structural, so a filled one returned earlier), and the transitions'
  `shouldRoute` field, which nothing can consume now that the stops are the key. Both
  noted at the site.
- **The DOM contract the smoke suite addresses is preserved on purpose**: `data-i` on rows,
  and the ids `#topbar`, `#tb-actions`, `#tb-directions`, `#tb-route-summary`,
  `#tb-dropdown`, `#tb-results`, `.tb-results-*`, `.tb-card*`, `#tb-corridor`,
  `#tb-corridor-range`, `#tb-corridor-value`. Cheap, and it is why graduating `/` left the
  smoke's topbar assertions untouched. Two selectors it names are **`hasCount(0)`
  assertions, not features**: `#tb-share-route` and `#tb-trip-dates` were dropped, and the
  smoke pins their absence. And the corridor slider lives *inside*
  `#tb-results .tb-results-body`, which is also what the vanilla did.

**A latent Phase 0 defect, found in a browser rather than by a test.**
`installTransitionShim` had existed since Phase 0 with **no caller**, so every
`window.__rt*` global was absent from the React tree. Unit tests could not see it — they
call the installer directly. Driving the built page in Chromium and asking for
`__rtRouteShareUrl()` returned `undefined`, which is what surfaced it. It is now mounted by
the map page (`useTransitionShim`), not by `AppProviders`: the watches and availability
pages have no business publishing a trip API. The two globals the Phase 0 audit recorded as
"read by nothing" are published — `SmokeTest.kt` reads both — with `__rtRouteShareUrl` in
the shim and `__rtUseCurrentLocationForTripStop` in `TopBar`, because filling a row needs
the planner.

**Verified** (each commit re-ran all four gates): typecheck clean, **1,394 tests green**,
build ok — only `maplibre` trips the 500kB chunk warning, as before — colour and CSS-block
checks ok. Kotlin's own gates could NOT run here: `:backend:generateJooq` needs Docker for
its testcontainer Postgres, so `StaticSiteRoutesTest` and the rest are unverified in this
environment (detekt passes, and the touched files compile far enough to resolve). And driven
in headless Chromium at 1280×900 and 420×900 against stubbed
`/api/pois/search`, `/api/geocode`, `/api/route` and `/api/pois/on-route`: the panel fits
both viewports and clears the legend control, typing lists both sources under POIS/PLACES
headers with the dropdown above the fold, a pick fills the row, Directions adds the second
row, the route lands ("132 km · 2h 5m", `trip-route-line` + `trip-corridor-fill` installed,
two stop markers, `?route=` in the address bar, a non-empty `__rtRouteShareUrl()`), and the
corridor slider is draggable at both widths, reads "2 campgrounds along the route" and does
not re-request the route.

**Seven defects an adversarial review of 4e found, all in its own code and none caught by
its tests.** Worth reading as a set, because four of the seven are shapes that recur:

- **A derived value computed in two places disagreed with itself.** The corridor was built
  by the install effect *and* by the radius effect, with a `installedMiles` ref meant to
  keep them in step; a basemap change re-ran the install, which re-preferred the server's
  polygon and then stamped the current radius as installed, leaving the fill permanently at
  the radius the route was fetched at. It is one memo now. **If two effects compute the same
  thing and a ref arbitrates, the ref is the bug.**
- **LDS's `Modal` needs the consumer to position its scrim.** `.lds-modal-scrim` has a
  background, `display: flex` and centring, but no `position` — so `<SettingsModal>` mounted
  inside the topbar rendered as a block in a 420px panel. It is portalled to `document.body`
  now, with the positioning in `account.css` scoped by `:has()`. Nothing had ever mounted a
  Modal before, which is why Phase 3's tests were all green.
- **A store action whose name fit was the wrong action.** The drawer's Directions button
  called `tripStore.addStop` (appends to the first empty slot) where the vanilla made the
  POI the *destination* of a new two-row trip. Invisible until the topbar existed to show
  the difference.
- **`(0, 0)` again.** `__rtRouteShareUrl` reads the store directly, without the
  `allStopsFilled` gate, so a "Locating you…" placeholder's finite coordinates were encoded
  into a shareable link. Third time this trap has been paid for (4c's `hasCoordinates`, 4e's
  search-result guard, this).
- An X button rendered on a row whose removal is a deliberate no-op (`draggable` used as a
  proxy for the vanilla's `canRemove`).
- `Number('')` is 0, so a file dropped on a stop row reordered row 0.
- The `?route=` writer's first pass ran before the restore was observable, deleting the
  parameter it was mounted with and re-adding it on the next render.

`FakeMap` had no `fitBounds`, which is why the first of those was reachable at all: every
route-mode test until then set a route with no line feature, so the camera fit had never
run in jsdom. It has one now, and the trip overlay has coverage for the install, the fit
happening once per route and not on a basemap change, and the corridor surviving a style
reload at the dragged radius.

**One parity observation for design, not a regression.** On desktop an open drawer covers
the topbar: the drawer is a left panel at `z-index: 999` and the topbar sits at 5. The
vanilla's `.cg-drawer` does exactly the same above `min-width: 768px`, so this is a lift,
not something the port introduced — but it now matters more, because the topbar is the
only search surface. Worth a look.

### The results list, the alerts panel and geolocation — what they turned out to be

**Trip results** (`web/topbar.js:1736-2054`, read for this). The card list is a projection
of the corridor response, sorted by distance along the route, each card hydrated from
`/api/pois/{id}` through the same query key the drawer uses — so a card and a drawer for
the same campground share one round trip. `route-index.ts` is the only new algorithm: a
cumulative-distance table over the route line, and a closest-segment lookup in degree
space, which is what turns a POI into "33 km in". Two features the smoke's selectors
implied do NOT exist and are asserted absent: `#tb-share-route` and `#tb-trip-dates`.

**The alerts panel** (`web/topbar/alerts.js`). Three status lists through `useQueries`, POI
names under `queryKeys.pois.name`, and a 401 read as "this visitor is anonymous" rather
than as a fault — so the panel is *absent* rather than showing an error, the same call 4d's
availability grid makes. A Slack deep link (`?alert=<id>&alert_action=<action>`) expands the
panel, scrolls the row into view and **pulses the named control without pressing it**: a
stale or forwarded card must not be able to change a watch, so the app stays the only
writer. The edit row is 4d's `WatchEditor`, which is the second consumer that port was
written for. `relativeTime` / `formatWatchDate` / `watchFallbackName` moved out of
`WatchTable.tsx` into `lib/watch-format.ts` now that two surfaces say the same things.

**Geolocation**, with three deliberate departures from `web/app.js`:

- **the puck follows the store, not the control event.** `mapStore.userLocation` has three
  writers (the map control, the topbar's locate button, the
  `__rtUseCurrentLocationForTripStop` seam) and the vanilla drew a puck for the first of
  them only — so locating yourself from the topbar left the map with no "you are here" at
  all. One effect on one field covers every writer, and makes the store's "single source of
  truth for where am I" comment true rather than aspirational.
- **a failed fix reports through a toast.** The vanilla appended a `div.geo-banner` to the
  body and **`web/` has no `.geo-banner` rule anywhere** — that message has been rendering
  as unstyled text at the top of the document. `<ToastProvider>` is already app-wide.
- **`showUserLocation: false` on the control**, because we draw the puck. The vanilla asked
  for `showUserHeading: false`, which this version of MapLibre has no option for at all, so
  that line was doing nothing.

`rerenderSearchResults()` has no counterpart: `useSearchResults` subscribes to
`userLocation` for its proximity bias, so React re-renders it by subscription. And
`core.js`'s `openPopup` is **not ported on purpose** — nothing in `web/` imports it, so it
is dead code there and would have been dead code here.

### Graduating `/` — what it cost

`index.html` joined `migratedPages`; `urlFormsOf` gives the root page `/` as its second URL
form instead of the `/index` that stripping `.html` produces; and the catch-all's
`default(index.html)` came off so the explicit route is the only claimant on `/`. The
`/preview/*` mount and `roadtrip.sandbox.preview-pages` were deleted with it — the map was
the only page they ever carried — which also took out `SandboxConfig`'s field, the
`application.yaml` key, the sandbox compose env var and the secret-registry entry.

**The deploy health check now asserts `/` is the React shell**, not merely that it 200s.
`/` is the one page that still has a legacy file, so a deploy whose frontend build was
skipped degrades it to the vanilla map *with a 200* — `/watches` and `/availability` 404
instead, which the existing probe already catches. The check greps for `id="root"`.
`scripts/deploy.sh`'s "npm not found" error says the same thing for a sandbox, where the
failure mode is a reviewer looking at the wrong app.

**A P0 in 4c that graduation would have shipped.** `flattenHydratedPoi` rewrites a
campground's `category` to its `subcategory` (a `core.js` behaviour its parity suite pins
byte for byte), and `drawerFor` keyed on `category` — so every campground carrying a
subcategory, which is nearly all of them, opened *"No detail view for this place yet"*. The
vanilla never had to answer the question: it dispatched on which layer was clicked
(`layers.js` called `openCampgroundDrawer` directly). `drawerFor` now takes the flattened
properties and recognises the rewrite (`category === subcategory` ⟹ campground, and only
campgrounds are rewritten). Every drawer test until then used a park, which is not
rewritten — so the suite was green. **Found by driving the built page in Chromium**, which
is now three phases in a row where the browser found what the tests could not.

**`SmokeTest.kt` moved onto the React DOM.** The topbar assertions are unchanged (that is
what the preserved ids bought); the drawer and legend are addressed by role and accessible
name instead of `#cg-drawer.open` / `#panel` / `#cg-agency-legend input[data-cg-agency]`.
Every selector in the rewritten suite was verified against `frontend/dist` in headless
Chromium with a stub API, because the Kotlin suite needs a live stack (Postgres, the
geocoder, tiles) and **could not be run in this environment** — nor could the Kotlin unit
tests, since `:backend:generateJooq` needs Docker. Three findings from that pass, all of
which would have failed CI:

1. **The agency rows have to be clicked by their label.** LDS renders the real input at
   `opacity: 0` with no size and `pointer-events: none` and draws `.lds-check__box` in its
   place, so `check()`/`uncheck()` on the input times out with "element is not visible".
   The input is still what reports state.
2. **The corridor slider is not in the DOM until a route is live**, because it lives inside
   the results list now. The vanilla built the row up front and hid it, so the old
   assertion passed against a `display: none` control. The assertion moved to after the
   route lands.
3. **`__rtSetRoutePois` can no longer drive a repaint** — React paints route POIs only
   while a route is *active* — so the agency filter's "the un-check survives a repaint"
   step drives `__rtRefreshBbox` instead.

`/` also joined the mount-and-render test, probing the legend's heading: it renders whether
or not MapLibre ever gets a style, which makes it a test of the bundle rather than of the
tiles.

### What remains

1. **The `window.__rt*` shim can go**, once `SmokeTest.kt` no longer reads it. It is a
   transition seam, and the transition is over; the smoke could drive the same steps
   through the UI, at the cost of a slower and flakier suite. Worth a decision rather than
   a default — the shim is 60 lines and buys the suite deterministic access to trip state.
2. ~~**`@tokens` is the last legacy import**, and it survives on purpose~~ — **done, and
   the reasoning above was wrong on both counts.** `tokens.css` moved into
   `frontend/src/tokens/` and is bundled; `@tokens` is a plain tsconfig path now.
   - The "no frontend build" property was **already gone**: LDS components read
     `--c-*`/`--surface-*` from the bundled `theme-roadtrip`, whose values are baked hex
     *copied* from tokens.css (`--c-600:#3B82F6;  /* --rt-brand */`). Editing tokens.css
     alone never restyled an LDS component, so the property held only for `--rt-*`
     consumers, and paying a runtime mount for it was not worth it.
   - Retiring the alias did **not** require reconciling `--rt-*` with `--c-*`. That
     reconciliation is still outstanding (see Token strategy) and is genuinely a design
     decision — but it is orthogonal to where the file lives. Moving it kept both
     vocabularies exactly as they were.
3. **One design question, not a regression.** On desktop an open drawer covers the topbar
   (drawer `z-index: 999`, topbar 5). The vanilla did exactly the same above 768px, so it
   is a lift — but it matters more now that the topbar is the only search surface.

## Phase 5 — COMPLETE (branch `claude/react-migration-5-delete-vanilla`)

**116 files deleted**, plus the root `index.html`: the whole strangled app (`app.js`,
`layers.js`, `core.js`, `basemap.js`, `search.js`, `share-links.js`, `topbar*`, `drawer/`,
`account/`, `availability/`, `api/`, `utils/`, `components/`, `campground-card.js`,
`upstream-html.js`), its seven `node:test` suites, and the vanilla design-system
primitives with their gallery and docs — all of it unreachable once `/` became React.

**Three things stayed, and a follow-up moved all three into `frontend/`.** As merged,
`web/design-system/tokens.css` was the colour source of truth **served rather than
bundled** so a token change needed no frontend build; `tokens.js` was the `@tokens`
bridge for values `var()` cannot reach (MapLibre paint, canvas charts); and the two
`sandbox-*` pairs are what let a reviewer pick a seed user on an auth-disabled sandbox.
Every React page loaded all three at runtime, injected by
`vite/runtime-served-assets.ts` with `runtime-served-assets.test.ts` pinning the tag set.

They are now `src/tokens/tokens.{css,ts}` and `src/app/sandbox/`, bundled, with the
`/web` mount and the plugin deleted — see "Finishing the job" below for why the
served-not-bundled rationale did not survive contact with the LDS theme.

**What the deletion touched beyond `web/`:**

- **`lib/poi.test.ts`'s parity suite is retired** with the `@legacy/core` alias and
  `src/types/legacy.d.ts`. It ran the port and `web/core.js`'s original over eleven
  fixtures and compared output — the strongest check available while both existed, and
  meaningless with one gone. The fixtures stay: they are exhaustive *because* they were
  built for that comparison, and the behaviour tests over them are now the contract.
- **`/`'s legacy fallback is gone**, because the file it fell back to is. All three pages
  are served from the build or 404 — `migratedPageFile` keeps its "built, else legacy, else
  nothing" shape, but the middle branch can no longer fire. `StaticSiteRoutesTest` says so:
  the fallback test now writes the legacy file it needs, so it proves precedence rather
  than passing over an empty directory.
- **The colour checker lost its root shell and four budget entries.** `ROOTS` stats every
  entry, so a deleted shell must leave the list too — that is twice this list has shrunk
  for that reason. The legacy `rgba()` high-water mark went from **51 to 7** by deleting
  files rather than tokenizing anything; the remaining 7 are the sandbox switcher's.
- **The `web/**` CI filters stay**, narrowed in intent rather than in path: three asset
  pairs that every page loads at runtime can still break every page, so they still gate
  `smoke` and `web-tests`. The `index.html` filter entries went with the file.
- **`docs/frontend-components.md` was rewritten**, not patched. `AGENTS.md` makes it
  mandatory reading before a frontend change, and it described the vanilla mount contract,
  the `*-template.js` rule, event delegation and a table of eight deleted primitives. A
  mandatory doc describing a deleted tree is worse than no doc.
- Compose no longer bind-mounts `./index.html`; `deploy.sh`'s "npm not found" error says
  the whole site is unavailable rather than one page; `CachePolicyTest`'s example path is a
  file that exists.

**The `#root` probe in the deploy health check outlived its original reason.** It was added
in 4e because `/` could silently degrade to the vanilla map with a 200; with `index.html`
deleted, an unbuilt frontend 404s and the plain probe catches it. It stays as the cheaper
half of the same guarantee — it asserts the page served at `/` IS the React shell, so a
stray file at the static root cannot pass for a working deploy.

**Verified:** frontend typecheck, **1,382 tests** (down from 1,394: the parity suite's
twelve went with it), build,
colour check (7 legacy occurrences, down from 51), CSS-block check, the 24 surviving
`web/` node tests (down from 153), `ktlintCheck` and `detekt`. The Kotlin unit suites
remain unrunnable here — `:backend:generateJooq` needs Docker — so `StaticSiteRoutesTest`'s
rewritten cases are CI's to confirm.

## Finishing the job — `web/` is actually gone (follow-up to Phase 5)

Phase 5 kept three files and the plumbing that served them. This removed all of it, so
`frontend/` is the whole of the site and `data/` is the only other static tree.

| Was | Is | Loaded by |
|---|---|---|
| `web/design-system/tokens.css` | `frontend/src/tokens/tokens.css` | `@import` in `src/ui/styles.css` |
| `web/design-system/tokens.js` | `frontend/src/tokens/tokens.ts` (typed) | the `@tokens` alias, unchanged at ~9 call sites |
| `web/sandbox-banner.{js,css}` | `frontend/src/app/sandbox/sandbox-banner.ts` + `sandbox.css` | `initSandboxChrome()` from `mountPage` |
| `web/sandbox-user-switcher.{js,css}` | `frontend/src/app/sandbox/sandbox-user-switcher.ts` + `sandbox.css` | same |
| `web/design-system/tokens-usage.test.mjs` | `scripts/check-token-usage.mjs` | `make test` and CI |
| the `runtimeServedAssets` plugin, the `/web` mount, the flat catch-all | *(deleted)* | — |

**The served-not-bundled rationale did not survive checking.** It was "a token change
takes effect without a frontend build". That was already false for most of the UI: LDS
components resolve `--c-*`/`--surface-*` from the bundled `theme-roadtrip`, and those are
baked hex *copied out of* tokens.css — `--c-600:#3B82F6;  /* --rt-brand */`. Editing
tokens.css alone restyled only the `--rt-*` consumers, never an LDS control, so the
property being preserved was a fraction of the one advertised. Bundling also removed a
papercut: `npm run dev` no longer needs the backend up to render styled, and an unstyled
dev page is now a build problem rather than a missing proxy target.

**`tokens.js` → `tokens.ts` reverses a Phase-0 decision, correctly.** Phase 0 refused to
port it because "a TS copy would be a second source of truth for those colors and would
itself trip the raw-hex check". Both halves were about it being a *copy*: this is a move,
so there is still exactly one fallback table, and the checker exempts it by path — that
entry already existed and only needed repointing. The hand-written `src/types/tokens.d.ts`
is gone with it.

**The sandbox chrome swapped a build-time guarantee for a structural one.** The plugin
existed because Vite treats `<script type="module" src>` in an entry as a build input and
*fails* on a path outside its root, so the tags could not live in the HTML — and a page
that silently forgot the user switcher looks signed-out in every sandbox, which is
indistinguishable from a real auth failure. `runtime-served-assets.test.ts` pinned the tag
list for that reason. `mountPage` now calls `initSandboxChrome()`, so a page that mounts
at all has the chrome, and `src/app/mount.test.tsx` pins that instead. Three deliberate
changes came with the port: no import-time auto-init (the vanilla modules ran `initX()` on
import, which is how a bundled unit test ends up making three network calls); the fetches
go through the API layer (`src/api/sandbox-api.ts`, DTOs pinned against `BuildInfoDto` and
`SandboxUserDto`); and the suites drive jsdom rather than a hand-rolled `doc` stub, which
is why the banner test can now see the classes and the commit link it renders. The one
seam kept is `renderUserSwitcher`'s `loc` parameter — `window.location.reload` is not
stubbable in jsdom — and the cookie's `path=/` is captured with a setter spy on
`Document.prototype.cookie`, since jsdom's getter reports only `name=value`.

**The last `node --test` suite became the third guardrail script.**
`tokens-usage.test.mjs` catches `var(--rt-typo)`, which resolves to nothing rather than
erroring. It is `scripts/check-token-usage.mjs` now, beside `check-color-tokens.mjs` and
`check-css-blocks.mjs` — a filesystem-walking repo check rather than a unit test — which
is also what let the `node --test` step come out of CI entirely. **It scans more than it
used to:** the suite walked `web/` only, so `frontend/src`'s stylesheets were never
covered. Widening it to 241 files surfaced five component-local properties at once
(`--rt-site-column-width`, `--rt-site-dates-width`, `--rt-site-matrix-viewport-width`,
`--rt-watch-editor-width`, `--rt-watch-editor-mobile-margin`). Each was checked to be
declared or assigned rather than misspelled — three are set from React inline styles in
`SiteMatrix.tsx` and read behind a fallback, which is exactly the case where a typo would
have rendered as the fallback forever — and they are allowlisted by name.

**The flat catch-all is deleted, which is a behaviour change.** Phase 5 kept it "because
the root is a real directory a deploy can drop a file into". That cuts the other way:
`staticDir` is `/app/static` in a container but the **checkout itself** on a host, where
it answered `/README.md`, `/Makefile` and `/gradlew`. Nothing needed it — every page is
explicit and `/assets`/`/data` have their own mounts — so the exposure bought nothing. Its
absence is pinned by a test, and `/preview/map` moved from 403 to 404 as a result.

**A test-only trap worth keeping in mind:** Ktor answers **403**, not 404, when a
`staticFiles` mount matches a path and its `exclude` rejects it. `/data/raw/*` is a 403;
paths with no mount at all are 404s. Asserting 404 for the former cost a CI round trip.

**Two seams still stay**, because what decides it is whether the *other* end is alive:
the `window.__rt*` shim (`SmokeTest.kt` reads five of those globals) and the `roadtrip:*`
event bridge (three React hooks call `notifyLegacyWatchesChanged`, and the bridge is what
turns that into cross-feature invalidation — nothing else invalidates the watches cache
across features). Both now say so at their definition instead of promising Phase 5 would
delete them. `escapeHtml` was the counter-example: `git log -S` finds no non-test importer
in `frontend/src` at any point, so it went, and `lib/html.ts` — left holding only phone
helpers — became `lib/phone.ts`.

## Serving (DONE)

Every page is served from the React build, and `data/` is the only other static tree
mounted. The pieces:

- **`StaticSiteRoutes.kt`** takes a `frontendDir` alongside `staticDir` and registers
  `/assets/*` (the hashed bundles — the flat catch-all deliberately refuses
  subdirectories, so without this mount a built page loads its HTML and nothing else).
  `MIGRATED_PAGES` drives both URL forms per page (`/watches` and `/watches.html`);
  add one entry per phase. Those names are also **excluded from the catch-all**, so the
  explicit route is the only thing that can serve them rather than leaving it to Ktor's
  resolution scoring.
- **Fallback is load-bearing, not defensive.** A page falls back to its legacy file when
  the built one is absent. A sandbox pulls a pre-built image but bind-mounts the
  checkout, so an unbuilt `frontend/dist` would otherwise 404 a page that works fine on
  the legacy path.
- **`frontend-dir` config** (`InfraModule`), default `frontend/dist`, resolved under
  `static-dir` when relative — so `.` on the host and `/app/static` in a container both
  work with no per-profile override.
- **Bind-mounted, not baked into the image**, exactly like `web/` already is:
  `./frontend/dist:/app/static/frontend/dist:ro` in `docker-compose.yml` and
  `docker-compose.sandbox.yml`. A rebuild therefore needs no image rebuild and no
  container restart — just a browser refresh. The Dockerfile is unchanged, which also
  keeps CI's `docker-build` job from needing Node.
- **`tilt up`** builds it: `frontend-deps` (keyed on the lockfile) → `frontend-dist`
  (keyed on sources), and `backend` waits on `frontend-dist` so the first bring-up has a
  build ready. A type error fails the resource in the Tilt UI.
- **`make frontend`** builds it standalone; `make run` (dev and prod) calls it.
- **`make test`** now runs the frontend gates — it claimed to "run everything CI runs"
  and did not.
- **Sandbox**: `scripts/deploy.sh` builds the frontend before `compose up`, guarded on
  `npm` being present and non-fatal on failure, so a host without Node serves the legacy
  site instead of failing the deploy.

**Nothing is legacy-served any more.** 4e added `index.html` to `migratedPages`, so all three
pages come from `frontend/dist`; `web/` and `data/` stay mounted for the assets a React page still
loads at runtime (`tokens.css`, the two sandbox modules) and for the GeoJSON layers. The root
page's second URL form is `/` rather than the `/index` that stripping `.html` would give —
`urlFormsOf` — and the catch-all's `default(index.html)` came off, so the explicit route is the
only thing that can answer `/`.

### Watches is decommissioned (Phase 5, for this page only)

`web/watches/` and the root `watches.html` are **deleted** — React owns the page.
Consequences worth knowing:

- **`/watches` has no fallback.** `migratedPageFile` still prefers the build and
  still falls back to a legacy file, but watches no longer has one, so an unbuilt
  `frontend/dist` means `/watches` is a 404 (not a 500 — the helper returns null
  and the route answers 404 rather than calling `respondFile` on a missing path).
- **The deploy therefore requires Node.** `scripts/deploy.sh` now *fails* if npm
  is missing or the build fails, where it used to warn and continue. Shipping a
  dead page silently is worse than a loud deploy failure.
- Removed with it: the `watches.html` compose bind-mounts, its `deploy.yml` and
  `ci.yml` path-filter entries, and its entry in the color checker's `ROOTS`
  (which `statSync`-walks and would have thrown ENOENT).
- **Kept on purpose:** `web/availability/watch-editor.js` and `web/api/watches-api.js`.
  Neither lives under `web/watches/`, and `availability-week.js` plus the vanilla
  topbar alerts panel still import them until Phases 2 and 4d.

## Gotchas / lessons (save yourself the debugging)

- **LDS form controls are uncontrolled, and this is not optional.** `value`/`checked` is the
  INITIAL value only. The stateless components render a template string, so changing the prop
  swaps the control's DOM: a controlled text input loses its caret and every keystroke after the
  first. `attrs.js` maps `defaultValue`/`defaultChecked` onto the `value`/`checked` attributes
  precisely so the uncontrolled pattern works. Seed once with `defaultValue`, let the DOM own the
  live value, mirror `onChange` into state, and make a reseed a REMOUNT via a React `key`. This
  cost real debugging in Phase 1 — typing "42" posted `poi_id: 4` — and every later phase with a
  form hits it.
- **Whose mount the snapshot belongs to is the follow-on trap.** "Seed once from a snapshot the
  parent froze" is right only for a field that lives as long as its parent. A field the parent
  *conditionally renders* — one gated on a toggle — unmounts and remounts, and the parent's
  snapshot is stale by then: the remounted input shows the old value while mirrored state, and so
  the payload, holds the new one. The user sees one thing and a different one is saved. Use
  **`SeededTextField` from `@ui`**, which snapshots at its OWN mount; reseed by remounting it.
  Review caught this on the watches trigger fields, where it could email an address other than the
  one on screen.
- **Never `Omit<…>` an LDS prop type.** LDS's `HtmlProps` ends in `[attr: string]: unknown` (any
  extra prop becomes an HTML attribute), and `Omit` over a type with an index signature collapses
  it to that signature alone — every named prop's type, including `onChange`'s parameter, silently
  degrades to `any`. Widen by intersection (`extends TextFieldProps`) and override at the call
  site instead. `@ui`'s `Table` correction predates this lesson and only survives it because
  `columns`/`rows` are the sole named props it touches.
- **Don't pass a changing `disabled` to a field you want to keep typed text in.** It changes the
  template, which swaps the DOM and resets the value. Disable the buttons instead.
- **LDS has no component that can drive tab navigation.** `Tabs` renders
  `<button class="lds-tabs__tab">` with no id, no data attribute and no `onChange` — there is no
  way to learn which tab was clicked, and it emits no URL. `SegmentedControl` *does* pass the
  selected value to `onChange`, but its own source says it is "a value picker, not navigation …
  tabs change what you are looking at; this changes a property of what you are already looking
  at." Phase 2 uses plain anchors (see `TabNav.tsx`), which is also what the vanilla page did and
  what URL-addressable tabs want: linkable, copyable, middle-clickable. Don't reach for
  `SegmentedControl` to dodge this.
- **A query key built as `key(filters ?? {})` is a LEAF, not a prefix.**
  `['dashboard','pollers',{}]` does not prefix-match `['dashboard','pollers',{active:'true'}]`, so
  invalidating the no-arg form silently refetches nothing. Keys used for invalidation need their
  own prefix entry — `queryKeys.dashboard.pollersAll()` exists for exactly this.
- **`Omit` is not the only TS trap on LDS props.** An interface cannot satisfy
  `Record<string, unknown>` (no implicit index signature) while a `type` alias can — so a filter
  bag passed to a query-key factory has to be declared with `type`, not `interface`.
- **LDS's `toggle` puts its visible label in a `<span>`**, not a `<label for>`, so `id` alone
  leaves the checkbox with no accessible name — pass `aria-label` too. Its `textField` DOES emit
  `<label for={id}>`, so there an `id` is enough (and required: without one there is no
  association at all).
- **LDS's `Table` is presentational.** No sort hooks, no per-row class, no row keys. Sorting and
  row-level styling are the consumer's job.
- **Some `@lew-ds/lds-react` types are narrower than the runtime.** `Table`'s column labels and cell
  values accept React nodes but are typed `Slot`. Corrected once in `src/ui/index.ts`; put any
  further corrections there rather than casting at call sites.
- **A `useMemo` dependency array must be a constant size.** A per-item query fan-out
  (`useQueries` over N ids) cannot be spread into deps; collapse it to one scalar.

- **LDS's `Checkbox` IS safe to drive from state — the uncontrolled rule is about the caret.**
  A repaint replaces the control's DOM, which is fatal for a text field mid-typing and harmless for
  a checkbox, so pass `checked` from the store and mirror `onChange` back. Better still: a React
  node passed as the `label` slot renders through a portal into a stable placeholder, so the
  template string does not change when only the label does — a legend row's count can tick without
  repainting its input at all.
- **Turn LDS's own knob down rather than restyling its controls.** `.lds-check` pads itself to
  `--control-xl` (44px, the touch floor). In a 50-row agency legend that is 2,200px of scrolling, so
  `legend.css` sets `--control-xl: 26px` on the list container and restores 40px under the mobile
  breakpoint. The control, its box and its focus ring stay LDS's.
- **A new bbox is a new query key, and a new key has no data yet.** Painting `query.data` straight
  through therefore blanks every overlay for the length of each round trip, and leaves it blank on a
  failed fetch — where `refreshBbox` logged and returned, keeping the pins already on screen.
  `useViewportPois` holds the last successfully fetched features and repaints from them until the
  next success. An empty *successful* response still clears the map, because that is a real answer.
- **An overlay installed on its own schedule needs an explicit insertion anchor.** MapLibre appends,
  and the vanilla paint order was implicit in one `style.load` handler installing everything in
  sequence. The state boundaries arrive when their fetch resolves — usually after the pins — so they
  pass `firstInstalledPinLayerId(map)` as `beforeId` or they draw over every dot.
- **The install effect must be able to see the newest data, and effects run in declaration order.**
  `useMapOverlays` keeps the current buckets in a ref synced by an effect declared BEFORE the
  install effect. Without that, a basemap change installs empty layers and the paint effect fills
  them one frame later — a visible flash of a map with no pins, which is exactly what the vanilla
  reinstall-from-cache avoided.
- **MapLibre throws on a missing layer id**, and between a basemap change and the reinstall there
  are no app layers at all. `setLayoutProperty`, `setFilter` and `queryRenderedFeatures` are all
  guarded by `getLayer` (`installedHitLayerIds` exists for the third one). A guard here is not
  defensive padding; the window is real and it is hit on every basemap switch.
- **Do not trust a Phase-0 store shape until something consumes it.** `mapStore`'s filter fields
  were designed before the legend existed and could not express its behaviour (all-on with per-row
  opt-out); `mapReady` was declared and never written. Both were corrected in 4b. Check the rest of
  the stores against their first real consumer rather than assuming they fit.
- **An app-shell page must escape `app/shell.css`'s `body` padding.** That padding exists for the
  document-flow pages; a full-bleed surface inside it renders inset and overflows. `.rt-map-shell`
  uses `position: fixed; inset: 0`.
- **A dispatch table keyed on a value something else rewrites is a bug waiting for its first
  real payload.** `flattenHydratedPoi` replaces a campground's `category` with its
  `subcategory` — faithful to `core.js`, and pinned there byte for byte — and the drawer
  registry looked up `category`. Result: nearly every campground opened "No detail view for
  this place yet", with a green test suite, because every drawer test used a park (parks are
  not rewritten). If a normaliser rewrites a field, nothing downstream may key on it without
  saying so at the lookup.
- **The vanilla's ids are not always its contract.** `#cg-drawer.open`, `#panel` and
  `#cg-agency-legend input[data-cg-agency]` were hand-built DOM hooks; the ports have real
  roles and accessible names, and `SmokeTest.kt` asks for those instead. The topbar is the
  exception — its `tb-*` ids were preserved on purpose, and that is what let `/` graduate
  without touching a single topbar assertion.
- **An LDS checkbox cannot be clicked through its input.** `.lds-check input` is
  `opacity: 0; width: 0; height: 0; pointer-events: none` — Playwright's `check()` times out
  with "element is not visible". Click the label; read state from the input. (Testing Library
  is unaffected: `userEvent.click` on the label, or `fireEvent` on the input, both work.)
- **In a browser test suite, the URL is shared state like a store.** `MapView.test.tsx`'s
  route-mode tests left `?route=` behind (the topbar writes it), so the NEXT mount restored
  that trip, `useRoute` fetched it, and whether an assertion saw a route came down to
  microtask timing — the suite's only flake, for two phases. Reset `window.history` in
  `beforeEach` alongside `reset()` on every store.
- **Playwright's JS binding evaluates a string as an EXPRESSION.** `waitForFunction('() => x')`
  polls a *function object*, which is truthy, so it resolves immediately and the assertion
  never runs. Java has no function literals, so the Kotlin smoke's string form is the
  supported one there — but a JS harness mirroring it must pass real functions. Half a
  verification pass silently "passed" this way before it was noticed.
- **Vitest must be v3 for Vite 6.** Vitest 2.x depends on Vite 5 and pulls a *nested* copy,
  causing a `Plugin` type clash with `@vitejs/plugin-react`. Use Vitest 3 and import
  `defineConfig` from `vitest/config` (not `vite`).
- **`tokens.css` and the sandbox chrome are intentionally not bundled**, and are injected
  by the `runtimeServedAssets` Vite plugin (`frontend/vite/runtime-served-assets.ts`)
  rather than written into each shell. Ktor serves them from the legacy tree at runtime,
  so the backend (or the dev proxy) has to be up for styles to load.

  A plugin because the tags cannot live in the HTML: Vite treats
  `<script type="module" src>` in an entry as a build input and **fails** the build on
  `/web/sandbox-banner.js` with "Failed to resolve … from watches.html", since it is
  outside the Vite root. (A `<link>` is only warned about — that is where the old
  "…doesn't exist at build time, it will remain unchanged to be resolved at runtime"
  warning came from. It is gone; the build is clean now, so a *new* warning is worth
  reading rather than assuming it is this one.) Injecting with `order: 'post'` runs after
  Vite's own HTML transform, which is what leaves the references untouched.

  **Every migrated shell needs the sandbox chrome.** An auth-disabled sandbox 401s every
  API call until an `rt_session=sandbox:<id>` cookie is picked, and the user switcher is
  the only page-local way to pick one — a page without it just looks signed-out, which is
  indistinguishable from a real auth failure. The migrated watches page shipped without it
  once. `vite/runtime-served-assets.test.ts` pins the tag set so a later phase cannot drop
  it silently.
- **GateGuard fact-forcing hook is ON** in this environment: every file create/edit and first
  Bash call demands a "facts, then retry" cycle (and denies the first attempt). Batch writes;
  present importers/purpose/instruction, then retry. (Disable path if ever wanted:
  `ECC_GATEGUARD=off` or `ECC_DISABLED_HOOKS=pre:edit-write:gateguard-fact-force,pre:bash:gateguard-fact-force`.)
- **`@lew-ds/lds` 1.1.0 removes `css/themes/roadtrip`.** Keep the direct dependency pinned to
  1.0.0 while that import remains; `@lew-ds/lds-react` otherwise permits npm to select 1.1.x.
- **`@lew-ds/lds-react` ships untranspiled `.jsx`** (`main: ./src/index.jsx`). This can fail at
  *bundle* time even when typecheck succeeds, which is why CI runs `npm run build` and not just
  `tsc`.
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
