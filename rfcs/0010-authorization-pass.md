---
title: Authorization pass
authors:
  - William Chen
created: 2026-07-27
last_updated: 2026-07-27
rfc_pr: TBD
status: Draft
---

# Proposal: Authorization pass

## Summary

RFC 0009 built identity: a request can be resolved to a `Principal`, and
`/api/me` proves it. Nothing consumes that. Every route is still as open as it
was before auth existed.

This RFC makes the app enforce access. It labels every surface, introduces Ktor
middleware that resolves a `Principal` once per request and makes it ambient,
and refactors existing routes onto it. It does not yet scope any resource to its
owner — watch ownership is the PR after, and it depends on this one.

The decision worth arguing is the **enforcement default**, and the answer this
RFC proposes is neither of the usual two: every route must *declare* its access
level, and a route that declares nothing fails the build rather than defaulting
either way.

## Motivation

Four concrete gaps, all live today.

1. **Watches are global.** `GET /api/watches` returns every user's watches and
   `POST /api/watches/{id}/delete` deletes anyone's. `web/topbar/alerts.js:7`
   still documents this as intended.

2. **Two unauthenticated send-mail surfaces**, not one. The known one is
   `trigger_config.email_notify.to`. The other is `POST /test/email`
   (`TestEmailRoutes.kt:36`), which takes an arbitrary `to` and sends through
   Resend with no auth at all — a single anonymous POST, no setup required.
   `POST /test/slack` is the same shape against the Slack bot token. Both are
   registered unconditionally in `registerKoinRoutes`.

3. **Admin has no in-app gate.** `/api/admin/data/*` and the availability
   dashboard — including `POST /api/availability/pollers/{id}/force`, which
   spends vendor quota — are protected only by a Cloudflare Zero Trust path rule
   (`AdminIngestRoutes.kt:60`). A tunnel misconfiguration exposes ingest control
   and force-poll to the internet.

4. **Slack interactivity mutates by id with no ownership check.**
   `SlackInteractivityHandler` pauses, resumes, and deletes watches. Signature
   verification proves *Slack* sent the request; it says nothing about whether
   that Slack user owns that watch.

Gap 1 needs watch ownership, which is the next RFC. Gaps 2 and 3 are fixed here.
Gap 4 needs both.

## Goals

- Every route declares its access level, and that declaration is checked
  mechanically rather than by reviewer attention.
- `Principal` is resolved once per request and available to any handler without
  each one re-reading a cookie.
- Admin surfaces are gated in-app by role, in addition to the edge rule.
- The unauthenticated send-mail and send-Slack surfaces are closed.
- Anonymous browsing is unchanged. No surface that is public today stops being
  public.

## Non-Goals

- **Watch ownership.** Scoping `availability_watch` to a user is the next RFC.
  This one gives that work the `Principal` it needs and stops there.
- **Per-user notification credentials**, per-user rec.gov, organisations.
- **Inbound rate limiting.** Related (it also protects anonymous surfaces) but a
  different mechanism with different failure modes; separate change.
- **Slack identity mapping.** Gap 4 needs watch ownership first.

## The central decision: what does an unlabelled route do?

The usual framing is deny-by-default versus allow-by-default. Both are wrong
here, for opposite reasons.

**Deny by default** — every route requires a principal unless marked anonymous.
The standard advice, and it fits an app whose surfaces are mostly private. This
app is the opposite: browsing *is* the product, and roughly three-quarters of
routes are legitimately anonymous. The annotation burden would land on the common
case, and the failure mode of a forgotten marker is that public browsing breaks —
loud, but a user-facing outage rather than a caught mistake.

**Allow by default** — routes are open unless gated. Matches the app's actual
shape and annotates only the few private routes. But the failure mode is the one
that matters: a new watch-shaped route that forgets its gate leaks silently, and
nothing tells anyone.

Neither default is acceptable, because the question "what happens when someone
forgets" has no good answer when the answer is a *runtime* behaviour.

### Proposed: declare-or-fail-the-build

Make the access level a required per-route declaration, and assert completeness
in a test that walks the routing tree. An undeclared route is not denied and not
allowed — it fails CI.

This converts the forgetting case from a silent leak or a production outage into
a red build, which is the only outcome that is actually cheap. It also puts the
decision at authoring time, where the person adding the route has the context to
make it.

The mechanism already has a sibling in this codebase. `describeApi` is a
per-route annotation returning `Route` (`route/common/RouteOpenApi.kt`):

```kotlin
get("/search") { … }
    .describeApi("pois", "Search POIs")
    .access(Anonymous)
```

`access(...)` attaches a route attribute; the middleware reads it to decide
whether to demand a principal; and `RouteAccessCoverageTest` walks the tree and
fails on any leaf without one. Precedent for build-time completeness checks is
established — `JooqCodegenDriftTest` does the same job for the schema.

## Surface inventory

Complete as of `b469bf77` (master after RFC 0009 merged). This table is the work
item, not context — every row becomes an `.access(...)` declaration.

| Route | Access | Note |
|---|---|---|
| `GET /api/health` | anonymous | |
| `GET /openapi.json`, `/api/docs` | anonymous | |
| `GET /api/pois`, `/api/pois/search`, `/api/pois/{id}` | anonymous | |
| `POST /api/pois/on-route` | anonymous | Mapbox-cost-bearing |
| `GET /api/route` | anonymous | Mapbox-cost-bearing |
| `GET /api/geocode` | anonymous | Mapbox-cost-bearing |
| `GET /api/pois/{id}/campsites` | anonymous | |
| `GET /api/pois/{id}/campsites/availability` | anonymous | vendor-cost-bearing |
| static site, `/availability`, `/watches` | anonymous | |
| `GET /auth/login`, `/auth/callback`, `/auth/logout` | anonymous | must be, to sign in |
| `GET /api/me` | anonymous | answers 200 either way by design |
| `GET/POST /api/watches`, `/{id}`, `/{id}/modify`, `/{id}/delete` | **user** | owner-scoping is the next RFC |
| `POST /api/admin/data/import[/{target}]` | **admin** | |
| `GET /api/admin/data/runs[/{id}]`, `/status` | **admin** | |
| `GET /api/availability/pollers[/summary,/{id}/runs]` | **admin** | ops surface |
| `POST /api/availability/pollers/{id}/force` | **admin** | spends vendor quota |
| `GET /api/availability/runs`, `/changes`, `/changes/summary` | **admin** | ops surface |
| `POST /test/email`, `POST /test/slack` | **admin** | see below |
| `POST /api/slack/interactivity` | signature | not principal-based; see below |

Two rows deserve their own note.

**The `/test/*` routes** are the sharpest live problem in this list, and the
cheapest to fix. They send real mail and real Slack messages to
caller-supplied destinations with no authentication. Gating them behind `admin`
is correct, but they should arguably also be registered only when a
`roadtrip.diagnostics` flag is set, so production does not carry a
send-anything endpoint at all. Proposed: both.

**`/api/slack/interactivity`** is authenticated by HMAC signature, not by
session, and must stay that way — Slack has no cookie. It gets an
`.access(Signed)` declaration so it is explicitly labelled rather than
accidentally uncovered. Its ownership gap (gap 4) stays open until watches have
owners.

## Proposal

### Access levels

```kotlin
// models/domain/auth/RouteAccess.kt
sealed interface RouteAccess {
    /** No principal required. The caller may still be signed in. */
    data object Anonymous : RouteAccess
    /** Any signed-in, active user. */
    data object User : RouteAccess
    /** A signed-in user holding [role]. */
    data class HasRole(val role: Role) : RouteAccess
    /** Authenticated by request signature, not by session (Slack). */
    data object Signed : RouteAccess
}
```

`Signed` exists so the coverage test cannot be satisfied by pretending the Slack
webhook is anonymous. It is authenticated — just not by us.

### Middleware

One Ktor plugin, installed once:

1. Resolve the session cookie to a `Principal` and put it in call attributes.
   Runs for every request, including anonymous ones — `Principal.Anonymous` is a
   value, not an absence.
2. Read the route's declared `RouteAccess` and enforce it: `401` with no
   principal, `403` with a principal lacking the role.

Resolution and enforcement are deliberately one plugin but two phases: a handler
on an `Anonymous` route still gets a populated `Principal`, which is what lets a
public page know who you are without gating it.

`call.principal()` becomes the single accessor. Services keep taking `Principal`
as a parameter — the ambient value stops at the route layer, per the layering
rules in `docs/backend-architecture.md`.

### Status codes

`401` when unauthenticated, `403` when authenticated but insufficient. For a
resource that exists but belongs to someone else, `404` — but that is the next
RFC's concern, since nothing is owner-scoped yet.

### Bootstrap admin

`ROADTRIP_BOOTSTRAP_EMAIL`: on sign-in, if the verified email matches, grant
`Role.ADMIN`. Idempotent, and the only way to get the first admin without a
manual `INSERT`. Logged loudly when it fires.

## Delivery plan

| # | Scope | Ships |
|---|---|---|
| 1 | `RouteAccess`, the plugin, `call.principal()`, `.access(...)`, `RouteAccessCoverageTest` — plus declarations on the anonymous majority | no behaviour change; coverage test goes green |
| 2 | `user` on `/api/watches/*`; `admin` on `/api/admin/*`, the availability dashboard, and `/test/*`; diagnostics flag on `/test/*`; bootstrap admin | enforcement begins |

PR 1 is deliberately inert: it declares every route at its *current* access level,
so the tree is fully labelled before anything starts refusing requests. PR 2 then
changes only the handful of labels that should tighten. That way the risky diff is
small and readable, instead of one PR that both touches every route file and
changes behaviour.

## Rationale

**Why not deny-by-default, given it is the standard advice?** Because the advice
assumes private-by-nature surfaces. Here the public surfaces are the product, and
a default that has to be overridden three times out of four is a default that
will be overridden carelessly. The build check gets the safety property —
forgetting is caught — without inverting the annotation burden.

**Why label anonymous routes at all, rather than only the private ones?** Because
the coverage test is the entire safety mechanism, and it only works if silence is
never a valid state. An unlabelled route must be distinguishable from a
deliberately-public one.

**Why resolve the principal even on anonymous routes?** So a public page can
render "you have 3 watches" without becoming a gated page. Resolution and
enforcement are separate concerns and are kept separate.

**Why gate `/test/*` behind a flag as well as a role?** Defence in depth for a
route whose entire purpose is to send arbitrary messages to arbitrary
destinations. A role check is one bug away from being bypassed; a route that is
not registered cannot be called.

## Unresolved questions

1. **Should `/test/*` exist in production at all?** Proposed: registered only
   under a diagnostics flag, off by default. The alternative is deleting them and
   relying on a local run for smoke-testing the senders.
2. **Cloudflare rule after in-app gating.** Keep it as belt-and-braces, or drop
   it once roles are enforced? Keeping it means admin sign-in works only through
   the tunnel, which may be the desired property.
3. **Does `/api/me` stay anonymous-accessible?** Proposed yes — the frontend
   calls it on every page load to decide whether to render sign-in.
4. **Session sweep.** `SessionService.deleteExpired()` exists and nothing calls
   it. Fits the existing scheduler; unrelated to authz but adjacent and cheap.

## Decision log

| # | Date | Decision | Rationale |
|---|---|---|---|
| 1 | 2026-07-27 | An **unlabelled route fails the build**, rather than defaulting to allow or deny. | Deny-by-default inverts the annotation burden in an app that is mostly public, and its failure mode is a user-facing outage. Allow-by-default fails silently, which is worse. A coverage test makes forgetting cheap to catch. |
| 2 | 2026-07-27 | Access is declared per route via `.access(...)`, alongside the existing `.describeApi(...)`. | Reuses an established per-route annotation shape rather than inventing a parallel registry that could drift from the routing tree. |
| 3 | 2026-07-27 | Principal resolution and access enforcement are separate phases of one plugin. | An anonymous route still needs to know who the caller is, so a public page can render signed-in state without becoming gated. |
| 4 | 2026-07-27 | `RouteAccess.Signed` is a distinct level for the Slack webhook. | It is authenticated, just not by session. Without its own level the coverage test would have to accept it as anonymous, which is untrue and would hide it. |
| 5 | 2026-07-27 | `/test/email` and `/test/slack` get an admin role **and** a diagnostics registration flag. | They send arbitrary messages to arbitrary destinations with no auth today. A route that is not registered cannot be called, which is a stronger guarantee than a role check. |
| 6 | 2026-07-27 | Ships as two PRs: label everything at current access, then tighten the few that change. | Keeps the behaviour-changing diff small and readable instead of combining it with a change that touches every route file. |
