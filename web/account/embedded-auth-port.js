//
// Port for embedded (in-page) credential authentication. Internal callers speak
// only this contract; the vendor SDK lives behind it in auth0-embedded.js. This
// is the frontend mirror of the backend's IdentityProvider seam — swapping
// vendors means rewriting only the adapter, not this contract or its callers.
//
// Contract:
//   authenticateWithPassword(email, password)
//     -> Promise<{ artifact: string, state: string }>
//     Resolves with an opaque OAuth artifact (an authorization code or an
//     id_token, per the spike decision) that the backend redeems, plus the CSRF
//     `state` returned by /auth/password/begin so the caller can echo it to
//     /auth/password/complete. Rejects with an Error whose `.code` is a stable
//     string ('invalid_credentials', 'too_many_attempts', 'unverified_email',
//     'network') so the UI can map it to an owned message without parsing
//     vendor text.
//
//   signupWithPassword(email, password)
//     -> Promise<{ artifact: string, state: string }>
//     Creates a new account, then logs it in — resolving with the same shape as
//     authenticateWithPassword so the caller drives the identical
//     /auth/password/complete path. Rejects with an Error whose `.code` is a
//     stable string; in addition to the login codes above, signup can reject
//     with 'user_exists' (the email already has an account) or 'invalid_password'
//     (the chosen password fails the connection's policy).

/**
 * A test double for the port. No network, no SDK.
 *
 * @param {{ artifact?: string, state?: string, failWith?: string|null, signupFailWith?: string|null }} [config]
 * @returns {{
 *   authenticateWithPassword: (email: string, password: string) => Promise<{ artifact: string, state: string }>,
 *   signupWithPassword: (email: string, password: string) => Promise<{ artifact: string, state: string }>,
 * }}
 */
export function makeFakeEmbeddedAuth({ artifact = 'fake-artifact', state = 'fake-state', failWith = null, signupFailWith = null } = {}) {
  function codedFailure(code) {
    const err = new Error(`fake auth failure: ${code}`);
    err.code = code;
    return err;
  }
  return {
    async authenticateWithPassword(_email, _password) {
      if (failWith) throw codedFailure(failWith);
      return { artifact, state };
    },
    async signupWithPassword(_email, _password) {
      if (signupFailWith) throw codedFailure(signupFailWith);
      return { artifact, state };
    },
  };
}
