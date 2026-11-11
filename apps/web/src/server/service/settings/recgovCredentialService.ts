import { MFA_CHALLENGE_EXPIRED, MFA_CHALLENGE_UNKNOWN, openMfaChallenge, sealMfaChallenge } from '@/server/auth/mfaChallengeCookie';
import { profileIdFor } from '@/server/auth/currentUser';
import { appDb } from '@/server/appDb';
import { relay, type BookingRelayPath, type RelayResult } from '@/server/client/bookingRelay';
import { config } from '@/server/config';
import type { BookingRelayResponseDto, BookingSettingsDto, RecgovLoginResponseDto, RecgovStatusDto } from '@/server/contract';
import { decryptSecret, encryptSecret } from '@/server/crypto/secretCipher';
import type { AppUserRow } from '@/server/repo/appUserRepo';
import {
  RECGOV_PROVIDER,
  findCredentials,
  removeCredentials,
  updateCredentialsUsername,
  upsertCredentials,
  type StoredCredentials,
} from '@/server/repo/bookingCredentialsRepo';
import { INVALID_BODY, INVALID_FIELD } from './settingsService';

const STATUS_OK = 200;
const STATUS_BAD_REQUEST = 400;
const STATUS_CONFLICT = 409;
const STATUS_BAD_GATEWAY = 502;
const STATUS_SERVICE_UNAVAILABLE = 503;
const MS_PER_SECOND = 1000;
const DEFAULT_MFA_CHALLENGE_TTL_MS = 300_000;
const ENCRYPTION_UNAVAILABLE = 'encryption_unavailable';
const RECGOV_NOT_CONFIGURED = 'recgov_not_configured';
const RECGOV_PROFILE_WIPE_FAILED = 'recgov_profile_wipe_failed';
const COMPANION_UNAVAILABLE = 'companion_unavailable';
const MFA_REQUIRED = 'mfa_required';
const SESSION_LAPSED = 'recgov_session_lapsed';
const INVALID_BODY_DETAIL = 'Request body is not valid JSON for this endpoint';
const BLANK_USERNAME_DETAIL = 'username must not be blank';
const FIRST_PASSWORD_DETAIL = 'password is required the first time credentials are saved';
const ENCRYPTION_DETAIL = 'Encryption key not configured; cannot store rec.gov credentials';
const NOT_CONFIGURED_DETAIL = 'no rec.gov credentials are stored';
const WIPE_FAILED_DETAIL = 'the rec.gov profile could not be wiped, so nothing was changed';
const NO_CHALLENGE_DETAIL = 'no login is waiting for a code';
const EXPIRED_CHALLENGE_DETAIL = 'the code request expired before the code was entered';
const CHALLENGE_ENDING_CODES: ReadonlySet<string> = new Set([
  'mfa_invalid',
  'login_failed',
  'captcha_required',
  MFA_CHALLENGE_UNKNOWN,
  MFA_CHALLENGE_EXPIRED,
]);
const SESSION_BY_HEALTH: Readonly<Record<string, RecgovStatusDto['session']>> = {
  active: 'active',
  never_logged_in: 'not_logged_in',
  inactive: 'expired',
  check_failed: 'check_failed',
  unavailable: 'companion_unavailable',
};

export type CookieChange = 'clear' | { value: string; maxAgeSeconds: number };

export interface ServiceAnswer<T> {
  status: number;
  body: T | { error: string; detail?: string };
  cookie?: CookieChange;
}

export interface RecgovDeps {
  cipherKey: Buffer | null;
  mfaChallengeTtlMs: number;
  now: () => number;
  relay: ((path: BookingRelayPath, body: object) => Promise<RelayResult>) | null;
  find: (userId: bigint) => Promise<StoredCredentials | null>;
  upsert: (userId: bigint, username: string, secretCipher: Buffer) => Promise<void>;
  updateUsername: (userId: bigint, username: string) => Promise<void>;
  removeRow: (userId: bigint) => Promise<boolean>;
}

const failure = (status: number, error: string, detail?: string): ServiceAnswer<never> => ({
  status,
  body: detail === undefined ? { error } : { error, detail },
});

const ok = <T>(body: T, cookie?: CookieChange): ServiceAnswer<T> => (cookie === undefined ? { status: STATUS_OK, body } : { status: STATUS_OK, body, cookie });

function bookingSettings(key: Buffer | null, stored: StoredCredentials | null): BookingSettingsDto {
  return key !== null && stored !== null ? { recgov_configured: true, recgov_username: stored.username } : { recgov_configured: false };
}

function envelopeOf(result: RelayResult): BookingRelayResponseDto | null {
  return result.ok ? result.answer : null;
}

async function wiped(user: AppUserRow, deps: RecgovDeps): Promise<{ signedOut: boolean; destroyed: boolean } | null> {
  if (deps.relay === null) return { signedOut: false, destroyed: false };
  const envelope = envelopeOf(await deps.relay('signout', { profile_id: profileIdFor(user.id) }));
  if (envelope?.profile_destroyed !== true) return null;
  return { signedOut: envelope.signed_out === true, destroyed: true };
}

export async function save(user: AppUserRow, body: unknown, deps: RecgovDeps): Promise<ServiceAnswer<BookingSettingsDto>> {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return failure(STATUS_BAD_REQUEST, INVALID_BODY, INVALID_BODY_DETAIL);
  const { username: rawUsername, password: rawPassword } = body as Record<string, unknown>;
  if ((rawUsername != null && typeof rawUsername !== 'string') || (rawPassword != null && typeof rawPassword !== 'string')) {
    return failure(STATUS_BAD_REQUEST, INVALID_BODY, INVALID_BODY_DETAIL);
  }
  const username = typeof rawUsername === 'string' ? rawUsername.trim() : '';
  if (username.length === 0) return failure(STATUS_BAD_REQUEST, INVALID_FIELD, BLANK_USERNAME_DETAIL);
  const stored = await deps.find(user.id);
  const password = typeof rawPassword === 'string' && rawPassword.trim().length > 0 ? rawPassword : null;
  if (password === null && stored === null) return failure(STATUS_BAD_REQUEST, INVALID_FIELD, FIRST_PASSWORD_DETAIL);
  if (password !== null && deps.cipherKey === null) return failure(STATUS_SERVICE_UNAVAILABLE, ENCRYPTION_UNAVAILABLE, ENCRYPTION_DETAIL);
  if (stored !== null && stored.username !== username && (await wiped(user, deps)) === null) {
    return failure(STATUS_BAD_GATEWAY, RECGOV_PROFILE_WIPE_FAILED, WIPE_FAILED_DETAIL);
  }
  if (password !== null && deps.cipherKey !== null) {
    await deps.upsert(user.id, username, encryptSecret(deps.cipherKey, password));
  } else {
    await deps.updateUsername(user.id, username);
  }
  return ok(bookingSettings(deps.cipherKey, await deps.find(user.id)));
}

export async function remove(user: AppUserRow, deps: RecgovDeps): Promise<ServiceAnswer<object>> {
  const wipe = await wiped(user, deps);
  if (wipe === null) return failure(STATUS_BAD_GATEWAY, RECGOV_PROFILE_WIPE_FAILED, WIPE_FAILED_DETAIL);
  const removed = await deps.removeRow(user.id);
  return ok({ removed, stranded_atc_watches: 0, companion_signed_out: wipe.signedOut, profile_destroyed: wipe.destroyed });
}

async function requireCredentials(user: AppUserRow, deps: RecgovDeps): Promise<{ username: string; password: string } | ServiceAnswer<never>> {
  if (deps.cipherKey === null) return failure(STATUS_SERVICE_UNAVAILABLE, ENCRYPTION_UNAVAILABLE, ENCRYPTION_DETAIL);
  const stored = await deps.find(user.id);
  if (stored === null) return failure(STATUS_CONFLICT, RECGOV_NOT_CONFIGURED, NOT_CONFIGURED_DETAIL);
  return { username: stored.username, password: decryptSecret(deps.cipherKey, stored.secretCipher) };
}

function challengeCookie(user: AppUserRow, envelope: BookingRelayResponseDto, deps: RecgovDeps): CookieChange | undefined {
  if (deps.cipherKey === null || envelope.challenge_id === undefined) return undefined;
  const ceiling = deps.now() + deps.mfaChallengeTtlMs;
  const reported = envelope.expires_at === undefined ? Number.NaN : Date.parse(envelope.expires_at);
  const expiresAt = Number.isFinite(reported) ? Math.min(reported, ceiling) : ceiling;
  return {
    value: sealMfaChallenge(deps.cipherKey, { challengeId: envelope.challenge_id, userId: user.id, expiresAt }),
    maxAgeSeconds: Math.max(0, Math.round((expiresAt - deps.now()) / MS_PER_SECOND)),
  };
}

function loginAnswer(user: AppUserRow, result: RelayResult, deps: RecgovDeps): ServiceAnswer<RecgovLoginResponseDto> {
  const envelope = envelopeOf(result);
  if (envelope === null) return ok({ status: 'failed', error: COMPANION_UNAVAILABLE });
  if (envelope.ok) return ok({ status: 'ok' }, 'clear');
  if (envelope.code === MFA_REQUIRED) {
    const body: RecgovLoginResponseDto = {
      status: 'mfa_required',
      ...(envelope.challenge_id === undefined ? {} : { challenge_id: envelope.challenge_id }),
      ...(envelope.expires_at === undefined ? {} : { expires_at: envelope.expires_at }),
    };
    return ok(body, challengeCookie(user, envelope, deps));
  }
  const code = envelope.code ?? COMPANION_UNAVAILABLE;
  const body: RecgovLoginResponseDto = { status: 'failed', error: code, ...(envelope.detail === undefined ? {} : { detail: envelope.detail }) };
  return ok(body, CHALLENGE_ENDING_CODES.has(code) ? 'clear' : undefined);
}

export async function login(user: AppUserRow, deps: RecgovDeps): Promise<ServiceAnswer<RecgovLoginResponseDto>> {
  const credentials = await requireCredentials(user, deps);
  if ('status' in credentials) return credentials;
  if (deps.relay === null) return ok({ status: 'failed', error: COMPANION_UNAVAILABLE });
  const profileId = profileIdFor(user.id);
  const refreshed = envelopeOf(await deps.relay('refresh', { profile_id: profileId }));
  if (refreshed?.ok === true) return ok({ status: 'ok' }, 'clear');
  return loginAnswer(user, await deps.relay('login', { profile_id: profileId, ...credentials }), deps);
}

export async function completeMfa(
  user: AppUserRow,
  body: unknown,
  cookie: string | undefined,
  deps: RecgovDeps,
): Promise<ServiceAnswer<RecgovLoginResponseDto>> {
  const code = typeof body === 'object' && body !== null ? (body as { code?: unknown }).code : undefined;
  if (typeof code !== 'string' || code.trim().length === 0) return failure(STATUS_BAD_REQUEST, INVALID_BODY, INVALID_BODY_DETAIL);
  const opened = deps.cipherKey === null ? { code: MFA_CHALLENGE_UNKNOWN } : openMfaChallenge(deps.cipherKey, cookie, user.id, deps.now());
  if ('code' in opened) {
    const detail = opened.code === MFA_CHALLENGE_EXPIRED ? EXPIRED_CHALLENGE_DETAIL : NO_CHALLENGE_DETAIL;
    return ok({ status: 'failed', error: opened.code, detail }, 'clear');
  }
  if (deps.relay === null) return ok({ status: 'failed', error: COMPANION_UNAVAILABLE }, 'clear');
  return loginAnswer(
    user,
    await deps.relay('login/mfa', { profile_id: profileIdFor(user.id), challenge_id: opened.challengeId, mfa_code: code.trim() }),
    deps,
  );
}

export async function verify(user: AppUserRow, deps: RecgovDeps): Promise<ServiceAnswer<object>> {
  const credentials = await requireCredentials(user, deps);
  if ('status' in credentials) return credentials;
  if (deps.relay === null) return ok({ ok: false, error: COMPANION_UNAVAILABLE });
  const envelope = envelopeOf(await deps.relay('verify', { profile_id: profileIdFor(user.id) }));
  if (envelope === null) return ok({ ok: false, error: COMPANION_UNAVAILABLE });
  if (envelope.ok) return ok({ ok: true });
  return ok({
    ok: false,
    error: envelope.code ?? COMPANION_UNAVAILABLE,
    ...(envelope.detail === undefined ? {} : { detail: envelope.detail }),
  });
}

export async function status(user: AppUserRow, cookie: string | undefined, deps: RecgovDeps): Promise<ServiceAnswer<RecgovStatusDto>> {
  const settings = bookingSettings(deps.cipherKey, await deps.find(user.id));
  if (!settings.recgov_configured) return ok({ configured: false, session: 'not_configured', mfa_pending: false });
  const mfaPending = deps.cipherKey !== null && 'challengeId' in openMfaChallenge(deps.cipherKey, cookie, user.id, deps.now());
  const envelope = deps.relay === null ? null : envelopeOf(await deps.relay('health', { profile_id: profileIdFor(user.id) }));
  const session = envelope?.health === undefined ? 'companion_unavailable' : (SESSION_BY_HEALTH[envelope.health] ?? 'companion_unavailable');
  const detail =
    session === 'expired' ? (envelope?.code ?? SESSION_LAPSED) : session === 'check_failed' || session === 'companion_unavailable' ? (envelope?.code ?? envelope?.detail) : undefined;
  return ok({
    configured: true,
    ...(settings.recgov_username === undefined ? {} : { username: settings.recgov_username }),
    session,
    ...(detail == null ? {} : { detail }),
    mfa_pending: mfaPending,
  });
}

export function liveRecgovDeps(): RecgovDeps {
  const db = appDb();
  const booking = config.booking;
  return {
    cipherKey: config.bookingCipherKey,
    mfaChallengeTtlMs: booking?.mfaChallengeTtlMs ?? DEFAULT_MFA_CHALLENGE_TTL_MS,
    now: Date.now,
    relay: booking === null ? null : (path, body) => relay(booking, path, body),
    find: (userId) => findCredentials(db, userId, RECGOV_PROVIDER),
    upsert: (userId, username, secretCipher) => upsertCredentials(db, userId, RECGOV_PROVIDER, username, secretCipher),
    updateUsername: (userId, username) => updateCredentialsUsername(db, userId, RECGOV_PROVIDER, username),
    removeRow: (userId) => removeCredentials(db, userId, RECGOV_PROVIDER),
  };
}
