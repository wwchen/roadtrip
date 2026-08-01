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

Sign-in/sign-up UI is Clerk's hosted **Account Portal**
(`accounts.roadtrip.floo.ca`) — the Universal-Login equivalent — reached
automatically when `/oauth/authorize` sees an unauthenticated user. Clerk's
embeddable `<SignIn />`/`<SignUp />` React components are not adopted (the
frontend is vanilla ES modules; embedding them is the SDK-native path this
design rejects). Dashboard component paths stay on their Account Portal
defaults, and the OAuth application should be set to skip the consent screen
(first-party app) so login flows sign-in → callback with no consent prompt.

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

### 3. Configuration and secrets — vendor-specific credentials

Credentials become **per-vendor** rather than generic, so both vendors'
values coexist in the vault and switching providers is a single
`ROADTRIP_AUTH_PROVIDER` flip with no secret swapping.

- `backend/src/main/resources/application.yaml`: default provider flips to
  `provider: "${ROADTRIP_AUTH_PROVIDER:clerk}"`, and the flat
  issuer/client-id/client-secret keys are replaced by per-vendor blocks:

  ```yaml
  roadtrip:
    auth:
      provider: "${ROADTRIP_AUTH_PROVIDER:clerk}"
      providers:
        auth0:
          issuer: "${ROADTRIP_AUTH_AUTH0_ISSUER:}"
          client-id: "${ROADTRIP_AUTH_AUTH0_CLIENT_ID:}"
          client-secret: "${ROADTRIP_AUTH_AUTH0_CLIENT_SECRET:}"
        clerk:
          issuer: "${ROADTRIP_AUTH_CLERK_ISSUER:}"
          client-id: "${ROADTRIP_AUTH_CLERK_CLIENT_ID:}"
          client-secret: "${ROADTRIP_AUTH_CLERK_CLIENT_SECRET:}"
  ```

  (Names keep the repo's `ROADTRIP_` env prefix convention.)
- `AuthConfig.fromConfig` selects the active provider's block; if that
  block is incomplete, auth is disabled (existing null-config behavior).
  The generic `ROADTRIP_AUTH_ISSUER`/`_CLIENT_ID`/`_CLIENT_SECRET` vars are
  retired — no back-compat shim.
- `secrets/registry.yaml` and `docker-compose.secrets.yml`: replace the three
  generic entries with the six vendor-specific ones; existing Auth0 vault
  values move under the `AUTH0`-suffixed keys, Clerk values are added under
  the `CLERK`-suffixed keys (operator step below).
- Note: the **active provider's** client secret derives the login-flow
  cookie HMAC key; switching providers invalidates in-flight login cookies
  (10-minute TTL — harmless).

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

Set `ROADTRIP_AUTH_PROVIDER=auth0` — nothing else. Both vendors' credentials
remain configured side by side (vendor-specific env vars), and the Auth0
dialect and its tests stay in-tree, so switching back (or forward again) is a
single env-var flip plus restart. Sessions are unaffected in both directions.
