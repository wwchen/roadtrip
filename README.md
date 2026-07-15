# Roadtrip Map

Personal web map for roadtripping a Tesla. Live at [roadtrip.floo.ca](https://roadtrip.floo.ca).

## Layers

- **Tesla Superchargers** — supercharge.info, live fetch; per-site pricing from tesla.com (US + Canada)
- **Planet Fitness** — OSM Overpass
- **Campgrounds** — USCampgrounds.info (federal/state/local, US) + BC Parks (BC provincial) + hand-curated Parks Canada (BC national parks), with US federal sites enriched from recreation.gov (photos, ratings, per-carrier cell coverage, containing unit name)
- **National & State Parks** — USGS PAD-US polygons
- **Basemap picker** — OpenFreeMap (Liberty/Bright/Positron), Carto (Voyager/Positron/Dark), OSM, plus an Esri satellite overlay
- **Geolocation** — standard geolocate control

## Local dev

```sh
tilt up                  # Compose stack (Postgres/backend/Grafana/observability) + host companion
make run                 # Kotlin/Ktor backend on http://127.0.0.1:8765 (serves static + /api)
make companion           # campsite Playwright companion against the local backend
make run env=prod        # on the deploy host: build image + docker compose up
make fetch-tesla-supercharger-pricing  # mint cookies + crawl Tesla Supercharger pricing into data/raw/
```

`tilt up` is the easiest path for full-stack dev: Tilt uses Docker Compose
for Postgres, the backend container, Grafana, Loki, Tempo, Prometheus, and
Alloy, then runs the campsite companion as a host Node process so Playwright
can drive a real Chromium. The backend still serves the app on
<http://127.0.0.1:8765>. Grafana is available at <http://127.0.0.1:3000>,
Tempo at <http://127.0.0.1:3200>, Prometheus at
<http://127.0.0.1:9090>, and Alloy at <http://127.0.0.1:12345>. Local
Compose enables anonymous editor access and provisioned dashboard UI saves so
dashboards can be adjusted in the Grafana UI; those saves live in the local
`grafana-data` volume and are not written back to
`grafana/dashboards/*.json`.
The backend container runs with the OpenTelemetry Java agent. Ktor, JDBC, and
JVM telemetry goes to Alloy over OTLP, Alloy forwards traces to Tempo and
metrics to Prometheus, and existing JSON logs still go through Docker stdout to
Loki. Trace/span IDs are injected into Logback MDC, so Grafana can correlate
logs and traces.
Provisioned dashboards include a catalog explorer
(`/d/roadtrip-catalog-explorer/roadtrip-catalog-explorer`) that covers POIs,
campsites, and snapshot-backed availability, plus status overview, POI detail,
Campground detail, Tesla Supercharger detail/stats, Campsite detail/stats, DB
stats, Roadtrip Metrics (`/d/roadtrip-metrics/roadtrip-metrics`),
watch/scheduler health, and API/SQL equivalence.
Tilt UI is at <http://localhost:10350>.

Plain `make run` remains the fastest backend-only loop: it starts Postgres in
Docker and runs the Kotlin/Ktor backend on the host with Gradle. Production
deploys use `make run env=prod`, which builds the backend image and recreates
the production Compose stack.

The Tilt UI also has a `data` cluster of manual-trigger background workers
(none auto-run on `tilt up`) for POI refresh. Tesla Supercharger pricing
isn't surfaced there — the fetch is interactive (cURL paste) and runs
from a terminal via `make fetch-tesla-supercharger-pricing`.

POI data refresh goes through the backend's admin API. Two-step flow,
two Tilt buttons under the `data` cluster, two make targets:

```sh
make data-fetch                       # spawn fetchers → data/raw/<source>/<ts>.json
make data-fetch TARGET=campgrounds    # one target only
make data-import                      # data/raw/ → Postgres rows via the ETL
make data-import TARGET=planet-fitness
```

`data-fetch` runs the Python fetchers (the same ones `make poll-raw`
dispatches); `data-import` runs the Kotlin ETL pipeline (parse →
validate → transform → upsert). Each phase is recorded in `ingest_runs`;
per-target mutex serializes a fetch and an import on the same target.
Skipping `data-fetch` is fine — the ETL runs against the newest capture
already on disk.

Targets are derived from `backend/src/main/resources/poi-registry.yaml` at boot. Each
`governing_body` slug becomes a multi-source target (refresh every source
under that body), and each `source.id` becomes its own target (refresh
just that one). Adding a vendor = appending a YAML row + writing the
Kotlin ETL impl. Adding a governing body = appending a YAML row.

> Note: `refresh-tesla-cookies` is **Tesla-only**. Recreation.gov auth is
> owned by the companion's logged-in Chromium profile. Two unrelated systems
> that both happen to use the word "cookies."

First time only:

```sh
make install        # Homebrew deps + companion (npm + playwright) + git hooks
```

Pricing is served from the on-disk cache (`data/pricing-cache/`). Tesla is
never called from the user request path — the backend just reads cached JSON
and 404s with `{"error":"not_cached"}` for sites that haven't been crawled.
To populate/refresh the cache, run `make fetch-tesla-supercharger-pricing`,
which mints fresh cookies, smoke-tests them, and walks the bulk index +
per-slug detail. (For a cache-aware locations-only re-fetch use
`make poll-raw SOURCE=tesla-locations`.) See `README_PRICING.md` for
cookie details.

## Refresh POI data

Two paths, picked by where you want to land:

- **`make poll-raw`** — interactive fzf picker over every fetcher. Runs
  the chosen one and prints the `data/raw/<source>/<ts>.json` it wrote.
  Append `SOURCE=<name>` to skip the picker, `SOURCE=--all` to run every
  source in registry order, `SOURCE=--list` for the JSON registry.
- **`make data-fetch` then `make data-import`** — same fetchers, run via
  the backend's admin API so they're recorded in `ingest_runs` and
  serialized by per-target mutex. Use this for production-shaped runs
  (Tilt buttons trigger the same path).

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

Source registry lives at `backend/src/main/resources/poi-registry.yaml`. Run `make poll-raw
SOURCE=--list` for the current set; abridged:

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
args. Reservation-provider dispatch uses `pois.source` plus `provider_ref`
JSON rather than a provider FK.

**Raw cache.** `data/raw/` is gitignored — captures are append-only on
the host running the poller. Crawling Aspira/Tesla is expensive (Azure
WAF, curl-impersonate + cookie injection); replaying raw is free.
Recovery on a fresh checkout: re-run the fetchers, or run
`scripts/_migrate_tesla_cache.py` to bootstrap Tesla from the legacy
`data/pricing-cache/` if it's still around.

**Curated repo data** (no fetch step) lives at `data/curated/`:
`parks-canada-{bc,ab}.json`, `alberta-provincial.json`. The Kotlin
importer reads these directly. New POIs should come from a poller, not
from new entries here — these files are tech debt to retire as fetchers
land for each gap (Alberta now covered by the ReserveAmerica fetcher).

### `/api/docs` — interactive API browser

Swagger UI at `/api/docs`, OpenAPI 3.1 spec at `/api/docs/openapi.json`.
Built from the live routing tree at boot, so the doc reflects whatever's
mounted. To document a new route, replace the `io.ktor.server.routing.{get,post}`
import with `io.github.smiley4.ktorswaggerui.dsl.routing.{get,post}` and pass
a doc block:

```kotlin
get("/api/foo", {
    tags = listOf("group")
    summary = "One-line description"
}) { /* handler */ }
```

Routes without a doc block still appear in the spec (untitled). The page is
public — paths and summaries only, no secrets.

### Admin API surface

| Verb | Path | Returns |
|------|------|---------|
| POST | `/api/admin/data/fetch[/{target}]` | sync; runs Python fetcher phase(s). 200 on success, 500 + `failed_phase` on phase failure |
| POST | `/api/admin/data/import[/{target}]` | sync; runs the Kotlin importer phase(s). 200 on success/noop, 500 on phase failure |
| GET  | `/api/admin/data/runs[?target=…]` | last 50 parent runs |
| GET  | `/api/admin/data/runs/{id}` | parent + ordered phase rows |
| GET  | `/api/admin/data/status` | per-target last completed status + age |

Without a `{target}`, fetch and import fan out across every known target
sequentially. Per-target mutex means a fetch and an import on the same
target serialize.

**Auth boundary:** Cloudflare Zero Trust path rule on `/api/admin/*` — same
tunnel that already fronts the deploy. Workload is idempotent +
non-sensitive (refresh trigger + status read). No in-app token. Locally the
routes are reachable on `127.0.0.1:8765` directly. **If you ever expose dev
to the public internet (port-forward, ngrok, etc.), bind admin routes to
loopback only first.**

The admin API only runs on hosts where `data/` is writable. In the Compose
stack, the backend container mounts `./data` read-write at `/app/static/data`
and mounts `./scripts` read-only so the admin fetch/import buttons run inside
the backend container and write raw captures back to the checkout.

## Deploy via Docker + Cloudflare tunnel

1. **Create a Cloudflare tunnel.** Zero Trust → Networks → Tunnels → Create
   tunnel; set the public hostname to route to `http://backend:8765`. Copy the
   tunnel token. The tunnel's public hostname routing is managed in Cloudflare;
   Compose only starts `cloudflared` with the token.

2. **`.env` on the deploy host:** Docker Compose reads runtime config from the
   checkout's `.env` when GitHub Deploy or a manual deploy runs
   `make run env=prod`:
   ```
   TESLA_COOKIES=ak_bmsc=...; _abck=...; bm_sz=...; ...
   CLOUDFLARE_TUNNEL_TOKEN=eyJhIjoi...
   POSTGRES_PASSWORD=<strong password>
   GRAFANA_ADMIN_PASSWORD=<strong password>
   GRAFANA_DB_PASSWORD=<strong password>
   ```

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

3. **Bring up the stack:** on the deploy host, `make run env=prod` builds the
   backend image and rolls it out with `docker compose up -d` (Postgres and the
   other long-running services stay up). The deploy is wired to GHA
   (push to master → .github/workflows/deploy.yml), so you usually don't run
   this by hand. The older `make deploy` SSH wrapper has been removed.

   The `backend` container serves the map on port 8765 (not exposed to the
   public host — cloudflared talks to it on the compose network). A
   `cookie-bot` service sits under a profile for future use (see
   cookie-bot/README notes in the code) — currently disabled because no
   aarch64 Chromium build passes Akamai's TLS fingerprint gate on the mini.

4. **Pricing cache** persists in `$HOME/.roadtrip-map/pricing-cache`
   (override with `CACHE_DIR=…` in `.env`).

### Heads up: pricing cookies are IP-bound

Tesla's `_abck` cookie is pinned to the IP that received it. Cookies pasted
from your laptop browser will work from the Docker host **only if the Docker
host egresses from the same public IP** — i.e., same home network. If the
Docker host is elsewhere, either grab cookies from a browser *on* that
network, or have your laptop egress through the host's IP via Tailscale
exit node before running `make fetch-tesla-supercharger-pricing`.
Production hosts mint their own cookies out-of-band.

Cookies expire every day or so. When pricing starts returning 403 or 429,
re-run `make fetch-tesla-supercharger-pricing` — its loop will mint fresh
ones automatically.

## Architecture notes

- **Backend.** Kotlin/Ktor + Netty serves the entire site: `/` →
  `index.html`, `/web/*` and `/data/*` → static (with `/data/pricing-cache/*`
  excluded so it's only reachable through `/api/pricing/{slug}`), plus
  `/api/pois`, `/api/pricing/{slug}`, `/api/health`. Postgres+PostGIS holds
  the imported POI data; Supercharger geometry is live from
  supercharge.info/service/supercharge/allSites.
- **Campsite alert sub-app.** A separate recreation.gov polling/booking tool
  is mounted at `/campsite/` (UI served from the JAR's classpath at
  `backend/src/main/resources/static/campsite/`) with its own API surface
  under `/api/campsite/*` (alerts, matches, settings, status, events SSE,
  poll, companion, campgrounds/search). Shares the same Postgres instance;
  Flyway migrates both schemas on startup.
- **Pricing cache.** `/api/pricing/{slug}` is read-only against
  `data/pricing-cache/{slug}.json`. Misses return 404 with
  `{"error":"not_cached"}`. Cache is populated offline by
  `scripts/fetch_tesla_index.py` + `scripts/fetch_tesla_locations.py` (run via `make fetch-tesla-supercharger-pricing`),
  which shells out to `curl-impersonate` because Akamai fingerprints TLS
  ClientHello + HTTP/2 SETTINGS — stock OpenSSL curl gets 403.
- **Map** — MapLibre GL, vector and raster basemaps, runtime style-swap.
  Overlay data is cached in memory and re-installed on every `style.load`
  so basemap swaps don't wipe POIs.

## Campsite alert tool (`/campsite/`)

The campsite sub-app polls recreation.gov for matching availability against
operator-defined alerts and (optionally) auto-claims matches by adding them
to a real recreation.gov shopping cart. **The cart-add path requires a
separate companion process** — recreation.gov sits behind Akamai, which
flags datacenter IPs and headless Chromium, so a real Chromium running on
the operator's machine is the only thing that lands cart adds reliably. The
backend never touches a browser; it only polls the public availability API,
queues authenticated dispatches for the companion, and tracks lease state.

- **`companion/`** — Node 22.9+ Playwright client. Claims backend dispatches via
  `POST /api/dispatches/claim`, drives Chromium to add the site to the
  operator's rec.gov cart, and reports completion or failure to
  `/api/dispatches/{id}/complete` or `/api/dispatches/{id}/fail`. Set
  `DISPATCH_COMPANION_TOKEN` in the repo `.env`; `npm start` loads it for the
  companion, and the Tilt/Compose backend reads the same file. Exported env
  vars still win for one-off overrides.
  ```sh
  cd companion
  npm install
  BACKEND_URL=http://127.0.0.1:8765 npm start
  ```
- **Recreation.gov login** happens in the companion's persistent Chromium
  profile. Run the companion headed, log in to recreation.gov in that window
  once, and the companion manages `localStorage.recaccount` and refreshes in
  that same browser context. The backend never receives the Recreation.gov JWT;
  it only queues dispatches and records completion/failure.
- **Slack notifications** are optional. Create a Slack app with the
  `chat:write` scope, install it to the workspace, and paste the bot
  token (`xoxb-…`) plus a channel name (`#camping-alerts`) or channel ID
  into Settings → Slack. The backend posts via `chat.postMessage`.
- **Without the companion**, alerts still fire and Slack still posts —
  every "Auto-add to cart" toggle and the "Test browser session" /
  "Test credentials" buttons in Settings just no-op (`SettingsRoutes`
  returns `not_implemented` for the Chromium-dependent endpoints).
