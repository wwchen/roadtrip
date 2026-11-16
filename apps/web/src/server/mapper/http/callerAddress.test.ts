import { expect, test } from 'vitest';
import { callerAddress } from './callerAddress';

function withForwardedFor(value?: string): Request {
  return new Request('https://example.test/api/route', value === undefined ? {} : { headers: { 'x-forwarded-for': value } });
}

test('the caller is the first forwarded address, the one the edge saw', () => {
  expect(callerAddress(withForwardedFor('203.0.113.7'))).toBe('203.0.113.7');
  expect(callerAddress(withForwardedFor(' 203.0.113.7 , 10.0.0.2'))).toBe('203.0.113.7');
});

test('a request with no forwarded address shares one bucket', () => {
  expect(callerAddress(withForwardedFor())).toBe('unknown');
  expect(callerAddress(withForwardedFor(' , 10.0.0.2'))).toBe('unknown');
});
