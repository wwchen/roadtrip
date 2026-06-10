(function (root) {
  const Campsite = root.Campsite || {};
  const { api, el, escapeHtml, fmtDate, fmtTimestamp, showToast, timeAgo } = Campsite;

  const STATUS_LABELS = {
    available: 'Still available',
    unavailable: 'No longer available',
    unqueryable: 'Cannot verify \u2014 no specific campsite ID',
  };

  function createCampsiteData() {
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
                <span>&#x1f4c5; ${fmtDate(a.start_date)} &ndash; ${fmtDate(a.end_date)}</span>
                <span>&#x1f319; ${a.min_nights}+ nights</span>
                ${a.max_people ? `<span>&#x1f465; ${a.max_people} people</span>` : ''}
                ${a.campsite_types?.length ? `<span>&#x1f3d5; ${a.campsite_types.join(', ')}</span>` : ''}
                ${a.auto_cart ? '<span style="color:var(--accent)">&#x26a1; Auto-cart</span>' : ''}
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

    async function toggleAlert(id, status) {
      try {
        await api('PATCH', `/api/campsite/alerts/${id}`, { status });
        loadAlerts();
      } catch (err) {
        showToast(`Error: ${err.message}`, 'error');
      }
    }

    async function deleteAlert(id) {
      if (!confirm('Delete this alert?')) return;
      try {
        await api('DELETE', `/api/campsite/alerts/${id}`);
        showToast('Alert deleted', 'info');
        loadAlerts();
      } catch (err) {
        showToast(`Error: ${err.message}`, 'error');
      }
    }

    async function loadMatches({ checkAvail = true } = {}) {
      const matches = await api('GET', '/api/campsite/matches?limit=30');
      const container = el('matches-list');
      el('matches-count').textContent = matches.length;

      if (matches.length === 0) {
        container.innerHTML = '<div class="empty-state">No matches yet &mdash; the poller will find open sites and show them here.</div>';
        return;
      }

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
              ${escapeHtml(m.campground_name)} &mdash; Site ${escapeHtml(m.campsite_site || m.campsite_id || '?')} ${m.campsite_loop ? `(${escapeHtml(m.campsite_loop)})` : ''}
            </div>
            <div class="match-dates">&#x1f4c5; ${m.available_dates.join(', ')} (${m.nights} night${m.nights > 1 ? 's' : ''})</div>
            <div class="match-meta">${m.campsite_type || ''} ${m.notified ? '&middot; &#x2709; notified' : ''} ${m.cart_added ? '&middot; &#x1f6d2; cart added' : ''}</div>
            <div class="match-found rel-time" title="${fmtTimestamp(m.found_at)}">Found ${timeAgo(m.found_at)}</div>
          </div>
          <div class="match-actions">
            <button class="btn btn-sm btn-primary" onclick="openMatch(${m.id})">Open &rarr;</button>
            <button class="btn btn-sm btn-secondary" onclick="cartMatch(${m.id})">Add to Cart</button>
            <button class="btn btn-sm btn-ghost btn-icon" onclick="deleteMatch(${m.id})" title="Remove match">&#x2715;</button>
          </div>
        </div>
      `).join('');

      for (const [id, status] of Object.entries(prevAvail)) {
        const card = document.getElementById(`match-card-${id}`);
        if (card) card.classList.add(status);
      }

      if (checkAvail) checkAllMatchAvailability(matches);
    }

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

    async function deleteMatch(id) {
      try {
        await api('DELETE', `/api/campsite/matches/${id}`);
        const card = document.getElementById(`match-card-${id}`);
        if (card) card.remove();
        const count = parseInt(el('matches-count').textContent || '0', 10);
        el('matches-count').textContent = Math.max(0, count - 1);
      } catch (err) {
        showToast(`Error: ${err.message}`, 'error');
      }
    }

    async function openMatch(id) {
      try {
        const result = await api('POST', `/api/campsite/matches/${id}/cart`, { action: 'open' });
        if (result.url) root.open(result.url, '_blank');
      } catch (err) {
        showToast(`Error: ${err.message}`, 'error');
      }
    }

    async function cartMatch(id) {
      showToast('Queuing add-to-cart for companion\u2026', 'info');
      try {
        const result = await api('POST', `/api/campsite/matches/${id}/cart`, {});
        if (result.cart_added) {
          showToast('Added to cart! Complete checkout in browser.', 'success', 6000);
        } else if (result.queued) {
          showToast('Match re-queued \u2014 companion will run ATC and report back via the matches list.', 'info', 6000);
        } else {
          showToast('Cart request accepted \u2014 see matches list for result.', 'info', 6000);
        }
        loadMatches({ checkAvail: false });
      } catch (err) {
        showToast(`Error: ${err.message}`, 'error');
      }
    }

    function installGlobals() {
      root.toggleAlert = toggleAlert;
      root.deleteAlert = deleteAlert;
      root.deleteMatch = deleteMatch;
      root.openMatch = openMatch;
      root.cartMatch = cartMatch;
    }

    return {
      loadAlerts,
      loadMatches,
      checkMatchAvailability,
      installGlobals,
    };
  }

  root.Campsite = {
    ...Campsite,
    createCampsiteData,
  };
})(window);
