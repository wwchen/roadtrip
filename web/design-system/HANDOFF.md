# Handoff: Roadtrip Map Design System (v1.0 · dark)

## Overview
This bundle formalizes the de-facto visual language of the Roadtrip Map app
(`wwchen/roadtrip`) into a documented, centralized design system. Today the app
uses ad-hoc `--cg-*` CSS custom properties scattered across `index.html`,
`web/topbar.js`, `web/layers.js`, and `web/drawer/*.js`. The task is to replace
those with a single canonical token sheet and align components to the system.

**Target repo:** `wwchen/roadtrip` (default branch `master`).
Suggested location for the token sheet: `web/design-system/tokens.css`
(or inline the `:root` block into the existing `<style>` in `index.html`).

## About the Design Files
The files in this bundle are **design references**, not drop-in production code:

- `tokens.css` — this one IS meant to ship verbatim. It is the source of truth.
- `roadtrip-design-system.html` — a self-contained visual reference page
  (open in any browser, works offline). It documents every color, type ramp,
  spacing value, component, and the availability system. Use it to see intended
  appearance; do **not** copy its inline styles into the app wholesale.
- The task is to wire `tokens.css` into the existing app and migrate components
  to reference the new variables, using the app's existing vanilla-JS + template
  patterns (no framework change).

## Fidelity
**High-fidelity.** Colors, type, spacing, radii, and elevation are final. Match
the hex values and conventions exactly.

## The one intentional change to preserve
The system **separates interactive-blue from category-green**:

- `--rt-brand` (`#3b82f6`, blue) is the **only** interactive color — CTAs, links,
  focus rings, the active route line, selected states.
- `--rt-avail` (`#4cb96a`, green) means **availability only** (open / bookable / go).

Previously green (`#4cb96a` / button `#2f7a3a`) was overloaded as the primary
button color *and* the campground layer color *and* the "available" status.
When migrating, repoint primary buttons and the route color to `--rt-brand`, and
reserve green for availability. The old route blue (`#4285F4` in
`web/topbar/state.js` `ROUTE_COLOR`) should become `--rt-brand` (`#3b82f6`).

## Design Tokens
The complete set is in `tokens.css`. Summary:

**Neutrals (surfaces):**
`--rt-bg-base #0e0f12` · `--rt-bg #16171b` · `--rt-bg-sunken #1c1d21` ·
`--rt-surface #26272d` · `--rt-surface-raised #2f3037`

**Overlays / borders:**
`--rt-fill-subtle rgba(255,255,255,.04)` · `--rt-fill-hover .06` ·
`--rt-fill-active .08` · `--rt-border rgba(255,255,255,.08)` ·
`--rt-border-strong .13`

**Text:** `--rt-text #e8eaed` · `--rt-muted #9aa0a8` · `--rt-faint #626770`

**Interactive (the only clickable color):**
`--rt-brand #3b82f6` · `--rt-brand-hover #2b6dd1` · `--rt-brand-press #245bb0` ·
`--rt-brand-tint rgba(59,130,246,.15)`

**Map data layers:**
`--rt-layer-supercharger #e82127` · `--rt-layer-cg-federal #2e7d32` ·
`--rt-layer-cg-state #4e9a3f` · `--rt-layer-cg-provincial #1f7a34` ·
`--rt-layer-cg-local #8fbf5a` · `--rt-layer-pf #7b4bb5` ·
`--rt-layer-np #2e7d32` · `--rt-layer-sp #8d6e63`

**Availability semantics:**
`--rt-avail #4cb96a` (+ `--rt-avail-bg`) · `--rt-first-come #f1a04a`
(+ `--rt-first-come-bg`) · `--rt-reserved #626770` · `--rt-watching #3b82f6` ·
`--rt-rating #f5a623`

**Status:** `--rt-success #4cb96a` · `--rt-warn #f1a04a` · `--rt-error #f56565` ·
`--rt-info #3b82f6`

**Radii:** `sm 6` · `md 8` · `lg 10` · `xl 12` · `2xl 18` · `pill 999` (px)

**Elevation (shadow only):**
`--rt-e1 0 1px 2px rgba(0,0,0,.35)` · `--rt-e2 0 4px 12px rgba(0,0,0,.30)` ·
`--rt-e3 0 6px 20px rgba(0,0,0,.40)` · `--rt-e4 0 8px 24px rgba(0,0,0,.45)`

**Type:** system stack via `--rt-sans`; `--rt-mono` for tokens/coords/raw data.
Ramp: Display 28/700/-0.02em · Title 20/600 · Heading 16/600 · Body 14/400 ·
Body-sm 13 · Label 12/500 · Caption 11 · Micro 10/600/0.06em/caps.

## Components (see the reference page for rendered examples)
Match these to the system when migrating:

- **Buttons — 3 tiers.** Primary = solid `--rt-brand`, white text, 40px tall
  (44 on touch), radius `--rt-r-md`. Secondary = `--rt-fill-subtle` + `1px
  --rt-border-strong`. Tertiary = transparent, `--rt-muted`. Disabled = faint.
  Icon button = 40×40. One primary per surface.
- **Layers panel.** `--rt-surface` + `--rt-e3`. Categories expand into per-agency
  sub-filters (checkbox + layer dot + count). Checkbox `accent-color: --rt-brand`.
- **Pills / tags.** Verdict pills use availability tints
  (`--rt-avail-bg` / `--rt-first-come-bg`); feature tags use `--rt-fill-subtle`.
  Radius `--rt-r-pill`.
- **Cell coverage.** Carrier chips colored by bars (green→amber→red), count in
  `tabular-nums`.
- **Command bar** (search → result → actions), corridor result cards, and
  supercharger pricing — see reference.

## Availability system (the priority surface)
One color language across every view — keep identical:
`--rt-avail` = open · `--rt-first-come` = first-come · `--rt-reserved`/faint =
reserved · 45° hatch = closed · `--rt-watching` blue dot = watched.

Views: **week grid** → **month popover** → **availability-history strip** (bars
showing a date's open-site count booking out over time; steep drop = set a watch
early) → **reservable-by-date site matrix** (site × night, price where open,
`tabular-nums`). Every date cell should be tappable to set a watch — open *or*
full — so checking flows into monitoring in one gesture.

**Availability alerts table** (watches summary): fixed-width aligned columns
(POI · Date · Trigger · Last checked · Actions); pause/resume as bordered
buttons (resume = `--rt-brand` tint); inactive rows at ~0.42 opacity; done/found
alerts sorted to the bottom; Slack deep-link focuses its row with an accent bar.

## Conventions
- 4px spacing grid. Elevation via shadow only — surfaces stay flat/same-colored;
  don't add heavy borders to floating panels.
- `tabular-nums` on every price, date, distance, count, coordinate.
- Touch targets ≥ 44px; inputs at 16px font to prevent iOS zoom-on-focus.
- Define `a` / `a:hover` from `--rt-brand` / `--rt-brand-hover`.

## Suggested migration steps
1. Add `web/design-system/tokens.css`; link it in `index.html` `<head>` before
   other styles (or paste the `:root` block into the existing `<style>`).
2. Grep for `--cg-` across `index.html`, `web/**`; map each to its `--rt-*`
   equivalent (surfaces, text, borders as documented above).
3. Repoint primary buttons + `ROUTE_COLOR` (`web/topbar/state.js`) to
   `--rt-brand`; free green for availability only.
4. Verify layers panel, drawers, availability views against the reference page.
5. Commit (e.g. `git add web/design-system && git commit -m "Add design system tokens (v1.0)"`).

## Slack watch notifications
See `roadtrip-slack-notifications.html` for the redesigned watch alerts. These
replace the current emoji-link messages. Five states, each keyed by the Slack
attachment **color bar** (the one styling lever Slack gives us):

| State | Bar color | Emoji | Primary action |
|---|---|---|---|
| Sites available | `#4cb96a` | 🏕️ | Reserve (URL button → Recreation.gov) |
| Watching (idle) | `#3b82f6` | 👀 | — |
| Paused | `#8a8f96` | ⏸ | Resume |
| Window expiring | `#f1a04a` | ⏳ | Extend window |
| Check failed | `#f56565` | ⚠️ | Retry now |

**Block Kit implementation (adjusted to real API constraints):**
- Bar = legacy `attachments[].color`; put all blocks inside the attachment.
- Actions = an `actions` block of `button` elements. **Reserve** = URL button
  (no backend). Pause/Resume/Delete/Retry/Extend = interactive buttons with
  `action_id` (your app handles the payload). Slack only supports
  `default` / `primary` (green) / `danger` (red) button styles — the main CTA is
  `primary`, destructive is `danger`. There is **no brand-blue filled button**.
- Fields = a `section` with a 2-col `fields` array; bold labels with `*…*`.
- Site list = one `section` of mrkdwn bullets, capped at 3 + "N more" (no shaded
  box — Slack sections have no background fill; use `•` or `🟢` bullets).
- Sub-line ("checked just now", "auto-expires…") = a `context` block.
- Set `unfurl_links:false` on `chat.postMessage` to suppress the giant
  Recreation.gov photo unfurl.
- **Not possible:** colored heading text (mrkdwn can't color body text — emoji +
  bar carry state), shaded card backgrounds, custom fonts/radii/button spacing.

Suggested code location: wherever the watch alert payloads are built today
(search for the current "watch dashboard" / "pause watch" message strings).

**Ready-to-send payloads:** `slack-blockkit-payloads.js` exports one
`chat.postMessage` argument object per state (available / watching / paused /
expiring / error), validated against Block Kit rules. Replace the `{{...}}`
placeholders (`channel`, `watchId`, `siteId`, `appUrl`) server-side, and wire the
interactive `action_id`s (`watch_pause`, `watch_resume`, `watch_delete`,
`watch_extend`, `watch_keep`, `watch_retry`, `open_grid`, `open_map`) into your
Slack interactivity handler. `reserve_site` is a URL button — no handler needed.

## Files in this bundle
- `tokens.css` — ship verbatim (source of truth)
- `roadtrip-design-system.html` — offline visual reference
- `roadtrip-slack-notifications.html` — offline Slack notification reference
- `slack-blockkit-payloads.js` — ready-to-send `chat.postMessage` payloads (5 states)
- `design-system-README.md` — short in-repo readme to include alongside the tokens
