import 'server-only';
import { createNeonAuth } from '@neon/auth/next/server';
import { config } from '@/server/config';
import type { AuthPort, AuthResult, AuthUser, RedirectResult } from './authPort';

type NeonAuth = ReturnType<typeof createNeonAuth>;

interface ApiOutcome {
  data?: unknown;
  error?: { code?: string } | null;
}

interface MagicLinkSignIn {
  magicLink(body: { email: string; callbackURL: string }): Promise<ApiOutcome>;
}

const GOOGLE_PROVIDER = 'google';
const SAME_SITE_LAX = 'lax';
const NETWORK_CODE_PREFIX = 'NETWORK_';
const MISSING_REDIRECT = 'MISSING_REDIRECT_URL';
const UNKNOWN_FAILURE = 'UNKNOWN';

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
  if (outcome.error) return { ok: false, code: outcome.error.code ?? UNKNOWN_FAILURE };
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
      if (error?.code?.startsWith(NETWORK_CODE_PREFIX)) throw new AuthUnreachableError(error.code);
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
      await auth.signOut();
    },
  };
}

export function authPort(): AuthPort | null {
  const auth = neonAuth();
  return auth === null ? null : adapterOver(auth);
}
