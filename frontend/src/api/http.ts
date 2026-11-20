// `credentials` is stated explicitly even though 'same-origin' is already the
// Fetch default — the session cookie reaching the API is load-bearing once
// routes require a principal, and an implicit default is easy to break by
// accident and hard to notice.
//
// Deliberately not 'include': the API is same-origin, and 'include' would also
// attach cookies to cross-origin requests, widening what a mistaken absolute
// URL would leak.
export const CREDENTIALS: RequestCredentials = 'same-origin';

/** What a streaming route answers: its events, or the JSON refusal it sends before any. */
const EVENT_STREAM_ACCEPT = 'text/event-stream, application/json';

const HTTP_NO_CONTENT = 204;

export interface RequestOptions {
  signal?: AbortSignal;
}

export class HttpError extends Error {
  readonly url: string;
  readonly status: number;
  /** Set by the *Ok helpers when the response body carries `{ error: "<code>" }`. */
  code?: string;
  /** The booking adapter that refused, when the envelope named one. */
  provider?: string;
  /** That adapter's booking site as a person reads it. */
  providerDisplay?: string;
  cartUrl?: string;
  detail?: string;
  /** Raw response text, attached by some clients (e.g. watches create/update). */
  body?: string;

  constructor(url: string, status: number) {
    super(`${url}: HTTP ${status}`);
    this.name = 'HttpError';
    this.url = url;
    this.status = status;
  }
}

export function jsonPost(url: string, body: unknown, options: RequestOptions = {}): Promise<Response> {
  return postJson(url, body, options);
}

/** The one JSON POST every helper here sends; [accept] names what the caller reads back, when it is not JSON. */
function postJson(url: string, body: unknown, { signal }: RequestOptions, accept?: string): Promise<Response> {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(accept ? { Accept: accept } : {}) },
    body: JSON.stringify(body),
    credentials: CREDENTIALS,
    signal,
  });
}

export function httpErrorFrom(url: string, response: Response): Promise<HttpError> {
  return attachErrorCode(new HttpError(url, response.status), response);
}

async function attachErrorCode(err: HttpError, response: Response): Promise<HttpError> {
  const text = await response.text().catch(() => '');
  let body: Record<string, unknown> | null = null;
  try {
    const parsed: unknown = text === '' ? null : JSON.parse(text);
    body = parsed !== null && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
  } catch {
    body = null;
  }
  if (text !== '') err.body = text;
  if (body === null) {
    if (text !== '') err.detail = text;
    return err;
  }
  if (typeof body.error === 'string') err.code = body.error;
  if (typeof body.detail === 'string') err.detail = body.detail;
  if (typeof body.provider === 'string') err.provider = body.provider;
  if (typeof body.provider_display === 'string') err.providerDisplay = body.provider_display;
  if (typeof body.cart_url === 'string') err.cartUrl = body.cart_url;
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

/**
 * POST [body] as JSON and hand back the answer's body as a stream, for a route
 * that answers `text/event-stream`. A refusal — any non-2xx, which such a route
 * sends as JSON before it starts streaming — throws an `HttpError` carrying the
 * envelope's `code`, as `jsonPostOk` does.
 */
export async function jsonPostStream(
  url: string,
  body: unknown,
  options: RequestOptions = {},
): Promise<ReadableStream<Uint8Array>> {
  const response = await postJson(url, body, options, EVENT_STREAM_ACCEPT);
  if (!response.ok) throw await attachErrorCode(new HttpError(url, response.status), response);
  // Only a bodiless status (204, 205, 304) answers without one — never a stream.
  if (!response.body) throw new Error(`${url}: HTTP ${response.status} carried no body to stream`);
  return response.body;
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
  if (response.status === HTTP_NO_CONTENT) return null;
  return response.json().catch(() => null) as Promise<T | null>;
}

export async function jsonDeleteBodyOk<T>(url: string, { signal }: RequestOptions = {}): Promise<T> {
  const response = await fetch(url, { method: 'DELETE', credentials: CREDENTIALS, signal });
  if (!response.ok) throw await attachErrorCode(new HttpError(url, response.status), response);
  if (response.status === HTTP_NO_CONTENT) throw new Error(`${url}: HTTP ${response.status} carried no body`);
  return response.json() as Promise<T>;
}
