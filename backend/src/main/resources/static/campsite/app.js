// ---- Utils ----

function el(id) { return document.getElementById(id); }

function showToast(msg, type = 'info', duration = 4000) {
  let container = el('toast-container');
  if (!container) {
    container = document.createElement('div');
    container.id = 'toast-container';
    document.body.appendChild(container);
  }
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.textContent = msg;
  container.appendChild(toast);
  setTimeout(() => toast.remove(), duration);
}

async function api(method, path, body) {
  const opts = {
    method,
    headers: { 'Content-Type': 'application/json' },
  };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(path, opts);
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

function fmtDate(iso) {
  if (!iso) return '—';
  return new Date(iso + (iso.includes('T') ? '' : 'T00:00:00Z')).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
}

function fmtTimestamp(iso) {
  if (!iso) return '';
  return new Date(iso.includes('T') ? iso : iso + 'T00:00:00Z')
    .toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' });
}

function timeAgo(iso) {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso.includes('T') ? iso : iso + 'T00:00:00Z').getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

// ---- Campground Search ----

// Multi-select state
const selectedCampgrounds = new Map(); // id → campground object
const expandedParks = new Map();       // parkId → { loading, campgrounds[] }
let lastSearchResults = { parks: [], campgrounds: [] };
let searchTimer = null;

el('campground-search').addEventListener('input', e => {
  clearTimeout(searchTimer);
  const q = e.target.value.trim();
  if (q.length < 2) {
    el('search-results').classList.add('hidden');
    return;
  }
  searchTimer = setTimeout(() => doSearch(q), 300);
});

el('campground-search').addEventListener('keydown', e => {
  if (e.key === 'Escape') el('search-results').classList.add('hidden');
});

document.addEventListener('click', e => {
  const searchWrap = el('campground-search').closest('.search-wrap');
  if (!searchWrap.contains(e.target)) {
    el('search-results').classList.add('hidden');
  }
});

async function doSearch(q) {
  try {
    const results = await api('GET', `/api/campsite/campgrounds/search?q=${encodeURIComponent(q)}`);
    lastSearchResults = results;
    renderSearchResults(results);
  } catch (err) {
    console.error('Search failed:', err);
  }
}

function rerenderDropdown() {
  renderSearchResults(lastSearchResults);
}

function renderSearchResults({ parks = [], campgrounds = [] }) {
  const container = el('search-results');
  container.innerHTML = '';

  if (!parks.length && !campgrounds.length) {
    container.innerHTML = '<div class="dropdown-item"><div class="dropdown-item-sub">No results found</div></div>';
    container.classList.remove('hidden');
    return;
  }

  if (parks.length) {
    appendSectionHeader(container, 'Parks & Recreation Areas');
    for (const park of parks) renderParkItem(container, park);
  }

  if (campgrounds.length) {
    if (parks.length) appendSectionHeader(container, 'Campgrounds');
    for (const cg of campgrounds) renderCampgroundItem(container, cg);
  }

  container.classList.remove('hidden');
}

function appendSectionHeader(container, text) {
  const h = document.createElement('div');
  h.className = 'dropdown-section-header';
  h.textContent = text;
  container.appendChild(h);
}

function renderParkItem(container, park) {
  const isExpanded = expandedParks.has(park.id);
  const state = expandedParks.get(park.id);
  const loc = [park.city, park.state].filter(Boolean).join(', ');

  const item = document.createElement('div');
  item.className = 'dropdown-item dropdown-item-park';
  item.innerHTML = `
    <div style="display:flex;align-items:center;gap:8px">
      <span class="park-chevron">${isExpanded ? '▼' : '▶'}</span>
      <div style="flex:1;min-width:0">
        <div class="dropdown-item-name">${escapeHtml(park.name)}</div>
        ${loc ? `<div class="dropdown-item-sub">${escapeHtml(loc)}</div>` : ''}
      </div>
    </div>
  `;
  item.addEventListener('click', e => { e.stopPropagation(); toggleParkExpand(park); });
  container.appendChild(item);

  if (isExpanded) {
    const campWrap = document.createElement('div');
    campWrap.className = 'park-campgrounds';
    if (!state?.campgrounds) {
      campWrap.innerHTML = '<div class="park-loading">Loading…</div>';
    } else if (state.campgrounds.length === 0) {
      campWrap.innerHTML = '<div class="park-loading">No campgrounds found in this area</div>';
    } else {
      for (const cg of state.campgrounds) renderParkCampgroundItem(campWrap, cg);
    }
    container.appendChild(campWrap);
  }
}

async function toggleParkExpand(park) {
  if (expandedParks.has(park.id)) {
    expandedParks.delete(park.id);
    rerenderDropdown();
    return;
  }
  expandedParks.set(park.id, { loading: true, campgrounds: null });
  rerenderDropdown();
  try {
    const campgrounds = await api('GET', `/api/campsite/campgrounds/in-park/${park.id}?name=${encodeURIComponent(park.name)}`);
    expandedParks.set(park.id, { loading: false, campgrounds });
    rerenderDropdown();
  } catch (err) {
    expandedParks.delete(park.id);
    showToast(`Failed to load campgrounds: ${err.message}`, 'error');
    rerenderDropdown();
  }
}

function renderParkCampgroundItem(container, cg) {
  const isSelected = selectedCampgrounds.has(cg.id);
  const item = document.createElement('div');
  item.className = 'park-campground-item';

  let meta = '';
  if (cg.reviews > 0) {
    const rStr = cg.reviews >= 1000 ? (cg.reviews / 1000).toFixed(1) + 'k' : cg.reviews;
    meta = `★${cg.rating ? cg.rating.toFixed(1) : '?'} · ${rStr} reviews`;
  }

  item.innerHTML = `
    <input type="checkbox" ${isSelected ? 'checked' : ''} style="pointer-events:none;accent-color:var(--accent)">
    <div class="park-campground-info">
      <div class="park-campground-name">${escapeHtml(cg.name)}</div>
      ${meta ? `<div class="park-campground-meta">${meta}</div>` : ''}
    </div>
  `;
  item.addEventListener('click', e => {
    e.stopPropagation();
    toggleCampground(cg);
    item.querySelector('input').checked = selectedCampgrounds.has(cg.id);
  });
  container.appendChild(item);
}

function renderCampgroundItem(container, cg) {
  const isSelected = selectedCampgrounds.has(cg.id);
  const item = document.createElement('div');
  item.className = `dropdown-item${isSelected ? ' selected' : ''}`;
  const sub = [cg.parent_name, cg.city, cg.state].filter(Boolean).join(' · ');
  item.innerHTML = `
    <div style="display:flex;align-items:center;gap:8px">
      <div style="flex:1;min-width:0">
        <div class="dropdown-item-name">${escapeHtml(cg.name)}</div>
        <div class="dropdown-item-sub">${escapeHtml(sub)} — ID: ${cg.id}</div>
      </div>
      <span class="dropdown-item-add">${isSelected ? '✓' : '+'}</span>
    </div>
  `;
  item.addEventListener('click', () => {
    toggleCampground(cg);
    const sel = selectedCampgrounds.has(cg.id);
    item.classList.toggle('selected', sel);
    item.querySelector('.dropdown-item-add').textContent = sel ? '✓' : '+';
  });
  container.appendChild(item);
}

function toggleCampground(cg) {
  if (selectedCampgrounds.has(cg.id)) {
    selectedCampgrounds.delete(cg.id);
  } else {
    selectedCampgrounds.set(cg.id, cg);
  }
  renderSelectedCampgrounds();
}

function renderSelectedCampgrounds() {
  const container = el('selected-campgrounds');
  container.innerHTML = '';
  for (const [id, cg] of selectedCampgrounds) {
    const chip = document.createElement('div');
    chip.className = 'campground-chip';
    chip.innerHTML = `
      <span>${escapeHtml(cg.name)} <span style="color:var(--text3)">#${id}</span></span>
      <button type="button" title="Remove">✕</button>
    `;
    chip.querySelector('button').addEventListener('click', () => {
      selectedCampgrounds.delete(id);
      renderSelectedCampgrounds();
      rerenderDropdown();
    });
    container.appendChild(chip);
  }
}

window.clearCampground = function () {
  selectedCampgrounds.clear();
  expandedParks.clear();
  lastSearchResults = { parks: [], campgrounds: [] };
  renderSelectedCampgrounds();
  el('campground-search').value = '';
  el('search-results').classList.add('hidden');
  el('campground-search').focus();
};

function escapeHtml(str) {
  return String(str || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ---- Preflight checks ----

// Runs before saving an alert. Returns array of error strings (empty = all good).
// Checks: Slack config, auto-cart login status.
async function preflightChecks(autoCart, notifySlack) {
  const errors = [];

  // Run Slack config check and login check in parallel
  const [settings, loginResult] = await Promise.all([
    api('GET', '/api/campsite/settings'),
    autoCart ? api('POST', '/api/campsite/settings/test-chrome', {}).catch(err => ({ error: err.message })) : null,
  ]);

  // Slack: if enabled but not configured, warn
  if (notifySlack) {
    const hasToken = settings.slack_token === '••••••••';
    const hasChannel = !!settings.slack_channel;
    if (!hasToken || !hasChannel) {
      errors.push('Slack notifications enabled but token/channel not configured — add them in Settings');
    }
  }

  // Auto-cart: must be logged in
  if (autoCart) {
    if (loginResult?.error) {
      errors.push(`Auto-cart check failed: ${loginResult.error}`);
    } else if (!loginResult?.loggedIn) {
      errors.push('Auto-cart requires a logged-in browser session — use "Test browser session" in Settings');
    }
  }

  return errors;
}

// ---- Alert Form ----

el('alert-form').addEventListener('submit', async e => {
  e.preventDefault();

  if (selectedCampgrounds.size === 0) {
    showToast('Please select at least one campground', 'error');
    return;
  }

  const startDate = el('start-date').value;
  const endDate = el('end-date').value;

  if (!startDate || !endDate) {
    showToast('Please set arrival and departure dates', 'error');
    return;
  }

  if (startDate >= endDate) {
    showToast('Departure must be after arrival', 'error');
    return;
  }

  const campsiteTypes = [...document.querySelectorAll('#campsite-types input:checked')].map(i => i.value);
  const equipmentTypes = [...document.querySelectorAll('#equipment-types input:checked')].map(i => i.value);

  const specificRaw = el('specific-sites').value.trim();
  const specificSites = specificRaw ? specificRaw.split(',').map(s => s.trim().padStart(3, '0')).filter(Boolean) : [];

  const base = {
    start_date: startDate,
    end_date: endDate,
    min_nights: parseInt(el('min-nights').value || '1', 10),
    campsite_types: campsiteTypes,
    equipment_types: equipmentTypes,
    max_people: parseInt(el('max-people').value || '0', 10) || null,
    specific_sites: specificSites,
    notify_slack: el('notify-slack').checked,
    auto_cart: el('auto-cart').checked,
    stop_after_match: el('stop-after-match').checked,
  };

  try {
    showToast('Checking integrations…', 'info', 3000);
    const errs = await preflightChecks(base.auto_cart, base.notify_slack);
    if (errs.length) {
      errs.forEach(msg => showToast(msg, 'error', 8000));
      return;
    }
    for (const [id, cg] of selectedCampgrounds) {
      await api('POST', '/api/campsite/alerts', { ...base, campground_id: id, campground_name: cg.name, parent_name: cg.parent_name || null, parent_id: cg.parent_id || null });
    }
    const n = selectedCampgrounds.size;
    showToast(`${n} alert${n > 1 ? 's' : ''} created — polling started!`, 'success');
    e.target.reset();
    el('auto-cart').checked = true;
    el('stop-after-match').checked = true;
    clearCampground();
    loadAlerts();
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
});

// ---- Alerts List ----

async function loadAlerts() {
  const alerts = await api('GET', '/api/campsite/alerts');
  const container = el('alerts-list');
  el('alerts-count').textContent = alerts.filter(a => a.status === 'active').length;

  if (alerts.length === 0) {
    container.innerHTML = '<div class="empty-state">No alerts yet. Create one above.</div>';
    return;
  }

  container.innerHTML = alerts.map(a => `
    <div class="alert-card ${a.status}" id="alert-${a.id}">
      <div class="alert-card-header">
        <div>
          <div class="alert-name">
            <a href="https://www.recreation.gov/camping/campgrounds/${a.campground_id}" target="_blank" rel="noopener" class="card-link">${escapeHtml(a.campground_name)}</a>
            <span style="color:var(--text3);font-size:12px;font-weight:400">#${a.campground_id}</span>
          </div>
          ${a.parent_name ? `<div class="alert-park"><a href="https://www.recreation.gov/camping/gateways/${a.parent_id}" target="_blank" rel="noopener" class="card-link-muted">${escapeHtml(a.parent_name)}</a></div>` : ''}
          <div class="alert-meta">
            <span>📅 ${fmtDate(a.start_date)} – ${fmtDate(a.end_date)}</span>
            <span>🌙 ${a.min_nights}+ nights</span>
            ${a.max_people ? `<span>👥 ${a.max_people} people</span>` : ''}
            ${a.campsite_types?.length ? `<span>🏕 ${a.campsite_types.join(', ')}</span>` : ''}
            ${a.auto_cart ? `<span style="color:var(--accent)">⚡ Auto-cart</span>` : ''}
            <span class="badge badge-${a.status}">${a.status}</span>
          </div>
        </div>
        <div class="alert-actions">
          ${a.status === 'active'
            ? `<button class="btn btn-sm btn-secondary" onclick="toggleAlert(${a.id}, 'paused')">Pause</button>`
            : a.status === 'paused'
              ? `<button class="btn btn-sm btn-primary" onclick="toggleAlert(${a.id}, 'active')">Resume</button>`
              : ''}
          <button class="btn btn-sm btn-ghost" onclick="openEditAlert(${a.id})">Edit</button>
          <button class="btn btn-sm btn-danger" onclick="deleteAlert(${a.id})">Delete</button>
        </div>
      </div>
      <div class="alert-footer">
        <span class="rel-time" title="${fmtTimestamp(a.last_checked)}">Checked: ${a.last_checked ? timeAgo(a.last_checked) : 'not yet'}</span>
        ${a.last_match ? `<span class="rel-time" style="color:var(--green)" title="${fmtTimestamp(a.last_match)}">Last match: ${timeAgo(a.last_match)}</span>` : ''}
        <span class="rel-time" title="${fmtTimestamp(a.created_at)}">Created: ${timeAgo(a.created_at)}</span>
      </div>
    </div>
  `).join('');
}

window.toggleAlert = async function (id, status) {
  try {
    await api('PATCH', `/api/campsite/alerts/${id}`, { status });
    loadAlerts();
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
};

window.deleteAlert = async function (id) {
  if (!confirm('Delete this alert?')) return;
  try {
    await api('DELETE', `/api/campsite/alerts/${id}`);
    showToast('Alert deleted', 'info');
    loadAlerts();
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
};

// ---- Matches List ----

async function loadMatches({ checkAvail = true } = {}) {
  const matches = await api('GET', '/api/campsite/matches?limit=30');
  const container = el('matches-list');
  el('matches-count').textContent = matches.length;

  if (matches.length === 0) {
    container.innerHTML = '<div class="empty-state">No matches yet — the poller will find open sites and show them here.</div>';
    return;
  }

  // Preserve existing availability states so they survive re-renders when checkAvail=false
  const prevAvail = {};
  for (const m of matches) {
    const card = document.getElementById(`match-card-${m.id}`);
    if (card) {
      const avail = ['available', 'unavailable', 'unqueryable'].find(s => card.classList.contains(s));
      if (avail) prevAvail[m.id] = avail;
    }
  }

  container.innerHTML = matches.map(m => `
    <div class="match-card" id="match-card-${m.id}">
      <div class="match-info">
        <div class="match-title">
          ${escapeHtml(m.campground_name)} — Site ${escapeHtml(m.campsite_site || m.campsite_id || '?')} ${m.campsite_loop ? `(${escapeHtml(m.campsite_loop)})` : ''}
        </div>
        <div class="match-dates">📅 ${m.available_dates.join(', ')} (${m.nights} night${m.nights > 1 ? 's' : ''})</div>
        <div class="match-meta">${m.campsite_type || ''} ${m.notified ? '· ✉ notified' : ''} ${m.cart_added ? '· 🛒 cart added' : ''}</div>
        <div class="match-found rel-time" title="${fmtTimestamp(m.found_at)}">Found ${timeAgo(m.found_at)}</div>
      </div>
      <div class="match-actions">
        <button class="btn btn-sm btn-primary" onclick="openMatch(${m.id})">Open →</button>
        <button class="btn btn-sm btn-secondary" onclick="cartMatch(${m.id})">Add to Cart</button>
        <button class="btn btn-sm btn-ghost btn-icon" onclick="deleteMatch(${m.id})" title="Remove match">✕</button>
      </div>
    </div>
  `).join('');

  // Restore previously-known availability states so cards don't reset on re-render
  for (const [id, status] of Object.entries(prevAvail)) {
    const card = document.getElementById(`match-card-${id}`);
    if (card) card.classList.add(status);
  }

  // Availability checks hit rec.gov — only run after a real poll cycle, not on every UI refresh.
  if (checkAvail) checkAllMatchAvailability(matches);
}

const STATUS_LABELS = {
  available:   'Still available',
  unavailable: 'No longer available',
  unqueryable: 'Cannot verify — no specific campsite ID',
};

async function checkAllMatchAvailability(matches) {
  for (const m of matches) {
    await checkMatchAvailability(m.id);
    await new Promise(r => setTimeout(r, 400));
  }
}

async function checkMatchAvailability(id) {
  const card = document.getElementById(`match-card-${id}`);
  if (!card) return;
  try {
    const { status } = await api('GET', `/api/campsite/matches/${id}/availability`);
    card.classList.remove('available', 'unavailable', 'unqueryable');
    card.classList.add(status);
    card.title = STATUS_LABELS[status] ?? status;
  } catch (err) {
    console.warn(`[availability] match ${id} failed:`, err.message);
    card.classList.remove('available', 'unavailable', 'unqueryable');
    card.classList.add('unqueryable');
  }
}

window.deleteMatch = async function (id) {
  try {
    await api('DELETE', `/api/campsite/matches/${id}`);
    const card = document.getElementById(`match-card-${id}`);
    if (card) card.remove();
    const count = parseInt(el('matches-count').textContent || '0', 10);
    el('matches-count').textContent = Math.max(0, count - 1);
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
};

window.openMatch = async function (id) {
  try {
    const result = await api('POST', `/api/campsite/matches/${id}/cart`, { action: 'open' });
    if (result.url) window.open(result.url, '_blank');
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
};

window.cartMatch = async function (id) {
  showToast('Queuing add-to-cart for companion…', 'info');
  try {
    const result = await api('POST', `/api/campsite/matches/${id}/cart`, {});
    if (result.cart_added) {
      showToast('Added to cart! Complete checkout in browser.', 'success', 6000);
    } else if (result.queued) {
      showToast('Match re-queued — companion will run ATC and report back via the matches list.', 'info', 6000);
    } else {
      showToast('Cart request accepted — see matches list for result.', 'info', 6000);
    }
    loadMatches({ checkAvail: false });
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
};

// ---- Data-refresh status dot ----

function updateDataStatus(state) {
  const dot = el('status-data');
  if (!dot) return;
  dot.className = 'status-dot ' + state;
  dot.title = {
    ok:       'Data is fresh — server is polling normally',
    err:      'Not receiving updates — connection to server may be lost',
    checking: 'Waiting for first refresh…',
  }[state] ?? state;
}

// ---- Poll countdown ----

let nextPollAt = null;
let pollIntervalMs = 60000; // loaded from settings; used for overdue threshold
let isPollingNow = false;

function updatePollCountdown() {
  const el_ = el('poll-countdown');
  if (!el_) return;
  if (!nextPollAt && !isPollingNow) { el_.classList.add('hidden'); return; }

  if (isPollingNow) {
    el_.textContent = '…';
    el_.className = 'poll-countdown refreshing';
    el_.classList.remove('hidden');
    return;
  }

  const msLeft = nextPollAt - Date.now();

  if (msLeft < -pollIntervalMs) {
    // More than one full interval overdue — connection likely lost
    updateDataStatus('err');
    el_.textContent = 'overdue';
    el_.className = 'poll-countdown overdue';
    el_.classList.remove('hidden');
    return;
  }

  if (msLeft <= 0) {
    el_.textContent = '0s';
    el_.className = 'poll-countdown';
    el_.classList.remove('hidden');
    return;
  }

  const totalSec = Math.ceil(msLeft / 1000);
  const mins = Math.floor(totalSec / 60);
  const secs = totalSec % 60;
  el_.textContent = mins > 0 ? `${mins}m${secs > 0 ? ` ${secs}s` : ''}` : `${secs}s`;
  el_.className = 'poll-countdown'; // green
  el_.classList.remove('hidden');
}

// ---- Poll Now ----

el('poll-now-btn').addEventListener('click', async () => {
  const btn = el('poll-now-btn');
  btn.disabled = true;
  try {
    await api('POST', '/api/campsite/poll');
  } catch (err) {
    showToast(`Poll error: ${err.message}`, 'error');
  } finally {
    btn.disabled = false;
  }
});

// ---- Refresh Matches ----

el('refresh-matches-btn').addEventListener('click', async () => {
  const btn = el('refresh-matches-btn');
  btn.disabled = true;
  try {
    await loadMatches({ checkAvail: false });
  } finally {
    btn.disabled = false;
  }
});

let matchesAutoRefreshInterval = null;

el('auto-refresh-matches-btn').addEventListener('click', () => {
  const btn = el('auto-refresh-matches-btn');
  if (matchesAutoRefreshInterval) {
    clearInterval(matchesAutoRefreshInterval);
    matchesAutoRefreshInterval = null;
    btn.textContent = 'Auto-refresh: off';
    btn.classList.remove('btn-primary');
    btn.classList.add('btn-secondary');
  } else {
    matchesAutoRefreshInterval = setInterval(() => loadMatches({ checkAvail: false }), 30 * 1000);
    btn.textContent = 'Auto-refresh: 30s';
    btn.classList.remove('btn-secondary');
    btn.classList.add('btn-primary');
    loadMatches({ checkAvail: false }); // immediate refresh on enable
  }
});

// ---- Extend Cart Hold ----

el('extend-hold-btn').addEventListener('click', async () => {
  const btn = el('extend-hold-btn');
  btn.disabled = true;
  btn.textContent = '…';
  try {
    await api('POST', '/api/campsite/cart/extend');
    showToast('Cart hold extended ✓', 'success');
  } catch (err) {
    showToast(`Extend failed: ${err.message}`, 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Extend Hold';
  }
});

// ---- Settings Modal ----

el('settings-btn').addEventListener('click', openSettings);
el('settings-close').addEventListener('click', closeSettings);
el('settings-cancel').addEventListener('click', closeSettings);
el('settings-modal').querySelector('.modal-backdrop').addEventListener('click', closeSettings);

async function openSettings() {
  const s = await api('GET', '/api/campsite/settings');
  const form = el('settings-form');
  for (const [k, v] of Object.entries(s)) {
    const field = form.querySelector(`[name="${k}"]`);
    if (!field || v === null || v === undefined) continue;
    if (field.type === 'checkbox') {
      field.checked = v === 'true' || v === true;
    } else {
      field.value = v;
    }
  }
  // Don't pre-fill the cookie textarea with the masked value — leave blank if already set
  const cookieField = form.querySelector('[name="recgov_cookies"]');
  if (cookieField && s.recgov_cookies === '••••••••') cookieField.value = '';
  // Slack section visibility tracks the toggle live.
  const slackToggle = el('settings-slack-enabled');
  el('settings-slack-fields').classList.toggle('hidden', !slackToggle.checked);

  // Show saved Bearer token expiry status
  const tokenEl = el('token-status');
  if (tokenEl) {
    if (s.recgov_token === '••••••••') {
      const exp = s.recgov_token_expires ? new Date(s.recgov_token_expires) : null;
      const expired = s.recgov_token_expired;
      if (exp) {
        tokenEl.textContent = expired
          ? `⚠ Saved Bearer token expired ${exp.toLocaleString()} — paste a fresh cURL to refresh`
          : `✓ Bearer token saved, expires ${exp.toLocaleString()}`;
        tokenEl.style.color = expired ? 'var(--yellow)' : 'var(--green)';
      } else {
        tokenEl.textContent = '✓ Bearer token saved (no expiry info)';
        tokenEl.style.color = 'var(--green)';
      }
    } else {
      tokenEl.textContent = 'No Bearer token saved — paste a cURL from a Fetch/XHR request above';
      tokenEl.style.color = 'var(--text3)';
    }
  }

  el('settings-modal').classList.remove('hidden');
}

function closeSettings() {
  el('settings-modal').classList.add('hidden');
}

el('settings-slack-enabled').addEventListener('change', e => {
  el('settings-slack-fields').classList.toggle('hidden', !e.target.checked);
});

el('settings-form').addEventListener('submit', async e => {
  e.preventDefault();
  const data = {};
  new FormData(e.target).forEach((v, k) => { data[k] = v; });
  // FormData omits unchecked checkboxes; explicit 'false' is needed to persist a toggle off.
  data.slack_enabled = el('settings-slack-enabled').checked ? 'true' : 'false';
  try {
    await api('POST', '/api/campsite/settings', data);
    showToast('Settings saved', 'success');
    applySlackEnabledUI(data.slack_enabled === 'true');
    closeSettings();
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
});

// ---- Cookie parsing (client-side preview) ----

function extractCookiesFromCurl(input) {
  const s = (input || '').trim();
  if (!s.startsWith('curl ')) return s;
  const bMatch = s.match(/(?:-b|--cookie)\s+['"]([^'"]*)['"]/s);
  if (bMatch) return bMatch[1].trim();
  const hMatch = s.match(/-H\s+['"][Cc]ookie:\s*([^'"]*)['"]/s);
  if (hMatch) return hMatch[1].trim();
  return s;
}

el('recgov-cookies-input').addEventListener('input', () => {
  const val = el('recgov-cookies-input').value.trim();
  const resultEl = el('cookies-parse-result');
  if (!val) { resultEl.textContent = ''; return; }
  if (val.startsWith('curl ')) {
    const cookies = extractCookiesFromCurl(val);
    const count = cookies ? cookies.split(';').filter(s => s.includes('=')).length : 0;
    const hasBearer = /-H\s+['"][Aa]uthorization:\s+[Bb]earer\s+/i.test(val);
    const parts = [];
    if (count > 0) parts.push(`${count} cookies`);
    if (hasBearer) parts.push('Bearer token');
    if (parts.length) {
      resultEl.textContent = `✓ Detected: ${parts.join(' + ')}`;
      resultEl.style.color = 'var(--green)';
    } else {
      resultEl.textContent = '⚠ Could not parse cookies or Bearer token from cURL';
      resultEl.style.color = 'var(--yellow)';
    }
  } else {
    const count = val.split(';').filter(s => s.includes('=')).length;
    resultEl.textContent = `${count} cookie${count !== 1 ? 's' : ''}`;
    resultEl.style.color = 'var(--text3)';
  }
});

el('test-cookies-btn').addEventListener('click', async () => {
  const raw = el('recgov-cookies-input').value.trim();
  const resultEl = el('cookies-parse-result');
  if (!raw) {
    resultEl.textContent = 'Paste a cURL command or cookie string first';
    resultEl.style.color = 'var(--yellow)';
    return;
  }
  resultEl.textContent = 'Testing…';
  resultEl.style.color = 'var(--text3)';
  try {
    const result = await api('POST', '/api/campsite/settings/test-cookies', { raw });
    const tokenStatus = result.hasBearer
      ? (result.tokenExpired ? '⚠ Bearer token expired' : `Bearer token expires ${new Date(result.tokenExpires).toLocaleString()}`)
      : 'no Bearer token found';
    const cookiePart = result.count > 0 ? `${result.count} cookies · ` : '';
    if (result.loggedIn) {
      resultEl.textContent = `✓ Logged in (${cookiePart}${tokenStatus})`;
      resultEl.style.color = 'var(--green)';
    } else {
      resultEl.textContent = `✗ Not logged in — ${tokenStatus}`;
      resultEl.style.color = 'var(--red)';
    }
    // Save immediately so the Bearer token is stored
    if (raw) {
      await api('POST', '/api/campsite/settings', { recgov_cookies: raw }).catch(() => {});
    }
    refreshStatus();   // update header status dots
    loadTokenExpiry(); // update countdown with freshly saved token
  } catch (err) {
    resultEl.textContent = `✗ ${err.message}`;
    resultEl.style.color = 'var(--red)';
  }
});


el('test-slack-btn').addEventListener('click', async () => {
  // Send the values currently in the form so user can test without saving first.
  // Masked sentinel ('••••••••') means "use saved value" — pass empty so backend falls back.
  const form = el('settings-form');
  const tokenField = form.querySelector('[name="slack_token"]').value;
  const channelField = form.querySelector('[name="slack_channel"]').value;
  const body = {};
  if (tokenField && tokenField !== '••••••••') body.slack_token = tokenField;
  if (channelField) body.slack_channel = channelField;
  try {
    await api('POST', '/api/campsite/settings/test-slack', body);
    showToast('Slack test message sent!', 'success');
  } catch (err) {
    showToast(`Failed: ${err.message}`, 'error');
  }
});

el('clear-session-btn').addEventListener('click', async () => {
  if (!confirm('Clear saved browser session? You will need to log in again.')) return;
  try {
    await api('POST', '/api/campsite/settings/clear-session', {});
    el('chrome-test-result').textContent = 'Session cleared — log in again via Test browser session';
    el('chrome-test-result').style.color = 'var(--text3)';
    showToast('Session cleared', 'info');
  } catch (err) {
    showToast(`Failed: ${err.message}`, 'error');
  }
});

el('test-chrome-btn').addEventListener('click', async () => {
  const resultEl = el('chrome-test-result');
  resultEl.textContent = 'Validating token…';
  try {
    const result = await api('POST', '/api/campsite/settings/test-chrome', {});
    if (result.loggedIn) {
      const exp = result.tokenExpires ? ` (expires ${new Date(result.tokenExpires).toLocaleString()})` : '';
      resultEl.textContent = result.refreshed
        ? `✓ Token refreshed${exp}`
        : `✓ Token valid${exp}`;
      resultEl.style.color = 'var(--green)';
      showToast(result.refreshed ? 'Token refreshed ✓' : 'Token valid ✓', 'success');
    } else {
      const msg = result.error || 'token expired or missing — paste a fresh recaccount above and Save';
      resultEl.textContent = `⚠ ${msg}`;
      resultEl.style.color = 'var(--yellow)';
      showToast(msg, 'info', 6000);
    }
    refreshStatus(); // update header status dots
    loadTokenExpiry();
  } catch (err) {
    resultEl.textContent = `✗ ${err.message}`;
    resultEl.style.color = 'var(--red)';
    showToast(`Token validation failed: ${err.message}`, 'error');
  }
});

// ---- Server-Sent Events ----

function connectSSE() {
  const es = new EventSource('/api/campsite/events');

  es.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'connected') {
      updateDataStatus(msg.next_poll_at ? 'ok' : 'checking');
      if (msg.next_poll_at) { nextPollAt = msg.next_poll_at; updatePollCountdown(); }
    } else if (msg.type === 'poll_start') {
      isPollingNow = true;
      nextPollAt = null;
      updatePollCountdown();
    } else if (msg.type === 'poll_done') {
      isPollingNow = false;
      updateDataStatus('ok');
      nextPollAt = msg.next_at;
      updatePollCountdown();
      loadAlerts();
      loadMatches();
    } else if (msg.type === 'match') {
      const m = msg.data;
      showToast(`🏕 Site ${m.site} available at ${m.alert?.campground_name || m.campgroundId} — ${m.availableDates?.join(', ')}`, 'success', 8000);
      loadMatches({ checkAvail: false });
      loadAlerts();
    }
  };

  es.onerror = () => {
    updateDataStatus('err');
    setTimeout(connectSSE, 5000); // Reconnect
  };
}

// ---- Edit Alert Modal ----

el('edit-close').addEventListener('click', closeEditAlert);
el('edit-cancel').addEventListener('click', closeEditAlert);
el('edit-modal').querySelector('.modal-backdrop').addEventListener('click', closeEditAlert);

window.openEditAlert = function (id) {
  api('GET', '/api/campsite/alerts').then(alerts => {
    const a = alerts.find(x => x.id === id);
    if (!a) return;

    el('edit-alert-id').value = a.id;
    el('edit-campground-label').textContent = `Campground: ${a.campground_name} (#${a.campground_id})`;
    el('edit-start-date').value = a.start_date;
    el('edit-end-date').value = a.end_date;
    el('edit-min-nights').value = a.min_nights;
    el('edit-max-people').value = a.max_people || '';
    el('edit-notify-slack').checked = a.notify_slack !== false;
    el('edit-auto-cart').checked = Boolean(a.auto_cart);
    el('edit-stop-after-match').checked = a.stop_after_match !== false;

    for (const cb of el('edit-campsite-types').querySelectorAll('input')) {
      cb.checked = (a.campsite_types || []).includes(cb.value);
    }
    for (const cb of el('edit-equipment-types').querySelectorAll('input')) {
      cb.checked = (a.equipment_types || []).includes(cb.value);
    }
    el('edit-specific-sites').value = (a.specific_sites || []).join(', ');

    // Show re-activate toggle only for done alerts
    const reactivateRow = el('edit-reactivate-row');
    const reactivateCb = el('edit-reactivate');
    if (a.status === 'done') {
      reactivateRow.classList.remove('hidden');
      reactivateCb.checked = false;
    } else {
      reactivateRow.classList.add('hidden');
      reactivateCb.checked = false;
    }

    el('edit-modal').classList.remove('hidden');
  }).catch(err => showToast(`Error: ${err.message}`, 'error'));
};

function closeEditAlert() {
  el('edit-modal').classList.add('hidden');
}

el('edit-form').addEventListener('submit', async e => {
  e.preventDefault();

  const id = parseInt(el('edit-alert-id').value, 10);
  const startDate = el('edit-start-date').value;
  const endDate = el('edit-end-date').value;

  if (startDate >= endDate) {
    showToast('Departure must be after arrival', 'error');
    return;
  }

  const specificRaw = el('edit-specific-sites').value.trim();
  const specificSites = specificRaw ? specificRaw.split(',').map(s => s.trim().padStart(3, '0')).filter(Boolean) : [];

  const payload = {
    start_date: startDate,
    end_date: endDate,
    min_nights: parseInt(el('edit-min-nights').value || '1', 10),
    max_people: parseInt(el('edit-max-people').value || '0', 10) || null,
    campsite_types: [...el('edit-campsite-types').querySelectorAll('input:checked')].map(i => i.value),
    equipment_types: [...el('edit-equipment-types').querySelectorAll('input:checked')].map(i => i.value),
    specific_sites: specificSites,
    notify_slack: el('edit-notify-slack').checked,
    auto_cart: el('edit-auto-cart').checked,
    stop_after_match: el('edit-stop-after-match').checked,
  };

  // Re-activate if toggle is checked (only visible for done alerts)
  if (el('edit-reactivate').checked) {
    payload.status = 'active';
  }

  try {
    showToast('Checking integrations…', 'info', 3000);
    const errs = await preflightChecks(payload.auto_cart, payload.notify_slack);
    if (errs.length) {
      errs.forEach(msg => showToast(msg, 'error', 8000));
      return;
    }
    await api('PATCH', `/api/campsite/alerts/${id}`, payload);
    showToast('Alert updated', 'success');
    closeEditAlert();
    loadAlerts();
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
});

// ---- Date picker: auto-open on focus, dark theme via color-scheme:dark in CSS ----

document.addEventListener('focusin', e => {
  if (e.target.type === 'date') {
    try { e.target.showPicker(); } catch {}
  }
});

// ---- Bearer token countdown ----

let tokenExpiry = null; // Date object or null

function updateTokenCountdown() {
  const countdownEl = document.getElementById('token-countdown');
  const refreshBtn = document.getElementById('token-refresh-btn');
  if (!countdownEl) return;
  if (!tokenExpiry) {
    countdownEl.classList.add('hidden');
    if (refreshBtn) refreshBtn.classList.add('hidden');
    return;
  }

  const msLeft = tokenExpiry - Date.now();
  countdownEl.classList.remove('hidden');
  if (refreshBtn) refreshBtn.classList.remove('hidden');

  if (msLeft <= 0) {
    countdownEl.textContent = 'token expired';
    countdownEl.className = 'token-countdown expired';
    return;
  }

  const totalSec = Math.ceil(msLeft / 1000);
  const mins = Math.floor(totalSec / 60);
  const secs = totalSec % 60;
  countdownEl.textContent = mins > 0
    ? `${mins}m${secs > 0 ? ` ${secs}s` : ''}`
    : `${secs}s`;

  // Only override to green — yellow (refreshing) is set externally during a refresh call
  if (!countdownEl.classList.contains('refreshing')) {
    countdownEl.className = 'token-countdown';
  }
}

el('token-refresh-btn').addEventListener('click', async () => {
  const btn = el('token-refresh-btn');
  const countdownEl = el('token-countdown');
  const prev = btn.textContent;
  btn.textContent = '…';
  btn.disabled = true;
  if (countdownEl) countdownEl.className = 'token-countdown refreshing';
  try {
    const result = await api('POST', '/api/campsite/settings/refresh-token');
    const exp = result.expires ? new Date(result.expires).toLocaleString() : 'unknown';
    showToast(`Token refreshed — expires ${exp}`, 'success');
    await loadTokenExpiry();
  } catch (err) {
    showToast(`Refresh failed: ${err.message}`, 'error');
  } finally {
    btn.textContent = prev;
    btn.disabled = false;
    updateTokenCountdown(); // restores green
  }
});

async function loadTokenExpiry() {
  try {
    const s = await api('GET', '/api/campsite/settings');
    tokenExpiry = s.recgov_token_expires ? new Date(s.recgov_token_expires) : null;
    updateTokenCountdown();
  } catch { /* non-fatal */ }
}

// ---- Connection status ----

async function refreshStatus() {
  const dotRecgov = el('status-recgov');
  const lblRecgov = el('status-recgov-label');
  const dotLogin = el('status-login');
  const lblLogin = el('status-login-label');

  dotRecgov.className = 'status-dot checking';
  dotLogin.className = 'status-dot checking';

  try {
    const s = await api('GET', '/api/campsite/status');

    dotRecgov.className = 'status-dot ' + (s.recgovReachable ? 'ok' : 'err');
    dotRecgov.title = s.recgovReachable ? 'rec.gov reachable' : 'rec.gov unreachable';
    lblRecgov.textContent = 'rec.gov';

    dotLogin.className = 'status-dot ' + (s.loggedIn ? 'ok' : 'err');
    dotLogin.title = s.loggedIn ? 'Logged in to rec.gov' : 'Not logged in — open Settings to configure credentials';
  } catch {
    dotRecgov.className = 'status-dot err';
    dotLogin.className = 'status-dot err';
  }
}

// ---- Slack-enabled UI hide ----

// Toggles every Slack-related UI bit (settings section, alert form toggles).
// SlackNotifier already no-ops on empty creds, so this is a pure UI hide.
function applySlackEnabledUI(enabled) {
  document.querySelectorAll('.slack-only').forEach(elt => {
    elt.classList.toggle('hidden', !enabled);
    // Uncheck nested toggles when hiding so new alerts don't carry stale state.
    if (!enabled) {
      elt.querySelectorAll('input[type="checkbox"]').forEach(cb => { cb.checked = false; });
    }
  });
  const fields = el('settings-slack-fields');
  if (fields) fields.classList.toggle('hidden', !enabled);
  const settingsToggle = el('settings-slack-enabled');
  if (settingsToggle) settingsToggle.checked = enabled;
}

// ---- Onboarding wizard ----

const onboarding = {
  step: 1,
  recgovOk: false,
  slackChoice: null, // 'yes' | 'no' | null
  slackOk: false,
  slackToken: '',
  slackChannel: '',
  recgovExpires: null,
  // Snapshot at open time so re-trigger from a configured state can render
  // "✓ already connected" UI without prefilling masked sentinels.
  recgovConfigured: false,
  slackTokenConfigured: false,
};

function showOnboardingStep(n) {
  onboarding.step = n;
  document.querySelectorAll('#onboarding-modal .onboarding-step').forEach(s => {
    s.classList.toggle('hidden', parseInt(s.dataset.step, 10) !== n);
  });
  document.querySelectorAll('#onboarding-modal .progress-dot').forEach(d => {
    const step = parseInt(d.dataset.step, 10);
    d.classList.toggle('active', step === n);
    d.classList.toggle('done', step < n);
  });
}

async function openOnboarding() {
  // Pull current state to decide whether Step 1 shows "already connected" UI.
  const s = await api('GET', '/api/campsite/settings').catch(() => ({}));
  onboarding.recgovConfigured = s.recgov_token === '••••••••';
  onboarding.slackTokenConfigured = s.slack_token === '••••••••';
  onboarding.recgovOk = onboarding.recgovConfigured && !s.recgov_token_expired;
  onboarding.slackChoice = null;
  onboarding.slackOk = false;
  onboarding.slackToken = '';
  onboarding.slackChannel = '';
  onboarding.recgovExpires = s.recgov_token_expires || null;

  // Step 1 layout: configured-summary OR paste form
  const configuredEl = el('onboarding-recgov-configured');
  const inputWrap = el('onboarding-recgov-input-wrap');
  const input = el('onboarding-recgov-input');
  const result = el('onboarding-recgov-result');
  const next1 = el('onboarding-step1-next');
  input.value = '';
  result.textContent = '';
  if (onboarding.recgovConfigured) {
    configuredEl.classList.remove('hidden');
    inputWrap.classList.add('hidden');
    next1.disabled = !onboarding.recgovOk;
  } else {
    configuredEl.classList.add('hidden');
    inputWrap.classList.remove('hidden');
    next1.disabled = true;
  }

  // Step 2 layout reset
  el('onboarding-slack-choice').classList.remove('hidden');
  el('onboarding-slack-form').classList.add('hidden');
  el('onboarding-step2-next').classList.add('hidden');
  el('onboarding-step2-next').disabled = true;
  el('onboarding-slack-token').value = '';
  el('onboarding-slack-channel').value = '';
  el('onboarding-slack-result').textContent = '';

  showOnboardingStep(1);
  el('onboarding-modal').classList.remove('hidden');
}

function closeOnboarding() {
  el('onboarding-modal').classList.add('hidden');
}

el('onboarding-close').addEventListener('click', closeOnboarding);

el('onboarding-recgov-edit').addEventListener('click', () => {
  el('onboarding-recgov-configured').classList.add('hidden');
  el('onboarding-recgov-input-wrap').classList.remove('hidden');
  onboarding.recgovOk = false;
  el('onboarding-step1-next').disabled = true;
});

el('onboarding-recgov-validate').addEventListener('click', async () => {
  const raw = el('onboarding-recgov-input').value.trim();
  const result = el('onboarding-recgov-result');
  const next = el('onboarding-step1-next');
  if (!raw) {
    result.textContent = 'Paste the recaccount blob first';
    result.style.color = 'var(--yellow)';
    return;
  }
  result.textContent = 'Validating…';
  result.style.color = 'var(--text3)';
  try {
    // test-cookies persists what it parses, so a successful validate also saves.
    const r = await api('POST', '/api/campsite/settings/test-cookies', { raw });
    if (r.loggedIn) {
      const exp = r.tokenExpires ? new Date(r.tokenExpires).toLocaleString() : 'unknown';
      result.textContent = `✓ Connected, token valid until ${exp}`;
      result.style.color = 'var(--green)';
      onboarding.recgovOk = true;
      onboarding.recgovExpires = r.tokenExpires || null;
      next.disabled = false;
      refreshStatus();
      loadTokenExpiry();
    } else if (r.hasBearer && r.tokenExpired) {
      result.textContent = '⚠ Token parsed but expired — paste a fresher one';
      result.style.color = 'var(--yellow)';
      onboarding.recgovOk = false;
      next.disabled = true;
    } else {
      result.textContent = '✗ Couldn\'t find a token in this paste. Check you copied the full output.';
      result.style.color = 'var(--red)';
      onboarding.recgovOk = false;
      next.disabled = true;
    }
  } catch (err) {
    result.textContent = `✗ ${err.message}`;
    result.style.color = 'var(--red)';
    next.disabled = true;
  }
});

el('onboarding-step1-next').addEventListener('click', () => {
  if (!onboarding.recgovOk) return;
  showOnboardingStep(2);
});

el('onboarding-step2-back').addEventListener('click', () => {
  showOnboardingStep(1);
});

el('onboarding-slack-yes').addEventListener('click', () => {
  onboarding.slackChoice = 'yes';
  el('onboarding-slack-choice').classList.add('hidden');
  el('onboarding-slack-form').classList.remove('hidden');
  el('onboarding-step2-next').classList.remove('hidden');
  el('onboarding-step2-next').disabled = true; // gated by test message
});

el('onboarding-slack-no').addEventListener('click', async () => {
  onboarding.slackChoice = 'no';
  // Persist disabled flag and clear any stored creds so notifier truly skips.
  try {
    await api('POST', '/api/campsite/settings', {
      slack_enabled: 'false',
      slack_token: '',
      slack_channel: '',
    });
    applySlackEnabledUI(false);
  } catch (err) {
    showToast(`Couldn't save: ${err.message}`, 'error');
    return;
  }
  buildSummary();
  showOnboardingStep(3);
});

el('onboarding-slack-test').addEventListener('click', async () => {
  const token = el('onboarding-slack-token').value.trim();
  const channel = el('onboarding-slack-channel').value.trim();
  const result = el('onboarding-slack-result');
  if (!token || !channel) {
    result.textContent = 'Fill in both token and channel';
    result.style.color = 'var(--yellow)';
    return;
  }
  result.textContent = 'Sending…';
  result.style.color = 'var(--text3)';
  try {
    // test-slack now accepts candidate creds and does NOT persist on its own.
    await api('POST', '/api/campsite/settings/test-slack', {
      slack_token: token,
      slack_channel: channel,
    });
    result.textContent = '✓ Test message sent — check your channel.';
    result.style.color = 'var(--green)';
    onboarding.slackOk = true;
    onboarding.slackToken = token;
    onboarding.slackChannel = channel;
    el('onboarding-step2-next').disabled = false;
  } catch (err) {
    result.textContent = `✗ ${err.message}`;
    result.style.color = 'var(--red)';
    onboarding.slackOk = false;
    el('onboarding-step2-next').disabled = true;
  }
});

el('onboarding-step2-next').addEventListener('click', async () => {
  if (!onboarding.slackOk) return;
  // Save creds + enable flag now that the test passed.
  try {
    await api('POST', '/api/campsite/settings', {
      slack_enabled: 'true',
      slack_token: onboarding.slackToken,
      slack_channel: onboarding.slackChannel,
    });
    applySlackEnabledUI(true);
  } catch (err) {
    showToast(`Couldn't save: ${err.message}`, 'error');
    return;
  }
  buildSummary();
  showOnboardingStep(3);
});

function buildSummary() {
  const ul = el('onboarding-summary');
  const recgovLine = onboarding.recgovExpires
    ? `✓ recreation.gov: connected (expires ${new Date(onboarding.recgovExpires).toLocaleDateString()})`
    : `✓ recreation.gov: connected`;
  let slackLine;
  if (onboarding.slackChoice === 'yes' && onboarding.slackOk) {
    slackLine = `✓ Slack: ${onboarding.slackChannel}`;
  } else {
    slackLine = `— Slack: skipped (you can enable it later in Settings)`;
  }
  ul.innerHTML = `<li>${escapeHtml(recgovLine)}</li><li>${escapeHtml(slackLine)}</li>`;
}

el('onboarding-finish').addEventListener('click', () => {
  closeOnboarding();
  const form = el('alert-form');
  if (form) form.scrollIntoView({ behavior: 'smooth', block: 'start' });
});

el('settings-rerun-wizard').addEventListener('click', e => {
  e.preventDefault();
  closeSettings();
  openOnboarding();
});

// ---- Init ----

async function init() {
  await Promise.all([loadAlerts(), loadMatches()]);
  connectSSE();
  refreshStatus();
  loadTokenExpiry();
  const settingsForInit = await api('GET', '/api/campsite/settings').catch(() => ({}));
  if (settingsForInit.poll_interval) pollIntervalMs = parseInt(settingsForInit.poll_interval, 10) * 1000;

  // Slack UI hide based on saved flag (default true for backwards compat).
  const slackEnabled = settingsForInit.slack_enabled !== 'false';
  applySlackEnabledUI(slackEnabled);

  // First-run wizard: trigger only when rec.gov is unset. Slack state is
  // irrelevant — the tool can't function without rec.gov auth.
  const isFirstRun = settingsForInit.recgov_token === '';
  if (isFirstRun) openOnboarding();

  // Tick countdowns every second
  setInterval(() => { updateTokenCountdown(); updatePollCountdown(); }, 1000);

  // Fallback full refresh every 5 min in case SSE poll_done is missed
  setInterval(() => { loadAlerts(); loadMatches(); }, 5 * 60 * 1000);

  // Re-check connection status every 60s
  setInterval(refreshStatus, 60000);

  // Reload token expiry every 5 min (in case user saves new cURL in another tab)
  setInterval(loadTokenExpiry, 5 * 60 * 1000);
}

init();
