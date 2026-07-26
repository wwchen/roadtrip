---
title: Auth provider layer
authors:
  - William Chen
created: 2026-07-26
last_updated: 2026-07-26
rfc_pr: TBD
status: Draft
---

# Proposal: Auth provider layer

## Summary

Introduce authentication and a first-party session to a codebase that has
neither. The vendor (Auth0) is terminated at a single callback route; every
other layer sees a `Principal` domain value and never a vendor type. Auth0 is
wired as *configuration of a generic OIDC adapter*, not as its own adapter, so
the vendor's name appears in YAML and never in Kotlin. This RFC covers the auth
layer only — browsing stays anonymous, and the downstream work that consumes
`Principal` (watch ownership, per-user notification credentials) is sketched as
a roadmap and specified separately.

## Motivation

The backend has no concept of a user. Grepping `backend/src/main` for
auth/session/token/jwt returns only Slack request signing and vendor API keys.
Three concrete consequences:

1. **Watches are global.** `availability_watch` has no owner column.
   `GET /api/watches` returns every user's watches; `POST /api/watches/{id}/delete`
   deletes anyone's. `web/topbar/alerts.js:7` documents this as intended
   behaviour: *"Watches are global (no auth), so this reflects everyone's watches."*

2. **The notification path is an open relay.** `WatchTriggerConfig.emailRecipients()`
   reads `trigger_config.email_notify.to` — an arbitrary, unverified address
   supplied by whoever created the watch. Any anonymous caller can make the
   deployment send mail to any address.

3. **Admin has no in-app gate.** `/api/admin/data/*` is protected only by a
   Cloudflare Zero Trust path rule, documented at `AdminIngestRoutes.kt:60`. A
   tunnel misconfiguration exposes ingest control to the internet.

None of these can be fixed without a user identity to attach ownership to. This
RFC builds that identity and stops there.

## Goals

- A user can sign in with Google, Apple, or email + password.
- The backend holds a first-party, revocable session; the vendor is not in the
  request path after callback.
- Every request resolves to a `Principal` — `Anonymous`, `User`, or `System`.
- Admin routes are gated in-app by role, not only at the edge.
- Local development and CI work with no Auth0 tenant provisioned.
- Swapping Auth0 for another OIDC provider is a config change. Swapping for a
  non-OIDC provider replaces one adapter class and nothing else.

## Non-Goals

- **Watch ownership.** Needs `Principal` to exist first; specified separately.
- **Per-user notification credentials** (Slack install, verified email). Same.
- **Per-user rec.gov / ATC.** The companion's persistent Chromium profile *is*
  the credential (`companion/src/recgovSession.js`); multiuser means a browser
  profile pool. Deliberately deferred — see Roadmap.
- **Anonymous-then-claim watches.** Sign-in is required to create a watch. An
  anon identity plus a claim flow plus an abandoned-watch reaper is real
  complexity for a rare path.
- **Organisations / teams / sharing.** Single-owner resources only.

## Proposal

### The seam

Browsing is anonymous. Creating or mutating an alert requires a user.
Notification credentials and targets live behind user settings.

| Surface | Access |
|---|---|
| `/api/pois*`, `/api/route`, `/api/geocode`, `/api/pois/{id}/campsites*` | anonymous |
| static site, `?poi=` / `?route=` share links | anonymous |
| `/api/watches` CRUD | user, owner-scoped |
| `/api/me`, `/api/me/settings/*` | user, self only |
| `/api/admin/data/*` | admin role |
| `/api/availability/pollers\|runs\|snapshots`, force-poll | admin role |
| `/api/slack/interactivity` | signature + identity mapping |

### Vendor containment

The load-bearing decision. Auth0 is a standards-compliant OIDC provider, so we
build a **generic OIDC adapter driven by a discovery document** and configure it
with Auth0's issuer. Nothing in the codebase is named `auth0` except a YAML
value and an env var.

```
Browser → GET /auth/login          → 302 to Auth0 (code + PKCE)
Browser → GET /auth/callback       → verify ID token, upsert identity,
                                      mint first-party session cookie
everything after                   → our cookie. Auth0 is not in the path.
```

The port follows the repo's existing `Dispatchable` registry idiom
(`support/Dispatchable.kt`, as used by `AlertProvider` and `BookingAdapter`):

```kotlin
// service/auth/IdentityProvider.kt
internal interface IdentityProvider : Dispatchable<IdentityProviderId> {
    val id: String
    override fun canHandle(key: IdentityProviderId): Boolean = key.slug == id

    /** Authorization URL plus the state and PKCE verifier the callback must echo. */
    fun authorizationRequest(returnTo: String): AuthorizationRequest

    /** Exchange the code, verify the ID token, return provider-neutral claims. */
    suspend fun exchange(code: String, verifier: String): IdentityClaims

    /** Provider logout URL, or null when the provider has no RP-initiated logout. */
    fun logoutUrl(returnTo: String): String?
}
```

`IdentityClaims` is the abstraction boundary. It carries no vendor shape:

```kotlin
// models/domain/auth/IdentityClaims.kt
data class IdentityClaims(
    val subject: String,            // the provider's `sub`
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val upstreamProvider: String?,  // "google" | "apple" | "password"
    val upstreamSubject: String?,   // the IdP's own sub — survives a vendor swap
)
```

`upstreamProvider` / `upstreamSubject` are the single genuinely Auth0-shaped
concern: Auth0 encodes the connection into `sub` as `google-oauth2|1234`,
`apple|001234`, `auth0|abc`. Parsing that is adapter-local — precisely where
vendor specifics belong under the no-leaky-abstractions rule. Recording the
upstream subject now is what makes a future migration a join on a stable key
rather than a fuzzy email match; retrofitting it later is not possible for
users who have already stopped signing in.

### Principal

```kotlin
// models/domain/auth/Principal.kt
sealed interface Principal {
    data object Anonymous : Principal
    data class User(val userId: UserId, val roles: Set<Role>) : Principal
    /** Schedulers, ETL, poller. Not constructible from a request. */
    data object System : Principal
}
```

Services take `Principal`. Ktor types stay in `route/`, per the layering rules
in `docs/backend-architecture.md`.

### Layer placement

```
models/domain/auth/     Principal, UserId, Role, IdentityClaims, AuthorizationRequest
clients/oidc/           OidcDiscoveryClient, OidcTokenClient, JwksVerifier
service/auth/           IdentityProvider (port), OidcIdentityProvider (adapter),
                        IdentityProviderRegistry, SessionService,
                        UserProvisioningService
repo/                   UserRepo, UserIdentityRepo, UserSessionRepo
route/auth/             AuthRoutes, session Authentication plugin, requireRole
config/                 AuthConfig
```

Dependency direction is unchanged: `route → service → repo, clients`.
`clients/oidc/` speaks HTTP to the provider and knows nothing of persistence;
`service/auth/` orchestrates and knows nothing of Ktor.

### Session model

DB-backed sessions in `user_session`, referenced by an opaque random token in an
`HttpOnly; Secure; SameSite=Lax` cookie. The cookie carries a random value; the
table stores its SHA-256. Not a JWT in `localStorage` — the frontend builds HTML
strings by template (`docs/frontend-components.md`), so a single missed
`escapeHtml` must not be able to exfiltrate a bearer token.

This costs one indexed lookup per authenticated request and zero for anonymous
browsing, which is the dominant traffic. If that ever matters, the escape hatch
is a short-TTL signed cookie revalidated periodically — not worth doing now.

DB-backed sessions also give real logout, revoke-on-credential-change, and — as
established in the stress test — a provider swap that does not log everyone out.

**CSRF.** `SameSite=Lax` plus an `Origin` check on state-changing routes. All
mutations are already `POST` with `Content-Type: application/json`, which browsers
will not emit cross-site from a form.

**PKCE / state round-trip.** Carried in a separate short-lived (10 min) signed
`HttpOnly` cookie rather than a server-side table — no schema, no cleanup job.

**Open redirect.** `returnTo` is validated as a same-origin *path* against an
allowlist, never accepted as an absolute URL.

### Schema

```sql
-- V47__auth.sql
CREATE TABLE app_user (
  id             BIGSERIAL PRIMARY KEY,
  email          CITEXT NOT NULL UNIQUE,
  email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  display_name   TEXT,
  status         TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_identity (
  id                BIGSERIAL PRIMARY KEY,
  user_id           BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  provider          TEXT   NOT NULL,   -- who we talked to: 'oidc'
  subject           TEXT   NOT NULL,   -- their `sub`
  upstream_provider TEXT,              -- 'google' | 'apple' | 'password'
  upstream_subject  TEXT,              -- the IdP's own sub
  email_verified_at TIMESTAMPTZ,       -- gates safe re-linking
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, subject)
);
CREATE INDEX user_identity_user_idx ON user_identity (user_id);
CREATE INDEX user_identity_upstream_idx ON user_identity (upstream_provider, upstream_subject)
  WHERE upstream_subject IS NOT NULL;

CREATE TABLE user_session (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  token_hash BYTEA  NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX user_session_user_idx ON user_session (user_id);

CREATE TABLE user_role (
  user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role    TEXT   NOT NULL CHECK (role IN ('admin')),
  PRIMARY KEY (user_id, role)
);
```

`user_credential` (encrypted per-user Slack / rec.gov secrets) is deferred to the
notification-credentials work; it is not needed to sign in.

New tables must be added to the jOOQ `includes` allowlist at
`backend/build.gradle.kts:248`, or `JooqCodegenDriftTest` fails the build.

**Account linking.** Only auto-link an identity to an existing `app_user` when
the provider asserts `email_verified`. Otherwise an attacker registers with a
victim's address on an unverified connection and inherits the account.

### Configuration

Two Auth0 tenants — dev and prod — because a prod client whose allowlist
contains `http://127.0.0.1:8765/auth/callback` is a token-exfiltration path.
Profiles `local` and `compose-local` share an origin and therefore share the dev
tenant.

The redirect URI is **derived** from the existing `web.root-url`, which already
holds the correct per-profile origin and is trailing-slash-normalised by
`WebAppConfig` (`WebAppConfig.kt:24`). One value, no drift, no mismatch bugs.

```yaml
# application.yaml — same env-backed shape as the slack/mapbox blocks
roadtrip:
  auth:
    issuer: "${ROADTRIP_AUTH_ISSUER:}"
    client-id: "${ROADTRIP_AUTH_CLIENT_ID:}"
    client-secret: "${ROADTRIP_AUTH_CLIENT_SECRET:}"
    session-key: "${ROADTRIP_SESSION_KEY:}"
    session-ttl: 30d
    cookie-secure: true
```

```yaml
# application-local.yaml
roadtrip:
  auth:
    cookie-secure: false    # http://127.0.0.1 will not set a Secure cookie
```

`AuthConfig.fromConfig` returns `null` when issuer or client-id is blank,
following `SlackConfig.fromConfig`'s precedent — a first-class *auth disabled*
state, not an error. With auth disabled, requests resolve to a configured dev
principal. A fresh clone, `make data-import`, and the Tilt buttons keep working
with no tenant provisioned.

Own the upstream Google OAuth client and Apple Service ID; hand those
credentials to Auth0 rather than using Auth0's shared social connections. Apple's
`sub` and its Hide-My-Email relay addresses are scoped to the Apple developer
team, so using someone else's app makes those users unmatchable on a future
migration.

Note that Apple rejects `http://` and `localhost` return URLs. Auth0 absorbs
this: Apple only ever sees Auth0's HTTPS callback, and Auth0 permits a localhost
redirect in the dev tenant. Sign in with Apple therefore works in local dev over
plain HTTP with no tunnel.

### Testing

`RouteTestApplication.kt:8` currently installs plugins and routes. Make principal
resolution injectable there so tests construct a `Principal` directly and CI never
touches a tenant:

```kotlin
internal fun Application.routeTestApplication(
    principal: Principal = Principal.User(TEST_USER_ID, emptySet()),
    body: Route.() -> Unit,
)
```

Existing route tests keep passing on the default. New tests pass a second user to
assert that another user's watch returns `404`, not `403` — ids must not leak.

## Delivery plan

Each numbered item is one PR through the RFC 0002 flow. PRs 1–4 are this RFC.

| # | Scope | Ships |
|---|---|---|
| 1 | `models/domain/auth/*`, `V47__auth.sql`, `UserRepo` / `UserIdentityRepo` / `UserSessionRepo`, jOOQ includes | dark — no HTTP surface |
| 2 | `AuthConfig`, `clients/oidc/*`, `IdentityProvider` port, `OidcIdentityProvider`, registry, `SessionService`, `UserProvisioningService` | dark — unit-tested against a fake IdP |
| 3 | `route/auth/*`: `/auth/login`, `/auth/callback`, `/auth/logout`, `/api/me`; session `Authentication` plugin; auth-disabled dev principal; frontend sign-in control; `credentials: 'same-origin'` in `web/api/http.js` | sign-in works end to end |
| 4 | `requireRole(Role.ADMIN)` on `/api/admin/*` and the availability dashboard; bootstrap admin from `ROADTRIP_BOOTSTRAP_EMAIL` | admin gated in-app |

PR 1 and PR 2 are independently reviewable and land without changing any
observable behaviour, which keeps the risky PR (3) small.

### Roadmap beyond this RFC

5. Watch ownership — `V48` owner column plus backfill, repo-level scoping,
   controller policy, `alerts.js` correction.
6. Email notifications from the verified `app_user.email`; drop
   `trigger_config.email_notify.to`.
7. Per-user Slack install in `user_credential`; ownership check on inbound
   interactivity, which today mutates watches by id with no such check.
8. Inbound per-IP rate limits on the cost-bearing anonymous routes
   (`/api/geocode`, `/api/route`, availability). `VendorRateLimiter` is an
   *outbound* governor and does not cover this.
9. Per-user rec.gov / ATC — separate project.

## Rationale

**Why terminate the vendor at the callback rather than pass its JWT around?**
Because it makes the session ours: revocable, swappable, and unaffected by a
provider migration. The alternative — validating an Auth0 JWT on every request —
puts the vendor's token format into every layer and makes a swap a rewrite.

**Why a generic OIDC adapter instead of an Auth0 SDK?** The SDK would give
marginally faster setup and permanent coupling. Auth0 is standards-compliant, so
the generic adapter costs little more and leaves `auth0` appearing only in
config. It also means Okta, Keycloak, and WorkOS are drop-in.

**Why one aggregator instead of integrating Google, Apple, and password
directly?** Apple alone justifies it: an ES256 client secret requiring rotation
roughly every six months, `form_post` response mode, a name returned only on
first authorization, and no localhost redirect for local dev. Password storage
would also become ours. Auth0 normalises all of it into one OIDC flow.

**Why DB sessions rather than signed stateless cookies?** Revocation. A stateless
cookie cannot be invalidated before expiry, which is unacceptable for logout and
for credential changes. The cost is one indexed lookup on authenticated requests
only.

**Why `404` rather than `403` for another user's resource?** `403` confirms the
id exists.

## Unresolved questions

1. **Password hash export.** Confirm Auth0's export policy in writing before
   committing. It is the only thing that makes a future migration of
   password-connection users possible — hosted-UI auth means we never see a
   plaintext password, so lazy re-hashing on login is unavailable. Options
   otherwise reduce to a forced reset for that cohort.
2. **Session TTL and idle timeout.** 30d absolute is proposed; no idle timeout.
3. **Bootstrap admin.** `ROADTRIP_BOOTSTRAP_EMAIL` granting `admin` on first
   sign-in is proposed. Simple, and adequate for a single-operator deployment.
4. **Sign-in UI placement.** Topbar control versus a dedicated page. Frontend
   decision, does not block PRs 1–2.
5. **Instrumentation.** Track the connection mix (`upstream_provider`) from day
   one. The cost of any future migration is set almost entirely by what fraction
   of users are password-only, and that number is worth knowing before it
   matters.

## Decision log

| # | Date | Decision | Rationale |
|---|---|---|---|
| 1 | 2026-07-26 | Auth0 as the identity provider. | Google + Apple + password behind one OIDC flow; normalises Apple's client-secret rotation, `form_post` mode, and localhost restriction. |
| 2 | 2026-07-26 | Auth0 is configured into a **generic OIDC adapter**, not given its own adapter class. | Keeps the vendor name in YAML and out of Kotlin; makes any OIDC provider a config change. |
| 3 | 2026-07-26 | First-party DB-backed session cookie; the vendor token never leaves the callback. | Revocable logout, and a provider swap that does not log every user out. |
| 4 | 2026-07-26 | Record `upstream_provider` / `upstream_subject` alongside the aggregator's `sub`. | Makes a future migration a join on a stable key. Cannot be retrofitted for lapsed users. |
| 5 | 2026-07-26 | Separate Auth0 tenants for dev and prod; redirect URI derived from `web.root-url`. | A prod client allowing `localhost` is a token-exfiltration path. Deriving the URI removes redirect-mismatch drift. |
| 6 | 2026-07-26 | Own the upstream Google client and Apple Service ID; do not use Auth0's shared social connections. | Apple's `sub` and relay emails are Apple-team-scoped; a borrowed app makes those users unmatchable on migration. |
| 7 | 2026-07-26 | Auth disabled is a first-class state (`AuthConfig.fromConfig` returns null). | Fresh clone, CI, and Tilt work with no tenant provisioned — follows `SlackConfig`'s precedent. |
| 8 | 2026-07-26 | rec.gov / ATC per-user is out of scope. | The companion's single Chromium profile is the credential; multiuser needs a profile pool. Would triple the timeline. |
