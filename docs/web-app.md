# Web app deployment (Vercel + Neon)

`apps/web` is the read-only Next.js app: `/campgrounds/[poiId]`, `/search`,
`/api/campgrounds/search`, `/api/health`. It runs on Vercel against a Neon Postgres. The Kotlin
stack on mini-ca is untouched and keeps serving production.

Nothing in this file is a secret. The connection string itself lives only in Vercel and in the
operator's own scratch file; this file records its shape and the settings measured against it.

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
before `next build`, and it also builds `frontend`'s Vite bundle, which Vercel never serves. That
cost is deliberate.

Environment variables, Preview and Production, none prefixed `NEXT_PUBLIC_`:

| Name | Type | Value |
|---|---|---|
| `DATABASE_URL` | sensitive | the Neon **pooled** endpoint as the `roadtrip_web` role, plus `connect_timeout=10&idle_timeout=20` |
| `DATABASE_PREPARE` | plain | `true` |
| `ENABLED_DATA_PROVIDERS` | plain | `campflare,bcparks-strapi,aspira` |

Deployment protection (checked 2026-10-29): Vercel Authentication is on with
`all_except_custom_domains`. The production alias `roadtrip-web-nu.vercel.app` answers without a
login; the per-deployment, branch and team-slug URLs redirect to Vercel login.

The first deployment of a project with no production deployment is assigned the production
target, whatever its branch. The first build here was of `claude/nextjs-parallel-read-app-19ceab`.

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
| `connect_timeout` | 10 s, in the URL | A suspended compute resumed inside the first connection in about 1 s (0.97 s measured directly, 0.85 s through the deployed `/api/health`). 10 s is ten times that and well under the platform's own limit. postgres.js default is 30 s. |
| `idle_timeout` | 20 s, in the URL | A frozen serverless instance should not hold sockets the pooler will have closed; the pooler multiplexes, so reconnecting is cheap. |
| `max` | postgres.js default, 10 | **Not settable from the URL.** postgres.js does not coerce `max`, and builds its slots with `Array(options.max)`, so `?max=5` yields `Array('5')`, one slot, whatever the number. Setting it deliberately needs a `max` option on `createDb` in `packages/db`. 10 per instance is tolerable behind the pooler; revisit if instance counts grow. |
| statement timeout | **none** | Neon ignores startup parameters on both endpoints (below), so `statement_timeout` cannot be sent from the client. It could be set at role level the way read-only is; the bbox area cap bounds the query instead. |

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

What does work on Neon is a **role-level setting**, applied by the server at login whatever the
client sends. The app connects as a dedicated role, `roadtrip_web`:

```sql
CREATE ROLE roadtrip_web LOGIN PASSWORD '...' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE roadtrip_web SET default_transaction_read_only = on;
GRANT USAGE ON SCHEMA public TO roadtrip_web;
GRANT SELECT ON pois, campgrounds, poi_campgrounds, campsites TO roadtrip_web;
```

Those four tables are everything the app reads (`campsites` through `campground_site_summary`).
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

### The `pg_read_all_stats` grant

Granting a predefined role needs superuser or `ADMIN OPTION` on it; no Neon role has either.
`0001_raw_objects.sql` now guards that one statement with
`pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER WITH ADMIN OPTION')` and raises a notice
otherwise. The other `grafana_reader` statements are object privileges and run on Neon as written.

## Known query cost and the bbox cap

`/api/campgrounds/search` is public and has no server-side timeout. Its plan depends on a row
estimate of 1 for the spatial filter on `pois`, which, past a certain box size, makes the planner
nest a sequential scan of `poi_campgrounds` inside the loop over matching POIs (121 million
filtered rows for a continental box). The same plan appears on PostGIS 3.4, so it is not a Neon
artefact. The query itself is unchanged; the endpoint instead refuses boxes over
`MAX_BBOX_AREA_SQUARE_DEGREES` (36, in `apps/web/src/server/boundary.ts`) with a 400, and `/search`
renders the same message as its alert.

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
150 ms or less. If the data or the
statistics change, re-measure before raising it.
