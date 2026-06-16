import { getWatch, getWatchHeatmap } from '/web/api/watches-api.js';
import { renderWatchHeatmap } from '/web/components/availability/watch-heatmap.js';

const titleEl = document.getElementById('page-title');
const subEl = document.getElementById('page-sub');
const metaEl = document.getElementById('meta');
const heatmapStatus = document.getElementById('heatmap-status');
const heatmapEl = document.getElementById('heatmap');

const id = readId();
if (id == null) {
  subEl.textContent = 'No watch id in URL.';
} else {
  Promise.all([loadMeta(id), loadHeatmap(id)]).catch((err) => {
    subEl.textContent = `Error: ${err.message}`;
  });
}

function readId() {
  // Prefer ?id=, fall back to /watches/{id} path.
  const qs = new URLSearchParams(window.location.search);
  const fromQuery = qs.get('id');
  if (fromQuery) return Number(fromQuery);
  const match = window.location.pathname.match(/^\/watches\/(\d+)\/?$/);
  return match ? Number(match[1]) : null;
}

async function loadMeta(id) {
  try {
    const data = await getWatch(id);
    const w = data.watch;
    titleEl.textContent = `Watch #${w.id}`;
    const scope = w.poi_id != null ? `POI ${w.poi_id}` : `Reservable ${w.reservable?.rid ?? w.reservable_id}`;
    subEl.textContent = `${scope} · ${w.status} · cadence ${w.cadence_sec}s`;
    metaEl.innerHTML = `
      <dt>id</dt><dd>${w.id}</dd>
      <dt>scope</dt><dd>${escapeHtml(scope)}</dd>
      <dt>status</dt><dd>${escapeHtml(w.status)}</dd>
      <dt>cadence</dt><dd>${escapeHtml(w.cadence_sec)}s</dd>
      <dt>target dates</dt><dd>${w.target_dates.map(escapeHtml).join(', ')}</dd>
      <dt>min nights</dt><dd>${escapeHtml(w.min_nights)}</dd>
      <dt>triggers</dt><dd>${w.trigger_kinds.map(escapeHtml).join(', ')}</dd>
      <dt>filters</dt><dd>${escapeHtml(JSON.stringify(w.reservable_filters))}</dd>
      <dt>created</dt><dd>${escapeHtml(formatTimestamp(w.created_at))}</dd>
    `;
  } catch (err) {
    subEl.textContent = `Watch error: ${err.message}`;
    metaEl.innerHTML = '';
  }
}

async function loadHeatmap(id) {
  try {
    const data = await getWatchHeatmap(id);
    if (data.groups.length === 0 || data.groups.every((g) => g.rows.length === 0)) {
      heatmapStatus.textContent = 'No reservables matched this watch yet.';
      heatmapEl.innerHTML = '';
      return;
    }
    const rowCount = data.groups.reduce((acc, g) => acc + g.rows.length, 0);
    heatmapStatus.textContent = `${rowCount} site${rowCount === 1 ? '' : 's'} × ${data.target_dates.length} date${data.target_dates.length === 1 ? '' : 's'}.`;
    renderWatchHeatmap(heatmapEl, data);
  } catch (err) {
    heatmapStatus.textContent = `Heatmap error: ${err.message}`;
    heatmapEl.innerHTML = '';
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
