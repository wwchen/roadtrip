# Frontend Component Architecture

The frontend is **React + TypeScript in `frontend/`**, built by Vite, with components
from the npm-published LDS design system behind `@ui`. **`web/` is gone entirely** — Phase 5
deleted the vanilla app, and the three files it left behind (the colour tokens, their JS
bridge, and the sandbox chrome) have since moved into this tree.

Read [docs/react-migration-plan.md](react-migration-plan.md) alongside this. That doc is
the long form — how each surface was ported, and a Gotchas section that is the accumulated
cost of getting LDS, MapLibre and TanStack Query wrong at least once each. This doc is the
short version: where a component goes and what it may import.

## Before you build: check `@ui`

`frontend/src/ui/index.ts` re-exports every LDS component the app uses, plus the few
corrections and local additions (`SeededTextField`, `ConfirmButton`, `SecretField`). If a
component covers what you need, compose it rather than writing page-specific markup or
CSS. The installed source is under `frontend/node_modules/@lew-ds/` — read it when a prop's
behaviour is unclear, because the types are occasionally narrower than the runtime.

The living component catalog is `/gallery`, implemented in
`frontend/src/pages/gallery/GalleryPage.tsx`. It renders through the production `@ui`
boundary, providers, theme, and CSS cascade. Add a representative state there when a new
shared primitive or reusable Roadtrip UI pattern is introduced.

## Two layers

### Primitives (`@ui`)

Generic, reusable across any page: `Button`, `Banner`, `Table`, `Modal`, `Checkbox`,
`TextField`, `Toggle`, `Tabs`, `SegmentedControl`, and the three local ones above. They
know nothing about watches, POIs or trips — they take data and callbacks.

Never `Omit<…>` an LDS prop type: `HtmlProps` ends in an index signature, and `Omit` over
one collapses every named prop to `any`. Widen by intersection instead.

### Domain components (`frontend/src/features/<feature>/`)

Compose primitives into feature-specific UI — `features/watches/WatchTable.tsx`,
`features/trip/TopBar.tsx`, `features/drawer/CampgroundDrawer.tsx`. They import
primitives from `@ui`, clients from `@/api`, stores from `@/stores`, and **never from
another feature directory**. A thing two features need moves to `@/lib`, `@/ui` or a
store.

`frontend/src/map/` is the exception to "components all the way down": MapLibre owns its
own DOM and layer list, so those modules are imperative functions that take a map and act
on it. React effects in `features/map/` drive them. Do not try to express a layer or a
marker as JSX.

Reusable business rules and domain-specific components that genuinely serve more than
one feature live under `frontend/src/domain/<domain>/`. Unlike `@ui`, they may know what
a watch or trip stop is; unlike a feature, they do not own a page surface. Pages compose
features, features and pages may import domains, and domains never import features.

`npm run lint` enforces the no-cross-feature rule for production source. Tests may
compose multiple features to exercise a page, but production composition belongs in
`pages/`.

## File structure

| Path | Holds |
|---|---|
| `frontend/src/features/<feature>/` | components, hooks and pure logic for one surface |
| `frontend/src/domain/<domain>/` | business rules and domain UI shared by multiple features |
| `frontend/src/map/` | imperative MapLibre modules (layers, markers, overlays) |
| `frontend/src/api/` | one typed client per endpoint group |
| `frontend/src/queries/` | query keys and cache invalidation |
| `frontend/src/stores/` | Zustand stores for cross-surface UI state |
| `frontend/src/lib/` | pure helpers shared across features |
| `frontend/src/ui/` | the `@ui` barrel over npm-published LDS |

Pure logic belongs in its own module beside the component that uses it — the ordering
rules, the copy, the state machine — because that is the half worth testing directly.
`features/availability/matrix-rows.ts` next to `SiteMatrix.tsx` is the pattern.

## Forms on LDS: the controls are uncontrolled

Non-negotiable, and the plan's Gotchas section has the full detail. The short version:

- Seed a field **once** with `defaultValue`/`defaultChecked`, let the DOM own the live
  value, mirror `onChange` into state for the payload, and make a reseed a **remount**
  via a React `key`. Passing changing state back in swaps the control's DOM and eats the
  caret.
- For a field that is **conditionally rendered** — gated on a toggle, say — use
  **`SeededTextField`** from `@ui` instead of `defaultValue`. It snapshots at its own
  mount, so a field that unmounts and comes back shows the current value rather than the
  one from when the form opened. Seeding those from the parent's snapshot displays one
  value and submits another.
- Disable **buttons**, not fields, while a save is in flight. `disabled` changes the
  template, which swaps the DOM and discards what was typed.
- An LDS checkbox's real `<input>` is `opacity: 0` with no size and `pointer-events:
  none`. Read state from it; click the **label**. Browser drivers time out on the input.
- An LDS toggle's visible `label` is a sibling of the switch, not the checkbox's
  accessible label. Always pass a matching `aria-label` to `Toggle`.

## Page shells

A page's `*.html` is a bare shell: `#root` plus its entry module. **Nothing else belongs
in it.**

- Styles come from `@ui/styles.css`, imported by each `pages/*/main.tsx`: the LDS
  cascade, the roadtrip theme, then `src/tokens/tokens.css`.
- The **sandbox chrome** (build banner + assume-user switcher) is started by
  `mountPage()`, so every page has it structurally. Load-bearing for review, not
  decoration: an auth-disabled sandbox 401s every API call until an
  `rt_session=sandbox:<id>` cookie is picked, and the switcher is the only page-local way
  to pick one — a page without it looks signed-out, which is indistinguishable from a
  real auth failure. `src/app/mount.test.tsx` pins it.

Both used to be `<link>`/`<script>` tags injected into every entry by a Vite plugin and
served by Ktor from `web/`. They are bundled now; the plugin is gone.

Four entries exist: `index.html` (map), `availability.html`, `watches.html`, and
`gallery.html`. Ktor serves each from `frontend/dist` and there is no fallback behind
them, so an unbuilt tree 404s the whole site rather than degrading. Adding a page means
an HTML entry, a `rollupOptions.input` entry, and an entry in `pages` in
`StaticSiteRoutes.kt`.

## Color: tokens only

`frontend/src/tokens/tokens.css` is the only place a raw **hex** may appear.
`node scripts/check-color-tokens.mjs` fails the build on one anywhere else, and it runs in
`make test` and in CI's web-test job.

Functional notation — `rgba()`, `hsl()` — is **ratcheted, not banned**. The remaining
occurrences are overlays at one-off alphas with no role to map onto; converting them would
mean inventing a token per alpha or rounding onto the nearest one, which is a silent visual
change. The checker holds a per-file high-water mark instead: new raw `rgba()` fails the
build, existing debt can only shrink. Compose new ones from a channel primitive:
`rgba(var(--rt-c-overlay-rgb), 0.06)`.

It is two tiers. Primitives (`--rt-c-*`) hold the raw values and are named for what they
*are* (`--rt-c-blue-500`). Semantic roles (`--rt-brand`, `--rt-surface`, `--rt-avail`)
alias a primitive and are named for what they *do*. **Components use semantic roles, never
primitives** — a primitive at a call site is the same drift as a hex, one indirection
later.

Need a color no role covers? Add the role to `tokens.css` rather than reaching for a
primitive or a literal.

### From JS

MapLibre paint properties, canvas charts and inline style strings can't resolve `var()`.
They go through the bridge, which reads the live computed value off the document root — so
`tokens.css` stays the single source and a theme reaches the map too:

```ts
import { token } from '@tokens';

paint: { 'circle-color': token('--rt-layer-np') }
```

A DOM element you build by hand is **not** one of these cases: `var()` resolves in an
inline style, so `map/trip-markers.ts` uses `background: var(--rt-brand)` and gets theme
changes for free. Reach for `token()` only where the consumer parses colour itself.

`tokens.js` carries a fallback table for early boot and for jsdom tests, where no
stylesheet has loaded — which is why a unit test asserts `token('--rt-…')` rather than a
literal hex. The checker verifies every fallback key names a token `tokens.css` actually
defines, so a rename fails loudly instead of pinning a stale value at runtime.

### Theming

Because every role resolves through a primitive, a theme is an override block — redefine
`--rt-c-*` under a scope like `[data-rt-theme="light"]`, plus only the roles that genuinely
diverge. Custom properties inherit downward only, so the scope attribute belongs on
`<html>`. Call `resetTokenCache()` from `@tokens` after a runtime swap so the map and
charts re-resolve.

## CSS rules

- Custom properties come from `src/tokens/tokens.css` (`--rt-*` prefix).
- A component imports its own stylesheet: `import './topbar.css'` beside the component
  that owns it. Vite bundles and hashes it; there is no runtime `<link>` injection any
  more.
- Prefer LDS's own classes and layout primitives before writing new CSS, and prefer
  extending an existing feature stylesheet before adding another one.
- `node scripts/check-css-blocks.mjs` fails on an unbalanced brace, and
  `node scripts/check-token-usage.mjs` fails on a `var(--rt-*)` naming a token that does
  not exist or a declaration whose parens do not balance. Both are cheap insurance for
  failures no test can see: a missing token resolves to nothing, and an unbalanced
  declaration is dropped whole by the browser. jsdom does no layout, so neither shows up
  in the suite.

## Testing

- **Vitest + jsdom + Testing Library** for everything in `frontend/src`. Assert on what a
  user sees: roles, labels, text. `data-testid` is a last resort.
- Pure modules get their own suites; components get behaviour tests through their real
  hooks against a stubbed `fetch`.
- **jsdom cannot see layout, and cannot see a page that fails to mount.** Three purely
  visual bugs reached review during the migration with every gate green. What catches
  those is `backend/src/smokeTest/.../SmokeTest.kt` — Playwright against a live stack via
  `make qa` — and, for anything jsdom structurally cannot answer, driving the built page
  in headless Chromium by hand.
- The map's fake (`frontend/src/test/fake-map.ts`) is a recorder, not a simulator: it
  answers what the app *asked* the map to do. Add to it rather than reaching for a real
  MapLibre instance, which needs WebGL.

## Reference

- [docs/react-migration-plan.md](react-migration-plan.md) — the migration's full history,
  every locked decision, and the Gotchas list. Read the Gotchas before your first LDS
  form, MapLibre effect or query key.
- [docs/backend-architecture.md](backend-architecture.md) — the API these clients call.
- [docs/reservation-providers.md](reservation-providers.md) — required reading before
  anything touching availability, watches or a provider integration.
