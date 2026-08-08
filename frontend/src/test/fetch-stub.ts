// Shared fetch stub for API-client tests.
//
// Every api/* module is a thin same-origin fetch wrapper, so its tests all need
// the same three things: install a fake fetch, assert the URL and request body it
// built, and control the status it sees. This is that, in one place, so the
// suites assert behavior instead of each re-deriving a mock.
import { vi } from 'vitest';

export interface RecordedRequest {
  url: string;
  init: RequestInit;
  /** `'GET'` when the caller left the method implicit. */
  method: string;
  /** The parsed JSON request body, or `undefined` when there was none. */
  body: unknown;
}

export interface FetchStub {
  requests: RecordedRequest[];
  /** The most recent request. Throws if nothing has been requested yet. */
  readonly last: RecordedRequest;
}

/** A JSON Response, the shape almost every route returns. */
export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** A 204, which the DELETE and password-complete paths return. */
export function noContentResponse(): Response {
  return new Response(null, { status: 204 });
}

/** A non-JSON error body, so `.json()` rejects and `.code` stays undefined. */
export function textResponse(text: string, status: number): Response {
  return new Response(text, { status });
}

/**
 * Install a fetch stub answering with `responses` in order; the last one repeats
 * once exhausted, so a test that only cares about the request can pass one.
 *
 * Undone by `vi.unstubAllGlobals()` — call it in `afterEach`.
 */
export function stubFetch(...responses: Response[]): FetchStub {
  const queue = responses.length > 0 ? responses : [jsonResponse({})];
  const requests: RecordedRequest[] = [];

  const mock = vi.fn(async (input: unknown, init?: RequestInit): Promise<Response> => {
    const raw = init?.body;
    requests.push({
      url: String(input),
      init: init ?? {},
      method: init?.method ?? 'GET',
      body: typeof raw === 'string' ? (JSON.parse(raw) as unknown) : undefined,
    });
    // Responses are single-use once their body is read, so hand out a clone.
    return (queue[Math.min(requests.length - 1, queue.length - 1)] as Response).clone();
  });
  vi.stubGlobal('fetch', mock);

  return {
    requests,
    get last(): RecordedRequest {
      const last = requests[requests.length - 1];
      if (!last) throw new Error('no fetch call was recorded');
      return last;
    },
  };
}
