# Availability Matrix Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans style task tracking. Steps use checkbox (`- [ ]`) syntax for implementation progress.

**Goal:** Bring the campground drawer matrix closer to vendor grid parity without turning the drawer into checkout. The matrix should help the user scan availability, prefer sites that satisfy the selected stay length, lightly sort/filter rows, and inspect campsite attributes/media from existing ETL data.

**Stack base:** Branch from `master` after the swappable matrix UI landed in PR #246. The backend min-night invariant remains separate in PR #247.

**Scope:**

- Consecutive-night run highlighting based on selected min nights.
- Lightweight row sorting/filtering in table mode.
- Site details panel opened from a matrix row/site click.
- Best-effort normalized details from existing reservable fields and provider `raw`.
- Best-effort image/media display when a usable URL exists in `raw`; no placeholder when absent.

**Out of scope:**

- Exact per-site map geometry/location.
- Checkout/add-to-cart.
- New upstream fetches or schema changes.
- Hard dependency on campsite photos being present for every provider/site.

---

## File map

**Modified:**

- `web/availability/availability-week.js` — own matrix UI state for selected site detail, filters, and sort; pass min-nights/run context to the matrix.
- `web/availability/site-matrix.js` — render filter/sort controls, row click/detail buttons, and consecutive-night highlights.
- `index.html` — compact controls, highlighted run cells, and site-detail panel styling.

**Created:**

- `web/availability/site-detail.js` — pure renderer for site attributes/media from reservable fields + provider raw JSON.

---

## Task 1: Consecutive-night highlight

- [x] Pass `minNights` into `renderSiteMatrix`.
- [x] Compute qualifying reservable ids for each arrival date using the loaded matrix days.
- [x] Add a distinct visual state for cells in a qualifying run.
- [x] Keep availability unchanged; highlight only expresses preference/fit.

## Task 2: Matrix sort/filter

- [x] Add table-mode state: text query, loop filter, type filter, sort key.
- [x] Derive loop/type filter options from loaded reservables.
- [x] Filter rows by name, loop, type, vendor id, and rid.
- [x] Sort rows by site, loop, type, available-first, and fit-first.

## Task 3: Site detail panel

- [x] Add `site-detail.js` renderer.
- [x] Open detail panel from a site row/name click without navigating away.
- [x] Render core fields: site, loop, type, vendor, booking link.
- [x] Render normalized attributes from Rec.gov and Aspira raw payloads.
- [x] Render a media image only when a plausible image URL is present.

## Task 4: Verification

- [x] JS syntax checks for touched modules.
- [x] Browser harness check: filter/sort controls render, run highlighting appears for min nights > 1, site detail opens.
- [x] Update/push stacked PR.
