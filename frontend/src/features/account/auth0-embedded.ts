// THE ONLY file that imports auth0-js. Everything else speaks the
// embedded-auth-port contract, so swapping providers means rewriting this file and
// nothing above it.
//
// Typed port of web/account/auth0-embedded.js, with one deliberate change: the SDK
// is an npm dependency rather than a CDN `<script>` read off `globalThis.auth0`.
// That removes a runtime dependency on a third party from the sign-in path — the
// one path where a failed CDN fetch means nobody can log in — and it means the
// vendor's own types check this file instead of `any` flowing through it.
import { WebAuth, type Auth0Error } from 'auth0-js';
import type { PasswordBeginResponse } from '@/api/password-auth-api';
import {
  EmbeddedAuthError,
  type EmbeddedAuthCode,
  type EmbeddedAuthPort,
  type EmbeddedAuthResult,
} from './embedded-auth-port';

const CODE_CHALLENGE_METHOD = 'S256';

/** In-page code flow, so the artifact is returned to the opener, not redirected. */
const RESPONSE_TYPE = 'code';
const RESPONSE_MODE = 'web_message';

export interface Auth0EmbeddedConfig {
  domain: string;
  clientID: string;
  /** The Auth0 database connection name. */
  realm: string;
  /** `/auth/password/begin` — mints PKCE, state, nonce and the redirect_uri. */
  begin: (returnTo: string) => Promise<PasswordBeginResponse>;
}

/**
 * The current URL, as the `returnTo` the backend records.
 *
 * Note the original's `|| '/'` fallback is dropped: `location.pathname` is never
 * empty in a browser, so the template literal is never falsy and the branch was
 * unreachable.
 */
function currentPath(): string {
  const { pathname, search, hash } = window.location;
  return `${pathname}${search}${hash}`;
}

/**
 * Vendor error → contract code, for the login path.
 *
 * **Order matters here, and the original's order has a trap worth naming.** Auth0
 * reports an unverified email as `access_denied` in some tenant configurations, not
 * only as `unauthorized`. The original tested `access_denied` first and mapped it
 * straight to `invalid_credentials`, so those users were told their password was
 * wrong. The description check therefore runs BEFORE the credential codes here:
 * a message that says "verify" is about verification whatever code carries it.
 */
function mapLoginError(raw: unknown): EmbeddedAuthError {
  const { code, message } = readVendorError(raw);

  if (/verif/i.test(message)) return new EmbeddedAuthError('unverified_email', message);
  if (code === 'invalid_user_password' || code === 'access_denied') {
    return new EmbeddedAuthError('invalid_credentials', message);
  }
  if (code === 'too_many_attempts') return new EmbeddedAuthError('too_many_attempts', message);
  if (code === 'unauthorized') return new EmbeddedAuthError('unverified_email', message);
  return new EmbeddedAuthError('network', message);
}

/** Signup surfaces a different vocabulary than login. */
const SIGNUP_CODE_MAP: ReadonlyMap<string, EmbeddedAuthCode> = new Map([
  ['user_exists', 'user_exists'],
  ['username_exists', 'user_exists'],
  ['invalid_password', 'invalid_password'],
  ['PasswordStrengthError', 'invalid_password'],
  ['PasswordHistoryError', 'invalid_password'],
  ['PasswordDictionaryError', 'invalid_password'],
  ['invalid_signup', 'invalid_signup'],
  ['too_many_attempts', 'too_many_attempts'],
  ['too_many_signups', 'too_many_attempts'],
  ['blocked', 'too_many_attempts'],
  // A server-side Action or Rule refused the signup. The request completed, so
  // `network` would be a lie — it is a policy rejection.
  ['extensibility_error', 'invalid_signup'],
  ['rule_error', 'invalid_signup'],
]);

function mapSignupError(raw: unknown): EmbeddedAuthError {
  const { code, message } = readVendorError(raw);
  return new EmbeddedAuthError(SIGNUP_CODE_MAP.get(code) ?? 'network', message);
}

/**
 * Pull a code and a description out of whatever the SDK threw.
 *
 * auth0-js is inconsistent about which field carries what — `code`, `error` and
 * `name` all appear depending on the endpoint — so all three are checked, in the
 * order the original checked them per path.
 */
function readVendorError(raw: unknown): { code: string; message: string } {
  const err = (raw ?? {}) as Partial<Auth0Error> & {
    code?: string;
    error?: string;
    name?: string;
    description?: string;
    error_description?: string;
    message?: string;
  };
  return {
    code: err.code || err.error || err.name || '',
    message: err.description || err.error_description || err.message || 'auth failed',
  };
}

/**
 * The Auth0 adapter for the embedded-auth port.
 *
 * Both entry points funnel through one `login`, so a new signup establishes its
 * session by exactly the path a returning user takes — the alternative is two code
 * paths that drift.
 */
export function makeAuth0EmbeddedAuth({
  domain,
  clientID,
  realm,
  begin,
}: Auth0EmbeddedConfig): EmbeddedAuthPort {
  async function login(email: string, password: string): Promise<EmbeddedAuthResult> {
    // The backend-supplied redirect_uri is used verbatim. Deriving it a second
    // time here is how redirect_uri mismatches happen.
    const {
      state,
      nonce,
      code_challenge: codeChallenge,
      redirect_uri: redirectUri,
    } = await begin(currentPath());

    const webAuth = new WebAuth({
      domain,
      clientID,
      redirectUri,
      responseType: RESPONSE_TYPE,
      responseMode: RESPONSE_MODE,
    });

    const artifact = await new Promise<string>((resolve, reject) => {
      webAuth.login(
        {
          realm,
          username: email,
          password,
          state,
          nonce,
          code_challenge: codeChallenge,
          code_challenge_method: CODE_CHALLENGE_METHOD,
        } as Parameters<typeof webAuth.login>[0],
        (err, authResult: { code?: string } | null) => {
          if (err) return reject(mapLoginError(err));
          // A success with no code is not a success — resolving would hand the
          // caller `undefined` to post to /auth/password/complete.
          if (!authResult?.code) {
            return reject(new EmbeddedAuthError('network', 'auth0 returned no code'));
          }
          resolve(authResult.code);
        },
      );
    });

    return { artifact, state };
  }

  return {
    authenticateWithPassword: login,

    async signupWithPassword(email, password) {
      // signup() only creates the account; it does not establish a session, so
      // this chains into login() for the artifact.
      const webAuth = new WebAuth({
        domain,
        clientID,
        // Unused by signup, but the constructor's types require them.
        redirectUri: window.location.origin,
        responseType: RESPONSE_TYPE,
      });

      await new Promise<void>((resolve, reject) => {
        webAuth.signup({ connection: realm, email, password }, (err) => {
          if (err) return reject(mapSignupError(err));
          resolve();
        });
      });

      return login(email, password);
    },
  };
}
