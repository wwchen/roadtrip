// The drawer's deep link: `?poi=<id>`.
//
// `replaceState`, never `pushState`: opening a drawer is not a navigation, and a
// history entry per pin click would turn Back into "close the last twelve
// drawers". The smoke suite loads `/?poi=…` directly, so this parameter is a
// contract, not a convenience.
const POI_PARAM = 'poi';

/**
 * Point the visible URL at a POI, leaving every other parameter alone.
 *
 * An active `?route=` in particular has to survive — the drawer is opened from
 * inside a trip as often as from browsing.
 */
export function showPoiInUrl(id: string | number | null | undefined): void {
  if (id == null || id === '') return;
  const url = new URL(window.location.href);
  url.searchParams.set(POI_PARAM, String(id));
  replaceVisibleUrl(url);
}

/** Drop only `?poi=`, preserving the rest of the query. */
export function clearPoiFromUrl(): void {
  const url = new URL(window.location.href);
  if (!url.searchParams.has(POI_PARAM)) return;
  url.searchParams.delete(POI_PARAM);
  replaceVisibleUrl(url);
}

/** The POI a page was opened on, for restoring a deep link. */
export function poiFromUrl(search: string = window.location.search): string | null {
  return new URLSearchParams(search).get(POI_PARAM);
}

/**
 * Swap the visible path+query, if it actually differs.
 *
 * The no-op check is not an optimisation: `replaceState` with an identical URL
 * still churns history state, and this runs on every drawer open.
 */
function replaceVisibleUrl(next: URL): void {
  if (next.origin !== window.location.origin) return;
  const nextPath = `${next.pathname}${next.search}${next.hash}`;
  const currentPath = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  if (nextPath === currentPath) return;
  window.history.replaceState(window.history.state, '', nextPath);
}
