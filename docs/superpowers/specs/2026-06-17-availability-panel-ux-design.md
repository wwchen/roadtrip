# Availability panel UX — three changes

Date: 2026-06-17
Surface: campground drawer → "Sites by date" matrix
Source files: `web/availability/availability-week.js`, `web/availability/site-matrix.js`, `web/availability/site-detail.js`, `web/availability/booking-links.js`

## Problems

1. **Infinite scroll jumps.** When the user scrolls near the right edge, `fetchMoreMatrixDays` starts a request that can take several seconds. The matrix re-renders mid-fetch (showing the existing days plus a "Loading next week…" footer), and when the response lands the column count grows abruptly, shifting the scroll geometry under the user's pointer.
2. **Cell click opens detail below the fold.** Both the site-header button and each cell button carry `data-site-detail-rid`, so any click renders `renderSelectedSiteDetail` at the top of the availability section. That panel sits above the matrix in DOM order but typically below the visible viewport when the user is mid-scroll, so the click feels like it did nothing.
3. **Booking is two clicks deep.** To reach the reservation URL, the user has to click a cell, scroll to the detail panel, then click "Book on …". The cell already knows the agency from `site.reservation_url_template`, so we can collapse this to a single armed-cell flow.

## Scope

Three independent UX changes, all client-side, no API changes. Skeleton column rendering for in-flight week extension; detail panel inlined as an expansion row under the clicked site; cells become two-tap booking buttons.

## Change 1 — Skeleton columns during week extension

Keep auto-infinite scroll. Eliminate the layout jump by reserving column space for the next week as soon as the fetch starts.

**State.** `availability-week.js` context gains:

```js
ctx.matrixPendingDays = null  // string[] | null — ISO dates currently being fetched
```

**Lifecycle.** `fetchMoreMatrixDays`:

- On entry, after computing `nextStart`, set `ctx.matrixPendingDays = [isoDate(nextStart), …, isoDate(addDays(nextStart, WEEK_DAYS - 1))]`.
- `rerender(ctx)` then renders both real days and pending days in the same matrix.
- On success or error, clear `ctx.matrixPendingDays = null` before the final rerender.

**Rendering.** A new helper `matrixAvailabilityDaysWithPending(ctx)` returns:

```js
[
  ...matrixAvailabilityDays(ctx),
  ...(ctx.matrixPendingDays || []).map((date) => ({ date, placeholder: true })),
]
```

`renderAvailabilitySurface` calls this helper instead of `matrixAvailabilityDays`.

**Skeleton cells.** In `site-matrix.js`:

- `dateHeaderHtml(day)`: if `day.placeholder`, return a header that renders the date label inside a `cg-site-matrix-skeleton-bar` shimmer span and omits the click button (no `data-matrix-date`).
- `cellHtml(...)`: if `day.placeholder`, return `<td class="cg-site-matrix-cell cg-site-matrix-skeleton-cell"><span class="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-pill"></span></td>` — no button, no click target.
- `availabilityByDate.get(day.date)` will be `undefined` for placeholder days; that's fine because we early-return before consulting it.

**Footer.** Drop the `renderLoadMoreStatus` text row — the skeleton columns convey loading. Keep the error variant so users see fetch failures (`cg-site-matrix-load-status cg-site-matrix-error` reused).

**Why columns and not a row at the end:** the goal is to stop the matrix from changing width when the real data lands. Reserving column slots up front means only cell contents change between the placeholder and final renders, so scrollWidth stays constant and the user's scroll position stays put.

## Change 2 — Inline detail row under clicked site header

The site detail panel becomes a `<tr>` that spans all columns and is injected directly beneath the clicked row.

**Click attribution.** Today the site-header button and the cell button share `data-site-detail-rid`. Split them:

- Site header button (`siteLabelHtml`): `data-site-header-rid="${row.rid}"`.
- Cells: see Change 3 — the attribute is replaced entirely by `data-book-rid` / `data-book-date`.

**Rendering.** In `rowHtml`, after rendering the row, if `String(row.rid) === String(selectedSiteRid)`, emit:

```html
<tr class="cg-site-matrix-detail-row">
  <td colspan="${1 + visibleDays.length}">
    ${renderSiteDetail({ site: row, selectedDate: null, selectedEndDate: null })}
  </td>
</tr>
```

This requires `rowHtml` to receive the full `row` object (already has it) and to thread the per-row reservable through `renderSiteDetail`. Since `renderSiteMatrix` already has `reservables`, no new data plumbing — just import `renderSiteDetail` into `site-matrix.js`.

**Removal.** Delete the `renderSelectedSiteDetail(ctx)` block from `renderShell` in `availability-week.js`. The detail no longer lives outside the matrix.

**Click handling.** In `availability-week.js` `onRootClick`:

- New branch for `[data-site-header-rid]`: toggle `ctx.selectedSiteRid` — set to the new rid if different, null if clicking the same row's header.
- Existing `[data-site-detail-close]` branch keeps working (just clears `selectedSiteRid`).
- Remove the existing branch that read `data-site-detail-rid` from cells; cells no longer open details.

**CSS.** New rule:

```css
.cg-site-matrix-detail-row > td {
  padding: var(--cg-site-detail-padding, 12px);
  background: var(--cg-site-detail-bg, var(--surface-2, #fafafa));
  border-bottom: 1px solid var(--cg-border, #e5e7eb);
}
.cg-site-matrix-detail-row .cg-site-detail {
  /* override existing detail panel margins so it sits flush in the cell */
  margin: 0;
}
```

## Change 3 — Cell two-tap booking

A click on an "Open" cell arms it; a second click on the armed cell opens the reservation URL in a new tab. Closed and Full cells have no click action.

**State.** New context field:

```js
ctx.armedBook = null  // { rid: string, date: string } | null
```

**Cell rendering.** In `site-matrix.js` `cellHtml`:

- If `state.kind !== 'available'`, render a plain `<td>` (no button, no data attributes). The cell shows the "Closed" / "Full" label as before.
- If `state.kind === 'available'`:
  - The button carries `data-book-rid="${rowRid(row)}"` and `data-book-date="${day.date}"`.
  - Pass an `armed` flag in to `cellHtml` (computed from `armedBook` against this row+date).
  - Armed → button gets class `is-armed`, label is `${bookingLabel(row)}` (e.g. "Book on Recreation.gov"), aria-label is updated.
  - Not armed → label remains "Open".

`bookingLabel(row)` is the same helper as today in `site-detail.js`, moved to `web/availability/booking-links.js` (which already hosts `reservationUrlFromTemplate`).

**Click handling.** In `availability-week.js` `onRootClick`, before the existing branches:

```
const bookBtn = tgt.closest('[data-book-rid]')
if (bookBtn) {
  const rid = bookBtn.getAttribute('data-book-rid')
  const date = bookBtn.getAttribute('data-book-date')
  const armed = ctx.armedBook && ctx.armedBook.rid === rid && ctx.armedBook.date === date
  if (armed) {
    const site = ctx.sites.find((s) => String(s.rid) === String(rid))
    const url = site && reservationUrlFromTemplate(site, { startDate: date, endDate: stayEndDate(ctx, date) })
    if (url) window.open(url, '_blank', 'noreferrer')
    ctx.armedBook = null
    rerender(ctx)
    return
  }
  ctx.armedBook = { rid, date }
  rerender(ctx)
  return
}
// Any other click path clears the armed cell.
if (ctx.armedBook) {
  ctx.armedBook = null
  // fall through; rerender happens below if another branch needs it
}
```

Also clear `armedBook` in `onRootScroll` (when the matrix scrolls), in `jumpMatrixToToday`, and on filter changes — anywhere we already rerender for a different reason, drop the armed state.

**No URL resolvable.** If `reservationUrlFromTemplate` returns falsy, the second tap is a no-op and we clear `armedBook`. The cell still arms on first tap; we don't try to detect "no URL" up front because that's a code-path duplication and the failure mode is rare. The user sees the armed label, taps again, nothing happens — acceptable degraded behavior. Future improvement: inspect the template at render time and skip arming.

**CSS.**

```css
.cg-site-matrix-cell-button.is-armed {
  background: var(--cg-armed-bg, #1f6feb);
  color: var(--cg-armed-fg, #fff);
  font-weight: 600;
}
```

## Out of scope

- Sticky-row pinning, side-panel detail, or floating popovers (rejected during brainstorming).
- Auto-detecting unavailable booking URLs at render time.
- Mobile touch behavior is treated as click — `pointerdown`/`pointerup` semantics not customized.
- Server-side changes (skeleton uses existing `requestPoiAvailability`; no new endpoint).

## Test surface

- Manual QA in the campground drawer: scroll right, verify the next-week skeleton appears immediately and resolves without a layout shift.
- Click a site name → detail row appears under that row; click another site → previous detail collapses and new one appears under its row; click the same site → detail collapses.
- Click an open cell → label changes to "Book on Recreation.gov" (or appropriate agency); click again → new tab opens with the templated URL. Click any other cell or scroll → arming clears.
- Closed/Full cells: clicking does nothing.
- Filter change while a cell is armed: arming clears.
- Watch refresh / freshness rerender while a site detail is expanded: detail stays expanded.
