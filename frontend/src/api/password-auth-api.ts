// Client for the embedded password endpoints. Typed port of
// web/api/password-auth-api.js.
//
// The session lands as an HttpOnly cookie set by /auth/password/complete;
// nothing sensitive is returned to script.
//
// This module deliberately does NOT go through `http.ts`. It carries its own
// request helper because it takes an injectable `_fetch` — the embedded auth
// flow is driven from a form whose tests supply a fake — and because its error
// is a plain Error with `.code`, not an HttpError. Both are preserved so the
// Phase-3 auth port is a like-for-like swap.
const BEGIN_URL = '/auth/password/begin';
const COMPLETE_URL = '/auth/password/complete';
const CREDENTIALS: RequestCredentials = 'same-origin';
const NO_CONTENT = 204;

/**
 * Thrown on a non-2xx response.
 *
 * A named subclass of Error rather than a new error type: the message,
 * `instanceof Error`, and `.code` all match what web/api/password-auth-api.js
 * threw, so existing handling is unchanged — it just gives `.code` a type.
 */
export class PasswordAuthError extends Error {
  /** The `error` field of the response body, when it carried one. */
  readonly code: string | undefined;

  constructor(url: string, status: number, code: string | undefined) {
    super(`${url}: HTTP ${status}`);
    this.code = code;
  }
}

/** Mirrors PasswordBeginResponseDto. Public flow material — no secrets. */
export interface PasswordBeginResponse {
  state: string;
  nonce: string;
  code_challenge: string;
  redirect_uri: string;
}

export interface PasswordAuthOptions {
  /** Injection seam for tests; defaults to the global fetch. */
  _fetch?: typeof fetch;
}

async function postJson<T>(url: string, body: unknown, _fetch: typeof fetch): Promise<T | null> {
  const response = await _fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    credentials: CREDENTIALS,
  });
  if (!response.ok) {
    let code: string | undefined;
    try {
      code = ((await response.json()) as { error?: string } | null)?.error;
    } catch {
      /* non-JSON body */
    }
    throw new PasswordAuthError(url, response.status, code);
  }
  return response.status === NO_CONTENT ? null : ((await response.json()) as T);
}

export function beginPasswordLogin(
  returnTo: string,
  { _fetch = fetch }: PasswordAuthOptions = {},
): Promise<PasswordBeginResponse | null> {
  return postJson<PasswordBeginResponse>(BEGIN_URL, { return_to: returnTo }, _fetch);
}

/** Resolves to null on the expected 204: the session is now in the cookie. */
export function completePasswordLogin(
  code: string,
  state: string,
  returnTo: string,
  { _fetch = fetch }: PasswordAuthOptions = {},
): Promise<null> {
  return postJson<never>(
    COMPLETE_URL,
    { code, state, return_to: returnTo },
    _fetch,
  ) as Promise<null>;
}
