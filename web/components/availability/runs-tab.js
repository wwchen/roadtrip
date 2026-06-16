// Runs tab: recent executions across all jobs (or filtered to one job).
// Provenance: clicking a run id navigates to /availability?tab=snapshots&run_id={id}.
// Clicking a run's job_id filters this tab to that job.

import { listRuns } from '/web/api/availability-dashboard-api.js';

export async function mount(rootEl, { onTabSwitch, urlParams }) {
  rootEl.innerHTML = `
    <section class="panel">
      <h2>Filter</h2>
      <form id="runs-filter" class="filters">
        <label>Status
          <select name="status">
            <option value="">any</option>
            <option value="started">started</option>
            <option value="completed">completed</option>
            <option value="failed">failed</option>
          </select>
        </label>
        <label>Job ID <input name="job_id" inputmode="numeric"></label>
        <div class="actions">
          <button class="primary" type="submit">Apply</button>
          <button type="reset">Reset</button>
        </div>
      </form>
    </section>
    <section class="panel" aria-live="polite">
      <div id="runs-status" class="status">Loading…</div>
      <div id="runs-results"></div>
    </section>
  `;

  const filterForm = rootEl.querySelector('#runs-filter');
  const statusEl = rootEl.querySelector('#runs-status');
  const resultsEl = rootEl.querySelector('#runs-results');

  if (urlParams.job_id) filterForm.querySelector('[name=job_id]').value = urlParams.job_id;
  if (urlParams.status) filterForm.querySelector('[name=status]').value = urlParams.status;

  filterForm.addEventListener('submit', (e) => {
    e.preventDefault();
    refresh();
  });
  filterForm.addEventListener('reset', () => setTimeout(refresh, 0));

  resultsEl.addEventListener('click', (e) => {
    const link = e.target.closest('[data-action]');
    if (!link) return;
    e.preventDefault();
    if (link.dataset.action === 'goto-snapshots-for-run') {
      onTabSwitch('snapshots', { run_id: link.dataset.runId });
    } else if (link.dataset.action === 'filter-by-job') {
      filterForm.querySelector('[name=job_id]').value = link.dataset.jobId;
      refresh();
    }
  });

  await refresh();

  async function refresh() {
    const fd = new FormData(filterForm);
    const params = {
      status: fd.get('status') || undefined,
      jobId: fd.get('job_id') || undefined,
    };
    statusEl.textContent = 'Loading…';
    try {
      const data = await listRuns(params);
      statusEl.textContent = `${data.runs.length} run${data.runs.length === 1 ? '' : 's'}.`;
      render(data.runs);
    } catch (err) {
      statusEl.textContent = `Error: ${err.message}`;
      resultsEl.innerHTML = '';
    }
  }

  function render(runs) {
    if (runs.length === 0) {
      resultsEl.innerHTML = '<div class="empty">No runs.</div>';
      return;
    }
    resultsEl.innerHTML = `
      <table class="data-table">
        <thead><tr>
          <th>id</th><th>job</th><th>status</th><th>snapshots</th>
          <th>duration</th><th>started</th><th>error</th>
        </tr></thead>
        <tbody>
          ${runs.map(renderRow).join('')}
        </tbody>
      </table>
    `;
  }

  function renderRow(r) {
    return `
      <tr>
        <td>
          <a href="#" data-action="goto-snapshots-for-run" data-run-id="${escapeHtml(r.id)}">${escapeHtml(r.id)}</a>
        </td>
        <td>
          <a href="#" data-action="filter-by-job" data-job-id="${escapeHtml(r.job_id)}">#${escapeHtml(r.job_id)}</a>
        </td>
        <td>${escapeHtml(r.status)}</td>
        <td>${escapeHtml(r.snapshot_count)}</td>
        <td>${r.duration_ms != null ? `${escapeHtml(r.duration_ms)}ms` : '—'}</td>
        <td>${escapeHtml(formatTimestamp(r.started_at))}</td>
        <td>${r.error ? escapeHtml(truncate(r.error, 80)) : ''}</td>
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

function truncate(s, n) {
  return s.length > n ? `${s.slice(0, n - 1)}…` : s;
}
