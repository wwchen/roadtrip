import { decryptSecret, encryptSecret } from '../crypto/secretCipher';

export const MFA_COOKIE = '__Secure-recgov-mfa';
export const MFA_COOKIE_PATH = '/api/settings/recgov/login';
export const MFA_CHALLENGE_UNKNOWN = 'mfa_challenge_unknown';
export const MFA_CHALLENGE_EXPIRED = 'mfa_challenge_expired';
const COOKIE_ENCODING = 'base64url';

export interface MfaChallenge {
  challengeId: string;
  userId: bigint;
  expiresAt: number;
}

interface SealedChallenge {
  c: string;
  u: string;
  e: number;
}

export type OpenedChallenge = { challengeId: string } | { code: typeof MFA_CHALLENGE_UNKNOWN | typeof MFA_CHALLENGE_EXPIRED };

export function sealMfaChallenge(key: Buffer, challenge: MfaChallenge): string {
  const payload: SealedChallenge = { c: challenge.challengeId, u: challenge.userId.toString(), e: challenge.expiresAt };
  return encryptSecret(key, JSON.stringify(payload)).toString(COOKIE_ENCODING);
}

function unsealed(key: Buffer, value: string): SealedChallenge | null {
  try {
    const parsed = JSON.parse(decryptSecret(key, Buffer.from(value, COOKIE_ENCODING))) as Partial<SealedChallenge>;
    if (typeof parsed.c !== 'string' || typeof parsed.u !== 'string' || typeof parsed.e !== 'number') return null;
    return { c: parsed.c, u: parsed.u, e: parsed.e };
  } catch {
    return null;
  }
}

export function openMfaChallenge(key: Buffer, value: string | undefined, userId: bigint, now: number): OpenedChallenge {
  if (value === undefined || value.length === 0) return { code: MFA_CHALLENGE_UNKNOWN };
  const challenge = unsealed(key, value);
  if (challenge === null || challenge.u !== userId.toString()) return { code: MFA_CHALLENGE_UNKNOWN };
  if (now >= challenge.e) return { code: MFA_CHALLENGE_EXPIRED };
  return { challengeId: challenge.c };
}

const COOKIE_SEPARATOR = ';';
const COOKIE_ASSIGN = '=';
const CLEARED_MAX_AGE = 0;

export function mfaCookieFrom(request: Request): string | undefined {
  const header = request.headers.get('cookie');
  if (header === null) return undefined;
  for (const part of header.split(COOKIE_SEPARATOR)) {
    const at = part.indexOf(COOKIE_ASSIGN);
    if (at < 0) continue;
    if (part.slice(0, at).trim() === MFA_COOKIE) return part.slice(at + 1).trim();
  }
  return undefined;
}

export function mfaSetCookie(value: string, maxAgeSeconds: number): string {
  return `${MFA_COOKIE}=${value}; Path=${MFA_COOKIE_PATH}; Max-Age=${maxAgeSeconds}; HttpOnly; Secure; SameSite=Strict`;
}

export function mfaClearCookie(): string {
  return mfaSetCookie('', CLEARED_MAX_AGE);
}
