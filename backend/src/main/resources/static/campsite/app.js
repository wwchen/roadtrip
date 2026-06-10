// ---- Shared frontend services ----

const {
  el,
  showToast,
  api,
  escapeHtml,
} = window.Campsite;

const campgroundSearch = window.Campsite.createCampgroundSearch();
campgroundSearch.init();
const selectedCampgrounds = campgroundSearch.selectedCampgrounds;
const campsiteData = window.Campsite.createCampsiteData();
campsiteData.installGlobals();
const { loadAlerts, loadMatches } = campsiteData;
const campsiteLive = window.Campsite.createCampsiteLive({ loadAlerts, loadMatches });
campsiteLive.initControls();

function clearCampground() {
  campgroundSearch.clear();
}

window.clearCampground = clearCampground;

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
  await campgroundSearch.prefillFromUrl();
  campsiteLive.connectSSE();
  refreshStatus();
  loadTokenExpiry();
  const settingsForInit = await api('GET', '/api/campsite/settings').catch(() => ({}));
  if (settingsForInit.poll_interval) campsiteLive.setPollIntervalMs(parseInt(settingsForInit.poll_interval, 10) * 1000);

  // Slack UI hide based on saved flag (default true for backwards compat).
  const slackEnabled = settingsForInit.slack_enabled !== 'false';
  applySlackEnabledUI(slackEnabled);

  // First-run wizard: trigger only when rec.gov is unset. Slack state is
  // irrelevant — the tool can't function without rec.gov auth.
  const isFirstRun = settingsForInit.recgov_token === '';
  if (isFirstRun) openOnboarding();

  // Tick countdowns every second
  setInterval(() => { updateTokenCountdown(); campsiteLive.updatePollCountdown(); }, 1000);

  // Fallback full refresh every 5 min in case SSE poll_done is missed
  setInterval(() => { loadAlerts(); loadMatches(); }, 5 * 60 * 1000);

  // Re-check connection status every 60s
  setInterval(refreshStatus, 60000);

  // Reload token expiry every 5 min (in case user saves new cURL in another tab)
  setInterval(loadTokenExpiry, 5 * 60 * 1000);
}

init();
