# Frontend Component Architecture

## Before you build: open the gallery

`web/design-system/gallery.html` is the living catalog. **Check it first** — if a
primitive covers what you need, compose it rather than writing page-specific
markup or CSS.

```bash
# .claude/launch.json → "static"
python3 -m http.server 8766
open http://localhost:8766/web/design-system/gallery.html
```

Every component there is mounted from its real module and styled by its real
stylesheet, so the page always reflects what ships. Adding a primitive means
adding its live section to the gallery — see `web/design-system/README.md`.

## Component pattern

All frontend UI is vanilla JS — no framework. Components follow this contract:

```js
mountComponent(container, config) → { dispose(), update?(), ... }
```

- `container`: the DOM element the component renders into
- `config`: static options, callbacks, initial state
- Returns a controller with `dispose()` (cleanup listeners/children) and optional methods like `update()`, `getValue()`, etc.

## File structure

Every component has up to 3 files:

| File | Responsibility |
|------|---------------|
| `component.js` | DOM mounting, event delegation, lifecycle (the controller) |
| `component-template.js` | Pure functions returning HTML strings — no DOM access, no side effects |
| `component.css` | Styles using `--rt-*` design tokens |

**Rule: no HTML fragments in `.js` files.** Templates belong in `*-template.js` files. The component `.js` file imports them and handles DOM/events only.

## Two layers

### Design-system primitives (`web/design-system/`)

Generic, reusable across any page. Examples: Banner, ToggleSwitch, DoubleConfirmButton, DataTable, FormSection.

These know nothing about watches, POIs, or domain logic. They accept data and callbacks.

### Domain components (`web/<feature>/`, and `frontend/src/features/<feature>/`)

Compose design-system primitives into feature-specific UI. Examples:
`web/account/notifications-panel.js` (vanilla) and
`frontend/src/features/watches/WatchTable.tsx` (React).

Domain components import from `web/design-system/` and from `web/api/` but never from other feature directories.
Their React equivalents import primitives from `@ui` and clients from `@/api`, with the same
no-cross-feature rule.

> **Migrating.** `web/` is being replaced by `frontend/` page by page — see
> [docs/react-migration-plan.md](react-migration-plan.md). Watches has already moved:
> its components live in `frontend/src/features/watches/` and are built on LDS via `@ui`,
> and `web/watches/` is deleted. Read the plan before adding to either tree, so new work
> lands in React rather than extending the tree that is going away.

### Forms on LDS: the controls are uncontrolled

Non-negotiable, and the plan's Gotchas section has the full detail. The short version
for anyone adding a React form:

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

### Page shells

A migrated page's `*.html` is a bare shell: `#root` plus its entry module. `tokens.css`
and the sandbox banner/user switcher are injected into every entry by the
`runtimeServedAssets` plugin (`frontend/vite/runtime-served-assets.ts`) — do not hand-write
them, and do not omit them. They cannot be written in the HTML anyway: Vite treats a module
script as a build input and fails to resolve one pointing outside its root.

## Color: tokens only

`web/design-system/tokens.css` is the only place a raw **hex** may appear.
`node scripts/check-color-tokens.mjs` fails the build on one anywhere else, and
it runs in `make test` and in CI's web-test job.

Functional notation — `rgba()`, `hsl()` — is **ratcheted, not banned**. 51
occurrences predate the rule, nearly all overlays at one-off alphas with no
role to map onto; converting them would mean inventing a token per alpha or
rounding onto the nearest one, which is a silent visual change. The checker
holds a per-file high-water mark instead: new raw `rgba()` fails the build,
existing debt can only shrink. Tokenize some, lower the file's number, delete
the entry at zero. Compose new ones from a channel primitive:
`rgba(var(--rt-c-overlay-rgb), 0.06)`.

It is two tiers. Primitives (`--rt-c-*`) hold the raw values and are named for
what they *are* (`--rt-c-blue-500`). Semantic roles (`--rt-brand`,
`--rt-surface`, `--rt-avail`) alias a primitive and are named for what they
*do*. **Components use semantic roles, never primitives** — a primitive at a
call site is the same drift as a hex, one indirection later.

Need a color no role covers? Add the role to `tokens.css` rather than reaching
for a primitive or a literal.

### From JS

MapLibre paint properties, canvas charts and inline style strings can't resolve
`var()`. They go through the bridge, which reads the live computed value off the
document root — so `tokens.css` stays the single source and a theme reaches the
map too:

```js
import { token, seriesColor, cgClassColors } from '/web/design-system/tokens.js';

paint: { 'circle-color': token('--rt-layer-np') }
```

`tokens.js` carries a fallback table for early boot and for jsdom tests, where
no stylesheet has loaded. The checker verifies every fallback key names a token
`tokens.css` actually defines, so a rename fails loudly instead of pinning a
stale value at runtime.

Two exceptions, both enforced by name in the checker rather than by convention:
Slack's attachment API takes a literal hex over the wire, and `<meta
name="theme-color">` is read by browser chrome before any stylesheet loads. Both
sites name the token they mirror.

### Theming

Because every role resolves through a primitive, a theme is an override block —
redefine `--rt-c-*` under a scope like `[data-rt-theme="light"]`, plus only the
roles that genuinely diverge. Custom properties inherit downward only, so the
scope attribute belongs on `<html>`. Call `resetTokenCache()` from `tokens.js`
after a runtime swap so the map and charts re-resolve.

## CSS rules

- All custom properties come from `web/design-system/tokens.css` (`--rt-*` prefix)
- Components inject their own stylesheet via `<link>` (not inline `<style>` tags)
- Use the style injection pattern:

```js
function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/component-name.css';
  document.head.appendChild(link);
}
```

## Template rules

Template functions are pure — they take data, return an HTML string:

```js
import { escapeHtml } from '../core.js';

export function myTemplate({ title, items }) {
  return `
    <div class="rt-my-component">
      <h2>${escapeHtml(title)}</h2>
      ${items.map(item => `<span>${escapeHtml(item)}</span>`).join('')}
    </div>
  `;
}
```

- Always use `escapeHtml()` for user-provided text
- Templates never access the DOM or hold state
- Templates never import anything except `escapeHtml` from `core.js`

## Composing components

Parent components mount children into DOM elements created during their own render:

```js
function render() {
  container.innerHTML = parentTemplate({ ... });
  const childHost = container.querySelector('[data-child-host]');
  children.push(mountChildComponent(childHost, { ... }));
}
```

Always dispose children before re-rendering:

```js
function render() {
  children.forEach(c => c.dispose());
  children.length = 0;
  // ... render and mount new children
}
```

## Event delegation

Attach listeners on the container, not on individual elements. This survives re-renders without re-binding:

```js
function onClick(e) {
  const btn = e.target.closest('[data-action]');
  if (!btn) return;
  // handle action
}

container.addEventListener('click', onClick);
```

## Page controllers

Each page has a `*-page.js` entry point that:
1. Mounts top-level components into DOM host elements (defined in the HTML shell)
2. Handles URL params for deep-linking
3. Wires callbacks between components (form → table refresh, etc.)
4. Calls API functions from `web/api/`

Page controllers are self-initializing (`init()` runs at module load) and have no exports.

## Existing design-system components

| Component | Import | Purpose |
|-----------|--------|---------|
| Banner | `web/design-system/banner.js` | Dismissible success/error/info message |
| ToggleSwitch | `web/design-system/toggle-switch.js` | On/off toggle with label + help text |
| DoubleConfirmButton | `web/design-system/double-confirm-button.js` | Two-click destructive action (≥44px; `size: 'compact'` for dense rows) |
| DataTable | `web/design-system/data-table.js` | Table from column defs + row data |
| FormSection | `web/design-system/form-section.js` | Label + input + help text group |
| Modal | `web/design-system/modal.js` | Blocking overlay; bottom-sheet on ≤560px |
| Tabs | `web/design-system/tabs.js` | Section nav; left rail, segmented control on ≤560px |
| SecretField | `web/design-system/secret-field.js` | Write-only credential input (masked → replace) |

Shared styles that are not components:

| Sheet | Purpose |
|-------|---------|
| `web/design-system/tokens.css` | All `--rt-*` design tokens — the source of truth |
| `web/design-system/buttons.css` | `.rt-btn` + `--primary` / `--secondary` / `--tertiary` variants |

See `web/design-system/README.md` for each component's config contract and
anatomy.
