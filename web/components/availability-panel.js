import { renderSiteMatrix } from '../availability/site-matrix.js';
import { escapeHtml } from './result-table.js';
import {
  availabilityStatusLabel,
  normalizeAvailabilityStatus,
} from '../utils/availability-status.js';

export function availabilityPanelHtml(rid, state, { colspan = 5, row = null } = {}) {
  const expanded = !!state && (state.mode === 'availability' || state.expanded);
  const query = state?.query || defaultAvailabilityQuery();
  const result = expanded ? availabilityResultHtml(state, row, rid) : '';
  return `
    <tr class="availability-row${expanded ? ' is-expanded' : ''}" data-panel-rid="${escapeHtml(rid)}">
      <td colspan="${colspan}">
        <div class="availability-panel">
          ${expanded ? availabilityQueryHtml(rid, query, { loading: !!state?.loading }) : ''}
          ${result}
        </div>
      </td>
    </tr>
  `;
}

export function availabilityQueryHtml(rid, query, { loading = false } = {}) {
  return `
    <form class="availability-controls" data-action="availability-query" data-rid="${escapeHtml(rid)}">
      <label>
        Start
        <input name="start_date" type="date" value="${escapeHtml(query.startDate)}">
      </label>
      <label>
        End
        <input name="end_date" type="date" value="${escapeHtml(query.endDate)}">
      </label>
      <label class="availability-force">
        <input name="force" type="checkbox"${query.force ? ' checked' : ''}>
        Force refresh
      </label>
      <div class="actions">
        <button class="primary" type="submit"${loading ? ' disabled' : ''}>Query</button>
        <button type="button" data-action="create-watch" data-rid="${escapeHtml(rid)}">Create watch</button>
      </div>
    </form>
  `;
}

export function createWatchUrlFromQuery(rid, query) {
  const params = new URLSearchParams({
    reservable_rid: rid,
    start_date: query.startDate,
    end_date: query.endDate,
    cadence_sec: '60',
    trigger_kinds: 'atc',
  });
  return `/watches?${params}`;
}

export function availabilityResultHtml(state, row = null, rid = '') {
  if (state.loading) {
    return '<div class="availability-summary">Loading availability...</div>';
  }
  if (state.error) {
    return `<div class="availability-summary error">${escapeHtml(state.error)}</div>`;
  }
  if (!state.data) {
    return '<div class="availability-summary">Edit query parameters, then run the request.</div>';
  }
  const body = state.data;
  const days = Array.isArray(body.availability) ? body.availability : [];
  const matrix = row ? availabilityMatrixHtml(row, rid, days) : '';
  const pills = matrix ? '' : days.slice(0, 14).map(dayPillHtml).join('');
  const remainder = days.length > 14 ? `<span class="muted">+${days.length - 14} more</span>` : '';
  return `
    <div class="availability-result">
      ${matrix || `<div class="availability-days">${pills}${remainder}</div>`}
      <details class="json-details">
        <summary><span class="action-icon inline" aria-hidden="true"></span><span>JSON</span></summary>
        <pre>${escapeHtml(JSON.stringify(body, null, 2))}</pre>
      </details>
    </div>
  `;
}

function availabilityMatrixHtml(row, rid, days) {
  const visibleDays = normalizeReservableDays(rid, days);
  if (visibleDays.length === 0) return '';
  return renderSiteMatrix({
    state: 'success',
    reservables: [row],
    days: visibleDays,
    error: '',
    selectedDate: null,
    siteColumnWidth: 128,
    filters: {
      query: '',
      loop: '',
      type: '',
      sort: 'site',
    },
    selectedSiteRid: null,
    loadingMore: false,
    loadMoreError: null,
    showToday: false,
  });
}

function normalizeReservableDays(rid, days) {
  return (Array.isArray(days) ? days : [])
    .filter((day) => day?.date)
    .map((day) => {
      const ids = day.available_reservable_ids ?? day.availableReservableIds;
      if (Array.isArray(ids)) return day;
      const available = reservableDayAvailable(day);
      return {
        ...day,
        available_count: available ? 1 : 0,
        total: day.total ?? 1,
        available_reservable_ids: available ? [rid] : [],
      };
    });
}

function reservableDayAvailable(day) {
  return normalizeAvailabilityStatus(day.status) === 'available';
}

export function availabilityQueryFromForm(formEl) {
  const data = new FormData(formEl);
  const startDate = String(data.get('start_date') || '').trim() || utcYmd(new Date());
  return {
    startDate,
    endDate: String(data.get('end_date') || '').trim() || utcYmd(addUtcDays(parseUtcYmd(startDate), 7)),
    force: data.get('force') === 'on',
  };
}

export function defaultAvailabilityQuery() {
  const startDate = utcYmd(new Date());
  return {
    startDate,
    endDate: utcYmd(addUtcDays(parseUtcYmd(startDate), 7)),
    force: false,
  };
}

function dayPillHtml(day) {
  const status = normalizeAvailabilityStatus(day.status);
  const cls = status;
  const count = `${Number(day.available_count || 0)} of ${Number(day.total || 0)}`;
  return `
    <span class="day-pill ${cls}">
      <span>${escapeHtml(day.date || '')}</span>
      <span>${escapeHtml(availabilityStatusLabel(status))}</span>
      <span>${escapeHtml(count)}</span>
    </span>
  `;
}

function utcYmd(date) {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, '0');
  const d = String(date.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function parseUtcYmd(value) {
  return new Date(`${value}T00:00:00Z`);
}

function addUtcDays(date, days) {
  const next = new Date(date);
  next.setUTCDate(date.getUTCDate() + days);
  return next;
}
