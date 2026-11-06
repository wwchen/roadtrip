# apps/web on mini-ca: the read API behind roadtrip.floo.ca

**Status:** design approved in conversation 2026-11-04; this document awaits review.

**Parent spec:** `docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md`,
§9 P1's fallback ("Next.js runs on mini-ca in standalone mode") taken for production while Vercel
keeps PR previews.

## Goal

Production traffic for the read API on `roadtrip.floo.ca` is served by `apps/web`, running as a
container on mini-ca against mini-ca's Postgres. Kotlin keeps serving everything else, unchanged.

Success: `roadtrip.floo.ca/api/pois/search?q=Mahood` is answered by the `web` container, the map
and drawer behave as they do today, signed-in users stay signed in, and pointing one Cloudflare
public hostname back at the backend returns every request to Kotlin.

## Non-goals

- Serving pages (`/`, `/poi`, `/assets/*`, `/data/*`) from Next.js. Kotlin keeps them.
- Sign-in, `/api/me`, watches, settings, availability, campsites, admin, Slack, build info and
  health on `roadtrip.floo.ca`. All stay on Kotlin.
- Vendor calls from Next.js. Availability stays out of scope.
- Changing Vercel. It keeps building PR previews against Neon branches.
- Retiring Kotlin, or anything Kotlin owns: migrations stay Flyway's, auth stays Clerk's.

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | Next.js reads **mini-ca's Postgres**, not Neon | Both apps read the same rows, so the map cannot disagree with the parts Kotlin still serves, and nothing syncs. The Drizzle schema is proven equal to Flyway's. |
| D2 | **Only the read API** moves | Kotlin keeps the pages and every signed-in route, so no user loses a feature, and one copy of the frontend build stays authoritative. |
| D3 | The path split is a **Caddy site block** in `caddy/Caddyfile` | Chosen by the owner (2026-11-05, reversing an earlier choice of a Cloudflare tunnel rule). The routing lives in the repo, is reviewed and tested with the real `caddy` image, ships with the release, and reverts with git; Caddy already runs on mini-ca for sandboxes. The cost is one in-network hop for production traffic. |

## Architecture

```
roadtrip.floo.ca ─ Cloudflare tunnel ─ caddy:80 ─┬─ read-API paths ──▶ web:3000      (apps/web, Next.js) ─┐
                                                  └─ everything else ─▶ backend:8765  (Kotlin)            ─┴─▶ postgres:5432
```

All three sit on the default Compose network that `cloudflared` already joins. None publishes a
host port.

### The routed paths

Exactly the read endpoints `apps/web` serves, each with the same methods as Kotlin's route, so a
path-only match never sends a method Next.js lacks:

| Path | Methods (both apps) |
|---|---|
| `/api/pois` | POST |
| `/api/pois/search` | GET |
| `/api/pois/{numeric id}` | GET |
| `/api/pois/on-route` | POST |
| `/api/campgrounds/search` | GET, POST |
| `/api/campgrounds/details` | POST |
| `/api/geocode` | GET |
| `/api/route` | GET |

The Caddy block, `http://roadtrip.floo.ca`, sends paths matching
`^/api/(pois(/(search|on-route|[0-9]+))?|campgrounds/(search|details)|geocode|route)$` to
`web:3000` and everything else to `backend:8765`. Caddy's global
`trusted_proxies static private_ranges` keeps the client address `cloudflared` puts in
`X-Forwarded-For`; without it Caddy would replace the header with its own peer, and Kotlin's
availability rate limits would key every caller the same. The owner then points the
`roadtrip.floo.ca` public hostname at `http://caddy:80` instead of the backend.

The anchors keep `/api/pois/{id}/campsites`, `/api/pois/{id}/campsites/availability` and
`/api/pois/availability/stays` on Kotlin. A non-numeric `/api/pois/{x}` also stays on Kotlin,
which answers it `bad_id` as Next.js would.

## Components

### 1. The image

- `apps/web/next.config.ts` gains `output: 'standalone'`. `outputFileTracingRoot` is already the
  workspace root, so workspace packages and `@photostructure/tz-lookup`'s data are traced.
- Two new targets in the root `Dockerfile`, which already builds every image from one context:
  - **`web-build`:** `node:22-alpine`; `npm ci`, then the root `npm run build`, which builds the
    workspace packages, the Vite frontend and `@roadtrip/web` (whose build runs
    `copy-frontend.mjs` before `next build`).
  - **`web`:** `node:22-alpine`, the `node` user, the standalone output plus `.next/static` and
    `public/`, `CMD ["node", "apps/web/server.js"]`, `PORT=3000`, `HOSTNAME=0.0.0.0`.
  - Build args `BUILD_ENV`, `BUILD_SHA`, `BUILD_BRANCH`, which `next.config.ts` already inlines for
    `/api/build-info`.
- `.dockerignore` gains `apps/*/node_modules`, `apps/web/.next` and `apps/web/public`.

### 2. CI and deploy

- `ci.yml`: the `docker-build` job, which already runs on every push because deploy pulls its
  tag, also builds `--target web` and pushes `ghcr.io/wwchen/roadtrip/web:<sha>`; its PR
  condition gains the `web` filter. The `web-tests` job builds the same target and runs the
  **image smoke test** (Testing, below).
- `deploy.yml`: `ghcr.io/<repo>/web:${DEPLOY_SHA}` joins the "Await the release images" list.
- `scripts/deploy.sh`: exports `ROADTRIP_WEB_IMAGE="ghcr.io/wwchen/roadtrip/web:${app_sha}"` next
  to `ROADTRIP_BACKEND_IMAGE`, and runs the web-reader role step (component 4) after Postgres is
  healthy and before `web` starts.
- `deploy/release-manifest.txt` needs no new entry: the new script sits in `postgres-init/`, which
  the manifest already ships, and `scripts/test_ci_image_builds.py` fails if `deploy.sh` reads a
  file the manifest omits.

### 3. The `web` service

In `docker-compose.yml`, its own profile `web`, which only production's compose invocation in
`deploy.sh` enables. Sandboxes reuse the same compose file under `pois`, and must not start a
service whose image tag they never pull.

- `image: ${ROADTRIP_WEB_IMAGE:-roadtrip/web}`, `restart: unless-stopped`, the shared
  `*log-rotation`, `hostname: web`, `mem_limit: 512m`.
- Environment, none of it secret:
  `DATABASE_URL=postgres://roadtrip_web@postgres:5432/${POSTGRES_DB:-roadtrip}`,
  `DATABASE_PASSWORD_FILE=/run/secrets/web_db_password`,
  `MAPBOX_TOKEN_FILE=/run/secrets/mapbox_token`, `ENABLED_DATA_PROVIDERS` matching the backend's.
- `healthcheck`: a `node -e` fetch of `http://127.0.0.1:3000/api/pois/search?q=ok` (the image has
  no curl). It reads the database as `roadtrip_web`, so a bad password or missing grant fails the
  deploy's `--wait` and rolls it back, rather than shipping a read API that answers 500.
- `depends_on: backend: condition: service_healthy`. Kotlin's Flyway applies the role's grants at
  boot, so a healthy backend means the grants exist.
- Logs reach Loki without change: Alloy's `discovery.docker` picks up every container.

### 4. The database role

`roadtrip_web`: `LOGIN`, no superuser or create rights, `default_transaction_read_only = on`,
`statement_timeout = 10s` (Neon's measured value, `docs/web-app.md`), `SELECT` on exactly `WEB_READ_TABLES` (`apps/web/src/server/repo/webReadGrants.ts`), following the
`grafana_reader` pattern:

- `backend/src/main/resources/db/migration/R__web_reader_grants.sql`: creates the role without a
  password if absent, sets `default_transaction_read_only` and `statement_timeout`, and grants `CONNECT`, `USAGE` on
  `public` and `SELECT` on the eight tables. Repeatable, so a `make reset-db` repairs itself.
- The same SQL in `packages/db/migrations/`, as `grafana_reader`'s role is mirrored there, so the
  schema-equivalence gate stays green.
- `postgres-init/20-web-reader.sh`: creates the role, or resets its password, from
  `WEB_DB_PASSWORD_FILE`, on a fresh cluster. With no password mounted (local, sandboxes) it says
  so and exits 0: only production runs `web`.
- `deploy.sh` runs that same script through `docker compose exec postgres` on every deploy, so the
  existing production cluster (whose data directory is not empty, so init scripts never run) gets
  the role and its current password. The script is idempotent.
- `webReadGrants.test.ts` gains a check that the migration grants exactly `WEB_READ_TABLES`, so
  the two lists cannot drift.

### 5. Secrets

- `secrets/registry.yaml`:
  - `WEB_DB_PASSWORD`, consumers `[postgres, web]`, required in prod. The owner sets its value
    with `secrets/manage.py`.
  - `MAPBOX_TOKEN` consumers become `[backend, web]`.
- `docker-compose.secrets.yml` is regenerated from the registry, as it always is.
- `apps/web/src/server/config.ts` learns the `_FILE` convention: `DATABASE_PASSWORD_FILE` sets the
  password on `DATABASE_URL`, and `MAPBOX_TOKEN_FILE` supplies the token. Setting both a value and
  its `_FILE` is a `ConfigError`; an unreadable file is a `ConfigError` naming the variable. Vercel
  keeps using plain `DATABASE_URL` and `MAPBOX_TOKEN`.

## Data flow

A pin request: browser → Cloudflare → tunnel → `caddy:80` matches `@web` → `web:3000` → `POST /api/pois` →
`serverDb()` (direct connection as `roadtrip_web`, prepared statements on, the read-only check on)
→ `postgres:5432` → JSON back the same way. Any path `@web` does not match goes to `backend:8765`
exactly as today.

## Rollout

1. Merge. Deploy runs: the role step, then `web` starts, and Caddy is recreated with the new block.
   No traffic is routed to it: Cloudflare still sends `roadtrip.floo.ca` to the backend.
2. On mini-ca: `docker compose exec web node -e` fetch of `/api/pois/search?q=Mahood` and
   `/api/build-info`; both answer, and build info names the deployed SHA.
3. The owner points the `roadtrip.floo.ca` public hostname at `http://caddy:80`, noting its
   current service first.
4. Through `roadtrip.floo.ca`: `/api/pois/search?q=Mahood` answers; the map loads, pins and the
   drawer work, a signed-in session stays signed in, and `/api/pois/{id}/campsites` still reaches
   Kotlin.

**Rollback:** point the hostname back at its previous service. Every path returns to Kotlin at
once, with no deploy; the container can stay running.

## Error handling

- `web` down or unhealthy: the routed paths answer 502 from Caddy; Kotlin's paths are
  unaffected. Compose restarts the container.
- Wrong or missing password: `serverDb()` fails and the read routes answer 500. Liveness
  (`/api/health` without a token) stays 200, so the Compose health check does not catch it; rollout
  step 2's search request does, before any traffic is routed.
- A role that is not read-only: the existing `DatabaseNotReadOnlyError` check refuses to serve.
  `DATABASE_REQUIRE_READ_ONLY` stays unset on mini-ca.
- Client addresses: Caddy trusts private-range peers, so `cloudflared`'s `X-Forwarded-For` reaches
  Kotlin with Caddy's hop appended, and `callerFromForwardedFor` skips private hops. Next.js's read
  routes are not rate-limited, the same as Kotlin's today.

## Testing

- `config.test.ts`: `_FILE` reading, value-and-file conflict, unreadable file. Red first.
- `webReadGrants.test.ts`: the migration's grants equal `WEB_READ_TABLES`.
- Image smoke test, in the `web-tests` CI job: start PostGIS, apply the schema and a seed row,
  create `roadtrip_web` with the migration plus the init script, run the built image against it
  with `DATABASE_PASSWORD_FILE`, and require `/api/health` 200 and `/api/pois/search?q=<seed>` to
  return the seed. This exercises the standalone build, the traced dependencies, `_FILE` and the
  read-only check over a direct connection.
- `scripts/test_ci_image_builds.py`: the new image is awaited by `deploy.yml` and published by CI,
  and every file `deploy.sh` reads is in the release manifest.
- `scripts/test_caddy_routing.py`: runs the real `caddy` image with the Caddyfile and stub `web`
  and `backend` upstreams; every routed path lands on `web`, every other on `backend`, and a
  client `X-Forwarded-For` reaches the backend.
- `webRouting.test.ts`: the pattern matches every read route and none of Kotlin's, and the
  Caddyfile carries it verbatim.
- Post-deploy, rollout steps 2 and 4.

## Owner actions

- Set `WEB_DB_PASSWORD` with `secrets/manage.py` before the first deploy that includes `web`.
- Point the `roadtrip.floo.ca` public hostname at `http://caddy:80` after rollout step 2 passes.

## Risks

- **Routing drift.** A route added to `apps/web` without widening `@web` is simply served by
  Kotlin. `webRouting.test.ts` keeps the pattern and the Caddyfile identical.
- **Caddy on the production path.** A Caddy outage now takes `roadtrip.floo.ca` down, not only the
  sandboxes. Rollback is the same hostname change.
- **Rolling back past this change.** A release older than the `roadtrip.floo.ca` block ships a
  Caddyfile without it; with the hostname on Caddy, that release refuses or blanks every request.
  Point the hostname back first. `deploy.sh`'s automatic rollback only returns to the last release
  that passed its health gate, which has the block.
- **Sandboxes can reach production through Caddy.** `caddy` joins the `roadtrip-sandbox` network,
  so sandbox containers can send `Host: roadtrip.floo.ca` to it, reach the production backend and
  `web` without Cloudflare, and, as a private-range peer, set their own `X-Forwarded-For`.
  Sandboxes run the owner's own PRs, so this is accepted rather than fenced off.
- **Two consumers of one Postgres.** Next.js adds read load to the database Kotlin writes to. The
  role's `statement_timeout` (as on Neon, 10 s) bounds a pathological query.
- **Frontend and API from different releases.** Both deploy from the same SHA in the same
  `deploy.sh` run, so the window is one deploy.
