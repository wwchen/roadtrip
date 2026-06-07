import { state, distanceKm, formatDistance } from './core.js';

const searchIndex = []; // { name, sub, kind, color, lng, lat, zoom, onSelect }

export function registerSearchItems(items) {
  searchIndex.push(...items);
}

/** Read-only access to the search index for the trip-planner top bar. */
export function getSearchIndex() {
  return searchIndex;
}

// Maps search result kind → one or more filter-toggle ids that must be ON
// for the destination layer's popup to render (queryRenderedFeatures only sees
// what's currently visible). For Superchargers, the site's status decides which
// status-toggle must be on.
function togglesForItem(item) {
  switch (item.kind) {
    case 'NP': return ['f-np'];
    case 'SP': return ['f-sp'];
    case 'PF': return ['f-pf'];
    case 'CG': {
      const cat = item.cgCategory || 'federal';
      const id = cat === 'other' ? 'f-cg-federal' : `f-cg-${cat}`;
      return [id];
    }
    case 'SC': {
      const group = item.scGroup || 'open';
      return [`f-${group}`];
    }
    default: return [];
  }
}

export function fitAndSelect(item) {
  const { map } = state;
  // Ensure the destination layer is visible before we flyTo + click it.
  for (const tid of togglesForItem(item)) {
    const el = document.getElementById(tid);
    if (el && !el.checked) {
      el.checked = true;
      el.dispatchEvent(new Event('change'));
    }
  }
  const dest = { center: [item.lng, item.lat], zoom: item.zoom ?? 11, speed: 1.6 };
  map.flyTo(dest);
  if (item.onSelect) {
    map.once('moveend', () => item.onSelect());
  }
}

export function initSearch() {
  const input = document.getElementById('search');
  const out = document.getElementById('search-results');
  const sortRow = document.getElementById('sort-nearest-row');
  const sortCheckbox = document.getElementById('sort-nearest');
  const sortLabel = sortRow.querySelector('label');
  let activeIdx = -1;
  let current = [];

  const render = (items) => {
    current = items;
    activeIdx = -1;
    if (!items.length) { out.classList.remove('open'); out.innerHTML = ''; return; }
    out.innerHTML = items.map((it, i) => {
      const distHtml = (it._distKm != null) ? `<span class="sr-dist">${formatDistance(it._distKm)}</span>` : '';
      return `<div class="sr-item" data-i="${i}">
         <span class="sr-kind" style="background:${it.color}">${it.kind}</span>
         ${it.name}${it.sub ? ` <span class="sr-sub">${it.sub}</span>` : ''}${distHtml}
       </div>`;
    }).join('');
    out.classList.add('open');
  };

  const runSearch = () => {
    const q = input.value.trim().toLowerCase();
    if (q.length < 2) { render([]); return; }
    const terms = q.split(/\s+/);
    const sortByNearest = !!(state.userLocation && sortCheckbox.checked);
    const scored = [];
    for (const it of searchIndex) {
      const hay = (it.name + ' ' + (it.sub || '')).toLowerCase();
      if (!terms.every(t => hay.includes(t))) continue;
      const pos = it.name.toLowerCase().indexOf(q);
      const nameScore = pos < 0 ? 500 : pos;
      let distKm = null;
      if (state.userLocation && Number.isFinite(it.lat) && Number.isFinite(it.lng)) {
        distKm = distanceKm(state.userLocation.lat, state.userLocation.lng, it.lat, it.lng);
      }
      scored.push({ it, nameScore, distKm });
    }
    if (sortByNearest) {
      scored.sort((a, b) => {
        const ad = a.distKm == null ? Infinity : a.distKm;
        const bd = b.distKm == null ? Infinity : b.distKm;
        if (ad !== bd) return ad - bd;
        return a.nameScore - b.nameScore;
      });
    } else {
      scored.sort((a, b) => a.nameScore - b.nameScore);
    }
    const items = scored.slice(0, 20).map(s => Object.assign({}, s.it, { _distKm: s.distKm }));
    render(items);
  };

  // Expose for geolocation handlers — when userLocation arrives/changes, the
  // currently-open results need to re-sort and show distances.
  window.rerenderSearchResults = () => {
    updateSortRowState();
    if (input.value.trim().length >= 2) runSearch();
  };

  function updateSortRowState() {
    if (state.userLocation) {
      sortCheckbox.disabled = false;
      sortRow.classList.remove('disabled');
      sortLabel.textContent = 'Sort by nearest';
    } else {
      sortCheckbox.disabled = true;
      sortCheckbox.checked = false;
      sortRow.classList.add('disabled');
      sortLabel.textContent = 'Sort by nearest (location off)';
    }
  }
  updateSortRowState();
  sortCheckbox.addEventListener('change', runSearch);

  input.addEventListener('input', runSearch);
  input.addEventListener('focus', () => { if (input.value.length >= 2) runSearch(); });
  input.addEventListener('keydown', (e) => {
    if (!current.length) return;
    if (e.key === 'ArrowDown') { activeIdx = Math.min(activeIdx + 1, current.length - 1); updateActive(); e.preventDefault(); }
    else if (e.key === 'ArrowUp') { activeIdx = Math.max(activeIdx - 1, 0); updateActive(); e.preventDefault(); }
    else if (e.key === 'Enter') { if (activeIdx >= 0) pick(activeIdx); else if (current.length) pick(0); e.preventDefault(); }
    else if (e.key === 'Escape') { out.classList.remove('open'); input.blur(); }
  });
  out.addEventListener('mousedown', (e) => {
    const item = e.target.closest('.sr-item');
    if (item) { pick(Number(item.dataset.i)); e.preventDefault(); }
  });
  document.addEventListener('click', (e) => {
    if (!document.getElementById('search-wrap').contains(e.target)) out.classList.remove('open');
  });

  function updateActive() {
    [...out.children].forEach((el, i) => el.classList.toggle('active', i === activeIdx));
    const el = out.children[activeIdx];
    if (el) el.scrollIntoView({ block: 'nearest' });
  }
  function pick(i) {
    const item = current[i];
    out.classList.remove('open');
    input.value = item.name;
    fitAndSelect(item);
  }
}
