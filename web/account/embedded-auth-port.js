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

/**
 * A test double for the port. No network, no SDK.
 *
 * @param {{ artifact?: string, state?: string, failWith?: string|null }} [config]
 * @returns {{ authenticateWithPassword: (email: string, password: string) => Promise<{ artifact: string, state: string }> }}
 */
export function makeFakeEmbeddedAuth({ artifact = 'fake-artifact', state = 'fake-state', failWith = null } = {}) {
  return {
    async authenticateWithPassword(_email, _password) {
      if (failWith) {
        const err = new Error(`fake auth failure: ${failWith}`);
        err.code = failWith;
        throw err;
      }
      return { artifact, state };
    },
  };
}
