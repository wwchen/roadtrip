import {
  actionButton,
  apiCallLabel,
  createRow,
  createTable,
  disclosureButton,
  element,
  fragment,
  pill,
  plainLink,
  submitButton,
} from './result-table.js';

export function createAvailabilityPanel(rid, state, { colspan = 5 } = {}) {
  const expanded = !!state?.expanded;
  const query = state?.query || defaultAvailabilityQuery();
  const row = element('tr', {
    className: `availability-row${expanded ? ' is-expanded' : ''}`,
    dataset: { panelRid: rid },
  });
  const cell = element('td', { attrs: { colspan } });
  const panel = element('div', { className: 'availability-panel' });

  panel.append(createAvailabilityHeading(rid, expanded));
  if (expanded) {
    panel.append(createAvailabilityQuery(rid, query, { loading: !!state?.loading }));
    panel.append(createAvailabilityResult(rid, state));
  }

  cell.append(panel);
  row.append(cell);
  return row;
}

export function createAvailabilityQuery(rid, query, { loading = false } = {}) {
  const form = element('form', {
    className: 'availability-controls',
    dataset: {
      action: 'availability-query',
      rid,
    },
  });

  const forceInput = element('input', { type: 'checkbox', name: 'force' });
  forceInput.checked = !!query.force;

  form.append(
    labeledControl('Start', element('input', {
      type: 'date',
      name: 'start',
      value: query.start,
    })),
    labeledControl('Days', element('input', {
      type: 'number',
      name: 'days',
      value: query.days,
      attrs: { min: '1', max: '60' },
    })),
    labeledControl('Min nights', element('input', {
      type: 'number',
      name: 'min_nights',
      value: query.minNights,
      attrs: { min: '1', max: '31' },
    })),
    element('label', { className: 'availability-force' }, forceInput, document.createTextNode('Force refresh')),
    element('div', { className: 'actions' }, submitButton('Query', { primary: true, disabled: loading })),
  );
  return form;
}

export function createAvailabilityResult(rid, state) {
  if (state?.loading) {
    return element('div', { className: 'availability-summary', text: 'Loading availability...' });
  }
  if (state?.error) {
    return element('div', { className: 'availability-summary error', text: state.error });
  }
  if (!state?.data) {
    return element('div', {
      className: 'availability-summary',
      text: 'Edit query parameters, then run the request.',
    });
  }

  const body = state.data;
  const days = Array.isArray(body.availability) ? body.availability : [];
  const result = element('div', { className: 'availability-result' });
  const summary = element(
    'div',
    { className: 'availability-summary' },
    element('strong', { text: body.summary || body.state || 'Availability response' }),
  );
  if (body.provider) summary.append(document.createTextNode(` / ${body.provider}`));

  result.append(summary, createAvailabilityDaysTable(rid, days.slice(0, 14), state));
  if (days.length > 14) {
    result.append(element('div', { className: 'muted', text: `+${days.length - 14} more` }));
  }
  return result;
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

function createAvailabilityHeading(rid, expanded) {
  return element(
    'div',
    { className: 'sub-heading' },
    element(
      'div',
      { className: 'sub-title' },
      disclosureButton({
        action: 'toggle-availability',
        idName: 'rid',
        id: rid,
        label: 'Availability',
        expanded,
      }),
      element('span', { className: 'muted', text: 'Query availability for this reservable' }),
    ),
    apiCallLabel({ method: 'POST', path: '/api/reservables/availability/query' }),
  );
}

function createAvailabilityDaysTable(rid, days, state) {
  const columns = [
    {
      label: 'Date',
      colClass: 'col-date',
      className: 'mono',
      render: (day) => day.date || '',
    },
    {
      label: 'Status',
      colClass: 'col-status',
      render: (day) => pill(day.status || 'unknown', statusClass(day.status)),
    },
    {
      label: 'Available',
      colClass: 'col-available',
      render: (day) => `${Number(day.available_count || 0)} of ${Number(day.total || 0)}`,
    },
    {
      label: 'Actions',
      colClass: 'col-actions',
      render: (day) => dayActions(rid, day, state),
    },
  ];
  return createTable({
    columns,
    rows: days,
    className: 'availability-days-table',
    wrapClassName: 'availability-days-table-wrap table-wrap',
    rowRenderer: (day) =>
      createRow(columns, day, {
        className: `availability-day-result-row ${availabilityDayClass(day)}`.trim(),
      }),
  });
}

function dayActions(rid, day, state) {
  const date = String(day.date || '');
  const pollerState = state?.pollersByDate?.[date] || {};
  return element(
    'div',
    { className: 'availability-day-action' },
    pollerAction(rid, date, pollerState),
    logsAction(rid, date),
  );
}

function pollerAction(rid, date, pollerState) {
  if (pollerState.loading) {
    const button = element('button', { type: 'button', text: 'Creating...' });
    button.disabled = true;
    return button;
  }
  if (pollerState.error) {
    return fragment(
      actionButton('Retry poller', 'create-availability-poller', {
        rid,
        targetDate: date,
      }),
      element('span', { className: 'availability-row-error', text: pollerState.error }),
    );
  }
  if (pollerState.poller) {
    const id = pollerState.poller.poller?.id || pollerState.poller.id;
    return plainLink({
      href: '/pollers',
      text: id ? `Poller #${id}` : 'Poller created',
    });
  }

  const button = actionButton('Create poller', 'create-availability-poller', {
    rid,
    targetDate: date,
  });
  if (!date) button.disabled = true;
  return button;
}

function logsAction(rid, date) {
  const params = new URLSearchParams({
    rid,
    target_date: date,
    limit: '100',
  });
  return plainLink({
    href: `/logs?${params}`,
    text: 'View logs',
  });
}

function labeledControl(text, control) {
  const label = element('label');
  label.append(document.createTextNode(text), control);
  return label;
}

function availabilityDayClass(day) {
  const status = String(day.status || '').toLowerCase();
  return ['available', 'partial'].includes(status) ? status : '';
}

function statusClass(status) {
  const value = String(status || '').toLowerCase();
  return ['available', 'partial', 'booked', 'closed', 'unknown'].includes(value) ? value : 'unknown';
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
