// 7-day availability grid renderer. Pure-ish — given a window of per-day
// classifications + UI state (selected day, watched dates), produces the
// HTML for the cells. Click handling lives in availability-week.js, which
// owns the AbortController and rerender loop.
//
// The BE wire shape is `available_count` (snake_case). We accept both
// `available_count` and `availableCount` so the renderer stays robust if
// the response shape ever drifts back to camelCase via a wrapper.

import { escapeHtml } from '../core.js';

const DOW_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const WEEK_DAYS = 7;

/**
 * Render the 7-cell grid as a string.
 *
 * @param {object} args
 * @param {Array}  args.days           Per-day classifications from /api/campsite/availability.
 * @param {string} args.todayIso       Today as YYYY-MM-DD (UTC).
 * @param {string|null} args.selectedDate
 * @param {Set<string>} args.watchedDates  Dates the user has alerts on.
 */
export function renderWeekGrid({ days, todayIso, selectedDate, watchedDates }) {
  const cells = days.map((d) => {
    const date = d.date;
    const dow = new Date(date + 'T00:00:00Z').getUTCDay();
    const dowLabel = DOW_LABELS[dow];
    const dayNum = parseInt(date.slice(8, 10), 10);
    const availLabel = renderAvailLabel(d);
    const watching = watchedDates.has(date);
    const classes = [
      'cg-day',
      `cg-day-${d.status || 'closed'}`,
      date === todayIso ? 'cg-day-today' : '',
      date === selectedDate ? 'cg-day-selected' : '',
      watching ? 'cg-day-watching' : '',
    ]
      .filter(Boolean)
      .join(' ');
    return `
      <button type="button" class="${classes}" data-date="${date}" aria-label="${escapeHtml(`${date} — ${d.status}`)}">
        <div class="cg-day-dow">${dowLabel}</div>
        <div class="cg-day-num">${dayNum}</div>
        <div class="cg-day-avail">${escapeHtml(availLabel)}</div>
      </button>
    `;
  });
  return `<div class="cg-week">${cells.join('')}</div>`;
}

/** Render the loading skeleton — same 7-cell shape so layout doesn't jump. */
export function renderWeekSkeleton() {
  const cell = `<div class="cg-day cg-day-skeleton">
    <div class="cg-day-dow">·</div>
    <div class="cg-day-num">·</div>
    <div class="cg-day-avail">·</div>
  </div>`;
  return `<div class="cg-week" aria-busy="true">${cell.repeat(WEEK_DAYS)}</div>`;
}

/**
 * The label inside each cell. Status-driven rather than count-driven, since
 * the count alone is ambiguous (0 could be 'fully booked' or 'closed').
 *
 * Reads both snake_case (BE wire) and camelCase (defensive). Pluralizes the
 * unit so "1 site" / "5 sites" reads naturally. Falls back to a status-only
 * label when the count is missing rather than printing 'undefined'.
 */
function renderAvailLabel(day) {
  const status = day.status || 'closed';
  if (status === 'closed') return 'closed';
  if (status === 'booked') return 'full';
  const count = day.available_count ?? day.availableCount;
  if (count == null) {
    // We know status is available/partial but the BE didn't ship a count —
    // tell the user what we do know rather than printing nothing.
    return status === 'partial' ? 'some open' : 'open';
  }
  return `${count} ${count === 1 ? 'site' : 'sites'}`;
}
