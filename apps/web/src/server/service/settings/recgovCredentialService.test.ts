import { expect, test, vi } from 'vitest';
import { decryptSecret, encryptSecret } from '../../crypto/secretCipher';
import type { RelayResult } from '../../client/bookingRelay';
import type { AppUserRow } from '../../repo/appUserRepo';
import { sealMfaChallenge } from '../../auth/mfaChallengeCookie';
import { login, completeMfa, remove, save, status, verify, type RecgovDeps } from './recgovCredentialService';

const USER: AppUserRow = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: null, theme: 'system' };
const KEY = Buffer.alloc(32, 5);
const NOW = Date.parse('2026-12-01T00:00:00Z');
const TTL_MS = 300_000;

interface Store {
  row: { username: string; secretCipher: Buffer } | null;
}

function answer(body: Record<string, unknown>, status = 200): RelayResult {
  return { ok: true, status, answer: { ok: false, ...body } as never };
}

function deps(store: Store, relay: RecgovDeps['relay'] | null = vi.fn(), overrides: Partial<RecgovDeps> = {}): RecgovDeps {
  return {
    cipherKey: KEY,
    mfaChallengeTtlMs: TTL_MS,
    now: () => NOW,
    relay,
    find: vi.fn(async () => store.row),
    upsert: vi.fn(async (_id, username, secretCipher) => {
      store.row = { username, secretCipher };
    }),
    updateUsername: vi.fn(async (_id, username) => {
      if (store.row) store.row = { ...store.row, username };
    }),
    removeRow: vi.fn(async () => {
      const had = store.row !== null;
      store.row = null;
      return had;
    }),
    ...overrides,
  };
}

const stored = (username = 'rec@example.com', password = 'hunter2'): Store => ({ row: { username, secretCipher: encryptSecret(KEY, password) } });

test('the first save needs a password and seals it', async () => {
  const store: Store = { row: null };
  expect(await save(USER, { username: 'rec@example.com' }, deps(store))).toMatchObject({ status: 400, body: { error: 'invalid_field' } });
  expect(await save(USER, { username: 'rec@example.com', password: 'hunter2' }, deps(store))).toEqual({
    status: 200,
    body: { recgov_configured: true, recgov_username: 'rec@example.com' },
  });
  expect(decryptSecret(KEY, store.row!.secretCipher)).toBe('hunter2');
});

test('a blank username is invalid_field and a non-object body is invalid_body', async () => {
  expect(await save(USER, { username: '  ', password: 'x' }, deps({ row: null }))).toMatchObject({ status: 400, body: { error: 'invalid_field' } });
  expect(await save(USER, 'nope', deps({ row: null }))).toMatchObject({ status: 400, body: { error: 'invalid_body' } });
});

test('saving without the key is 503 encryption_unavailable', async () => {
  const result = await save(USER, { username: 'rec@example.com', password: 'hunter2' }, deps({ row: null }, vi.fn(), { cipherKey: null }));
  expect(result).toMatchObject({ status: 503, body: { error: 'encryption_unavailable' } });
});

test('saving the same username again does not wipe the profile', async () => {
  const relay = vi.fn();
  await save(USER, { username: 'rec@example.com', password: 'new' }, deps(stored(), relay));
  expect(relay).not.toHaveBeenCalled();
});

test('a new username wipes the profile first', async () => {
  const relay = vi.fn().mockResolvedValue({ ok: true, status: 200, answer: { ok: true, signed_out: true, profile_destroyed: true } });
  const store = stored();
  const result = await save(USER, { username: 'other@example.com' }, deps(store, relay));
  expect(relay).toHaveBeenCalledWith('signout', { profile_id: 'n:7' });
  expect(result.status).toBe(200);
  expect(store.row?.username).toBe('other@example.com');
  expect(decryptSecret(KEY, store.row!.secretCipher)).toBe('hunter2');
});

test('a failed wipe refuses the save and keeps the old row', async () => {
  const relay = vi.fn().mockResolvedValue(answer({ code: 'destroy_failed', profile_destroyed: false }));
  const store = stored();
  const result = await save(USER, { username: 'other@example.com', password: 'x' }, deps(store, relay));
  expect(result).toMatchObject({ status: 502, body: { error: 'recgov_profile_wipe_failed' } });
  expect(store.row?.username).toBe('rec@example.com');
});

test('remove signs out, wipes and deletes', async () => {
  const relay = vi.fn().mockResolvedValue({ ok: true, status: 200, answer: { ok: true, signed_out: true, profile_destroyed: true } });
  const store = stored();
  expect(await remove(USER, deps(store, relay))).toEqual({
    status: 200,
    body: { removed: true, stranded_atc_watches: 0, companion_signed_out: true, profile_destroyed: true },
  });
  expect(store.row).toBeNull();
});

test('remove refuses when the wipe fails or mini-ca is unreachable, and keeps the row', async () => {
  for (const result of [answer({ code: 'destroy_failed', profile_destroyed: false }), { ok: false, code: 'unreachable' } as RelayResult]) {
    const store = stored();
    expect(await remove(USER, deps(store, vi.fn().mockResolvedValue(result)))).toMatchObject({
      status: 502,
      body: { error: 'recgov_profile_wipe_failed' },
    });
    expect(store.row).not.toBeNull();
  }
});

test('remove with no relay configured deletes the row and says nothing was wiped', async () => {
  const store = stored();
  expect(await remove(USER, deps(store, null))).toEqual({
    status: 200,
    body: { removed: true, stranded_atc_watches: 0, companion_signed_out: false, profile_destroyed: false },
  });
});

test('login refreshes first and sends no password when that works', async () => {
  const relay = vi.fn().mockResolvedValue({ ok: true, status: 200, answer: { ok: true } });
  expect(await login(USER, deps(stored(), relay))).toEqual({ status: 200, body: { status: 'ok' }, cookie: 'clear' });
  expect(relay).toHaveBeenCalledTimes(1);
  expect(relay).toHaveBeenCalledWith('refresh', { profile_id: 'n:7' });
});

test('login signs in with the decrypted password when the refresh does not help', async () => {
  const relay = vi
    .fn()
    .mockResolvedValueOnce(answer({ code: 'recgov_refresh_failed' }))
    .mockResolvedValueOnce({ ok: true, status: 200, answer: { ok: true } });
  expect((await login(USER, deps(stored(), relay))).body).toEqual({ status: 'ok' });
  expect(relay).toHaveBeenLastCalledWith('login', { profile_id: 'n:7', username: 'rec@example.com', password: 'hunter2' });
});

test('mfa_required sets the sealed challenge cookie', async () => {
  const relay = vi
    .fn()
    .mockResolvedValueOnce(answer({ code: 'recgov_refresh_failed' }))
    .mockResolvedValueOnce(answer({ code: 'mfa_required', challenge_id: 'ch-9', expires_at: '2026-12-01T00:02:00Z' }));
  const result = await login(USER, deps(stored(), relay));
  expect(result.body).toEqual({ status: 'mfa_required', challenge_id: 'ch-9', expires_at: '2026-12-01T00:02:00Z' });
  expect(result.cookie).toMatchObject({ maxAgeSeconds: 120 });
});

test('login without stored credentials is 409 recgov_not_configured, and without a relay is companion_unavailable', async () => {
  expect(await login(USER, deps({ row: null }))).toMatchObject({ status: 409, body: { error: 'recgov_not_configured' } });
  expect((await login(USER, deps(stored(), null))).body).toEqual({ status: 'failed', error: 'companion_unavailable' });
});

test('mfa completes the challenge the cookie names, and clears it', async () => {
  const relay = vi.fn().mockResolvedValue({ ok: true, status: 200, answer: { ok: true } });
  const cookie = sealMfaChallenge(KEY, { challengeId: 'ch-9', userId: 7n, expiresAt: NOW + 60_000 });
  expect(await completeMfa(USER, { code: '123456' }, cookie, deps(stored(), relay))).toEqual({ status: 200, body: { status: 'ok' }, cookie: 'clear' });
  expect(relay).toHaveBeenCalledWith('login/mfa', { profile_id: 'n:7', challenge_id: 'ch-9', mfa_code: '123456' });
});

test('mfa with no, expired or foreign cookie fails and clears it, without calling mini-ca', async () => {
  const relay = vi.fn();
  const expired = sealMfaChallenge(KEY, { challengeId: 'ch-9', userId: 7n, expiresAt: NOW });
  const foreign = sealMfaChallenge(KEY, { challengeId: 'ch-9', userId: 8n, expiresAt: NOW + 60_000 });
  expect(await completeMfa(USER, { code: '1' }, undefined, deps(stored(), relay))).toMatchObject({ body: { error: 'mfa_challenge_unknown' }, cookie: 'clear' });
  expect(await completeMfa(USER, { code: '1' }, expired, deps(stored(), relay))).toMatchObject({ body: { error: 'mfa_challenge_expired' }, cookie: 'clear' });
  expect(await completeMfa(USER, { code: '1' }, foreign, deps(stored(), relay))).toMatchObject({ body: { error: 'mfa_challenge_unknown' }, cookie: 'clear' });
  expect(relay).not.toHaveBeenCalled();
});

test('mfa without a code is invalid_body', async () => {
  expect(await completeMfa(USER, {}, undefined, deps(stored()))).toMatchObject({ status: 400, body: { error: 'invalid_body' } });
});

test('verify answers the dry run', async () => {
  const relay = vi.fn().mockResolvedValue(answer({ code: 'recgov_not_authenticated', detail: 'no session' }));
  expect(await verify(USER, deps(stored(), relay))).toEqual({
    status: 200,
    body: { ok: false, error: 'recgov_not_authenticated', detail: 'no session' },
  });
});

test('status without stored credentials never calls mini-ca', async () => {
  const relay = vi.fn();
  expect(await status(USER, undefined, deps({ row: null }, relay))).toEqual({
    status: 200,
    body: { configured: false, session: 'not_configured', mfa_pending: false },
  });
  expect(relay).not.toHaveBeenCalled();
});

test.each([
  [{ ok: true, status: 200, answer: { ok: true, health: 'active' } }, { session: 'active' }],
  [{ ok: true, status: 200, answer: { ok: false, health: 'never_logged_in' } }, { session: 'not_logged_in' }],
  [{ ok: true, status: 200, answer: { ok: false, health: 'inactive' } }, { session: 'expired', detail: 'recgov_session_lapsed' }],
  [{ ok: true, status: 200, answer: { ok: false, health: 'check_failed', code: 'x' } }, { session: 'check_failed', detail: 'x' }],
  [{ ok: false, code: 'unreachable' }, { session: 'companion_unavailable' }],
])('status maps %j to %j', async (result, expected) => {
  const body = (await status(USER, undefined, deps(stored(), vi.fn().mockResolvedValue(result)))).body;
  expect(body).toEqual({ configured: true, username: 'rec@example.com', mfa_pending: false, ...expected });
});

test('status reports a pending challenge from a live cookie', async () => {
  const cookie = sealMfaChallenge(KEY, { challengeId: 'ch-9', userId: 7n, expiresAt: NOW + 60_000 });
  const relay = vi.fn().mockResolvedValue({ ok: true, status: 200, answer: { ok: false, health: 'never_logged_in' } });
  expect((await status(USER, cookie, deps(stored(), relay))).body).toMatchObject({ mfa_pending: true });
});

test.each([
  ['the profile is busy', answer({ code: 'profile_busy', category: 'retry_later' })],
  ['mini-ca is unreachable', { ok: false, code: 'unreachable' } as RelayResult],
  ['the relay timed out', answer({ code: 'relay_timeout', category: 'retry_later' }, 504)],
])('an mfa submit that fails because %s keeps the challenge cookie for a retry', async (_label, result) => {
  const cookie = sealMfaChallenge(KEY, { challengeId: 'ch-9', userId: 7n, expiresAt: NOW + 60_000 });
  const answered = await completeMfa(USER, { code: '1' }, cookie, deps(stored(), vi.fn().mockResolvedValue(result)));
  expect(answered.cookie).toBeUndefined();
});

test('a rejected code ends the challenge and clears the cookie', async () => {
  const cookie = sealMfaChallenge(KEY, { challengeId: 'ch-9', userId: 7n, expiresAt: NOW + 60_000 });
  const relay = vi.fn().mockResolvedValue(answer({ code: 'mfa_invalid', category: 'caller_action' }));
  expect((await completeMfa(USER, { code: '1' }, cookie, deps(stored(), relay))).cookie).toBe('clear');
});
