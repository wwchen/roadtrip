// Snapshots tab: tabular view scoped to either one reservable or one run.
// Backend rejects calls with neither/both, so the tab requires the operator
// to pick one mode.

import {
  listSnapshotsForReservable,
  listSnapshotsForRun,
} from '/web/api/availability-dashboard-api.js';

export async function mount(rootEl, { urlParams }) {
  rootEl.innerHTML = `
    <section class="panel">
      <h2>Filter</h2>
      <form id="snap-filter" class="filters">
        <label>Reservable ID <input name="reservable_id" inputmode="numeric"></label>
        <label>Run ID <input name="run_id" inputmode="numeric"></label>
        <div class="actions">
          <button class="primary" type="submit">Apply</button>
          <button type="reset">Reset</button>
        </div>
      </form>
    </section>
    <section class="panel" aria-live="polite">
      <div id="snap-status" class="status">Set a Reservable ID or Run ID to load snapshots.</div>
      <div id="snap-results"></div>
    </section>
  `;

  const filterForm = rootEl.querySelector('#snap-filter');
  const statusEl = rootEl.querySelector('#snap-status');
  const resultsEl = rootEl.querySelector('#snap-results');

  if (urlParams.reservable_id) filterForm.querySelector('[name=reservable_id]').value = urlParams.reservable_id;
  if (urlParams.run_id) filterForm.querySelector('[name=run_id]').value = urlParams.run_id;

  filterForm.addEventListener('submit', (e) => {
    e.preventDefault();
    refresh();
  });
  filterForm.addEventListener('reset', () => setTimeout(refresh, 0));

  if (urlParams.reservable_id || urlParams.run_id) {
    await refresh();
  }

  async function refresh() {
    const fd = new FormData(filterForm);
    const reservableId = (fd.get('reservable_id') || '').trim();
    const runId = (fd.get('run_id') || '').trim();
    if (!reservableId === !runId) {
      statusEl.textContent = 'Set exactly one of Reservable ID or Run ID.';
      resultsEl.innerHTML = '';
      return;
    }
    statusEl.textContent = 'Loading…';
    try {
      const data = reservableId
        ? await listSnapshotsForReservable(reservableId)
        : await listSnapshotsForRun(runId);
      statusEl.textContent = `${data.snapshots.length} snapshot${data.snapshots.length === 1 ? '' : 's'}.`;
      render(data.snapshots);
    } catch (err) {
      statusEl.textContent = `Error: ${err.message}`;
      resultsEl.innerHTML = '';
    }
  }

  function render(snaps) {
    if (snaps.length === 0) {
      resultsEl.innerHTML = '<div class="empty">No snapshots.</div>';
      return;
    }
    resultsEl.innerHTML = `
      <table class="data-table">
        <thead><tr>
          <th>id</th><th>reservable</th><th>run</th><th>target date</th>
          <th>observed</th><th>status</th><th>available</th>
        </tr></thead>
        <tbody>
          ${snaps.map(renderRow).join('')}
        </tbody>
      </table>
    `;
  }

  function renderRow(s) {
    return `
      <tr>
        <td>${escapeHtml(s.id)}</td>
        <td>${s.reservable_id != null ? `<a href="/reservables?id=${encodeURIComponent(s.reservable_id)}">#${escapeHtml(s.reservable_id)}</a>` : '—'}</td>
        <td>${s.run_id != null ? `#${escapeHtml(s.run_id)}` : '—'}</td>
        <td>${escapeHtml(s.target_date)}</td>
        <td>${escapeHtml(formatTimestamp(s.observed_at))}</td>
        <td>${escapeHtml(s.status)}</td>
        <td>${s.available ? '✓' : '✗'}</td>
      </tr>
    `;
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function formatTimestamp(iso) {
  return iso.replace('T', ' ').replace(/\.\d+/, '').replace(/Z$/, '');
}
