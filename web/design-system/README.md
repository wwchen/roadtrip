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
