// The alert-email magic link.
//
// An alert email carries `?action=modify&id=<watch>&watch_token=<token>`. The
// token is a capability scoped to that one watch: the API accepts it on the
// single-watch routes in place of a session, so the reader can change or stop
// the alert straight from their mailbox. It authorizes nothing else — not the
// list, not another watch, not the account.
//
// Kept in memory for the page's lifetime and stripped from the address bar as
// soon as it is read. A bearer token in a URL leaks through `Referer`, through
// shared links, and through anything that reads history; none of that is
// reversible, so the window in which it sits there should be one turn of the
// event loop, not the whole session.

/** Matches WATCH_TOKEN_PARAM on the backend — email, web app, and route must agree. */
export const WATCH_TOKEN_PARAM = 'watch_token';

const ID_PARAM = 'id';

export interface WatchLink {
  token: string;
  /** The watch the token authorizes, as it appeared in the link. */
  watchId: string;
}

let current: WatchLink | null = null;

/**
 * Parse a magic link out of a query string and hold on to it.
 *
 * Takes the search string rather than reading `window.location` so the module
 * has no hidden dependency on the page it happens to be mounted in — the pages
 * that never receive a magic link simply never call this, and a test can hand it
 * any link it wants.
 *
 * A token with no `id` is not a usable link: the token names a watch, but the
 * page still needs the id to ask for it, and inventing one would just produce a
 * 404 with a confusing message.
 */
export function initWatchLink(search: string): WatchLink | null {
  const params = new URLSearchParams(search);
  const token = params.get(WATCH_TOKEN_PARAM);
  const watchId = params.get(ID_PARAM);
  current = token && watchId ? { token, watchId } : null;
  return current;
}

/** The magic link this page was opened with, or null. */
export function watchLink(): WatchLink | null {
  return current;
}

/** The ambient token the watch API attaches to single-watch requests. */
export function watchLinkToken(): string | null {
  return current?.token ?? null;
}

/**
 * Drop the token from the address bar, keeping every other parameter.
 *
 * Deliberately narrower than the watches page's own "strip the whole query"
 * cleanup: that one runs when a deep-link action is consumed, which for a
 * signed-out reader never happens. The token must not wait for it.
 */
export function stripWatchTokenFromUrl(): void {
  const url = new URL(window.location.href);
  if (!url.searchParams.has(WATCH_TOKEN_PARAM)) return;
  url.searchParams.delete(WATCH_TOKEN_PARAM);
  const search = url.searchParams.toString();
  window.history.replaceState(null, '', `${url.pathname}${search ? `?${search}` : ''}${url.hash}`);
}

/** Test seam: forget any link this module is holding. */
export function resetWatchLink(): void {
  current = null;
}
