import { expect, test } from 'vitest';
import { RELAY_CASES, RELAY_DECODED } from '@/server/testing/bookingRelayAnswers';
import { decodeAction, decodeHealth, decodeHold, decodeLogin, decodeSignOut, type RelayExchange } from './bookingRelayWire';

const DECODERS: Record<string, (exchange: RelayExchange) => unknown> = {
  hold: decodeHold,
  sign_in: decodeLogin,
  submit_mfa: decodeLogin,
  refresh: decodeAction,
  verify: decodeAction,
  health: decodeHealth,
  sign_out: decodeSignOut,
};

test('every answer Kotlin records has an expected decoding here, and no other', () => {
  expect(Object.keys(RELAY_DECODED).sort()).toEqual(Object.keys(RELAY_CASES).sort());
});

test.each(Object.entries(RELAY_CASES))('decodes Kotlin’s %s answer', (name, { operation, status, body }) => {
  const decode = DECODERS[operation];
  expect(decode, `no decoder for operation ${operation}`).toBeDefined();
  expect(decode({ kind: 'answered', status, body })).toStrictEqual(RELAY_DECODED[name as keyof typeof RELAY_DECODED]);
});
