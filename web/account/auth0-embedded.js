// web/account/auth0-embedded.js
//
// THE ONLY file that imports/uses auth0-js. Everything else speaks the
// embedded-auth-port contract. Swapping providers means rewriting this file.
// auth0-js loads via a CDN <script> in index.html as the global `auth0`
// (same pattern as maplibre/turf).

const CODE_CHALLENGE_METHOD = 'S256';

/**
 * @param {{ domain: string, clientID: string, realm: string,
 *   begin: (returnTo: string) => Promise<{ state: string, nonce: string, code_challenge: string, redirect_uri: string }> }} config
 * @returns {{
 *   authenticateWithPassword: (email: string, password: string) => Promise<{ artifact: string, state: string }>,
 *   signupWithPassword: (email: string, password: string) => Promise<{ artifact: string, state: string }>,
 * }}
 */
export function makeAuth0EmbeddedAuth({ domain, clientID, realm, begin }) {
  // Runs the in-page code flow: begin (backend mints PKCE + state + redirect_uri)
  // then webAuth.login. Shared by both entry points so signup logs the new user
  // in through the exact same path as a returning sign-in.
  async function login(email, password) {
    // The backend-supplied redirect_uri is used verbatim — two independent
    // derivations are how redirect_uri mismatches happen.
    const {
      state,
      nonce,
      code_challenge: codeChallenge,
      redirect_uri: redirectUri,
    } = await begin(currentPath());

    const webAuth = new globalThis.auth0.WebAuth({
      domain, clientID,
      redirectUri,
      responseType: 'code',
      responseMode: 'web_message',
    });

    const artifact = await new Promise((resolve, reject) => {
      webAuth.login(
        {
          realm, username: email, password, state, nonce,
          code_challenge: codeChallenge,
          code_challenge_method: CODE_CHALLENGE_METHOD,
        },
        (err, authResult) => {
          if (err) return reject(mapAuth0Error(err));
          resolve(authResult.code);
        },
      );
    });

    return { artifact, state };
  }

  return {
    authenticateWithPassword: login,

    async signupWithPassword(email, password) {
      // signup() only creates the account; it does not establish a session.
      // On success we chain straight into login() so the caller gets the same
      // { artifact, state } and drives the identical /auth/password/complete path.
      const webAuth = new globalThis.auth0.WebAuth({ domain, clientID });
      await new Promise((resolve, reject) => {
        webAuth.signup(
          { connection: realm, email, password },
          (err) => {
            if (err) return reject(mapAuth0SignupError(err));
            resolve();
          },
        );
      });
      return login(email, password);
    },
  };
}

// Map vendor error shapes to the port's stable codes.
function mapAuth0Error(err) {
  const code = err && (err.code || err.error);
  const e = new Error((err && (err.description || err.error_description)) || 'auth failed');
  if (code === 'invalid_user_password' || code === 'access_denied') e.code = 'invalid_credentials';
  else if (code === 'too_many_attempts') e.code = 'too_many_attempts';
  else if (code === 'unauthorized' && /verif/i.test(e.message)) e.code = 'unverified_email';
  else e.code = 'network';
  return e;
}

// Signup surfaces a different error vocabulary than login.
function mapAuth0SignupError(err) {
  const code = err && (err.code || err.name || err.error);
  const e = new Error((err && (err.description || err.error_description || err.message)) || 'signup failed');
  if (code === 'user_exists' || code === 'username_exists') e.code = 'user_exists';
  else if (code === 'invalid_password' || code === 'PasswordStrengthError' || code === 'PasswordHistoryError' || code === 'PasswordDictionaryError') e.code = 'invalid_password';
  else if (code === 'invalid_signup') e.code = 'invalid_signup';
  else if (code === 'too_many_attempts' || code === 'too_many_signups' || code === 'blocked') e.code = 'too_many_attempts';
  // A server-side Action/Rule refused the signup: the request completed, so
  // 'network' would be a lie. Surface it as a signup-rejected policy error.
  else if (code === 'extensibility_error' || code === 'rule_error') e.code = 'invalid_signup';
  else e.code = 'network';
  return e;
}

function currentPath() {
  const { pathname, search, hash } = window.location;
  return `${pathname}${search}${hash}` || '/';
}
