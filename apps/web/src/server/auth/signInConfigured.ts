import type { AuthPort } from '@/server/auth/authPort';
import { authPort } from '@/server/auth/neonAuth';
import { ConfigError, config } from '@/server/config';

const NO_ADMINS: ReadonlySet<string> = new Set();

export interface SignInSetup {
  port: AuthPort;
  adminEmails: ReadonlySet<string>;
}

export function signInSetup(): SignInSetup | null {
  const signIn = config.signIn;
  const port = authPort();
  if (port === null) return null;
  return { port, adminEmails: signIn?.adminEmails ?? NO_ADMINS };
}

export function signInConfigured(): boolean {
  try {
    return signInSetup() !== null;
  } catch (error) {
    if (error instanceof ConfigError) return false;
    throw error;
  }
}
