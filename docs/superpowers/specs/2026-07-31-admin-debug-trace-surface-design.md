# Admin Debug / Trace Surface — Scope

**Date:** 2026-07-31
**Status:** Scoping. No code written; this document is the work item.
**Context:** RFC 0009 landed identity. RFC 0010 PR 1 landed `RouteAccess`
declarations, the `roadtripAuthorization` plugin, and `call.principal()`.
`docs/observability.md` describes a full Grafana/Loki/Tempo pipeline that only
operators can see. This scopes making that visibility available **inside the
product**, to signed-in admins.

## The ask

> Surface debugging / trace logging if the logged-in user is an admin.
> (We also don't have a way to assign roles right now.)

Two things, and the second blocks the first. Taken literally the ask is one
feature; in this codebase it decomposes into **five separable tracks**, three of
which are prerequisites that already have owners in RFC 0010.

---

## What already exists

Worth stating precisely, because most of the plumbing is built and the gap is
narrower than it looks.

### Roles: modelled, persisted, readable — never written

| Piece | Where | State |
|---|---|---|
| `Role.ADMIN` enum | `model/domain/auth/Role.kt` | done |
| `user_role` table, `CHECK (role IN ('admin'))` | `V47__auth.sql` | done |
| `UserRepo.grantRole` / `revokeRole` / `rolesFor` | `repo/UserRepo.kt:110-139` | done, idempotent (`onConflictDoNothing`) |
| `Principal.User.roles`, `Principal.hasRole` | `model/domain/auth/Principal.kt` | done |
| `RouteAccess.HasRole(role)` + `check()` → 401/403 | `model/domain/auth/RouteAccess.kt` | done |
| `.access(...)` enforcement interceptor | `route/common/RouteAccessDsl.kt:50` | done |
| Roles resolved per request | `SessionService.resolve` → `userRepo.findById` → `rolesFor` | done, **fresh each request** — a granted role takes effect without re-login |
| Roles exposed to the client | `/api/me` → `MeResponseDto.roles` | done |

**Nothing calls `grantRole`.** No production code path can produce an admin, so
`RouteAccess.HasRole(Role.ADMIN)` has zero call sites and every ops surface is
still `RouteAccess.Anonymous`. That is the blocker the ask flags.

### Enforcement: declared everywhere, tightened nowhere

`.access(...)` declarations across `route/`: **37 `Anonymous`, 6 `User`
(settings only), 1 `Signed`, 0 `HasRole`.** RFC 0010's delivery plan calls this
PR 1 ("deliberately inert"); PR 2 — which flips admin surfaces and adds
`ROADTRIP_BOOTSTRAP_EMAIL` — has not landed. Currently anonymous but listed as
admin in the RFC's own inventory:

- `POST /api/admin/data/import[/{target}]`, `GET /api/admin/data/runs|status`
  (`route/api/admin/AdminIngestRoutes.kt`) — protected only by a Cloudflare Zero
  Trust path rule, per the comment at `AdminIngestRoutes.kt:60`.
- All 12 declarations in `route/api/availability/AvailabilityDashboardRoutes.kt`,
  including `POST /api/availability/pollers/{id}/force`, which spends vendor
  quota.

### Trace data: rich, and none of it reaches the browser

- OTel Java agent injects `trace_id` / `span_id` into Logback MDC; Alloy promotes
  them to Loki structured metadata (`docs/observability.md`).
- `RoadtripAccessLogging` (`RoadtripRouting.kt:132`) emits per-request MDC:
  `http_method`, `http_path`, `http_status`, `http_duration_ms`, `http_remote`.
- `availability_fetch_call` is an explicit trace table — one row per upstream
  fetch a run made, with `outcome`, `duration_ms`, `error`
  (`repo/AvailabilityFetchCallRepo.kt`). `availability_run` and `ingest_runs` are
  the same shape for their domains.
- `AvailabilityCacheBlock` (`hit`, `age_seconds`, `ttl_seconds`) is **already in
  every availability response, for everyone** — precedent that a small amount of
  read-path introspection is considered non-sensitive here.
- `GrafanaConfig.rootUrl` exists so alerts can deep-link dashboards — reusable
  for "open this trace in Tempo".

So the raw material is there. What is missing is a **principal-aware seam that
carries it into an HTTP response**, and a place to render it.

---

## Tracks

Ordered by dependency. A, B are prerequisites; C, D, E are the feature.

### Track A — A way to make someone an admin *(blocker, ~half a day)*

RFC 0010 already decided this: `ROADTRIP_BOOTSTRAP_EMAIL` — on sign-in, if the
verified email matches, grant `Role.ADMIN`, idempotently, logged loudly.

Recommended shape, following the config conventions in `config/`:

- New `AdminConfig` under `roadtrip.admin` in `application.yaml`, with
  `bootstrap-emails: "${ROADTRIP_BOOTSTRAP_EMAILS:}"` — a **comma-separated
  list**, not a single value. Same cost to build, and a second admin (or an
  ops rotation) does not need a code change. Blank ⇒ `null` config object, the
  same "first-class disabled state" pattern `AuthConfig`/`SlackConfig` use.
- Granting happens in `service/auth/UserProvisioningService`, which is where the
  verified email is already known and where account-linking policy already
  lives. Gate on `email_verified` — the same rule that gates account linking
  (`UserProvisioningService` step 2). An unverified match must not grant.
- Emails compared case-insensitively against the already-lowercased stored
  address.
- Grant only; **never revoke** on removal from the list. Silent de-admining on a
  config typo is a worse failure than a stale grant, and revocation has a
  deliberate path (Track F).

**Open decision:** does the bootstrap grant belong at sign-in, or at boot? Boot
is more predictable (admin exists before anyone signs in) but requires the user
row to exist, which it does not before first sign-in. Sign-in is proposed.

**Deliberately out of scope for A:** an in-app role-management UI. See Track F.

### Track B — Gate the admin surfaces that exist *(RFC 0010 PR 2, ~1 day)*

Flip the ~14 declarations listed above from `Anonymous` to
`HasRole(Role.ADMIN)`, and hide the `/availability` dashboard entry point from
non-admins in the frontend. This is not new design work — it is RFC 0010's
second PR, and it is a hard dependency for Tracks C–E: there is no point adding
an admin-only debug payload while the ops dashboard next to it is world-readable.

Note the RFC's unresolved question 2 (keep the Cloudflare rule or drop it) is
still open and should be answered here: keeping it means admin sign-in works
only through the tunnel.

The `/test/email` and `/test/slack` routes named in the RFC no longer exist —
per-user test sends now live behind `RouteAccess.User` in `SettingsRoutes`. That
part of PR 2 is already satisfied.

### Track C — Request correlation into the browser *(~1 day)*

The cheapest genuinely useful thing: let an admin get from "this page looked
wrong" to the exact Loki/Tempo record.

- **`X-Trace-Id` response header** on `/api/*`, sourced from the OTel span
  context (or MDC `trace_id`). Must degrade to absent when the agent is not
  attached — `make run` without the agent is a supported mode
  (`docs/observability.md`), so the header is best-effort, never a hard
  dependency.
- **Decision to make:** emit to everyone, or only to admins? **Proposed:
  everyone.** A trace id is an opaque correlator that is useless without Grafana
  access, and emitting it unconditionally means an anonymous bug report can
  carry one. The *rendering* is what gets gated. The alternative (admin-only
  header) buys little and makes the header's presence itself a role oracle.
- **`/api/me` gains the admin's tooling context**: an `is_admin` convenience
  boolean (derivable from `roles`, but every consumer would otherwise
  re-derive it) and, when the caller is an admin, `grafana_root_url` from
  `GrafanaConfig` so the client can build `explore?...traceId=` links. Non-admins
  get neither field — the client cannot construct a deep link it should not have.

### Track D — Server-side debug payload on the read path *(~2–3 days, the real work)*

This is where "debug logging" becomes product-visible: an admin asks *why* an
availability answer looks the way it does, and the response tells them.

**Trigger:** `?debug=1` on the availability read endpoints
(`GET /api/pois/{id}/campsites/availability` first; the poller/run dashboard
endpoints second). When the caller is not an admin the parameter is **ignored,
not refused** — a 403 would turn the parameter into a role oracle on an
otherwise anonymous endpoint.

**Content** — all of it already computed, none of it currently returned:

- which provider answered, and every failover attempt that did not
  (`FailoverAvailabilityFetcher`, `ProviderCooldownTracker`)
- cache decision beyond the existing `hit/age/ttl`: which cache tier, what key,
  why a miss
- upstream call timings and outcomes for this request
- the `availability_run` / `availability_fetch_call` rows behind the served data
  (run id, fetched-at, outcome, `duration_ms`, `error`)
- date-window resolution (`AvailabilityDateResolver`, `AvailabilityFreshness`) —
  requested window vs served window vs cutoff
- vendor rate-limit bucket state at request time (`VendorRateLimitConfig`,
  `vendor_rate_limit_bucket`)
- the `trace_id`, so the payload joins to Tempo

**Layering — the part to get right.** Per `docs/backend-architecture.md`, routes
are the HTTP shell and services own orchestration, so the debug data must be
produced *by the service* as a domain value and merely serialized by the route.
Proposed seam, mirroring how `Principal` is passed:

```
model/domain/diagnostics/DebugTrace.kt      // domain value: ordered typed events
model/domain/diagnostics/TraceSink.kt       // interface + NoopSink object + RecordingSink
model/api/AvailabilityDebugDto.kt           // the wire shape, admin-only
```

- `CampsiteAvailabilityController` / `CampsiteAvailabilityService` take a
  `TraceSink` parameter, exactly as they take a `Principal`. Ktor stays in
  `route/`; the service never learns who asked.
- The route decides which sink to pass: `RecordingSink` when
  `call.principal().hasRole(Role.ADMIN) && debug=1`, `NoopSink` otherwise. **One
  choke point, one place to test.** No-op means zero cost on the hot path, which
  matters because this is the vendor-cost-bearing endpoint.
- Provider adapters record through the sink as structured events (name +
  string map), **not** vendor types — surfacing an Aspira/rec.gov response shape
  through the debug DTO would be exactly the leaky abstraction
  `docs/reservation-providers.md` and the `ReservationProvider` port exist to
  prevent.
- Per the no-magic-constants rule: the `debug` parameter name, the event names,
  and any cap on recorded events are `const val`.

**Cap the payload.** A `RecordingSink` with no bound is a memory footgun on a
fan-out request across many campsites. Named constant, and the DTO reports when
it truncated.

**Caching:** `/api/*` already returns `Cache-Control: no-store`
(`support/CachePolicy.kt:17`), so an admin-only payload cannot be cached and
replayed to an anonymous visitor by the origin. Worth confirming no Cloudflare
rule caches `/api/*` ahead of the origin — a one-line check, not a design
question.

### Track E — Frontend debug surface *(~2 days)*

- `web/topbar/state.js` gains `isAdmin` from the `/api/me` response it already
  fetches (`web/api/auth-api.js`). Nothing else in `web/` reads `roles` today.
- A new domain area `web/debug/` (peer to `web/account/`, `web/watches/`),
  following the `component.js` / `component-template.js` / `component.css`
  contract in `docs/frontend-components.md`. Composed from existing design-system
  primitives — `data-table` for event lists, `tabs` for request/cache/provider,
  `banner` for degraded states. **Check `web/design-system/gallery.html` before
  adding any new primitive**, and add a gallery section for anything new.
- Toggle: `?debug=1` in the URL, persisted in `localStorage`, mounted only when
  `isAdmin`. Server-side gating stays authoritative — the client-side check is
  presentation, never enforcement.
- Deep links out to Grafana Explore / Tempo built from `grafana_root_url` +
  `X-Trace-Id`.
- `web/api/http.js` is the natural place to capture `X-Trace-Id` off responses,
  since every call already funnels through it. That means threading the header
  out of helpers that currently return parsed JSON only — a small but real
  refactor across `jsonGetOk` / `jsonPostOk` / etc., worth scoping explicitly
  rather than discovering mid-PR.

### Track F — Role management UI *(deferred, ~1–2 days)*

`grantRole`/`revokeRole` exist; an admin-gated `GET/POST /api/admin/users/{id}/roles`
plus a panel in the settings modal is a small, self-contained follow-up. Track A
makes the first admin; F makes the second one without an env var deploy. Not
required for the debug surface — listed so "we can't assign roles" is closed for
good rather than just unblocked.

---

## Delivery plan

| # | Track | Blocks | Rough size |
|---|---|---|---|
| 1 | A — bootstrap admin grant | everything | ~0.5d |
| 2 | B — RFC 0010 PR 2 admin gating | C, D, E | ~1d |
| 3 | C — `X-Trace-Id` + `/api/me` admin context | E | ~1d |
| 4 | D — `TraceSink` seam + availability debug payload | E | ~2–3d |
| 5 | E — `web/debug/` panel | — | ~2d |
| 6 | F — role management UI | — | ~1–2d, deferred |

1 and 2 are worth shipping on their own regardless of whether C–E proceed: today
an ungated `POST /api/availability/pollers/{id}/force` will spend vendor quota
for anyone who finds it, and the only thing standing in front of it is a
Cloudflare path rule.

## Risks

- **The debug payload becomes an exfiltration path.** Mitigated by a single
  choke point (route picks the sink) plus a test that asserts anonymous and
  non-admin `?debug=1` responses are byte-identical to non-debug ones.
- **Trace collection on the hot path.** `NoopSink` must be genuinely free; the
  availability read path is vendor-cost-bearing and already latency-sensitive.
- **Scope creep into "an admin console".** This is a read-only diagnostic
  surface. Mutating ops actions stay where they are, behind Track B's gate.
- **OTel agent absent** (`make run`, some CI paths) ⇒ no trace ids. Every surface
  degrades to "unavailable", never errors.
- **Role check drift.** Two role checks (server enforcement, client rendering)
  can disagree. Server is authoritative; the client check only decides whether to
  mount UI.

## Open questions

1. **Which surface is the actual pain?** The availability read path (why did this
   campsite show as free?) and the ops dashboards (why did this poll run fail?)
   are different products. Track D assumes the read path is first — worth
   confirming before it is built.
2. **Bootstrap: single email or list?** Proposed list, per Track A.
3. **`X-Trace-Id` to everyone, or admins only?** Proposed everyone.
4. **Does the debug surface cover write paths** — watch evaluation, trigger
   firing, alert dispatch — or read only? Read only is proposed; watch-evaluation
   tracing is a plausible follow-up and would reuse the same `TraceSink`.
5. **Cloudflare rule after Track B** — keep as belt-and-braces or drop? This is
   RFC 0010's unresolved question 2 and it needs an answer before B ships.
6. **Is this an RFC?** Tracks C–E introduce a new cross-cutting seam
   (`TraceSink`) and a new frontend domain area. That is arguably RFC-sized under
   `rfcs/0002-pr-process.md`; Tracks A and B are already covered by RFC 0010 and
   need no new document.
