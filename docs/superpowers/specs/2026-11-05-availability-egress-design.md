# Availability egress: Vercel renders the campsite matrix, mini-ca fetches it

**Status:** design approved in conversation 2026-11-05; this document awaits review.

**Amended 2026-11-05 (owner's decisions), replacing the text below where they differ:**

- **Availability lives only in mini-ca's Postgres.** Neon keeps the catalog; Vercel stores no
  availability. There is no writer role, no `DATABASE_WRITER_URL` and no migration.
- **The matrix GET reads through mini-ca.** It asks the egress endpoint for `mode: "stored"`
  (mini-ca's current cells, no vendor call, 5-second timeout) and fuses them with Neon's campsite
  list. mini-ca resolves the window and latest date against its own serving provider, and lists the
  campsites it knows; freshness counts only those. An unreachable or unconfigured mini-ca is
  `503 availability_unavailable`. The refresh POST asks for `mode: "refresh"` in `after()` and writes nothing; the page polls
  the GET as before.
- **Cloudflare Access is the only caller check.** No bearer token. A service token on
  `roadtrip-egress.floo.ca` is sent as `CF-Access-Client-Id`/`CF-Access-Client-Secret`; the egress
  URL and both Access variables are needed, all or none. Kotlin mounts the route when
  `AVAILABILITY_EGRESS_ENABLED` is `true` (on in production compose, off in sandboxes), and Caddy
  refuses `/api/internal/*` on every other host.

**Parent spec:** `docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md`
(C1 residential egress, C3 one rate-limit authority, C4 swappable datastore).

## Goal

The campsite availability matrix on a campground page is served by `apps/web` on Vercel from
Neon. When the stored snapshot is stale, the page shows it at once, asks for a refresh, and the
refresh is fetched by mini-ca from its residential IP, written to Neon, and picked up by the page.

Success: on a Vercel deployment with refresh configured, opening an Aspira or Campflare
campground whose snapshot is older than its TTL shows the stored matrix immediately, labelled
with its age, and within a few seconds shows the fresh one; no vendor request leaves a Vercel IP;
a deployment without refresh configured shows stored data and never calls mini-ca.

## Where this sits

This is the first of three specs that move production to Vercel with Neon as the system of
record and mini-ca reduced to vendor egress:

1. **This spec:** the availability fetch behind the campsite matrix.
2. **Later:** scheduled polls and watches on the same check, using Vercel Queues and Workflow.
3. **Later:** production cutover: loading every provider into Neon, DNS, and whatever signed-in
   features have moved by then.

Signed-in features (sign-in, watches, settings) stay behind a feature gate on Vercel; this spec
does not touch them.

## Non-goals

- Vercel Queues, Workflow, scheduled polls, watches and alerts. They come with spec 2.
- The stay check (`POST /api/pois/availability/stays`). It reuses this path later.
- Sign-in and every signed-in route.
- Loading rec.gov, ReserveAmerica, ReserveCalifornia, Tesla or Planet Fitness data into Neon.
  Until it is loaded, matrices for those campgrounds stay empty on Vercel (see Known limits).
- Porting vendor clients to `packages/worker`. Kotlin serves the egress endpoint for now.
- Changing what `roadtrip.floo.ca` serves today.

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | Vercel with **Neon is the target production home**; mini-ca keeps only vendor egress | Owner's choice (2026-11-05). Vercel hosts the site; mini-ca exists for its residential IP (C1). |
| D2 | **One check primitive** serves every surface | Owner: "the underlying check is the same across these surfaces; if not, they should be." The matrix is its first caller; the stay check and polls follow. |
| D3 | **Stale now, fresh pushed** | Owner's choice. The page never waits on a vendor; it renders what is stored and replaces it when the refresh lands. |
| D4 | Vercel **calls mini-ca over the Cloudflare tunnel** | Chosen over mini-ca pulling from a Vercel Queue, whose API needs a Vercel OIDC token that a non-Vercel host can only get as a ~12-hour development token, and over Neon as a work queue. |
| D5 | **No queue in this spec**: the refresh runs in the request's `after()` | Owner: the queue is for later scheduled polls. One viewer, one refresh. |
| D6 | **Kotlin serves the egress endpoint first** | Owner's choice. It reuses tested vendor adapters, failover, cooldowns and the one in-memory rate limiter, which the Kotlin pollers already share, so C3 holds. A TS port later replaces it behind the same HTTP contract. |
| D7 | The egress endpoint runs Kotlin's **existing read-through path** | mini-ca answers from its own fresh snapshot when it has one, so production, every preview and the Kotlin pollers share one cache and duplicate refreshes cost no vendor call. |
| D8 | Records are identified by **`(data_provider, data_provider_ref)`**, never database id | Neon and mini-ca's Postgres are loaded separately and assign different ids. Both schemas key campgrounds and campsites by this pair (`campgrounds_provider_uidx`, `campsites_provider_uidx`). |
| D9 | The matrix GET is **read-only**; a separate **POST starts a refresh** | A GET that fetches would refetch on every poll and let crawlers and link prefetching trigger vendor calls. |
| D10 | Neon writes go through a **minimal writer role** | Production Functions never hold the owner role, which can run DDL. |

## Architecture

```
browser ── GET  /api/pois/{id}/campsites/availability ──────────▶ Vercel ── read-only ──▶ Neon
        ── POST /api/pois/{id}/campsites/availability/refresh ─▶ Vercel ─┐
                                                                         │ after():
                                                                         ▼
           egress hostname ─ Cloudflare tunnel ─ caddy:80 ─ backend:8765 POST /internal/availability/fetch
                                                                         │  (Kotlin read-through on mini-ca's
                                                                         │   Postgres, vendor call if stale)
                                                                         ▼
                                                          Vercel ── writer role ──▶ Neon
```

### Vercel (`apps/web`)

- **`GET /api/pois/{id}/campsites`**: ported from Kotlin, same response
  (`PoiCampsitesResponseSchema`), read from Neon.
- **`GET /api/pois/{id}/campsites/availability`**: ported from Kotlin, same response
  (`PoiCampsitesAvailabilityResponseDto`), read from Neon. It never calls mini-ca.
  - The date window resolves as Kotlin's `AvailabilityDateResolver` does: booking horizon, the
    provider's maximum window, the default window.
  - `cache` is `{ hit, age_seconds, ttl_seconds }` exactly as today. `age_seconds` is the age
    of the stalest cell the window needs.
  - `watch_capabilities` reports watching as unsupported while signed-in features are gated.
- **`POST /api/pois/{id}/campsites/availability/refresh`**, with the same `start_date` and
  `end_date` as the GET. It answers `202` at once and runs the refresh in `after()`. It answers
  `202` without fetching when the window is already fresh, and `404` for an unknown POI. When
  refresh is not configured, or the campground has no provider that serves availability, it
  answers `501` with the existing `AvailabilityErrorDto`.
- **The refresh** (a service, not route code):
  1. It calls the egress endpoint with the campground's `(data_provider, data_provider_ref)` and
     the window, under `AVAILABILITY_EGRESS_TIMEOUT`.
  2. It maps each returned campsite pair to the Neon campsite of that campground. Pairs with no
     Neon campsite are dropped and counted in one log line per refresh.
  3. It writes the cells through the writer role with Kotlin's `recordObservations` semantics: an
     unchanged status bumps `last_observed_at` in place; a changed status inserts a row linked by
     `previous_id`; `run_id` stays null. Concurrent writers are safe for the same reason they are
     in Kotlin: `availability_previous_id_uq` with `ON CONFLICT DO NOTHING`.
  4. Each cell's `last_observed_at` is the `observed_at` mini-ca returned, never Vercel's clock.
     Otherwise an answer from mini-ca's two-hour-old cache would be stored as new and the matrix
     would call stale data fresh.
  5. Failures are logged with the error code and write nothing.
- **Freshness** follows Kotlin's per-cell rule (`AvailabilityLoader.unansweredCells`): a window
  is fresh only when every `(campsite, date)` it needs has an observation at least as new as
  now minus the TTL. The TTL is `AVAILABILITY_TTL`, defaulting to Kotlin's 2 hours.
- **Browser**: the matrix renders the GET's data with its age. When `age_seconds > ttl_seconds`,
  or `cache` is null, it POSTs one refresh and then polls the GET every
  `AVAILABILITY_REFRESH_POLL_INTERVAL` until the window is fresh or `AVAILABILITY_REFRESH_POLL_LIMIT`
  polls have passed. After the cap it keeps the stored data and shows that it could not refresh.
  It never POSTs again for the same window without a new page visit. It sends no POST when the
  campground has no availability provider.

### mini-ca (Kotlin)

- **`POST /internal/availability/fetch`**, registered only when `AVAILABILITY_EGRESS_TOKEN` is
  set. Layering is route → controller → `CampsiteAvailabilityService`.
  - It requires `Authorization: Bearer <AVAILABILITY_EGRESS_TOKEN>`, compared in constant time;
    otherwise `401`.
  - It resolves the campground by `(data_provider, data_provider_ref)` in its own catalog, takes
    all its campsites, and calls the existing `fetchAvailability` with the requested window. That
    serves cells from mini-ca's Postgres when fresh and calls the vendor otherwise, through the
    failover fetcher, cooldowns and rate limiter that exist today.
  - It writes only to mini-ca's own Postgres, as the read-through path already does.
- Nothing else in Kotlin changes. `roadtrip.floo.ca` keeps serving as it does.

### Egress contract

Request (values illustrative):

```json
{
  "campground": { "data_provider": "campflare", "data_provider_ref": "ref-2001" },
  "start_date": "2026-12-01",
  "end_date": "2026-12-15"
}
```

Responses:

| Status | Body | When |
|---|---|---|
| `200` | `{ start_date, end_date, cells: [{ campsite: { data_provider, data_provider_ref }, date, status, observed_at }] }` | Answered from mini-ca's store or a fresh vendor call. `start_date` and `end_date` are the window after clamping to the booking horizon. |
| `400` | `AvailabilityErrorDto` | Malformed request or a window the provider refuses. |
| `401` | none | Missing or wrong bearer token. |
| `404` | `AvailabilityErrorDto` | No campground with that pair. |
| `501` | `AvailabilityErrorDto` | No provider serves availability for that campground. |
| `503` | `AvailabilityErrorDto` | Every provider failed. The existing code and retryability carry through. |

The request and response are `@Serializable` DTOs with a row in `ApiContract.kt`, so their
TypeScript types are generated into `api-types.ts` and Vercel imports them.

### Network (mini-ca)

- A new Cloudflare tunnel public hostname, `roadtrip-egress.floo.ca`, points at `caddy:80`. The owner
  creates it.
- `caddy/Caddyfile` gets a site block for `http://roadtrip-egress.floo.ca` that proxies only
  `POST /internal/availability/fetch` to `backend:8765` and answers `404` to everything else.
- The companion keeps no public route (C2).

## Schema

One migration, written twice so the schema-equivalence gate (`scripts/schema-equivalence.mjs`)
stays green: `packages/db/migrations/0003_availability_egress.sql` for Drizzle/Neon, and the
matching Flyway change for mini-ca.

- `roadtrip_web` gains `SELECT ON availability`. It stays read-only.
- A new role, `roadtrip_availability_writer`, created only if missing, with its password set
  separately, as `0002_web_reader.sql` does for `roadtrip_web`:
  - `SELECT` on `campgrounds`, `campsites`, `availability`
  - `INSERT, UPDATE` on `availability`
  - `USAGE` on `availability`'s id sequence
  - a `statement_timeout`

No table changes. Kotlin's grant files are repeatable migrations (`R__…`), so its side is an edit
to those files.

## Configuration

| Name | Where | Default | Notes |
|---|---|---|---|
| `AVAILABILITY_EGRESS_URL` | Vercel | unset | Base URL of the egress hostname. |
| `AVAILABILITY_EGRESS_TOKEN` | Vercel, and mini-ca (`secrets/registry.yaml`, consumer `backend`, `required_in` empty) | unset | Shared bearer. Unset on mini-ca means the endpoint does not exist. |
| `DATABASE_WRITER_URL` | Vercel | unset | Connection as `roadtrip_availability_writer`. |
| `AVAILABILITY_TTL` | Vercel | 2 hours | Mirrors Kotlin's default availability TTL. |
| `AVAILABILITY_EGRESS_TIMEOUT` | Vercel | 25 seconds | Kept below the refresh route's `maxDuration`, set explicitly to 60 seconds. |
| `AVAILABILITY_REFRESH_POLL_INTERVAL`, `AVAILABILITY_REFRESH_POLL_LIMIT` | frontend constants | 2 seconds, 15 polls | 30 seconds of polling covers the egress timeout with margin. |

Refresh is on only when `AVAILABILITY_EGRESS_URL`, `AVAILABILITY_EGRESS_TOKEN` and
`DATABASE_WRITER_URL` are all set. Setting some but not all is a `ConfigError` at boot. Previews
may enable it: they write to their own Neon branch and share mini-ca's rate limiter.

**Per-IP limit.** Kotlin caps the matrix read at 30 calls per minute per IP
(`ip-rate-limit-per-minute`), because each call can reach a vendor. On Vercel that call is the
refresh POST, so production gets the same cap as a Vercel Firewall rate-limit rule on
`POST /api/pois/*/campsites/availability/refresh`. The rule is configuration, set by the owner,
and holds in production only.

## Failure handling

| Failure | Result |
|---|---|
| mini-ca unreachable or slow past the timeout | Logged; nothing written; the page stops polling at the cap and keeps stored data. |
| Vendor failure (`503`) | Logged with its code; mini-ca's cooldown tracker damps repeats. |
| Unknown campground on mini-ca (`404`) | Logged as catalog drift; nothing written. |
| Campsites mini-ca knows and Neon does not | Dropped; the count is logged. |
| Writer connection fails | Logged; the next refresh retries. |
| Refresh not configured | The POST answers `501`; the page shows stored data without polling. |

A refresh failure never changes the GET's answer.

## Testing

- **Kotlin route test** for `/internal/availability/fetch`: `401` without and with a wrong
  token; `404`, `400` and `501`; a `200` through a fake provider, which also feeds the contract
  ledger; a `200` served from the store with no provider call when the cells are fresh; and the
  route absent when the token is unset.
- **Vercel service tests**, against real Postgres:
  - A stale window starts a refresh; a fresh one does not.
  - Unset or partial configuration disables refresh, or fails at boot.
  - The write path: an unchanged status bumps in place, a change inserts a linked row, and two
    concurrent writes of the same change leave one row. These mirror Kotlin's
    `recordObservations` cases.
  - `last_observed_at` equals the returned `observed_at`.
  - Unknown campsite pairs are dropped.
  - Per-cell freshness matches Kotlin's on the same fixture.
- **Route tests** for the two ported GETs, checked for parity against Kotlin's responses on the
  same data, and for the POST's statuses.
- **Caddy routing test** (`scripts/test_caddy_routing.py`): `roadtrip-egress.floo.ca` proxies only the
  egress path, and existing hosts are unchanged.
- **Frontend:** the matrix POSTs once, polls the GET, and stops at the cap.
- No test calls a real vendor. The egress is faked on the Vercel side, and the provider is faked
  on the Kotlin side.

## Rollout

1. Merge. Kotlin ships the endpoint dark, because the token is unset on mini-ca.
2. The owner creates the tunnel hostname, then sets `AVAILABILITY_EGRESS_TOKEN` on mini-ca
   (`./secrets/manage.py set AVAILABILITY_EGRESS_TOKEN prod`) and deploys.
3. The owner sets the writer role's password on Neon, then `AVAILABILITY_EGRESS_URL`,
   `AVAILABILITY_EGRESS_TOKEN` and `DATABASE_WRITER_URL` in Vercel, and adds the Firewall rule.
4. Verify on a deployment: a stale Aspira matrix refreshes, the egress hostname answers `404`
   off its one path, and Kotlin's log shows the request from the tunnel.

Rollback is unsetting `AVAILABILITY_EGRESS_URL` in Vercel. Matrices then serve stored data only.

## Known limits

- **Some windows never become fresh.** A window with a cell the vendor never answers stays stale
  under the per-cell rule, so each refresh of it costs one vendor call. Production behaves this
  way today; mini-ca's rate limiter and cooldowns bound it.
- **Two viewers can start two refreshes.** The second is usually answered from mini-ca's store.
  Merging identical in-flight requests inside the Kotlin endpoint is the fix if this proves
  wasteful, and needs no schema change.
- **Neon lacks most providers' campsites.** Matrices for rec.gov, ReserveAmerica and
  ReserveCalifornia campgrounds are empty on Vercel until spec 3 loads them.
- **Two catalogs can drift.** Neon and mini-ca are loaded separately; drift shows up as dropped
  campsites and `404`s in the logs.
