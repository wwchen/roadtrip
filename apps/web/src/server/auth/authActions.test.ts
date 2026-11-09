import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { AUTH_UNAVAILABLE, EMAIL_NOT_VERIFIED, PASSWORD_TOO_SHORT, USER_ALREADY_EXISTS, type AuthPort } from './authPort';
import {
  requestPasswordResetAction,
  resendVerificationAction,
  resetPasswordAction,
  sendMagicLinkAction,
  signInWithGoogleAction,
  signInWithPasswordAction,
  signUpAction,
} from './authActions';
import {
  MAGIC_LINK_SENT,
  PASSWORD_TOO_SHORT_MESSAGE,
  RESET_LINK_SENT,
  RETRY,
  VERIFY_EMAIL,
  WRONG_PASSWORD,
} from './authCopy';

const OK = { ok: true } as const;
const ORIGIN = 'https://roadtrip.test';

let warn: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

const form = (fields: Record<string, string>) => {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
};

const port = (overrides: Partial<AuthPort>): AuthPort => overrides as AuthPort;

const credentials = { email: 'will@example.com', password: 'correct horse' };

test('password sign-in redirects to a safe return_to', async () => {
  const signIn = port({ signInWithPassword: vi.fn(async () => OK) });
  expect(await signInWithPasswordAction(signIn, form({ ...credentials, return_to: '/watches' }), ORIGIN)).toEqual({
    kind: 'redirect',
    url: '/watches',
  });
  expect(await signInWithPasswordAction(signIn, form({ ...credentials, return_to: '//evil.example' }), ORIGIN)).toEqual({
    kind: 'redirect',
    url: '/',
  });
});

test('a wrong password shows one message', async () => {
  const signIn = port({ signInWithPassword: vi.fn(async () => ({ ok: false, code: 'INVALID_EMAIL_OR_PASSWORD' }) as const) });
  expect(await signInWithPasswordAction(signIn, form(credentials), ORIGIN)).toEqual({
    kind: 'message',
    state: { message: WRONG_PASSWORD, canResendVerification: false },
  });
});

test('an unverified account is told to verify', async () => {
  const signIn = port({ signInWithPassword: vi.fn(async () => ({ ok: false, code: EMAIL_NOT_VERIFIED }) as const) });
  expect(await signInWithPasswordAction(signIn, form(credentials), ORIGIN)).toEqual({
    kind: 'message',
    state: { message: VERIFY_EMAIL, canResendVerification: true },
  });
});

test('Google redirects to the provider URL with the return path as callback', async () => {
  const signInWithGoogle = vi.fn(async () => ({ ok: true, url: 'https://accounts.google.test/o/oauth2' }) as const);
  const outcome = await signInWithGoogleAction(port({ signInWithGoogle }), form({ return_to: '/watches' }), ORIGIN);
  expect(signInWithGoogle).toHaveBeenCalledWith(
    'https://roadtrip.test/watches',
    'https://roadtrip.test/auth/sign-in?return_to=%2Fwatches&error=retry',
  );
  expect(outcome).toEqual({ kind: 'redirect', url: 'https://accounts.google.test/o/oauth2' });
});

test('resending verification answers the verify message', async () => {
  const resendVerification = vi.fn(async () => OK);
  const outcome = await resendVerificationAction(port({ resendVerification }), form({ email: 'a@x.io', return_to: '/watches' }), ORIGIN);
  expect(resendVerification).toHaveBeenCalledWith('a@x.io', 'https://roadtrip.test/watches');
  expect(outcome).toEqual({ kind: 'message', state: { message: VERIFY_EMAIL, canResendVerification: false } });
});

test('a magic link answers the same whether or not the account exists', async () => {
  for (const result of [OK, { ok: false, code: 'USER_NOT_FOUND' } as const]) {
    const sendMagicLink = vi.fn(async () => result);
    expect(await sendMagicLinkAction(port({ sendMagicLink }), form({ email: 'a@x.io', return_to: '/watches' }), ORIGIN)).toEqual({
      kind: 'message',
      state: { message: MAGIC_LINK_SENT, canResendVerification: false },
    });
    expect(sendMagicLink).toHaveBeenCalledWith('a@x.io', 'https://roadtrip.test/watches');
  }
});

test('forgot answers the same whether or not the account exists', async () => {
  for (const result of [OK, { ok: false, code: 'USER_NOT_FOUND' } as const]) {
    const requestPasswordReset = vi.fn(async () => result);
    const outcome = await requestPasswordResetAction(port({ requestPasswordReset }), form({ email: 'a@x.io', return_to: '/watches' }), ORIGIN);
    expect(outcome).toEqual({ kind: 'message', state: { message: RESET_LINK_SENT, canResendVerification: false } });
    expect(requestPasswordReset).toHaveBeenCalledWith('a@x.io', 'https://roadtrip.test/auth/reset?return_to=%2Fwatches');
  }
});

test('reset with a good token goes to sign-in, keeping the return path', async () => {
  const resetPassword = vi.fn(async () => OK);
  const outcome = await resetPasswordAction(port({ resetPassword }), form({ token: 't-1', password: 'new one!', return_to: '/watches' }), ORIGIN);
  expect(resetPassword).toHaveBeenCalledWith('t-1', 'new one!');
  expect(outcome).toEqual({ kind: 'redirect', url: '/auth/sign-in?return_to=%2Fwatches&reset=done' });
});

test('sign-up asks the user to verify', async () => {
  const signUp = vi.fn(async () => OK);
  const outcome = await signUpAction(port({ signUp }), form({ ...credentials, name: 'Will', return_to: '//evil.example' }), ORIGIN);
  expect(signUp).toHaveBeenCalledWith('will@example.com', 'correct horse', 'Will', 'https://roadtrip.test/');
  expect(outcome).toEqual({ kind: 'message', state: { message: VERIFY_EMAIL, canResendVerification: false } });
});

test('sign-up does not reveal an existing account', async () => {
  const signUp = vi.fn(async () => ({ ok: false, code: USER_ALREADY_EXISTS }) as const);
  const outcome = await signUpAction(port({ signUp }), form({ ...credentials, name: 'Will' }), ORIGIN);
  expect(outcome).toEqual({ kind: 'message', state: { message: VERIFY_EMAIL, canResendVerification: false } });
});

test('sign-up with a short password says so', async () => {
  const signUp = vi.fn(async () => ({ ok: false, code: PASSWORD_TOO_SHORT }) as const);
  const outcome = await signUpAction(port({ signUp }), form({ ...credentials, name: 'Will' }), ORIGIN);
  expect(outcome).toEqual({ kind: 'message', state: { message: PASSWORD_TOO_SHORT_MESSAGE, canResendVerification: false } });
});

test('an unreachable Neon Auth shows the retry message', async () => {
  const signIn = port({ signInWithPassword: vi.fn(async () => Promise.reject(Object.assign(new Error('x'), { code: 'NETWORK_DNS' }))) });
  expect(await signInWithPasswordAction(signIn, form(credentials), ORIGIN)).toEqual({
    kind: 'message',
    state: { message: RETRY, canResendVerification: false },
  });
  expect(warn).toHaveBeenCalledTimes(1);
  expect(warn).toHaveBeenCalledWith('auth:', 'NETWORK_DNS');
  expect(JSON.stringify(warn.mock.calls)).not.toContain('will@example.com');
});

test('sign-in disabled gives the retry message for every action', async () => {
  const actions = [
    signInWithPasswordAction,
    signInWithGoogleAction,
    sendMagicLinkAction,
    signUpAction,
    requestPasswordResetAction,
    resetPasswordAction,
    resendVerificationAction,
  ];
  for (const action of actions) {
    expect(await action(null, form(credentials), ORIGIN)).toEqual({
      kind: 'message',
      state: { message: RETRY, canResendVerification: false },
    });
  }
});

test('an unavailable Neon Auth on password sign-in shows the retry message, not the wrong-password one', async () => {
  const signIn = port({ signInWithPassword: vi.fn(async () => ({ ok: false, code: AUTH_UNAVAILABLE }) as const) });
  expect(await signInWithPasswordAction(signIn, form(credentials), ORIGIN)).toEqual({
    kind: 'message',
    state: { message: RETRY, canResendVerification: false },
  });
});
