import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope } from './envelope.js';

const TEMP_DIR_PREFIX = 'envelope-';
const WIDE_INTEGER = '72157625256027958';
const NEGATIVE_WIDE_INTEGER = '-9007199254740993';
const BEYOND_DOUBLE_RANGE = '1e400';
const HUGE_INTEGER = '123456789012345678901234567890';
const FETCHER_VERSION = '1';
const FETCHED_AT = '2026-06-07T21:33:18Z';
const POLLER_RUN_ID = 42;
const HTTP_OK = 200;

let dir = '';
let counter = 0;

const BASE_ENVELOPE = `{
  "fetcher": "fetch_fixture",
  "fetcher_version": "${FETCHER_VERSION}",
  "fetched_at": "${FETCHED_AT}",
  "request": {"url": "https://example.test/", "method": "GET", "headers": {"accept": "*/*"}},
  "response": {"status": ${HTTP_OK}, "headers": {}},
  "poller_run_id": null,
  "payload": null
}`;

function fileHolding(text: string): string {
  counter += 1;
  const file = path.join(dir, `envelope-${counter}.json`);
  writeFileSync(file, text);
  return file;
}

function envelopeWith(mutate: (envelope: Record<string, unknown>) => void): string {
  const envelope = JSON.parse(BASE_ENVELOPE) as Record<string, unknown>;
  mutate(envelope);
  return fileHolding(JSON.stringify(envelope));
}

function envelopeWithRawPayload(rawPayload: string): string {
  return fileHolding(BASE_ENVELOPE.replace('"payload": null', `"payload": ${rawPayload}`));
}

beforeAll(() => {
  dir = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

describe('parseEnvelope wire format', () => {
  test('snake_case wire keys map to camelCase fields', () => {
    const envelope = parseEnvelope(
      envelopeWith((e) => {
        e.poller_run_id = POLLER_RUN_ID;
        e.part = 'page-001';
        e.payload = { a: 1 };
      }),
    );
    expect(envelope).toMatchObject({
      fetcher: 'fetch_fixture',
      fetcherVersion: FETCHER_VERSION,
      fetchedAt: FETCHED_AT,
      pollerRunId: POLLER_RUN_ID,
      part: 'page-001',
      payload: { a: 1 },
    });
    expect(envelope.request).toEqual({ url: 'https://example.test/', method: 'GET', headers: { accept: '*/*' } });
    expect(envelope.response).toEqual({ status: HTTP_OK, headers: {} });
  });

  test('a missing poller_run_id is null and a missing part is undefined, as Kotlin defaults them', () => {
    const envelope = parseEnvelope(
      envelopeWith((e) => {
        delete e.poller_run_id;
      }),
    );
    expect(envelope.pollerRunId).toBeNull();
    expect(envelope.part).toBeUndefined();
  });

  test('a null payload is a valid payload and unknown top-level keys are ignored', () => {
    const envelope = parseEnvelope(
      envelopeWith((e) => {
        e.extra = 'ignored';
      }),
    );
    expect(envelope.payload).toBeNull();
    expect(envelope).not.toHaveProperty('extra');
  });

  test.each([
    ['fetcher', (e: Record<string, unknown>) => delete e.fetcher],
    ['fetcher_version', (e: Record<string, unknown>) => delete e.fetcher_version],
    ['fetched_at', (e: Record<string, unknown>) => delete e.fetched_at],
    ['request', (e: Record<string, unknown>) => delete e.request],
    ['response', (e: Record<string, unknown>) => delete e.response],
    ['payload', (e: Record<string, unknown>) => delete e.payload],
    ['request.url', (e: Record<string, unknown>) => delete (e.request as Record<string, unknown>).url],
    ['request.method', (e: Record<string, unknown>) => delete (e.request as Record<string, unknown>).method],
    ['response.status', (e: Record<string, unknown>) => delete (e.response as Record<string, unknown>).status],
  ])('an envelope without %s is rejected, as Kotlin rejects it', (field, mutate) => {
    const file = envelopeWith((e) => {
      mutate(e);
    });
    expect(() => parseEnvelope(file)).toThrow(new RegExp(`${field.replace('.', '\\.')}`));
  });

  test('a numeric fetcher_version is rejected rather than coerced to a string', () => {
    const file = envelopeWith((e) => {
      e.fetcher_version = 2;
    });
    expect(() => parseEnvelope(file)).toThrow(/fetcher_version/);
  });

  test('a file that is not JSON is rejected and names the file', () => {
    const file = fileHolding('{not json');
    expect(() => parseEnvelope(file)).toThrow(file);
  });
});

describe('parseEnvelope number fidelity', () => {
  test('an integer wider than IEEE-754 exact range survives parsing with its digits intact', () => {
    const envelope = parseEnvelope(envelopeWithRawPayload(`{"photo_id":${WIDE_INTEGER}}`));
    expect(JSON.stringify(envelope.payload)).toBe(`{"photo_id":${WIDE_INTEGER}}`);
  });

  test('wide integers survive inside arrays and nested objects, negative and astronomically large included', () => {
    const raw = `{"a":[${NEGATIVE_WIDE_INTEGER},{"b":${HUGE_INTEGER}}],"c":{"d":[${WIDE_INTEGER}]}}`;
    const envelope = parseEnvelope(envelopeWithRawPayload(raw));
    expect(JSON.stringify(envelope.payload)).toBe(raw);
  });

  test('a number literal beyond the double range is kept rather than becoming null', () => {
    const envelope = parseEnvelope(envelopeWithRawPayload(`{"x":${BEYOND_DOUBLE_RANGE}}`));
    expect(JSON.stringify(envelope.payload)).toBe(`{"x":${BEYOND_DOUBLE_RANGE}}`);
  });

  test('integers inside the safe range stay plain numbers so adapters read them unchanged', () => {
    const raw = `{"max":${Number.MAX_SAFE_INTEGER},"min":${Number.MIN_SAFE_INTEGER},"small":7,"frac":2.5}`;
    const payload = parseEnvelope(envelopeWithRawPayload(raw)).payload as Record<string, unknown>;
    expect(payload).toEqual({ max: Number.MAX_SAFE_INTEGER, min: Number.MIN_SAFE_INTEGER, small: 7, frac: 2.5 });
    expect(typeof payload.max).toBe('number');
    expect(typeof payload.min).toBe('number');
  });

  test('a long digit run inside a string is left a string', () => {
    const raw = `{"photo_id":"${WIDE_INTEGER}","note":"id ${HUGE_INTEGER} here"}`;
    const payload = parseEnvelope(envelopeWithRawPayload(raw)).payload;
    expect(payload).toEqual({ photo_id: WIDE_INTEGER, note: `id ${HUGE_INTEGER} here` });
  });

  test('every integer from the first past the safe range onward is carried, where rounding starts', () => {
    const raw = `[${Number.MAX_SAFE_INTEGER},${Number.MAX_SAFE_INTEGER + 1},9007199254740993]`;
    const envelope = parseEnvelope(envelopeWithRawPayload(raw));
    expect(JSON.stringify(envelope.payload)).toBe(raw);
  });
});
