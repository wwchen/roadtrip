export interface AuthUser {
  id: string;
  email: string;
  name: string | null;
  emailVerified: boolean;
}

export type AuthResult = { ok: true } | { ok: false; code: string };

export type RedirectResult = { ok: true; url: string } | { ok: false; code: string };

export const EMAIL_NOT_VERIFIED = 'EMAIL_NOT_VERIFIED';

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
