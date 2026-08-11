// The session lives in an HttpOnly cookie, so the page cannot read who it is —
// it asks /api/me instead. That is the point of HttpOnly: a missed escape
// somewhere must not be able to exfiltrate a session.
//
// Sign-in and sign-out are full-page navigations, not fetches. They end in a
// cross-site redirect to the identity provider, which XHR cannot follow.
import { jsonGetOk, type RequestOptions } from './http';

const ME_URL = '/api/me';
const LOGIN_URL = '/auth/login';
const LOGOUT_URL = '/auth/logout';

const RETURN_TO_PARAM = 'return_to';
const CONNECTION_PARAM = 'connection';

/** `GET /api/me` → `user`. Mirrors MeUserDto. */
export interface MeUser {
  id: number;
  email: string;
  display_name?: string | null;
  email_verified: boolean;
  roles: string[];
}

/**
 * `GET /api/me`. Mirrors MeResponseDto.
 *
 * Resolves for anonymous visitors too — `authenticated: false` is a normal
 * answer, not an error, and the backend deliberately answers 200 for everyone so
 * 401 keeps meaning a genuine authorization failure.
 */
export interface Me {
  authenticated: boolean;
  user?: MeUser | null;
  /** False when no identity provider is configured: hide sign-in entirely. */
  auth_enabled: boolean;
  /** Public (non-secret) config for the embedded resource-owner login flow. */
  auth_client_id?: string | null;
  auth_domain?: string | null;
  auth_realm?: string | null;
  /** Provider name for login copy ("Continue with Clerk"). */
  provider_label?: string | null;
  /** True → mount the embedded email/password card; false → redirect to /auth/login. */
  auth_embedded?: boolean;
}

export function fetchMe({ signal }: RequestOptions = {}): Promise<Me> {
  return jsonGetOk<Me>(ME_URL, { signal });
}

/**
 * Starts sign-in, returning here afterwards.
 *
 * `returnTo` is sent as a path and re-validated server-side; anything that is
 * not a same-origin path is discarded there, so a tampered link cannot turn this
 * into an open redirect.
 */
export function signIn(returnTo: string = currentPath()): void {
  window.location.assign(`${LOGIN_URL}?${RETURN_TO_PARAM}=${encodeURIComponent(returnTo)}`);
}

/**
 * Starts a social sign-in that redirects to the provider's consent screen. OAuth
 * cannot embed this step, so it is a full-page navigation like `signIn`.
 */
export function signInWithConnection(connection: string, returnTo: string = currentPath()): void {
  const url =
    `${LOGIN_URL}?${RETURN_TO_PARAM}=${encodeURIComponent(returnTo)}` +
    `&${CONNECTION_PARAM}=${encodeURIComponent(connection)}`;
  window.location.assign(url);
}

export function signOut(): void {
  window.location.assign(LOGOUT_URL);
}

function currentPath(): string {
  const { pathname, search, hash } = window.location;
  return `${pathname}${search}${hash}` || '/';
}
