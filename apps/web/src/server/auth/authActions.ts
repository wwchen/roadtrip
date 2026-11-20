import {
  EMAIL_NOT_VERIFIED,
  INVALID_CREDENTIALS,
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
const SIGN_IN_NOT_CONFIGURED = 'sign_in_not_configured';
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

const rawOf = (failed: AuthFailure): string => (failed.cause === undefined ? failed.code : `${failed.code}: ${failed.cause}`);

const failedWith = (failed: AuthFailure): ActionOutcome => message(`${RETRY} (${rawOf(failed)})`);

function thrownFailure(error: unknown): AuthFailure {
  const code = (error as { code?: unknown } | null)?.code;
  const cause = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
  return { ok: false, code: typeof code === 'string' ? code : UNKNOWN_FAILURE, cause };
}

type Failure = (result: AuthResult | RedirectResult) => AuthFailure | null;
type Work = (port: AuthPort, form: FormData, origin: string, failure: Failure) => Promise<ActionOutcome>;

function loggerFor(action: string): (failed: AuthFailure, ...extra: unknown[]) => AuthFailure {
  return (failed, ...extra) => {
    console.warn(AUTH_LOG, action, failed.code, ...(failed.cause === undefined ? [] : [failed.cause]), ...extra);
    return failed;
  };
}

function guarded(action: string, work: Work): AuthAction {
  const logged = loggerFor(action);
  const failure: Failure = (result) => (result.ok ? null : logged(result));
  return async (port, form, origin) => {
    if (port === null) return failedWith({ ok: false, code: SIGN_IN_NOT_CONFIGURED });
    try {
      return await work(port, form, origin, failure);
    } catch (error) {
      return failedWith(logged(thrownFailure(error), error));
    }
  };
}

const sentOrUnknown = (failed: AuthFailure | null): boolean => failed === null || failed.code === USER_NOT_FOUND;

export const signInWithPasswordAction = guarded(ACTION.PASSWORD_SIGN_IN, async (port, form, _origin, failure) => {
  const failed = failure(await port.signInWithPassword(field(form, EMAIL_FIELD), field(form, PASSWORD_FIELD)));
  if (failed === null) return redirectTo(returnToOf(form));
  if (failed.code === EMAIL_NOT_VERIFIED) return message(VERIFY_EMAIL, true);
  if (failed.code === INVALID_CREDENTIALS) return message(WRONG_PASSWORD);
  return failedWith(failed);
});

export const signInWithGoogleAction = guarded(ACTION.GOOGLE_SIGN_IN, async (port, form, origin, failure) => {
  const returnTo = returnToOf(form);
  const errorCallback = `${withReturnTo(SIGN_IN_PATH, returnTo)}&${ERROR_PARAM}=${ERROR_RETRY}`;
  const result = await port.signInWithGoogle(absolute(origin, returnTo), absolute(origin, errorCallback));
  if (result.ok) return redirectTo(result.url);
  return failedWith(failure(result) ?? result);
});

export const sendMagicLinkAction = guarded(ACTION.MAGIC_LINK, async (port, form, origin, failure) => {
  const failed = failure(await port.sendMagicLink(field(form, EMAIL_FIELD), absolute(origin, returnToOf(form))));
  return failed === null || sentOrUnknown(failed) ? message(MAGIC_LINK_SENT) : failedWith(failed);
});

export const signUpAction = guarded(ACTION.SIGN_UP, async (port, form, origin, failure) => {
  const failed = failure(
    await port.signUp(
      field(form, EMAIL_FIELD),
      field(form, PASSWORD_FIELD),
      field(form, NAME_FIELD),
      absolute(origin, returnToOf(form)),
    ),
  );
  if (failed === null || failed.code === USER_ALREADY_EXISTS) return message(VERIFY_EMAIL);
  if (failed.code === PASSWORD_TOO_SHORT) return message(PASSWORD_TOO_SHORT_MESSAGE);
  return failedWith(failed);
});

export const resendVerificationAction = guarded(ACTION.RESEND_VERIFICATION, async (port, form, origin, failure) => {
  const failed = failure(await port.resendVerification(field(form, EMAIL_FIELD), absolute(origin, returnToOf(form))));
  return failed === null || sentOrUnknown(failed) ? message(VERIFY_EMAIL) : failedWith(failed);
});

export const requestPasswordResetAction = guarded(ACTION.PASSWORD_RESET_REQUEST, async (port, form, origin, failure) => {
  const failed = failure(
    await port.requestPasswordReset(field(form, EMAIL_FIELD), absolute(origin, withReturnTo(RESET_PATH, returnToOf(form)))),
  );
  return failed === null || sentOrUnknown(failed) ? message(RESET_LINK_SENT) : failedWith(failed);
});

export const resetPasswordAction = guarded(ACTION.PASSWORD_RESET, async (port, form, _origin, failure) => {
  const failed = failure(await port.resetPassword(field(form, TOKEN_FIELD), field(form, PASSWORD_FIELD)));
  if (failed !== null) return failedWith(failed);
  return redirectTo(`${withReturnTo(SIGN_IN_PATH, returnToOf(form))}&${RESET_PARAM}=${RESET_DONE}`);
});

export function signInNotice(error: string | undefined, reset: string | undefined): string | null {
  if (error === ERROR_LINK) return LINK_OTHER_BROWSER;
  if (error === ERROR_RETRY) return RETRY;
  if (reset === RESET_DONE) return PASSWORD_CHANGED;
  return null;
}
