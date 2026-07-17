// Changes tab: tabular view scoped to either one campsite id or
// one poi id. Backend rejects calls with neither/both.

import {
  getChangesSummary,
  listChangesForCampsite,
  listChangesForPoi,
} from '/web/api/availability-dashboard-api.js';
import { availabilityStatusLabel } from '/web/utils/availability-status.js';

export async function mount(rootEl, { urlParams }) {
  rootEl.innerHTML = `
    <section class="panel">
      <h2>Filter</h2>
      <form id="snap-filter" class="filters">
        <label>Campsite ID <input name="campsite_id" inputmode="numeric"></label>
        <label>POI ID <input name="poi_id" inputmode="numeric"></label>
        <label>Target Date <input name="target_date" type="date"></label>
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
      <div id="snap-status" class="status">Set a Campsite ID or POI ID to load changes.</div>
      <div id="snap-results"></div>
    </section>
  `;

  const filterForm = rootEl.querySelector('#snap-filter');
  const statusEl = rootEl.querySelector('#snap-status');
  const resultsEl = rootEl.querySelector('#snap-results');
  const statsPanel = rootEl.querySelector('#snap-stats-panel');
  const statsEl = rootEl.querySelector('#snap-stats');

  if (urlParams.campsite_id) filterForm.querySelector('[name=campsite_id]').value = urlParams.campsite_id;
  if (urlParams.poi_id) filterForm.querySelector('[name=poi_id]').value = urlParams.poi_id;
  if (urlParams.target_date) filterForm.querySelector('[name=target_date]').value = urlParams.target_date;

  filterForm.addEventListener('submit', (e) => {
    e.preventDefault();
    refresh();
  });
  filterForm.addEventListener('reset', () => setTimeout(refresh, 0));

  if (urlParams.campsite_id || urlParams.poi_id) {
    await refresh();
  }

  async function refresh() {
    const fd = new FormData(filterForm);
    const campsiteId = (fd.get('campsite_id') || '').trim();
    const poiId = (fd.get('poi_id') || '').trim();
    const targetDate = (fd.get('target_date') || '').trim();
    if (!campsiteId === !poiId) {
      statusEl.textContent = 'Set exactly one of Campsite ID or POI ID.';
      resultsEl.innerHTML = '';
      hideStats();
      return;
    }
    statusEl.textContent = 'Loading…';
    try {
      const data = campsiteId
        ? await listChangesForCampsite(campsiteId, { targetDate: targetDate || undefined })
        : await listChangesForPoi(poiId, { targetDate: targetDate || undefined });
      statusEl.textContent = `${data.changes.length} change${data.changes.length === 1 ? '' : 's'}.`;
      render(data.changes);
      if (campsiteId) {
        await refreshStats(campsiteId);
      } else {
        hideStats();
      }
    } catch (err) {
      statusEl.textContent = `Error: ${err.message}`;
      resultsEl.innerHTML = '';
      hideStats();
    }
  }

  function render(changes) {
    if (changes.length === 0) {
      resultsEl.innerHTML = '<div class="empty">No changes.</div>';
      return;
    }
    resultsEl.innerHTML = `
      <table class="data-table">
        <thead><tr>
          <th>campsite</th><th>target date</th>
          <th>since</th><th>observed</th><th>status</th><th>available</th>
        </tr></thead>
        <tbody>
          ${changes.map(renderRow).join('')}
        </tbody>
      </table>
    `;
  }

  function renderRow(s) {
    return `
      <tr>
        <td>${s.campsite_id != null ? `#${escapeHtml(s.campsite_id)}` : '—'}</td>
        <td>${escapeHtml(s.target_date)}</td>
        <td>${s.observed_from ? escapeHtml(formatTimestamp(s.observed_from)) : '—'}</td>
        <td>${escapeHtml(formatTimestamp(s.observed_at))}</td>
        <td title="${escapeHtml(s.status)}">${escapeHtml(availabilityStatusLabel(s.status))}</td>
        <td>${s.available ? '✓' : '✗'}</td>
      </tr>
    `;
  }

  async function refreshStats(campsiteId) {
    try {
      const data = await getChangesSummary(campsiteId);
      if (data.stats.length === 0) {
        hideStats();
        return;
      }
      statsPanel.hidden = false;
      statsEl.innerHTML = `
        <table class="data-table">
          <thead><tr>
            <th>target date</th><th>last available</th><th>available window</th>
            <th>median 24h</th><th>opens 24h</th><th>runs</th>
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
    const lastAvailable =
      s.is_currently_open ? '<strong>available NOW</strong>' :
      s.last_open_at ? `${escapeHtml(formatTimestamp(s.last_open_at))}` :
      '<span class="muted">never seen available</span>';
    const window =
      s.current_or_last_open_window_sec != null
        ? formatDuration(s.current_or_last_open_window_sec)
        : '—';
    const median =
      s.median_open_window_sec != null ? formatDuration(s.median_open_window_sec) : '—';
    return `
      <tr>
        <td>${escapeHtml(s.target_date)}</td>
        <td>${lastAvailable}</td>
        <td>${escapeHtml(window)}</td>
        <td>${escapeHtml(median)}</td>
        <td>${escapeHtml(s.opens_last_24h)}</td>
        <td>${escapeHtml(s.total_runs)}</td>
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
