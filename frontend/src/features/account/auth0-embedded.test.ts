// Tests for the Auth0 adapter, which is the only place vendor error shapes are
// interpreted. The legacy adapter had no tests, so this is the first pinning of the
// mapping — and the mapping is what decides whether a user is told "wrong password"
// or "verify your email".
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import type { PasswordBeginResponse } from '@/api/password-auth-api';
import { EmbeddedAuthError, isEmbeddedAuthError } from './embedded-auth-port';
import { makeAuth0EmbeddedAuth } from './auth0-embedded';

const BEGIN: PasswordBeginResponse = {
  state: 'state-123',
  nonce: 'nonce-456',
  code_challenge: 'challenge-789',
  redirect_uri: 'https://app.test/auth/callback',
};

// One fake WebAuth, driven per test. `login`/`signup` invoke their callback with
// whatever the test queued, matching auth0-js's (err, result) convention.
interface Queued {
  loginErr?: unknown;
  loginResult?: unknown;
  signupErr?: unknown;
}

let queued: Queued;
let constructorArgs: unknown[];
let loginOptions: Record<string, unknown> | undefined;

vi.mock('auth0-js', () => ({
  WebAuth: class {
    constructor(options: unknown) {
      constructorArgs.push(options);
    }

    login(options: Record<string, unknown>, cb: (err: unknown, r: unknown) => void) {
      loginOptions = options;
      cb(queued.loginErr ?? null, queued.loginResult ?? { code: 'auth-code-abc' });
    }

    signup(_options: unknown, cb: (err: unknown) => void) {
      cb(queued.signupErr ?? null);
    }
  },
}));

const begin = vi.fn(async (): Promise<PasswordBeginResponse> => BEGIN);

function adapter() {
  return makeAuth0EmbeddedAuth({
    domain: 'test.auth0.com',
    clientID: 'client-1',
    realm: 'Username-Password-Authentication',
    begin,
  });
}

/** The code the port rejected with, for a failing call. */
async function codeFrom(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
  } catch (err) {
    if (!isEmbeddedAuthError(err)) throw new Error(`not a port error: ${String(err)}`);
    return err.code;
  }
  throw new Error('expected a rejection');
}

beforeEach(() => {
  queued = {};
  constructorArgs = [];
  loginOptions = undefined;
  begin.mockClear();
  window.history.replaceState(null, '', '/watches?tab=1#frag');
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('login', () => {
  test('returns the artifact and the state from begin', async () => {
    await expect(adapter().authenticateWithPassword('a@b.test', 'pw')).resolves.toEqual({
      artifact: 'auth-code-abc',
      state: 'state-123',
    });
  });

  test('sends begin the current path including search and hash', async () => {
    await adapter().authenticateWithPassword('a@b.test', 'pw');
    expect(begin).toHaveBeenCalledWith('/watches?tab=1#frag');
  });

  // Deriving redirect_uri a second time on the client is how mismatches happen.
  test('uses the backend redirect_uri verbatim', async () => {
    await adapter().authenticateWithPassword('a@b.test', 'pw');
    expect(constructorArgs[0]).toMatchObject({ redirectUri: BEGIN.redirect_uri });
  });

  test('forwards the PKCE challenge, state and nonce to the SDK', async () => {
    await adapter().authenticateWithPassword('a@b.test', 'pw');
    expect(loginOptions).toMatchObject({
      username: 'a@b.test',
      password: 'pw',
      state: BEGIN.state,
      nonce: BEGIN.nonce,
      code_challenge: BEGIN.code_challenge,
      code_challenge_method: 'S256',
      realm: 'Username-Password-Authentication',
    });
  });

  // A success carrying no code would hand `undefined` to /auth/password/complete.
  test('a success with no code is a failure, not an undefined artifact', async () => {
    queued.loginResult = {};
    expect(await codeFrom(adapter().authenticateWithPassword('a@b.test', 'pw'))).toBe('network');
  });

  test('maps a wrong password', async () => {
    queued.loginErr = { code: 'invalid_user_password' };
    expect(await codeFrom(adapter().authenticateWithPassword('a@b.test', 'pw'))).toBe(
      'invalid_credentials',
    );
  });

  test('maps a rate limit', async () => {
    queued.loginErr = { code: 'too_many_attempts' };
    expect(await codeFrom(adapter().authenticateWithPassword('a@b.test', 'pw'))).toBe(
      'too_many_attempts',
    );
  });

  test('maps an unverified email reported as unauthorized', async () => {
    queued.loginErr = { code: 'unauthorized', description: 'Please verify your email' };
    expect(await codeFrom(adapter().authenticateWithPassword('a@b.test', 'pw'))).toBe(
      'unverified_email',
    );
  });

  // The trap the legacy order had: Auth0 reports an unverified email as
  // `access_denied` in some tenant configs, and the original tested access_denied
  // first — telling those users their password was wrong.
  test('an unverified email reported as access_denied is not "wrong password"', async () => {
    queued.loginErr = { code: 'access_denied', description: 'Please verify your email' };
    expect(await codeFrom(adapter().authenticateWithPassword('a@b.test', 'pw'))).toBe(
      'unverified_email',
    );
  });

  test('a plain access_denied is still a credential failure', async () => {
    queued.loginErr = { code: 'access_denied', description: 'Wrong email or password' };
    expect(await codeFrom(adapter().authenticateWithPassword('a@b.test', 'pw'))).toBe(
      'invalid_credentials',
    );
  });

  test('an unrecognised failure falls back to network', async () => {
    queued.loginErr = { code: 'something_new' };
    expect(await codeFrom(adapter().authenticateWithPassword('a@b.test', 'pw'))).toBe('network');
  });

  test('reads the code from `error` when `code` is absent', async () => {
    queued.loginErr = { error: 'invalid_user_password' };
    expect(await codeFrom(adapter().authenticateWithPassword('a@b.test', 'pw'))).toBe(
      'invalid_credentials',
    );
  });

  // Vendor wording is for logs; the code is what the UI reads.
  test('keeps the vendor description on the error for logs', async () => {
    queued.loginErr = { code: 'invalid_user_password', description: 'Wrong email or password.' };
    await expect(adapter().authenticateWithPassword('a@b.test', 'pw')).rejects.toThrow(
      'Wrong email or password.',
    );
  });
});

describe('signup', () => {
  test('creates the account then signs in through the login path', async () => {
    await expect(adapter().signupWithPassword('a@b.test', 'pw')).resolves.toEqual({
      artifact: 'auth-code-abc',
      state: 'state-123',
    });
    // begin() is only reached via login(), so this proves signup chained into it.
    expect(begin).toHaveBeenCalledTimes(1);
  });

  test('maps an existing account', async () => {
    for (const code of ['user_exists', 'username_exists']) {
      queued.signupErr = { code };
      expect(await codeFrom(adapter().signupWithPassword('a@b.test', 'pw'))).toBe('user_exists');
    }
  });

  test('maps every password-policy rejection', async () => {
    for (const code of [
      'invalid_password',
      'PasswordStrengthError',
      'PasswordHistoryError',
      'PasswordDictionaryError',
    ]) {
      queued.signupErr = { code };
      expect(await codeFrom(adapter().signupWithPassword('a@b.test', 'pw'))).toBe(
        'invalid_password',
      );
    }
  });

  test('maps signup rate limits and blocks', async () => {
    for (const code of ['too_many_attempts', 'too_many_signups', 'blocked']) {
      queued.signupErr = { code };
      expect(await codeFrom(adapter().signupWithPassword('a@b.test', 'pw'))).toBe(
        'too_many_attempts',
      );
    }
  });

  // The request completed, so calling it a network error would be a lie.
  test('a server-side Action refusal is a policy rejection, not a network error', async () => {
    for (const code of ['extensibility_error', 'rule_error']) {
      queued.signupErr = { code };
      expect(await codeFrom(adapter().signupWithPassword('a@b.test', 'pw'))).toBe('invalid_signup');
    }
  });

  // auth0-js puts the code in `name` for some signup failures.
  test('reads the code from `name` when that is where it lands', async () => {
    queued.signupErr = { name: 'PasswordStrengthError' };
    expect(await codeFrom(adapter().signupWithPassword('a@b.test', 'pw'))).toBe('invalid_password');
  });

  test('an unrecognised signup failure falls back to network', async () => {
    queued.signupErr = { code: 'brand_new' };
    expect(await codeFrom(adapter().signupWithPassword('a@b.test', 'pw'))).toBe('network');
  });

  test('a failed signup never reaches the login path', async () => {
    queued.signupErr = { code: 'user_exists' };
    await codeFrom(adapter().signupWithPassword('a@b.test', 'pw'));
    expect(begin).not.toHaveBeenCalled();
  });
});

describe('EmbeddedAuthError', () => {
  test('is recognisable across the port boundary', () => {
    const err = new EmbeddedAuthError('network', 'boom');
    expect(isEmbeddedAuthError(err)).toBe(true);
    expect(isEmbeddedAuthError(new Error('boom'))).toBe(false);
    expect(err.code).toBe('network');
  });
});
