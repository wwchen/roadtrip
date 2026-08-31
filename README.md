# Roadtrip Map

Personal web map for roadtripping a Tesla. Live at [roadtrip.floo.ca](https://roadtrip.floo.ca).

## Layers

- **Tesla Superchargers** — supercharge.info, live fetch (US + Canada)
- **Planet Fitness** — OSM Overpass
- **Campgrounds** — USCampgrounds.info (federal/state/local, US) + BC Parks (BC provincial) + hand-curated Parks Canada (BC national parks), with US federal sites enriched from recreation.gov (photos, ratings, per-carrier cell coverage, containing unit name)
- **National & State Parks** — USGS PAD-US polygons
- **Basemap picker** — OpenFreeMap (Liberty/Bright/Positron), Carto (Voyager/Positron/Dark), OSM, plus an Esri satellite overlay
- **Geolocation** — standard geolocate control

## Getting started

First time only:

```sh
make install                 # Homebrew deps (incl. sops + age) + companion (npm + playwright) + git hooks
./secrets/manage.py init     # mint this host's age key — see docs/secrets.md
```

Runtime secrets live encrypted in `secrets/` and are mounted into containers
at `/run/secrets`; nothing writes a plaintext `.env`. Until the public key
printed by `init` is added as a vault recipient (someone who can already
decrypt adds it to `secrets/.sops.yaml` and runs `./secrets/manage.py rotate`),
`tilt up` and `make run` refuse to boot. `./secrets/manage.py ls` shows what
exists, `set` changes a value, and committing it is the deploy. Full details in
**[docs/secrets.md](docs/secrets.md)**.

Then bring up the stack:

```sh
tilt up                  # full dev stack: Postgres/backend/Grafana/observability/Rec.gov companion
make run                 # backend on the host; Postgres + Rec.gov companion in Docker
# production: merge to master; the deploy workflow pulls immutable images
```

Where to go next:

- **[CONTRIBUTING.md](CONTRIBUTING.md)** — the full clone-to-first-PR path
  (tests, hooks, review process, docs index).
- **[docs/glossary.md](docs/glossary.md)** — the project vocabulary: watch,
  poller, slot, companion, ATC, governor, …
- **[docs/first-run.md](docs/first-run.md)** — what a healthy first `tilt up`
  looks like, and why the map starts empty.
- **[docs/observability.md](docs/observability.md)** — the
  Grafana/Loki/Tempo/Prometheus/Alloy stack.
- **[rfcs/](rfcs/)** — accepted architecture and process decisions.

A deploy host needs neither Tilt nor Node and should skip `make install`;
**[docs/installation.md](docs/installation.md)** splits the two setups and
lists version requirements.

## Local dev

`tilt up` is the easiest path for full-stack dev: Tilt uses Docker Compose
for Postgres, the backend container, Grafana, Loki, Tempo, Prometheus, Alloy,
and the Rec.gov companion HTTP executor. The backend serves the app on
<http://127.0.0.1:8765>; the Tilt UI is at <http://localhost:10350>.

The stack ships with full observability: the backend runs under the
OpenTelemetry Java agent, logs flow from Docker stdout through Alloy into Loki,
traces to Tempo, metrics to Prometheus, and Grafana
(<http://127.0.0.1:3000>) correlates all three. Alert rules are provisioned and
route to Slack. The pipeline's shape — Loki's three log tiers, why logs ride
stdout instead of OTLP, the backend's domain metrics, retention, dashboards —
is documented in **[docs/observability.md](docs/observability.md)**.

Plain `make run` remains the fastest backend-only loop: it starts Postgres and
builds + starts the Rec.gov companion in Docker, then runs the Kotlin/Ktor
backend on the host with Gradle. Production deploys run `scripts/deploy.sh prod`
from a commit-pinned release archive. The host pulls exact application,
companion, and data images and updates Compose; it builds nothing and has no Git
checkout. `make run env=prod` is only a compatible manual wrapper.

The Tilt UI also has a `data` cluster of manual-trigger background workers
(none auto-run on `tilt up`) for POI refresh.

POI data refresh is a two-step flow. Fetch runs the registry Python fetchers on
the host; import goes through the backend admin API and writes Postgres rows.
Tilt exposes both as manual buttons under the `data` cluster:

```sh
make data-fetch                       # spawn fetchers → data/raw/<source>/<ts>.json
make data-fetch TARGET=campflare-campgrounds-export    # one data_source slug
make data-import                      # data/raw/ → Postgres rows via the ETL
make data-import TARGET='Planet Fitness'               # one poi_data/campsite_data row
```

`data-fetch` runs the Python fetchers declared under `data_sources:`;
`data-import` runs the Kotlin ETL pipeline (parse → validate → transform
→ upsert). Import runs are recorded in `ingest_runs`. Skipping
`data-fetch` is fine — the ETL runs against the newest capture already
on disk.

Fetch targets are `data_sources.slug` values in
`backend/src/main/resources/poi-registry.yaml`. Import targets are the
display names from `poi_data:` and `campsite_data:` rows. Adding a vendor
means appending registry rows, writing the fetcher if needed, and wiring
the Kotlin ETL adapter.

## Refresh POI data

Use **`make data-fetch` then `make data-import`**. Fetchers run on the
host first, then the backend admin API imports the newest raw captures
into Postgres. Tilt buttons trigger the same split path. For an
interactive source picker, run `python3 scripts/poll_raw.py` directly.

### Pipeline shape

Every fetcher is thin: hit upstream, wrap the response in a uniform
envelope, write to `data/raw/<source>/<ts>.json`. No transform, no
merge — those happen in the Kotlin ETL (parse → validate → transform →
upsert) when `data-import` runs.

Envelope shape:

```json
{
  "fetcher":         "fetch_aspira_maps",
  "fetcher_version": "2",
  "fetched_at":      "2026-06-07T21:07:39Z",
  "request":  { "url": "...", "method": "GET", "headers": {...} },
  "response": { "status": 200, "headers": {...} },
  "poller_run_id":   null,
  "payload":         <verbatim upstream JSON|string>
}
```

Source registry lives at `backend/src/main/resources/poi-registry.yaml`.
Run `make data-fetch TARGET=--list` for the current set; abridged:

| Source                | Upstream                                | Output dir |
|-----------------------|-----------------------------------------|------------|
| `osm-pf`              | OSM Overpass — Planet Fitness           | `data/raw/osm-pf/<ts>.json` |
| `uscampgrounds`       | uscampgrounds.info regional CSVs        | `data/raw/uscampgrounds/<ts>/{west,…}.json` |
| `bcparks-strapi`      | bcparks.api.gov.bc.ca (paginated)       | `data/raw/bcparks-strapi/<ts>/page-NNN.json` |
| `apca-{accommodation,places}` | Parks Canada ArcGIS feeds        | `data/raw/apca-accommodation/<ts>/...`, `data/raw/apca-places/<ts>/...` |
| `aspira-maps-{pc,bc,wa}` | Aspira `/api/maps` (one row per host) | `data/raw/aspira-maps-pc/<ts>.json`, `…-bc`, `…-wa` |
| `recgov-campgrounds`  | RIDB /facilities (all RIDB agencies)    | `data/raw/recgov-campgrounds/<ts>/page-NNN.json` |
| `recgov-campground-enrichment` | Recreation.gov rating/cell aggregate API | `data/raw/recgov-campground-enrichment/<ts>/facility-<id>.json` |
| `reserveamerica-{abpp,ny}` | Active Network ReserveAmerica (Alberta, New York) | `data/raw/reserveamerica-<contract>/<ts>/{directory-*,park-*}.json` |
| `reservecalifornia-catalog` | ReserveCalifornia Search All + place/facility/grid API | `data/raw/reservecalifornia-catalog/<ts>/{website-settings,search-all,place-*,facility-*,grid-*}.json` |
| `tesla-index`         | tesla.com get-locations (curl-impersonate) | `data/raw/tesla-index/<ts>.json` |
| `tesla-locations`     | tesla.com per-slug, cache-aware (~30d)  | `data/raw/tesla-locations/<slug>/<ts>.json` |

`backend/src/main/resources/poi-registry.yaml` is the source of truth for governing bodies
(NPS, USFS, BC Parks, Alberta Parks, …), POI data sources, and tenant
args. How catalog rows carry provider identity (`data_provider` /
`booking_provider` and their refs) and how availability dispatch resolves a
provider is documented in
[docs/reservation-providers.md](docs/reservation-providers.md).

**Raw captures.** `data/raw/` is Git-tracked — captures are append-only and
become a new immutable data image when committed. Production materializes that
image into `roadtrip-data-<data-tree-sha>`. The Docker volume survives container
replacement, is traceable to Git, and is reconstructible; sandboxes share it
read-only. Generated `data/etl-out/` remains ignored.

### `/api/docs` — interactive API browser

Swagger UI at `/api/docs`, OpenAPI 3.1 spec at `/api/docs/openapi.json`.
Built from the live routing tree at boot, so the doc reflects whatever's
mounted. To document a new route, use the normal `io.ktor.server.routing`
route function and chain `describeApi`:

```kotlin
get("/api/foo") { /* handler */ }
    .describeApi("group", "One-line description")
```

Routes without a doc block still appear in the spec (untitled). The page is
public — paths and summaries only, no secrets.

### Admin API surface

| Verb | Path | Returns |
|------|------|---------|
| POST | `/api/admin/data/import[/{target}]` | sync; runs the Kotlin importer phase(s). 200 on success/noop, 500 on phase failure |
| GET  | `/api/admin/data/runs[?target=…]` | last 50 parent runs |
| GET  | `/api/admin/data/runs/{id}` | parent + ordered phase rows |
| GET  | `/api/admin/data/status` | per-target last completed status + age |

Without a `{target}`, import fans out across every known import target
sequentially.

**Auth boundary:** Cloudflare Zero Trust path rule on `/api/admin/*` — same
tunnel that already fronts the deploy. Workload is idempotent +
non-sensitive (refresh trigger + status read). No in-app token. Locally the
routes are reachable on `127.0.0.1:8765` directly. **If you ever expose dev
to the public internet (port-forward, ngrok, etc.), bind admin routes to
loopback only first.**

The admin import API reads captured data but writes imported rows only to
Postgres. Local Compose mounts `./data` read-write for fetch loops; production
mounts the SHA-addressed data volume read-only at `/app/static/data`. New
production data becomes available after captures are committed and CI publishes
the resulting data-tree image.

## Deploy via Docker + Cloudflare tunnel

1. **Create a Cloudflare tunnel.** Zero Trust → Networks → Tunnels → Create
   tunnel; set the public hostname to route to `http://backend:8765`. Copy the
   tunnel token. The tunnel's public hostname routing is managed in Cloudflare;
   Compose only starts `cloudflared` with the token.

2. **Secrets on the deploy host:** nothing to place by hand. They ride along
   encrypted in `secrets/`, and `make run env=prod` decrypts them with the
   host's own age key into `/run/secrets` mounts. The host needs an age key
   once (`./secrets/manage.py init`, add its public key to `secrets/.sops.yaml`,
   then `rotate` from a machine that can already decrypt). After that, changing
   a secret is `./secrets/manage.py set NAME prod` plus a commit — the same
   release archive that deploys code also carries the encrypted value. See
   [docs/secrets.md](docs/secrets.md).

   Grafana state is stored in the Compose-managed named volume
   `grafana-data` (Docker prefixes it with the Compose project name);
   Tempo and Prometheus use `tempo-data` and `prometheus-data` for local trace
   and metric retention. Dashboard JSON and datasource provisioning stay
   bind-mounted from `grafana/`.
   Dashboard JSON reconciles on Grafana's provisioning poll
   (`updateIntervalSeconds` is >10 so Grafana polls the files rather than
   relying on inotify, which doesn't cross the bind mount). Deploy also
   restarts Grafana so datasource/config changes reload and dashboards refresh
   immediately rather than on the next poll.
   Provisioned dashboard UI saves are disabled by default on deploy hosts; set
   `GRAFANA_DASHBOARD_ALLOW_UI_UPDATES=true` only when you intentionally want
   Grafana to persist UI edits in its database.

3. **Bring up the stack:** GitHub Actions sends the validated commit's small
   release archive to `~/.roadtrip/releases/<sha>` and runs
   `scripts/deploy.sh prod`. That single entrypoint pulls the application and
   companion images, initializes the Git-tree-addressed data volume, updates
   Compose, verifies service state, and prunes unused Roadtrip images. Postgres
   and the other stateful services stay up. No production Git clone is involved.

   The `backend` container serves the map on port 8765 (not exposed to the
   public host — cloudflared talks to it on the compose network).

   Throwaway per-PR/branch environments (backend + Postgres only, auth off)
   are available via `make sandbox` or PR comment `/sandbox`; see
   **[docs/sandbox-deploys.md](docs/sandbox-deploys.md)**.

## Architecture notes

- **Backend.** Kotlin/Ktor + Netty serves the entire site: `/`, `/watches` and
  `/availability` → the React build in `frontend/dist`, `/assets/*` → its hashed
  bundles, `/data/*` → static data (with `/data/raw/*` excluded), plus
  `/api/pois`, `/api/health`, and the availability/watch API described below.
  Postgres+PostGIS holds the imported POI data; Supercharger geometry is live
  from supercharge.info/service/supercharge/allSites.
- **Frontend.** React 18 + TypeScript in `frontend/`, built by Vite into three
  page entries. Components come from LDS via the `@ui` adapter; server state is
  TanStack Query, client state Zustand. Every page is served from the build and
  nothing else — there is no fallback, so an unbuilt `frontend/dist` 404s
  loudly. CI packages that build with the backend JAR into the SHA-tagged image
  used by production and sandboxes; local development overlays `frontend/dist`
  for quick iteration. See [docs/frontend-components.md](docs/frontend-components.md).
- **Campsite availability + watches.** Reservation-provider availability and
  watch/alert management live in the main app: the API is
  `/api/pois/{id}/campsites`, `/api/pois/{id}/campsites/availability`, and
  `/api/watches`, and the UI is the topbar alerts panel, the campground drawer's
  availability grid, the `/availability` admin dashboard, and `/watches`. See
  [docs/reservation-providers.md](docs/reservation-providers.md) for the
  provider abstraction.
- **Map** — MapLibre GL, vector and raster basemaps, runtime style-swap.
  Overlay data is cached in memory and re-installed on every `style.load`
  so basemap swaps don't wipe POIs.

## Campsite availability alerts and watches

The watch poller polls reservation-provider availability (rec.gov, Aspira,
ReserveAmerica, ...) for matching openings against operator-defined watches
and (optionally) auto-claims rec.gov matches by adding them to a real
recreation.gov shopping cart. This lives in the main app — see
[docs/reservation-providers.md](docs/reservation-providers.md) for the
provider abstraction and the `/availability` / `/watches` pages above for
the UI. **The cart-add path requires a
separate companion process or container** — recreation.gov sits behind Akamai, which
flags datacenter IPs and headless Chromium, so a real Chromium running on
the operator's machine is the only thing that lands cart adds reliably. The
backend never touches a browser; it only polls the public availability API,
then calls the companion's one-shot executor.

- **`companion/`** — Node 22.9+ Playwright HTTP service. It runs one
  persistent Chromium profile per user, drives the add-to-cart flow, and
  returns a terminal JSON success/failure to the backend.
  [docs/companion.md](docs/companion.md) owns the contract: routes
  (`POST /atc`, `POST /login`, `POST /verify`, ...), the browser-profile pool
  and its `profile_id` requirement, the shared-secret header, and the
  exposure invariant that keeps the companion off the public internet.
  ```sh
  cd companion
  npm install
  COMPANION_API_TOKEN=dev npm start
  ```
  Operator commands (headed login, forced refresh, a one-shot ATC that places
  a **real** hold) are `make recgov-login`, `make recgov-refresh` and
  `make recgov-atc PAYLOAD=…`; see the companion doc for their contracts and
  for running the Compose `recgov-companion` profile.
- **Slack notifications** are optional. Create a Slack app with the
  `chat:write` scope, install it to the workspace, and paste the bot
  token (`xoxb-…`) plus a channel name (`#camping-alerts`) or channel ID
  into Settings → Slack. The backend posts via `chat.postMessage`.
- **Without the companion**, alerts still fire and Slack still posts —
  every "Auto-add to cart" toggle and the "Test browser session" /
  "Test credentials" buttons in Settings just no-op (`SettingsRoutes`
  returns `not_implemented` for the Chromium-dependent endpoints).
