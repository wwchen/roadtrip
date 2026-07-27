// Shared fetch helpers.
//
// `credentials` is stated explicitly even though 'same-origin' is already the
// Fetch default — the session cookie reaching the API is load-bearing once
// routes require a principal, and an implicit default is easy to break by
// accident and hard to notice. Direct fetch() calls elsewhere in web/ rely on
// the same default and work unchanged.
//
// Deliberately not 'include': the API is same-origin, and 'include' would also
// attach cookies to cross-origin requests, widening what a mistaken absolute
// URL would leak.
const CREDENTIALS = 'same-origin';

export class HttpError extends Error {
  constructor(url, status) {
    super(`${url}: HTTP ${status}`);
    this.name = 'HttpError';
    this.url = url;
    this.status = status;
    // `code` is set by the *Ok helpers when the response body carries
    // `{ error: "<code>" }`. Guard with try/catch so a non-JSON body never
    // masks the original HttpError. Callers that ignore `.code` are unaffected.
    this.code = undefined;
  }
}

export function jsonPost(url, body, { signal } = {}) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    credentials: CREDENTIALS,
    signal,
  });
}

async function attachErrorCode(err, response) {
  try {
    const body = await response.json();
    if (body && typeof body.error === 'string') err.code = body.error;
  } catch {
    // Non-JSON or empty body — leave err.code undefined.
  }
  return err;
}

export async function jsonPostOk(url, body, options = {}) {
  const response = await jsonPost(url, body, options);
  if (!response.ok) throw await attachErrorCode(new HttpError(url, response.status), response);
  return response.json();
}

export async function jsonPutOk(url, body, { signal } = {}) {
  const response = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    credentials: CREDENTIALS,
    signal,
  });
  if (!response.ok) throw await attachErrorCode(new HttpError(url, response.status), response);
  return response.json();
}

export async function jsonGetOk(url, { signal } = {}) {
  const response = await fetch(url, { credentials: CREDENTIALS, signal });
  if (!response.ok) throw await attachErrorCode(new HttpError(url, response.status), response);
  return response.json();
}

export async function jsonDeleteOk(url, { signal } = {}) {
  const response = await fetch(url, { method: 'DELETE', credentials: CREDENTIALS, signal });
  if (!response.ok) throw await attachErrorCode(new HttpError(url, response.status), response);
  return response.status === 204 ? null : response.json().catch(() => null);
}
