import { describe, expect, test } from 'vitest';
import { STATUS_FORBIDDEN } from './httpAnswer';
import { CROSS_ORIGIN, crossOriginRefusal } from './sameOrigin';

const SERVED = 'https://roadtrip.test/api/settings/profile';
const SERVED_ORIGIN = 'https://roadtrip.test';
const SIBLING_ORIGIN = 'https://other.roadtrip.test';
const REFUSAL = { status: STATUS_FORBIDDEN, body: { error: CROSS_ORIGIN } };

function write(headers: Record<string, string>, url = SERVED, method = 'POST'): Request {
  return new Request(url, { method, headers });
}

describe('crossOriginRefusal', () => {
  test('a read is never refused, whatever it carries', () => {
    for (const method of ['GET', 'HEAD', 'OPTIONS']) {
      expect(crossOriginRefusal(write({ origin: SIBLING_ORIGIN, 'sec-fetch-site': 'cross-site' }, SERVED, method))).toBeNull();
    }
    expect(crossOriginRefusal(undefined)).toBeNull();
  });

  test('a write the browser marks same-origin passes without an Origin header', () => {
    expect(crossOriginRefusal(write({ 'sec-fetch-site': 'same-origin' }))).toBeNull();
  });

  test('a write whose Origin host is the served host passes', () => {
    expect(crossOriginRefusal(write({ origin: SERVED_ORIGIN }))).toBeNull();
    expect(crossOriginRefusal(write({ origin: SERVED_ORIGIN, host: 'roadtrip.test' }))).toBeNull();
  });

  test('behind a proxy that ends TLS, the forwarded host decides and the scheme does not', () => {
    const internal = 'http://web:3000/api/settings/profile';
    expect(crossOriginRefusal(write({ origin: SERVED_ORIGIN, host: 'web:3000', 'x-forwarded-host': 'roadtrip.test' }, internal))).toBeNull();
    expect(crossOriginRefusal(write({ origin: SERVED_ORIGIN, host: 'roadtrip.test' }, internal))).toBeNull();
    expect(crossOriginRefusal(write({ origin: SIBLING_ORIGIN, 'x-forwarded-host': 'roadtrip.test, web:3000' }, internal))).toEqual(REFUSAL);
  });

  test('a write from a sibling host on the same site is refused, which SameSite=Lax would let through', () => {
    expect(crossOriginRefusal(write({ origin: SIBLING_ORIGIN, 'sec-fetch-site': 'same-site' }))).toEqual(REFUSAL);
  });

  test('a write with no origin evidence, or an opaque or unreadable Origin, is refused', () => {
    expect(crossOriginRefusal(write({}))).toEqual(REFUSAL);
    expect(crossOriginRefusal(write({ origin: 'null' }))).toEqual(REFUSAL);
    expect(crossOriginRefusal(write({ origin: 'not a url' }))).toEqual(REFUSAL);
  });

  test('every write method is checked', () => {
    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
      expect(crossOriginRefusal(write({ origin: SIBLING_ORIGIN }, SERVED, method))).toEqual(REFUSAL);
    }
  });
});
