// web/api/auth-api.js
//
// Client for the auth surface. The session lives in an HttpOnly cookie, so the
// page cannot read who it is — it asks /api/me instead. That is the point of
// HttpOnly: a missed escapeHtml somewhere must not be able to exfiltrate a
// session.
//
// Sign-in and sign-out are full-page navigations, not fetches. They end in a
// cross-site redirect to the identity provider, which XHR cannot follow.

import { jsonGetOk } from './http.js';

const ME_URL = '/api/me';
const LOGIN_URL = '/auth/login';
const LOGOUT_URL = '/auth/logout';

const RETURN_TO_PARAM = 'return_to';
const CONNECTION_PARAM = 'connection';

/**
 * Who the caller is.
 *
 * Resolves for anonymous visitors too — `authenticated: false` is a normal
 * answer, not an error. `auth_enabled: false` means no identity provider is
 * configured, and callers should hide sign-in rather than offer a control that
 * cannot work.
 *
 * @param {{signal?: AbortSignal}} [options]
 * @returns {Promise<{authenticated: boolean, auth_enabled: boolean, user?: object}>}
 */
export function fetchMe({ signal } = {}) {
  return jsonGetOk(ME_URL, { signal });
}

/**
 * Starts sign-in, returning here afterwards.
 *
 * `returnTo` is sent as a path and re-validated server-side; anything that is
 * not a same-origin path is discarded there, so a tampered link cannot turn
 * this into an open redirect.
 */
export function signIn(returnTo = currentPath()) {
  window.location.assign(`${LOGIN_URL}?${RETURN_TO_PARAM}=${encodeURIComponent(returnTo)}`);
}

/**
 * Starts a social sign-in that redirects to the provider's consent screen.
 * OAuth cannot embed this step, so it is a full-page navigation like signIn.
 */
export function signInWithConnection(connection, returnTo = currentPath()) {
  const url = `${LOGIN_URL}?${RETURN_TO_PARAM}=${encodeURIComponent(returnTo)}` +
    `&${CONNECTION_PARAM}=${encodeURIComponent(connection)}`;
  window.location.assign(url);
}

export function signOut() {
  window.location.assign(LOGOUT_URL);
}

function currentPath() {
  const { pathname, search, hash } = window.location;
  return `${pathname}${search}${hash}` || '/';
}
