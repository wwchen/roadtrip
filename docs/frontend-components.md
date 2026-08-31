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

The living component catalog is Storybook under `frontend/.storybook/`, with stories beside
the components they document. Run `npm run storybook` for local development and
`npm run build-storybook` to verify the static catalog. The global preview imports the same
`@ui/styles.css` theme boundary as production. Add a representative story when a new shared
primitive or reusable Roadtrip UI pattern is introduced.

## Two layers

### Primitives (`@ui`)

Generic, reusable across any page: `Button`, `Banner`, `Table`, `Modal`, `Checkbox`,
`TextField`, `Toggle`, `Tabs`, `SegmentedControl`, and the three local ones above. They
know nothing about watches, POIs or trips — they take data and callbacks.

Never `Omit<…>` an LDS prop type: `HtmlProps` ends in an index signature, and `Omit` over
one collapses every named prop to `any`. Widen by intersection instead.

### Domain components (`frontend/src/features/<feature>/`)

Compose primitives into feature-specific UI — `features/watches/WatchTable.tsx`,
`features/trip/TopBar.tsx`, `features/drawer/PoiDrawer.tsx`. They import
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

## The POI page (`src/domain/poi/`)

Every pin opens the same page. The type decides which blocks appear, and nothing
else about the page changes — that is the rule the whole directory exists to keep,
and it comes from the M0 screens doc (4a, "One order, thirteen blocks").

- `blocks.ts` is the **only** place the order lives: thirteen ids, grouped by
  hairline, with `RULE_BEFORE_GROUP` marking the fold between "can I stay here, and
  when" and "tell me more". Nothing else may express an order.
- `PoiPageShell.tsx` takes a bag of blocks keyed by id and renders them in that
  order. A type omits a block by leaving the key out; a group with nothing in it
  draws no band and no stray rule. `variant` is `panel` (the map drawer, ~520px) or
  `page` (the routed detail page, which also shows the step above) — CSS and one
  nav, not structure.
- **Nothing is printed twice.** The trail is one step, not a chain: the title says
  where you are, so an ancestry would spend three lines restating it. Whatever that
  step names comes out of the subtitle — but only on `page`, since the panel does
  not render the step and would otherwise lose the region entirely. `eyebrowFor`
  drops a type word the name already carries, so "Campground · USDA Forest Service"
  over **Tuff Campground** loses the first half and "State park" over **Silver Falls
  State Park** disappears.
- `PoiBlocks.tsx` is the block vocabulary — identity, actions, glance, prose, specs,
  contact, links, nearby, the footer stamp and the provenance disclosure. They
  render shapes from `model.ts` and know nothing about any provider.
- `fields.tsx` is extraction and the three shared controls (`DirectionsButton`,
  `CallButtons`, `SharePoiButton`); `campground-detail.ts` and
  `supercharger-detail.ts` are the per-provider extractors. A control that belongs
  in *every* actions row goes here and is added to each type's `PoiActions` — the
  row's contents are the type's call, which is why they are not injected by the
  shell.
- `types/` holds one component per POI type plus `registry.ts`, a category → page
  `Map`. Types whose page is identity, actions and one spec list — gym, trailhead,
  town stop, dropped pin, state — are rows in `place.tsx`'s descriptor table rather
  than components of their own.

Two surfaces consume it and neither adds anything: `features/drawer/PoiDrawer`
(the shell, the drag gesture, and the two states that have no page to show) and
`pages/poi/PoiPage` (the routed `?poi=<id>` page). **If one shows a block the other
does not, that is a bug in one of them.** Adding a type is a row in `registry.ts`;
adding a block is an id in `blocks.ts` and a component beside its neighbours.

## Theme

`<html>` carries `theme-roadtrip-zion` always, and `mode-dark` when the resolved
mode is dark. Three things own this and nothing else should touch it:

- `src/lib/theme.ts` — the types, `resolveMode`, and the `localStorage` mirror.
- `src/stores/themeStore.ts` — the only writer of the class, the `theme-color`
  meta and `resetTokenCache()`. Adding the class anywhere else leaves the map
  painting the previous mode's colours.
- The inline script in each page shell, which applies the mirrored
  mode before first paint. It is duplicated on purpose and pinned by
  `src/test/page-shells.test.ts` — edit them all together, and note that the
  dark `theme-color` is a literal there because the script cannot import; that
  test pins it against `THEME_COLORS.dark` so the two cannot drift.

The mirrors mean **what is saved**, which is what lets the boot script paint from
them before anything can correct it. So a preview goes through `previewChoice`,
which applies a mode without mirroring it, and only `setChoice` persists.
Previewing through `setChoice` would let an unsaved choice survive a closed tab —
`SettingsModal`'s revert-on-close is a React unmount cleanup, and closing a tab
runs no cleanup at all.

A signed-in user's choice lives on `profile.theme`; anonymous visitors follow
`prefers-color-scheme`. New colours must come from mode-aware `--rt-*` roles, not
from a literal `--gray-*` step: zion's grey ramp does not invert, so a literal is
correct-looking in light and unreadable in dark.

The map follows the same mode through `src/features/map/basemaps.ts`. Dark mode
resolves the opening basemap to `carto-dark` only when the user has never
explicitly picked one — `initialBasemapKey(mode)` reads a stored key first and
falls back to the mode's default. The picker's **Auto** option returns to that
state by calling `forgetBasemapKey()`, which removes the stored key rather than
writing a sentinel for "follow the theme": absence already means auto, so a
second encoding of the same state would just be one more place for the two to
drift apart. `MapProvider` re-styles the map on every mode change (even when the
basemap key itself is unchanged), because overlay colours are cached by
`tokens.ts` and a full `setStyle` is what makes them re-resolve.

That reload destroys every source and layer the app added, so overlays reinstall
off `styleEpoch` — the context's style-generation counter, 0 before any style has
loaded and a fresh higher number on every `style.load`. It is falsy exactly when
nothing may touch the map, so `if (!map || !styleEpoch) return;` is the guard, and
it doubles as the effect dependency that drives reinstalls. **It is a counter and
not a boolean on purpose.** For an inline style — which the `carto-dark` default is
— MapLibre fires `style.load` synchronously inside `setStyle`, so the reset and the
reload land in one React batch; a boolean going true → false → true inside a single
batch is indistinguishable from one that never changed, React bails out, and every
reinstall effect is skipped. That shipped once: flipping the OS to dark swapped the
basemap and silently dropped every overlay while the legend still showed their
counts. A counter cannot collapse that way, because the new generation is a value
the old one never held.

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
  cascade, the Roadtrip Zion theme, app-owned data tokens, then the Zion-to-app
  chrome-role bridge.
- The **sandbox chrome** (build banner) is started by `mountPage()`, so every
  page has the same deployment provenance indicator. Sandboxes use the normal
  provider-backed auth flow; there is no page-local user switcher.
- `AppProviders` wraps every page in **`app/PageErrorBoundary`**, outside the
  query client and the toast host, so a throwing component shows a banner with a
  reload rather than a white page — the failure jsdom structurally cannot see.
  Its fallback banners rather than toasts, because the provider it would toast
  through may be the thing that failed. `mountPage()` also logs a missing `#root`
  and any `unhandledrejection`; both go to `console.error`, which is the only
  reporting path this app has.

Both used to be `<link>`/`<script>` tags injected into every entry by a Vite plugin and
served by Ktor from `web/`. They are bundled now; the plugin is gone.

Four entries exist: `index.html` (map), `availability.html`, `watches.html`, and `poi.html`. Ktor serves
each from `frontend/dist` and there is no fallback behind them, so an unbuilt tree 404s the
whole site rather than degrading. Storybook is a development tool and is not a Ktor route or
production Vite entry. Adding a production page means
an HTML entry, a `rollupOptions.input` entry, and an entry in `pages` in
`StaticSiteRoutes.kt`.

## Color: tokens only

Raw **hex** values belong only in approved token sources. The app-owned source is
`frontend/src/tokens/tokens.css`; `roadtrip-zion.css` is the other approved source because it
is a byte-for-byte exported LDS theme kept intact for easy re-syncing. `tokens.ts` contains
the corresponding early-boot/jsdom fallbacks. `node scripts/check-color-tokens.mjs` rejects
raw hex everywhere else, and it runs in `make test` and in CI's web-test job.

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

`tokens.ts` carries a fallback table for early boot and for jsdom tests, where no
stylesheet has loaded — which is why a unit test asserts `token('--rt-…')` rather than a
literal hex. The checker verifies every fallback key names a token `tokens.css` actually
defines, so a rename fails loudly instead of pinning a stale value at runtime.

### Theming

The active theme is scoped by `theme-roadtrip-zion` on `<html>`. Its bridge maps app chrome
roles such as `--rt-surface` and `--rt-brand` onto LDS theme roles while deliberately leaving
map-layer, availability, and status colors app-owned. Custom properties inherit downward
only, so any future runtime theme scope also belongs on `<html>`. Call `resetTokenCache()`
from `@tokens` after a runtime swap so the map and charts re-resolve computed colors.

## CSS rules

- Custom properties come from `src/tokens/tokens.css` (`--rt-*` prefix).
- A component imports its own stylesheet: `import './topbar.css'` beside the component
  that owns it. Vite bundles and hashes it; there is no runtime `<link>` injection any
  more.
- Prefer LDS's own classes and layout primitives before writing new CSS, and prefer
  extending an existing feature stylesheet before adding another one.
- Two custom properties carry the geometry one fixed surface needs from another, because
  CSS cannot read a sibling's box:
  - `--rt-chrome-top` — room a fixed deployment bar has taken off the top of the viewport.
    `app/shell.css` defaults it to `0px`; only `sandbox/sandbox.css` sets it, keyed on the
    `has-sandbox-chrome` class so the room can be reserved from a mirror before the
    build-info fetch answers. The map shell, `body` padding, the desktop drawer, the legend
    and the topbar's max-height all offset by it — a new fixed surface, or anything sized
    against `100dvh` inside the map shell, should honour it rather than assume `top: 0`.
  - `--rt-topbar-bottom` — where the search panel's lower edge sits, relative to the map
    shell, published by `features/trip/useTopbarClearance.ts` and consumed by the desktop
    drawer, which starts below it. It is the panel's bottom edge and not its height
    because the panel is itself inset from the top. The search popover is excluded on
    purpose, so typing does not move the drawer.

    The drawer spends it on `top`, not `padding-top`: the two surfaces **tile**, so the
    drawer's box is the container for its contents and resizes with whatever the panel
    above is doing — expanding the alerts list or opening the corridor results shortens
    the drawer, collapsing them gives the height back. As padding the drawer's surface
    ran the full viewport height behind the panel, which read as one sheet with an
    unexplained empty band and hid the map above it.

    The drawer reserves this much room **uncapped**, which only works because
    `topbar.css` bounds the panel's own height at `min(50dvh, …)` and scrolls it
    internally. Do not add a ceiling on the drawer side: clamping there silently returns
    the drawer to underneath the panel whenever the panel is tall — a route with a
    corridor results list is enough — which is the bug the clearance exists to prevent.
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
