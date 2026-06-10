(function (root) {
  const Campsite = root.Campsite || {};

  function el(id) {
    return document.getElementById(id);
  }

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
    if (!iso) return '\u2014';
    return new Date(iso + (iso.includes('T') ? '' : 'T00:00:00Z')).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
  }

  function fmtTimestamp(iso) {
    if (!iso) return '';
    return new Date(iso.includes('T') ? iso : iso + 'T00:00:00Z')
      .toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' });
  }

  function timeAgo(iso) {
    if (!iso) return '\u2014';
    const diff = Date.now() - new Date(iso.includes('T') ? iso : iso + 'T00:00:00Z').getTime();
    const m = Math.floor(diff / 60000);
    if (m < 1) return 'just now';
    if (m < 60) return `${m}m ago`;
    const h = Math.floor(m / 60);
    if (h < 24) return `${h}h ago`;
    return `${Math.floor(h / 24)}d ago`;
  }

  function escapeHtml(str) {
    return String(str || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  root.Campsite = {
    ...Campsite,
    el,
    showToast,
    api,
    fmtDate,
    fmtTimestamp,
    timeAgo,
    escapeHtml,
  };
})(window);
