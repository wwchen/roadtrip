import {
  AUTH_UNAVAILABLE,
  EMAIL_NOT_VERIFIED,
  PASSWORD_TOO_SHORT,
  UNKNOWN_FAILURE,
  USER_ALREADY_EXISTS,
  type AuthPort,
  type AuthResult,
} from './authPort';
import { MAGIC_LINK_SENT, PASSWORD_TOO_SHORT_MESSAGE, RESET_LINK_SENT, RETRY, VERIFY_EMAIL, WRONG_PASSWORD } from './authCopy';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from './returnTo';

export interface FormState {
  message: string | null;
  canResendVerification: boolean;
}

export type ActionOutcome = { kind: 'redirect'; url: string } | { kind: 'message'; state: FormState };

export type AuthAction = (port: AuthPort | null, form: FormData) => Promise<ActionOutcome>;

export const EMAIL_FIELD = 'email';
export const PASSWORD_FIELD = 'password';
export const NAME_FIELD = 'name';
export const TOKEN_FIELD = 'token';
export const SIGN_IN_PATH = '/auth/sign-in';
export const RESET_PATH = '/auth/reset';
export const ERROR_PARAM = 'error';
export const ERROR_RETRY = 'retry';
export const RESET_PARAM = 'reset';
export const RESET_DONE = 'done';

const AUTH_LOG = 'auth:';

const message = (text: string, canResendVerification = false): ActionOutcome => ({
  kind: 'message',
  state: { message: text, canResendVerification },
});

const redirectTo = (url: string): ActionOutcome => ({ kind: 'redirect', url });

const field = (form: FormData, name: string): string => {
  const value = form.get(name);
  return typeof value === 'string' ? value : '';
};

const returnToOf = (form: FormData): string => safeReturnTo(field(form, RETURN_TO_PARAM));

const isUnavailable = (code: string): boolean => code === AUTH_UNAVAILABLE;

function codeOf(error: unknown): string {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === 'string' ? code : UNKNOWN_FAILURE;
}

function logged(code: string): string {
  console.warn(AUTH_LOG, code);
  return code;
}

function guarded(work: (port: AuthPort, form: FormData) => Promise<ActionOutcome>): AuthAction {
  return async (port, form) => {
    if (port === null) return message(RETRY);
    try {
      return await work(port, form);
    } catch (error) {
      logged(codeOf(error));
      return message(RETRY);
    }
  };
}

function failure(result: AuthResult): string | null {
  return result.ok ? null : logged(result.code);
}

export const signInWithPasswordAction = guarded(async (port, form) => {
  const code = failure(await port.signInWithPassword(field(form, EMAIL_FIELD), field(form, PASSWORD_FIELD)));
  if (code === null) return redirectTo(returnToOf(form));
  if (code === EMAIL_NOT_VERIFIED) return message(VERIFY_EMAIL, true);
  return message(isUnavailable(code) ? RETRY : WRONG_PASSWORD);
});

export const signInWithGoogleAction = guarded(async (port, form) => {
  const returnTo = returnToOf(form);
  const errorCallback = `${withReturnTo(SIGN_IN_PATH, returnTo)}&${ERROR_PARAM}=${ERROR_RETRY}`;
  const result = await port.signInWithGoogle(returnTo, errorCallback);
  if (result.ok) return redirectTo(result.url);
  logged(result.code);
  return message(RETRY);
});

export const sendMagicLinkAction = guarded(async (port, form) => {
  const code = failure(await port.sendMagicLink(field(form, EMAIL_FIELD), returnToOf(form)));
  return message(code !== null && isUnavailable(code) ? RETRY : MAGIC_LINK_SENT);
});

export const signUpAction = guarded(async (port, form) => {
  const code = failure(
    await port.signUp(field(form, EMAIL_FIELD), field(form, PASSWORD_FIELD), field(form, NAME_FIELD), returnToOf(form)),
  );
  if (code === null || code === USER_ALREADY_EXISTS) return message(VERIFY_EMAIL);
  if (code === PASSWORD_TOO_SHORT) return message(PASSWORD_TOO_SHORT_MESSAGE);
  return message(RETRY);
});

export const resendVerificationAction = guarded(async (port, form) => {
  const code = failure(await port.resendVerification(field(form, EMAIL_FIELD), returnToOf(form)));
  return message(code !== null && isUnavailable(code) ? RETRY : VERIFY_EMAIL);
});

export const requestPasswordResetAction = guarded(async (port, form) => {
  const code = failure(await port.requestPasswordReset(field(form, EMAIL_FIELD), withReturnTo(RESET_PATH, returnToOf(form))));
  return message(code !== null && isUnavailable(code) ? RETRY : RESET_LINK_SENT);
});

export const resetPasswordAction = guarded(async (port, form) => {
  const code = failure(await port.resetPassword(field(form, TOKEN_FIELD), field(form, PASSWORD_FIELD)));
  if (code !== null) return message(RETRY);
  return redirectTo(`${withReturnTo(SIGN_IN_PATH, returnToOf(form))}&${RESET_PARAM}=${RESET_DONE}`);
});
