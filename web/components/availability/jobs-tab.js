// Jobs tab: read-only list with per-status counters at the top.
// Provenance: clicking a job's id navigates to /availability?tab=runs&job_id={id}.
// Clicking a job's watch_id navigates to /watches.

import { listJobs, getJobsSummary } from '/web/api/availability-dashboard-api.js';

export async function mount(rootEl, { onTabSwitch }) {
  rootEl.innerHTML = `
    <section class="panel">
      <h2>Status</h2>
      <div class="counter-row" id="jobs-counters">Loading…</div>
    </section>
    <section class="panel">
      <h2>Filter</h2>
      <form id="jobs-filter" class="filters">
        <label>Status
          <select name="status">
            <option value="">any</option>
            <option value="active" selected>active</option>
            <option value="paused">paused</option>
            <option value="done">done</option>
          </select>
        </label>
        <label>Watch ID <input name="watch_id" inputmode="numeric"></label>
        <div class="actions">
          <button class="primary" type="submit">Apply</button>
          <button type="reset">Reset</button>
        </div>
      </form>
    </section>
    <section class="panel" aria-live="polite">
      <div id="jobs-status" class="status">Loading…</div>
      <div id="jobs-results"></div>
    </section>
  `;

  const filterForm = rootEl.querySelector('#jobs-filter');
  const statusEl = rootEl.querySelector('#jobs-status');
  const resultsEl = rootEl.querySelector('#jobs-results');
  const countersEl = rootEl.querySelector('#jobs-counters');

  filterForm.addEventListener('submit', (e) => {
    e.preventDefault();
    refresh();
  });
  filterForm.addEventListener('reset', () => setTimeout(refresh, 0));

  resultsEl.addEventListener('click', (e) => {
    const link = e.target.closest('[data-action]');
    if (!link) return;
    const action = link.dataset.action;
    if (action === 'goto-runs-for-job') {
      e.preventDefault();
      onTabSwitch('runs', { job_id: link.dataset.jobId });
    }
  });

  await Promise.all([refreshSummary(), refresh()]);

  async function refreshSummary() {
    try {
      const s = await getJobsSummary();
      countersEl.innerHTML = `
        <span class="chip">active <strong>${s.active}</strong></span>
        <span class="chip">paused <strong>${s.paused}</strong></span>
        <span class="chip">done <strong>${s.done}</strong></span>
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
      status: fd.get('status') || undefined,
      watchId: fd.get('watch_id') || undefined,
    };
    statusEl.textContent = 'Loading…';
    try {
      const data = await listJobs(params);
      statusEl.textContent = `${data.total} job${data.total === 1 ? '' : 's'}.`;
      render(data.jobs);
    } catch (err) {
      statusEl.textContent = `Error: ${err.message}`;
      resultsEl.innerHTML = '';
    }
  }

  function render(jobs) {
    if (jobs.length === 0) {
      resultsEl.innerHTML = '<div class="empty">No jobs.</div>';
      return;
    }
    resultsEl.innerHTML = `
      <table class="data-table">
        <thead><tr>
          <th>id</th><th>watch</th><th>cadence</th><th>status</th>
          <th>next run</th><th>last run</th><th>claimed</th>
        </tr></thead>
        <tbody>
          ${jobs.map(renderRow).join('')}
        </tbody>
      </table>
    `;
  }

  function renderRow(j) {
    return `
      <tr>
        <td>
          <a href="#" data-action="goto-runs-for-job" data-job-id="${escapeHtml(j.id)}">${escapeHtml(j.id)}</a>
        </td>
        <td>
          <a href="/watches?id=${encodeURIComponent(j.watch_id)}">#${escapeHtml(j.watch_id)}</a>
        </td>
        <td>${escapeHtml(j.cadence_sec)}s</td>
        <td>${escapeHtml(j.status)}</td>
        <td>${escapeHtml(formatTimestamp(j.next_run_at))}</td>
        <td>${escapeHtml(j.last_run_at ? formatTimestamp(j.last_run_at) : '—')}</td>
        <td>${j.claimed_until ? escapeHtml(formatTimestamp(j.claimed_until)) : '—'}</td>
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
