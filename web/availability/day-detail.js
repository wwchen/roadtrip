// Day-detail panel: renders the status line + CTAs for the currently
// selected day. The action is a generic availability watch; provider-specific
// booking links live on reservable rows.
//
// Pure renderer; click handling lives in availability-week.js.

import { escapeHtml } from '../core.js';

/**
 * @param {object} args
 * @param {object} args.day           Per-day classification.
 * @param {boolean} args.watching
 * @param {boolean} args.canWatch
 */
export function renderDayDetail({ day, watching, canWatch }) {
  const dateLabel = new Date(day.date + 'T00:00:00Z').toLocaleDateString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  });
  const statusLine = renderStatusLine(day);
  const actions = renderActions({ day, watching, canWatch });

  return `
    <div class="cg-day-detail">
      <div class="cg-day-detail-head">
        <div class="cg-day-detail-date">${escapeHtml(dateLabel)}</div>
        <div class="cg-day-detail-meta">${statusLine}</div>
      </div>
      <div class="cg-day-detail-actions">${actions}</div>
    </div>
  `;
}

function renderStatusLine(day) {
  const total = day.total ?? 0;
  const count = availableCount(day) ?? 0;
  switch (renderStatus(day)) {
    case 'available':
      return `<span class="cg-status-ok">Available</span> · ${count} of ${total} sites`;
    case 'partial':
      if (count === 0) {
        return `<span class="cg-status-partial">Open, no matching sites</span> · ${total} ${total === 1 ? 'site' : 'sites'}`;
      }
      return `<span class="cg-status-partial">Partial</span> · ${count} of ${total} sites`;
    case 'booked':
      return '<span class="cg-status-full">Full</span>';
    case 'closed':
      return '<span class="cg-status-full">Closed</span>';
    default:
      return 'No availability details';
  }
}

function renderStatus(day) {
  const status = day.status || 'closed';
  const count = availableCount(day);
  if (count != null) {
    if (count > 0) return 'available';
    if (status === 'available') return 'booked';
  }
  return status;
}

function availableCount(day) {
  return day.available_count ?? day.availableCount;
}

function renderActions({ day, watching, canWatch }) {
  const parts = [];

  // Watch toggle: hidden on closed days because there is no open inventory
  // state to monitor.
  const canAlert = day.status !== 'closed' && Boolean(canWatch);
  if (canAlert) {
    parts.push(
      watching
        ? `<button type="button" class="cg-btn cg-btn-secondary cg-day-alert" data-state="watching">Watching - tap to remove</button>`
        : `<button type="button" class="cg-btn cg-btn-primary cg-day-alert" data-state="set">Set watch</button>`,
    );
  } else if (!canWatch) {
    parts.push(`<span class="cg-day-detail-meta">Watches are not available for this campground.</span>`);
  } else {
    parts.push(`<span class="cg-day-detail-meta">No openings to watch on a closed day.</span>`);
  }

  return parts.join('');
}
