// Day-detail panel: renders the status line + CTAs for the currently
// selected day. The action is a generic availability watch; provider-specific
// booking links live on campsite rows.
//
// Pure renderer; click handling lives in availability-week.js.

import { escapeHtml } from '../core.js';
import { availableCount, campsiteCount } from './day-fields.js';
import {
  availabilityStatusMeta,
  normalizeAvailabilityStatus,
} from '../utils/availability-status.js';

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
  const total = campsiteCount(day);
  const count = availableCount(day);
  const meta = availabilityStatusMeta(day.status);
  if (meta.value === 'available') {
    return `<span class="${meta.detailClass}">${meta.text}</span> · ${count} of ${total} sites`;
  }
  return `<span class="${meta.detailClass}">${meta.text}</span>`;
}

function renderActions({ day, watching, canWatch }) {
  const parts = [];

  // Watch toggle: hidden on closed days because there is no available inventory
  // state to monitor.
  const status = normalizeAvailabilityStatus(day.status);
  const canAlert = status !== 'closed' && status !== 'unknown' && Boolean(canWatch);
  if (canAlert) {
    parts.push(
      watching
        ? `<button type="button" class="cg-btn cg-btn-secondary cg-day-alert" data-state="watching">Watching - tap to remove</button>`
        : `<button type="button" class="cg-btn cg-btn-primary cg-day-alert" data-state="set">Set watch</button>`,
    );
  } else if (!canWatch) {
    parts.push(`<span class="cg-day-detail-meta">Watches are not available for this campground.</span>`);
  } else {
    parts.push(`<span class="cg-day-detail-meta">No online openings to watch for this day.</span>`);
  }

  return parts.join('');
}
