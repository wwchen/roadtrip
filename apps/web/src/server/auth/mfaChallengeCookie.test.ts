import { expect, test } from 'vitest';
import { MFA_CHALLENGE_EXPIRED, MFA_CHALLENGE_UNKNOWN } from '../service/settings/model';
import { openMfaChallenge, sealMfaChallenge } from './mfaChallengeCookie';

const KEY = Buffer.alloc(32, 3);
const NOW = Date.parse('2026-12-01T00:00:00Z');
const LATER = NOW + 60_000;

test('a sealed challenge opens for its own user before it expires', () => {
  const value = sealMfaChallenge(KEY, { challengeId: 'ch-1', userId: 7n, expiresAt: LATER });
  expect(openMfaChallenge(KEY, value, 7n, NOW)).toEqual({ challengeId: 'ch-1' });
});

test('the cookie value does not show the challenge id', () => {
  expect(sealMfaChallenge(KEY, { challengeId: 'ch-visible', userId: 7n, expiresAt: LATER })).not.toContain('ch-visible');
});

test('an expired challenge is mfa_challenge_expired', () => {
  const value = sealMfaChallenge(KEY, { challengeId: 'ch-1', userId: 7n, expiresAt: NOW });
  expect(openMfaChallenge(KEY, value, 7n, NOW)).toEqual({ code: MFA_CHALLENGE_EXPIRED });
});

test("another user's challenge is mfa_challenge_unknown", () => {
  const value = sealMfaChallenge(KEY, { challengeId: 'ch-1', userId: 8n, expiresAt: LATER });
  expect(openMfaChallenge(KEY, value, 7n, NOW)).toEqual({ code: MFA_CHALLENGE_UNKNOWN });
});

test.each([[undefined], [''], ['not-base64-!!'], ['AAAA']])('a missing or unreadable cookie %j is mfa_challenge_unknown', (value) => {
  expect(openMfaChallenge(KEY, value, 7n, NOW)).toEqual({ code: MFA_CHALLENGE_UNKNOWN });
});

const COOKIE_ENCODING = 'base64url';
const NONCE_BYTE = 0;
const CIPHERTEXT_BYTE = 12;
const LAST_TAG_BYTE = -1;
const EVERY_BIT = 0xff;

function withByteFlipped(value: string, index: number): string {
  const bytes = Buffer.from(value, COOKIE_ENCODING);
  const at = index < 0 ? bytes.length + index : index;
  bytes[at] = bytes[at]! ^ EVERY_BIT;
  return bytes.toString(COOKIE_ENCODING);
}

test.each([
  ['nonce', NONCE_BYTE],
  ['ciphertext', CIPHERTEXT_BYTE],
  ['tag', LAST_TAG_BYTE],
])('a cookie with a changed %s byte is mfa_challenge_unknown', (_part, index) => {
  const value = sealMfaChallenge(KEY, { challengeId: 'ch-1', userId: 7n, expiresAt: LATER });
  const tampered = withByteFlipped(value, index);
  expect(tampered).not.toBe(value);
  expect(openMfaChallenge(KEY, tampered, 7n, NOW)).toEqual({ code: MFA_CHALLENGE_UNKNOWN });
});
