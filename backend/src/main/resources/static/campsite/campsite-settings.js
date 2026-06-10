(function (root) {
  const Campsite = root.Campsite || {};
  const { api, el, showToast } = Campsite;
  const MASKED = '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022';

  function createCampsiteSettings({ onRerunWizard } = {}) {
    let tokenExpiry = null;

    function initControls() {
      el('settings-btn')?.addEventListener('click', openSettings);
      el('settings-close')?.addEventListener('click', closeSettings);
      el('settings-cancel')?.addEventListener('click', closeSettings);
      el('settings-modal')?.querySelector('.modal-backdrop')?.addEventListener('click', closeSettings);

      el('settings-slack-enabled')?.addEventListener('change', e => {
        el('settings-slack-fields')?.classList.toggle('hidden', !e.target.checked);
      });

      el('settings-form')?.addEventListener('submit', saveSettings);
      el('recgov-cookies-input')?.addEventListener('input', previewCookies);
      el('test-cookies-btn')?.addEventListener('click', testCookies);
      el('test-slack-btn')?.addEventListener('click', testSlack);
      el('clear-session-btn')?.addEventListener('click', clearSession);
      el('test-chrome-btn')?.addEventListener('click', testChrome);
      el('token-refresh-btn')?.addEventListener('click', refreshToken);

      el('settings-rerun-wizard')?.addEventListener('click', e => {
        e.preventDefault();
        closeSettings();
        onRerunWizard?.();
      });
    }

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

      const cookieField = form.querySelector('[name="recgov_cookies"]');
      if (cookieField && s.recgov_cookies === MASKED) cookieField.value = '';

      const slackToggle = el('settings-slack-enabled');
      el('settings-slack-fields')?.classList.toggle('hidden', !slackToggle.checked);

      const tokenEl = el('token-status');
      if (tokenEl) {
        if (s.recgov_token === MASKED) {
          const exp = s.recgov_token_expires ? new Date(s.recgov_token_expires) : null;
          const expired = s.recgov_token_expired;
          if (exp) {
            tokenEl.textContent = expired
              ? `\u26a0 Saved Bearer token expired ${exp.toLocaleString()} \u2014 paste a fresh cURL to refresh`
              : `\u2713 Bearer token saved, expires ${exp.toLocaleString()}`;
            tokenEl.style.color = expired ? 'var(--yellow)' : 'var(--green)';
          } else {
            tokenEl.textContent = '\u2713 Bearer token saved (no expiry info)';
            tokenEl.style.color = 'var(--green)';
          }
        } else {
          tokenEl.textContent = 'No Bearer token saved \u2014 paste a cURL from a Fetch/XHR request above';
          tokenEl.style.color = 'var(--text3)';
        }
      }

      el('settings-modal').classList.remove('hidden');
    }

    function closeSettings() {
      el('settings-modal').classList.add('hidden');
    }

    async function saveSettings(e) {
      e.preventDefault();
      const data = {};
      new FormData(e.target).forEach((v, k) => { data[k] = v; });
      data.slack_enabled = el('settings-slack-enabled').checked ? 'true' : 'false';
      try {
        await api('POST', '/api/campsite/settings', data);
        showToast('Settings saved', 'success');
        applySlackEnabledUI(data.slack_enabled === 'true');
        closeSettings();
      } catch (err) {
        showToast(`Error: ${err.message}`, 'error');
      }
    }

    function extractCookiesFromCurl(input) {
      const s = (input || '').trim();
      if (!s.startsWith('curl ')) return s;
      const bMatch = s.match(/(?:-b|--cookie)\s+['"]([^'"]*)['"]/s);
      if (bMatch) return bMatch[1].trim();
      const hMatch = s.match(/-H\s+['"][Cc]ookie:\s*([^'"]*)['"]/s);
      if (hMatch) return hMatch[1].trim();
      return s;
    }

    function previewCookies() {
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
          resultEl.textContent = `\u2713 Detected: ${parts.join(' + ')}`;
          resultEl.style.color = 'var(--green)';
        } else {
          resultEl.textContent = '\u26a0 Could not parse cookies or Bearer token from cURL';
          resultEl.style.color = 'var(--yellow)';
        }
      } else {
        const count = val.split(';').filter(s => s.includes('=')).length;
        resultEl.textContent = `${count} cookie${count !== 1 ? 's' : ''}`;
        resultEl.style.color = 'var(--text3)';
      }
    }

    async function testCookies() {
      const raw = el('recgov-cookies-input').value.trim();
      const resultEl = el('cookies-parse-result');
      if (!raw) {
        resultEl.textContent = 'Paste a cURL command or cookie string first';
        resultEl.style.color = 'var(--yellow)';
        return;
      }
      resultEl.textContent = 'Testing...';
      resultEl.style.color = 'var(--text3)';
      try {
        const result = await api('POST', '/api/campsite/settings/test-cookies', { raw });
        const tokenStatus = result.hasBearer
          ? (result.tokenExpired ? '\u26a0 Bearer token expired' : `Bearer token expires ${new Date(result.tokenExpires).toLocaleString()}`)
          : 'no Bearer token found';
        const cookiePart = result.count > 0 ? `${result.count} cookies \u00b7 ` : '';
        if (result.loggedIn) {
          resultEl.textContent = `\u2713 Logged in (${cookiePart}${tokenStatus})`;
          resultEl.style.color = 'var(--green)';
        } else {
          resultEl.textContent = `\u2717 Not logged in \u2014 ${tokenStatus}`;
          resultEl.style.color = 'var(--red)';
        }
        if (raw) {
          await api('POST', '/api/campsite/settings', { recgov_cookies: raw }).catch(() => {});
        }
        refreshStatus();
        loadTokenExpiry();
      } catch (err) {
        resultEl.textContent = `\u2717 ${err.message}`;
        resultEl.style.color = 'var(--red)';
      }
    }

    async function testSlack() {
      const form = el('settings-form');
      const tokenField = form.querySelector('[name="slack_token"]').value;
      const channelField = form.querySelector('[name="slack_channel"]').value;
      const body = {};
      if (tokenField && tokenField !== MASKED) body.slack_token = tokenField;
      if (channelField) body.slack_channel = channelField;
      try {
        await api('POST', '/api/campsite/settings/test-slack', body);
        showToast('Slack test message sent!', 'success');
      } catch (err) {
        showToast(`Failed: ${err.message}`, 'error');
      }
    }

    async function clearSession() {
      if (!confirm('Clear saved browser session? You will need to log in again.')) return;
      try {
        await api('POST', '/api/campsite/settings/clear-session', {});
        el('chrome-test-result').textContent = 'Session cleared \u2014 log in again via Test browser session';
        el('chrome-test-result').style.color = 'var(--text3)';
        showToast('Session cleared', 'info');
      } catch (err) {
        showToast(`Failed: ${err.message}`, 'error');
      }
    }

    async function testChrome() {
      const resultEl = el('chrome-test-result');
      resultEl.textContent = 'Validating token...';
      try {
        const result = await api('POST', '/api/campsite/settings/test-chrome', {});
        if (result.loggedIn) {
          const exp = result.tokenExpires ? ` (expires ${new Date(result.tokenExpires).toLocaleString()})` : '';
          resultEl.textContent = result.refreshed
            ? `\u2713 Token refreshed${exp}`
            : `\u2713 Token valid${exp}`;
          resultEl.style.color = 'var(--green)';
          showToast(result.refreshed ? 'Token refreshed \u2713' : 'Token valid \u2713', 'success');
        } else {
          const msg = result.error || 'token expired or missing \u2014 paste a fresh recaccount above and Save';
          resultEl.textContent = `\u26a0 ${msg}`;
          resultEl.style.color = 'var(--yellow)';
          showToast(msg, 'info', 6000);
        }
        refreshStatus();
        loadTokenExpiry();
      } catch (err) {
        resultEl.textContent = `\u2717 ${err.message}`;
        resultEl.style.color = 'var(--red)';
        showToast(`Token validation failed: ${err.message}`, 'error');
      }
    }

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

      if (!countdownEl.classList.contains('refreshing')) {
        countdownEl.className = 'token-countdown';
      }
    }

    async function refreshToken() {
      const btn = el('token-refresh-btn');
      const countdownEl = el('token-countdown');
      const prev = btn.textContent;
      btn.textContent = '\u2026';
      btn.disabled = true;
      if (countdownEl) countdownEl.className = 'token-countdown refreshing';
      try {
        const result = await api('POST', '/api/campsite/settings/refresh-token');
        const exp = result.expires ? new Date(result.expires).toLocaleString() : 'unknown';
        showToast(`Token refreshed \u2014 expires ${exp}`, 'success');
        await loadTokenExpiry();
      } catch (err) {
        showToast(`Refresh failed: ${err.message}`, 'error');
      } finally {
        btn.textContent = prev;
        btn.disabled = false;
        updateTokenCountdown();
      }
    }

    async function loadTokenExpiry() {
      try {
        const s = await api('GET', '/api/campsite/settings');
        tokenExpiry = s.recgov_token_expires ? new Date(s.recgov_token_expires) : null;
        updateTokenCountdown();
      } catch { /* non-fatal */ }
    }

    async function refreshStatus() {
      const dotRecgov = el('status-recgov');
      const lblRecgov = el('status-recgov-label');
      const dotLogin = el('status-login');

      dotRecgov.className = 'status-dot checking';
      dotLogin.className = 'status-dot checking';

      try {
        const s = await api('GET', '/api/campsite/status');

        dotRecgov.className = 'status-dot ' + (s.recgovReachable ? 'ok' : 'err');
        dotRecgov.title = s.recgovReachable ? 'rec.gov reachable' : 'rec.gov unreachable';
        lblRecgov.textContent = 'rec.gov';

        dotLogin.className = 'status-dot ' + (s.loggedIn ? 'ok' : 'err');
        dotLogin.title = s.loggedIn ? 'Logged in to rec.gov' : 'Not logged in \u2014 open Settings to configure credentials';
      } catch {
        dotRecgov.className = 'status-dot err';
        dotLogin.className = 'status-dot err';
      }
    }

    function applySlackEnabledUI(enabled) {
      document.querySelectorAll('.slack-only').forEach(elt => {
        elt.classList.toggle('hidden', !enabled);
        if (!enabled) {
          elt.querySelectorAll('input[type="checkbox"]').forEach(cb => { cb.checked = false; });
        }
      });
      const fields = el('settings-slack-fields');
      if (fields) fields.classList.toggle('hidden', !enabled);
      const settingsToggle = el('settings-slack-enabled');
      if (settingsToggle) settingsToggle.checked = enabled;
    }

    return {
      applySlackEnabledUI,
      closeSettings,
      extractCookiesFromCurl,
      initControls,
      loadTokenExpiry,
      openSettings,
      refreshStatus,
      updateTokenCountdown,
    };
  }

  root.Campsite = {
    ...Campsite,
    createCampsiteSettings,
  };
})(window);
