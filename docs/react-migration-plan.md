# Frontend Migration: vanilla JS → React + TypeScript

> **Completed migration record.** The migration finished in August 2026. This document records
> the resulting architecture, verification workflow, durable decisions, and lessons that still
> apply. It is no longer an execution checklist.

## Current state

The browser application is entirely React + TypeScript under `frontend/`. The former `web/`
tree, root vanilla shells, dual-serving fallback, transition aliases, and runtime-served asset
plugin are gone.

Four Vite entries are built and served by Ktor:

| URL | Entry | Purpose |
|---|---|---|
| `/` | `frontend/index.html` | Map, search, trip planner, POI drawers, and account UI |
| `/availability` | `frontend/availability.html` | Availability administration dashboard |
| `/watches` | `frontend/watches.html` | Watch management |
| `/gallery` | `frontend/gallery.html` | Production LDS and Roadtrip component gallery |

There are no `window.__rt*` APIs. Browser smoke tests exercise public UI behavior and accessible
DOM instead of reaching into Zustand or MapLibre internals.

## Verification

Run these before committing frontend changes:

```bash
cd frontend
npm ci
npm run typecheck
npm run lint
npm run test
npm run build
cd ..
node scripts/check-color-tokens.mjs
node scripts/check-token-usage.mjs
node scripts/check-css-blocks.mjs
```

`make qa` runs the Playwright-backed Kotlin smoke suite against a live stack when
`QA_BASE_URL` is set. It needs the backend and database; unit tests and the Vite build do not.

## Resulting architecture

```text
frontend/
  index.html
  availability.html
  watches.html
  gallery.html
  src/
    api/                         typed same-origin clients
    app/                         providers, page mount, sandbox chrome
    domain/                      shared business rules and domain UI
    features/                    feature-owned components and hooks
    lib/                         pure shared helpers
    map/                         imperative MapLibre sources, layers, and controls
    pages/                       page composition and entry modules
    queries/                     TanStack Query client and keys
    stores/                      Zustand client state
    tokens/                      Roadtrip theme, semantic tokens, JS token bridge
    ui/                          @ui adapter and local primitives
```

The dependency direction and component placement rules live in
[`docs/frontend-components.md`](frontend-components.md). That document is authoritative for new
frontend work.

## Durable decisions

- React 19.2 and TypeScript, built by Vite as a multi-page application.
- TanStack Query owns server state and invalidation.
- Zustand owns cross-surface client state.
- Components come from `@lew-ds/lds-react` through the local `@ui` adapter.
- `@ui/styles.css` loads LDS, the Roadtrip Zion theme, semantic `--rt-*` tokens, and the bridge
  that maps Roadtrip roles onto the current theme.
- MapLibre remains imperative. `MapProvider` owns the map instance; effects drive modules in
  `src/map/`. Layers and markers are not expressed as JSX.
- Ktor serves only declared HTML entries, Vite assets, and the `data/` tree. An unbuilt frontend
  returns 404 instead of falling back to vanilla files.
- The component gallery is a production Vite entry, not Storybook, so it always renders through
  the same dependency versions, providers, theme, and CSS cascade as the application.

## Page composition

The map page composes otherwise independent features at `pages/map/MapPage.tsx`:

- `MapProvider` owns MapLibre creation and style lifecycle.
- `TopBar` owns search and trip planning.
- `MapView` owns viewport fetching, overlays, filters, basemaps, and map controls.
- `PoiDrawer` owns selected-POI detail presentation.
- `AvailabilityWeek` is injected into the campground drawer by the page boundary.
- `AuthRow` mounts account/settings and `AlertsPanel` provides watch alerts.

On desktop, the topbar intentionally stacks above an open drawer. Search and trip navigation are
the app's primary navigation surface and must remain reachable while a detail panel is open.
Mobile keeps the modal bottom-sheet behavior.

## Token and theme strategy

`frontend/src/tokens/tokens.css` defines Roadtrip semantic roles. Components use semantic
`--rt-*` roles, not raw colors or palette primitives. `roadtrip-zion.css` defines the LDS theme,
and `roadtrip-zion-bridge.css` maps Roadtrip chrome roles onto that theme.

MapLibre paint and Chart.js cannot resolve CSS `var()` values. They use `token()` through the
`@tokens` alias, which reads the computed value and has a checked fallback table for tests and
early boot.

The guardrails are:

- `check-color-tokens.mjs` rejects new raw colors outside approved token sources.
- `check-token-usage.mjs` rejects missing or malformed `--rt-*` references.
- `check-css-blocks.mjs` rejects structurally unbalanced CSS.

## Browser testing boundary

Vitest and Testing Library cover component behavior and the fake MapLibre recorder covers
imperative map calls. The Kotlin smoke suite covers facts jsdom cannot establish: pages mount,
the map has dimensions, real search opens drawers, mobile panels operate, route mode works, and
desktop stacking keeps search reachable.

The smoke suite must not introduce a production global solely to inspect application state.
Prefer, in order:

1. visible UI and accessible roles;
2. observed URL or network behavior;
3. a real user-facing control that causes the required map transition;
4. a focused unit test over the imperative map adapter when the fact is not user-observable.

## Migration history

| Phase | Outcome |
|---|---|
| 0 | Vite/React/TypeScript/Vitest foundation; typed clients and helpers; Query, Zustand, LDS |
| 1 | Watches page migrated and legacy watch page deleted |
| 2 | Availability dashboard migrated; Chart.js moved from CDN to npm |
| 3 | Account/settings components migrated, including write-only secrets |
| 4a | Map provider, basemap registry, and style lifecycle |
| 4b | Imperative overlays, viewport fetch loop, legend, and filters |
| 4c | Typed POI drawers and guarded detail hydration |
| 4d | Campground availability tree, watches, calendars, matrices, and popovers |
| 4e | Search/trip planner, routes, corridor results, alerts, auth, map controls, shared links |
| 5 | Vanilla application and dual-serving infrastructure deleted |
| Follow-up | Tokens and sandbox chrome moved into `frontend/`; `/web` mount removed |
| Cleanup | `window.__rt*` QA globals removed; smoke coverage moved to public behavior; gallery added |

## Lessons that still apply

### LDS controls are uncontrolled

Seed text controls once with `defaultValue`, let the DOM own the live value, and mirror changes
for submission. Passing a changing `value` causes LDS to replace the real input and can eat the
caret. Use `SeededTextField` for conditionally mounted fields and remount deliberately to reseed.

Checkboxes can be state-driven because replacing a checkbox does not destroy typed text. In a
browser, click the LDS label rather than its visually hidden input.

### Do not `Omit` LDS prop types casually

LDS `HtmlProps` has an index signature. `Omit` can collapse named props and silently degrade
callbacks to `any`. Widen by intersection and keep declaration corrections in `src/ui/index.ts`.

### Query invalidation needs prefix keys

A key such as `['dashboard', 'pollers', {}]` is a leaf, not the prefix for filtered variants.
Define an explicit `*All()` key for invalidation.

### MapLibre style reloads erase application layers

Every custom source, layer, filter, handler, and insertion anchor must tolerate the window between
`setStyle` and reinstall. Guard missing layer IDs and repaint from the last successful data while
the next viewport request is pending.

### Overlay order must be explicit

Async layers do not arrive in a stable order. Use an explicit `beforeId` anchor so state lines,
routes, and pins retain their intended visual order.

### Normalize before dispatching

POI flattening can rewrite fields such as `category`. Registries must dispatch on a stable,
documented discriminator rather than a field an upstream normalizer mutates.

### Reset browser URL state between tests

The route parameter is shared state. Reset `window.history` alongside stores or a prior test's
`?route=` can restore a trip during the next mount.

### Build verification is separate from typechecking

LDS and MapLibre packaging issues can pass `tsc` and fail Rollup. CI and local verification run
both `typecheck` and `build`.

## Historical PRs

The principal migration landed through PRs #568–#580, with Phase 4d follow-up defects corrected
in #577. Git history remains the detailed source for individual implementation choices; this file
describes the supported result rather than preserving obsolete branch handoffs.
