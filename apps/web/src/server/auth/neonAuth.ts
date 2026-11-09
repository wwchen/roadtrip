import 'server-only';
import { createNeonAuth } from '@neon/auth/next/server';
import { config } from '@/server/config';
import {
  AUTH_UNAVAILABLE,
  EMAIL_NOT_VERIFIED,
  PASSWORD_TOO_SHORT,
  UNKNOWN_FAILURE,
  USER_ALREADY_EXISTS,
  USER_NOT_FOUND,
  type AuthPort,
  type AuthResult,
  type AuthUser,
  type RedirectResult,
} from './authPort';

type NeonAuth = ReturnType<typeof createNeonAuth>;

export interface SdkError {
  code?: string;
  status?: number;
}

interface ApiOutcome {
  data?: unknown;
  error?: SdkError | null;
}

interface MagicLinkSignIn {
  magicLink(body: { email: string; callbackURL: string }): Promise<ApiOutcome>;
}

const GOOGLE_PROVIDER = 'google';
const SAME_SITE_LAX = 'lax';
const NETWORK_CODE_PREFIX = 'NETWORK_';
const MISSING_REDIRECT = 'MISSING_REDIRECT_URL';
const FIRST_SERVER_ERROR_STATUS = 500;
const SDK_CODES: Readonly<Record<string, string>> = {
  email_not_confirmed: EMAIL_NOT_VERIFIED,
  user_already_exists: USER_ALREADY_EXISTS,
  email_exists: USER_ALREADY_EXISTS,
  user_not_found: USER_NOT_FOUND,
  weak_password: PASSWORD_TOO_SHORT,
  internal_error: AUTH_UNAVAILABLE,
  unexpected_failure: AUTH_UNAVAILABLE,
};

export function portCodeOf(error: SdkError): string {
  const code = error.code;
  if (code?.startsWith(NETWORK_CODE_PREFIX)) return AUTH_UNAVAILABLE;
  if (error.status !== undefined && error.status >= FIRST_SERVER_ERROR_STATUS) return AUTH_UNAVAILABLE;
  if (code === undefined) return UNKNOWN_FAILURE;
  return SDK_CODES[code] ?? code;
}

let cached: NeonAuth | null | undefined;

export function neonAuth(): NeonAuth | null {
  if (cached !== undefined) return cached;
  const signIn = config.signIn;
  cached =
    signIn === null
      ? null
      : createNeonAuth({
          baseUrl: signIn.neonAuthBaseUrl.href,
          cookies: { secret: signIn.cookieSecret, sameSite: SAME_SITE_LAX },
        });
  return cached;
}

function resultOf(outcome: ApiOutcome): AuthResult {
  if (outcome.error) return { ok: false, code: portCodeOf(outcome.error) };
  return { ok: true };
}

class AuthUnreachableError extends Error {
  constructor(readonly code: string) {
    super(`Neon Auth unreachable: ${code}`);
    this.name = 'AuthUnreachableError';
  }
}

function adapterOver(auth: NeonAuth): AuthPort {
  return {
    async currentUser(): Promise<AuthUser | null> {
      const { data, error } = (await auth.getSession()) as ApiOutcome & {
        data?: { user?: { id: string; email: string; name?: string | null; emailVerified?: boolean } | null } | null;
      };
      if (error && portCodeOf(error) === AUTH_UNAVAILABLE) throw new AuthUnreachableError(error.code ?? UNKNOWN_FAILURE);
      const user = data?.user;
      if (!user) return null;
      return { id: user.id, email: user.email, name: user.name ?? null, emailVerified: user.emailVerified ?? false };
    },
    async signInWithPassword(email, password) {
      return resultOf(await auth.signIn.email({ email, password }));
    },
    async signInWithGoogle(callbackUrl, errorCallbackUrl): Promise<RedirectResult> {
      const outcome = (await auth.signIn.social({
        provider: GOOGLE_PROVIDER,
        callbackURL: callbackUrl,
        errorCallbackURL: errorCallbackUrl,
      })) as ApiOutcome & { data?: { url?: string } | null };
      const failed = resultOf(outcome);
      if (!failed.ok) return failed;
      const url = outcome.data?.url;
      return url ? { ok: true, url } : { ok: false, code: MISSING_REDIRECT };
    },
    async sendMagicLink(email, callbackUrl) {
      return resultOf(await (auth.signIn as unknown as MagicLinkSignIn).magicLink({ email, callbackURL: callbackUrl }));
    },
    async signUp(email, password, name, callbackUrl) {
      return resultOf(await auth.signUp.email({ email, password, name, callbackURL: callbackUrl }));
    },
    async resendVerification(email, callbackUrl) {
      return resultOf(await auth.sendVerificationEmail({ email, callbackURL: callbackUrl }));
    },
    async requestPasswordReset(email, redirectTo) {
      return resultOf(await auth.requestPasswordReset({ email, redirectTo }));
    },
    async resetPassword(token, newPassword) {
      return resultOf(await auth.resetPassword({ token, newPassword }));
    },
    async signOut() {
      return resultOf(await auth.signOut());
    },
  };
}

export function authPort(): AuthPort | null {
  const auth = neonAuth();
  return auth === null ? null : adapterOver(auth);
}
