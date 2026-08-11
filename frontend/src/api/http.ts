// `credentials` is stated explicitly even though 'same-origin' is already the
// Fetch default — the session cookie reaching the API is load-bearing once
// routes require a principal, and an implicit default is easy to break by
// accident and hard to notice.
//
// Deliberately not 'include': the API is same-origin, and 'include' would also
// attach cookies to cross-origin requests, widening what a mistaken absolute
// URL would leak.
const CREDENTIALS: RequestCredentials = 'same-origin';

export interface RequestOptions {
  signal?: AbortSignal;
}

export class HttpError extends Error {
  readonly url: string;
  readonly status: number;
  /** Set by the *Ok helpers when the response body carries `{ error: "<code>" }`. */
  code?: string;
  /** Raw response text, attached by some clients (e.g. watches create/update). */
  body?: string;

  constructor(url: string, status: number) {
    super(`${url}: HTTP ${status}`);
    this.name = 'HttpError';
    this.url = url;
    this.status = status;
  }
}

export function jsonPost(
  url: string,
  body: unknown,
  { signal }: RequestOptions = {},
): Promise<Response> {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    credentials: CREDENTIALS,
    signal,
  });
}

async function attachErrorCode(err: HttpError, response: Response): Promise<HttpError> {
  try {
    const body = await response.json();
    if (body && typeof body.error === 'string') err.code = body.error;
  } catch {
    // Non-JSON or empty body — leave err.code undefined.
  }
  return err;
}

export async function jsonPostOk<T = unknown>(
  url: string,
  body: unknown,
  options: RequestOptions = {},
): Promise<T> {
  const response = await jsonPost(url, body, options);
  if (!response.ok) throw await attachErrorCode(new HttpError(url, response.status), response);
  return response.json() as Promise<T>;
}

export async function jsonPutOk<T = unknown>(
  url: string,
  body: unknown,
  { signal }: RequestOptions = {},
): Promise<T> {
  const response = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    credentials: CREDENTIALS,
    signal,
  });
  if (!response.ok) throw await attachErrorCode(new HttpError(url, response.status), response);
  return response.json() as Promise<T>;
}

export async function jsonGetOk<T = unknown>(
  url: string,
  { signal }: RequestOptions = {},
): Promise<T> {
  const response = await fetch(url, { credentials: CREDENTIALS, signal });
  if (!response.ok) throw await attachErrorCode(new HttpError(url, response.status), response);
  return response.json() as Promise<T>;
}

export async function jsonDeleteOk<T = unknown>(
  url: string,
  { signal }: RequestOptions = {},
): Promise<T | null> {
  const response = await fetch(url, { method: 'DELETE', credentials: CREDENTIALS, signal });
  if (!response.ok) throw await attachErrorCode(new HttpError(url, response.status), response);
  if (response.status === 204) return null;
  return response.json().catch(() => null) as Promise<T | null>;
}
