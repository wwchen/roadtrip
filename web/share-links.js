const ROUTE_SCHEMA_VERSION = 1;

function appUrl() {
  if (typeof window === 'undefined') return '';
  return new URL(window.location.pathname || '/', window.location.origin);
}

function bytesToBase64Url(bytes) {
  let binary = '';
  bytes.forEach(b => { binary += String.fromCharCode(b); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function base64UrlToBytes(value) {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=');
  const binary = atob(padded);
  return Uint8Array.from(binary, c => c.charCodeAt(0));
}

function roundCoord(n) {
  return Math.round(Number(n) * 1e6) / 1e6;
}

function normalizeStop(stop) {
  const lng = roundCoord(stop?.lng);
  const lat = roundCoord(stop?.lat);
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null;
  return {
    name: String(stop?.name || 'Stop').slice(0, 160),
    lng,
    lat,
    kind: String(stop?.kind || 'PLACE').slice(0, 24),
  };
}

export function poiShareUrl(id) {
  if (id == null || id === '') return '';
  const url = appUrl();
  url.searchParams.set('poi', String(id));
  return url.toString();
}

export function encodeRouteState(stops, corridorMiles) {
  const normalized = (stops || []).map(normalizeStop).filter(Boolean);
  if (normalized.length < 2) return '';
  const payload = {
    v: ROUTE_SCHEMA_VERSION,
    radius_miles: Number(corridorMiles) || undefined,
    stops: normalized,
  };
  return bytesToBase64Url(new TextEncoder().encode(JSON.stringify(payload)));
}

export function routeShareUrl(stops, corridorMiles) {
  const encoded = encodeRouteState(stops, corridorMiles);
  if (!encoded) return '';
  const url = appUrl();
  url.searchParams.set('route', encoded);
  return url.toString();
}

export function replaceVisibleUrl(url) {
  if (typeof window === 'undefined' || !url) return;
  try {
    const next = new URL(url, window.location.href);
    if (next.origin !== window.location.origin) return;
    const nextPath = `${next.pathname}${next.search}${next.hash}`;
    const currentPath = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    if (nextPath !== currentPath) {
      window.history.replaceState(window.history.state, '', nextPath);
    }
  } catch (_) {
    // Bad share URLs should fail closed; callers still have a working copy
    // button if URL replacement is unavailable.
  }
}

export function clearVisibleShareUrl() {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  url.searchParams.delete('poi');
  url.searchParams.delete('route');
  replaceVisibleUrl(url.toString());
}

export function decodeRouteState(value) {
  if (!value || typeof value !== 'string') return null;
  try {
    const json = new TextDecoder().decode(base64UrlToBytes(value));
    const payload = JSON.parse(json);
    if (payload?.v !== ROUTE_SCHEMA_VERSION || !Array.isArray(payload.stops)) return null;
    const stops = payload.stops.map(normalizeStop).filter(Boolean);
    if (stops.length < 2) return null;
    const radius = Number(payload.radius_miles);
    return {
      stops,
      corridorMiles: Number.isFinite(radius) ? radius : null,
    };
  } catch (_) {
    return null;
  }
}

function setCopiedState(el) {
  if (!el) return;
  const oldTitle = el.getAttribute('title') || '';
  const oldLabel = el.getAttribute('aria-label') || '';
  el.classList.add('copied');
  el.setAttribute('title', 'Copied');
  el.setAttribute('aria-label', 'Copied');
  clearTimeout(el._rtShareResetTimer);
  el._rtShareResetTimer = setTimeout(() => {
    el.classList.remove('copied');
    if (oldTitle) el.setAttribute('title', oldTitle);
    if (oldLabel) el.setAttribute('aria-label', oldLabel);
  }, 1600);
}

function fallbackCopy(text) {
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.cssText = 'position:fixed;top:-1000px;left:-1000px;opacity:0;';
  document.body.appendChild(ta);
  ta.select();
  let ok = false;
  try { ok = document.execCommand('copy'); } catch (_) { ok = false; }
  ta.remove();
  return ok;
}

export async function copyShareUrl(url, { sourceEl = null } = {}) {
  if (!url) return false;
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(url);
      setCopiedState(sourceEl);
      return true;
    }
  } catch (_) {
    // Fall through to the textarea path. Clipboard permission can be blocked
    // in headless browsers or non-secure contexts.
  }
  const ok = fallbackCopy(url);
  if (ok) setCopiedState(sourceEl);
  return ok;
}
