(function (root) {
  const Campsite = root.Campsite || {};
  const { api, el, showToast } = Campsite;

  function createCampsiteLive({ loadAlerts, loadMatches }) {
    let nextPollAt = null;
    let pollIntervalMs = 60000;
    let isPollingNow = false;
    let matchesAutoRefreshInterval = null;
    let eventSource = null;

    function updateDataStatus(state) {
      const dot = el('status-data');
      if (!dot) return;
      dot.className = 'status-dot ' + state;
      dot.title = {
        ok: 'Data is fresh \u2014 server is polling normally',
        err: 'Not receiving updates \u2014 connection to server may be lost',
        checking: 'Waiting for first refresh\u2026',
      }[state] ?? state;
    }

    function updatePollCountdown() {
      const el_ = el('poll-countdown');
      if (!el_) return;
      if (!nextPollAt && !isPollingNow) { el_.classList.add('hidden'); return; }

      if (isPollingNow) {
        el_.textContent = '\u2026';
        el_.className = 'poll-countdown refreshing';
        el_.classList.remove('hidden');
        return;
      }

      const msLeft = nextPollAt - Date.now();

      if (msLeft < -pollIntervalMs) {
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
      el_.className = 'poll-countdown';
      el_.classList.remove('hidden');
    }

    function initControls() {
      const pollNowBtn = el('poll-now-btn');
      if (pollNowBtn) {
        pollNowBtn.addEventListener('click', async () => {
          pollNowBtn.disabled = true;
          try {
            await api('POST', '/api/campsite/poll');
          } catch (err) {
            showToast(`Poll error: ${err.message}`, 'error');
          } finally {
            pollNowBtn.disabled = false;
          }
        });
      }

      const refreshMatchesBtn = el('refresh-matches-btn');
      if (refreshMatchesBtn) {
        refreshMatchesBtn.addEventListener('click', async () => {
          refreshMatchesBtn.disabled = true;
          try {
            await loadMatches({ checkAvail: false });
          } finally {
            refreshMatchesBtn.disabled = false;
          }
        });
      }

      const autoRefreshBtn = el('auto-refresh-matches-btn');
      if (autoRefreshBtn) {
        autoRefreshBtn.addEventListener('click', () => {
          if (matchesAutoRefreshInterval) {
            clearInterval(matchesAutoRefreshInterval);
            matchesAutoRefreshInterval = null;
            autoRefreshBtn.textContent = 'Auto-refresh: off';
            autoRefreshBtn.classList.remove('btn-primary');
            autoRefreshBtn.classList.add('btn-secondary');
          } else {
            matchesAutoRefreshInterval = setInterval(() => loadMatches({ checkAvail: false }), 30 * 1000);
            autoRefreshBtn.textContent = 'Auto-refresh: 30s';
            autoRefreshBtn.classList.remove('btn-secondary');
            autoRefreshBtn.classList.add('btn-primary');
            loadMatches({ checkAvail: false });
          }
        });
      }

      const extendHoldBtn = el('extend-hold-btn');
      if (extendHoldBtn) {
        extendHoldBtn.addEventListener('click', async () => {
          extendHoldBtn.disabled = true;
          extendHoldBtn.textContent = '\u2026';
          try {
            await api('POST', '/api/campsite/booking/cart/extend');
            showToast('Cart hold extended \u2713', 'success');
          } catch (err) {
            showToast(`Extend failed: ${err.message}`, 'error');
          } finally {
            extendHoldBtn.disabled = false;
            extendHoldBtn.textContent = 'Extend Hold';
          }
        });
      }
    }

    function connectSSE() {
      if (eventSource) eventSource.close();
      eventSource = new EventSource('/api/campsite/events');
      const es = eventSource;

      const parse = e => {
        try { return JSON.parse(e.data || '{}'); } catch { return {}; }
      };

      es.addEventListener('connected', () => {
        updateDataStatus('ok');
        root.__campsiteState = { ...(root.__campsiteState || {}), sseConnected: true };
      });

      es.addEventListener('poll_start', () => {
        isPollingNow = true;
        nextPollAt = null;
        updatePollCountdown();
      });

      es.addEventListener('poll_done', e => {
        const msg = parse(e);
        isPollingNow = false;
        updateDataStatus(msg.success === false ? 'err' : 'ok');
        nextPollAt = msg.next_at || msg.nextPollAt || null;
        updatePollCountdown();
        loadAlerts();
        loadMatches();
      });

      es.addEventListener('match', e => {
        const m = parse(e);
        const where = m.campgroundName || m.alert?.campground_name || m.campgroundId || 'campground';
        showToast(`Site ${m.site || m.campsiteSite || m.campsiteId || '?'} available at ${where} - ${(m.availableDates || []).join(', ')}`, 'success', 8000);
        loadMatches({ checkAvail: false });
        loadAlerts();
      });

      es.addEventListener('result', () => loadMatches({ checkAvail: false }));
      es.addEventListener('lease_expired', () => loadMatches({ checkAvail: false }));

      es.onerror = () => {
        updateDataStatus('err');
      };
    }

    function setPollIntervalMs(value) {
      pollIntervalMs = value;
    }

    return {
      connectSSE,
      initControls,
      setPollIntervalMs,
      updatePollCountdown,
    };
  }

  root.Campsite = {
    ...Campsite,
    createCampsiteLive,
  };
})(window);
