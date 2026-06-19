# Availability Panel UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three independent UX changes to the campground drawer's "Sites by date" matrix: (1) skeleton columns when extending the date window so the layout doesn't jump; (2) inline expansion row for site details under the clicked row; (3) two-tap booking flow on cells that opens the agency reservation URL.

**Architecture:** All client-side. State lives in `availability-week.js`'s `ctx`; rendering happens in `site-matrix.js` (pure HTML strings). No backend changes, no API changes, no new dependencies. Existing booking-link helper (`web/availability/booking-links.js`) gains a small `bookingLabel(row)` export.

**Tech Stack:** Vanilla JS modules, plain HTML strings, no test framework on the web side. Verification is `node --check` for syntax, plus manual browser QA via Tilt (`tilt up`).

**Spec:** `docs/superpowers/specs/2026-06-17-availability-panel-ux-design.md`

---

## File map

**Modified:**
- `web/availability/site-matrix.js` — placeholder day rendering for headers + cells; replace `data-site-detail-rid` on the site header button with `data-site-header-rid`; replace cell click attrs with `data-book-rid` / `data-book-date`; emit detail expansion `<tr>` after the selected row; render closed/full cells without buttons; thread armed-cell flag through `cellHtml`.
- `web/availability/availability-week.js` — add `ctx.matrixPendingDays`, `ctx.armedBook`; new `matrixAvailabilityDaysWithPending` helper; set/clear pending in `fetchMoreMatrixDays`; remove `renderSelectedSiteDetail` standalone block; new click branches for `data-site-header-rid` and `data-book-rid`; clear `armedBook` on scroll/filter/today-jump.
- `web/availability/booking-links.js` — add `bookingLabel(row)` helper.
- `web/availability/site-detail.js` — re-export `bookingLabel` and use it internally so cells and detail panel share the same agency-naming logic.
- `web/components/catalog.css` — new rules `.cg-site-matrix-detail-row`, `.cg-site-matrix-cell-button.is-armed`, optional placeholder header tweaks.

**No new files.**

---

## Conventions

- The repo has no JS unit-test framework for `web/`. Each task ends with `node --check <file>` for the touched files (catches syntax/typo errors) plus a manual verification step.
- Commits use the existing repo style: imperative subject under ~70 chars, no body unless the why is non-obvious. Co-author trailer is fine but not required for solo work — match the surrounding history.
- One commit per task. If a task balloons, split it; do not batch.
- Branch is already `availability-panel-ux` (created during the brainstorm). All commits land there.

---

## Task 1: Add `bookingLabel` to the shared booking-links helper

The cell needs the same "Book on Recreation.gov" label that today only the site detail panel computes. Move that helper out of `site-detail.js` so both consumers share it.

**Files:**
- Modify: `web/availability/booking-links.js`
- Modify: `web/availability/site-detail.js`

- [ ] **Step 1: Add `bookingLabel`, `agencyLabel`, and supporting helpers to `booking-links.js`**

Append to `web/availability/booking-links.js` (after the existing exports, keep file contents otherwise unchanged):

```js
export function bookingLabel(row) {
  const agency = agencyLabel(row);
  return agency ? `Book on ${agency}` : 'Book';
}

export function agencyLabel(row) {
  const template = reservationUrlTemplateOf(row);
  const host = hostFromUrl(template);
  if (host === 'recreation.gov' || host === 'www.recreation.gov') return 'Recreation.gov';
  if (host === 'reservation.pc.gc.ca') return 'Parks Canada';
  if (host === 'camping.bcparks.ca' || host === 'discovercamping.ca') return 'BC Parks';
  if (host === 'washington.goingtocamp.com') return 'Washington State Parks';

  const vendor = String(row?.vendor || '').toLowerCase();
  if (vendor === 'recgov') return 'Recreation.gov';
  if (vendor === 'aspira_pc') return 'Parks Canada';
  if (vendor === 'aspira_bc') return 'BC Parks';
  if (vendor === 'aspira_wa') return 'Washington State Parks';
  if (vendor.startsWith('aspira_')) return 'Aspira';
  return labelFromHost(host) || humanizeAgency(vendor);
}

function reservationUrlTemplateOf(row) {
  const raw = row?.reservation_url_template;
  return typeof raw === 'string' ? raw.trim() : '';
}

function hostFromUrl(url) {
  if (!url || typeof url !== 'string') return '';
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return '';
  }
}

function labelFromHost(host) {
  const base = String(host || '').replace(/^www\./, '').split('.')[0];
  return base ? humanizeAgency(base) : '';
}

function humanizeAgency(key) {
  return String(key)
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\b\w/g, (char) => char.toUpperCase());
}
```

Note: `reservationUrlTemplateOf` duplicates the existing private `reservationUrlTemplate` in this file because it's already private and renaming would be churn. Both call sites continue to read `row.reservation_url_template`.

- [ ] **Step 2: Replace local helpers in `site-detail.js` with the shared import**

In `web/availability/site-detail.js`:

Update the import line near the top from:

```js
import { reservationUrlFromTemplate } from './booking-links.js';
```

to:

```js
import { reservationUrlFromTemplate, bookingLabel as sharedBookingLabel, agencyLabel as sharedAgencyLabel } from './booking-links.js';
```

Replace the local `bookingLabel(site, url)` function (currently around line 58) with a one-line wrapper that delegates to the shared helper, and delete the now-unused local `agencyLabel`, `hostFromUrl`, and `labelFromHost` functions:

```js
function bookingLabel(site, _url) {
  return sharedBookingLabel(site);
}
```

Search the file for any remaining references to the deleted helpers (`agencyLabel`, `hostFromUrl`, `labelFromHost`) and confirm zero matches before committing. Leave `humanize` alone — it's still used by `renderFacts`/feature labels and is unrelated.

- [ ] **Step 3: Syntax check**

Run: `node --check web/availability/booking-links.js && node --check web/availability/site-detail.js`
Expected: no output (success).

- [ ] **Step 4: Commit**

```bash
git add web/availability/booking-links.js web/availability/site-detail.js
git commit -m "Share bookingLabel between cells and detail panel"
```

---

## Task 2: Add skeleton columns during week extension (Change 1)

Reserve column slots in the matrix the moment `fetchMoreMatrixDays` starts a request. Real data replaces the placeholders when the response lands; column count stays constant.

**Files:**
- Modify: `web/availability/availability-week.js`
- Modify: `web/availability/site-matrix.js`

- [ ] **Step 1: Add `matrixPendingDays` to the context**

In `web/availability/availability-week.js`, in `makeContext`, add the field next to `matrixDays` (around line 76):

```js
    matrixDays: null,
    matrixPendingDays: null,
    matrixLoading: false,
```

In `resetMatrixRange` (around line 678), clear it too:

```js
function resetMatrixRange(ctx) {
  ctx.matrixDays = null;
  ctx.matrixPendingDays = null;
  ctx.matrixLoading = false;
  ctx.matrixEnd = false;
  ctx.matrixError = null;
  ctx.matrixScrollLeft = 0;
  ctx.matrixRequestSeq += 1;
}
```

- [ ] **Step 2: Set/clear pending days in `fetchMoreMatrixDays`**

Modify `fetchMoreMatrixDays` (starts around line 496). Set `ctx.matrixPendingDays` after computing `nextStart`, and clear it in the `finally` block. The new function body:

```js
async function fetchMoreMatrixDays(ctx) {
  const visibleDays = matrixAvailabilityDays(ctx);
  const lastDay = visibleDays[visibleDays.length - 1];
  if (!lastDay?.date) return;
  const nextStart = addDays(parseIsoDate(lastDay.date), 1);
  const requestSeq = ++ctx.matrixRequestSeq;
  ctx.matrixLoading = true;
  ctx.matrixError = null;
  ctx.matrixPendingDays = Array.from({ length: WEEK_DAYS }, (_, i) =>
    isoDate(addDays(nextStart, i)),
  );
  rerender(ctx);
  try {
    const resp = await requestPoiAvailability(ctx.poiId, {
      startDate: isoDate(nextStart),
      endDate: isoDate(addDays(nextStart, WEEK_DAYS)),
      signal: ctx.signal,
    });
    if (ctx.signal?.aborted) return;
    if (requestSeq !== ctx.matrixRequestSeq) return;
    if (!resp.ok) {
      const json = await resp.json().catch(() => null);
      if (json?.error === 'bad_date_window') {
        ctx.matrixEnd = true;
      } else {
        ctx.matrixError = json?.error || `HTTP ${resp.status}`;
      }
      return;
    }
    const json = await resp.json();
    const nextDays = Array.isArray(json.availability) ? json.availability : [];
    const merged = mergeAvailabilityDays(visibleDays, nextDays);
    ctx.matrixDays = merged;
    ctx.matrixEnd = merged.length === visibleDays.length || nextDays.length < WEEK_DAYS;
    if (json.cache) ctx.cacheBlock = json.cache;
  } catch (e) {
    if (e.name === 'AbortError') return;
    if (ctx.signal?.aborted) return;
    if (requestSeq !== ctx.matrixRequestSeq) return;
    ctx.matrixError = e.message || 'network';
  } finally {
    if (requestSeq === ctx.matrixRequestSeq) {
      ctx.matrixLoading = false;
      ctx.matrixPendingDays = null;
      rerender(ctx);
    }
  }
}
```

- [ ] **Step 3: Add `matrixAvailabilityDaysWithPending` and use it in `renderAvailabilitySurface`**

In `web/availability/availability-week.js`, replace the call inside `renderAvailabilitySurface` so it uses the new helper:

```js
function renderAvailabilitySurface(ctx) {
  if (ctx.state !== 'success') return renderBody(ctx);
  const days = matrixAvailabilityDaysWithPending(ctx);
  return renderSiteMatrix({
    state: ctx.sitesState,
    reservables: ctx.sites,
    days,
    error: ctx.sitesError,
    selectedDate: null,
    siteColumnWidth: ctx.siteColumnWidth,
    filters: ctx.matrixFilters,
    selectedSiteRid: ctx.selectedSiteRid,
    loadingMore: ctx.matrixLoading,
    loadMoreError: ctx.matrixError,
    showToday: shouldShowMatrixToday(ctx),
  });
}
```

Add the helper near `matrixAvailabilityDays` (around line 687):

```js
function matrixAvailabilityDaysWithPending(ctx) {
  const real = matrixAvailabilityDays(ctx);
  const pending = Array.isArray(ctx.matrixPendingDays) ? ctx.matrixPendingDays : [];
  if (pending.length === 0) return real;
  return [...real, ...pending.map((date) => ({ date, placeholder: true }))];
}
```

- [ ] **Step 4: Render placeholder headers and cells in `site-matrix.js`**

In `web/availability/site-matrix.js`, modify `dateHeaderHtml` to render a non-clickable shimmer header when `day.placeholder`:

```js
function dateHeaderHtml(day) {
  const date = day.date;
  if (day.placeholder) {
    return `
      <th scope="col" class="cg-site-matrix-date cg-site-matrix-skeleton-cell">
        <span class="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-meta"></span>
      </th>
    `;
  }
  const parsed = new Date(`${date}T00:00:00Z`);
  const dow = DOW_LABELS[parsed.getUTCDay()] || '';
  const dayNum = parseInt(date.slice(8, 10), 10);
  return `
    <th scope="col" class="cg-site-matrix-date">
      <button type="button" class="cg-site-matrix-date-button" data-matrix-date="${escapeHtml(date)}">
        <span>${escapeHtml(dow)}</span>
        <strong>${Number.isFinite(dayNum) ? dayNum : escapeHtml(date)}</strong>
      </button>
    </th>
  `;
}
```

Modify `cellHtml` to short-circuit for placeholder days. Insert at the top of the function:

```js
function cellHtml({ row, day, availableIds, selectedDate, siteLabel }) {
  if (day.placeholder) {
    return `
      <td class="cg-site-matrix-cell cg-site-matrix-skeleton-cell">
        <span class="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-pill"></span>
      </td>
    `;
  }
  const state = cellState(row, day, availableIds);
  // ... existing body unchanged
```

(Leave the rest of `cellHtml` exactly as it is — Task 3 will revisit it.)

The skeleton CSS classes (`cg-site-matrix-skeleton-cell`, `cg-site-matrix-skeleton-bar`, `cg-site-matrix-skeleton-pill`, `cg-site-matrix-skeleton-meta`) already exist in `web/components/catalog.css` — no new CSS needed for this task.

- [ ] **Step 5: Drop the now-redundant "Loading next week..." footer**

In `web/availability/site-matrix.js`, change `renderLoadMoreStatus` so it only renders the error variant (placeholders convey loading now):

```js
function renderLoadMoreStatus({ loadingMore, loadMoreError }) {
  if (loadMoreError) {
    return `<div class="cg-site-matrix-load-status cg-site-matrix-error">${escapeHtml(loadMoreError)}</div>`;
  }
  return '';
}
```

The function still receives `loadingMore` because callers pass it; ignoring it here is fine and keeps the call sites stable. The `loadingMore` "Loading..." status pill in the matrix head (around line 194-198 of site-matrix.js) also stays — it's small and serves screen readers.

- [ ] **Step 6: Syntax check**

Run: `node --check web/availability/availability-week.js && node --check web/availability/site-matrix.js`
Expected: no output.

- [ ] **Step 7: Manual verification**

Run `tilt up` and open the site (port from Tiltfile). Open a campground drawer, scroll right on the "Sites by date" matrix to within ~140px of the right edge.

Expected:
- 7 shimmer columns appear immediately past the existing days.
- Scroll position does not jump.
- When the fetch completes (~1-3s), the shimmer columns are replaced in place by real data; total scrollWidth stays the same in that frame.
- If the fetch fails or returns `bad_date_window`, the shimmer columns disappear and either the error banner shows or `matrixEnd` flips silently (no more loads).

- [ ] **Step 8: Commit**

```bash
git add web/availability/availability-week.js web/availability/site-matrix.js
git commit -m "Skeleton columns while extending availability matrix"
```

---

## Task 3: Inline detail row under clicked site header (Change 2)

The site header button gets its own data attribute (`data-site-header-rid`); the matrix emits the detail panel as a `<tr>` directly beneath the selected row; the standalone detail block at the top of the availability section is removed.

**Files:**
- Modify: `web/availability/site-matrix.js`
- Modify: `web/availability/availability-week.js`
- Modify: `web/components/catalog.css`

- [ ] **Step 1: Import `renderSiteDetail` into `site-matrix.js`**

At the top of `web/availability/site-matrix.js`, add:

```js
import { renderSiteDetail } from './site-detail.js';
```

- [ ] **Step 2: Switch the site header button to `data-site-header-rid`**

In `siteLabelHtml` (around line 351), replace `data-site-detail-rid` with `data-site-header-rid`:

```js
function siteLabelHtml(row, siteLabel, siteTitle) {
  const loop = typeof row.loop === 'string' ? row.loop.trim() : '';
  const prefix = loop ? `<span class="cg-site-matrix-loop-prefix">${escapeHtml(loop)} / </span>` : '';
  return `
    <button
      type="button"
      class="cg-site-matrix-site-button"
      data-site-header-rid="${escapeHtml(row.rid)}"
      title="${escapeHtml(siteTitle)}"
      aria-label="View details for ${escapeHtml(siteTitle)}"
    >
      <span class="cg-site-matrix-site-title">${prefix}<span class="cg-site-matrix-name">${escapeHtml(siteLabel)}</span></span>
    </button>
  `;
}
```

- [ ] **Step 3: Emit the detail expansion row from `rowHtml`**

Modify `rowHtml` (around line 326) to append a detail `<tr>` when this row is selected:

```js
function rowHtml(row, context) {
  const siteLabel = siteName(row);
  const siteTitle = siteTitleText(row, siteLabel);
  const isSelected = String(row.rid) === String(context.selectedSiteRid);
  const rowClass = isSelected ? ' class="cg-site-matrix-row-selected"' : '';
  const cells = context.visibleDays
    .map((day) =>
      cellHtml({
        availableIds: context.availabilityByDate.get(day.date),
        day,
        row,
        selectedDate: context.selectedDate,
        siteLabel,
      }),
    )
    .join('');
  const detailRow = isSelected
    ? `
      <tr class="cg-site-matrix-detail-row">
        <td colspan="${1 + context.visibleDays.length}">
          ${renderSiteDetail({ site: row, selectedDate: null, selectedEndDate: null })}
        </td>
      </tr>
    `
    : '';
  return `
    <tr${rowClass}>
      <th scope="row" class="cg-site-matrix-site" title="${escapeHtml(siteTitle)}">
        ${siteLabelHtml(row, siteLabel, siteTitle)}
      </th>
      ${cells}
    </tr>
    ${detailRow}
  `;
}
```

- [ ] **Step 4: Remove the standalone detail block from `availability-week.js`**

In `renderShell`, delete the `${renderSelectedSiteDetail(ctx)}` line. Also remove the now-unused `renderSelectedSiteDetail` function and the `selectedMatrixSite` helper (the matrix renders detail itself now). The `import { renderSiteDetail }` line at the top can also be removed if no other consumer remains — search the file to confirm.

The `renderShell` block becomes:

```js
function renderShell(ctx) {
  const selectedDay = selectedAvailabilityDay(ctx);
  const sitesDay = selectedDay && availableCount(selectedDay) > 0 ? selectedDay : null;
  return `
    <section class="cg-availability">
      ${renderAvailabilitySurface(ctx)}
      <div class="cg-freshness">${renderFreshness(ctx)}</div>
      ${renderDetail(ctx)}
      ${renderSiteList({
        state: ctx.sitesState,
        reservables: ctx.sites,
        error: ctx.sitesError,
        expanded: ctx.sitesExpanded,
        selectedDay: sitesDay,
        selectedEndDate: sitesDay ? stayEndDate(ctx, sitesDay.date) : null,
      })}
    </section>
  `;
}
```

- [ ] **Step 5: New click branch for `data-site-header-rid`**

In `onRootClick` (around line 254), add a branch before the existing `data-site-detail-rid` handler. Replace the existing site-detail-rid branch with the new header branch:

```js
  const siteHeaderBtn = tgt.closest('[data-site-header-rid]');
  if (siteHeaderBtn) {
    const rid = siteHeaderBtn.getAttribute('data-site-header-rid');
    if (!rid) return;
    ctx.selectedSiteRid = String(ctx.selectedSiteRid) === String(rid) ? null : rid;
    ctx.selectedSiteDate = null;
    rerender(ctx);
    return;
  }
```

The existing `data-site-detail-rid` handler stays for now — Task 4 removes it when cells stop emitting that attribute. The `[data-site-detail-close]` branch also stays unchanged; clicking the close button inside the inline detail still nulls `selectedSiteRid`.

- [ ] **Step 6: Add CSS for the detail row**

Append to `web/components/catalog.css` (after the existing `.cg-site-matrix-load-status` rule, around line 953):

```css
.cg-site-matrix-detail-row > td {
  padding: 12px;
  background: var(--cg-surface-2, rgba(255,255,255,0.03));
  border-top: 1px solid var(--cg-border);
  border-bottom: 1px solid var(--cg-border);
}

.cg-site-matrix-detail-row .cg-site-detail {
  margin: 0;
  padding-top: 0;
  border-top: 0;
}
```

- [ ] **Step 7: Syntax check**

Run: `node --check web/availability/site-matrix.js && node --check web/availability/availability-week.js`
Expected: no output.

- [ ] **Step 8: Manual verification**

Reload the campground drawer. Click a site name in the matrix.

Expected:
- A detail panel appears as a full-width row directly beneath the clicked row.
- Other rows' positions don't shift except being pushed down by the inserted row's height.
- Click another site → previous detail row collapses, new one opens under the new row.
- Click the same site again → detail row collapses.
- The "Close" button inside the detail panel collapses it.
- (Cells still open the detail at the top via the legacy handler — Task 4 fixes this.)

- [ ] **Step 9: Commit**

```bash
git add web/availability/site-matrix.js web/availability/availability-week.js web/components/catalog.css
git commit -m "Inline site detail as expansion row under clicked header"
```

---

## Task 4: Two-tap booking on cells (Change 3)

Cells stop opening the detail panel. An "Open" cell is now a two-tap button: first tap arms it (label swaps to "Book on Recreation.gov" or the resolved agency), second tap opens the URL in a new tab. Closed/Full cells render without buttons.

**Files:**
- Modify: `web/availability/site-matrix.js`
- Modify: `web/availability/availability-week.js`
- Modify: `web/components/catalog.css`

- [ ] **Step 1: Add `armedBook` to the context**

In `web/availability/availability-week.js` `makeContext`, add next to `selectedSiteRid` (around line 88):

```js
    selectedSiteRid: null,
    selectedSiteDate: null,
    armedBook: null,
```

- [ ] **Step 2: Pass armed-cell info into `renderSiteMatrix`**

In `renderAvailabilitySurface`, add `armedBook: ctx.armedBook` to the props passed to `renderSiteMatrix`:

```js
  return renderSiteMatrix({
    state: ctx.sitesState,
    reservables: ctx.sites,
    days,
    error: ctx.sitesError,
    selectedDate: null,
    siteColumnWidth: ctx.siteColumnWidth,
    filters: ctx.matrixFilters,
    selectedSiteRid: ctx.selectedSiteRid,
    loadingMore: ctx.matrixLoading,
    loadMoreError: ctx.matrixError,
    showToday: shouldShowMatrixToday(ctx),
    armedBook: ctx.armedBook,
  });
```

- [ ] **Step 3: Thread `armedBook` through `renderSiteMatrix` to `cellHtml`**

In `web/availability/site-matrix.js`:

Add the import for `bookingLabel`:

```js
import { bookingLabel } from './booking-links.js';
```

Update the `renderSiteMatrix` signature to accept `armedBook`:

```js
export function renderSiteMatrix({
  state,
  reservables,
  days,
  error,
  selectedDate,
  siteColumnWidth,
  filters = DEFAULT_FILTERS,
  selectedSiteRid = null,
  loadingMore = false,
  loadMoreError = null,
  showToday = true,
  armedBook = null,
}) {
```

Pass `armedBook` into the per-row context where `bodyRows` is built:

```js
  const bodyRows = rows
    .map((row) =>
      rowHtml(row, {
        availabilityByDate,
        selectedDate,
        selectedSiteRid,
        visibleDays,
        armedBook,
      }),
    )
    .join('');
```

In `rowHtml`, forward `armedBook` to `cellHtml`:

```js
  const cells = context.visibleDays
    .map((day) =>
      cellHtml({
        availableIds: context.availabilityByDate.get(day.date),
        day,
        row,
        selectedDate: context.selectedDate,
        siteLabel,
        armedBook: context.armedBook,
      }),
    )
    .join('');
```

- [ ] **Step 4: Rewrite `cellHtml` for the two-tap flow**

Replace `cellHtml` in `web/availability/site-matrix.js` with:

```js
function cellHtml({ row, day, availableIds, selectedDate, siteLabel, armedBook }) {
  if (day.placeholder) {
    return `
      <td class="cg-site-matrix-cell cg-site-matrix-skeleton-cell">
        <span class="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-pill"></span>
      </td>
    `;
  }
  const state = cellState(row, day, availableIds);
  const isSelected = selectedDate === day.date;
  const selectedClass = isSelected ? ' is-selected' : '';
  const aria = `${siteLabel} ${day.date}: ${state.aria}`;

  if (state.kind !== 'available') {
    return `
      <td class="cg-site-matrix-cell cg-site-matrix-cell-${state.kind}${selectedClass}" aria-label="${escapeHtml(aria)}">
        <span class="cg-site-matrix-cell-label">${escapeHtml(state.label)}</span>
      </td>
    `;
  }

  const armed = !!armedBook
    && String(armedBook.rid) === rowRid(row)
    && armedBook.date === day.date;
  const label = armed ? bookingLabel(row) : state.label;
  const armedClass = armed ? ' is-armed' : '';
  const ariaLabel = armed
    ? `${aria}; ${label}, click to open booking page`
    : `${aria}; click to book`;

  return `
    <td class="cg-site-matrix-cell cg-site-matrix-cell-${state.kind}${selectedClass}">
      <button
        type="button"
        class="cg-site-matrix-cell-button${armedClass}"
        data-book-rid="${escapeHtml(rowRid(row))}"
        data-book-date="${escapeHtml(day.date)}"
        aria-label="${escapeHtml(ariaLabel)}"
      >
        ${escapeHtml(label)}
      </button>
    </td>
  `;
}
```

- [ ] **Step 5: Handle the two-tap click in `availability-week.js`**

Add `reservationUrlFromTemplate` to the existing `booking-links` import. At the top of `web/availability/availability-week.js` find the existing imports; if `booking-links.js` isn't imported yet, add:

```js
import { reservationUrlFromTemplate } from './booking-links.js';
```

Replace the entire body of `onRootClick` with the version below. This adds a booking branch as the first check, deletes the dead `[data-site-detail-rid]` branch (cells no longer emit that attribute and the site header now has its own branch from Task 3), and clears `armedBook` whenever a click lands anywhere else. The `wasArmed` flag triggers a final rerender if the click hit empty space — without it, the previously-armed cell would visually stay armed until the next render.

```js
function onRootClick(ctx, e) {
  const tgt = e.target;
  if (!(tgt instanceof Element)) return;

  const bookBtn = tgt.closest('[data-book-rid]');
  if (bookBtn) {
    e.preventDefault();
    const rid = bookBtn.getAttribute('data-book-rid');
    const date = bookBtn.getAttribute('data-book-date');
    if (!rid || !date) return;
    const armed = ctx.armedBook && String(ctx.armedBook.rid) === String(rid) && ctx.armedBook.date === date;
    if (armed) {
      const site = ctx.sites.find((s) => String(s.rid) === String(rid));
      const url = site
        ? reservationUrlFromTemplate(site, { startDate: date, endDate: stayEndDate(ctx, date) })
        : '';
      if (url) window.open(url, '_blank', 'noreferrer');
      ctx.armedBook = null;
    } else {
      ctx.armedBook = { rid: String(rid), date };
    }
    rerender(ctx);
    return;
  }

  const wasArmed = ctx.armedBook != null;
  if (wasArmed) ctx.armedBook = null;

  const matrixDateBtn = tgt.closest('[data-matrix-date], .cg-day[data-date]');
  if (matrixDateBtn) {
    const date = matrixDateBtn.getAttribute('data-matrix-date') || matrixDateBtn.getAttribute('data-date');
    if (!date) return;
    const selected = ctx.selectedDate !== date;
    ctx.selectedDate = selected ? date : null;
    ctx.sitesExpanded = selected;
    if (selected) {
      fetchSites(ctx);
    } else {
      rerender(ctx);
    }
    return;
  }
  const siteHeaderBtn = tgt.closest('[data-site-header-rid]');
  if (siteHeaderBtn) {
    const rid = siteHeaderBtn.getAttribute('data-site-header-rid');
    if (!rid) return;
    ctx.selectedSiteRid = String(ctx.selectedSiteRid) === String(rid) ? null : rid;
    ctx.selectedSiteDate = null;
    rerender(ctx);
    return;
  }
  if (tgt.closest('[data-site-detail-close]')) {
    ctx.selectedSiteRid = null;
    ctx.selectedSiteDate = null;
    rerender(ctx);
    return;
  }
  if (tgt.closest('[data-matrix-today]')) {
    e.preventDefault();
    jumpMatrixToToday(ctx);
    return;
  }
  if (tgt.closest('.cg-refresh')) {
    e.preventDefault();
    fetchWeek(ctx, { force: true });
    return;
  }
  if (tgt.closest('.cg-retry')) {
    e.preventDefault();
    fetchWeek(ctx);
    return;
  }
  const alertBtn = tgt.closest('.cg-day-alert');
  if (alertBtn) {
    e.preventDefault();
    toggleWatch(ctx, alertBtn);
    return;
  }
  const sitesToggle = tgt.closest('.cg-sites-toggle');
  if (sitesToggle && !sitesToggle.disabled) {
    ctx.sitesExpanded = !ctx.sitesExpanded;
    rerender(ctx);
    return;
  }
  if (tgt.closest('.cg-sites-retry')) {
    e.preventDefault();
    fetchSites(ctx);
    return;
  }

  if (wasArmed) rerender(ctx);
}
```

- [ ] **Step 6: Clear arming on scroll and filter changes**

In `onRootScroll` (around line 370), add `ctx.armedBook = null` before the existing checks:

```js
function onRootScroll(ctx, e) {
  const scroll = e.target;
  if (!(scroll instanceof HTMLElement)) return;
  if (!scroll.classList.contains('cg-site-matrix-scroll')) return;
  if (ctx.armedBook) {
    ctx.armedBook = null;
    // Repaint asynchronously to avoid mid-scroll layout work.
    window.requestAnimationFrame?.(() => rerender(ctx));
  }
  ctx.matrixScrollLeft = scroll.scrollLeft;
  if (ctx.state !== 'success') return;
  if (ctx.matrixLoading || ctx.matrixEnd) return;
  const remaining = scroll.scrollWidth - scroll.clientWidth - scroll.scrollLeft;
  if (remaining <= MATRIX_SCROLL_LOAD_THRESHOLD_PX) {
    fetchMoreMatrixDays(ctx);
  }
}
```

In `updateMatrixFilter` (around line 345), clear `armedBook` when a filter actually changes:

```js
function updateMatrixFilter(ctx, key, value) {
  const current = ctx.matrixFilters || {};
  const nextValue = typeof value === 'string' ? value : '';
  if ((current[key] || '') === nextValue) return false;
  ctx.matrixFilters = {
    query: current.query || '',
    loop: current.loop || '',
    type: current.type || '',
    sort: current.sort || 'open',
    [key]: nextValue,
  };
  ctx.armedBook = null;
  return true;
}
```

`jumpMatrixToToday` already gets a rerender — clear armedBook there too:

```js
function jumpMatrixToToday(ctx) {
  const today = startOfTodayUtc();
  ctx.selectedDate = null;
  ctx.sitesExpanded = false;
  ctx.matrixScrollLeft = 0;
  ctx.armedBook = null;
  if (!sameDay(ctx.weekStart, today)) {
    ctx.weekStart = today;
    fetchWeek(ctx);
    return;
  }
  ctx.matrixDays = Array.isArray(ctx.days) ? [...ctx.days] : ctx.matrixDays;
  ctx.matrixEnd = false;
  ctx.matrixError = null;
  rerender(ctx);
}
```

- [ ] **Step 7: Add CSS for the armed cell and the inert closed/full cells**

Append to `web/components/catalog.css` (after `.cg-site-matrix-cell-closed .cg-site-matrix-cell-button`, around line 947):

```css
.cg-site-matrix-cell-button.is-armed {
  background: var(--cg-accent, #4cb96a);
  color: var(--cg-on-accent, #0b1410);
  font-weight: 700;
  white-space: nowrap;
  padding: 0 8px;
}

.cg-site-matrix-cell-button.is-armed:hover {
  background: var(--cg-accent-strong, var(--cg-accent, #4cb96a));
}

.cg-site-matrix-cell-booked .cg-site-matrix-cell-label,
.cg-site-matrix-cell-closed .cg-site-matrix-cell-label {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  min-height: 40px;
  padding: 0 4px;
  font-size: 11px;
  font-weight: 600;
  color: var(--cg-faint);
}
```

(Closed/Full cells now render a `<span>` instead of a `<button>`, so we need a label rule to preserve the existing min-height + centering. The values mirror `.cg-site-matrix-cell-button` from the same file.)

- [ ] **Step 8: Syntax check**

Run: `node --check web/availability/site-matrix.js && node --check web/availability/availability-week.js`
Expected: no output.

- [ ] **Step 9: Manual verification**

Reload the campground drawer.

Expected:
- Click an "Open" cell → cell label becomes "Book on Recreation.gov" (or correct agency for the row's vendor) and the cell is visually emphasized.
- Click that same cell again → a new tab opens at the templated reservation URL.
- Click a different open cell → first cell de-arms, new cell arms.
- Scroll the matrix horizontally while a cell is armed → arming clears.
- Type into the filter search → arming clears.
- Closed and Full cells: hovering shows a non-clickable label; clicking does nothing (and clears any other cell's arming).
- Site header click still opens the inline detail row from Task 3.
- For a row whose `reservation_url_template` is empty/missing: cell still arms on first tap; second tap does nothing visible (URL was empty), arming clears.

- [ ] **Step 10: Commit**

```bash
git add web/availability/site-matrix.js web/availability/availability-week.js web/components/catalog.css
git commit -m "Two-tap booking flow on availability cells"
```

---

## Task 5: End-to-end verification

A consolidated pass over all three changes interacting together.

- [ ] **Step 1: Full QA pass**

With `tilt up` running:

1. Open a recently-modified campground POI drawer (one with reservables).
2. Confirm the matrix renders.
3. Scroll right past the threshold → verify Task 2 skeleton columns appear and resolve cleanly.
4. Click a site name → verify Task 3 inline detail row appears under that row.
5. Click a different site name → previous detail collapses, new one opens.
6. With a detail row open, click an open cell two rows down → cell arms. Click again → new tab opens. Detail row stays open.
7. Click "Today" in the matrix head → arming clears, scroll resets.
8. Open a campground that's `closed_for_season` → no matrix renders, no regressions.
9. Open a campground with no reservables → empty-state message shows, no console errors.

- [ ] **Step 2: Cross-feature regression spot-check**

1. The site-list panel (below the matrix) still expands/collapses on the toggle.
2. Day cell in the heat strip (if visible) still opens the day detail panel.
3. Watch alert button in day detail still toggles correctly.
4. Refresh button in the freshness line still re-fetches the week.

- [ ] **Step 3: PR**

The branch was created during the brainstorming phase (`availability-panel-ux`). After all three task commits land:

```bash
git push -u origin availability-panel-ux
gh pr create --title "Availability matrix UX: skeleton columns, inline detail, two-tap booking" --body-file pr_body.md
rm pr_body.md
```

`pr_body.md` body:

```
## Summary
- Skeleton columns appear immediately when the matrix auto-extends past the visible week, so the layout no longer jumps when the response lands.
- Site detail panel now expands inline as a row directly beneath the clicked site header, instead of rendering above the matrix where it sat below the fold.
- Cells become two-tap booking buttons: first tap arms (label swaps to "Book on Recreation.gov"), second tap opens the templated reservation URL in a new tab. Closed/Full cells become inert.

## Test plan
- [ ] Scroll right on the matrix → skeleton columns appear, no scroll jump when real data lands.
- [ ] Click a site name → detail row appears under that row; clicking another site moves the detail.
- [ ] Click an Open cell twice → new tab opens at the correct vendor URL with start_date/end_date filled in.
- [ ] Click an Open cell, then scroll or type a filter → arming clears.
- [ ] Closed/Full cells → no click action.
- [ ] Closed-for-season and empty-state campgrounds → no regressions.
```

(Per project CLAUDE.md, write the body to a tmp file and use `--body-file`; delete after.)

---

## Self-review notes

- All three spec sections (Change 1/2/3) are covered by Tasks 2/3/4 respectively.
- Task 1 is a prerequisite extracted because Change 3 needs the agency-label helper that today only `site-detail.js` has.
- Method/property names: `ctx.matrixPendingDays`, `ctx.armedBook`, `data-site-header-rid`, `data-book-rid`, `data-book-date`, `bookingLabel`, `agencyLabel`, `matrixAvailabilityDaysWithPending` — used consistently across tasks.
- No placeholders, no "implement later" stubs, no fictional pytest commands — repo has no JS test framework on the web side, so verification is `node --check` plus manual QA, mirroring `2026-06-16-availability-matrix-parity.md`.
- Task 4 rewrites `onRootClick` once with a brief explanation of the `wasArmed` pattern; no dueling drafts.
