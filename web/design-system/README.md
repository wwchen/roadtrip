# Roadtrip Map — Design System (v1.0 · dark)

A quiet, data-dense system for planning road trips and camping on the map.
Utilitarian and precise, in the spirit of Google Maps and Linear.

## Files

- **`tokens.css`** — the source of truth. Linked from `index.html` and
  `availability.html`. Replaces the ad-hoc `--cg-*` variables that used to live
  in an inline `:root` block.

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
| Layer | `--rt-layer-*` | map pin + legend identity |
| Availability / status | `--rt-avail`, `--rt-first-come`, `--rt-warn`, `--rt-error`… | meaning only |

## Conventions

- 4px spacing grid; controls 6–10px radius; drawer 18px; pills fully round.
- Elevation is shadow only (`--rt-e1`…`--rt-e4`) — surfaces stay flat and same-colored.
- `tabular-nums` on every price, date, distance, count and coordinate.
- Touch targets ≥ 44px; inputs at 16px font to stop iOS zoom-on-focus.
- One primary (solid blue) per surface; everything else secondary/tertiary.

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
