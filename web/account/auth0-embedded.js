// web/account/auth0-embedded.js
//
// THE ONLY file that imports/uses auth0-js. Everything else speaks the
// embedded-auth-port contract. Swapping providers means rewriting this file.
// auth0-js loads via a CDN <script> in index.html as the global `auth0`
// (same pattern as maplibre/turf).

const CODE_CHALLENGE_METHOD = 'S256';

/**
 * @param {{ domain: string, clientID: string, realm: string,
 *   begin: (returnTo: string) => Promise<{ state: string, nonce: string, code_challenge: string }> }} config
 * @returns {{ authenticateWithPassword: (email: string, password: string) => Promise<{ artifact: string, state: string }> }}
 */
export function makeAuth0EmbeddedAuth({ domain, clientID, realm, begin }) {
  return {
    async authenticateWithPassword(email, password) {
      // Backend mints + stores the PKCE verifier and returns its challenge + state.
      const { state, nonce, code_challenge: codeChallenge } = await begin(currentPath());

      const webAuth = new globalThis.auth0.WebAuth({
        domain, clientID,
        redirectUri: `${window.location.origin}/auth/callback`,
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

function currentPath() {
  const { pathname, search, hash } = window.location;
  return `${pathname}${search}${hash}` || '/';
}
