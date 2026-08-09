# Frontend Component Architecture

The frontend is **React 18 + TypeScript**, built by Vite, and lives entirely in
`frontend/`. There is no second tree: the vanilla `web/` modules this replaced were
deleted in Phase 5 of the migration (see
[docs/react-migration-plan.md](react-migration-plan.md), which remains the record of
why each piece looks the way it does).

## Before you build: check LDS

Components come from **LDS** (`matthewlew/lds`, the "Lew Design System"), imported
through the local `@ui` adapter. **Check it first** — if a primitive covers what you
need, compose it rather than writing page-specific markup or CSS.

```sh
npm --prefix frontend run dev     # :5173, proxies /api,/auth,/data to Ktor on :8765
```

LDS is vendored at `frontend/vendor/{lds,lds-react,open-icons}` and consumed as
`vendor/*` npm workspaces, because npm cannot install a single workspace member of a
git monorepo and `@lew/lds*` is not published. `frontend/vendor/lds-react/src/index.d.ts`
is the full API surface; `frontend/vendor/lds/README.md` documents each component's
anatomy.

**Everything is imported from `@ui`, never from `@lew/lds-react` directly.** That
keeps a swap to a published registry dependency a change to `src/ui/index.ts` and
`package.json` only, with no call-site churn.

## Component pattern

Function components with hooks. The conventions that matter:

- **Server state is TanStack Query**, never `useState` + `useEffect`. A fetch keyed
  by its inputs is how staleness is handled: a superseded request has no observer to
  reach, which is why the ported code has no request-sequence counters or
  `AbortController` bookkeeping (`useViewportPois.ts` documents the mapping).
- **Client state is Zustand** (`src/stores/{authStore,tripStore,mapStore}.ts`).
  Imperative handles — the MapLibre instance, Markers, per-layer FeatureCollections —
  deliberately do NOT go in a store; each store documents its exclusions.
- **State scoped to a prop that can change under the component wants a `key`, not a
  reset effect.** A reset effect has to be extended for every new piece of state;
  `AvailabilityWeek` had seven and got one wrong. Remount instead.
- **Renders have no side effects.** No `console.warn`, no `setState` in another
  setter's updater — StrictMode double-invokes updaters, and both setters batch
  identically outside one.

## File structure

```
frontend/src/
  app/            mount.tsx, AppProviders.tsx, shell.css, sandbox/  # page frame
  pages/<page>/   main.tsx + the page component                      # one per HTML entry
  features/<f>/   components, hooks, and pure logic for one feature
  lib/            pure, typed, tested helpers with no React in them
  api/            one thin client per backend surface
  queries/        QueryClient, the key table, the event bridge
  stores/         Zustand stores
  map/            imperative MapLibre internals React effects drive
  tokens/         tokens.css (the colour source of truth) + its JS bridge
  ui/             the @ui adapter over LDS, plus local additions
```

**Pure logic lives beside its components, not inside them.** Every feature splits the
same way: the rules are exported functions with their own tests
(`matrix-rows.ts`, `stops.ts`, `alert-rows.ts`, `route-summary.ts`) and the components
only draw. That split is what makes a reviewer able to check behaviour without
rendering anything.

## Two layers

### Design-system primitives (`@ui`)

Generic, reusable across any page: `Button`, `Modal`, `TextField`, `Table`, `Tabs`,
`Toggle`, `Banner`, `Select`, `Card`, `Menu`, `Tooltip`, `Checkbox`, `Chip`, `Icon`,
`EmptyState`, `Skeleton`, … plus `ToastProvider`/`useToast()`.

`src/ui/` also holds **local additions** — components that exist to make an LDS
constraint safe to use rather than to restyle anything: `SeededTextField`,
`ConfirmButton`, `SecretField`. Type corrections to LDS props belong there too, not
as casts at call sites.

### Domain components (`features/<feature>/`)

Compose primitives into feature-specific UI. They import primitives from `@ui`,
clients from `@/api`, and helpers from `@/lib` — **never from another feature
directory**. Something two features need moves to `lib/` or `ui/`
(`lib/watch-format.ts` came out of `WatchTable.tsx` the moment the alerts panel
needed the same strings).

### Forms on LDS: the controls are uncontrolled

Non-negotiable, and the migration plan's Gotchas section has the full detail. The
short version for anyone adding a React form:

- Seed a field **once** with `defaultValue`/`defaultChecked`, let the DOM own the live
  value, mirror `onChange` into state for the payload, and make a reseed a **remount**
  via a React `key`. Passing changing state back in swaps the control's DOM and eats
  the caret.
- For a field that is **conditionally rendered** — gated on a toggle, say — use
  **`SeededTextField`** from `@ui` instead of `defaultValue`. It snapshots at its own
  mount, so a field that unmounts and comes back shows the current value rather than
  the one from when the form opened. Seeding those from the parent's snapshot displays
  one value and submits another.
- Disable **buttons**, not fields, while a save is in flight. `disabled` changes the
  template, which swaps the DOM and discards what was typed.
- `Checkbox` **is** safe to drive from state — the rule above is about the caret, and
  a repaint is harmless for a checkbox.

## Page shells

A page's `*.html` is a bare shell: `<html class="theme-roadtrip mode-dark">`, the
meta tags, `<div id="root">`, and its entry module. **Nothing else belongs in it.**

- Styles come from `@ui/styles.css`, imported by each `pages/*/main.tsx`: the LDS
  cascade, the roadtrip theme, then `src/tokens/tokens.css`.
- The **sandbox chrome** (build banner + assume-user switcher) is started by
  `mountPage()` in `src/app/mount.tsx`, so every page has it structurally. This is
  load-bearing for review, not decoration: an auth-disabled sandbox 401s every API
  call until an `rt_session=sandbox:<id>` cookie is picked, and the switcher is the
  only page-local way to pick one — a page without it looks signed-out, which is
  indistinguishable from a real auth failure. `src/app/mount.test.tsx` pins it.

Adding a page means an HTML entry, a `rollupOptions.input` entry in `vite.config.ts`,
and an entry in `pages` in `StaticSiteRoutes.kt` (which registers both `/name` and
`/name.html` from it).

## Color: tokens only

`frontend/src/tokens/tokens.css` is the only place a raw **hex** may appear.
`node scripts/check-color-tokens.mjs` fails the build on one anywhere else, and it
runs in `make test` and in CI's frontend-tests job. It scans `.css`, `.html`, `.ts`
and `.tsx`.

Functional notation — `rgba()`, `hsl()` — is **ratcheted, not banned**. One occurrence
remains (the sandbox switcher's own translucent ground, which has no role to map
onto); the checker holds a per-file high-water mark, so new raw `rgba()` fails the
build and existing debt can only shrink. Compose new ones from a channel primitive:
`rgba(var(--rt-c-overlay-rgb), 0.06)`.

It is two tiers. Primitives (`--rt-c-*`) hold the raw values and are named for what
they *are* (`--rt-c-blue-500`). Semantic roles (`--rt-brand`, `--rt-surface`,
`--rt-avail`) alias a primitive and are named for what they *do*. **Components use
semantic roles, never primitives** — a primitive at a call site is the same drift as a
hex, one indirection later.

Need a color no role covers? Add the role to `tokens.css` rather than reaching for a
primitive or a literal.

### Two token vocabularies, on purpose

LDS's own cascade defines `--c-*` / `--grey-*` / `--surface-*`; ours defines `--rt-*`.
Both are loaded and they are disjoint — the vendored `theme-roadtrip` was generated
*from* our tokens and only names them in comments. App CSS composes from `--rt-*`,
which is the set the checker validates. Reconciling the two (a thin alias layer, or
repointing the JS bridge at LDS's names) is still outstanding.

### From JS

MapLibre paint properties, canvas charts and inline style strings can't resolve
`var()`. They go through the bridge, which reads the live computed value off the
document root — so `tokens.css` stays the single source and a theme reaches the map
too:

```ts
import { token, seriesColor } from '@tokens';

paint: { 'circle-color': token('--rt-layer-np') }
```

`@tokens` is `src/tokens/tokens.ts`. It carries a fallback table for early boot and
for jsdom tests, where no stylesheet has loaded — **so assert against `token('--rt-…')`
in tests rather than a literal hex**, which would also trip the checker. The checker
verifies every fallback key names a token `tokens.css` actually defines, so a rename
fails loudly instead of pinning a stale value at runtime.

One exception, enforced by name in the checker rather than by convention:
`<meta name="theme-color">` is read by browser chrome before any stylesheet loads. It
names the token it mirrors.

### Theming

Because every role resolves through a primitive, a theme is an override block —
redefine `--rt-c-*` under a scope like `[data-rt-theme="light"]`, plus only the roles
that genuinely diverge. Custom properties inherit downward only, so the scope
attribute belongs on `<html>`. Call `resetTokenCache()` from `@tokens` after a runtime
swap so the map and charts re-resolve.

## CSS rules

- One stylesheet per feature (`features/<f>/<f>.css`), imported by the component that
  owns it. Vite bundles them; nothing injects a `<link>` at runtime.
- All custom properties come from `src/tokens/tokens.css` (`--rt-*` prefix).
- **Do not nest selectors.** `scripts/check-css-blocks.mjs` rejects a selector
  appearing inside a rule body, because that is the signature of a rule that lost its
  closing brace — which is still valid CSS and therefore silent. It cost a 240px
  popover rendering 1061px wide.
- **Turn LDS's own knobs down rather than restyling its controls.** `.lds-check` pads
  itself to `--control-xl` (44px, the touch floor); `legend.css` sets
  `--control-xl: 26px` on its list container and restores 40px under the mobile
  breakpoint. The control, its box and its focus ring stay LDS's.
- **jsdom does no layout**, so no unit test can catch a layout bug. Anything about
  size, overflow or position needs a browser — `make qa` (see [SMOKE.md](../SMOKE.md))
  or headless Chromium against `frontend/dist`. Three phases of the migration in a row
  had their worst defect found that way and not by tests.

## Escaping

React escapes what it renders, so the vanilla tree's `escapeHtml`-at-every-
interpolation discipline is gone. The **only** `dangerouslySetInnerHTML` in the tree
is `ProviderHtml` in `features/drawer/parts.tsx`, fed solely by
`lib/upstream-html.ts`'s whitelist sanitiser. Do not add a second one.

## The map: an imperative escape hatch

Layers are **not** expressed as JSX. `src/map/` is a plain-TS module — the overlay
registry, pin bucketing, the route/corridor overlay, markers, the viewport request
shaping and containment cache — and `<MapProvider>` owns the MapLibre instance in a
ref. React effects call install functions and subscribe map events into `mapStore`.

Two rules that bite here: MapLibre **throws on a missing layer id**, and between a
basemap change and the reinstall there are no app layers at all, so
`setLayoutProperty`/`setFilter`/`queryRenderedFeatures` are all guarded by `getLayer`;
and an overlay installed on its own schedule needs an explicit `beforeId` anchor,
because MapLibre appends and the state boundaries arrive after the pins.

## Tests

Vitest 3 + jsdom + React Testing Library, colocated with the module
(`thing.ts` → `thing.test.ts`). All four gates must be green before every commit:

```sh
npm --prefix frontend run typecheck   # tsc --noEmit
npm --prefix frontend run test        # vitest run
npm --prefix frontend run build       # the only thing that exercises bundling
node scripts/check-color-tokens.mjs
node scripts/check-css-blocks.mjs
```

`npm run build` is not redundant with the typecheck: the vendored LDS ships
untranspiled `.jsx`, which fails at bundle time only.

Two traps worth knowing. **An LDS checkbox cannot be clicked through its input**
(`opacity: 0; width: 0; pointer-events: none`) — click the label, read state from the
input. And **in a browser test suite the URL is shared state like a store**: reset
`window.history` in `beforeEach` alongside `reset()` on every store, or a test that
leaves `?route=` behind changes what the next mount restores.
