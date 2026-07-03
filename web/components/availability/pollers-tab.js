// Pollers tab: read-only list with counters at the top.
// A poller is the coalesced per-(provider, parent_ref) schedulable; many
// watches share one. Provenance: clicking a poller's id navigates to
// /availability?tab=runs&poller_id={id}.

import { listPollers, getPollersSummary } from '/web/api/availability-dashboard-api.js';

export async function mount(rootEl, { onTabSwitch }) {
  rootEl.innerHTML = `
    <section class="panel">
      <h2>Status</h2>
      <div class="counter-row" id="pollers-counters">Loading…</div>
    </section>
    <section class="panel">
      <h2>Filter</h2>
      <form id="pollers-filter" class="filters">
        <label>Active
          <select name="active">
            <option value="">any</option>
            <option value="true" selected>active</option>
            <option value="false">dormant</option>
          </select>
        </label>
        <div class="actions">
          <button class="primary" type="submit">Apply</button>
          <button type="reset">Reset</button>
        </div>
      </form>
    </section>
    <section class="panel" aria-live="polite">
      <div id="pollers-status" class="status">Loading…</div>
      <div id="pollers-results"></div>
    </section>
  `;

  const filterForm = rootEl.querySelector('#pollers-filter');
  const statusEl = rootEl.querySelector('#pollers-status');
  const resultsEl = rootEl.querySelector('#pollers-results');
  const countersEl = rootEl.querySelector('#pollers-counters');

  filterForm.addEventListener('submit', (e) => {
    e.preventDefault();
    refresh();
  });
  filterForm.addEventListener('reset', () => setTimeout(refresh, 0));

  resultsEl.addEventListener('click', (e) => {
    const link = e.target.closest('[data-action]');
    if (!link) return;
    const action = link.dataset.action;
    if (action === 'goto-runs-for-poller') {
      e.preventDefault();
      onTabSwitch('runs', { poller_id: link.dataset.pollerId });
    }
  });

  await Promise.all([refreshSummary(), refresh()]);

  async function refreshSummary() {
    try {
      const s = await getPollersSummary();
      countersEl.innerHTML = `
        <span class="chip">active <strong>${s.active}</strong></span>
        <span class="chip">dormant <strong>${s.dormant}</strong></span>
        <span class="chip">due now <strong>${s.due_now}</strong></span>
        <span class="chip">claimed <strong>${s.claimed}</strong></span>
      `;
    } catch (err) {
      countersEl.textContent = `Counters error: ${err.message}`;
    }
  }

  async function refresh() {
    const fd = new FormData(filterForm);
    const params = {
      active: fd.get('active') || undefined,
    };
    statusEl.textContent = 'Loading…';
    try {
      const data = await listPollers(params);
      statusEl.textContent = `${data.total} poller${data.total === 1 ? '' : 's'}.`;
      render(data.pollers);
    } catch (err) {
      statusEl.textContent = `Error: ${err.message}`;
      resultsEl.innerHTML = '';
    }
  }

  function render(pollers) {
    if (pollers.length === 0) {
      resultsEl.innerHTML = '<div class="empty">No pollers.</div>';
      return;
    }
    resultsEl.innerHTML = `
      <table class="data-table">
        <thead><tr>
          <th>id</th><th>provider</th><th>parent ref</th><th>status</th>
          <th>watches</th><th>next run</th><th>last run</th><th>claimed</th>
        </tr></thead>
        <tbody>
          ${pollers.map(renderRow).join('')}
        </tbody>
      </table>
    `;
  }

  function renderRow(p) {
    return `
      <tr>
        <td>
          <a href="#" data-action="goto-runs-for-poller" data-poller-id="${escapeHtml(p.id)}">${escapeHtml(p.id)}</a>
        </td>
        <td>${escapeHtml(p.provider)}</td>
        <td>${escapeHtml(p.parent_ref)}</td>
        <td>${p.active ? 'active' : 'dormant'}</td>
        <td>${escapeHtml(p.attached_watches)}</td>
        <td>${escapeHtml(formatTimestamp(p.next_run_at))}</td>
        <td>${escapeHtml(p.last_run_at ? formatTimestamp(p.last_run_at) : '—')}</td>
        <td>${p.claimed_until ? escapeHtml(formatTimestamp(p.claimed_until)) : '—'}</td>
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
