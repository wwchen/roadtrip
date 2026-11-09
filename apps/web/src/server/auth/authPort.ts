export interface AuthUser {
  id: string;
  email: string;
  name: string | null;
  emailVerified: boolean;
}

export type AuthResult = { ok: true } | { ok: false; code: string };

export type RedirectResult = { ok: true; url: string } | { ok: false; code: string };

export const EMAIL_NOT_VERIFIED = 'EMAIL_NOT_VERIFIED';
export const USER_ALREADY_EXISTS = 'USER_ALREADY_EXISTS';
export const PASSWORD_TOO_SHORT = 'PASSWORD_TOO_SHORT';
export const AUTH_UNAVAILABLE = 'AUTH_UNAVAILABLE';
export const UNKNOWN_FAILURE = 'UNKNOWN';

export interface AuthPort {
  currentUser(): Promise<AuthUser | null>;
  signInWithPassword(email: string, password: string): Promise<AuthResult>;
  signInWithGoogle(callbackUrl: string, errorCallbackUrl: string): Promise<RedirectResult>;
  sendMagicLink(email: string, callbackUrl: string): Promise<AuthResult>;
  signUp(email: string, password: string, name: string, callbackUrl: string): Promise<AuthResult>;
  resendVerification(email: string, callbackUrl: string): Promise<AuthResult>;
  requestPasswordReset(email: string, redirectTo: string): Promise<AuthResult>;
  resetPassword(token: string, newPassword: string): Promise<AuthResult>;
  signOut(): Promise<void>;
}
