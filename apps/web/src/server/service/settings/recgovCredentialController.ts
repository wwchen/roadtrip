import type { ActiveUser } from '../auth/model';
import type { LoginResult, SignOutResult } from '../booking/model';
import {
  MFA_CHALLENGE_EXPIRED,
  MFA_CHALLENGE_UNKNOWN,
  type CredentialsCommand,
  type LoginOutcome,
  type PendingChallenge,
  type RecgovSession,
  type RecgovStatus,
  type RemoveCredentialsOutcome,
  type SaveCredentialsOutcome,
  type VerifyOutcome,
} from './model';
import type { RecgovPorts } from './ports';

const NOT_CONFIGURED_FAULT = 'not_configured';
const SESSION_LAPSED = 'recgov_session_lapsed';
const CHALLENGE_ENDING_CODES: ReadonlySet<string> = new Set([
  'mfa_invalid',
  'login_failed',
  'captcha_required',
  MFA_CHALLENGE_UNKNOWN,
  MFA_CHALLENGE_EXPIRED,
]);

interface Wipe {
  signedOut: boolean;
  profileDestroyed: boolean;
}

async function wipeProfile(userId: bigint, ports: RecgovPorts): Promise<Wipe | null> {
  const result: SignOutResult = await ports.gateway.signOut(userId);
  if (result.kind === 'wiped') return { signedOut: result.signedOut, profileDestroyed: true };
  if (result.kind === 'unavailable' && result.fault === NOT_CONFIGURED_FAULT) return { signedOut: false, profileDestroyed: false };
  return null;
}

export async function saveCredentials(user: ActiveUser, command: CredentialsCommand, ports: RecgovPorts): Promise<SaveCredentialsOutcome> {
  const before = await ports.vault.status(user.id);
  if (before.kind === 'none' && command.password === null) return { kind: 'password_required' };
  if (before.kind === 'locked' && command.password !== null) return { kind: 'vault_unavailable' };
  if (before.kind !== 'none' && before.username !== command.username && (await wipeProfile(user.id, ports)) === null) return { kind: 'wipe_failed' };
  if (command.password !== null) {
    if ((await ports.vault.store(user.id, command.username, command.password)) === 'unavailable') return { kind: 'vault_unavailable' };
  } else {
    await ports.vault.rename(user.id, command.username);
  }
  const after = await ports.vault.status(user.id);
  return { kind: 'saved', settings: after.kind === 'stored' ? { configured: true, username: after.username } : { configured: false } };
}

export async function removeCredentials(user: ActiveUser, ports: RecgovPorts): Promise<RemoveCredentialsOutcome> {
  const wipe = await wipeProfile(user.id, ports);
  if (wipe === null) return { kind: 'wipe_failed' };
  const removed = await ports.vault.remove(user.id);
  return { kind: 'removed', removed, signedOut: wipe.signedOut, profileDestroyed: wipe.profileDestroyed };
}

async function openedCredentials(userId: bigint, ports: RecgovPorts) {
  const opened = await ports.vault.open(userId);
  if (opened.kind === 'unavailable') return { kind: 'vault_unavailable' } as const;
  if (opened.kind === 'none') return { kind: 'not_configured' } as const;
  return opened;
}

function issuedExpiry(reported: string | undefined, ports: RecgovPorts): number {
  const ceiling = ports.clock.now() + ports.mfaChallengeTtlMs;
  const parsed = reported === undefined ? Number.NaN : Date.parse(reported);
  return Number.isFinite(parsed) ? Math.min(parsed, ceiling) : ceiling;
}

function withDetail(detail: string | undefined): { detail?: string } {
  return detail === undefined ? {} : { detail };
}

function loginOutcome(result: LoginResult, userId: bigint, ports: RecgovPorts): LoginOutcome {
  switch (result.kind) {
    case 'signed_in':
      return { kind: 'signed_in', challenge: 'end' };
    case 'mfa_required':
      return {
        kind: 'mfa_required',
        challengeId: result.challengeId,
        ...(result.expiresAt === undefined ? {} : { expiresAt: result.expiresAt }),
        challenge: { issue: { challengeId: result.challengeId, userId, expiresAt: issuedExpiry(result.expiresAt, ports) } },
      };
    case 'failed':
      return {
        kind: 'failed',
        code: result.refusal.code,
        ...withDetail(result.refusal.detail),
        challenge: CHALLENGE_ENDING_CODES.has(result.refusal.code) ? 'end' : 'keep',
      };
    case 'timed_out':
      return {
        kind: 'failed',
        code: result.refusal.code,
        ...withDetail(result.refusal.detail),
        challenge: 'keep',
      };
    case 'unavailable':
      return { kind: 'unavailable', challenge: 'keep' };
  }
}

export async function signIn(user: ActiveUser, ports: RecgovPorts): Promise<LoginOutcome> {
  const credentials = await openedCredentials(user.id, ports);
  if (credentials.kind !== 'opened') return credentials;
  const refreshed = await ports.gateway.refresh(user.id);
  if (refreshed.kind === 'unavailable' && refreshed.fault === NOT_CONFIGURED_FAULT) return { kind: 'unavailable', challenge: 'keep' };
  if (refreshed.kind === 'ok') return { kind: 'signed_in', challenge: 'end' };
  return loginOutcome(await ports.gateway.signIn(user.id, credentials.username, credentials.password), user.id, ports);
}

export async function submitMfa(user: ActiveUser, code: string, pending: PendingChallenge, ports: RecgovPorts): Promise<LoginOutcome> {
  if (pending.kind === 'none') return { kind: 'challenge_gone', reason: 'none' };
  if (pending.kind === 'expired') return { kind: 'challenge_gone', reason: 'expired' };
  const result = await ports.gateway.submitMfa(user.id, pending.challengeId, code);
  if (result.kind === 'unavailable' && result.fault === NOT_CONFIGURED_FAULT) return { kind: 'unavailable', challenge: 'end' };
  return loginOutcome(result, user.id, ports);
}

export async function verifySession(user: ActiveUser, ports: RecgovPorts): Promise<VerifyOutcome> {
  const credentials = await openedCredentials(user.id, ports);
  if (credentials.kind !== 'opened') return credentials;
  const result = await ports.gateway.verify(user.id);
  switch (result.kind) {
    case 'ok':
      return { kind: 'verified' };
    case 'failed':
    case 'timed_out':
      return { kind: 'failed', code: result.refusal.code, ...withDetail(result.refusal.detail) };
    case 'unavailable':
      return { kind: 'unavailable' };
  }
}

export async function recgovStatus(user: ActiveUser, pending: PendingChallenge, ports: RecgovPorts): Promise<RecgovStatus> {
  const vault = await ports.vault.status(user.id);
  if (vault.kind !== 'stored') return { configured: false };
  const health = await ports.gateway.health(user.id);
  const mfaPending = pending.kind === 'open';
  const stored = { configured: true, username: vault.username, mfaPending } as const;
  const reported = (session: RecgovSession, detail: string | undefined): RecgovStatus =>
    detail === undefined ? { ...stored, session } : { ...stored, session, detail };
  switch (health.kind) {
    case 'active':
      return reported('active', undefined);
    case 'never_logged_in':
      return reported('not_logged_in', undefined);
    case 'inactive':
      return reported('expired', health.code ?? SESSION_LAPSED);
    case 'check_failed':
      return reported('check_failed', health.code ?? health.detail);
    case 'unavailable':
      return reported('companion_unavailable', health.code ?? health.detail);
  }
}
