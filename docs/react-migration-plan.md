# Frontend Migration: vanilla JS → React + TypeScript

> **Handoff doc.** Status as of 2026-08-09. This is the source of truth for the
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
- **Phase 4b is COMPLETE and NOT served** (this branch): the imperative overlay module, the
  viewport POI fetch loop, and the legend/filter panel. `/` still resolves to the vanilla map, so
  users see no change; reach the React map with `npm run dev`. Read "Phase 4b — what landed" for
  the two scoping corrections it makes (the panel's search box is dead code; park layers are
  deliberately absent).
- **Browser coverage is partial.** `SmokeTest.kt` now loads `/watches` and `/availability` and
  asserts each renders (#572), so those two are covered. The React map page cannot be smoke-tested
  yet — Ktor does not serve it — so **everything in 4a/4b is verified by unit tests and by hand,
  not by CI in a browser.**

### Resume quickstart
```bash
cd frontend
npm ci              # vendor/* are workspaces — nothing is fetched for LDS
npm run typecheck   # tsc --noEmit — must be clean
npm run test        # vitest run — currently 701 tests green
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
| LDS consumption | **Vendor into the repo now**, switch to a registry dep once `@lew/lds*` is published (swap behind `@ui`; still unpublished as of 2026-08-08) |
| Rollout | **Strangler, page-by-page**: watches → availability → account → map app |
| Tests | **Vitest 3** + jsdom + React Testing Library (ports the `node --test` `*.test.mjs` suites) |

## LDS findings (inspected 2026-08-07)

`matthewlew/lds` is a **public** monorepo — the "Lew Design System".
- **`@lew/lds`** — 28 framework-free components (`(props) => htmlString`); five stateful ones
  ship `mountX(el, config) → { update, dispose }` controllers (same contract as our current
  `web/design-system`). One-token CSS cascade, four themes × light/dark. Ships `.d.ts` types.
  Exports: `@lew/lds` (templates+controllers), `@lew/lds/templates`, `@lew/lds/controllers`,
  `@lew/lds/css`, `@lew/lds/css/themes/roadtrip`, etc.
- **`@lew/lds-react`** — now on **`main`** (merged upstream in PR #7, `214e442`); still NOT
  published to npm. A complete, typed React binding: every template has a PascalCase
  component (`Button`, `Modal`,
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
# Clone to inspect (public repo). All three packages are on main now:
git clone https://github.com/matthewlew/lds /tmp/lds
cd /tmp/lds
cat packages/lds-react/src/index.d.ts     # full React API surface
```
**Vendored revision: `2bdcf68`** (`frontend/vendor/{lds,lds-react,open-icons}`). To check for
upstream drift:
```bash
git diff 2bdcf68 main -- packages/          # upstream changes since we vendored
for p in lds lds-react open-icons; do       # or byte-compare against the vendored copy
  git archive main packages/$p | tar -x -C /tmp/cmp-$p --strip-components=2
  diff -rq /tmp/cmp-$p frontend/vendor/$p
done
```
**Why still vendored, now that it's on `main`:** npm cannot install a single workspace member of
a git monorepo, and the packages depend on each other by exact version. A git dep therefore
still does not work — publication to npm is the unblocker, and `@lew/lds*` is not published
(checked 2026-08-08, all three 404). Swapping to a registry dep means dropping
`"workspaces": ["vendor/*"]` from `frontend/package.json` and changing the specifiers; `@ui`
means no call site moves.

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
    ui/index.ts  ui/styles.css                 # @ui → @lew/lds-react; LDS css + roadtrip theme
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

**Phase 2 — Availability admin dashboard.** *(COMPLETE)* Tabs (pollers/runs/changes) rebuilt on
LDS + TanStack Query; Chart.js from npm, tree-shaken, replacing two CDN `<script>` tags; URL
contract (`?tab=…` plus per-tab params) preserved. `availability.html`, `web/availability.js` and
`web/components/availability/*` are deleted, and the hand-written `get("/availability")` route
went with them — `migratedPages` generates both URL forms.

**Scope note, because the file tree misleads.** `wc -l web/availability*` reports ~4,100 lines,
but only ~640 of those were this phase: the tab router (51) and the three tab modules (585).
The big files under `web/availability/` — `availability-week.js` (1,226), `site-matrix.js` (627),
`watch-editor.js` (417), `site-detail.js` (338) — are the MAP DRAWER's availability UI, reached
from `index.html`, and belong to Phase 4d. They are untouched and must stay until then.

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
`lds-react-adapter` branch (since merged to `main`), so all three packages are vendored from
one tree at `2bdcf68` into
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

**Not in 4b, and not owned by anything yet:** the `NavigationControl`/`GeolocateControl` pair and
the custom user-location puck from `web/app.js` (the store has `userLocation` with no writer), and
`core.js`'s single-popup `openPopup` helper. Their consumers are the drawer (4c) and the topbar's
proximity search (4e), so they should land with whichever gets there first.

### Seeing it

`/` still serves the vanilla map, so the React map has its own URL where preview pages are
switched on:

| Where | URL |
|---|---|
| Sandbox (`ROADTRIP_SANDBOX_PREVIEW_PAGES=true`, set in `docker-compose.sandbox.yml`) | `/preview/map` |
| Dev server | `npm run dev` → `http://localhost:5173/` (with the backend up for `/api` and `tokens.css`) |

The preview URL is a **second** page, not a replacement, and that is the point: the vanilla map
is still the only one with a drawer and a topbar, and it is what QA of everything else on a
sandbox runs against. `previewPages` in `StaticSiteRoutes.kt` is the list; a page graduates by
moving to `migratedPages`, and the flag goes away with the last one. Production never has it on.

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

## Phase 4c — in progress (branch `claude/react-migration-4c-drawer`, no PR yet)

**4b is merged** (#573, squash-merged as `4c061332`). 4c is branched off master and is
**three of four drawer types in**. Read this before resuming — it is written so a
fresh session needs nothing else.

**What is done, and where:**

| Piece | File |
|---|---|
| Hydration by id | `features/drawer/usePoiDetail.ts` |
| Shell: sheet / panel, snap states, drag-dismiss | `Drawer.tsx`, `useDrawerDrag.ts`, `drawer.css` |
| Shared header / subline / distance / directions / pills / upstream / call buttons | `parts.tsx` |
| `?poi=<id>` deep link | `poi-url.ts` |
| Category dispatch | `registry.ts` |
| Composition (loading, error+retry, unknown category) | `PoiDrawer.tsx`, rendered from `MapView` |
| Park (both kinds), Planet Fitness, Supercharger | `ParkDrawer.tsx`, `PlanetFitnessDrawer.tsx`, `SuperchargerDrawer.tsx` + `supercharger-detail.ts` |

**What is left: the campground drawer.** `web/drawer/campground.js` (275 lines) plus
`web/campground-card.js` (589), and it is the only type whose port has a hole in it
by design — `campground.js:48` imports `mountAvailabilityWeek` from
`web/availability/availability-week.js`, and that 1,226-line component is **4d, not
4c**. So the 4c campground drawer ships everything except the 7-day availability grid
and its day-detail panel, with the seam documented at the call site. The card's other
sections (parent park name, rating/reviews, amenities, cell coverage, season verdict,
CTAs, structured details, last-verified and booking-system footers) are 4c.

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

**Verified so far:** typecheck clean, 746 tests green, build ok, colour check ok.
Nothing in 4c has been driven in a browser yet — the drawer has never been opened
against real data, only in jsdom. The dev-server + headless-Chromium harness used for
4b is the way to do it (`npm run dev`, then Playwright with `/api/pois` and
`/api/pois/{id}` stubbed), and `__rtState.selectedPoiId` from `useQaHooks` is how to
confirm a click landed.

## Serving (DONE)

Migrated pages are served from the React build; everything else still resolves from
`web/`. The pieces:

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

Still legacy-served: `/` (map, Phase 4) and `/availability` (Phase 2).

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
- **Some `@lew/lds-react` types are narrower than the runtime.** `Table`'s column labels and cell
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
