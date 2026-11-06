# Web app deployment (Vercel + Neon)

`apps/web` is the read-only Next.js app. It serves the Vite frontend's map (`/`) and POI page
(`/poi.html`, also `/poi`) as static files copied in at build time, the read endpoints those
pages call (`/api/pois`, `/api/pois/{id}`, `/api/pois/search`, `/api/campgrounds/search`,
`/api/campgrounds/details`, `/api/geocode`, `/api/route`, `/api/pois/on-route`, `/api/me`,
`/api/build-info`, and `/api/watches`, which answers 401), its own `/campgrounds/[poiId]` and
`/search` pages, and `/api/health`. It runs on Vercel against a Neon Postgres. Sign-in, watches,
availability and the worker are not ported yet; the Kotlin stack on mini-ca is untouched and
keeps serving production.

A campground's detail carries Kotlin's booking buttons (`cta`) and booking site
(`booking_system`), and its summary carries `booking_system`. Both are computed from the
catalog row and the booking-site table in `@roadtrip/domain` (kept equal to
`poi-registry.yaml`'s `booking_providers` by a test in `packages/worker`), with no vendor
calls. An Aspira button is dated to the campground's first bookable night in its own time zone,
looked up from its coordinates. `availability_supported` and the availability date fields stay
off until availability is ported, so the drawer's availability grid does not mount.

Nothing in this file is a secret. The connection string itself lives only in Vercel and in the
operator's own scratch file; this file records its shape and the settings measured against it.

## Production on mini-ca

Vercel builds PR previews; production for `roadtrip.floo.ca` runs `apps/web` as the `web`
container on mini-ca, beside the Kotlin `backend`. `web` serves only the read API. Kotlin keeps
the pages, sign-in, `/api/me`, watches, settings, availability, campsites, build info and health.
Design: `docs/superpowers/specs/2026-11-04-web-on-mini-ca-design.md`.

- **Image:** `ghcr.io/wwchen/roadtrip/web:<sha>`, the root `Dockerfile`'s `web` target, published
  by CI's `docker-build` job on every push and smoke-tested against PostGIS by `web-tests`.
- **Service:** `web` in `docker-compose.yml`, profile `web`, which only `deploy.sh`'s production
  invocation enables. It starts after a healthy `backend`, because Flyway applies its grants.
- **Database:** mini-ca's Postgres as `roadtrip_web`: read-only by default, `statement_timeout`
  10 s, `SELECT` on the tables in `WEB_READ_TABLES`. `R__web_reader_grants.sql` (mirrored as
  `packages/db/migrations/0002_web_reader.sql`) owns the grants; `postgres-init/20-web-reader.sh`
  sets the password, on a fresh cluster and on every deploy.
- **Secrets:** `WEB_DB_PASSWORD` and `MAPBOX_TOKEN`, mounted as files and read through
  `DATABASE_PASSWORD_FILE` and `MAPBOX_TOKEN_FILE`.

### Routing

`caddy/Caddyfile`'s `http://roadtrip.floo.ca` block splits production traffic. Requests whose path
matches `@web` go to `web:3000`; everything else goes to `backend:8765`, exactly as before. The
pattern is `WEB_ROUTED_PATH` in `apps/web/src/server/webRouting.ts`, whose test checks that it
matches every read route `apps/web` serves and none of Kotlin's (`/api/pois/{id}/campsites`,
`/api/pois/availability/stays`, `/api/me`, the pages), and that the Caddyfile carries it verbatim.
`scripts/test_caddy_routing.py` runs the real `caddy` image with stub upstreams and checks where
each path lands.

Caddy's global `trusted_proxies static private_ranges` lets the client address that
`cloudflared` puts in `X-Forwarded-For` through to Kotlin; without it Caddy replaces the header
with its own peer and every caller shares one rate-limit key on the availability routes.

### Rollout and rollback

1. Set the password once: `./secrets/manage.py set WEB_DB_PASSWORD common`, and commit the vault.
2. Merge. The deploy creates the role, starts `web`, and recreates Caddy with the new block.
   Cloudflare still sends `roadtrip.floo.ca` straight to the backend, so no traffic reaches `web` yet.
3. On mini-ca, from the release directory:
   `docker compose --profile web exec web node -e "fetch('http://127.0.0.1:3000/api/pois/search?q=Mahood').then(async (r) => console.log(r.status, await r.text()))"`
   must print `200` and Mahood Lake.
4. In Cloudflare Zero Trust, change the `roadtrip.floo.ca` public hostname's service from the
   backend to `http://caddy:80`. Check `https://roadtrip.floo.ca/api/pois/search?q=Mahood`, that the
   map's pins and drawer work, and that a signed-in session stays signed in.

Note the hostname's current service before changing it. To roll back, set it back to that value:
every path returns to Kotlin at once, with no deploy.

Once the hostname points at Caddy, never deploy or roll back to a release older than the
`roadtrip.floo.ca` block in `caddy/Caddyfile` without first pointing the hostname back. That
release's Caddyfile has no such block, so Caddy refuses the connection, or, with a sandbox up,
answers every request with an empty 200, and the whole site is down. `deploy.sh`'s own rollback
is safe: it returns only to the last release that passed its health gate, which has the block.

## Vercel

| Setting | Value |
|---|---|
| Project | `roadtrip-web` (team `wwchens-projects`), linked to `wwchen/roadtrip` |
| Root Directory | `apps/web` |
| Include files outside root directory | enabled (`sourceFilesOutsideRootDirectory`) |
| Install command | `cd ../.. && npm ci` |
| Build command | `cd ../.. && npm run build` |
| Function region | `iad1` (Neon is `us-east-2`, the same coast) |

The commands run from the Root Directory, so each starts with `cd ../..` to execute at the
repository root. The root build is the command `make test` and CI run; it builds `packages/*`
and `frontend`'s Vite bundle before `next build`. `apps/web`'s own build
(`scripts/copy-frontend.mjs`) empties `apps/web/public`, copies the Vite build and
`data/us-states.geojson` into it, and fails when the Vite build is missing; `/` is rewritten to
`/index.html`. `next.config.ts` maps Vercel's `VERCEL_ENV`, `VERCEL_GIT_COMMIT_SHA` and
`VERCEL_GIT_COMMIT_REF` to `BUILD_ENV`, `BUILD_SHA` and `BUILD_BRANCH`, inlined at build time for
`/api/build-info`.

Environment variables, Preview and Production, none prefixed `NEXT_PUBLIC_`:

| Name | Type | Value |
|---|---|---|
| `DATABASE_URL` | sensitive | the Neon **pooled** endpoint as the `roadtrip_web` role, plus `connect_timeout=10&idle_timeout=20` |
| `DATABASE_PREPARE` | plain | `true` |
| `DATABASE_REQUIRE_READ_ONLY` | plain, optional | default `true`; set `false` for **Preview only**, whose branch-scoped `DATABASE_URL` from the Neon integration logs in as the branch owner, which is not read-only |
| `ENABLED_DATA_PROVIDERS` | plain, optional | default `campflare,bcparks-strapi,aspira,recgov,reserveamerica,reservecalifornia`, the same campground providers as Kotlin's `read-path.enabled-data-providers`; set but empty is a `ConfigError`, never "none enabled" |
| `HEALTH_DATABASE_TOKEN` | sensitive | the bearer secret for the database mode of `/api/health` (below) |
| `MAPBOX_TOKEN` | sensitive | Mapbox token for `/api/geocode`, `/api/route` and `/api/pois/on-route`; unset, those answer 503 and the rest of the map works |
| `CAMPGROUND_SEARCH_MAX_RESULTS` | plain, optional | cap on `POST /api/campgrounds/search` ids, default 5000 |
| `ENABLED_AVAILABILITY_PROVIDERS` | plain, optional | default `aspira,campflare,reserveamerica,reservecalifornia`, Kotlin's read-path list; picks the provider whose booking links the campsite list shows (the matrix takes its window from mini-ca) |
| `AVAILABILITY_TTL_SECONDS` | plain, optional | default `7200`; a matrix cell older than this counts as stale |
| `AVAILABILITY_EGRESS_URL` | plain, optional | `https://roadtrip-egress.floo.ca`; with the two Access variables, how the matrix reaches mini-ca's availability |
| `AVAILABILITY_EGRESS_ACCESS_CLIENT_ID`, `AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET` | sensitive, optional | the Cloudflare Access service token in front of `roadtrip-egress.floo.ca`, sent as `CF-Access-Client-Id` and `CF-Access-Client-Secret`; the only credential the egress call carries |

Deployment protection (checked 2026-10-29): Vercel Authentication is on with
`all_except_custom_domains`. The production alias `roadtrip-web-nu.vercel.app` answers without a
login; the per-deployment, branch and team-slug URLs redirect to Vercel login.

The first deployment of a project with no production deployment is assigned the production
target, whatever its branch. The first build here was of `claude/nextjs-parallel-read-app-19ceab`.

## Availability on Vercel

Neon holds the catalog (POIs, campgrounds, campsites). Availability lives only in mini-ca's
Postgres, where Kotlin's read-through and pollers already keep it. Vercel stores no availability.

- `GET /api/pois/{id}/campsites/availability` takes the campground and its campsites from Neon,
  then asks mini-ca for what it has stored (`mode: "stored"`, 5-second timeout). mini-ca resolves
  the window against its serving provider's horizon and widest window, answers the latest bookable
  date, lists the campsites it knows, and reads its own Postgres; it never calls a vendor on this
  path. Freshness counts only campsites mini-ca knows, so a campsite missing from its catalog shows
  unknown without keeping the week stale. A window mini-ca refuses is passed through as its `400`.
  If mini-ca is unreachable or the egress is not configured, the GET answers
  `503 availability_unavailable`.
- When the week is stale, the page sends one `POST /api/pois/{id}/campsites/availability/refresh`.
  It answers `202` at once and, after the response, asks mini-ca for `mode: "refresh"`: mini-ca's
  read-through fetches from the vendor through its one rate limiter and stores the result in its
  own Postgres. The page polls the GET every 2 seconds, at most 15 times, until the week is fresh.

Both are on only where `AVAILABILITY_EGRESS_URL` and both `AVAILABILITY_EGRESS_ACCESS_CLIENT_ID`/
`_SECRET` are set; a partial set fails at configuration. Unset, the matrix GET answers `503` and
the refresh POST answers `501`. **Rollback is unsetting `AVAILABILITY_EGRESS_URL`.**

How the call reaches mini-ca:

1. The Cloudflare tunnel public hostname `roadtrip-egress.floo.ca` points at `http://caddy:80`. A
   tunnel hostname is reachable by anyone, so a Cloudflare Access application guards it: a Service
   Auth policy admits only requests carrying the service token in
   `AVAILABILITY_EGRESS_ACCESS_CLIENT_ID`/`_SECRET`, and Cloudflare refuses the rest at its edge
   before anything reaches mini-ca.
2. Caddy's `roadtrip-egress.floo.ca` block proxies only `POST /api/internal/availability/fetch` to
   the backend and answers `404` to everything else; every other host answers `404` for
   `/api/internal/*`.
3. The backend mounts that route only when `AVAILABILITY_EGRESS_ENABLED` is `true`: on in
   `docker-compose.yml` (production), off in sandboxes. It has no credential of its own. Cells are
   named by `(data_provider, data_provider_ref)` and carry the time mini-ca observed them.

Production needs one Vercel Firewall rule, set by the owner: rate-limit
`POST /api/pois/*/campsites/availability/refresh` to 30 requests per minute per IP, the same
ceiling Kotlin puts on its matrix read (`ip-rate-limit-per-minute`), because each refresh can
reach a vendor.

To turn it on:

1. Create the tunnel hostname `roadtrip-egress.floo.ca` → `http://caddy:80`. In Cloudflare Zero
   Trust, create a service token and an Access application for the hostname whose policy is
   **Service Auth** including that token.
2. Deploy mini-ca; production's compose turns the egress route on.
3. Set the three variables in Vercel Production and add the Firewall rule.
4. Open a stale campground's matrix on the deployment and watch it refresh.

## Previews

Each preview deployment gets its own Neon branch, `preview/<git branch>`, from the Neon Postgres
integration on the Vercel project. The integration branches from `main`, sets the branch's
`DATABASE_URL` as a branch-scoped Preview variable, connects as its default (owner) role, and
deletes the Neon branch when the git branch goes. So a preview reads a copy of the full catalog
and can write to its own branch; it does not exercise `roadtrip_web`'s read-only path.

`.github/workflows/preview-check.yml` runs on Vercel's `vercel.deployment.success` repository
dispatch. For a preview, it searches a small bbox around Mahood Lake through
`/api/campgrounds/search`, opens that campground's page, and requires both to answer 200 with the
name, then posts the `Preview check` commit status on the deployed commit. It reaches the
protected deployment with the `x-vercel-protection-bypass` header, which needs Protection Bypass
for Automation enabled on the project and its secret stored as the repository secret
`VERCEL_AUTOMATION_BYPASS_SECRET`. A repository dispatch runs the workflow as it is on the
default branch.

## Health endpoint

`/api/health` has two modes, because Neon autosuspends an idle compute and a public endpoint that
queried on every hit would let any crawler or uptime checker hold the database awake and billed.

| Request | Result | Touches the database |
|---|---|---|
| no `Authorization` header | `200 {"status":"ok"}` | **no**: it proves only that the function is running |
| `Authorization: Bearer <HEALTH_DATABASE_TOKEN>` | the probe: `200 {"status":"ok"}` or `503 {"status":"unavailable"}`, with a 12 second race | yes |
| any other `Authorization` header (wrong secret, wrong scheme, empty) | `401 {"status":"unauthorized"}` with `WWW-Authenticate: Bearer` | no |

**An uptime checker should use the plain request** to learn the site is up, and the bearer form,
at a modest interval, only if it must learn the database is reachable. Each bearer hit wakes a
suspended compute, so the interval sets the minimum the database is awake.

Decisions: the secret travels in a **header**, not a query parameter, because a query string lands
in access logs and referrers. The probe's race (`DATABASE_PING_TIMEOUT_MS`, 12 s) is deliberately **longer** than the `roadtrip_web` role's 10 s `statement_timeout`: on a stuck database Postgres aborts the query first and the probe reports what the database said, not a guess of its own, and a compute resume (3.45 s measured) has more than 3x headroom. Do not tidy the two to match; the race must stay above the statement timeout. A refused database request gets **401**, not 404: a checker with a
stale token should be told it is an authentication problem, and the token is the secret, not the
existence of the mode. The check **fails closed**: if `HEALTH_DATABASE_TOKEN` is unset, empty or
only whitespace, no header authenticates, and a configured value is compared exactly and never
trimmed. Comparison is `timingSafeEqual` over SHA-256 digests of both values, so neither length nor
content leaks through timing. The unauthenticated path does not construct a database client; a
test with the real service asserts `serverDb()` and the ping are not called. The variable is
server-only and must never be `NEXT_PUBLIC_`-prefixed. It is set in Vercel (sensitive, Production
and Preview) and is not declared in `secrets/registry.yaml`, which is generated into Docker
Compose and has no Vercel consumer.

## Neon

| | |
|---|---|
| Project | `square-sky-33743589`, Postgres 16, `aws-us-east-2` |
| Database / role | `roadtrip` / `roadtrip` |
| Compute | 0.25 CU fixed, autosuspend at the default 5 minutes |
| Logical size limit | 1 GiB (the account's `branch_logical_size_limit`) |
| PostGIS | 3.3.10 (production and the test containers run 3.4) |
| Direct endpoint | `ep-dry-cake-b5ph0ri3.c-7.us-east-2.aws.neon.tech` (migrations only) |
| Pooled endpoint | `ep-dry-cake-b5ph0ri3-pooler.c-7.us-east-2.aws.neon.tech` (the app) |

## Connection settings, measured 2026-10-29 against the pooled endpoint

| Setting | Value | Evidence |
|---|---|---|
| `prepare` | `true` | 1164 queries over 12 fresh clients and one long-lived client at concurrency 24 to 30: 1164 succeeded with `prepare: true` and 1164 with `prepare: false`, no `prepared statement ... does not exist` or `already exists` errors in either. Wall time was the same within 3%. `true` is therefore safe and is kept for the plan reuse. |
| `connect_timeout` | 10 s, in the URL | A suspended compute resumed inside the first connection in about 1 s (0.97 s measured directly, 0.85 s through the deployed `/api/health`, which then queried on every hit). 10 s is ten times that and well under the platform's own limit. postgres.js default is 30 s. |
| `idle_timeout` | 20 s, in the URL | A frozen serverless instance should not hold sockets the pooler will have closed; the pooler multiplexes, so reconnecting is cheap. |
| `max` | postgres.js default, 10 | **Not settable from the URL.** postgres.js does not coerce `max`, and builds its slots with `Array(options.max)`, so `?max=5` yields `Array('5')`, one slot, whatever the number. Setting it deliberately needs a `max` option on `createDb` in `packages/db`. 10 per instance is tolerable behind the pooler; revisit if instance counts grow. |
| statement timeout | 10 s, on the `roadtrip_web` role | Not sendable from the client (Neon ignores startup parameters), but a **role-level** setting works, with the pooler caveat under "Server-side timeouts on Neon" below. |

`connect_timeout` and `idle_timeout` are carried in `DATABASE_URL` because postgres.js reads them
from the query string; changing them is a Vercel env edit, not a code change.

### Neon ignores startup parameters, so read-only is a role setting

The original design set `default_transaction_read_only=on` as a startup parameter in
`openServerDb`. **That does not work on Neon.** Neon ignores startup parameters on both the direct
and the pooled endpoint: `SHOW default_transaction_read_only` through `openServerDb` answers `off`
on both, `statement_timeout` sent the same way reads back `0`, and a `CREATE TABLE` through it
succeeded when connected as the owner role. The earlier claim in the plan that the parameter
guards against accidental writes was true on a plain Postgres (the local test fixture) and false
here; Task 3's read-only test passes locally and says nothing about Neon. The parameter stays in
`openServerDb` because it still works against ordinary Postgres, but on Neon it does nothing.

Because the parameter cannot be trusted, `serverDb` no longer trusts it. On the first use of the
handle it runs `SELECT current_setting('default_transaction_read_only')` and throws
`DatabaseNotReadOnlyError` unless the answer is `on`; an unreadable answer is refused too, rather
than assumed read-only. So the guarantee is now checked wherever the app runs instead of being
asserted by a parameter the target ignores, and a `DATABASE_URL` pointed at a writable role fails
closed on the first read instead of quietly permitting writes. The check is memoised per process,
and a failed check is retried rather than poisoning the handle. Two limits worth knowing: it is a
login-time snapshot, so it cannot see a later `ALTER ROLE` (and under the pooler it reads whatever
the first pooled backend reports — see "Server-side timeouts on Neon"), and a session that holds
the grants could still `SET transaction_read_only = off`. The grants, not this check, are the
backstop.

Previews skip the check. The Neon integration gives each preview a `DATABASE_URL` for its own
branch as the owner role, which is not read-only, so every database route answered 500. Preview
sets `DATABASE_REQUIRE_READ_ONLY=false`; a stray write there lands on that preview's disposable
copy of `main`. Production leaves it unset and keeps the check.

What does work on Neon is a **role-level setting**, applied by the server at login whatever the
client sends. The app connects as a dedicated role, `roadtrip_web`:

```sql
CREATE ROLE roadtrip_web LOGIN PASSWORD '...' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE roadtrip_web SET default_transaction_read_only = on;
GRANT USAGE ON SCHEMA public TO roadtrip_web;
GRANT SELECT ON pois, campgrounds, poi_campgrounds, campsites, tesla_superchargers, poi_tesla_superchargers, planet_fitness_locations, poi_planet_fitness_locations TO roadtrip_web;
```

Those eight tables are everything the app reads (`campsites` through `campground_site_summary`). The list lives in `apps/web/src/server/repo/webReadGrants.ts`; `webReadGrants.test.ts` runs every query the app makes as a role holding only those grants, and fails if this `GRANT` line drifts from it.
Checked 2026-10-29 as `roadtrip_web` through the **pooled** endpoint and through the direct one:
`SHOW default_transaction_read_only` is `on`; `INSERT`, `UPDATE`, `DELETE` and `CREATE TABLE` fail
with `cannot execute ... in a read-only transaction`; with the transaction forced to
`READ WRITE` they fail instead with `permission denied for table pois`, `... for table campsites`
and `... for schema public`; `SELECT` on `api_cache` is `permission denied`. The role setting
survives Neon's pooler.

This guards against accidental writes. It is **not a security boundary**, for two reasons: a
session can `SET transaction_read_only = off` (the grants, not the setting, are what stop it then),
and the owner role `roadtrip` and its credentials still exist and are the ones migrations use.
The role's password lives only in Vercel's `DATABASE_URL`.

## Geometry fault messages on PostGIS 3.3.10

Run directly and through `searchWithinBoundary` against both Neon endpoints. The messages are
identical to PostGIS 3.4.3's:

| Input | Message |
|---|---|
| `{}` | `invalid GeoJSON representation` |
| `{"type":"Polygon","coordinates":"nope"}` | `The 'coordinates' in GeoJSON are not an array` |
| `{"type":"Polygon","coordinates":[1,2]}` | `The 'coordinates' in GeoJSON ring are not an array` |
| `{"type":"Polygon","coordinates":[[1,2]]}` | `The 'coordinates' in GeoJSON are not sufficiently nested` |
| `{"type":"Bogus","coordinates":[]}` | `invalid GeoJson representation` |
| `[]`, `"x"` | `unknown GeoJSON type` (not classified; a 500 on 3.3 and 3.4 alike) |

Through the app the boundary is always built from a validated `bbox`, so none of these is
reachable over HTTP.

## Populating the database

The catalog is built by the project's own importer, never copied from production, in two hops that
touch no production tooling:

1. A throwaway local Postgres (`postgis/postgis:16-3.4`) and the Kotlin backend from this
   worktree, run with `ROADTRIP_PROFILE=local` on a spare port. The backend's working directory
   is `backend/`, so `data/raw` is reached through a temporary `backend/data -> ../data` symlink.
   `make data-import TARGET=<name> ADMIN_BASE=http://127.0.0.1:<port>` for `Campflare Campgrounds`,
   `Washington State Parks`, `BC Provincial Parks`, `Parks Canada`, `Campflare Campsites`.
2. `pg_dump --data-only --no-owner --no-privileges` of `pois`, `campgrounds`, `campsites` and
   `poi_campgrounds`, piped into `psql --single-transaction` on the Neon **direct** endpoint, whose
   schema came from `npm run migrate --workspace @roadtrip/db`.

Row counts matched on both sides, and so did an md5 over the ordered `campsites` ids and over the
`pois`/`poi_campgrounds` pairs:

| Table | Rows |
|---|---|
| `pois` | 11,281 |
| `campgrounds` | 11,281 (campflare 10,963; aspira 175; bcparks-strapi 143) |
| `poi_campgrounds` | 11,281 |
| `campsites` | 299,857 |

Neon logical size after the load: 596 MB of the 1 GiB limit (`campsites` is 512 MB of it).
Planet Fitness and Tesla were not moved: this app reads neither.

The Kotlin backend is never pointed at Neon: its Flyway `R__grafana_reader_grants.sql` carries the
same `GRANT pg_read_all_stats` that `0001_raw_objects.sql` used to, and Neon cannot run it.

### Server-side timeouts on Neon

Measured 2026-10-29 with throwaway roles (dropped afterwards), so a reliable server-side
`statement_timeout` **is** available, through exactly one route:

| Route | Direct endpoint | Pooled endpoint |
|---|---|---|
| startup parameter (what postgres.js `connection` sends) | ignored | ignored |
| `options=-c statement_timeout=...` in the URL | works | **rejected** at connect: "unsupported startup parameter in options" |
| `ALTER ROLE ... SET statement_timeout` | works at once | works for **new** server connections only |
| `BEGIN; SET LOCAL statement_timeout ...; <query>; COMMIT` | works | works (transaction-scoped, so safe under transaction pooling) |
| session `SET statement_timeout` | works | **leaks**: it stuck to the pooled server connection and showed up in later, unrelated sessions |

The pooled caveats are why a role setting can look inert. Neon's pooler keeps server connections
open, and a role setting is applied only when a backend starts: after `ALTER ROLE`, existing
pooled backends keep the old value until they end, which is how a fresh client connection read
`statement_timeout = 0` right after the setting was applied. After changing a role setting, end
that role's backends (`SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '...'`).
`pg_sleep(5)` was then cancelled at 3 s through the pooled endpoint on a probe role carrying the
same two settings as `roadtrip_web`.

The pooler also pins reported parameters per role: `default_transaction_read_only` is reported by
the server, and the pooler held the value it first saw for a role, so changing that one at role
level after the role has connected through the pooler had no effect (a role later set to `off`
still read `on` through the pooler and `off` directly). That fails safe for read-only, but it means
a role's `default_transaction_read_only` must be set before its first pooled connection; a role
created, connected, and then altered can disagree with its own configuration.

`roadtrip_web` carries `default_transaction_read_only = on` and `statement_timeout = 10s`. It was set to 3 s first and raised to 10 s: the first query after a redeploy or a compute resume measured 1.3 s on a cold cache, leaving only ~2.2x headroom, and since the bbox area cap is the primary bound this timeout only has to catch an unknown pathological plan. A timeout that fails a legitimately cold request is a worse failure than a loose bound. The
bbox area cap and this timeout are the two bounds on `/api/campgrounds/search`; the cap keeps
ordinary requests far under the timeout (the slowest in-limit measurement was 183 ms).

### The `pg_read_all_stats` grant

Granting a predefined role needs superuser or `ADMIN OPTION` on it; no Neon role has either.
`0001_raw_objects.sql` now guards that one statement with
`pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER WITH ADMIN OPTION')` and raises a notice
otherwise. The other `grafana_reader` statements are object privileges and run on Neon as written.

The Drizzle copy of that grant and Flyway's `R__grafana_reader_grants.sql` therefore differ on
purpose: the Drizzle one is capability-gated, and Flyway's, which production runs on every boot,
is untouched. The schema equivalence gate cannot see the difference, because it dumps with
`--no-privileges` and a role-membership grant is cluster-level and never emitted.

## Known query cost and the bbox cap

`/api/campgrounds/search` is public. Its plan depends on a row
estimate of 1 for the spatial filter on `pois`, which, past a certain box size, makes the planner
nest a sequential scan of `poi_campgrounds` inside the loop over matching POIs (121 million
filtered rows for a continental box). The same plan appears on PostGIS 3.4, so it is not a Neon
artefact. The query itself is unchanged; the endpoint instead refuses a box with south greater
than north (a signed area would otherwise be negative and slip past the cap) and boxes over
`MAX_BBOX_AREA_SQUARE_DEGREES` (36, in `apps/web/src/server/boundary.ts`) with a 400, and `/search`
renders the same message as its alert. A `bbox` given more than once is refused the same way on
both surfaces (a 400 on the route, the alert on the page) rather than answering for whichever value
happens to come first.

Measured on Neon at 0.25 CU (`EXPLAIN ANALYZE` of the search query, 2026-10-29):

| Box | Square degrees | Database time |
|---|---|---|
| default (Lake Tahoe) | 0.56 | 73 to 80 ms deployed |
| 3x3 up to 7x7 centred on the densest area (N California) | 9 to 49 | 9 to 16 ms |
| 24.5x2, 2x24.5, 6x6 Colorado, 7x7 east coast | 36 to 49 | 27 to 145 ms (the high end is a cold cache) |
| 8x8 | 64 | 795 ms |
| 7.75x7.75 | 60 | 660 ms |
| 10x10 | 100 | 0.99 s |
| 20x20 | 400 | 2.6 s |
| `-125,42,-116,49` | 63 | 0.85 to 1.8 s deployed |
| `-130,25,-65,50` | 1625 | 9.9 s deployed |

The cost is not smooth: the plan flips between 49 and 60 square degrees, and a 16x4 box of 64
square degrees stayed fast, so area is a proxy for the plan rather than the cause. 36 square
degrees (6 by 6 degrees, a state-sized viewport) is 27% under 49, the largest box that was fast in
every sample, and 40% under 60, the smallest slow one; every measured case at or under it took
150 ms or less. The cap sits just under a flip that depends on planner statistics, so do not raise
it without re-measuring, and re-measure if the data or the statistics change.
