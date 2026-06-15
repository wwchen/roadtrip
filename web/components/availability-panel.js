import { escapeHtml, expanderButton } from './result-table.js';

export function availabilityPanelHtml(rid, state, { colspan = 5 } = {}) {
  const expanded = !!state?.expanded;
  const query = state?.query || defaultAvailabilityQuery();
  const url = reservableAvailabilityUrl(rid, query);
  const result = expanded ? availabilityResultHtml(state) : '';
  return `
    <tr class="availability-row${expanded ? ' is-expanded' : ''}" data-panel-rid="${escapeHtml(rid)}">
      <td colspan="${colspan}">
        <div class="availability-panel">
          <div class="sub-heading">
            <div class="sub-title">
              ${expanderButton({
                action: 'toggle-availability',
                idName: 'rid',
                id: rid,
                label: 'Availability',
                expanded,
              })}
              <span class="muted">Query availability for this reservable</span>
            </div>
            <span class="mono muted">${escapeHtml(url)}</span>
          </div>
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
        <input name="start" type="date" value="${escapeHtml(query.start)}">
      </label>
      <label>
        Days
        <input name="days" type="number" min="1" max="60" value="${escapeHtml(query.days)}">
      </label>
      <label>
        Min nights
        <input name="min_nights" type="number" min="1" max="31" value="${escapeHtml(query.minNights)}">
      </label>
      <label class="availability-force">
        <input name="force" type="checkbox"${query.force ? ' checked' : ''}>
        Force refresh
      </label>
      <label>
        Poll date
        <input name="target_date" type="date" value="${escapeHtml(query.start)}">
      </label>
      <label>
        Cadence
        <input name="cadence" type="number" min="5" step="5" value="300">
      </label>
      <label class="availability-force">
        <input name="stop_when_triggered" type="checkbox" checked>
        Stop on match
      </label>
      <div class="actions">
        <button class="primary" type="submit"${loading ? ' disabled' : ''}>Query</button>
        <button type="button" data-action="create-availability-poller"${loading ? ' disabled' : ''}>Create poller</button>
      </div>
    </form>
  `;
}

export function availabilityResultHtml(state) {
  if (state.loading) {
    return '<div class="availability-summary">Loading availability...</div>';
  }
  if (state.error) {
    return `<div class="availability-summary error">${escapeHtml(state.error)}</div>`;
  }
  if (!state.data) {
    return availabilityPollerMessageHtml(state) +
      '<div class="availability-summary">Edit query parameters, then run the request.</div>';
  }
  const body = state.data;
  const days = Array.isArray(body.availability) ? body.availability : [];
  const pills = days.slice(0, 14).map(dayPillHtml).join('');
  const remainder = days.length > 14 ? `<span class="muted">+${days.length - 14} more</span>` : '';
  return `
    <div class="availability-result">
      ${availabilityPollerMessageHtml(state)}
      <div class="availability-summary">
        <strong>${escapeHtml(body.summary || body.state || 'Availability response')}</strong>
        ${body.provider ? ` / ${escapeHtml(body.provider)}` : ''}
      </div>
      <div class="availability-days">${pills}${remainder}</div>
      <details class="json-details">
        <summary><span class="action-icon inline" aria-hidden="true"></span><span>JSON</span></summary>
        <pre>${escapeHtml(JSON.stringify(body, null, 2))}</pre>
      </details>
    </div>
  `;
}

export function availabilityQueryFromForm(formEl) {
  const data = new FormData(formEl);
  return {
    start: String(data.get('start') || '').trim(),
    days: String(data.get('days') || '7').trim() || '7',
    minNights: String(data.get('min_nights') || '1').trim() || '1',
    force: data.get('force') === 'on',
  };
}

export function availabilityPollerFromForm(formEl) {
  const data = new FormData(formEl);
  const start = String(data.get('start') || '').trim();
  const targetDate = String(data.get('target_date') || '').trim() || start;
  return {
    target_dates: [targetDate],
    min_nights: numberString(data.get('min_nights'), '1'),
    cadence: numberString(data.get('cadence'), '300'),
    trigger_actions: ['notify_slack'],
    stop_when_triggered: data.get('stop_when_triggered') === 'on',
    force: data.get('force') === 'on',
  };
}

export function defaultAvailabilityQuery() {
  return {
    start: utcYmd(new Date()),
    days: '7',
    minNights: '1',
    force: false,
  };
}

function reservableAvailabilityUrl(rid, query) {
  const params = new URLSearchParams({
    days: String(query.days || 7),
    start: query.start || utcYmd(new Date()),
    min_nights: String(query.minNights || 1),
  });
  if (query.force) params.set('force', 'true');
  return `/api/reservable/${encodeURIComponent(rid)}/availability?${params}`;
}

function availabilityPollerMessageHtml(state) {
  if (state.pollerLoading) {
    return '<div class="availability-summary">Creating poller...</div>';
  }
  if (state.pollerError) {
    return `<div class="availability-summary error">${escapeHtml(state.pollerError)}</div>`;
  }
  if (state.poller) {
    const id = state.poller.poller?.id;
    const href = id ? `/pollers` : '/pollers';
    return `
      <div class="availability-summary">
        Poller created${id ? `: <a href="${href}">#${escapeHtml(id)}</a>` : ''}.
      </div>
    `;
  }
  return '';
}

function dayPillHtml(day) {
  const status = String(day.status || '').toLowerCase();
  const cls = ['available', 'partial'].includes(status) ? status : '';
  const count = `${Number(day.available_count || 0)} of ${Number(day.total || 0)}`;
  return `
    <span class="day-pill ${cls}">
      <span>${escapeHtml(day.date || '')}</span>
      <span>${escapeHtml(status || 'unknown')}</span>
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

function numberString(value, fallback) {
  const text = String(value || fallback).trim();
  const n = Number(text);
  return Number.isFinite(n) ? n : Number(fallback);
}
