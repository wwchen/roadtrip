# Roadtrip Map — Design System (v1.0 · dark)

A quiet, data-dense system for planning road trips and camping on the map.
Utilitarian and precise, in the spirit of Google Maps and Linear.

## Start here: the living gallery

**`gallery.html` is the catalog. Open it before building any UI** — if a
primitive already covers what you need, compose it instead of writing something
ad hoc. That is how this stays cohesive.

```bash
# .claude/launch.json → "static"
python3 -m http.server 8766
open http://localhost:8766/web/design-system/gallery.html
```

It is plain HTML that `<link>`s the real stylesheets and `import`s the real
component modules — every component on the page is a live mount, so the gallery
renders exactly what ships and cannot go stale. Keep it that way: **never
replace it with a design-tool export.** The previous gallery was a bundled
export, and the first hand-edit silently destroyed it (see `HANDOFF.md`).
Frozen exports live in `docs/design-references/`.

A new primitive is not done until: the three files (`*.js` / `*-template.js` /
`*.css`) land here, it has a live section in `gallery.html`, and any new
convention is documented below.

## Files

- **`tokens.css`** — the source of truth. Linked from `index.html` and
  `availability.html`. Replaces the ad-hoc `--cg-*` variables that used to live
  in an inline `:root` block.
- **`gallery.html`** — the living catalog of every primitive and token.

## The one rule

Blue (`--rt-brand`) is the **only** interactive color — CTAs, links, focus, the
active route. Green (`--rt-avail`) means **availability** only, not "click me."
Previously green was overloaded as button color + campground layer + status;
this split fixes it.

## Color roles (kept strictly apart)

| Role | Tokens | Use |
|---|---|---|
| Neutral | `--rt-bg*`, `--rt-surface*` | every surface |
| Interactive | `--rt-brand*` | the only clickable color |
| Interactive text | `--rt-brand-text` | brand blue used **as text** (links, text-only buttons) |
| Layer | `--rt-layer-*` | map pin + legend identity |
| Availability / status | `--rt-avail`, `--rt-first-come`, `--rt-warn`, `--rt-error`… | meaning only |

## Conventions

- 4px spacing grid; controls 6–10px radius; drawer 18px; pills fully round.
- Elevation is shadow only (`--rt-e1`…`--rt-e4`) — surfaces stay flat and same-colored.
- `tabular-nums` on every price, date, distance, count and coordinate.
- Touch targets ≥ 44px; inputs at 16px font to stop iOS zoom-on-focus.
- One primary (solid blue) per surface; everything else secondary/tertiary.

## Buttons

Use `.rt-btn` plus a variant modifier for every clickable action. Classes live in
`web/design-system/buttons.css`; inject it once per component with an id-guarded
`<link id="rt-buttons-styles">` in `injectStyles()`.

| Variant | Class | When to use |
|---|---|---|
| Primary | `.rt-btn--primary` | The ONE solid-blue action per surface (Save, Continue with …) |
| Secondary | `.rt-btn--secondary` | Filled surface-raised; secondary actions (Send a test email) |
| Tertiary | `.rt-btn--tertiary` | Brand-text link; lowest emphasis (Cancel, Replace) |

**Rules:**
- Only one `.rt-btn--primary` should appear per visible surface.
- Never use green or any other hue for interactive actions — blue only.
- Full-width buttons: add `width: 100%` via a layout wrapper or inline style;
  `.rt-btn` is `inline-flex` by default.
- Blue **as text** uses `--rt-brand-text`, not `--rt-brand`. `--rt-brand` is
  tuned for solid fills (white-on-blue) and only reaches 4.05:1 as text on
  `--rt-surface`, under the 4.5:1 AA floor. `.rt-btn--tertiary` is the one
  exception — it is large enough to carry `--rt-brand`.

### DoubleConfirmButton sizing

`mountDoubleConfirmButton(container, config)` renders at ≥44px by default,
because it fires destructive actions (Sign out, Disconnect Slack). Pass
`size: 'compact'` only where the button sits in a dense, fixed-width row —
today just `web/watches/watch-table.js`, whose action grid is 28px columns.

| `size` | Height | When |
|---|---|---|
| *(omitted)* | `min-height: 44px` | Default. Anything a finger touches. |
| `'compact'` | `28px` | Dense table rows with fixed-width siblings. |

## Tabs rail / segmented control convention

Use `mountTabs(container, config)` from `web/design-system/tabs.js` for section
navigation within a page (e.g., account settings pages).

| Config | Default | Effect |
|---|---|---|
| `tabs` | `[]` | Array of `{ id, label }` descriptors |
| `active` | first tab id | Initially selected tab |
| `onChange` | `undefined` | Called with the new tab id whenever selection changes |

**Rail → segmented control breakpoint:** on viewports ≤560px the vertical left rail
collapses to a full-width horizontal segmented control at the top. Each button flexes
equally, text-centered, with a 44px min-height touch target.

**Three-file contract:**
- `tabs-template.js` — pure function, no DOM, imports only `escapeHtml` from `../core.js`
- `tabs.js` — controller: injects `tabs.css` via `<link>` with an id-guard, delegates
  click events on the container, sets active via `setActive(id)`. Returns
  `{ getActive(), setActive(id), dispose() }`.
- `tabs.css` — `--rt-*` tokens only; vertical rail default, `@media (max-width:560px)`
  segmented control variant.

**Active styling:** active tab uses `--rt-brand-tint` background and `--rt-text` color;
inactive tabs use transparent background and `--rt-muted` color.

**Anatomy:** container (`.rt-tabs-rail`, `role="tablist"`) with buttons
(`.rt-tabs-tab`, `[data-tab=<id>]`, `role="tab"`, `aria-selected`). Active button also
gets `.rt-tabs-tab--active`.

## Modal overlay / bottom-sheet convention

Use `mountModal(container, config)` from `web/design-system/modal.js` for any overlay
that blocks the rest of the UI.

| Config | Default | Effect |
|---|---|---|
| `title` | `''` | Header title text (escaped) |
| `sheetOnMobile` | `false` | Renders as bottom-sheet on `≤560px` viewports |
| `onClose` | `undefined` | Called on Escape, scrim click, and header ✕ |
| `closeOnBackdrop` | `true` | Toggle scrim-click-to-close |

**Three-file contract:**
- `modal-template.js` — pure function, no DOM, imports only `escapeHtml` from `../core.js`
- `modal.js` — controller: injects `modal.css` via `<link>` with an id-guard, delegates
  click events on the container, adds `keydown` Escape on `document`. Returns
  `{ close(), setBody(el), dispose() }`.
- `modal.css` — `--rt-*` tokens only; scrim, centered card, `@media (max-width:560px)`
  bottom-sheet variant with grab handle.

**Anatomy:** scrim (`data-modal-backdrop`) + card (`.rt-modal-card`) with header
(title + `[data-modal-close]` ✕) and body (`[data-modal-body]`). When `sheetOnMobile`
is set, `.rt-modal-sheet` is added and a grab-handle renders above the header.

## SecretField — write-only secret input convention

Use `mountSecretField(container, config)` from `web/design-system/secret-field.js`
for any credential input that must never echo the real value back to the UI (e.g. a
Slack bot token). The component receives only a last-4 **hint** suffix, never the full
secret.

| Config | Default | Effect |
|---|---|---|
| `label` | `''` | Field label (escaped) |
| `hint` | `null` | Last-4 chars of the stored secret, or `null` if none |
| `help` | `null` | Optional help text rendered below the field |

**Write-only-secret pattern (masked → replace):**

The field operates in two modes:

- **`stored`** — a `hint` exists. Renders `••••<hint>` in monospace + a **Replace**
  button (`data-action="replace"`). `getValue()` returns **`null`** in this mode,
  meaning "leave unchanged." This maps directly to the backend's `null = leave
  unchanged` contract — the caller sends `null` and the server skips the update.
- **`replacing`** — triggered either by clicking Replace (from `stored`) or when
  `hint` is null/absent at mount time (no prior secret). Renders an empty `<input
  type="password">` at 16px (iOS zoom guard) + a **Cancel** button
  (`data-action="cancel"`, only shown when a prior hint exists). `getValue()` returns
  the entered string.

Clicking Cancel in `replacing` mode returns to `stored` (restoring the original hint).

**Three-file contract:**
- `secret-field-template.js` — pure functions: `initialState(hint)`, `toReplacing`,
  `toCancelled`, `withInput`, `valueOf`, and `secretFieldTemplate(state, config)`. No
  DOM access; imports only `escapeHtml` from `../core.js`.
- `secret-field.js` — controller: injects `secret-field.css` via `<link>` with an
  id-guard, delegates click events (`data-action="replace"` / `"cancel"`) and input
  events on the container, tracks state via the pure reducer. Returns
  `{ getValue(), getMode(), reset(), dispose() }`.
- `secret-field.css` — `--rt-*` tokens only; 44px min-height touch targets on Replace
  and Cancel buttons; 16px `font-size` on the password input.

**Pure reducer API** (exported from `secret-field-template.js`, testable without DOM):

```js
import { initialState, toReplacing, toCancelled, withInput, valueOf }
  from './secret-field-template.js';

const s0 = initialState('3f9a');    // { mode: 'stored', hint: '3f9a', value: '' }
valueOf(s0);                         // null  — "leave unchanged"
const s1 = toReplacing(s0);         // { mode: 'replacing', ... }
const s2 = withInput(s1, 'xoxb-…'); // { mode: 'replacing', value: 'xoxb-…' }
valueOf(s2);                         // 'xoxb-…'
const s3 = toCancelled(s2, '3f9a'); // back to stored
valueOf(s3);                         // null
```

**Anatomy:** host (`.rt-secret-field`) with label (`.rt-secret-field-label`) + either
a stored row (`.rt-secret-field-stored`: masked span `.rt-secret-field-masked` +
Replace button `.rt-secret-field-replace-btn`) or a replacing row
(`.rt-secret-field-replacing`: password input `.rt-secret-field-input` + optional
Cancel button `.rt-secret-field-cancel-btn`) + optional help text (`.rt-secret-field-help`).
