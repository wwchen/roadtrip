import { describe, expect, test } from 'vitest';
import { mfaClearCookie, sealMfaChallenge } from '../../auth/mfaChallengeCookie';
import type { ActiveUser } from '../../service/auth/model';
import type { LoginOutcome } from '../../service/settings/model';
import {
  challengeCodec,
  loginAnswer,
  parseCredentials,
  parseMfaCode,
  pendingChallenge,
  removeAnswer,
  saveAnswer,
  statusAnswer,
  verifyAnswer,
} from './recgovHttp';

const USER: ActiveUser = { id: 7n, email: 'a@b.test', emailVerified: true, displayName: null, theme: 'system', status: 'active' };
const KEY = Buffer.alloc(32, 5);
const NOW = Date.parse('2026-12-01T00:00:00Z');
const clock = { now: () => NOW };
const codec = challengeCodec(KEY, clock);
const CHALLENGE = { challengeId: 'ch-9', userId: 7n, expiresAt: NOW + 120_000 };

describe('parseCredentials', () => {
  test('trims the username and keeps a non-blank password as given', () => {
    expect(parseCredentials({ username: ' rec@example.com ', password: ' pw ' })).toEqual({
      kind: 'ok',
      value: { username: 'rec@example.com', password: ' pw ' },
    });
  });

  test('an absent, null or blank password is null', () => {
    for (const password of [undefined, null, '   ']) {
      expect(parseCredentials({ username: 'a', password })).toEqual({ kind: 'ok', value: { username: 'a', password: null } });
    }
  });

  test.each([[null], [[]], ['x'], [{ username: 5 }], [{ username: 'a', password: 5 }]])('%j is 400 invalid_body', (body) => {
    expect(parseCredentials(body)).toMatchObject({
      kind: 'invalid',
      answer: { status: 400, body: { error: 'invalid_body', detail: 'Request body is not valid JSON for this endpoint' } },
    });
  });

  test('a blank or missing username is 400 invalid_field', () => {
    for (const body of [{ username: '  ' }, {}, { username: null }]) {
      expect(parseCredentials(body)).toMatchObject({
        kind: 'invalid',
        answer: { status: 400, body: { error: 'invalid_field', detail: 'username must not be blank' } },
      });
    }
  });
});

describe('parseMfaCode', () => {
  test('trims the code', () => {
    expect(parseMfaCode({ code: ' 123456 ' })).toEqual({ kind: 'ok', value: '123456' });
  });

  test.each([[undefined], [null], ['x'], [{}], [{ code: '  ' }], [{ code: 1 }]])('%j is 400 invalid_body', (body) => {
    expect(parseMfaCode(body)).toMatchObject({ kind: 'invalid', answer: { status: 400, body: { error: 'invalid_body' } } });
  });
});

describe('challengeCodec', () => {
  test('opens a live sealed challenge', () => {
    expect(codec.open(sealMfaChallenge(KEY, CHALLENGE), 7n)).toEqual({ kind: 'open', challengeId: 'ch-9' });
  });

  test('an absent, foreign or garbled cookie is none and a past one is expired', () => {
    expect(codec.open(undefined, 7n)).toEqual({ kind: 'none' });
    expect(codec.open(sealMfaChallenge(KEY, CHALLENGE), 8n)).toEqual({ kind: 'none' });
    expect(codec.open('garbage', 7n)).toEqual({ kind: 'none' });
    expect(codec.open(sealMfaChallenge(KEY, { ...CHALLENGE, expiresAt: NOW }), 7n)).toEqual({ kind: 'expired' });
  });

  test('with no key it opens none and seals null', () => {
    const keyless = challengeCodec(null, clock);
    expect(keyless.open(sealMfaChallenge(KEY, CHALLENGE), 7n)).toEqual({ kind: 'none' });
    expect(keyless.seal(CHALLENGE)).toBeNull();
  });

  test('seal gives the whole Set-Cookie value with the seconds left, never negative', () => {
    const sealed = codec.seal(CHALLENGE) ?? '';
    expect(sealed).toContain('__Secure-recgov-mfa=');
    expect(sealed).toContain('Max-Age=120');
    expect(codec.seal({ ...CHALLENGE, expiresAt: NOW - 5_000 })).toContain('Max-Age=0');
  });
});

describe('pendingChallenge', () => {
  test('reads the cookie off the request, and no request is no cookie', () => {
    const value = sealMfaChallenge(KEY, CHALLENGE);
    const request = new Request('http://localhost/x', { headers: { cookie: `a=1; __Secure-recgov-mfa=${value}` } });
    expect(pendingChallenge(request, USER, codec)).toEqual({ kind: 'open', challengeId: 'ch-9' });
    expect(pendingChallenge(undefined, USER, codec)).toEqual({ kind: 'none' });
  });
});

describe('answers', () => {
  test('save', () => {
    expect(saveAnswer({ kind: 'saved', settings: { configured: true, username: 'u' } })).toEqual({
      status: 200,
      body: { recgov_configured: true, recgov_username: 'u' },
    });
    expect(saveAnswer({ kind: 'saved', settings: { configured: false } })).toEqual({ status: 200, body: { recgov_configured: false } });
    expect(saveAnswer({ kind: 'password_required' })).toEqual({
      status: 400,
      body: { error: 'invalid_field', detail: 'password is required the first time credentials are saved' },
    });
    expect(saveAnswer({ kind: 'vault_unavailable' })).toEqual({
      status: 503,
      body: { error: 'encryption_unavailable', detail: 'Encryption key not configured; cannot store rec.gov credentials' },
    });
    expect(saveAnswer({ kind: 'wipe_failed' })).toEqual({
      status: 502,
      body: { error: 'recgov_profile_wipe_failed', detail: 'the rec.gov profile could not be wiped, so nothing was changed' },
    });
  });

  test('remove', () => {
    expect(removeAnswer({ kind: 'removed', removed: true, signedOut: false, profileDestroyed: true })).toEqual({
      status: 200,
      body: { removed: true, stranded_atc_watches: 0, companion_signed_out: false, profile_destroyed: true },
    });
    expect(removeAnswer({ kind: 'wipe_failed' })).toMatchObject({ status: 502, body: { error: 'recgov_profile_wipe_failed' } });
  });

  test('login signed_in clears the cookie', () => {
    expect(loginAnswer({ kind: 'signed_in', challenge: 'end' }, codec)).toEqual({ status: 200, body: { status: 'ok' }, cookie: mfaClearCookie() });
  });

  test('login mfa_required seals the issued challenge, or sets no cookie without a key', () => {
    const outcome: LoginOutcome = { kind: 'mfa_required', challengeId: 'ch-9', expiresAt: 'x', challenge: { issue: CHALLENGE } };
    const answered = loginAnswer(outcome, codec);
    expect(answered.body).toEqual({ status: 'mfa_required', challenge_id: 'ch-9', expires_at: 'x' });
    expect(answered.cookie).toContain('Max-Age=120');
    expect(loginAnswer(outcome, challengeCodec(null, clock)).cookie).toBeUndefined();
    expect(loginAnswer({ ...outcome, expiresAt: undefined } as LoginOutcome, codec).body).toEqual({ status: 'mfa_required', challenge_id: 'ch-9' });
  });

  test('login failed carries code and detail, and the cookie follows the challenge change', () => {
    expect(loginAnswer({ kind: 'failed', code: 'mfa_invalid', detail: 'd', challenge: 'end' }, codec)).toEqual({
      status: 200,
      body: { status: 'failed', error: 'mfa_invalid', detail: 'd' },
      cookie: mfaClearCookie(),
    });
    expect(loginAnswer({ kind: 'failed', code: 'profile_busy', challenge: 'keep' }, codec)).toEqual({
      status: 200,
      body: { status: 'failed', error: 'profile_busy' },
    });
  });

  test('a challenge the web app finds gone carries its detail and clears the cookie', () => {
    expect(loginAnswer({ kind: 'challenge_gone', reason: 'none' }, codec).cookie).toBe(mfaClearCookie());
    expect(loginAnswer({ kind: 'challenge_gone', reason: 'expired' }, codec).cookie).toBe(mfaClearCookie());
    expect(loginAnswer({ kind: 'challenge_gone', reason: 'none' }, codec).body).toEqual({
      status: 'failed',
      error: 'mfa_challenge_unknown',
      detail: 'no login is waiting for a code',
    });
    expect(loginAnswer({ kind: 'challenge_gone', reason: 'expired' }, codec).body).toEqual({
      status: 'failed',
      error: 'mfa_challenge_expired',
      detail: 'the code request expired before the code was entered',
    });
  });

  test('a relay refusal with a challenge code and no detail has no detail key', () => {
    for (const code of ['mfa_challenge_unknown', 'mfa_challenge_expired']) {
      expect(loginAnswer({ kind: 'failed', code, challenge: 'end' }, codec)).toEqual({
        status: 200,
        body: { status: 'failed', error: code },
        cookie: mfaClearCookie(),
      });
    }
  });

  test('login unavailable is companion_unavailable, clearing only on end', () => {
    expect(loginAnswer({ kind: 'unavailable', challenge: 'keep' }, codec)).toEqual({
      status: 200,
      body: { status: 'failed', error: 'companion_unavailable' },
    });
    expect(loginAnswer({ kind: 'unavailable', challenge: 'end' }, codec).cookie).toBe(mfaClearCookie());
  });

  test('login vault_unavailable and not_configured', () => {
    expect(loginAnswer({ kind: 'vault_unavailable' }, codec)).toMatchObject({ status: 503, body: { error: 'encryption_unavailable' } });
    expect(loginAnswer({ kind: 'not_configured' }, codec)).toEqual({
      status: 409,
      body: { error: 'recgov_not_configured', detail: 'no rec.gov credentials are stored' },
    });
  });

  test('verify', () => {
    expect(verifyAnswer({ kind: 'verified' })).toEqual({ status: 200, body: { ok: true } });
    expect(verifyAnswer({ kind: 'failed', code: 'c', detail: 'd' })).toEqual({ status: 200, body: { ok: false, error: 'c', detail: 'd' } });
    expect(verifyAnswer({ kind: 'failed', code: 'c' })).toEqual({ status: 200, body: { ok: false, error: 'c' } });
    expect(verifyAnswer({ kind: 'unavailable' })).toEqual({ status: 200, body: { ok: false, error: 'companion_unavailable' } });
    expect(verifyAnswer({ kind: 'vault_unavailable' }).status).toBe(503);
    expect(verifyAnswer({ kind: 'not_configured' }).status).toBe(409);
  });

  test('status', () => {
    expect(statusAnswer({ configured: false })).toEqual({
      status: 200,
      body: { configured: false, session: 'not_configured', mfa_pending: false },
    });
    expect(statusAnswer({ configured: true, username: 'u', session: 'expired', detail: 'd', mfaPending: true })).toEqual({
      status: 200,
      body: { configured: true, username: 'u', session: 'expired', detail: 'd', mfa_pending: true },
    });
    expect(statusAnswer({ configured: true, username: 'u', session: 'active', mfaPending: false }).body).toEqual({
      configured: true,
      username: 'u',
      session: 'active',
      mfa_pending: false,
    });
  });
});
