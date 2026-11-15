import {
  AUTH_UNAVAILABLE,
  EMAIL_NOT_VERIFIED,
  PASSWORD_TOO_SHORT,
  UNKNOWN_FAILURE,
  USER_ALREADY_EXISTS,
  USER_NOT_FOUND,
  type AuthFailure,
  type AuthPort,
  type AuthResult,
  type RedirectResult,
} from './authPort';
import {
  LINK_OTHER_BROWSER,
  MAGIC_LINK_SENT,
  PASSWORD_CHANGED,
  PASSWORD_TOO_SHORT_MESSAGE,
  RESET_LINK_SENT,
  RETRY,
  VERIFY_EMAIL,
  WRONG_PASSWORD,
} from './authCopy';
import { RESET_PATH, SIGN_IN_PATH } from './authPaths';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from './returnTo';

export interface FormState {
  message: string | null;
  canResendVerification: boolean;
}

export type ActionOutcome = { kind: 'redirect'; url: string } | { kind: 'message'; state: FormState };

export type AuthAction = (port: AuthPort | null, form: FormData, origin: string) => Promise<ActionOutcome>;

export const EMAIL_FIELD = 'email';
export const PASSWORD_FIELD = 'password';
export const NAME_FIELD = 'name';
export const TOKEN_FIELD = 'token';
export const ERROR_PARAM = 'error';
export const ERROR_RETRY = 'retry';
export const ERROR_LINK = 'link';
export const RESET_PARAM = 'reset';
export const RESET_DONE = 'done';

const AUTH_LOG = 'auth:';
const ACTION = {
  PASSWORD_SIGN_IN: 'password_sign_in',
  GOOGLE_SIGN_IN: 'google_sign_in',
  MAGIC_LINK: 'magic_link',
  SIGN_UP: 'sign_up',
  RESEND_VERIFICATION: 'resend_verification',
  PASSWORD_RESET_REQUEST: 'password_reset_request',
  PASSWORD_RESET: 'password_reset',
} as const;

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

const absolute = (origin: string, path: string): string => new URL(path, origin).toString();

const isUnavailable = (code: string): boolean => code === AUTH_UNAVAILABLE;

function codeOf(error: unknown): string {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === 'string' ? code : UNKNOWN_FAILURE;
}

type Failure = (result: AuthResult | RedirectResult) => string | null;
type Work = (port: AuthPort, form: FormData, origin: string, failure: Failure) => Promise<ActionOutcome>;

function loggerFor(action: string): (failed: AuthFailure) => string {
  return (failed) => {
    console.warn(AUTH_LOG, action, failed.code, ...(failed.cause === undefined ? [] : [failed.cause]));
    return failed.code;
  };
}

function guarded(action: string, work: Work): AuthAction {
  const logged = loggerFor(action);
  const failure: Failure = (result) => (result.ok ? null : logged(result));
  return async (port, form, origin) => {
    if (port === null) return message(RETRY);
    try {
      return await work(port, form, origin, failure);
    } catch (error) {
      logged({ ok: false, code: codeOf(error) });
      return message(RETRY);
    }
  };
}

const sentOrUnknown = (code: string | null): boolean => code === null || code === USER_NOT_FOUND;

export const signInWithPasswordAction = guarded(ACTION.PASSWORD_SIGN_IN, async (port, form, _origin, failure) => {
  const code = failure(await port.signInWithPassword(field(form, EMAIL_FIELD), field(form, PASSWORD_FIELD)));
  if (code === null) return redirectTo(returnToOf(form));
  if (code === EMAIL_NOT_VERIFIED) return message(VERIFY_EMAIL, true);
  return message(isUnavailable(code) ? RETRY : WRONG_PASSWORD);
});

export const signInWithGoogleAction = guarded(ACTION.GOOGLE_SIGN_IN, async (port, form, origin, failure) => {
  const returnTo = returnToOf(form);
  const errorCallback = `${withReturnTo(SIGN_IN_PATH, returnTo)}&${ERROR_PARAM}=${ERROR_RETRY}`;
  const result = await port.signInWithGoogle(absolute(origin, returnTo), absolute(origin, errorCallback));
  if (result.ok) return redirectTo(result.url);
  failure(result);
  return message(RETRY);
});

export const sendMagicLinkAction = guarded(ACTION.MAGIC_LINK, async (port, form, origin, failure) => {
  const code = failure(await port.sendMagicLink(field(form, EMAIL_FIELD), absolute(origin, returnToOf(form))));
  return message(sentOrUnknown(code) ? MAGIC_LINK_SENT : RETRY);
});

export const signUpAction = guarded(ACTION.SIGN_UP, async (port, form, origin, failure) => {
  const code = failure(
    await port.signUp(
      field(form, EMAIL_FIELD),
      field(form, PASSWORD_FIELD),
      field(form, NAME_FIELD),
      absolute(origin, returnToOf(form)),
    ),
  );
  if (code === null || code === USER_ALREADY_EXISTS) return message(VERIFY_EMAIL);
  if (code === PASSWORD_TOO_SHORT) return message(PASSWORD_TOO_SHORT_MESSAGE);
  return message(RETRY);
});

export const resendVerificationAction = guarded(ACTION.RESEND_VERIFICATION, async (port, form, origin, failure) => {
  const code = failure(await port.resendVerification(field(form, EMAIL_FIELD), absolute(origin, returnToOf(form))));
  return message(sentOrUnknown(code) ? VERIFY_EMAIL : RETRY);
});

export const requestPasswordResetAction = guarded(ACTION.PASSWORD_RESET_REQUEST, async (port, form, origin, failure) => {
  const code = failure(
    await port.requestPasswordReset(field(form, EMAIL_FIELD), absolute(origin, withReturnTo(RESET_PATH, returnToOf(form)))),
  );
  return message(sentOrUnknown(code) ? RESET_LINK_SENT : RETRY);
});

export const resetPasswordAction = guarded(ACTION.PASSWORD_RESET, async (port, form, _origin, failure) => {
  const code = failure(await port.resetPassword(field(form, TOKEN_FIELD), field(form, PASSWORD_FIELD)));
  if (code !== null) return message(RETRY);
  return redirectTo(`${withReturnTo(SIGN_IN_PATH, returnToOf(form))}&${RESET_PARAM}=${RESET_DONE}`);
});

export function signInNotice(error: string | undefined, reset: string | undefined): string | null {
  if (error === ERROR_LINK) return LINK_OTHER_BROWSER;
  if (error === ERROR_RETRY) return RETRY;
  if (reset === RESET_DONE) return PASSWORD_CHANGED;
  return null;
}
