// Port for embedded (in-page) credential authentication.
//
// Typed port of web/account/embedded-auth-port.js. Internal callers speak only
// this contract; the vendor SDK lives behind it in `auth0-embedded.ts`. This is the
// frontend mirror of the backend's `IdentityProvider` seam — swapping vendors means
// rewriting only the adapter, never this file or its callers.
//
// The original expressed the contract in a JSDoc block and enforced nothing. Here
// the codes are a union and the failure is a class, so a UI `switch` over them is
// exhaustively checked and an adapter cannot invent a code the UI has no message
// for. That is the whole reason to port it ahead of the components.

/**
 * Stable failure codes. **Vendor-neutral by contract** — an adapter maps its own
 * error vocabulary onto these, and the UI maps these onto owned copy. Nothing
 * downstream parses vendor text.
 *
 * `network` is the catch-all: unreachable, malformed, or simply unrecognised.
 */
export const EMBEDDED_AUTH_LOGIN_CODES = [
  'invalid_credentials',
  'too_many_attempts',
  'unverified_email',
  'network',
] as const;

/** Signup can fail the login way, plus these. */
export const EMBEDDED_AUTH_SIGNUP_CODES = [
  'user_exists',
  'invalid_password',
  'invalid_signup',
] as const;

export type EmbeddedAuthLoginCode = (typeof EMBEDDED_AUTH_LOGIN_CODES)[number];
export type EmbeddedAuthSignupCode =
  | EmbeddedAuthLoginCode
  | (typeof EMBEDDED_AUTH_SIGNUP_CODES)[number];

/** Every code either entry point can reject with. */
export type EmbeddedAuthCode = EmbeddedAuthSignupCode;

/**
 * What a successful authentication yields.
 *
 * `artifact` is opaque — an authorization code or an id_token, per the spike
 * decision — and is redeemed by the backend, never inspected here. `state` is the
 * CSRF value `/auth/password/begin` minted, echoed back to
 * `/auth/password/complete`.
 */
export interface EmbeddedAuthResult {
  artifact: string;
  state: string;
}

/**
 * A failure carrying one of the contract's codes.
 *
 * A class rather than `Object.assign(new Error(), { code })` as the original did:
 * `instanceof` narrows, so a caller reading `.code` is checked rather than trusted,
 * and a vendor error escaping the adapter unmapped is a type error instead of an
 * `undefined` code reaching a `switch`.
 *
 * `message` is kept for logs only. It may carry vendor wording, so it must never
 * be shown to a user — that is what `code` is for.
 */
export class EmbeddedAuthError extends Error {
  readonly code: EmbeddedAuthCode;

  constructor(code: EmbeddedAuthCode, message?: string) {
    super(message ?? `embedded auth failed: ${code}`);
    this.name = 'EmbeddedAuthError';
    this.code = code;
  }
}

/** True for a failure that came through the port with a known code. */
export function isEmbeddedAuthError(error: unknown): error is EmbeddedAuthError {
  return error instanceof EmbeddedAuthError;
}

export interface EmbeddedAuthPort {
  /** Sign an existing account in. */
  authenticateWithPassword(email: string, password: string): Promise<EmbeddedAuthResult>;

  /**
   * Create an account, then sign it in.
   *
   * Resolves with the same shape as `authenticateWithPassword` deliberately, so
   * the caller drives the identical `/auth/password/complete` path either way.
   */
  signupWithPassword(email: string, password: string): Promise<EmbeddedAuthResult>;
}

export interface FakeEmbeddedAuthConfig {
  artifact?: string;
  state?: string;
  /** Reject `authenticateWithPassword` with this code. */
  failWith?: EmbeddedAuthCode | null;
  /** Reject `signupWithPassword` with this code. */
  signupFailWith?: EmbeddedAuthCode | null;
}

/**
 * A test double for the port. No network, no SDK.
 *
 * Kept from the original because it is what lets the login UI be tested without
 * standing up a vendor: the components depend on `EmbeddedAuthPort`, so this
 * substitutes cleanly.
 */
export function makeFakeEmbeddedAuth({
  artifact = 'fake-artifact',
  state = 'fake-state',
  failWith = null,
  signupFailWith = null,
}: FakeEmbeddedAuthConfig = {}): EmbeddedAuthPort {
  return {
    async authenticateWithPassword() {
      if (failWith) throw new EmbeddedAuthError(failWith, `fake auth failure: ${failWith}`);
      return { artifact, state };
    },
    async signupWithPassword() {
      if (signupFailWith) {
        throw new EmbeddedAuthError(signupFailWith, `fake auth failure: ${signupFailWith}`);
      }
      return { artifact, state };
    },
  };
}
