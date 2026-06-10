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
const campsiteSettings = window.Campsite.createCampsiteSettings({ onRerunWizard: openOnboarding });
campsiteSettings.initControls();

function clearCampground() {
  campgroundSearch.clear();
}

window.clearCampground = clearCampground;

const campsiteAlertForms = window.Campsite.createCampsiteAlertForms({ clearCampground, loadAlerts, selectedCampgrounds });
campsiteAlertForms.initControls();

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
      campsiteSettings.refreshStatus();
      campsiteSettings.loadTokenExpiry();
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
    campsiteSettings.applySlackEnabledUI(false);
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
    campsiteSettings.applySlackEnabledUI(true);
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

// ---- Init ----

async function init() {
  await Promise.all([loadAlerts(), loadMatches()]);
  await campgroundSearch.prefillFromUrl();
  campsiteLive.connectSSE();
  campsiteSettings.refreshStatus();
  campsiteSettings.loadTokenExpiry();
  const settingsForInit = await api('GET', '/api/campsite/settings').catch(() => ({}));
  if (settingsForInit.poll_interval) campsiteLive.setPollIntervalMs(parseInt(settingsForInit.poll_interval, 10) * 1000);

  // Slack UI hide based on saved flag (default true for backwards compat).
  const slackEnabled = settingsForInit.slack_enabled !== 'false';
  campsiteSettings.applySlackEnabledUI(slackEnabled);

  // First-run wizard: trigger only when rec.gov is unset. Slack state is
  // irrelevant — the tool can't function without rec.gov auth.
  const isFirstRun = settingsForInit.recgov_token === '';
  if (isFirstRun) openOnboarding();

  // Tick countdowns every second
  setInterval(() => { campsiteSettings.updateTokenCountdown(); campsiteLive.updatePollCountdown(); }, 1000);

  // Fallback full refresh every 5 min in case SSE poll_done is missed
  setInterval(() => { loadAlerts(); loadMatches(); }, 5 * 60 * 1000);

  // Re-check connection status every 60s
  setInterval(campsiteSettings.refreshStatus, 60000);

  // Reload token expiry every 5 min (in case user saves new cURL in another tab)
  setInterval(campsiteSettings.loadTokenExpiry, 5 * 60 * 1000);
}

init();
