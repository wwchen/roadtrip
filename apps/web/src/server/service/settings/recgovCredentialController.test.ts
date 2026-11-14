import { expect, test, vi } from 'vitest';
import { sealMfaChallenge, mfaClearCookie } from '../../auth/mfaChallengeCookie';
import { unconfiguredBookingGateway } from '../../client/bookingRelay';
import { challengeCodec, loginAnswer, parseCredentials, parseMfaCode, removeAnswer, saveAnswer, statusAnswer, verifyAnswer } from '../../mapper/http/recgovHttp';
import { memoryVault } from '../../testing/memoryVault';
import type { ActiveUser } from '../auth/model';
import type { BookingGateway, CredentialVault } from '../booking/ports';
import { recgovStatus, removeCredentials, saveCredentials, signIn, submitMfa, verifySession } from './recgovCredentialController';

const USER: ActiveUser = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: null, theme: 'system', status: 'active' };
const KEY = Buffer.alloc(32, 5);
const NOW = Date.parse('2026-12-01T00:00:00Z');
const TTL_MS = 300_000;
const clock = { now: () => NOW };
const codec = challengeCodec(KEY, clock);
const STORED = { username: 'rec@example.com', password: 'hunter2' };
const WIPED = { kind: 'wiped' as const, signedOut: true };
const DESTROY_FAILED = {
  kind: 'failed' as const,
  signedOut: false,
  refusal: { category: 'upstream' as const, code: 'destroy_failed' },
};
const UNREACHABLE = { kind: 'unavailable' as const, fault: 'unreachable' as const };

function gateway(overrides: Partial<BookingGateway> = {}): BookingGateway {
  return {
    hold: vi.fn(),
    signIn: vi.fn(),
    submitMfa: vi.fn(),
    refresh: vi.fn(),
    verify: vi.fn(),
    health: vi.fn(),
    signOut: vi.fn(),
    ...overrides,
  };
}

const ports = (g: BookingGateway, vault: CredentialVault) => ({ gateway: g, vault, clock, mfaChallengeTtlMs: TTL_MS });

async function save(body: unknown, g: BookingGateway, vault: CredentialVault) {
  const command = parseCredentials(body);
  if (command.kind === 'invalid') return command.answer;
  return saveAnswer(await saveCredentials(USER, command.value, ports(g, vault)));
}

const remove = async (g: BookingGateway, vault: CredentialVault) => removeAnswer(await removeCredentials(USER, ports(g, vault)));
const login = async (g: BookingGateway, vault: CredentialVault) => loginAnswer(await signIn(USER, ports(g, vault)), codec);
const verify = async (g: BookingGateway, vault: CredentialVault) => verifyAnswer(await verifySession(USER, ports(g, vault)));

const live = (userId = 7n, expiresAt = NOW + 60_000) => sealMfaChallenge(KEY, { challengeId: 'ch-9', userId, expiresAt });

async function submit(body: unknown, cookie: string | undefined, g: BookingGateway, vault: CredentialVault, withCodec = codec) {
  const code = parseMfaCode(body);
  if (code.kind === 'invalid') return code.answer;
  return loginAnswer(await submitMfa(USER, code.value, withCodec.open(cookie, USER.id), ports(g, vault)), withCodec);
}

async function status(cookie: string | undefined, g: BookingGateway, vault: CredentialVault, withCodec = codec) {
  return statusAnswer(await recgovStatus(USER, withCodec.open(cookie, USER.id), ports(g, vault)));
}

test('the first save needs a password and seals it', async () => {
  const { vault, row } = memoryVault(null);
  expect(await save({ username: 'rec@example.com' }, gateway(), vault)).toMatchObject({ status: 400, body: { error: 'invalid_field' } });
  expect(await save({ username: 'rec@example.com', password: 'hunter2' }, gateway(), vault)).toEqual({
    status: 200,
    body: { recgov_configured: true, recgov_username: 'rec@example.com' },
  });
  expect(row()).toEqual(STORED);
});

test('a blank username is invalid_field and a non-object body is invalid_body', async () => {
  expect(await save({ username: '  ', password: 'x' }, gateway(), memoryVault(null).vault)).toMatchObject({ status: 400, body: { error: 'invalid_field' } });
  expect(await save('nope', gateway(), memoryVault(null).vault)).toMatchObject({ status: 400, body: { error: 'invalid_body' } });
});

test('saving without the key is 503 encryption_unavailable', async () => {
  const result = await save({ username: 'rec@example.com', password: 'hunter2' }, gateway(), memoryVault(null, false).vault);
  expect(result).toMatchObject({ status: 503, body: { error: 'encryption_unavailable' } });
});

test('saving the same username again does not wipe the profile', async () => {
  const g = gateway();
  await save({ username: 'rec@example.com', password: 'new' }, g, memoryVault(STORED).vault);
  expect(g.signOut).not.toHaveBeenCalled();
});

test('a new username wipes the profile first', async () => {
  const g = gateway({ signOut: vi.fn().mockResolvedValue(WIPED) });
  const { vault, row } = memoryVault(STORED);
  const result = await save({ username: 'other@example.com' }, g, vault);
  expect(g.signOut).toHaveBeenCalledWith(7n);
  expect(result.status).toBe(200);
  expect(row()).toEqual({ username: 'other@example.com', password: 'hunter2' });
});

test('a failed wipe refuses the save and keeps the old row', async () => {
  const g = gateway({ signOut: vi.fn().mockResolvedValue(DESTROY_FAILED) });
  const { vault, row } = memoryVault(STORED);
  const result = await save({ username: 'other@example.com', password: 'x' }, g, vault);
  expect(result).toMatchObject({ status: 502, body: { error: 'recgov_profile_wipe_failed' } });
  expect(row()?.username).toBe('rec@example.com');
});

test('remove signs out, wipes and deletes', async () => {
  const { vault, row } = memoryVault(STORED);
  expect(await remove(gateway({ signOut: vi.fn().mockResolvedValue(WIPED) }), vault)).toEqual({
    status: 200,
    body: { removed: true, stranded_atc_watches: 0, companion_signed_out: true, profile_destroyed: true },
  });
  expect(row()).toBeNull();
});

test('remove refuses when the wipe fails or mini-ca is unreachable, and keeps the row', async () => {
  for (const result of [DESTROY_FAILED, UNREACHABLE]) {
    const { vault, row } = memoryVault(STORED);
    expect(await remove(gateway({ signOut: vi.fn().mockResolvedValue(result) }), vault)).toMatchObject({
      status: 502,
      body: { error: 'recgov_profile_wipe_failed' },
    });
    expect(row()).not.toBeNull();
  }
});

test('remove with no relay configured deletes the row and says nothing was wiped', async () => {
  const { vault } = memoryVault(STORED);
  expect(await remove(unconfiguredBookingGateway, vault)).toEqual({
    status: 200,
    body: { removed: true, stranded_atc_watches: 0, companion_signed_out: false, profile_destroyed: false },
  });
});

test('login refreshes first and sends no password when that works', async () => {
  const g = gateway({ refresh: vi.fn().mockResolvedValue({ kind: 'ok' }) });
  expect(await login(g, memoryVault(STORED).vault)).toEqual({ status: 200, body: { status: 'ok' }, cookie: mfaClearCookie() });
  expect(g.refresh).toHaveBeenCalledTimes(1);
  expect(g.refresh).toHaveBeenCalledWith(7n);
  expect(g.signIn).not.toHaveBeenCalled();
});

test('login signs in with the decrypted password when the refresh does not help', async () => {
  const g = gateway({
    refresh: vi.fn().mockResolvedValue({ kind: 'failed', refusal: { category: 'upstream', code: 'recgov_refresh_failed' } }),
    signIn: vi.fn().mockResolvedValue({ kind: 'signed_in' }),
  });
  expect((await login(g, memoryVault(STORED).vault)).body).toEqual({ status: 'ok' });
  expect(g.signIn).toHaveBeenLastCalledWith(7n, 'rec@example.com', 'hunter2');
});

test('mfa_required sets the sealed challenge cookie', async () => {
  const g = gateway({
    refresh: vi.fn().mockResolvedValue({ kind: 'failed', refusal: { category: 'upstream', code: 'recgov_refresh_failed' } }),
    signIn: vi.fn().mockResolvedValue({ kind: 'mfa_required', challengeId: 'ch-9', expiresAt: '2026-12-01T00:02:00Z' }),
  });
  const result = await login(g, memoryVault(STORED).vault);
  expect(result.body).toEqual({ status: 'mfa_required', challenge_id: 'ch-9', expires_at: '2026-12-01T00:02:00Z' });
  expect(result.cookie).toContain('Max-Age=120');
});

test('login without stored credentials is 409 recgov_not_configured, and without a relay is companion_unavailable', async () => {
  expect(await login(gateway(), memoryVault(null).vault)).toMatchObject({ status: 409, body: { error: 'recgov_not_configured' } });
  const unconfigured = await login(unconfiguredBookingGateway, memoryVault(STORED).vault);
  expect(unconfigured.body).toEqual({ status: 'failed', error: 'companion_unavailable' });
  expect(unconfigured.cookie).toBeUndefined();
});

test('mfa completes the challenge the cookie names, and clears it', async () => {
  const g = gateway({ submitMfa: vi.fn().mockResolvedValue({ kind: 'signed_in' }) });
  expect(await submit({ code: '123456' }, live(), g, memoryVault(STORED).vault)).toEqual({
    status: 200,
    body: { status: 'ok' },
    cookie: mfaClearCookie(),
  });
  expect(g.submitMfa).toHaveBeenCalledWith(7n, 'ch-9', '123456');
});

test('mfa with no, expired or foreign cookie fails and clears it, without calling mini-ca', async () => {
  const g = gateway();
  const { vault } = memoryVault(STORED);
  expect(await submit({ code: '1' }, undefined, g, vault)).toMatchObject({ body: { error: 'mfa_challenge_unknown' }, cookie: mfaClearCookie() });
  expect(await submit({ code: '1' }, live(7n, NOW), g, vault)).toMatchObject({ body: { error: 'mfa_challenge_expired' }, cookie: mfaClearCookie() });
  expect(await submit({ code: '1' }, live(8n), g, vault)).toMatchObject({ body: { error: 'mfa_challenge_unknown' }, cookie: mfaClearCookie() });
  expect(g.submitMfa).not.toHaveBeenCalled();
});

test.each([['mfa_challenge_unknown'], ['mfa_challenge_expired']])(
  'a relay refusal with a challenge code and no detail answers the code alone and clears the cookie (%s)',
  async (code) => {
    const g = gateway({ submitMfa: vi.fn().mockResolvedValue({ kind: 'failed', refusal: { category: 'caller_action', code } }) });
    expect(await submit({ code: '1' }, live(), g, memoryVault(STORED).vault)).toEqual({
      status: 200,
      body: { status: 'failed', error: code },
      cookie: mfaClearCookie(),
    });
  },
);

const RELAY_DETAIL = 'from the relay';

test.each([['mfa_challenge_unknown'], ['mfa_challenge_expired']])(
  'a relay refusal with a challenge code and a detail passes the detail through (%s)',
  async (code) => {
    const g = gateway({
      submitMfa: vi.fn().mockResolvedValue({ kind: 'failed', refusal: { category: 'caller_action', code, detail: RELAY_DETAIL } }),
    });
    expect(await submit({ code: '1' }, live(), g, memoryVault(STORED).vault)).toEqual({
      status: 200,
      body: { status: 'failed', error: code, detail: RELAY_DETAIL },
      cookie: mfaClearCookie(),
    });
  },
);

test('mfa without a code is invalid_body', async () => {
  expect(await submit({}, undefined, gateway(), memoryVault(STORED).vault)).toMatchObject({ status: 400, body: { error: 'invalid_body' } });
});

test('verify answers the dry run', async () => {
  const g = gateway({
    verify: vi.fn().mockResolvedValue({ kind: 'failed', refusal: { category: 'caller_action', code: 'recgov_not_authenticated', detail: 'no session' } }),
  });
  expect(await verify(g, memoryVault(STORED).vault)).toEqual({
    status: 200,
    body: { ok: false, error: 'recgov_not_authenticated', detail: 'no session' },
  });
});

test('status without stored credentials never calls mini-ca', async () => {
  const g = gateway();
  expect(await status(undefined, g, memoryVault(null).vault)).toEqual({
    status: 200,
    body: { configured: false, session: 'not_configured', mfa_pending: false },
  });
  expect(g.health).not.toHaveBeenCalled();
});

test.each([
  [{ kind: 'active' }, { session: 'active' }],
  [{ kind: 'never_logged_in' }, { session: 'not_logged_in' }],
  [{ kind: 'inactive' }, { session: 'expired', detail: 'recgov_session_lapsed' }],
  [{ kind: 'check_failed', code: 'x' }, { session: 'check_failed', detail: 'x' }],
  [{ kind: 'unavailable' }, { session: 'companion_unavailable' }],
])('status maps %j to %j', async (health, expected) => {
  const g = gateway({ health: vi.fn().mockResolvedValue(health) });
  const answered = await status(undefined, g, memoryVault(STORED).vault);
  expect(answered.body).toEqual({ configured: true, username: 'rec@example.com', mfa_pending: false, ...expected });
});

test('status reports a pending challenge from a live cookie', async () => {
  const g = gateway({ health: vi.fn().mockResolvedValue({ kind: 'never_logged_in' }) });
  expect((await status(live(), g, memoryVault(STORED).vault)).body).toMatchObject({ mfa_pending: true });
});

test.each([
  ['the profile is busy', { kind: 'failed', refusal: { category: 'retry_later', code: 'profile_busy' } }],
  ['mini-ca is unreachable', UNREACHABLE],
  ['the relay timed out', { kind: 'timed_out', refusal: { category: 'retry_later', code: 'relay_timeout' } }],
])('an mfa submit that fails because %s keeps the challenge cookie for a retry', async (_label, result) => {
  const g = gateway({ submitMfa: vi.fn().mockResolvedValue(result) });
  const answered = await submit({ code: '1' }, live(), g, memoryVault(STORED).vault);
  expect(answered.cookie).toBeUndefined();
});

test('a rejected code ends the challenge and clears the cookie', async () => {
  const g = gateway({ submitMfa: vi.fn().mockResolvedValue({ kind: 'failed', refusal: { category: 'caller_action', code: 'mfa_invalid' } }) });
  expect((await submit({ code: '1' }, live(), g, memoryVault(STORED).vault)).cookie).toBe(mfaClearCookie());
});

test('a save with a password against a locked vault is 503 and wipes nothing', async () => {
  const g = gateway({ signOut: vi.fn().mockResolvedValue(WIPED) });
  const { vault, row } = memoryVault(STORED, false);
  expect(await save({ username: 'other@example.com', password: 'x' }, g, vault)).toMatchObject({ status: 503, body: { error: 'encryption_unavailable' } });
  expect(g.signOut).not.toHaveBeenCalled();
  expect(row()).toEqual(STORED);
});

test('a rename against a locked vault succeeds and still reads not configured', async () => {
  const g = gateway({ signOut: vi.fn().mockResolvedValue(WIPED) });
  const { vault, row } = memoryVault(STORED, false);
  expect(await save({ username: 'other@example.com' }, g, vault)).toEqual({ status: 200, body: { recgov_configured: false } });
  expect(row()?.username).toBe('other@example.com');
});

test.each([['garbage'], ['2099-01-01T00:00:00Z']])('a relay expires_at that does not parse, or lies past the ceiling, gives Max-Age 300 (%s)', async (expiresAt) => {
  const g = gateway({
    refresh: vi.fn().mockResolvedValue({ kind: 'failed', refusal: { category: 'upstream', code: 'recgov_refresh_failed' } }),
    signIn: vi.fn().mockResolvedValue({ kind: 'mfa_required', challengeId: 'ch-9', expiresAt }),
  });
  expect((await login(g, memoryVault(STORED).vault)).cookie).toContain('Max-Age=300');
});

test('with no key, a cookie opens as none: status says mfa_pending false and a submit answers mfa_challenge_unknown and clears it', async () => {
  const keyless = challengeCodec(null, clock);
  const g = gateway({ health: vi.fn().mockResolvedValue({ kind: 'never_logged_in' }), submitMfa: vi.fn() });
  expect((await status(live(), g, memoryVault(STORED).vault, keyless)).body).toMatchObject({ mfa_pending: false });
  expect(await submit({ code: '1' }, live(), g, memoryVault(STORED).vault, keyless)).toMatchObject({
    body: { error: 'mfa_challenge_unknown' },
    cookie: mfaClearCookie(),
  });
  expect(g.submitMfa).not.toHaveBeenCalled();
});

test('a malformed answer on sign-in is companion_unavailable and keeps the cookie; on sign-out it is recgov_profile_wipe_failed', async () => {
  const malformed = { kind: 'unavailable', fault: 'malformed' };
  const g = gateway({ refresh: vi.fn().mockResolvedValue(malformed), signIn: vi.fn().mockResolvedValue(malformed), signOut: vi.fn().mockResolvedValue(malformed) });
  const signedIn = await login(g, memoryVault(STORED).vault);
  expect(signedIn.body).toEqual({ status: 'failed', error: 'companion_unavailable' });
  expect(signedIn.cookie).toBeUndefined();
  expect(await remove(g, memoryVault(STORED).vault)).toMatchObject({ status: 502, body: { error: 'recgov_profile_wipe_failed' } });
});
