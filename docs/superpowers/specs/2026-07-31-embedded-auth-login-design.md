# Embedded sign-in for Roadtrip — design

Date: 2026-07-31
Status: Approved (brainstorming)

## Problem

Today the "Sign in to Roadtrip" modal shows a single **"Continue with single
sign-on"** button. Clicking it is a full-page navigation to Auth0's hosted login
page (`/auth/login` → provider authorize endpoint), where the user enters
credentials, then is redirected back to `/auth/callback`. The experience leaves
the app, and the login screen is not ours.

We want an **inline sign-in card**: an email/password form we own, plus a
"Continue with Google" button. Password login must complete **without leaving
the page**. Google login still redirects — that is an OAuth requirement (the
consent screen lives on Google's origin) and is the vendor-neutral path, so it
is accepted as-is.

## Constraints and context

- Custom Auth0 domain `auth.roadtrip.floo.ca` exists. This is what makes
  embedded (cross-origin) password auth viable in production; browsers would
  otherwise block the third-party cookies the flow depends on.
- The tenant has **real email/password users today** (not Google-only), so an
  embedded password form delivers value immediately.
- The current architecture (RFC 0009) is deliberately vendor-neutral:
  - Backend speaks **standard OIDC** via `OidcClient` (discovery, `exchangeCode`,
    JWKS) and validates ID tokens with `IdTokenVerifier` (nimbus-jose-jwt, not an
    Auth0 library). The only Auth0-specific code is `Auth0ClaimsDialect`
    (~60 lines decoding the `google|…` `sub` shape).
  - Frontend has **no Auth0 SDK at all**; it navigates to `/auth/login` and reads
    identity from `/api/me`. The session is a first-party HttpOnly cookie.
- That portability exists *because* the vendor owns the login page. Owning the
  form necessarily adds some vendor coupling. The design goal is to **quarantine
  that coupling to the smallest possible surface.**

### Decision: vendor SDK is acceptable, but only behind our interface

The user is comfortable integrating a vendor SDK **as long as internal callers
speak our own interface**, never the vendor's types directly.

- **Frontend:** `auth0-js` is used, but only inside a single adapter behind a
  port interface. No other frontend file imports it.
- **Backend:** we deliberately do **not** add the Auth0 Java SDK. The existing
  `OidcClient` + `IdTokenVerifier` already *are* the vendor-neutral interface
  (`IdentityProvider`), implemented in ~130 lines of standard OIDC that work
  today. Adding the SDK would be pure cost — a new dependency to do what standard
  code already does — with no portability gain. If a future vendor ever needed
  server-side SDK calls the OIDC standard cannot express, it would be wrapped in
  a new `IdentityProvider` implementation; that seam already exists.

## Non-goals (YAGNI)

- Sign-up, password reset, and in-page MFA — remain on the hosted page if needed
  later.
- Sign in with Apple — already deferred per RFC 0009 decision 21.
- Replacing the Google redirect with anything embedded — impossible under OAuth
  and not attempted.
- Adding the Auth0 Java SDK to the backend — explicitly rejected above.

## Architecture

```
┌─ Frontend (ours, portable) ─────────────────────────────────┐
│  login-card.js          your form markup, validation, errors, Google button   │
│  embedded-auth-port.js  interface: authenticateWithPassword(email, pw) → {…}   │
│  auth0-embedded.js      THE ONLY file importing auth0-js   ◄── lock-in here    │
└──────────────────────────────────────────────────────────────┘
                    │ posts a standard OAuth artifact (code or id_token)
                    ▼
┌─ Backend (ours, vendor-neutral — reuses existing seams) ─────┐
│  POST /auth/password/begin     mints state/nonce/PKCE + signed flow cookie     │
│                                 (reuses AuthController.beginLogin machinery)   │
│  POST /auth/password/complete   reuses AuthController.completeLogin →           │
│                                 OidcClient.exchangeCode → issues session cookie │
└──────────────────────────────────────────────────────────────┘
```

The vendor boundary is a single, narrow, **standards-shaped output**: the
adapter turns `(email, password)` into a standard OAuth artifact (a `code`, or
an `id_token`), in-page, with no redirect. From that artifact onward, the backend
runs the same verified path it runs today.

## Components

### Frontend

1. **`web/account/embedded-auth-port.js`** — the port (interface + contract
   docs). Defines the shape internal callers use:
   - `authenticateWithPassword(email, password) → Promise<{ code }>` (or
     `{ idToken }`, depending on the spike outcome below).
   - Social login is *not* part of the port — "Continue with Google" stays a
     plain redirect to the existing `/auth/login?connection=google`, which needs
     no SDK and is fully vendor-neutral.
   A fake implementation of this port is what tests inject.

2. **`web/account/auth0-embedded.js`** — the single `auth0-js` implementation of
   the port. The only file in the codebase that imports the vendor SDK. Switching
   to WorkOS/Clerk means rewriting only this file.

3. **`web/account/login-card.js` + `login-card-template.js`** — reworked from a
   single SSO button into the inline card:
   - Email + password fields (owned markup, owned validation, owned error/loading
     states), submitting through the port.
   - A "Continue with Google" button that redirects via `signIn`-style navigation
     to `/auth/login?connection=google`.
   - Keeps the existing dependency-injection style (`_signIn`, `_fetchMe`,
     `_mountModal` are already injectable) — the port implementation is injected
     the same way so tests pass a fake.

### Backend

4. **`POST /auth/password/begin`** (in `AuthRoutes.kt`) — returns the
   state/nonce/PKCE material the adapter needs and sets the signed HttpOnly flow
   cookie, reusing `LoginFlowState` and `AuthController.beginLogin`'s existing
   machinery. No new secrets handling.

5. **`POST /auth/password/complete`** (in `AuthRoutes.kt`) — accepts the artifact
   from the adapter, verifies state against the flow cookie, and calls the
   **existing** `AuthController.completeLogin` (→ `OidcClient.exchangeCode` →
   `IdTokenVerifier.verify` → `SessionService.issue`). Sets the same first-party
   session cookie as `/auth/callback`. No vendor-specific code added.

Both routes are typed DTOs (`@Serializable`), per backend layering rules — no
hand-built JSON. Route → controller → repo layering is preserved; the routes are
thin HTTP shells over `AuthController`.

## The one real technical risk

The exact `auth0-js` call sequence for cross-origin credential validation that
returns a `code` (while the backend holds the PKCE `code_verifier`) is
vendor-specific and must not be guessed.

**Implementation step 1 is a short spike** against the real tenant to confirm the
mechanism. Two candidate paths, decided by what `auth0-js` actually supports:

- **Path A (preferred):** adapter returns a `code` via `response_mode=web_message`;
  backend runs `OidcClient.exchangeCode` (client secret stays server-side, tokens
  never persist in the browser). Most faithful to today's design.
- **Path B (fallback):** adapter returns an `id_token` in-page; backend verifies
  it with the **existing `IdTokenVerifier`** and mints the session. Simpler;
  the id_token transits the browser but is never persisted.

Both reuse existing backend code and keep the backend vendor-neutral. The spike
selects one before the real implementation is built. The port interface is
shaped to accommodate whichever artifact wins (`{ code }` vs `{ idToken }`)
without changing callers.

## Auth0 tenant configuration (ops, not code)

Documented for the operator; not touched by this work:

- Enable Cross-Origin Authentication.
- Add `https://roadtrip.floo.ca` (and local origins as needed) to Allowed Web
  Origins.
- Confirm the custom domain `auth.roadtrip.floo.ca` is the origin used by the
  embedded flow.

## Error handling

- Invalid credentials, locked account, unverified email, and rate-limit
  responses from the adapter map to owned, user-facing messages in the card — no
  raw vendor errors surfaced.
- Backend `/auth/password/*` failures reuse the existing single generic
  `login_failed` response (telling a caller *which* check failed only tells an
  attacker what to fix), consistent with `/auth/callback`.
- Network/SDK-load failure in the adapter degrades gracefully: the card can fall
  back to the Google button and/or a link to the hosted flow.

## Testing

- **Port contract test:** a fake port implementation drives `login-card`
  interaction tests (field validation, error states, loading, success) with no
  network — matching the existing `_signIn`/`_fetchMe` injection pattern.
- **Adapter:** thin; covered by the spike + a focused test that it conforms to
  the port contract.
- **Backend:** unit tests on `/auth/password/begin` and `/auth/password/complete`
  reusing existing `AuthController` test patterns; assert they produce the same
  session outcome as `/auth/callback` and share its failure semantics.

## Portability summary

- **Backend:** unchanged vendor-neutrality. Swapping providers = change issuer
  env var + pick a `ClaimsDialect`, exactly as today.
- **Frontend:** lock-in surface shrinks from "the whole login" to a single
  adapter file (`auth0-embedded.js`). Swapping providers = rewrite that one file;
  UI and backend do not move.
