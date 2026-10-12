// Reading a `text/event-stream` body: the server-sent-events wire format, parsed
// the way a browser's EventSource parses it (WHATWG HTML, "Parsing an event
// stream"), but over any byte stream — so a POST answered with a stream, which
// EventSource cannot make, reads the same.
//
// This module knows the format and nothing else: which events a stream carries,
// and what their data means, are the caller's.

/** One dispatched event: its type, its data, and the id it carried, if any. */
export interface ServerEvent {
  /** The `event:` field, or `message` when the event named none. */
  event: string;
  /** Every `data:` line of the event, joined by `\n`. */
  data: string;
  id?: string;
}

const DEFAULT_EVENT = 'message';
const BYTE_ORDER_MARK = '﻿';
const LINE_BREAK = /\r\n|\r|\n/;
const COMMENT_PREFIX = ':';
const FIELD_SEPARATOR = ':';
const NUL = '\u0000';

/**
 * The events of [body], each yielded as it is complete.
 *
 * Bytes are decoded as UTF-8 across chunk boundaries, so a character split
 * between two chunks still reads whole. Lines may end `\r\n`, `\r` or `\n`; a
 * blank line dispatches the event built so far, `:` lines are comments (servers
 * send them as keep-alives), and a field's value drops one leading space. An
 * event the stream ends in the middle of is discarded, as the spec says — so a
 * stream cut off mid-event never yields a half-read one.
 *
 * Cancelling the request that produced [body] (its fetch's signal) ends the
 * iteration with that request's abort error. Leaving the loop before the stream
 * ends — a `break`, a `return`, a throw — cancels [body], so the connection
 * behind it is let go rather than left open unread.
 */
export async function* readEventStream(body: ReadableStream<Uint8Array>): AsyncGenerator<ServerEvent> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let pending = '';
  let started = false;
  let event = '';
  let data: string[] = [];
  let id: string | undefined;
  let ended = false;

  /** One complete line: a field, a comment, or the blank line that dispatches. */
  const takeLine = (line: string): ServerEvent | null => {
    if (line === '') {
      const dispatched = data.length > 0 ? { event: event || DEFAULT_EVENT, data: data.join('\n'), ...(id !== undefined ? { id } : {}) } : null;
      event = '';
      data = [];
      return dispatched;
    }
    if (line.startsWith(COMMENT_PREFIX)) return null;
    const separator = line.indexOf(FIELD_SEPARATOR);
    const field = separator === -1 ? line : line.slice(0, separator);
    const raw = separator === -1 ? '' : line.slice(separator + 1);
    const value = raw.startsWith(' ') ? raw.slice(1) : raw;
    if (field === 'event') event = value;
    else if (field === 'data') data.push(value);
    else if (field === 'id' && !value.includes(NUL)) id = value;
    return null;
  };

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        ended = true;
        return;
      }
      pending += decoder.decode(value, { stream: true });
      if (!started && pending.length > 0) {
        if (pending.startsWith(BYTE_ORDER_MARK)) pending = pending.slice(BYTE_ORDER_MARK.length);
        started = true;
      }
      for (;;) {
        const match = LINE_BREAK.exec(pending);
        // A trailing `\r` may be the first half of a `\r\n` the next chunk ends.
        if (!match || (match[0] === '\r' && match.index === pending.length - 1)) break;
        const line = pending.slice(0, match.index);
        pending = pending.slice(match.index + match[0].length);
        const dispatched = takeLine(line);
        if (dispatched) yield dispatched;
      }
    }
  } finally {
    // Cancelling an errored stream rejects with its error, which the caller already has.
    if (!ended) await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}
