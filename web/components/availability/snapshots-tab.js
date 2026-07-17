// Changes tab: tabular view scoped to either one campsite id or
// one poi id. Backend rejects calls with neither/both.

import {
  getChangesSummary,
  listChangesForCampsite,
  listChangesForPoi,
} from '/web/api/availability-dashboard-api.js';


export async function mount(rootEl, { urlParams }) {
  rootEl.innerHTML = `
    <section class="panel">
      <h2>Filter</h2>
      <form id="snap-filter" class="filters">
        <label>POI ID <input name="poi_id" inputmode="numeric"></label>
        <label>Campsite ID <input name="campsite_id" inputmode="numeric"></label>
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
    <section class="panel" id="snap-chart-panel" hidden>
      <h2>Timeline</h2>
      <div style="position:relative;height:300px"><canvas id="snap-chart"></canvas></div>
    </section>
    <section class="panel" aria-live="polite">
      <div id="snap-status" class="status">Set a Campsite ID or POI ID to load changes.</div>
      <div id="snap-results"></div>
    </section>
  `;

  const filterForm = rootEl.querySelector('#snap-filter');
  const statusEl = rootEl.querySelector('#snap-status');
  const resultsEl = rootEl.querySelector('#snap-results');
  const chartPanel = rootEl.querySelector('#snap-chart-panel');
  const chartCanvas = rootEl.querySelector('#snap-chart');
  const statsPanel = rootEl.querySelector('#snap-stats-panel');
  const statsEl = rootEl.querySelector('#snap-stats');
  let chart = null;

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
      renderChart([]);
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
      renderChart(data.changes);
      if (campsiteId) {
        await refreshStats(campsiteId);
      } else {
        hideStats();
      }
    } catch (err) {
      statusEl.textContent = `Error: ${err.message}`;
      resultsEl.innerHTML = '';
      renderChart([]);
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
          <th>observed</th><th>from</th><th>to</th>
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
        <td>${s.campsite_name ? escapeHtml(s.campsite_name) : (s.campsite_id != null ? `#${s.campsite_id}` : '—')}</td>
        <td>${escapeHtml(s.target_date)}</td>
        <td>${escapeHtml(formatTimestamp(s.observed_at))}</td>
        <td>${escapeHtml(s.from_status || '—')}</td>
        <td>${escapeHtml(s.to_status)}</td>
      </tr>
    `;
  }

  function renderChart(changes) {
    if (chart) { chart.destroy(); chart = null; }
    if (changes.length === 0 || typeof Chart === 'undefined') {
      chartPanel.hidden = true;
      return;
    }
    chartPanel.hidden = false;

    const STATUS_Y = { available: 2, first_come: 1.5, reserved: 1, closed: 0, unknown: -1, past: -1 };
    const COLORS = [
      '#4dc9f6', '#f67019', '#f53794', '#537bc4', '#acc236',
      '#166a8f', '#00a950', '#58595b', '#8549ba',
    ];

    // Group by "campsite_name / target_date"
    const groups = new Map();
    for (const c of changes) {
      const key = `${c.campsite_name || c.campsite_id} @ ${c.target_date}`;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(c);
    }

    const datasets = [];
    let colorIdx = 0;
    for (const [label, rows] of groups) {
      // Sort chronologically and build stepped data from to_status
      const sorted = rows.slice().sort((a, b) => new Date(a.observed_at) - new Date(b.observed_at));
      const points = sorted.map(r => ({ x: new Date(r.observed_at), y: STATUS_Y[r.to_status] ?? -1 }));
      datasets.push({
        label,
        data: points,
        stepped: 'before',
        borderColor: COLORS[colorIdx % COLORS.length],
        backgroundColor: COLORS[colorIdx % COLORS.length],
        borderWidth: 2,
        pointRadius: 4,
        fill: false,
      });
      colorIdx++;
    }

    chart = new Chart(chartCanvas, {
      type: 'line',
      data: { datasets },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'nearest', intersect: false },
        scales: {
          x: {
            type: 'time',
            time: { tooltipFormat: 'yyyy-MM-dd HH:mm' },
            title: { display: true, text: 'Observed at' },
          },
          y: {
            title: { display: true, text: 'Status' },
            ticks: {
              callback: (v) => {
                const labels = { 2: 'available', 1.5: 'first_come', 1: 'reserved', 0: 'closed', '-1': 'unknown' };
                return labels[v] || '';
              },
              stepSize: 0.5,
            },
            min: -1,
            max: 2.5,
          },
        },
        plugins: {
          tooltip: {
            callbacks: {
              label: (ctx) => {
                const labels = { 2: 'available', 1.5: 'first_come', 1: 'reserved', 0: 'closed', '-1': 'unknown' };
                return `${ctx.dataset.label}: ${labels[ctx.parsed.y] || ctx.parsed.y}`;
              },
            },
          },
        },
      },
    });
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
