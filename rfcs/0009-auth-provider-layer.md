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
neither. The proposal is a **generic, standards-based OIDC architecture**; the
vendor is a configuration value. Auth0 and WorkOS are both supported targets and
either can be selected — or replaced — without touching Kotlin outside one small,
named seam. The vendor's token terminates at a single callback route and every
layer downstream sees a `Principal` domain value.

This RFC delivers the auth layer as **new surface area only**. It does not modify
a single existing route. Classifying which surfaces require which principal, and
introducing the Ktor middleware that injects and enforces that context, is a
deliberate follow-up pass.

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
- A `Principal` domain type exists — `Anonymous`, `User`, `System` — and is
  resolvable from a session.
- **The vendor is selected by configuration.** Auth0 and WorkOS are both
  first-class targets; neither is baked into the code.
- Local development and CI work with no tenant provisioned at any vendor.
- The layer adds routes and tables. It changes no existing behaviour.

## Non-Goals

Deferred to the **authz pass** (the immediate follow-up, see Roadmap):

- **Labelling surfaces** as anonymous / user / admin. The table below is context
  for reviewers, not a work item in this RFC.
- **Ktor authentication & authorization middleware** that injects `Principal`
  into the call context and enforces it declaratively.
- **Refactoring existing routes** to consume that context.
- **Admin role gating** on `/api/admin/*` and the availability dashboard.

Deferred further out:

- **Watch ownership**, **per-user notification credentials** (Slack install,
  verified email), and **per-user rec.gov / ATC** — the companion's persistent
  Chromium profile *is* the credential (`companion/src/recgovSession.js`), so
  multiuser there means a browser-profile pool.
- **Anonymous-then-claim watches.** Sign-in will be required to create a watch.
- **Organisations / teams / sharing.** Single-owner resources only.

## Proposal

### Target seam — context only, not this RFC's scope

Recorded so reviewers can see where the layer is heading. Nothing here is
implemented by this RFC.

| Surface | Eventual access |
|---|---|
| `/api/pois*`, `/api/route`, `/api/geocode`, `/api/pois/{id}/campsites*` | anonymous |
| static site, `?poi=` / `?route=` share links | anonymous |
| `/api/watches` CRUD | user, owner-scoped |
| `/api/me`, `/api/me/settings/*` | user, self only |
| `/api/admin/data/*` | admin role |
| `/api/availability/pollers\|runs\|snapshots`, force-poll | admin role |
| `/api/slack/interactivity` | signature + identity mapping |

### Vendor containment

The load-bearing decision. Auth0 and WorkOS are both standards-compliant OIDC
providers, so we build **one generic, discovery-driven OIDC adapter** and select
the vendor by issuer URL. No vendor SDK, no vendor name in Kotlin.

```
Browser → GET /auth/login          → 302 to provider (code + PKCE)
Browser → GET /auth/callback       → verify ID token, upsert identity,
                                      mint first-party session cookie
everything after                   → our cookie. The vendor is not in the path.
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

### The one vendor-specific seam

Roughly 95% of the flow is standards-based and genuinely shared: discovery, PKCE,
code exchange, JWKS fetch, ID-token signature and claim validation. Exactly one
thing differs between vendors — **how the upstream connection is spelled in the
token.** Auth0 encodes it into `sub` as `google-oauth2|1234`, `apple|001234`,
`auth0|abc`. WorkOS expresses it differently.

Rather than let that leak, it is isolated into a named, tested, swappable seam:

```
IdentityProvider (port)
  └── OidcIdentityProvider          generic; owns the entire flow
        └── ClaimsDialect           the only vendor-aware code
              ├── Auth0ClaimsDialect
              ├── WorkOsClaimsDialect
              └── StandardClaimsDialect     plain OIDC claims, no upstream detail
```

```kotlin
// service/auth/ClaimsDialect.kt
internal interface ClaimsDialect : Dispatchable<ClaimsDialectId> {
    /** Map verified ID-token claims into the provider-neutral domain shape. */
    fun toIdentityClaims(verified: VerifiedIdToken): IdentityClaims
}
```

A dialect is one small class plus a fixture-driven unit test. Adding a third
vendor is one file and one registry row — the same shape as adding an ETL vendor
or an availability provider.

`IdentityClaims` is the boundary type and carries no vendor shape:

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

Recording the upstream subject now is what makes a future vendor migration a join
on a stable key rather than a fuzzy email match. It cannot be retrofitted for
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

This RFC defines `Principal` and ships `SessionService.resolve(token): Principal?`,
called directly by `/api/me`. Generalising that call into a Ktor plugin installed
across the app is the follow-up pass — the plugin will wrap this same service
method, so the work is additive rather than a rewrite.

### Layer placement

```
model/domain/auth/      Principal, UserId, Role, UserStatus, IdentityClaims,
                        AuthorizationRequest, VerifiedIdToken
clients/oidc/           OidcDiscoveryClient, OidcTokenClient, JwksVerifier
service/auth/           IdentityProvider (port), OidcIdentityProvider,
                        ClaimsDialect + per-vendor dialects, registries,
                        SessionService, UserProvisioningService
repo/                   UserRepo, UserIdentityRepo, UserSessionRepo
route/auth/             AuthRoutes
config/                 AuthConfig
```

Dependency direction is unchanged: `route → service → repo, clients`.
`clients/oidc/` speaks HTTP and knows nothing of persistence; `service/auth/`
orchestrates and knows nothing of Ktor.

### Session model

DB-backed sessions in `user_session`, referenced by an opaque random token in an
`HttpOnly; Secure; SameSite=Lax` cookie. The cookie carries a random value; the
table stores its SHA-256. Not a JWT in `localStorage` — the frontend builds HTML
by string template (`docs/frontend-components.md`), so a single missed
`escapeHtml` must not be able to exfiltrate a bearer token.

This costs one indexed lookup per authenticated request and zero for anonymous
browsing, which is the dominant traffic. If it ever matters, the escape hatch is
a short-TTL signed cookie revalidated periodically — not worth doing now.

DB-backed sessions also give real logout, revoke-on-credential-change, and a
vendor swap that does not log every user out.

**CSRF.** `SameSite=Lax` plus an `Origin` check on state-changing routes. All
mutations are already `POST` with `Content-Type: application/json`, which browsers
will not emit cross-site from a form.

**PKCE / state round-trip.** Carried in a separate short-lived (10 min) signed
`HttpOnly` cookie rather than a server-side table — no schema, no cleanup job.

**Open redirect.** `returnTo` is validated as a same-origin *path* against an
allowlist, never accepted as an absolute URL.

### Schema

Email is `TEXT` with a unique index on `lower(email)` rather than `CITEXT`: the
citext extension is not installed (V1 installs only postgis), and an expression
index keeps normalization explicit at the boundary instead of hidden in a column
type.

```sql
-- V47__auth.sql
CREATE TABLE app_user (
  id             BIGSERIAL PRIMARY KEY,
  email          TEXT   NOT NULL,          -- UNIQUE via lower(email) index
  email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  display_name   TEXT,
  status         TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX app_user_email_lower_uq ON app_user (lower(email));

CREATE TABLE user_identity (
  id                BIGSERIAL PRIMARY KEY,
  user_id           BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  provider          TEXT   NOT NULL,   -- configured provider slug
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

`user_role` ships here even though nothing enforces it until the authz pass — the
table is trivial and having it in the same migration avoids a second schema
change for one column.

`user_credential` (encrypted per-user Slack / rec.gov secrets) is deferred to the
notification-credentials work; it is not needed to sign in.

New tables must be added to the jOOQ `includes` allowlist at
`backend/build.gradle.kts:248`, or `JooqCodegenDriftTest` fails the build.

**Account linking.** Only auto-link an identity to an existing `app_user` when
the provider asserts `email_verified`. Otherwise an attacker registers with a
victim's address on an unverified connection and inherits the account.

### Configuration

The vendor is a config value. Switching between Auth0 and WorkOS is an issuer
URL, a credential pair, and a dialect slug:

```yaml
# application.yaml — same env-backed shape as the slack/mapbox blocks
roadtrip:
  auth:
    provider: "${ROADTRIP_AUTH_PROVIDER:oidc}"   # auth0 | workos | oidc
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
state, not an error. A fresh clone, `make data-import`, and the Tilt buttons keep
working with no tenant provisioned anywhere.

**Two tenants per vendor, dev and prod.** A prod client whose allowlist contains
`http://127.0.0.1:8765/auth/callback` is a token-exfiltration path. Profiles
`local` and `compose-local` share an origin and therefore share the dev tenant.

The redirect URI is **derived** from the existing `web.root-url`, which already
holds the correct per-profile origin and is trailing-slash-normalised by
`WebAppConfig` (`WebAppConfig.kt:24`). One value, no drift, no mismatch bugs.

Own the upstream Google OAuth client and Apple Service ID; hand those credentials
to whichever vendor is selected rather than using the vendor's shared social
connections. Apple's `sub` and its Hide-My-Email relay addresses are scoped to the
Apple developer team, so a borrowed app makes those users unmatchable on a future
migration — and defeats the point of supporting two vendors.

Note that Apple rejects `http://` and `localhost` return URLs. Both vendors absorb
this: Apple only ever sees the vendor's HTTPS callback, and the vendor permits a
localhost redirect in a dev tenant. Sign in with Apple therefore works in local
dev over plain HTTP with no tunnel.

### Testing

CI never touches a tenant. `OidcIdentityProvider` is tested against a fake
discovery document and a locally-signed ID token; each `ClaimsDialect` is tested
against captured token fixtures per vendor.

`RouteTestApplication.kt:8` currently installs plugins and routes and needs no
change in this RFC — the auth routes are additive and tested in isolation. It is
the authz pass, when middleware becomes global, that will need principal
injection there.

## Delivery plan

Each numbered item is one PR through the RFC 0002 flow.

| # | Scope | Ships |
|---|---|---|
| 1 | `models/domain/auth/*`, `V47__auth.sql`, `UserRepo` / `UserIdentityRepo` / `UserSessionRepo`, jOOQ includes | dark |
| 2 | `AuthConfig`, `clients/oidc/*`, `IdentityProvider` port, `OidcIdentityProvider`, `ClaimsDialect` + Auth0/WorkOS/standard dialects, registries, `SessionService`, `UserProvisioningService` | dark — unit-tested against a fake IdP |
| 3 | `route/auth/*`: `/auth/login`, `/auth/callback`, `/auth/logout`, `/api/me`; auth-disabled dev principal; frontend sign-in control; `credentials: 'same-origin'` in `web/api/http.js` | sign-in works end to end |

**This plan adds surface area and modifies no existing route.** PRs 1 and 2 land
with zero observable behaviour change. PR 3's only externally visible effect is
four new endpoints and a sign-in control. Nothing that works today starts
requiring a session.

### Roadmap beyond this RFC

**Next: the authz pass.** Label every surface, introduce Ktor authentication and
authorization middleware that injects `Principal` into the call context, refactor
existing routes onto it, and gate `/api/admin/*` and the availability dashboard by
role. Deserves its own RFC — it touches every route file, and the enforcement
default (deny vs. allow) is the decision worth arguing about in writing.

Then, in rough order:

- Watch ownership — `V48` owner column plus backfill, repo-level scoping,
  controller policy, `alerts.js` correction.
- Email notifications from the verified `app_user.email`; drop
  `trigger_config.email_notify.to`.
- Per-user Slack install in `user_credential`; ownership check on inbound
  interactivity, which today mutates watches by id with no such check.
- Inbound per-IP rate limits on the cost-bearing anonymous routes
  (`/api/geocode`, `/api/route`, availability). `VendorRateLimiter` is an
  *outbound* governor and does not cover this.
- Per-user rec.gov / ATC — separate project.

## Rationale

**Why a generic OIDC adapter instead of a vendor SDK?** The SDK gives marginally
faster setup and permanent coupling. Both candidate vendors are
standards-compliant, so the generic adapter costs little more and leaves the
vendor name in config. Okta, Keycloak, and Supabase-behind-an-OIDC-shim become
drop-in.

**Why support two vendors rather than picking one?** Because the cost of doing so
is one `ClaimsDialect` class, and the benefit is that the choice stays reversible
past the point where migration would otherwise be expensive. It also forces the
abstraction to be real: an interface with one implementation is a guess, and
maintaining two keeps the boundary honest.

**Why terminate the vendor at the callback rather than pass its JWT around?** It
makes the session ours: revocable, swappable, and unaffected by a provider
migration. Validating a vendor JWT on every request puts the vendor's token format
into every layer and makes a swap a rewrite.

**Why one aggregator instead of integrating Google, Apple, and password
directly?** Apple alone justifies it: an ES256 client secret requiring rotation
roughly every six months, `form_post` response mode, a name returned only on first
authorization, and no localhost redirect for local dev. Password storage would
also become ours.

**Why DB sessions rather than signed stateless cookies?** Revocation. A stateless
cookie cannot be invalidated before expiry, which is unacceptable for logout and
credential changes. The cost is one indexed lookup on authenticated requests only.

**Why split authz into a separate pass?** The two concerns fail differently.
Getting authn wrong means nobody can sign in — loud and immediate. Getting authz
wrong means the wrong person reads someone's data — silent. They deserve separate
review attention, and bundling them would produce one PR touching every route file
in the repo, reviewed under the fatigue of a large diff.

## Unresolved questions

1. **Vendor selection.** Auth0 and WorkOS are both viable; the architecture does
   not depend on the answer, and PRs 1–2 can land before it is made. Inputs worth
   gathering: current pricing at expected MAU, and question 2 below.
2. **Password hash export.** Ask both vendors, in writing, before committing. It
   is the only thing that makes a future migration of password-connection users
   possible — hosted-UI auth means we never see a plaintext password, so lazy
   re-hashing on login is unavailable. Otherwise the fallback is a forced reset
   for that cohort. This may well be the deciding factor between the two.
3. **WorkOS claim shape.** `WorkOsClaimsDialect` needs its upstream-connection
   mapping confirmed against a real token during PR 2. If WorkOS does not expose
   the upstream IdP subject, that dialect populates `upstreamProvider` only and
   the migration story for its users falls back to verified email.
4. **Session TTL and idle timeout.** 30d absolute proposed; no idle timeout.
5. **Bootstrap admin.** `ROADTRIP_BOOTSTRAP_EMAIL` granting `admin` on first
   sign-in is proposed. Adequate for a single-operator deployment. Not consumed
   until the authz pass.
6. **Sign-in UI placement.** Topbar control versus a dedicated page. Frontend
   decision, does not block PRs 1–2.
7. **Instrumentation.** Track the connection mix (`upstream_provider`) from day
   one. The cost of any future migration is set almost entirely by what fraction
   of users are password-only, and that number is worth knowing before it matters.

## Decision log

| # | Date | Decision | Rationale |
|---|---|---|---|
| 1 | 2026-07-26 | Architecture is **generic OIDC**; the vendor is a config value. Auth0 and WorkOS are both first-class targets. | Both are standards-compliant, so supporting either costs one small class. Keeps the choice reversible and keeps the abstraction honest. |
| 2 | 2026-07-26 | Vendor differences are confined to a `ClaimsDialect` seam. | ~95% of the flow is standards-based. Only the upstream-connection encoding differs; isolating it keeps vendor shape out of every other layer. |
| 3 | 2026-07-26 | First-party DB-backed session cookie; the vendor token never leaves the callback. | Revocable logout, and a vendor swap that does not log every user out. |
| 4 | 2026-07-26 | Record `upstream_provider` / `upstream_subject` alongside the vendor's `sub`. | Makes a future migration a join on a stable key. Cannot be retrofitted for lapsed users. |
| 5 | 2026-07-26 | Separate dev and prod tenants; redirect URI derived from `web.root-url`. | A prod client allowing `localhost` is a token-exfiltration path. Deriving the URI removes redirect-mismatch drift. |
| 6 | 2026-07-26 | Own the upstream Google client and Apple Service ID; do not use a vendor's shared social connections. | Apple's `sub` and relay emails are Apple-team-scoped; a borrowed app makes those users unmatchable on migration, defeating multi-vendor support. |
| 7 | 2026-07-26 | Auth disabled is a first-class state (`AuthConfig.fromConfig` returns null). | Fresh clone, CI, and Tilt work with no tenant provisioned — follows `SlackConfig`'s precedent. |
| 8 | 2026-07-26 | **Surface labelling, authz middleware, route refactor, and admin gating are a separate follow-up pass.** | Authn fails loudly, authz fails silently; they deserve separate review. Bundling would produce one PR touching every route file. |
| 9 | 2026-07-26 | This RFC adds surface area only — no existing route is modified. | Nothing that works today starts requiring a session, so the layer can land incrementally without a flag day. |
| 10 | 2026-07-26 | rec.gov / ATC per-user is out of scope. | The companion's single Chromium profile is the credential; multiuser needs a profile pool. Would triple the timeline. |
| 11 | 2026-07-26 | `app_user.email` is `TEXT` + a unique index on `lower(email)`, not `CITEXT`. | The citext extension is not installed; V1 installs only postgis. An expression index keeps normalization explicit rather than hidden in a column type. |
| 12 | 2026-07-26 | `UserId` is a `@JvmInline value class`, the first in this codebase — every other id is a bare `Long`. | Ownership checks are the one place a wrong id is a security bug, not a correctness bug. Establishing it before the authz pass creates call sites is far cheaper than retrofitting. |
