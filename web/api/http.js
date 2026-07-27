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

export async function jsonPostOk(url, body, options = {}) {
  const response = await jsonPost(url, body, options);
  if (!response.ok) throw new HttpError(url, response.status);
  return response.json();
}

export async function jsonGetOk(url, { signal } = {}) {
  const response = await fetch(url, { credentials: CREDENTIALS, signal });
  if (!response.ok) throw new HttpError(url, response.status);
  return response.json();
}

export async function jsonDeleteOk(url, { signal } = {}) {
  const response = await fetch(url, { method: 'DELETE', credentials: CREDENTIALS, signal });
  if (!response.ok) throw new HttpError(url, response.status);
  return response.status === 204 ? null : response.json().catch(() => null);
}
