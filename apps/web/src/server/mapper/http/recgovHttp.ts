import { mfaClearCookie, mfaCookieFrom, mfaSetCookie, openMfaChallenge, sealMfaChallenge } from '../../auth/mfaChallengeCookie';
import type { RecgovLoginResponseDto, RecgovRemovedDto, RecgovStatusDto, RecgovVerifyResponseDto, BookingSettingsDto } from '../../contract';
import type { ActiveUser } from '../../service/auth/model';
import type { Clock } from '../../service/booking/ports';
import {
  MFA_CHALLENGE_EXPIRED,
  MFA_CHALLENGE_UNKNOWN,
  type ChallengeChange,
  type CredentialsCommand,
  type LoginOutcome,
  type MfaChallenge,
  type PendingChallenge,
  type RecgovStatus,
  type RemoveCredentialsOutcome,
  type SaveCredentialsOutcome,
  type VerifyOutcome,
} from '../../service/settings/model';
import {
  errorAnswer,
  INVALID_BODY_DETAIL,
  INVALID_FIELD,
  invalidBody,
  isTextOrAbsent,
  okAnswer,
  STATUS_BAD_GATEWAY,
  STATUS_BAD_REQUEST,
  STATUS_CONFLICT,
  STATUS_SERVICE_UNAVAILABLE,
  type HttpAnswer,
  type Parsed,
  withDetail,
} from './httpAnswer';
import { bookingBody } from './settingsHttp';

const MS_PER_SECOND = 1000;
const MIN_MAX_AGE_SECONDS = 0;
const ENCRYPTION_UNAVAILABLE = 'encryption_unavailable';
const RECGOV_NOT_CONFIGURED = 'recgov_not_configured';
const RECGOV_PROFILE_WIPE_FAILED = 'recgov_profile_wipe_failed';
const COMPANION_UNAVAILABLE = 'companion_unavailable';
const STATUS_FAILED = 'failed';
const STATUS_MFA_REQUIRED = 'mfa_required';
const STATUS_SIGNED_IN = 'ok';
const SESSION_NOT_CONFIGURED = 'not_configured';
const BLANK_USERNAME_DETAIL = 'username must not be blank';
const FIRST_PASSWORD_DETAIL = 'password is required the first time credentials are saved';
const ENCRYPTION_DETAIL = 'Encryption key not configured; cannot store rec.gov credentials';
const NOT_CONFIGURED_DETAIL = 'no rec.gov credentials are stored';
const WIPE_FAILED_DETAIL = 'the rec.gov profile could not be wiped, so nothing was changed';
const NO_CHALLENGE_DETAIL = 'no login is waiting for a code';
const EXPIRED_CHALLENGE_DETAIL = 'the code request expired before the code was entered';
const STRANDED_WATCHES = 0;

export interface ChallengeCodec {
  open(cookie: string | undefined, userId: bigint): PendingChallenge;
  seal(challenge: MfaChallenge): string | null;
}

export function challengeCodec(key: Buffer | null, clock: Clock): ChallengeCodec {
  return {
    open(cookie, userId) {
      if (key === null) return { kind: 'none' };
      const opened = openMfaChallenge(key, cookie, userId, clock.now());
      if ('challengeId' in opened) return { kind: 'open', challengeId: opened.challengeId };
      return opened.code === MFA_CHALLENGE_EXPIRED ? { kind: 'expired' } : { kind: 'none' };
    },
    seal(challenge) {
      if (key === null) return null;
      const maxAge = Math.max(MIN_MAX_AGE_SECONDS, Math.round((challenge.expiresAt - clock.now()) / MS_PER_SECOND));
      return mfaSetCookie(sealMfaChallenge(key, challenge), maxAge);
    },
  };
}

export function pendingChallenge(request: Request | undefined, user: ActiveUser, codec: ChallengeCodec): PendingChallenge {
  return codec.open(request === undefined ? undefined : mfaCookieFrom(request), user.id);
}

export function parseCredentials(body: unknown): Parsed<CredentialsCommand> {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return invalidBody(INVALID_BODY_DETAIL);
  const { username: rawUsername, password: rawPassword } = body as Record<string, unknown>;
  if (!isTextOrAbsent(rawUsername) || !isTextOrAbsent(rawPassword)) return invalidBody(INVALID_BODY_DETAIL);
  const username = typeof rawUsername === 'string' ? rawUsername.trim() : '';
  if (username.length === 0) return { kind: 'invalid', answer: errorAnswer(STATUS_BAD_REQUEST, INVALID_FIELD, BLANK_USERNAME_DETAIL) };
  const password = typeof rawPassword === 'string' && rawPassword.trim().length > 0 ? rawPassword : null;
  return { kind: 'ok', value: { username, password } };
}

export function parseMfaCode(body: unknown): Parsed<string> {
  const code = typeof body === 'object' && body !== null ? (body as { code?: unknown }).code : undefined;
  if (typeof code !== 'string' || code.trim().length === 0) return invalidBody(INVALID_BODY_DETAIL);
  return { kind: 'ok', value: code.trim() };
}

const vaultUnavailable = (): HttpAnswer<never> => errorAnswer(STATUS_SERVICE_UNAVAILABLE, ENCRYPTION_UNAVAILABLE, ENCRYPTION_DETAIL);
const notConfigured = (): HttpAnswer<never> => errorAnswer(STATUS_CONFLICT, RECGOV_NOT_CONFIGURED, NOT_CONFIGURED_DETAIL);
const wipeFailed = (): HttpAnswer<never> => errorAnswer(STATUS_BAD_GATEWAY, RECGOV_PROFILE_WIPE_FAILED, WIPE_FAILED_DETAIL);

export function saveAnswer(outcome: SaveCredentialsOutcome): HttpAnswer<BookingSettingsDto> {
  switch (outcome.kind) {
    case 'saved':
      return okAnswer(bookingBody(outcome.settings));
    case 'password_required':
      return errorAnswer(STATUS_BAD_REQUEST, INVALID_FIELD, FIRST_PASSWORD_DETAIL);
    case 'vault_unavailable':
      return vaultUnavailable();
    case 'wipe_failed':
      return wipeFailed();
  }
}

export function removeAnswer(outcome: RemoveCredentialsOutcome): HttpAnswer<RecgovRemovedDto> {
  switch (outcome.kind) {
    case 'removed':
      return okAnswer({
        removed: outcome.removed,
        stranded_atc_watches: STRANDED_WATCHES,
        companion_signed_out: outcome.signedOut,
        profile_destroyed: outcome.profileDestroyed,
      });
    case 'wipe_failed':
      return wipeFailed();
  }
}

function cookieFor(change: ChallengeChange, codec: ChallengeCodec): string | undefined {
  if (change === 'end') return mfaClearCookie();
  if (change === 'keep') return undefined;
  return codec.seal(change.issue) ?? undefined;
}

export function loginAnswer(outcome: LoginOutcome, codec: ChallengeCodec): HttpAnswer<RecgovLoginResponseDto> {
  switch (outcome.kind) {
    case 'signed_in':
      return okAnswer({ status: STATUS_SIGNED_IN }, cookieFor(outcome.challenge, codec));
    case 'mfa_required':
      return okAnswer(
        {
          status: STATUS_MFA_REQUIRED,
          challenge_id: outcome.challengeId,
          ...(outcome.expiresAt === undefined ? {} : { expires_at: outcome.expiresAt }),
        },
        cookieFor(outcome.challenge, codec),
      );
    case 'failed':
      return okAnswer(
        { status: STATUS_FAILED, error: outcome.code, ...withDetail(outcome.detail) },
        cookieFor(outcome.challenge, codec),
      );
    case 'challenge_gone':
      return outcome.reason === 'expired'
        ? okAnswer({ status: STATUS_FAILED, error: MFA_CHALLENGE_EXPIRED, detail: EXPIRED_CHALLENGE_DETAIL }, mfaClearCookie())
        : okAnswer({ status: STATUS_FAILED, error: MFA_CHALLENGE_UNKNOWN, detail: NO_CHALLENGE_DETAIL }, mfaClearCookie());
    case 'unavailable':
      return okAnswer({ status: STATUS_FAILED, error: COMPANION_UNAVAILABLE }, cookieFor(outcome.challenge, codec));
    case 'vault_unavailable':
      return vaultUnavailable();
    case 'not_configured':
      return notConfigured();
  }
}

export function verifyAnswer(outcome: VerifyOutcome): HttpAnswer<RecgovVerifyResponseDto> {
  switch (outcome.kind) {
    case 'verified':
      return okAnswer({ ok: true });
    case 'failed':
      return okAnswer({ ok: false, error: outcome.code, ...withDetail(outcome.detail) });
    case 'unavailable':
      return okAnswer({ ok: false, error: COMPANION_UNAVAILABLE });
    case 'vault_unavailable':
      return vaultUnavailable();
    case 'not_configured':
      return notConfigured();
  }
}

export function statusAnswer(status: RecgovStatus): HttpAnswer<RecgovStatusDto> {
  if (!status.configured) return okAnswer({ configured: false, session: SESSION_NOT_CONFIGURED, mfa_pending: false });
  return okAnswer({
    configured: true,
    username: status.username,
    session: status.session,
    ...(status.detail === undefined ? {} : { detail: status.detail }),
    mfa_pending: status.mfaPending,
  });
}
