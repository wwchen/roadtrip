import { describe, expect, test } from 'vitest';
import { readEventStream, type ServerEvent } from './event-stream';

const encoder = new TextEncoder();

/** A body that hands over [chunks] one read at a time, as a network would. */
function bodyOf(...chunks: Array<string | Uint8Array>): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk);
      controller.close();
    },
  });
}

async function eventsOf(body: ReadableStream<Uint8Array>): Promise<ServerEvent[]> {
  const events: ServerEvent[] = [];
  for await (const event of readEventStream(body)) events.push(event);
  return events;
}

describe('readEventStream', () => {
  test('yields each event its blank line dispatches, named or defaulting to message', async () => {
    const events = await eventsOf(bodyOf('event: answer\ndata: {"poi_id":1}\n\ndata: plain\n\n'));

    expect(events).toEqual([
      { event: 'answer', data: '{"poi_id":1}' },
      { event: 'message', data: 'plain' },
    ]);
  });

  test('reads lines ended CRLF, CR or LF alike — a CRLF split across chunks included', async () => {
    const events = await eventsOf(bodyOf('event: a\r\ndata: 1\r', '\n\r\nevent: b\rdata: 2\r\rdata: 3\n\n'));

    expect(events).toEqual([
      { event: 'a', data: '1' },
      { event: 'b', data: '2' },
      { event: 'message', data: '3' },
    ]);
  });

  test('a field split across chunks reads whole, and so does a character split between its bytes', async () => {
    const bytes = encoder.encode('data: Café — ok\n\n');
    const accent = 'data: Caf'.length; // the first byte of the two-byte é

    const events = await eventsOf(bodyOf('ev', 'ent: an', 'swer\n', bytes.slice(0, accent + 1), bytes.slice(accent + 1)));

    expect(events).toEqual([{ event: 'answer', data: 'Café — ok' }]);
  });

  test('joins an event\'s data lines with newlines', async () => {
    expect(await eventsOf(bodyOf('data: one\ndata: two\ndata:\n\n'))).toEqual([{ event: 'message', data: 'one\ntwo\n' }]);
  });

  test('skips comments, the keep-alives a quiet server sends, and fields it does not know', async () => {
    const events = await eventsOf(bodyOf(': keep-alive\n\n:\n\nretry: 3000\nevent: done\nnoise\ndata: {}\n\n'));

    expect(events).toEqual([{ event: 'done', data: '{}' }]);
  });

  test('drops one leading space from a value, and only one', async () => {
    expect(await eventsOf(bodyOf('data:tight\n\ndata:  roomy\n\n'))).toEqual([
      { event: 'message', data: 'tight' },
      { event: 'message', data: ' roomy' },
    ]);
  });

  test('a blank line with no data dispatches nothing, and the name it set does not carry over', async () => {
    expect(await eventsOf(bodyOf('event: lonely\n\ndata: next\n\n'))).toEqual([{ event: 'message', data: 'next' }]);
  });

  test('carries the last id an event set', async () => {
    expect(await eventsOf(bodyOf('id: 7\ndata: a\n\ndata: b\n\n'))).toEqual([
      { event: 'message', data: 'a', id: '7' },
      { event: 'message', data: 'b', id: '7' },
    ]);
  });

  test('strips a leading byte order mark', async () => {
    expect(await eventsOf(bodyOf('\uFEFFdata: x\n\n'))).toEqual([{ event: 'message', data: 'x' }]);
  });

  test('discards an event the stream ends in the middle of', async () => {
    expect(await eventsOf(bodyOf('data: whole\n\nevent: answer\ndata: {"poi_id"'))).toEqual([
      { event: 'message', data: 'whole' },
    ]);
  });

  test('leaving the loop early cancels the body', async () => {
    let cancelled = false;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('data: first\n\n'));
      },
      cancel() {
        cancelled = true;
      },
    });

    for await (const event of readEventStream(body)) {
      expect(event.data).toBe('first');
      break;
    }

    expect(cancelled).toBe(true);
    expect(body.locked).toBe(false);
  });

  test('a body that errors ends the iteration with its error', async () => {
    const cut = new DOMException('The operation was aborted.', 'AbortError');
    // Errors on the read after the first event: erroring a stream drops whatever it still had queued.
    let reads = 0;
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        reads += 1;
        if (reads === 1) controller.enqueue(encoder.encode('data: first\n\n'));
        else controller.error(cut);
      },
    });
    const seen: string[] = [];

    await expect(
      (async () => {
        for await (const event of readEventStream(body)) seen.push(event.data);
      })(),
    ).rejects.toBe(cut);
    expect(seen).toEqual(['first']);
  });
});
