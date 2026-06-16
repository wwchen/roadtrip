// Snapshots tab: tabular view scoped to either one reservable (by RID) or
// one run id. Backend rejects calls with neither/both.

import {
  getSnapshotsSummary,
  listSnapshotsForReservable,
  listSnapshotsForRun,
} from '/web/api/availability-dashboard-api.js';

export async function mount(rootEl, { urlParams }) {
  rootEl.innerHTML = `
    <section class="panel">
      <h2>Filter</h2>
      <form id="snap-filter" class="filters">
        <label>Reservable RID <input name="reservable_rid" placeholder="site:recgov:330257"></label>
        <label>Run ID <input name="run_id" inputmode="numeric"></label>
        <div class="actions">
          <button class="primary" type="submit">Apply</button>
          <button type="reset">Reset</button>
        </div>
      </form>
    </section>
    <section class="panel" id="snap-stats-panel" hidden>
      <h2>Stats</h2>
      <div id="snap-stats"></div>
    </section>
    <section class="panel" aria-live="polite">
      <div id="snap-status" class="status">Set a Reservable RID or Run ID to load snapshots.</div>
      <div id="snap-results"></div>
    </section>
  `;

  const filterForm = rootEl.querySelector('#snap-filter');
  const statusEl = rootEl.querySelector('#snap-status');
  const resultsEl = rootEl.querySelector('#snap-results');
  const statsPanel = rootEl.querySelector('#snap-stats-panel');
  const statsEl = rootEl.querySelector('#snap-stats');

  if (urlParams.reservable_rid) filterForm.querySelector('[name=reservable_rid]').value = urlParams.reservable_rid;
  if (urlParams.run_id) filterForm.querySelector('[name=run_id]').value = urlParams.run_id;

  filterForm.addEventListener('submit', (e) => {
    e.preventDefault();
    refresh();
  });
  filterForm.addEventListener('reset', () => setTimeout(refresh, 0));

  if (urlParams.reservable_rid || urlParams.run_id) {
    await refresh();
  }

  async function refresh() {
    const fd = new FormData(filterForm);
    const rid = (fd.get('reservable_rid') || '').trim();
    const runId = (fd.get('run_id') || '').trim();
    if (!rid === !runId) {
      statusEl.textContent = 'Set exactly one of Reservable RID or Run ID.';
      resultsEl.innerHTML = '';
      hideStats();
      return;
    }
    statusEl.textContent = 'Loading…';
    try {
      const data = rid
        ? await listSnapshotsForReservable(rid)
        : await listSnapshotsForRun(runId);
      statusEl.textContent = `${data.snapshots.length} snapshot${data.snapshots.length === 1 ? '' : 's'}.`;
      render(data.snapshots);
      if (rid) {
        await refreshStats(rid);
      } else {
        hideStats();
      }
    } catch (err) {
      statusEl.textContent = `Error: ${err.message}`;
      resultsEl.innerHTML = '';
      hideStats();
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
          <th>id</th><th>run</th><th>target date</th>
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
        <td>${s.run_id != null ? `#${escapeHtml(s.run_id)}` : '—'}</td>
        <td>${escapeHtml(s.target_date)}</td>
        <td>${escapeHtml(formatTimestamp(s.observed_at))}</td>
        <td>${escapeHtml(s.status)}</td>
        <td>${s.available ? '✓' : '✗'}</td>
      </tr>
    `;
  }

  async function refreshStats(rid) {
    try {
      const data = await getSnapshotsSummary(rid);
      if (data.stats.length === 0) {
        hideStats();
        return;
      }
      statsPanel.hidden = false;
      statsEl.innerHTML = `
        <table class="data-table">
          <thead><tr>
            <th>target date</th><th>last open</th><th>open window</th>
            <th>median 24h</th><th>flips 24h</th><th>snapshots</th>
          </tr></thead>
          <tbody>
            ${data.stats.map(renderStatsRow).join('')}
          </tbody>
        </table>
      `;
    } catch (err) {
      hideStats();
    }
  }

  function hideStats() {
    statsPanel.hidden = true;
    statsEl.innerHTML = '';
  }

  function renderStatsRow(s) {
    const lastOpen =
      s.is_currently_open ? '<strong>open NOW</strong>' :
      s.last_open_at ? `${escapeHtml(formatTimestamp(s.last_open_at))}` :
      '<span class="muted">never seen open</span>';
    const window =
      s.current_or_last_open_window_sec != null
        ? formatDuration(s.current_or_last_open_window_sec)
        : '—';
    const median =
      s.median_open_window_sec != null ? formatDuration(s.median_open_window_sec) : '—';
    return `
      <tr>
        <td>${escapeHtml(s.target_date)}</td>
        <td>${lastOpen}</td>
        <td>${escapeHtml(window)}</td>
        <td>${escapeHtml(median)}</td>
        <td>${escapeHtml(s.flips_last_24h)}</td>
        <td>${escapeHtml(s.total_snapshots)}</td>
      </tr>
    `;
  }

  function formatDuration(sec) {
    if (sec < 60) return `${sec}s`;
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    if (m < 60) return `${m}m ${s}s`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
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
