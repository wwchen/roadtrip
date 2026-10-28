import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { isWideNumber, parseEnvelope, serializeOpaquePayload } from './envelope.js';

const TEMP_DIR_PREFIX = 'envelope-';
const WIDE_INTEGER = '72157625256027958';
const NEGATIVE_WIDE_INTEGER = '-9007199254740993';
const BEYOND_DOUBLE_RANGE = '1e400';
const HUGE_INTEGER = '123456789012345678901234567890';
const FETCHER_VERSION = '1';
const FETCHED_AT = '2026-06-07T21:33:18Z';
const POLLER_RUN_ID = 42;
const HTTP_OK = 200;
const BAD_POLLER_RUN_ID = 'abc';
const FRACTIONAL_POLLER_RUN_ID = 1.5;
const BAD_PART = 5;

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

  test.each([
    ['request', (e: Record<string, unknown>) => ((e.request as Record<string, unknown>).headers = { nested: { a: 'b' } })],
    ['response', (e: Record<string, unknown>) => ((e.response as Record<string, unknown>).headers = { endpoint_status: { equipment: 200 } })],
    ['request', (e: Record<string, unknown>) => ((e.request as Record<string, unknown>).headers = { count: 7 })],
    ['response', (e: Record<string, unknown>) => ((e.response as Record<string, unknown>).headers = ['a'])],
  ])('a %s header map holding a non-string value or a non-object is rejected', (side, mutate) => {
    const file = envelopeWith((e) => {
      mutate(e);
    });
    expect(() => parseEnvelope(file)).toThrow(new RegExp(`${side}\\.headers`));
  });

  test('absent headers on request and response default to empty maps', () => {
    const envelope = parseEnvelope(
      envelopeWith((e) => {
        delete (e.request as Record<string, unknown>).headers;
        delete (e.response as Record<string, unknown>).headers;
      }),
    );
    expect(envelope.request.headers).toEqual({});
    expect(envelope.response.headers).toEqual({});
  });

  test('unknown keys inside request and response are dropped', () => {
    const envelope = parseEnvelope(
      envelopeWith((e) => {
        (e.request as Record<string, unknown>).extra = 'x';
        (e.response as Record<string, unknown>).extra = 'y';
      }),
    );
    expect(envelope.request).toEqual({ url: 'https://example.test/', method: 'GET', headers: { accept: '*/*' } });
    expect(envelope.response).toEqual({ status: HTTP_OK, headers: {} });
  });

  test.each([
    ['a string', BAD_POLLER_RUN_ID],
    ['a fraction', FRACTIONAL_POLLER_RUN_ID],
  ])('a poller_run_id that is %s is rejected', (_label, value) => {
    const file = envelopeWith((e) => {
      e.poller_run_id = value;
    });
    expect(() => parseEnvelope(file)).toThrow(/poller_run_id/);
  });

  test('a poller_run_id wider than the safe range is rejected rather than carried as a non-number', () => {
    const file = fileHolding(BASE_ENVELOPE.replace('"poller_run_id": null', `"poller_run_id": ${WIDE_INTEGER}`));
    expect(() => parseEnvelope(file)).toThrow(/poller_run_id/);
  });

  test('a part that is not a string is rejected and a null part is absent', () => {
    const bad = envelopeWith((e) => {
      e.part = BAD_PART;
    });
    expect(() => parseEnvelope(bad)).toThrow(/part must be a string/);
    const absent = parseEnvelope(
      envelopeWith((e) => {
        e.part = null;
      }),
    );
    expect(absent.part).toBeUndefined();
    expect(absent).not.toHaveProperty('part');
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

describe('the wide-number carrier contract', () => {
  function parsedWide(): unknown {
    return parseEnvelope(envelopeWithRawPayload(`{"photo_id":${WIDE_INTEGER}}`)).payload;
  }

  function wideField(): unknown {
    return (parsedWide() as Record<string, unknown>).photo_id;
  }

  test('isWideNumber recognises a carried integer and nothing else', () => {
    expect(isWideNumber(wideField())).toBe(true);
    expect(isWideNumber(7)).toBe(false);
    expect(isWideNumber(WIDE_INTEGER)).toBe(false);
    expect(isWideNumber({ rawJSON: WIDE_INTEGER })).toBe(false);
    expect(isWideNumber(null)).toBe(false);
  });

  test('serializeOpaquePayload writes a carried integer as an unquoted literal with its digits', () => {
    expect(serializeOpaquePayload(parsedWide())).toBe(`{"photo_id":${WIDE_INTEGER}}`);
  });

  test('serializeOpaquePayload handles a payload that is itself a wide integer, and a null payload', () => {
    const wideTop = parseEnvelope(envelopeWithRawPayload(HUGE_INTEGER)).payload;
    expect(serializeOpaquePayload(wideTop)).toBe(HUGE_INTEGER);
    expect(serializeOpaquePayload(null)).toBe('null');
  });

  test('serializeOpaquePayload refuses a value JSON cannot represent', () => {
    expect(() => serializeOpaquePayload(undefined)).toThrow(/not JSON-serializable/);
  });

  test('HAZARD: structuredClone of a payload holding a carried integer throws', () => {
    expect(() => structuredClone(parsedWide())).toThrow();
  });

  test('HAZARD: object spread of a carried integer silently loses the digits', () => {
    const spread = { ...(wideField() as object) };
    expect(isWideNumber(spread)).toBe(false);
    expect(serializeOpaquePayload(spread)).toBe(`{"rawJSON":"${WIDE_INTEGER}"}`);
  });

  test('HAZARD: a serialize-then-parse round trip rounds the integer, so serialize once from the parsed value', () => {
    const roundTripped = JSON.parse(serializeOpaquePayload(parsedWide())) as { photo_id: number };
    expect(typeof roundTripped.photo_id).toBe('number');
    expect(String(roundTripped.photo_id)).not.toBe(WIDE_INTEGER);
    expect(serializeOpaquePayload(roundTripped)).not.toContain(WIDE_INTEGER);
  });
});

describe('number normalisation is intended', () => {
  test('decimal literals are normalised, so the differential must compare jsonb values and never jsonb text: 10.0 serialises as 10', () => {
    const decimals = parseEnvelope(envelopeWithRawPayload('{"minValue":0.0,"maxValue":10.0,"exp":1e2}')).payload;
    expect(serializeOpaquePayload(decimals)).toBe('{"minValue":0,"maxValue":10,"exp":100}');
  });

  test('wide integers beside a normalised decimal keep their digits, because their value changes if they are rounded', () => {
    const raw = `{"id":${WIDE_INTEGER},"maxValue":10.0}`;
    const payload = parseEnvelope(envelopeWithRawPayload(raw)).payload;
    expect(serializeOpaquePayload(payload)).toBe(`{"id":${WIDE_INTEGER},"maxValue":10}`);
  });
});
