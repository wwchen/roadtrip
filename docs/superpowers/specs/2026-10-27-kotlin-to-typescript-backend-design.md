# Kotlin Backend to TypeScript

Replace the Ktor backend with TypeScript: a Next.js app serving the web surface and
a Node worker owning vendor egress and scheduled work, sharing one Drizzle package.
The datastore is a configuration choice, not an architectural one.

## Problem

The backend is 855 Kotlin files — 42,184 LOC main, 52,945 LOC test, 1,066 LOC smoke
— serving 46 HTTP endpoints over 66 Flyway migrations. The frontend is a separate
Vite + React 19 SPA. Four things motivate replacing it:

1. **One language.** Types cross the boundary through a generator
   (`frontend/src/api/generated/api-types.ts`, 884 lines) rather than directly.
2. **Build weight.** `generateJooq` runs against a Testcontainers Postgres, so a
   backend build requires Docker. The edit-run loop is Gradle-paced.
3. **Deploy.** Self-hosted Compose behind Caddy and a Cloudflare tunnel, with no
   preview deploys.
4. **SSR.** The SPA cannot server-render campground or POI pages.

Two conditions make a port viable that would otherwise not be: there is no active
development on the existing site, and there is no data to migrate. The current
repository is a reference implementation, not a system to keep running in parallel.

## Constraints

These are discovered, not chosen. Each one killed an earlier version of this design.

### C1 — Vendor traffic must originate from a residential IP

Recreation.gov sits behind Akamai, which flags datacenter IPs. `docs/companion.md`
records this as the reason the rec.gov companion runs a real Chromium with a
persistent profile. It applies beyond the companion: `CampsiteAvailabilityService`
computes a freshness cutoff from a per-provider TTL — `DEFAULT_AVAILABILITY_TTL` is
**2 hours** — and when the stored snapshot is older, calls
`failoverFetcher.fetch(...)` **during the user's request**. A request-path fetch from
a serverless function would originate from a datacenter.

**Therefore:** one process owns all vendor egress, and it runs on mini-ca.

### C2 — The companion never gets a public vhost

`docs/companion.md` states this as an invariant: no Compose `ports:`, no Caddy
route. Per-user isolation is enforceable because the process calling the companion
authenticates the user and passes only *their* `profile_id`.

**Therefore:** whatever calls the companion sits inside the Compose network, where
the Ktor backend sits today.

### C3 — Vendor rate limiting is per-process

`VendorRateLimitConfig` is a token bucket — `DEFAULT_VENDOR_BUCKET_CAPACITY` 60,
refilling 60 per minute — held in memory, alongside `ProviderCooldownTracker`. Two
processes fetching from the same vendor would hold two independent buckets and be
blind to each other's cooldowns.

**Therefore:** C1's single egress process is also the only rate-limit authority.

### C4 — The datastore must be swappable

Supabase, self-hosted Docker Postgres, and Neon must all work, across production,
preview, and local. This is a first-class requirement.

**Therefore:** the application depends on Postgres and nothing else. Every vendor
capability is an adapter behind a port with a portable default — the `AGENTS.md`
port/adapter rule applied to infrastructure rather than to reservation vendors.

C4 is why Supabase Realtime is **not** the push mechanism, despite fitting the
cold-snapshot flow well. It is Supabase-only. Postgres `LISTEN`/`NOTIFY` does not
rescue portability either, because transaction-mode pooling — Supavisor, Neon's
pooler, pgbouncer — forbids it, and that is the pooling a serverless API requires.
The portable default is polling. Realtime may be added later as an adapter if
polling proves measurably inadequate.

## Design

### 1. Two runtimes and a shared package

| Component | Host | Owns |
| --- | --- | --- |
| **Next.js** | Vercel (or mini-ca; see §9) | SSR pages, the frontend, ~41 endpoints |
| **Worker** | mini-ca | Scheduler, availability pollers, vendor clients, notifications, the companion-facing routes, and the importer CLI |

The companion-facing routes are the four in `RecgovSettingsRoutes` — login, MFA,
verify, status — plus whichever settings route performs credential save and removal,
since removal calls `destroyProfile`. The eight `CompanionSessionPort` methods are
not all routes; `refresh` and `markKeepWarm` are driven by `RecGovKeepaliveJob`,
which is worker-internal either way.
| **Companion** | mini-ca | Unchanged. Already Node, already a clean HTTP contract |
| **`@roadtrip/db`** | shared workspace package | Drizzle schema, domain types, JSONB codecs |

Both runtimes import `@roadtrip/db`, so "one schema mapper" is a compiler guarantee
rather than a convention.

The worker connects to Postgres directly and keeps `FOR UPDATE SKIP LOCKED` exactly
as `SchedulableRepo` does today. There is no internal work-claim API, no signed-token
scheme, and no cross-language DTO contract — all three were in earlier drafts of this
design and all three exist only to paper over a language boundary that this design
removes.

C2 needs no new design: the worker sits where the Ktor backend sits today, on the
same Compose network, with the same shared secret and the same `profile_id`-only
contract.

### 2. Connection configuration

Two connection strings, not one:

- **Next.js** uses the transaction-mode pooler.
- **The worker** is long-lived and takes a direct session-mode connection.

**`prepare: false` is pooler- and version-dependent, not a universal rule.** An
earlier draft of this section said transaction pooling simply requires it, and that is
wrong. Task 6 stood up a real PgBouncer in transaction mode and found `prepare: true`
survived: PgBouncer tracks prepared statements from 1.21 and enables that by default
from 1.24. Modelling a strict pooler took setting `MAX_PREPARED_STATEMENTS=0`
explicitly.

So the setting has to be determined per deployment rather than assumed. Getting it
wrong is costly in both directions: `prepare: false` against a pooler that supports
statements gives up prepared-statement performance for nothing, and `prepare: true`
against one that does not fails confusingly under concurrency. Determine it for the
chosen pooler at its pinned version, and record the answer beside the connection
string rather than in someone's memory.

`FOR UPDATE SKIP LOCKED` works identically under transaction pooling on all three
targets, so the scheduler's claim-lease mechanism is portable unchanged.

Local development is `docker run postgres` — not a ten-container Supabase stack.

### 3. Schema

The live schema is the source, **not the 66 migrations**. Those contain historical
churn describing tables that no longer exist: `availability_snapshot` was dropped in
V36, `availability_job` in V28. Reading them as a description of today's schema
produces wrong answers; this spec's first draft made exactly that error.

The reference is **the schema the 66 migrations produce when run**, not a live
database and not the migrations read individually. Running `flyway migrate` into a
throwaway container and dumping the result gives exactly the live schema with no
production access and a result reproducible in CI. The earlier error was reading
migrations as descriptions; running them is authoritative.

Author the Drizzle schema as **one initial migration** against that reference.

**Drizzle cannot express all of it, and the parts it cannot are business logic.** An
escape-hatch raw-SQL migration carries:

| Feature | Count | Note |
| --- | --- | --- |
| PostGIS extension and `geometry(Geometry, 4326)` columns | 3 tables | Needs a Drizzle custom type. The extension must be created *before* the generated migration, which declares those columns |
| `CREATE CONSTRAINT TRIGGER availability_watch_target_prune_empty` and its plpgsql function | 1 + 1 | `DEFERRABLE INITIALLY DEFERRED`; deleting the last target prunes the watch. **Business logic in the database** — a schema port drops this silently |
| `CREATE TYPE availability_status AS ENUM` | 1 | `pgEnum`, with the member order preserved |
| `CREATE VIEW grafana_api_cache_metadata` | 1 | A plain view, inside `R__grafana_reader_grants.sql` |
| Partial and expression indexes | several | Drizzle expresses `.where()`; expression indexes need raw SQL |
| `CHECK` constraints | **76** | Named explicitly by Flyway; Drizzle auto-names unless told, so names must be pinned |
| `R__grafana_reader_grants.sql` role and grants | 1 | Repeatable `GRANT` migration; Drizzle does not manage grants or roles |

**There are no materialized views.** An earlier draft of this section listed
`campground_canonical` and `campsite_canonical` as the canonical catalog read model,
from counting `CREATE MATERIALIZED VIEW` across the migrations. They are created in
V39, recreated in V41 and V43, and **dropped for good in V44** —
`CanonicalCatalogSchemaTest` asserts their absence against `pg_matviews`. That error
is this section's own warning in miniature: six `CREATE` statements read as proof of
existence, and nobody looked for the later `DROP`.

The `CHECK` count has the same shape. The migration *source text* contains 107
`CHECK (` occurrences; the schema those migrations *produce* has 76, because the rest
constrain tables and columns that no longer exist. Any count asserted against this
schema must come from the dump.

The gate is therefore **semantic equivalence, not a textual diff**: migrate Drizzle
into a second database, dump both with `pg_dump --schema-only --no-owner
--no-privileges`, normalize, and fail on any difference. A textual comparison against
an unnormalized dump would never pass, and the 107 explicitly-named constraints are
the reason it matters.

**`@roadtrip/db` must not be importable from the browser.** It depends on a Postgres
driver. But the frontend needs the domain vocabulary — `frontend/src/lib/campground-vocab.ts`
and seven other files already work with amenity, carrier and campsite-kind concepts.
So the package is a pair: `@roadtrip/domain` holds browser-safe enums, labels and
JSONB shapes, and `@roadtrip/db` holds Drizzle and the codecs and depends on it.

Every typed JSONB column — `amenities`, `cell_service`, `metadata`, `price`,
`default_campsite_schedule`, `alerts`, `photos`, `equipment`, `attributes`,
`booking_aliases`, `kind`, `location`/`address`, `properties`, `source_payload` — is
written only by the importer and read only by the API. Both are TypeScript importing
the same package, so `CatalogColumnJson`'s "one codec" rule survives as a type
constraint. `availability_watch.reservable_filters` and `trigger_config` are written
by the API and read by the worker; same package, same guarantee.

### 4. The importer

`data/raw` is 1.7G across 19,136 envelope files and 29 sources. It is a cache of
vendor responses, so the importer is a batch job — one-time seed plus ad hoc refresh
— not a service. It is a CLI in the worker package taking a directory and a
connection string.

It must not run through an HTTP endpoint. Routing a few hundred thousand upserts
through single-digit-MB request bodies against capped function durations is the wrong
shape, not a tuning problem.

**Verification by differential testing.** Run the Kotlin importer and the TypeScript
importer against the same 19,136 envelopes into two databases and diff the rows.
Deterministic input, deterministic output, no mocking, and a reference implementation
that already works. This replaces most ETL unit tests with one harness and is
stronger than what it replaces.

Note for execution: if the importer runs on mini-ca against a remote datastore, the
bulk load crosses a residential uplink. Seeding against a local Postgres and
dump/restoring upward is likely faster.

### 5. Auth

Supabase Auth replaces the sign-in flow. The reason is **not** that the existing code
is weak — that framing appeared in an earlier draft and is wrong.

`rfcs/0009-auth-provider-layer.md` documents two live holes this code closed:
`availability_watch` had no owner column, so `GET /api/watches` returned every user's
watches and the delete endpoint would delete anyone's; and `trigger_config.email_notify.to`
was an arbitrary unverified address, making the notification path an open email relay
any anonymous caller could drive.

The primitives are minimal and correct. `Pkce.kt` is 49 lines, over half comments.
`AuthCookies.kt` is 112 lines. The replacement must still guarantee, and these files
are the reference for what each one is for:

| Guarantee | Reference | Why it exists |
| --- | --- | --- |
| Asymmetric-only algorithm allowlist | `IdTokenVerifier.kt` | Prevents alg-confusion token forgery |
| Issuer compared against configured value, not the discovery document's | `IdTokenVerifier.kt` | The discovery value rejected every Auth0 token over a trailing slash |
| Opaque random session tokens, only SHA-256 persisted | `SessionService.kt`, `user_session.token_hash` | Revocable before expiry; survives an IdP swap without signing everyone out |
| HMAC-signed login-flow cookie, constant-time compare, domain-separated key | `LoginFlowState.kt` | Closes login CSRF and session fixation |
| `SameSite=Lax`, **not** `Strict` | `AuthCookies.kt` | `Strict` is not sent on the provider's cross-site callback redirect; "hardening" it breaks every login |

**The risk being avoided is the port, not the original.** A TypeScript
reimplementation derived from this code's shape rather than its reasoning would
plausibly lose the allowlist, the constant-time compare, or the Lax-not-Strict
subtlety. Not writing it twice is the win.

What is genuine excess is the provider-dialect layer: ~370 LOC across
`Auth0ClaimsDialect`, `ClerkClaimsDialect`, `WorkOsClaimsDialect`,
`StandardClaimsDialect`, `ClaimsDialectRegistry`, and the id types.
`docker-compose.secrets.yml` provisions Auth0 and Clerk only; WorkOS has no
credentials anywhere in the repo. Dropping provider-switching is an accepted loss.

**The port shrinks to one question:** given a request, who is the user and what roles
do they have. Adapters behind it: a Supabase adapter verifying its JWT, and a dev
adapter for local and any plain-Postgres configuration. `app_user`, `user_identity`
and `user_role` stay in the Drizzle schema, so authorization stays ours and stays
portable per C4.

`MagicLinkTokenService` is **not** part of this and does not move. It is wired to
`AvailabilityWatchRepo` and read by `AvailabilityWatchRoutes` via a query param; it
backs unauthenticated watch access from an alert link. A product feature, not sign-in.

### 6. Authorization

`rfcs/0010-authorization-pass.md` settles a decision this rewrite must not quietly
reverse: **every route declares its access level, and a route that declares nothing
fails the build.** Neither deny-by-default nor allow-by-default was acceptable —
roughly three-quarters of routes are legitimately anonymous because browsing is the
product, so deny-by-default puts the annotation burden on the common case and turns a
forgotten marker into a user-facing outage; allow-by-default turns one into a silent
leak.

Port `RouteAccess` — `Anonymous`, `User`, `HasRole(role)`, `Signed` — and the
coverage check. RFC 0010 contains the complete 46-row surface inventory with the
access level for each route; that table is the authoritative input, not something to
re-derive.

Two rows carry their own requirements. `POST /test/email` and `POST /test/slack` take
a caller-supplied destination and send real mail and real Slack messages; they must
be `admin`-gated **and** registered only behind a diagnostics flag, so production
carries no send-anything endpoint. `POST /api/slack/interactivity` is authenticated
by HMAC signature rather than session and must stay that way — Slack has no cookie —
so it is declared `Signed` rather than left uncovered.

Authorization stays in code (`RoadtripAuthorization`, `WatchAccessResolver`), not in
RLS policies. Policies are harder to test for no gain, since the database is not
exposed to browsers. This also keeps C4: RLS is Supabase-shaped.

### 7. Enforcement gates

These are what currently make drift and layering violations impossible. Each needs a
replacement, and the replacements are written **before** the code they guard. The
existing `scripts/check-feature-boundaries.mjs` is the local precedent for the style.

| Current gate | Replacement |
| --- | --- |
| `RouteAccessCoverageTest` | Walk the route tree; fail on any handler without a declared access level |
| `LayeringGuardTest` | Import-boundary check over the workspace: no route→repo, no `org.jooq` equivalent outside the data layer |
| `ApiContractCoverageTest`, `ContractLedgerCheck`, `ContractBodyCheck` | Zod schemas as the source, `zod-to-openapi` for the document; assert every route's success body is produced by a route test |
| `OpenApiContractDocumentTest` | Snapshot the generated OpenAPI document |
| `JooqCodegenDriftTest` | `drizzle-kit check` plus a schema-vs-dump round-trip |
| `SecretRegistryDriftTest` | Port directly against `secrets/registry.yaml` |
| `AvailabilityProviderContractTest` | Port directly — one suite every provider adapter must satisfy |
| `TypedInfrastructurePropertyNaming` (detekt) | ESLint rule, or drop if the Drizzle types make it unnecessary |

`make api-types` and the generator in `backend/src/main/kotlin/ca/floo/roadtrip/apigen`
are deleted rather than replaced: with one language there is nothing to generate.

### 8. Testing

**Do not port the test distribution. Derive it.** 52,945 LOC of Kotlin tests exist
partly because there was no cheap way to observe real behaviour: a repo test against
Testcontainers was the affordable substitute for looking at the running system. That
constraint goes away, so reproducing the existing shape would be porting a workaround.

#### 8.1 The verification environment is a deliverable

A per-PR preview deploy, a branched database, and a deterministic seed together make
a real environment cheap enough to verify against directly — by a person or by an
agent driving Playwright against the preview URL. This is built **early**, because it
changes what every later step needs to write.

**Its scope is API, database, and frontend iteration.** That is what a preview URL on
a seeded branch is good at, and it covers steps 7, 8, 10 and 11. Pollers,
notifications and the importer are verified by other means (§8.3) and are not a
shortfall of this environment — they are outside what it is for.

Three pieces:

- **Seed as a restorable dump, not an import run.** Re-running the importer over
  19,136 envelopes is minutes; restoring a post-import `pg_dump` is seconds. The dump
  is a versioned artifact produced by §4's importer. Tests that assert on seeded rows
  pin the seed version, or they break the next time it is regenerated.
- **Branched database per PR.** Supported by both Supabase and Neon; on bare Docker
  it is a template-database copy. Needs lifecycle management — create on PR open, drop
  on close — or branches accumulate.
- **Vendor responses come from `data/raw`.** E2E must never call a real vendor: C1
  forbids the IP, C3 forbids the quota, and vendor flakiness would make CI
  non-deterministic. The 1.7G of captures are already the right fixtures and
  `RawCaptureStore` is already the seam. Point it at a pinned subset for tests.

#### 8.2 Scoping

With that environment in place, each level is written for what only it can catch:

- **Unit tests on genuine logic, and only there.** The status-run compression in
  `recordObservations`, `PoiAvailabilityFusion`, cadence and backoff math,
  `BookingHorizonResolver`, ETL field transforms. These are algorithms with edge
  cases; a preview deploy cannot tell you the off-by-one.
- **Contract tests on the API surface.** Against the frozen `api-types.ts` and
  OpenAPI document from the Kotlin build, which are the target specification.
- **E2E on flows, against the preview.** `backend/src/smokeTest` is 1,066 LOC of
  Playwright already — JS-native, so the easiest thing in this project to port, and
  the end-to-end safety net for every later step. Port it **first**, not last.
- **Differential, for the importer.** §4's harness. Stronger than any unit test of a
  transform, and it replaces most of them.
- **Guard tests, before the code they guard.** §7.
- **Not ported at all:** jOOQ mapping tests, DI wiring tests, the §5 auth internals,
  the dialect layer. These have no counterpart in the new system.

#### 8.3 What covers the rest

The worker's halves are not uncovered; they are covered by mechanisms better suited
to them than a preview URL would be.

- **The importer** has §4's differential harness: both implementations over the same
  19,136 envelopes, rows diffed, against a reference implementation that works. This
  is the strongest verification in this spec, and stronger than anything a preview
  gives the API.
- **Scheduler claim-lease behaviour** tests against a real Postgres, concurrency and
  lease expiry included. `FOR UPDATE SKIP LOCKED` under contention is exactly what an
  integration test with two claimants demonstrates.
- **Pollers and notifications** test at their boundaries with vendor responses from
  `data/raw` and a fake outbound transport for Slack and email. `AvailabilityProvider`
  and `NotificationService` are already ports, so the seams exist.
- **Live vendor behaviour** — Akamai's actual responses, real rate-limit and cooldown
  behaviour — is not automatically verifiable, and was not before this port either.
  Unchanged, not a regression.

During step 9, running a local worker against a PR's branched database is a useful
manual loop on top of the above. It is a convenience, not the plan.

### 9. Early probes

Both are **early steps, not blockers.** C4 is what makes them non-blocking: the
datastore and the host are configuration, so a bad answer changes a config value
rather than invalidating this design.

**P1 — Read latency.** Run `CampgroundSearchRepo`'s geo queries and the availability
read path from a deployed function against a managed datastore, and compare against
in-process-to-localhost today. If the regression is unacceptable, Next.js runs on
mini-ca in standalone mode and everything else in this spec is unchanged; the only
loss is Vercel preview deploys.

**P2 — Supabase third-party auth.** Determine whether Supabase can sit in front of an
external IdP, or whether adopting it means replacing the IdP outright. Either answer
is acceptable — provider-switching is an accepted loss per §5 — but it changes the
shape of the §5 adapter and should be known before that step.

### 10. Sequencing

This spec describes a program, not a single change. Each step below is its own
implementation plan; steps 2, 4, 7, 8, 9 and 11 are each large enough to warrant one.
Do not expand this into one plan.

1. P1 and P2, in parallel with step 2.
2. Monorepo, `@roadtrip/domain` and `@roadtrip/db`, Drizzle schema plus the §3
   escape-hatch migration, local Postgres. New top-level directories must be
   registered in `.claude/settings.json` → `worktree.sparsePaths`, or
   `scripts/check-worktree-sparse-paths.mjs` fails and every future worktree is
   missing the code. *Gate:* normalized `pg_dump` equivalence against the
   Flyway-produced reference.
3. Enforcement gates (§7), before the code they guard.
4. Importer plus differential harness. *Gate:* row-level diff clean against the
   Kotlin importer.
5. Verification environment (§8.1): seed dump produced from step 4, branch-per-PR
   with lifecycle, preview deploy, `RawCaptureStore` pointed at a pinned fixture
   subset. *Gate:* opening a PR yields a preview URL on a seeded branch.
6. Port the Playwright suite from `backend/src/smokeTest`. It is the acceptance gate
   for steps 7–11 and is expected red until step 7 — porting it here buys the
   acceptance criteria for everything after, not a green build now.
7. Read API and SSR pages for POI and campground. *Gate:* responses match the frozen
   contract; read-path smoke green on a seeded preview.
8. Auth (§5) and the full `RouteAccess` declaration set from RFC 0010's inventory.
   *Gate:* coverage check fails on an undeclared route.
9. Worker: scheduler, pollers, notifications. *Gate:* smoke, plus the worker's own
   verification story per §8.3 — it does not inherit the preview one.
10. Companion-facing routes on the worker; Next.js proxies, and the worker resolves
    the forwarded session token against the database itself rather than trusting a
    header.
11. Frontend to Next.js pages. *Gate:* full smoke suite green.
12. Cutover; decommission the Kotlin build.

### 11. Secrets

**Narrow SOPS to where it earns its weight; let Vercel own its own.** The existing
system is SOPS with age recipients in `secrets/.sops.yaml`, vaults that are dotenv
files with encrypted values and plaintext keys so `git diff` shows *which* secret
changed, and `manage.py` wrapping the `sops` binary — 945 LOC there, 176 in
`docs/secrets.md`, and a key ceremony across 6 recipients. Its load-bearing property
is that `docker-compose.secrets.yml` is *generated* from the registry's `consumers`
field, so a wrong entry means the container genuinely does not receive the secret —
the metadata cannot disagree with reality. `manage.py check` runs in CI and
pre-commit.

**The structural problem is that SOPS is a file-based store committed to git and
Vercel is an API-based store.** Bridging them means one holds values it does not
serve, and something has to keep the two in step.

**Resolve it by accepting two stores, not by syncing them.** The registry's real job
is recording *which* secrets exist, *who* consumes them, and *where* absence is fatal.
That job does not require it to hold every value. So it gains a `store` field —
`sops`, `vercel`, or `supabase-integration` — and `check` verifies names, placement
and `required_in`, never values.

| Tier | Store | Why |
| --- | --- | --- |
| Boot-critical mini-ca infra: `POSTGRES_PASSWORD`, `GRAFANA_DB_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, `GRAFANA_SLACK_WEBHOOK_URL`, `CLOUDFLARE_TUNNEL_TOKEN` | **SOPS** | Encrypted in git has *no runtime dependency*. The box boots with nothing reachable |
| Worker and companion runtime: tokens, API keys, `BOOKING_CREDENTIAL_ENCRYPTION_KEY` | **SOPS** | Already works, already generated into Compose |
| Next.js runtime: `MAPBOX_TOKEN`, `SLACK_SIGNING_SECRET`, `SLACK_TOKEN_ENCRYPTION_KEY` | **Vercel** | Set once, where the consumer already reads from |
| `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY` | **Supabase↔Vercel integration** | First-party and automatic; managed by nobody |
| Preview `DATABASE_URL` | **injected per deploy** | Dynamic; see below |

This deprecates SOPS for exactly the half where it was awkward, and keeps it where
its defining property is load-bearing. No `push` command, no decrypt-and-sync, no age
key in CI, and no production value sitting in plaintext in two places.

**Why not deprecate SOPS entirely.** Both replacements lose something real. An
unversioned `.env` on the box gives up change history, the registry → Compose
generation that makes consumer drift impossible, and reproducible setup. A hosted
store — 1Password, say — makes mini-ca's boot depend on an external service being
reachable, and that includes `POSTGRES_PASSWORD`, so Postgres itself cannot start
during an outage of the secret manager. SOPS-in-git has no such dependency, and for
the tier that must come up unattended that is the whole point.

Full deprecation becomes available only when nothing runs on mini-ca. C1 forbids
that, so it is a non-goal rather than a later step.

**Also rejected — the age key in Vercel, decrypting at runtime.** One key would
unlock every environment at once, per-value rotation would need a redeploy, and the
cold path would need sops or an age implementation.

Where the 20 current secrets land:

| Secret | After |
| --- | --- |
| `MAPBOX_TOKEN` | vercel — `/api/route`, `/api/geocode`, `/api/pois/on-route` are server-side |
| `SLACK_SIGNING_SECRET` | vercel — verifies the inbound interactivity webhook |
| `AUTH_CLERK_*`, `AUTH_AUTH0_*` (6) | **deleted** — replaced by Supabase per §5 |
| `SUPABASE_URL`, `SUPABASE_ANON_KEY` | vercel, browser-exposed |
| `SUPABASE_SERVICE_ROLE_KEY` | vercel, **server-only** |
| `SLACK_BOT_TOKEN`, `SLACK_ALERT_CHANNEL`, `RESEND_API_KEY` | worker — outbound notification |
| `CAMPFLARE_API_KEY` | worker, host-tools |
| `COMPANION_API_TOKEN` | worker, companion |
| `RIDB_API_KEY` | host-tools — unchanged |
| `POSTGRES_PASSWORD` | postgres, worker — only in the self-hosted C4 configuration |
| `GRAFANA_*` (3), `CLOUDFLARE_TUNNEL_TOKEN` | unchanged |
| `ENCRYPTION_KEY` | **split in two; see below** |

#### Environments do not line up

The registry's environments are `local` and `prod` (`required_in`), with vaults
`common.enc.env`, `local.enc.env`, `prod.enc.env`. Vercel's are production, preview
and development. Preview is new and has no vault, so it needs one —
`preview.enc.env` — rather than silently inheriting `prod`.

**A preview deploy must not carry production notification credentials.** This is the
sharpest hazard in this section: a PR preview holding `SLACK_BOT_TOKEN` and
`RESEND_API_KEY` sends real Slack alerts and real email to real users from unreviewed
code. The registry already records the way out — both are `required_in: []`, meaning
absence degrades the feature rather than failing boot, and the code must actually do
so. So preview simply does not receive them, and `manage.py check` fails if it does.

Accepting two stores keeps CI out of it: nothing decrypts in CI, so `.sops.yaml`
gains no recipient and the 6 that exist stay the whole list. `docs/secrets.md`'s
threat model needs a section on the Vercel tier, but not a new key holder.

#### Three hazards

**`ENCRYPTION_KEY` is needed on both sides, so split it by purpose.** It feeds
`SecretCipher` in three places: `UserSettingsService` writes a user's Slack token
from the settings page (Vercel), `WatchNotificationTargetResolver` reads that token
when dispatching an alert (worker), and `RecGovCredentialService` holds rec.gov
credentials (worker). One key would therefore have to exist in Vercel's environment.
Two keys — `SLACK_TOKEN_ENCRYPTION_KEY` on both sides, `BOOKING_CREDENTIAL_ENCRYPTION_KEY`
on the worker only — keep the higher-value secret, a user's actual rec.gov password,
from ever leaving mini-ca. Decide this before any value is encrypted; splitting later
means re-encrypting stored rows.

**`NEXT_PUBLIC_` inlines into the client bundle.** A service-role key named with that
prefix is served to every visitor, and it bypasses RLS. The registry must classify
each `vercel` secret as browser-exposed or server-only, and `manage.py check` must
fail when a server-only name carries the prefix or is referenced from a client
component. This is the same shape as the drift tests already in the repo.

**A static preview `DATABASE_URL` silently defeats §8.1.** The branch-per-PR database
URL is generated at branch creation, so it is not a registry secret at all. Pasting a
long-lived URL into Vercel's preview environment makes every PR share one database
while appearing to work. The registry records it as injected-per-deploy, and `check`
fails if a literal value is set for the preview environment.

#### A property to keep

`frontend/src` contains no `import.meta.env` references, no Mapbox token, and no
external tile URL — **the browser holds zero secrets today.** Moving to Next.js makes
leaking one a one-line mistake, so the only browser-exposed values permitted are
`SUPABASE_URL` and `SUPABASE_ANON_KEY`. Mapbox stays server-side.

## Risks

**Operational surface grows.** Today: one JVM process plus a Node companion. After:
Next.js, a Node worker, the companion, and `scripts/poll_raw.py` — which stays Python
and is out of scope. Four runtimes to two hosts. Three of the four motives are about
less friction, and this is a real cost against them.

**The availability write path is not a simple insert.** `recordObservations` →
`latestPerCell` → `currentStatusRuns` → `insertStatusRuns`/`bumpObservedAt` maintains
a `previous_id` linked list under a unique index. It is a read-modify-write against
current state. Porting it needs the reasoning, not the shape — the same hazard §5
describes for auth. Cells per poll (campsites × target dates) is unmeasured.

**Live vendor behaviour stays unverifiable.** Akamai's real responses and real
rate-limit and cooldown behaviour cannot be exercised by any automated suite, here or
today. The ported poller is correct against `data/raw` fixtures and still unproven
against the live edge until it runs on mini-ca. Step 9 ends with a period of watching
production, not with a green suite.

**SSR delivers less than it sounds like.** The primary view is maplibre-gl, which is
client-only. Server rendering buys campground and POI detail pages — real SEO value,
but not the app.

**Deleting provider-switching is irreversible in practice.** `AUTH_PROVIDER=clerk`
in `docker-compose.sandbox.yml` shows it is in live use across environments.

**The RFCs are the reasoning, the code is only its shape.** This spec's first draft
asserted schema facts from dropped tables and mis-framed the auth code, both from
reading code without reading `rfcs/`. Before porting any subsystem, read its RFC:
0004 for ingestion, 0007 for availability and alerts, 0008 for the reservable
hierarchy, 0009 and 0010 for auth and authorization.

## Open questions

- Does the companion fold into the worker process or stay separate? Staying separate
  is assumed — it owns a browser pool, persistent profiles, and concurrency caps —
  but it is now the same language, so the choice is open.
- Does `scripts/poll_raw.py` eventually port, or stay Python permanently?
- Is a hosted datastore used for preview deploys, or does preview point at a branch
  of production data? C4 permits either.
