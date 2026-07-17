import { listWatches, getWatch, createWatch, updateWatch, deleteWatch } from '../api/watches-api.js';
import { fetchPoiDetail } from '../api/poi-api.js';
import { notifyWatchesChanged } from '../availability/watch-events.js';
import { mountBanner } from '../design-system/banner.js';
import { mountWatchForm } from './watch-form.js';
import { mountWatchTable } from './watch-table.js';

const WATCH_LIST_LIMIT = 200;

let bannerCtrl = null;
let formCtrl = null;
let tableCtrl = null;
const poiNameCache = new Map();

async function init() {
  const bannerHost = document.getElementById('banner-host');
  const formHost = document.getElementById('form-host');
  const tableHost = document.getElementById('table-host');

  formCtrl = mountWatchForm(formHost, {
    mode: 'create',
    onSubmit: handleSubmit,
    onCancel: handleCancel,
  });

  tableCtrl = mountWatchTable(tableHost, {
    watches: [],
    poiNames: poiNameCache,
    onEdit: handleEdit,
    onPauseResume: handlePauseResume,
    onDelete: handleDelete,
  });

  await loadWatches();
  applyUrlAction(bannerHost);
}

async function loadWatches() {
  const [active, paused, done] = await Promise.all([
    listWatches({ status: 'active', limit: WATCH_LIST_LIMIT }),
    listWatches({ status: 'paused', limit: WATCH_LIST_LIMIT }),
    listWatches({ status: 'done', limit: WATCH_LIST_LIMIT }),
  ]);
  const watches = [
    ...(active?.watches || []),
    ...(paused?.watches || []),
    ...(done?.watches || []),
  ].sort(byStartDate);
  await ensurePoiNames(watches);
  tableCtrl.update({ watches, poiNames: poiNameCache });
}

async function ensurePoiNames(list) {
  const ids = [...new Set(list.map((w) => w.poi_id).filter((id) => id != null && !poiNameCache.has(id)))];
  await Promise.all(ids.map(async (id) => {
    try {
      const d = await fetchPoiDetail(id);
      poiNameCache.set(id, d?.properties?.name || d?.name || `POI ${id}`);
    } catch {
      poiNameCache.set(id, `POI ${id}`);
    }
  }));
}

async function handleSubmit(data) {
  formCtrl.setLoading(true);
  formCtrl.setError(null);
  const bannerHost = document.getElementById('banner-host');
  const editingId = formCtrl.getEditingId();
  try {
    if (editingId) {
      await updateWatch(editingId, data);
      showBanner(bannerHost, 'success', `Watch #${editingId} updated.`);
    } else {
      await createWatch(data);
      showBanner(bannerHost, 'success', `Watch created for POI ${data.poi_id}.`);
    }
    formCtrl.setMode('create', null);
    notifyWatchesChanged();
    await loadWatches();
  } catch (err) {
    formCtrl.setError(err?.message || 'Could not save. Try again.');
  } finally {
    formCtrl.setLoading(false);
  }
}

function handleCancel() {
  formCtrl.setMode('create', null);
}

async function handleEdit(id) {
  const bannerHost = document.getElementById('banner-host');
  try {
    const detail = await getWatch(id);
    const watch = detail.watch || detail;
    formCtrl.setMode('edit', watch);
    document.getElementById('form-host')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch {
    showBanner(bannerHost, 'error', 'Could not load watch for editing.');
  }
}

async function handlePauseResume(id, newStatus) {
  const bannerHost = document.getElementById('banner-host');
  try {
    await updateWatch(id, { status: newStatus });
    notifyWatchesChanged();
    await loadWatches();
  } catch {
    showBanner(bannerHost, 'error', 'Could not update watch status.');
  }
}

async function handleDelete(id) {
  const bannerHost = document.getElementById('banner-host');
  try {
    await deleteWatch(id);
    notifyWatchesChanged();
    showBanner(bannerHost, 'success', `Watch #${id} deleted.`);
    if (String(formCtrl.getEditingId()) === String(id)) {
      formCtrl.setMode('create', null);
    }
    await loadWatches();
  } catch {
    showBanner(bannerHost, 'error', 'Could not delete watch.');
  }
}

async function applyUrlAction(bannerHost) {
  const params = new URLSearchParams(window.location.search);
  const action = params.get('action');
  if (!action) return;

  const id = params.get('id');
  const poiId = params.get('poi_id');
  const startDate = params.get('start_date');

  clearUrlParams();

  if (action === 'create') {
    const prefill = {};
    if (poiId) prefill.poi_id = poiId;
    if (startDate) prefill.start_date = startDate;
    formCtrl.setMode('create', prefill);
  } else if (action === 'modify' && id) {
    await handleEdit(id);
  } else if (action === 'delete' && id) {
    try {
      await deleteWatch(id);
      notifyWatchesChanged();
      showBanner(bannerHost, 'success', `Watch #${id} deleted.`);
      await loadWatches();
    } catch {
      showBanner(bannerHost, 'error', 'Could not delete watch.');
    }
  }
}

function clearUrlParams() {
  const url = new URL(window.location.href);
  url.search = '';
  window.history.replaceState(null, '', `${url.pathname}${url.hash}`);
}

function showBanner(host, type, message) {
  bannerCtrl?.dispose();
  bannerCtrl = mountBanner(host, { type, message, dismissable: true, onDismiss: () => { bannerCtrl = null; } });
}

function byStartDate(a, b) {
  const da = a.start_date ?? '';
  const db = b.start_date ?? '';
  if (da === db) return 0;
  if (!da) return 1;
  if (!db) return -1;
  return da < db ? -1 : 1;
}

function initTestNotifications() {
  const slackBtn = document.getElementById('test-slack-btn');
  const emailBtn = document.getElementById('test-email-btn');

  slackBtn?.addEventListener('click', async () => {
    const channel = document.getElementById('test-slack-channel')?.value.trim() || undefined;
    const status = document.getElementById('test-slack-status');
    slackBtn.disabled = true;
    status.textContent = 'Sending…';
    status.className = 'test-status';
    try {
      const body = channel ? JSON.stringify({ channel }) : '{}';
      const resp = await fetch('/test/slack', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body });
      if (resp.ok) {
        status.textContent = '✓ Sent';
        status.className = 'test-status ok';
      } else {
        const data = await resp.json().catch(() => ({}));
        status.textContent = data.detail || 'Failed';
        status.className = 'test-status err';
      }
    } catch {
      status.textContent = 'Network error';
      status.className = 'test-status err';
    } finally {
      slackBtn.disabled = false;
    }
  });

  emailBtn?.addEventListener('click', async () => {
    const to = document.getElementById('test-email-to')?.value.trim();
    const status = document.getElementById('test-email-status');
    if (!to) {
      status.textContent = 'Enter an email address';
      status.className = 'test-status err';
      return;
    }
    emailBtn.disabled = true;
    status.textContent = 'Sending…';
    status.className = 'test-status';
    try {
      const resp = await fetch('/test/email', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ to }) });
      if (resp.ok) {
        status.textContent = '✓ Sent';
        status.className = 'test-status ok';
      } else {
        const data = await resp.json().catch(() => ({}));
        status.textContent = data.detail || 'Failed';
        status.className = 'test-status err';
      }
    } catch {
      status.textContent = 'Network error';
      status.className = 'test-status err';
    } finally {
      emailBtn.disabled = false;
    }
  });
}

init();
initTestNotifications();
