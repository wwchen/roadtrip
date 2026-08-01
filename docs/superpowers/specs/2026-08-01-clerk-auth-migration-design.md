# Auth provider migration: Auth0 → Clerk

Date: 2026-08-01
Status: Approved
Related: `rfcs/0009-auth-provider-layer.md`

## Goal

Switch the identity provider from Auth0 to Clerk by pointing the existing
vendor-agnostic OIDC auth layer at the Clerk production instance
(`https://clerk.roadtrip.floo.ca`). No Clerk SDK and no new dependencies.
Existing first-party sessions survive the swap; returning users are relinked
by verified email on their next login. Rollback is a config flip — the Auth0
claims dialect remains in the codebase.

## Integration approach

Clerk is integrated as an **OAuth application** (Clerk dashboard: Configure →
OAuth applications), which yields a standard confidential OIDC client
(`client_id` + `client_secret`) served from Clerk's OIDC discovery endpoint.
This slots directly into the existing `ROADTRIP_AUTH_*` configuration and
preserves RFC 0009's vendor containment.

The Clerk SDK-style keys (`pk_live_…` publishable key, `sk_live_…` Backend API
key) are **not used** by this design. The secret Backend API key never enters
the repo. (If Clerk Backend API enrichment of upstream identities is ever
wanted, it becomes a well-defined extension point inside the Clerk adapter.)

Verified against the live discovery document
(`https://clerk.roadtrip.floo.ca/.well-known/openid-configuration`):

- `authorization_code` grant, PKCE `S256`, `client_secret_post` — all
  supported; matches `OidcClient` / `OidcIdentityProvider` behavior.
- `id_token_signing_alg_values_supported: [RS256]` — compatible with
  `IdTokenVerifier` (asymmetric-only policy).
- `claims_supported` includes `sub`, `email`, `email_verified`, `name`,
  `picture` — everything `IdentityClaims` needs.
- **No `end_session_endpoint`** — see Logout below.

## Changes

### 1. `ClerkClaimsDialect`

New class in `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/`,
registered under ID `"clerk"` in `ClaimsDialectRegistry.default()`.

Clerk subjects are opaque (`user_2abc…`) with no embedded connection
information, so the dialect maps:

- `subject` = raw `sub`, verbatim
- `email`, `isEmailVerified`, `displayName` from the standard OIDC claims
- `upstreamProvider` = null, `upstreamSubject` = null

Registering an explicit dialect (rather than falling through to
`StandardClaimsDialect`) avoids the registry's silent degrade-with-warning
path and gives Clerk-specific parsing a home if it grows.

### 2. Provider display label

- Add a human-readable `displayName` to the `ClaimsDialect` contract
  (`"clerk"` → `"Clerk"`, `"auth0"` → `"Auth0"`, WorkOS → `"WorkOS"`,
  standard/unknown → fall back to the raw slug).
- `ServiceModule` resolves `providerLabel` through the registry instead of
  passing the raw config slug.
- Bug fix (pre-existing): `web/account/login-card.js` reads
  `me.provider_label`, but `MeResponseDto` has no such field, so the login
  card has always rendered the `'single sign-on'` fallback. Add
  `provider_label` to `MeResponseDto` (populated from the same resolved
  label) so the card reads "Continue with Clerk".

### 3. Configuration and secrets

- `backend/src/main/resources/application.yaml`: default provider flips to
  `provider: "${ROADTRIP_AUTH_PROVIDER:clerk}"`.
- `secrets/registry.yaml`: update guidance for `ROADTRIP_AUTH_ISSUER`
  (`https://clerk.roadtrip.floo.ca`), `ROADTRIP_AUTH_CLIENT_ID`, and
  `ROADTRIP_AUTH_CLIENT_SECRET` (values from the Clerk OAuth application).
- Encrypted values in `secrets/*.enc.env` are updated out-of-band once the
  Clerk OAuth application exists (operator step below).
- Note: `ROADTRIP_AUTH_CLIENT_SECRET` also derives the login-flow cookie HMAC
  key; rotation invalidates in-flight login cookies (10-minute TTL — harmless).

**Operator step (dashboard, cannot be done from the repo):** create the OAuth
application in Clerk with scopes `openid email profile` and callback URL
`<roadtrip.web.root-url>/auth/callback` for each environment that needs
login. Record the resulting `client_id`/`client_secret` into the SOPS vault.

## Logout (accepted degradation)

Clerk's OAuth server publishes no `end_session_endpoint`. The existing
fallback in `OidcIdentityProvider.logoutUrl` / `AuthRoutes` already handles
this: sign-out revokes the first-party session and redirects to `/`, but the
Clerk session persists, so the next sign-in completes silently without
re-prompting. Accepted; documented in code where the fallback triggers.

## Data

No DB migration. Existing `user_identity` rows with `provider = 'auth0'`
remain (inert; they preserve rollback). New logins insert
`provider = 'clerk'` rows; `UserProvisioningService`'s verified-email path
links them to the existing `app_user`. Password-only Auth0 users establish a
new credential in Clerk (passwords are not migrated — RFC 0009 decision 17)
and relink via verified email. Unverified-email takeover remains refused.

## Tests and CI

- `ClerkClaimsDialectTest` mirroring the Auth0 dialect test.
- Registry resolution test for `"clerk"`.
- Label expectations updated (`UserSettingsServiceTest`, frontend
  `login-card` / settings tests) for the resolved display name and the new
  `/api/me` field.
- `.github/workflows/deploy.yml` post-deploy smoke test: the generic
  `/auth/login` → provider authorize flow already works against Clerk;
  replace the Auth0-specific "Callback URL mismatch" HTML scraping with
  provider-neutral diagnostics.

## Error handling

- Unknown `kid` after Clerk key rotation: already handled by forced JWKS
  refresh in `OidcIdentityProvider.exchange`.
- Missing/blank Clerk credentials: `AuthConfig.fromConfig` returns null and
  auth is disabled (existing first-class state; frontend hides sign-in).
- Discovery issuer mismatch: existing hard assertion in
  `OidcClient.discovery()` — verified to match (`https://clerk.roadtrip.floo.ca`,
  no trailing-slash quirk).

## Rollback

Set `ROADTRIP_AUTH_PROVIDER=auth0` and restore the previous Auth0
issuer/client credentials in the vault. The Auth0 dialect and its tests stay
in-tree. Sessions are unaffected in both directions.
