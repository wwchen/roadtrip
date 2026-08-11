// Shareable URLs for a POI and for a whole trip.
//
// Typed port of web/share-links.js. The wire format is a contract, not an
// implementation detail: a link someone pasted into Slack last month has to keep
// opening the same trip, so the schema version, the coordinate rounding and the
// base64url alphabet are all preserved exactly. `decodeRouteState` is therefore
// tested against strings produced by the legacy encoder as well as by this one.
//
// Phase 4c already ports the `?poi=` half of the reader (features/map/
// useDeepLinkedPoi.ts). This module owns the writers, the `?route=` reader, and
// the clipboard helper the drawer's share button needs.

/** Bumped only for a format change the old decoder could not read. */
const ROUTE_SCHEMA_VERSION = 1;

/** Six decimal places is ~11cm — far past what a routing engine resolves. */
const COORD_PRECISION = 1e6;
/** Names come from geocoder output, which has no length contract of its own. */
const MAX_STOP_NAME_CHARS = 160;
const MAX_STOP_KIND_CHARS = 24;
/** A route needs two ends before it is a route. */
const MIN_SHAREABLE_STOPS = 2;
/** How long the copy button reads "Copied" before returning to its label. */
export const COPIED_STATE_MS = 1600;

export interface ShareableStop {
  name: string;
  lng: number;
  lat: number;
  kind?: string;
  /**
   * True while the browser is still resolving this stop's location.
   *
   * Declared here so the encoder can reject it: a pending stop's coordinates are
   * (0, 0), which is finite, so the coordinate check alone lets it through — and
   * Route sharing reads the store's stops directly, without the
   * `allStopsFilled` gate the address-bar writer has. A link whose origin is null
   * island is worse than no link.
   */
  pending?: boolean;
}

export interface DecodedRouteState {
  stops: ShareableStop[];
  /** Null when the link carried no radius, so the caller can default it. */
  corridorMiles: number | null;
}

/**
 * The app's own URL, without the current query.
 *
 * Built from `pathname` rather than `href` so a share link never inherits the
 * parameters of the link that opened this tab — copying a shared route while
 * looking at `?poi=…` must not produce a link that opens both.
 */
function appUrl(): URL {
  return new URL(window.location.pathname || '/', window.location.origin);
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function base64UrlToBytes(value: string): Uint8Array {
  const padded = value
    .replace(/-/g, '+')
    .replace(/_/g, '/')
    .padEnd(Math.ceil(value.length / 4) * 4, '=');
  return Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
}

const roundCoord = (value: unknown): number =>
  Math.round(Number(value) * COORD_PRECISION) / COORD_PRECISION;

/**
 * A stop trimmed to what a link should carry.
 *
 * Returns null for a stop without finite coordinates — which is what an empty
 * slot and the "Locating you…" placeholder both look like, and neither belongs
 * in a shared trip.
 */
function normalizeStop(stop: Partial<ShareableStop> | null | undefined): ShareableStop | null {
  if (stop?.pending) return null;
  const lng = roundCoord(stop?.lng);
  const lat = roundCoord(stop?.lat);
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null;
  return {
    name: String(stop?.name || 'Stop').slice(0, MAX_STOP_NAME_CHARS),
    lng,
    lat,
    kind: String(stop?.kind || 'PLACE').slice(0, MAX_STOP_KIND_CHARS),
  };
}

export function poiShareUrl(id: string | number | null | undefined): string {
  if (id == null || id === '') return '';
  const url = appUrl();
  url.searchParams.set('poi', String(id));
  return url.toString();
}

/** The `route` parameter's value: base64url of a versioned JSON payload. */
export function encodeRouteState(
  stops: readonly (Partial<ShareableStop> | null | undefined)[] | null | undefined,
  corridorMiles?: number | null,
): string {
  const normalized = (stops ?? []).map(normalizeStop).filter((s): s is ShareableStop => s != null);
  if (normalized.length < MIN_SHAREABLE_STOPS) return '';
  const payload = {
    v: ROUTE_SCHEMA_VERSION,
    // `undefined` is dropped by JSON.stringify, which is the point: a link with
    // no radius decodes to `corridorMiles: null` and the reader defaults it.
    radius_miles: Number(corridorMiles) || undefined,
    stops: normalized,
  };
  return bytesToBase64Url(new TextEncoder().encode(JSON.stringify(payload)));
}

export function routeShareUrl(
  stops: readonly (Partial<ShareableStop> | null | undefined)[] | null | undefined,
  corridorMiles?: number | null,
): string {
  const encoded = encodeRouteState(stops, corridorMiles);
  if (!encoded) return '';
  const url = appUrl();
  url.searchParams.set('route', encoded);
  return url.toString();
}

export function decodeRouteState(value: string | null | undefined): DecodedRouteState | null {
  if (!value || typeof value !== 'string') return null;
  try {
    const payload = JSON.parse(new TextDecoder().decode(base64UrlToBytes(value))) as {
      v?: unknown;
      stops?: unknown;
      radius_miles?: unknown;
    };
    if (payload?.v !== ROUTE_SCHEMA_VERSION || !Array.isArray(payload.stops)) return null;
    const stops = (payload.stops as (Partial<ShareableStop> | null)[])
      .map(normalizeStop)
      .filter((s): s is ShareableStop => s != null);
    if (stops.length < MIN_SHAREABLE_STOPS) return null;
    const radius = Number(payload.radius_miles);
    return { stops, corridorMiles: Number.isFinite(radius) ? radius : null };
  } catch {
    // A malformed link is a link, not a crash: the map still opens.
    return null;
  }
}

/**
 * Rewrite the address bar in place.
 *
 * `replaceState`, not `pushState`: the trip is one page, and pushing would make
 * the browser Back button walk backwards through every stop the user typed.
 * Same-origin only, and a no-op when the path already matches — a redundant
 * replace still fires `popstate` handlers in some browsers.
 */
export function replaceVisibleUrl(url: string | null | undefined): void {
  if (!url) return;
  try {
    const next = new URL(url, window.location.href);
    if (next.origin !== window.location.origin) return;
    const nextPath = `${next.pathname}${next.search}${next.hash}`;
    const currentPath = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    if (nextPath !== currentPath) {
      window.history.replaceState(window.history.state, '', nextPath);
    }
  } catch {
    // Fail closed: the copy button still has a working URL to offer.
  }
}

/** Drop both share parameters — the trip was cleared. */
export function clearVisibleShareUrl(): void {
  const url = new URL(window.location.href);
  url.searchParams.delete('poi');
  url.searchParams.delete('route');
  replaceVisibleUrl(url.toString());
}

/**
 * Drop only `?poi=` — the drawer closed.
 *
 * Deliberately not `clearVisibleShareUrl`: an active `?route=` describes the
 * trip still on screen, and closing a drawer must not make the trip unshareable.
 */
export function clearVisiblePoiUrl(): void {
  const url = new URL(window.location.href);
  if (!url.searchParams.has('poi')) return;
  url.searchParams.delete('poi');
  replaceVisibleUrl(url.toString());
}

/**
 * Write the trip into the visible URL, preserving every other parameter.
 *
 * Not `replaceVisibleUrl(routeShareUrl(...))`, which is what the vanilla's
 * `updateRouteAddressUrl` amounted to: `routeShareUrl` builds from `pathname`, so
 * writing it drops `?poi=`. In the vanilla the two writers never collided often
 * enough to notice; here the drawer writes `?poi=` on every pin click, so editing a
 * trip with a drawer open would silently un-share the POI.
 *
 * A trip that cannot be shared removes the parameter rather than writing an empty
 * one — that is what clearing the trip looks like from here.
 */
export function setVisibleRouteParam(
  stops: readonly (Partial<ShareableStop> | null | undefined)[] | null | undefined,
  corridorMiles?: number | null,
): void {
  const url = new URL(window.location.href);
  const encoded = encodeRouteState(stops, corridorMiles);
  if (encoded) url.searchParams.set('route', encoded);
  else url.searchParams.delete('route');
  replaceVisibleUrl(url.toString());
}

/**
 * Copy a URL, with the textarea fallback the async clipboard needs.
 *
 * `navigator.clipboard` is unavailable in a non-secure context and rejects when
 * the document is not focused — both of which happen in headless browsers, which
 * is where the smoke suite reads this. The fallback is what keeps the button
 * honest there. Returns whether the text reached the clipboard, so the caller can
 * decide what to show.
 */
export async function copyShareUrl(url: string | null | undefined): Promise<boolean> {
  if (!url) return false;
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(url);
      return true;
    }
  } catch {
    // Fall through: a blocked permission is not a reason to fail the copy.
  }
  return fallbackCopy(url);
}

function fallbackCopy(text: string): boolean {
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.cssText = 'position:fixed;top:-1000px;left:-1000px;opacity:0;';
  document.body.appendChild(area);
  area.select();
  let ok = false;
  try {
    ok = document.execCommand('copy');
  } catch {
    ok = false;
  }
  area.remove();
  return ok;
}
