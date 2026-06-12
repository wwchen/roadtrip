// Day-detail panel: renders the status line + CTAs for the currently
// selected day. Two CTAs by design — Reserve on rec.gov (deeplink, neutral
// "go look at the source"), and Set alert / Watching ✓ (the confident
// action). Reserve is intentionally secondary so we don't imply
// availability the user can actually book given their filters.
//
// Pure renderer; click handling lives in availability-week.js.

import { escapeHtml } from '../core.js';

const GREEN_AVAILABLE_THRESHOLD = 5;

/**
 * @param {object} args
 * @param {object} args.day           Per-day classification.
 * @param {number} args.minNights
 * @param {boolean} args.watching
 * @param {string|null} args.recgovId Reserve link target. Null hides the link.
 */
export function renderDayDetail({ day, minNights, watching, recgovId }) {
  const dateLabel = new Date(day.date + 'T00:00:00Z').toLocaleDateString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  });
  const stayLabel = `${minNights}-night stay`;
  const statusLine = renderStatusLine(day, stayLabel);
  const actions = renderActions({ day, watching, recgovId });

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

function renderStatusLine(day, stayLabel) {
  const total = day.total ?? 0;
  const count = availableCount(day) ?? 0;
  switch (renderStatus(day)) {
    case 'available':
      return `<span class="cg-status-ok">Available</span> · ${count} of ${total} sites · ${stayLabel}`;
    case 'partial':
      return `<span class="cg-status-partial">Partial</span> · ${count} of ${total} sites · ${stayLabel}`;
    case 'booked':
      return `<span class="cg-status-full">Full</span> · ${stayLabel}`;
    case 'closed':
      return `<span class="cg-status-full">Closed</span> · ${stayLabel}`;
    default:
      return stayLabel;
  }
}

function renderStatus(day) {
  const status = day.status || 'closed';
  const count = availableCount(day);
  if (status === 'partial' && count != null && count >= GREEN_AVAILABLE_THRESHOLD) {
    return 'available';
  }
  return status;
}

function availableCount(day) {
  return day.available_count ?? day.availableCount;
}

function renderActions({ day, watching, recgovId }) {
  const parts = [];

  // Alert toggle — the primary action. Hidden on closed days (campground
  // isn't open; nothing to watch). Hidden when there's no recgovId because
  // alert creation needs a campground_id; future providers will need their
  // own gating signal.
  const canAlert = day.status !== 'closed' && Boolean(recgovId);
  if (canAlert) {
    parts.push(
      watching
        ? `<button type="button" class="cg-btn cg-btn-secondary cg-day-alert" data-state="watching">Watching ✓ — tap to remove</button>`
        : `<button type="button" class="cg-btn cg-btn-primary cg-day-alert" data-state="set">🔔 Set alert</button>`,
    );
  } else if (!recgovId) {
    parts.push(`<span class="cg-day-detail-meta">Alerts coming soon for this provider.</span>`);
  } else {
    parts.push(`<span class="cg-day-detail-meta">No openings to watch on a closed day.</span>`);
  }

  // Reserve deeplink — secondary. Sends the user to rec.gov's calendar
  // pre-filtered to the selected date so they can apply their real
  // equipment/site-type constraints there, not here.
  if (recgovId && day.status !== 'closed') {
    const url = `https://www.recreation.gov/camping/campgrounds/${encodeURIComponent(recgovId)}?startDate=${encodeURIComponent(day.date)}`;
    parts.push(
      `<a class="cg-btn cg-btn-secondary cg-day-reserve" href="${url}" target="_blank" rel="noreferrer">Reserve on rec.gov</a>`,
    );
  }

  return parts.join('');
}
