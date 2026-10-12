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

/** One server-sent event as a stub writes it: a name, and data sent as JSON. */
export interface StubEvent {
  event: string;
  data: unknown;
}

const EVENT_STREAM_HEADERS = { 'Content-Type': 'text/event-stream' };

function frameOf({ event, data }: StubEvent): string {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

/** A `text/event-stream` 200 carrying [events] in order, then closing — a streaming route that ran to its end. */
export function eventStreamResponse(events: readonly StubEvent[]): Response {
  return new Response(events.map(frameOf).join(''), { status: 200, headers: EVENT_STREAM_HEADERS });
}

export interface OpenEventStream {
  response: Response;
  /** Writes [event] to the stream; a no-op once it has closed or been aborted. */
  send: (event: StubEvent) => void;
  /** Ends the stream where it is — after `done` or, to play a cut connection, before it. */
  close: () => void;
}

/**
 * A `text/event-stream` 200 the test writes as it goes, for a check that is
 * still running. Aborting [signal] errors the body with an `AbortError`, as a
 * real fetch's does, so a reader waiting on it is let go.
 */
export function openEventStream(signal?: AbortSignal | null): OpenEventStream {
  const encoder = new TextEncoder();
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  let open = true;
  const body = new ReadableStream<Uint8Array>({ start: (c) => void (controller = c) });
  signal?.addEventListener('abort', () => {
    if (!open) return;
    open = false;
    controller.error(new DOMException('The operation was aborted.', 'AbortError'));
  });
  return {
    response: new Response(body, { status: 200, headers: EVENT_STREAM_HEADERS }),
    send: (event) => {
      if (open) controller.enqueue(encoder.encode(frameOf(event)));
    },
    close: () => {
      if (!open) return;
      open = false;
      controller.close();
    },
  };
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
