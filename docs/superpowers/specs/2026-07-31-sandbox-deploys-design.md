# Sandbox deploys — design

**Date:** 2026-07-31
**Status:** Design approved; ready for implementation planning.

## Problem

Every AI coding session or PR review benefits from a **live, working endpoint** a
reviewer (human or agent) can hit — not just a diff and green CI. Today there is
no way to stand up a running instance of an arbitrary branch. Prod is the only
deployed environment: a `workflow_run`-triggered job SSHes to a single host
(`mini-ca`) over Tailscale and runs `make run env=prod`, a docker-compose stack
fronted by a Cloudflare Tunnel at `roadtrip.floo.ca`.

We want a **`/sandbox`-style, on-demand** mechanism that stands up a throwaway
endpoint for **any ref — PR or not** — so both AI sessions and PR reviewers get a
clickable URL showing the change running against realistic data.

## Goals

- On-demand spin-up of a live endpoint for any git ref, via a PR comment **and** a
  CLI/`make` target (both thin wrappers over one script).
- Endpoint shows a **realistic map** (real POI catalog), so data/UI review is real.
- Support **user-specific features** via an "assume as user" switcher, without a
  real OIDC provider in the loop.
- Runs on the existing `mini-ca` host today, but the host is a **parameter** so the
  whole sandbox tier can move to a dedicated box with a config change, not a rewrite.
- **Consolidate the CI/CD flow**: prod deploy, sandbox spin-up, and local bring-up
  become one parameterized `deploy()` seam rather than three separate mechanisms.

## Non-goals

- Real OIDC login inside sandboxes. (The provider's callback allowlist is
  per-hostname across only a dev and a prod tenant — ephemeral hostnames can't
  share it cleanly. See "Auth & impersonation".)
- Migrating prod's ingress path in this project (kept as a documented later flip).
- Per-sandbox observability stack (Grafana/Loki/Tempo/Prometheus/Alloy) or the
  recgov-companion sidecar. Sandboxes run backend + postgres only.

## Key facts this design rests on

Established by investigation of the current codebase:

- **Public ingress** is a Cloudflare Tunnel → `roadtrip.floo.ca`, with per-hostname
  routing in Cloudflare Zero Trust. Prod is `cloudflared → backend:8765` directly.
- **No image registry today** — prod builds the backend fat jar on the host. CI
  (`docker-build` job) builds `roadtrip/backend` but pushes nothing.
- **`data/` is ~1.7 GB** — a full `make data-import` per sandbox is a non-starter.
- **Minimal serving stack = postgres + backend.** The backend serves the entire
  site (static + `/api/*`). The map renders with **no Mapbox/vendor keys** (free
  basemap tiles); Mapbox is only needed server-side for `/api/route` + `/api/geocode`,
  which degrade to 503 without it.
- **Auth can be disabled** (`ROADTRIP_AUTH_ISSUER` blank → first-class "auth off"
  state, `config/AuthConfig.kt:42-49`).
- **Identity has exactly one seam.** Every request's principal comes from
  `resolvePrincipal: (String?) -> Principal` in `di/RouteModule.kt:99-101`,
  defaulting to `Principal.Anonymous` when auth is off. Routes read `call.principal()`
  (`route/common/RouteAccessDsl.kt:27`); `.access(level)` guards enforce it.
- **Auth-disabled today yields pure `Principal.Anonymous`** — no dev user, no
  impersonation, no `X-User` shortcut exists. This design's impersonation is net-new.
- **Only `user_settings` is user-scoped today.** Watches and admin ingest are still
  `RouteAccess.Anonymous` (no owner column). Impersonation is future-proofed for when
  watch-ownership lands.
- **No seeded `app_user` rows exist.** A sandbox must seed its own known users.

## Approach

### Architecture & routing

A sandbox is **one docker-compose project** named `roadtrip-sb-<name>` running the
minimal stack (`postgres` + `backend`), auth off, observability and companion
omitted. `<name>` is the PR number (`pr-123`) or a branch slug (`fix-foo`) — stable,
so re-running `/sandbox` on the same PR **replaces** rather than accumulates.

**Routing keeps all per-sandbox work on the host — no Cloudflare API call per
spin-up.** One wildcard route `*.sandbox.floo.ca` → a single long-lived **Caddy**
reverse proxy on the host, plus one `cloudflared` ingress entry for the wildcard.
Each sandbox publishes its backend to a host-local port (`127.0.0.1:<allocated>`);
Caddy maps `sb-<name>.sandbox.floo.ca` → that port. Spin-up = write a Caddy vhost
snippet + reload Caddy.

```
Cloudflare  *.sandbox.floo.ca ──tunnel──> cloudflared ──> Caddy ──┬─> 127.0.0.1:41001  (sb-pr-123 backend)
                                                                  └─> 127.0.0.1:41002  (sb-fix-foo backend)
```

### The unified `deploy()` seam (partial consolidation)

One script; environment is a parameter set. Prod, sandbox, and local all call it;
only the marked steps branch. **Routing is a pluggable step (`direct` vs
`caddy-vhost`)** so prod's ingress is untouched now but can flip to Caddy later
(the "full consolidation" migration is deferred, not designed out).

| step | prod | sandbox | local |
|---|---|---|---|
| **image** | GHCR by SHA *(new)* | GHCR by SHA | local build |
| **project** | `roadtrip` | `roadtrip-sb-<name>` | `roadtrip` |
| **profiles** | pois + tunnel + companion | backend + postgres only | full |
| **DB** | real seeded | cloned snapshot + fresh seed users (Will/Matt) | dev import |
| **routing** | `direct` (cloudflared→backend) | `caddy-vhost` | direct/port |
| **auth** | OIDC on | off + `SANDBOX_ASSUME_USER` | off |

Conceptual unit:
`deploy(env, ref, name)` → resolve image (GHCR by SHA) · prepare DB · `compose up`
the project · register vhost (`direct` | `caddy-vhost`) · health-check.

### Image distribution

CI's existing `docker-build` job gains a **tag + push to GHCR by SHA** step. A
sandbox for a given SHA pulls a ready image (seconds) instead of building a fat jar
on the shared host. This is also the clean seam for a dedicated sandbox host later:
that host needs only registry pull creds, not the Gradle/JDK build toolchain.
Nothing else in CI changes.

### Database

A scheduled job `pg_dump`s the real seeded catalog to a compressed artifact on the
host (refreshed nightly). Per-sandbox boot = `pg_restore` into that sandbox's own
postgres volume, then seed the two known users. This avoids the 1.7 GB
`make data-import` per sandbox while still giving reviewers a realistic map.

### Auth & impersonation ("assume as user")

Sandboxes run **auth off**. Real OIDC is not shared because the redirect URI is
derived from `web.root-url` per hostname and the provider only completes flows for
callbacks on a tenant's allowlist (only a dev and a prod tenant exist; prod's
allowlist is deliberately kept clean). Ephemeral hostnames and shared working OIDC
are fundamentally in tension.

Instead, impersonation wraps the single identity seam. When **auth is disabled**
(`config.auth == null` / `authWiring == null`) **and** a dedicated flag
`ROADTRIP_SANDBOX_ASSUME_USER` is set, `resolvePrincipal` reads an
`X-Sandbox-User: <id>` signal (header, or a cookie the switcher sets), calls
`UserRepo.findById(UserId(n))` to pull that user's real roles, and returns
`Principal.User(...)`; otherwise `Principal.Anonymous`. **Zero route/service
changes** — everything downstream already reads `call.principal()`.

**Seed users are created fresh at boot** (not inherited from the prod snapshot — the
`pg_dump` restore is followed by an explicit seed step, and prod identities are never
cloned into a sandbox). Two seed users with fixed roles, defined in one place (a
sandbox seed fixture):

- **Will** → admin
- **Matt** → user

Roles come from the seeded `app_user` / `user_role` rows — there is no separate
role picker. The frontend gets an "assume user" switcher listing these users,
rendered only when `/api/me` reports `isAuthEnabled: false`; selecting one sets the
`X-Sandbox-User` signal.

**Safety gates — all must hold or the signal is ignored (fail closed):**

1. **Only consulted when `config.auth == null`**, asserted at wiring time. A prod
   with a real issuer can never honor the header — no auth bypass.
2. **`ROADTRIP_SANDBOX_ASSUME_USER` must be provably unset in prod** — a second
   independent latch beside auth-off. Do not rely on `cookie-secure` or other
   incidentally-correlated local config.
3. **Only ever constructs `Principal.User`** — never `Principal.System` (which holds
   every role in `RouteAccess.check`).
4. **Fails closed** — anything unexpected → `Anonymous`.

> Note: admin ingest and watch routes are currently `RouteAccess.Anonymous`, so
> impersonation grants no new access there *today*. When role-gating lands there,
> these gates are what keep a leaked header from handing out admin — hence fail
> closed now.

### Build-info banner

Every sandbox shows an **absolute-positioned bar across the top** identifying the
running build, so a reviewer never mistakes which env/change they're looking at.
It displays: **env** (`SANDBOX`), **short SHA**, and **branch name**.

The values already exist in the deploy path — the sandbox deploys a specific GHCR
image tag (the SHA) for a specific ref (the branch). They travel:

`deploy → backend env vars → `/api/build-info` → frontend banner`

- **Deploy** passes `ROADTRIP_BUILD_ENV` (`sandbox`|`prod`), `ROADTRIP_BUILD_SHA`,
  and `ROADTRIP_BUILD_BRANCH` into the backend container. Branch is easy: the
  trigger already resolves the ref/PR head. SHA is the image tag. Prod sets the same
  vars (env `prod`), so the mechanism is shared, not sandbox-only.
- **Backend** exposes them at a tiny unauthenticated `GET /api/build-info` returning
  `{ env, sha, branch }` (a `@Serializable` DTO — no hand-built JSON), read once at
  boot from env. This also gives the deploy health-check a version to assert.
- **Frontend** fetches `/api/build-info`; the banner **renders only when
  `env == "sandbox"`** — prod stays chrome-free. A shared banner component (not
  page-specific markup), fixed to the top, with the SHA and branch shown; clicking
  the SHA can link to the commit on GitHub.

Banner visibility is gated on `env == "sandbox"` from the endpoint, independent of
the auth/impersonation switches, so it appears regardless of which user is assumed.

### Lifecycle & triggers

- **Core:** `scripts/sandbox_up.sh <ref> [name]` does resolve-image → prepare-DB →
  compose-up → register-vhost → health-check.
- **CLI / AI session:** `make sandbox REF=…` wraps the script (also the any-branch,
  non-PR path). Prints the URL.
- **PR comment:** `.github/workflows/sandbox.yml` reacts to a `/sandbox` comment,
  resolves the PR head SHA, calls the script over SSH, and posts the URL back as a
  comment. `/sandbox stop` tears down.
- **Authorization (comment path):** the workflow's `if:` gates on
  `github.event.comment.author_association` being `OWNER` or `COLLABORATOR` — a
  string check on the event payload, no extra API call, consistent with the existing
  owner-only gate in `self-approve.yml`/`auto-rebase.yml`. An unauthorized commenter's
  `/sandbox` never starts a job, so arbitrary branch code can't be run on the host by
  outsiders. The **CLI path has no GitHub identity** — `make sandbox` runs as the
  operator's shell using their SSH access to `SANDBOX_HOST`, so it is gated by host
  access, not by GitHub. The `GITHUB_TOKEN` / `if:` gate governs only the comment path.
- **Teardown:** `make sandbox-stop NAME=…` and `/sandbox stop`, plus a **scheduled
  reaper** that tears down sandboxes older/idle beyond a TTL (default 24h):
  `compose down`, drop the volume, remove the Caddy vhost snippet.

### Host abstraction

`SANDBOX_HOST`, `SANDBOX_TUNNEL_ZONE`, and `SANDBOX_SECRET_KEY_PATH` are config,
set to the `mini-ca` values today and repointable to a dedicated box with no code
change. This is what makes "practically option 1, designed for option 2" real.

## Components & seams

| component | responsibility | seam it touches |
|---|---|---|
| `scripts/deploy.sh` (or refactor of current deploy) | parameterized `deploy(env, ref, name)` | prod deploy + sandbox share it |
| `scripts/sandbox_up.sh` / `sandbox_down.sh` | sandbox-specific wrapper (name, port alloc, Caddy snippet) | calls `deploy.sh` with sandbox params |
| `make sandbox` / `make sandbox-stop` | CLI entry | wraps the scripts |
| `.github/workflows/sandbox.yml` | `/sandbox` comment reaction, gated to OWNER/COLLABORATOR | thin GitHub wrapper over SSH → script |
| CI `docker-build` step | tag + push image to GHCR by SHA | new; also usable by prod later |
| snapshot job | nightly `pg_dump` of seeded catalog | produces per-sandbox restore artifact |
| reaper (scheduled) | TTL teardown | compose down + volume drop + Caddy reload |
| Caddy (long-lived) | wildcard vhost → per-sandbox port | the shared routing layer |
| impersonation wrap in `di/RouteModule.kt` | assume-user when auth off + flag | wraps `resolvePrincipal` only |
| frontend "assume user" switcher | pick a seeded user when `isAuthEnabled:false` | sets `X-Sandbox-User` |
| user seed step | insert fresh seed users (Will=admin, Matt=user) into snapshot | boot-time DB seed |
| `GET /api/build-info` | serve `{ env, sha, branch }` from boot env vars | deploy sets vars; frontend + health-check read it |
| frontend build-info banner | top bar, renders only when `env == "sandbox"` | shared component; reads `/api/build-info` |

## Error handling & edge cases

- **Re-run on same PR** replaces the existing `roadtrip-sb-<name>` project (stable name).
- **Port allocation** picks a free host-local port per sandbox; recorded so the
  reaper and Caddy snippet stay consistent.
- **Image not yet in GHCR** for the ref (CI still running): the trigger waits for /
  reports the missing image rather than falling back to an on-host build.
- **Snapshot missing/stale**: sandbox boot fails loudly rather than serving an empty
  map; the nightly job's freshness is observable.
- **Impersonation misconfig**: any failed gate → `Anonymous` (never a partial or
  escalated principal).
- **Unauthorized `/sandbox` comment**: an author who is not OWNER/COLLABORATOR does
  not start a job (the `if:` short-circuits) — no code runs on the host.

## Testing

- **Impersonation unit tests** at the `resolvePrincipal` seam: auth-on ignores the
  header (bypass-proof); auth-off + flag + valid id → `Principal.User` with the
  seeded roles; unknown id / `System` id / flag-unset → `Anonymous`.
- **Script smoke** mirrors the existing CI `smoke` recipe (postgres + bare backend +
  seeded row) to validate spin-up + health-check + Caddy vhost + teardown locally.
- **Seed-user assertions**: Will resolves to admin roles, Matt to user roles.
- **Reaper test**: a sandbox past TTL is fully removed (project, volume, vhost).
- **Build-info test**: `/api/build-info` returns the env-supplied `{ env, sha, branch }`;
  the banner renders only when `env == "sandbox"` and is absent in prod.

## Deferred / later

- **Full ingress consolidation (option A)**: put Caddy in front of prod too so prod
  and sandbox are literally one code path. Structured for it (routing is a pluggable
  step); deferred to avoid changing the live ingress on day one.
- **Auth-enabled sandboxes**: if reviewing real login becomes necessary, either a
  single fixed `auth-sandbox.floo.ca` registered once in the dev tenant, or (Auth0
  dev tenant only) a wildcard callback on the sandbox zone. Not needed given the
  impersonation switcher.
