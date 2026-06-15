import { apiCallLabel, escapeHtml, expanderButton, renderRow, renderTable } from './result-table.js';

export function availabilityPanelHtml(rid, state, { colspan = 5 } = {}) {
  const expanded = !!state?.expanded;
  const query = state?.query || defaultAvailabilityQuery();
  const result = expanded ? availabilityResultHtml(rid, state) : '';
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
            ${apiCallLabel({ method: 'POST', path: '/api/reservables/availability/query' })}
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
      <div class="actions">
        <button class="primary" type="submit"${loading ? ' disabled' : ''}>Query</button>
      </div>
    </form>
  `;
}

export function availabilityResultHtml(rid, state) {
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
  const table = availabilityDaysTableHtml(rid, days.slice(0, 14), state);
  const remainder = days.length > 14 ? `<div class="muted">+${days.length - 14} more</div>` : '';
  return `
    <div class="availability-result">
      <div class="availability-summary">
        <strong>${escapeHtml(body.summary || body.state || 'Availability response')}</strong>
        ${body.provider ? ` / ${escapeHtml(body.provider)}` : ''}
      </div>
      ${table}
      ${remainder}
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

export function availabilityPollerFromQuery(query, targetDate) {
  return {
    target_dates: [targetDate || query.start],
    min_nights: numberString(query.minNights, '1'),
    cadence: 300,
    trigger_actions: ['notify_slack'],
    stop_when_triggered: true,
    force: !!query.force,
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

function availabilityDaysTableHtml(rid, days, state) {
  const columns = [
    {
      label: 'Date',
      colClass: 'col-date',
      className: 'mono',
      render: (day) => escapeHtml(day.date || ''),
    },
    {
      label: 'Status',
      colClass: 'col-status',
      render: (day) => statusPill(day.status),
    },
    {
      label: 'Available',
      colClass: 'col-available',
      render: (day) => countHtml(day),
    },
    {
      label: 'Actions',
      colClass: 'col-actions',
      render: (day) => dayActionsHtml(rid, day, state),
    },
  ];
  return renderTable({
    columns,
    rows: days,
    className: 'availability-days-table',
    wrapClassName: 'availability-days-table-wrap table-wrap',
    rowRenderer: (day) =>
      renderRow(columns, day, {
        className: `availability-day-result-row ${availabilityDayClass(day)}`.trim(),
      }),
  });
}

function dayActionsHtml(rid, day, state) {
  const date = String(day.date || '');
  const pollerState = state?.pollersByDate?.[date] || {};
  return `
    <div class="availability-day-action">
      ${pollerActionHtml(rid, date, pollerState)}
      ${logsActionHtml(rid, date)}
    </div>
  `;
}

function countHtml(day) {
  return escapeHtml(`${Number(day.available_count || 0)} of ${Number(day.total || 0)}`);
}

function statusPill(status) {
  const value = String(status || 'unknown');
  return `<span class="pill ${escapeHtml(statusClass(value))}">${escapeHtml(value)}</span>`;
}

function availabilityDayClass(day) {
  const status = String(day.status || '').toLowerCase();
  return ['available', 'partial'].includes(status) ? status : '';
}

function statusClass(status) {
  const value = String(status || '').toLowerCase();
  return ['available', 'partial', 'booked', 'closed', 'unknown'].includes(value) ? value : 'unknown';
}

function pollerActionHtml(rid, date, pollerState) {
  if (pollerState.loading) {
    return '<button type="button" disabled>Creating...</button>';
  }
  if (pollerState.error) {
    return `
      <button
        type="button"
        data-action="create-availability-poller"
        data-rid="${escapeHtml(rid)}"
        data-target-date="${escapeHtml(date)}"
      >Retry poller</button>
      <span class="availability-row-error">${escapeHtml(pollerState.error)}</span>
    `;
  }
  if (pollerState.poller) {
    const id = pollerState.poller.poller?.id || pollerState.poller.id;
    const label = id ? `Poller #${id}` : 'Poller created';
    return `<a class="link-chip" href="/pollers"><span class="chip-kind page">Page</span><span class="link-text">${escapeHtml(label)}</span></a>`;
  }
  return `
    <button
      type="button"
      data-action="create-availability-poller"
      data-rid="${escapeHtml(rid)}"
      data-target-date="${escapeHtml(date)}"
      ${date ? '' : 'disabled'}
    >Create poller</button>
  `;
}

function logsActionHtml(rid, date) {
  const params = new URLSearchParams({
    rid,
    target_date: date,
    limit: '100',
  });
  return `
    <a class="link-chip" href="/logs?${params}">
      <span class="chip-kind page">Page</span>
      <span class="link-text">View logs</span>
    </a>
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
